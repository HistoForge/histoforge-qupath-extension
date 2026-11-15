package qupath.ext.histoforge.large;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec.ParentScope;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec.CoreHaloProfile;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputType;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;

/** Cross-record identity, transition, and recovery-reference validation for a large action. */
final class LargeActionHandleValidator {

    private LargeActionHandleValidator() {}

    /** Complete validated request-file projection retained only in this process. */
    record ValidatedRecoveryReferences(
            Optional<SourceArtifactCodec.SourceArtifactStartRequest> sourceStartRequest,
            Map<String, LargeActionHandleStore.AuthorizedModelInput> inputs,
            List<LargeActionHandleStore.AuthorizedModelRun> runs) {
        ValidatedRecoveryReferences {
            sourceStartRequest = Objects.requireNonNull(sourceStartRequest);
            inputs = Map.copyOf(inputs);
            runs = List.copyOf(runs);
        }
    }

    static void requireRunningBuild(
            LargeActionHandle handle, BuildIdentityCodec.ClientBuildIdentity runningBuild) {
        if (!handle.clientBuild().equals(runningBuild)) {
            throw new IllegalStateException(
                    "large-action mutation requires the exact running client build");
        }
    }

    static void requireSameHandle(LargeActionHandle current, LargeActionHandle next) {
        if (current.schemaVersion() != next.schemaVersion()
                || !current.actionId().equals(next.actionId())
                || !current.handleGenerationId().equals(next.handleGenerationId())
                || !current.clientBuild().equals(next.clientBuild())
                || !current.imageId().equals(next.imageId())
                || !current.module().equals(next.module())
                || !current.action().equals(next.action())
                || !current.target().equals(next.target())
                || !current.selectionRegions().equals(next.selectionRegions())
                || !current.baseline().equals(next.baseline())
                || !current.sourcePlan().equals(next.sourcePlan())
                || !current.createdAt().equals(next.createdAt())) {
            throw new IllegalArgumentException("large-action immutable identity changed");
        }
    }

    static void requireTransition(LargeActionHandle current, LargeActionHandle next) {
        if (next.updatedAt().isBefore(current.updatedAt())) {
            throw new IllegalArgumentException("large-action updated_at moved backward");
        }
        requireRecoveryContinuity(current, next);
        if (current.phase().terminal()) {
            if (!current.equals(next)) {
                throw new IllegalStateException("terminal large-action handle cannot transition");
            }
            return;
        }
        boolean allowed =
                switch (current.phase()) {
                    case PREPARING ->
                            next.phase() == LargeActionPhase.PREPARING
                                    || next.phase() == LargeActionPhase.UPLOADING
                                    || next.phase() == LargeActionPhase.EXECUTING;
                    case UPLOADING ->
                            next.phase() == LargeActionPhase.UPLOADING
                                    || next.phase() == LargeActionPhase.EXECUTING;
                    case EXECUTING ->
                            next.phase() == LargeActionPhase.EXECUTING
                                    || next.phase() == LargeActionPhase.UPLOADING
                                    || next.phase() == LargeActionPhase.RESULT_READY;
                    case RESULT_READY -> next.phase() == LargeActionPhase.RESULT_READY;
                    default -> false;
                };
        boolean terminalAllowed =
                next.phase() == LargeActionPhase.CANCELLED
                        || next.phase() == LargeActionPhase.FAILED
                        || next.phase() == LargeActionPhase.CONFLICT
                        || (current.phase() == LargeActionPhase.RESULT_READY
                                && (next.phase() == LargeActionPhase.COMMITTED
                                        || next.phase() == LargeActionPhase.SUPERSEDED));
        if (!allowed && !terminalAllowed) {
            throw new IllegalArgumentException(
                    "unsupported large-action phase transition "
                            + current.phase()
                            + " -> "
                            + next.phase());
        }
    }

    static void requireRequestPlanContinuity(
            LargeActionHandle current, LargeActionHandle next, RecordedFileReader files)
            throws IOException {
        if (artifactReplaced(current, next)) {
            return;
        }
        for (var currentStep : current.steps()) {
            var retained =
                    next.steps().stream()
                            .filter(step -> step.stepId().equals(currentStep.stepId()))
                            .findFirst()
                            .orElseThrow();
            for (int index = 0; index < currentStep.runs().size(); index++) {
                var currentRun = currentStep.runs().get(index);
                var retainedRun = retained.runs().get(index);
                var currentRequest = readModelRunRequest(currentRun.requestFile(), files);
                var retainedRequest = readModelRunRequest(retainedRun.requestFile(), files);
                boolean sameRun = currentRun.modelRunId().equals(retainedRun.modelRunId());
                RunImageSourceValidator.requireContinuity(currentRun, retainedRun);
                if (sameRun
                        ? !currentRequest.equals(retainedRequest)
                        : !Arrays.equals(
                                ModelRunRequestCodec.replacementPlanBytes(currentRequest),
                                ModelRunRequestCodec.replacementPlanBytes(retainedRequest))) {
                    throw recoveryConflict(
                            sameRun
                                    ? "large-action retained run request cannot change"
                                    : "large-action replacement run plan cannot change");
                }
                if (!sameRun && retainedRequest.resumeFrom().isPresent()) {
                    ModelRunRequestCodec.ModelRunResumeSource source =
                            retainedRequest.resumeFrom().orElseThrow();
                    if (!source.previousRunId().equals(currentRun.modelRunId())
                            || currentRun.resultManifestDigest().isEmpty()
                            || !source.previousManifestDigest()
                                    .equals(currentRun.resultManifestDigest().orElseThrow())) {
                        throw recoveryConflict(
                                "large-action replacement resume source must name the retained"
                                        + " prior manifest");
                    }
                }
                if (sameRun
                        && currentRun.resultManifestDigest().isPresent()
                        && !currentRun
                                .resultManifestDigest()
                                .equals(retainedRun.resultManifestDigest())) {
                    throw recoveryConflict("large-action observed run manifest cannot change");
                }
                if (sameRun && currentRun.completeResultFiles().isPresent()) {
                    var oldFiles = currentRun.completeResultFiles().orElseThrow();
                    var newFiles =
                            retainedRun
                                    .completeResultFiles()
                                    .orElseThrow(
                                            () ->
                                                    recoveryConflict(
                                                            "complete result references cannot be discarded"));
                    if (!oldFiles.manifestArtifact().equals(newFiles.manifestArtifact())
                            || !oldFiles.manifestFile().equals(newFiles.manifestFile())
                            || newFiles.featureFiles().size() < oldFiles.featureFiles().size()
                            || !oldFiles.featureFiles()
                                    .equals(
                                            newFiles.featureFiles()
                                                    .subList(0, oldFiles.featureFiles().size()))) {
                        throw recoveryConflict(
                                "complete result references must retain their published prefix");
                    }
                }
            }
        }
    }

    static ValidatedRecoveryReferences validateRecoveryReferences(
            LargeActionHandle handle, RecordedFileReader files) throws IOException {
        Optional<SourceArtifactCodec.SourceArtifactStartRequest> sourceStart = Optional.empty();
        if (handle.source().isPresent()) {
            var source = handle.source().orElseThrow();
            byte[] startBytes = files.read(source.startRequestFile());
            final SourceArtifactCodec.SourceArtifactStartRequest start;
            try {
                start = SourceArtifactCodec.parseStartRequest(CanonicalCodec.parseJson(startBytes));
                if (!Arrays.equals(
                        startBytes,
                        CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start)))) {
                    throw new IllegalArgumentException("source start request is not canonical");
                }
            } catch (IllegalArgumentException error) {
                throw corruptRecovery("source start request is invalid", error);
            }
            try {
                handle.sourcePlan().requireMatchingStart(start);
            } catch (IllegalArgumentException error) {
                throw recoveryConflict("source upload differs from its captured plan", error);
            }
            if (!SourceArtifactCodec.startRequestDigest(start)
                    .equals(source.startRequestFile().sha256())) {
                throw corruptRecovery("source start request digest does not match its file record");
            }
            if (!start.artifactId().equals(source.artifactId())
                    || (source.sealedArtifact().isEmpty()
                            && !start.creationActionId().equals(handle.actionId()))) {
                throw recoveryConflict("source start request does not match its handle");
            }
            if (source.sealedArtifact().isPresent()) {
                var sealed = source.sealedArtifact().orElseThrow();
                if (!sealed.creationActionId().equals(start.creationActionId())
                        || sealed.container() != start.container()) {
                    throw recoveryConflict("sealed source does not match its start request");
                }
            }
            var originSource =
                    switch (start.origin()) {
                        case SourceArtifactCodec.OriginalWsi original -> original.source();
                        case SourceArtifactCodec.DerivedRegion derived -> derived.source();
                    };
            if (!originSource.equals(handle.baseline().pixelSource())) {
                throw recoveryConflict(
                        "source start request does not match the captured pixel source");
            }
            if (start.origin() instanceof SourceArtifactCodec.DerivedRegion derived
                    && (!derived.plane().equals(handle.target().plane())
                            || !derived.writerBuild().equals(handle.clientBuild().extension()))) {
                throw recoveryConflict(
                        "derived source origin does not match its target and writer");
            }

            boolean derived = start.origin() instanceof SourceArtifactCodec.DerivedRegion;
            boolean packaged =
                    start.container() == SourceArtifactCodec.SourceContainer.SLIDE_FILE_SET;
            boolean ownsBytes = source.ownedUploadFile().isPresent();
            if (derived && !ownsBytes
                    || packaged && source.sealedArtifact().isEmpty() && !ownsBytes
                    || !derived && !packaged && ownsBytes) {
                throw recoveryConflict(
                        "source representation does not match its owned upload record");
            }
            if (handle.module().activatedModels().values().stream()
                    .anyMatch(
                            model ->
                                    !model.capabilities()
                                            .sourceContainers()
                                            .contains(start.container()))) {
                throw recoveryConflict("source container is not accepted by every activated model");
            }
            if (source.ownedUploadFile().isPresent()) {
                LocalFileRecord file = source.ownedUploadFile().orElseThrow();
                if (file.sizeBytes() != start.totalBytes()) {
                    throw recoveryConflict("owned upload length does not match its start request");
                }
                if (source.sealedArtifact().isPresent()
                        && !file.sha256()
                                .equals(source.sealedArtifact().orElseThrow().bytesDigest())) {
                    throw recoveryConflict(
                            "owned upload digest does not match its sealed artifact");
                }
            }
            sourceStart = Optional.of(start);
        }

        Map<String, LargeActionHandleStore.AuthorizedModelInput> authorizedInputs =
                new LinkedHashMap<>();
        List<LargeActionHandleStore.AuthorizedModelRun> authorizedRuns = new ArrayList<>();
        for (var step : handle.steps()) {
            for (var run : step.runs()) {
                if (run.completeResultFiles().isPresent()) {
                    var retained = run.completeResultFiles().orElseThrow();
                    var expected =
                            RecoveryResultFiles.record(
                                    handle, run.modelRunId(), retained.manifestArtifact());
                    String prefix =
                            RecoveryResultFiles.directory(handle) + "/" + run.modelRunId() + "/";
                    if (!retained.manifestFile().equals(expected)
                            || retained.featureFiles().stream()
                                    .anyMatch(
                                            file ->
                                                    !file.projectRelativePosixPath()
                                                                    .startsWith(prefix)
                                                            || file.projectRelativePosixPath()
                                                                    .substring(prefix.length())
                                                                    .contains("/"))) {
                        throw recoveryConflict(
                                "result files must belong to their exact action and model run");
                    }
                }
                ModelRunRequestCodec.ModelRunRequest request =
                        readModelRunRequest(run.requestFile(), files);
                var model = handle.module().activatedModels().get(step.stepId());
                if (!request.actionId().equals(handle.actionId())
                        || !request.modelRunId().equals(run.modelRunId())
                        || !request.producer().stepId().equals(step.stepId())
                        || !request.producer()
                                .moduleRef()
                                .equals(handle.module().module().moduleRef())
                        || !request.expectedBackend()
                                .equals(handle.module().backendIdentity(step.stepId()))) {
                    throw recoveryConflict("model-run request does not match its handle step");
                }
                try {
                    ModelRunRequestCodec.validatePlanFamily(
                            request,
                            model.capabilities().finite().targetRaster(),
                            model.largeRegionProfile());
                    // Finite shards omit the setting; core/halo shards retain action capture.
                    Optional<Integer> capturedHalo =
                            request.expectedProfile()
                                    .filter(CoreHaloProfile.class::isInstance)
                                    .flatMap(ignored -> model.haloPxOverride());
                    if (!request.haloPxOverride().equals(capturedHalo)) {
                        throw new IllegalArgumentException(
                                "model-run halo override differs from captured action");
                    }
                    ModelRunRequestCodec.validateResourceLimits(
                            request,
                            model.capabilities()
                                    .durableRun()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "large action requires durable-run"
                                                                    + " limits"))
                                    .run());
                    RunImageSourceValidator.validate(handle, run, request, files);
                } catch (IllegalArgumentException error) {
                    throw recoveryConflict(
                            "model-run request violates captured runner capabilities", error);
                }
                Optional<BackendExecutionCodec.DurableRun> execution =
                        run.resultManifestDigest()
                                .map(
                                        digest ->
                                                new BackendExecutionCodec.DurableRun(
                                                        run.modelRunId(), digest));
                authorizedRuns.add(
                        new LargeActionHandleStore.AuthorizedModelRun(
                                step.stepId(), run.shardIndex(), request, execution));
                for (var input : request.inputs()) {
                    var rasterInput = input.rasterInput();
                    var frame = rasterInput.frame();
                    boolean crop = model.input().type() == ModelInputType.CROP;
                    if (Double.compare(
                                    frame.transform().raster().targetMpp(),
                                    model.input().targetMpp())
                            != 0) {
                        throw recoveryConflict(
                                "model-run input target MPP does not match its captured model");
                    }
                    if (crop != (input.output().scope() instanceof ParentScope)) {
                        throw recoveryConflict(
                                "model-run output scope does not match its captured input type");
                    }
                    boolean sameSource =
                            handle.sourcePlan() instanceof IncrementalSourcePlan captured
                                    ? rasterInput.sourceView()
                                                    instanceof SourceCaptureCodec.View view
                                            && view.capture()
                                                    .equals(
                                                            captured.capture(
                                                                    handle.baseline().pixelSource(),
                                                                    handle.clientBuild()
                                                                            .extension()))
                                    : rasterInput.sourceView()
                                                    instanceof RasterInputCodec.SourceView sealed
                                            && handle.source()
                                                    .flatMap(
                                                            LargeActionTypes.SourceUploadRef
                                                                    ::sealedArtifact)
                                                    .filter(sealed.artifact()::equals)
                                                    .isPresent();
                    if (!sameSource) {
                        throw recoveryConflict(
                                "model-run request references another source artifact");
                    }
                    if (!frame.region().equals(handle.target().key())
                            || !frame.transform().raster().plane().equals(handle.target().plane())
                            || !frame.sourceRevision()
                                    .imageDataRevision()
                                    .imageId()
                                    .equals(handle.imageId())
                            || !frame.sourceRevision()
                                    .pixelSource()
                                    .equals(handle.baseline().pixelSource())) {
                        throw recoveryConflict(
                                "model-run input does not match its handle target and source");
                    }
                    if (authorizedInputs.putIfAbsent(
                                    input.outputGenerationId(),
                                    new LargeActionHandleStore.AuthorizedModelInput(
                                            input, request.expectedBackend(), execution))
                            != null) {
                        throw recoveryConflict(
                                "large-action requests reuse an output-generation ID");
                    }
                }
            }
        }
        return new ValidatedRecoveryReferences(
                sourceStart, Map.copyOf(authorizedInputs), List.copyOf(authorizedRuns));
    }

    static void requireCompleteModelWork(
            LargeActionHandle handle,
            Map<String, LargeActionHandleStore.AuthorizedModelInput> authorizedInputs) {
        if (handle.phase() != LargeActionPhase.EXECUTING
                && handle.phase() != LargeActionPhase.RESULT_READY) {
            throw new IllegalStateException(
                    "large-action commit requires EXECUTING or RESULT_READY");
        }
        var expectedSteps =
                handle.module().recipe().steps().stream()
                        .filter(qupath.ext.histoforge.modules.ModuleRecipe.Step::isModelStep)
                        .map(qupath.ext.histoforge.modules.ModuleRecipe.Step::model)
                        .filter(handle.module().activatedModels()::containsKey)
                        .toList();
        if (!handle.steps().stream()
                        .map(LargeActionTypes.StepRunRef::stepId)
                        .toList()
                        .equals(expectedSteps)
                || handle.steps().stream()
                        .flatMap(step -> step.runs().stream())
                        .anyMatch(run -> run.resultManifestDigest().isEmpty())
                || authorizedInputs.values().stream()
                        .anyMatch(input -> input.execution().isEmpty())) {
            throw new IllegalStateException(
                    "large-action commit requires every activated model terminal reference");
        }
    }

    private static ModelRunRequestCodec.ModelRunRequest readModelRunRequest(
            LocalFileRecord file, RecordedFileReader files) throws IOException {
        byte[] requestBytes = files.read(file);
        try {
            ModelRunRequestCodec.ModelRunRequest request =
                    ModelRunRequestCodec.parseRequest(CanonicalCodec.parseJson(requestBytes));
            if (!Arrays.equals(
                    requestBytes,
                    CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(request)))) {
                throw new IllegalArgumentException("model-run request is not canonical");
            }
            return request;
        } catch (IllegalArgumentException error) {
            throw corruptRecovery("model-run request is invalid", error);
        }
    }

    private static LargeActionHandleStore.UnrecoverableRecoveryStateException corruptRecovery(
            String message) {
        return new LargeActionHandleStore.UnrecoverableRecoveryStateException(message);
    }

    private static LargeActionHandleStore.UnrecoverableRecoveryStateException corruptRecovery(
            String message, Throwable cause) {
        return new LargeActionHandleStore.UnrecoverableRecoveryStateException(message, cause);
    }

    private static LargeActionHandleStore.RecoveryReferenceConflictException recoveryConflict(
            String message) {
        return new LargeActionHandleStore.RecoveryReferenceConflictException(message);
    }

    private static LargeActionHandleStore.RecoveryReferenceConflictException recoveryConflict(
            String message, Throwable cause) {
        return new LargeActionHandleStore.RecoveryReferenceConflictException(message, cause);
    }

    private static void requireRecoveryContinuity(
            LargeActionHandle current, LargeActionHandle next) {
        if (current.hasRetainedModelRunRequest() && !current.source().equals(next.source())) {
            throw new IllegalArgumentException(
                    "large-action source cannot change after model work is retained");
        }
        boolean artifactReplaced = artifactReplaced(current, next);
        if (artifactReplaced
                && (next.phase() != LargeActionPhase.UPLOADING || !next.steps().isEmpty())) {
            throw new IllegalArgumentException(
                    "source replacement must restart model work from zero");
        }
        if (!artifactReplaced) {
            for (var currentStep : current.steps()) {
                var retained =
                        next.steps().stream()
                                .filter(step -> step.stepId().equals(currentStep.stepId()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "large-action cannot discard a retained"
                                                                + " step"));
                if (!currentStep.inputPlanDigest().equals(retained.inputPlanDigest())
                        || !currentStep.parentPlanDigest().equals(retained.parentPlanDigest())
                        || currentStep.runs().size() != retained.runs().size()) {
                    throw new IllegalArgumentException(
                            "large-action retained step plan identity cannot change");
                }
            }
        }
        if (current.expectedCommittedHierarchy().isPresent()
                && !current.expectedCommittedHierarchy()
                        .equals(next.expectedCommittedHierarchy())) {
            throw new IllegalArgumentException(
                    "large-action expected hierarchy identity cannot change");
        }
        if (current.phase() == LargeActionPhase.RESULT_READY
                && (!current.source().equals(next.source())
                        || !current.steps().equals(next.steps())
                        || !current.expectedCommittedHierarchy()
                                .equals(next.expectedCommittedHierarchy()))) {
            throw new IllegalArgumentException("RESULT_READY recovery references cannot change");
        }
        if (next.phase().terminal()
                && (!current.source().equals(next.source())
                        || !current.steps().equals(next.steps())
                        || !current.expectedCommittedHierarchy()
                                .equals(next.expectedCommittedHierarchy()))) {
            throw new IllegalArgumentException(
                    "terminal transition must retain the current recovery references");
        }
    }

    private static boolean artifactReplaced(LargeActionHandle current, LargeActionHandle next) {
        return current.source().isPresent()
                && next.source().isPresent()
                && !current.source()
                        .orElseThrow()
                        .artifactId()
                        .equals(next.source().orElseThrow().artifactId());
    }

    @FunctionalInterface
    interface RecordedFileReader {
        byte[] read(LocalFileRecord file) throws IOException;
    }
}

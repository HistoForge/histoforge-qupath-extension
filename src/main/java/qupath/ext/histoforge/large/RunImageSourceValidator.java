package qupath.ext.histoforge.large;

import java.io.IOException;
import java.util.Arrays;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ModelRunSourceCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;

/** Checks retained unit images against their canonical request and the prior saved handle. */
final class RunImageSourceValidator {
    private RunImageSourceValidator() {}

    static void requireContinuity(
            LargeActionTypes.ModelRunRef previous, LargeActionTypes.ModelRunRef next) {
        if (previous.imageSources().isPresent() != next.imageSources().isPresent()) {
            throw new IllegalArgumentException(
                    "a retained run cannot change its image-delivery mode");
        }
        if (previous.imageSources().isEmpty()) return;
        var oldImages = previous.imageSources().orElseThrow();
        var newImages = next.imageSources().orElseThrow();
        boolean sameRun = previous.modelRunId().equals(next.modelRunId());
        if (sameRun
                && oldImages.failureReport().isPresent()
                && !oldImages.failureReport().equals(newImages.failureReport())) {
            throw new IllegalArgumentException(
                    "a recorded image failure cannot change within its run");
        }
        for (var old : oldImages.uploads()) {
            var retained =
                    newImages.uploads().stream()
                            .filter(
                                    upload ->
                                            upload.inputId() == old.inputId()
                                                    && upload.unitId() == old.unitId())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "a retained unit image cannot be discarded"));
            if (old.bindingAccepted() && !old.equals(retained)) {
                throw new IllegalArgumentException(
                        "a previously accepted image cannot be replaced");
            }
            if (old.source().artifactId().equals(retained.source().artifactId())
                    && (!old.source()
                                    .startRequestFile()
                                    .equals(retained.source().startRequestFile())
                            || !old.source()
                                    .ownedUploadFile()
                                    .equals(retained.source().ownedUploadFile())
                            || old.source().sealedArtifact().isPresent()
                                    && !old.source()
                                            .sealedArtifact()
                                            .equals(retained.source().sealedArtifact()))) {
                throw new IllegalArgumentException(
                        "an existing upload cannot change its source bytes or sealed identity");
            }
        }
    }

    static void validate(
            LargeActionTypes.LargeActionHandle handle,
            LargeActionTypes.ModelRunRef run,
            ModelRunRequestCodec.ModelRunRequest request,
            LargeActionHandleValidator.RecordedFileReader files)
            throws IOException {
        boolean incremental = handle.sourcePlan() instanceof IncrementalSourcePlan;
        if (run.imageSources().isPresent() != incremental) {
            throw new IllegalArgumentException(
                    "incremental requests must retain their image-delivery records");
        }
        if (!incremental) return;
        var model = handle.module().activatedModels().get(request.producer().stepId());
        var plans =
                ModelRunPublicationCodec.buildExpectedPlans(
                        request, model.capabilities().durableRun().orElseThrow().run());
        for (var upload : run.imageSources().orElseThrow().uploads()) {
            var source = upload.source();
            byte[] bytes = files.read(source.startRequestFile());
            var start = SourceArtifactCodec.parseStartRequest(CanonicalCodec.parseJson(bytes));
            if (!Arrays.equals(
                    bytes, CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start)))) {
                throw new IllegalArgumentException("bounded image start request must be canonical");
            }
            var input =
                    request.inputs().stream()
                            .filter(value -> value.inputId() == upload.inputId())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "image input is absent from its run"));
            if (!(input.rasterInput().sourceView() instanceof SourceCaptureCodec.View view)
                    || !(start.origin() instanceof SourceArtifactCodec.DerivedRegion origin)
                    || !start.creationActionId().equals(handle.actionId())
                    || !start.artifactId().equals(source.artifactId())
                    || !origin.equals(
                            SourceCaptureCodec.imageOrigin(
                                    view.capture(), origin.sourceRectAtLevel()))
                    || !model.capabilities().sourceContainers().contains(start.container())) {
                throw new IllegalArgumentException(
                        "bounded image differs from its captured source and model");
            }
            var rectangle = origin.sourceRectAtLevel();
            var expectedLevel =
                    new SourceArtifactCodec.SourceLevelRequirement(
                            0, 0, rectangle.width(), rectangle.height());
            if (!start.requiredLevels().equals(java.util.List.of(expectedLevel))) {
                throw new IllegalArgumentException(
                        "bounded image must have one exact lossless level");
            }
            var processing =
                    ModelRunSourceCodec.processingRectangle(
                            input.rasterInput(), plans.get(upload.inputId()), upload.unitId());
            SourceViewGeometry.requiredCapturedSourcePixelRect(
                            view, input.rasterInput().frame().transform(), processing)
                    .ifPresent(
                            required -> SourceCaptureCodec.requireContained(required, rectangle));
            var file = source.ownedUploadFile().orElseThrow();
            if (file.sizeBytes() != start.totalBytes()) {
                throw new IllegalArgumentException(
                        "bounded image length differs from its start request");
            }
            if (source.sealedArtifact().isPresent()) {
                var sealed = source.sealedArtifact().orElseThrow();
                if (!sealed.bytesDigest().equals(file.sha256())
                        || !sealed.creationActionId().equals(start.creationActionId())
                        || sealed.container() != start.container()) {
                    throw new IllegalArgumentException(
                            "bounded image differs from its sealed identity");
                }
            }
        }
    }
}

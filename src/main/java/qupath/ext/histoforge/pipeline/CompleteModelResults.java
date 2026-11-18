package qupath.ext.histoforge.pipeline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qupath.ext.histoforge.client.DurableModelRunExecutor.CompleteResultReader;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelResultReader;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.FeatureStreamDecoder;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec.Manifest;
import qupath.ext.histoforge.contract.ModelRunRequestCodec.ModelRunRequest;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes.CompleteResultFiles;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;
import qupath.ext.histoforge.large.RecoveryResultFiles;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;

/** Retains complete model predictions before a recipe advances, and validates them on replay. */
final class CompleteModelResults {
    private CompleteModelResults() {}

    /** Validated counts and exact local bytes, not another in-memory polygon collection. */
    record RetainedFeatures(
            RecoveryResultFiles files,
            LocalFileRecord file,
            FeatureStreamDecoder.ValidatedFeatureStream validation) {
        RetainedFeatures {
            files = Objects.requireNonNull(files, "files");
            file = Objects.requireNonNull(file, "file");
            validation = Objects.requireNonNull(validation, "validation");
        }

        void read(Consumer<InferenceFeatureCodec.Feature> consumer, BooleanSupplier shouldStop)
                throws IOException {
            requireRunning(shouldStop);
            var found =
                    files.read(
                            file,
                            stream -> {
                                FeatureStreamDecoder.readValidated(
                                        stream, validation, consumer, shouldStop);
                                return true;
                            });
            if (found.isEmpty()) {
                throw new IOException(
                        "Retained prediction file is missing or changed; reconnect to the analysis"
                                + " to repair it");
            }
        }
    }

    static Map<Integer, RetainedFeatures> retainedInputs(
            LargeActionHandle handle,
            Manifest manifest,
            Map<Integer, FeatureStreamDecoder.ValidatedFeatureStream> validated,
            RecoveryResultFiles files) {
        Map<Integer, RetainedFeatures> inputs = new LinkedHashMap<>();
        for (var input : manifest.completedInputs()) {
            inputs.put(
                    input.inputId(),
                    new RetainedFeatures(
                            files,
                            RecoveryResultFiles.record(
                                    handle, manifest.modelRunId(), input.featureStreamArtifact()),
                            validated.get(input.inputId())));
        }
        if (!inputs.keySet().equals(validated.keySet())) {
            throw new IllegalArgumentException(
                    "Validated inputs differ from the complete manifest");
        }
        return Map.copyOf(inputs);
    }

    static CompleteResultReader retaining(
            DurableStepJournal journal,
            String stepId,
            int shardIndex,
            ModelRunRequest request,
            ModelSnapshot model,
            BooleanSupplier shouldStop) {
        return (artifact, manifest, downloads) -> {
            requireRunning(shouldStop);
            var handle = journal.currentAction().handle();
            var files = journal.resultFiles();
            var manifestFile = RecoveryResultFiles.record(handle, request.modelRunId(), artifact);
            byte[] bytes = CanonicalCodec.canonicalBytes(ModelRunPublicationCodec.toJson(manifest));
            // The serialization must be the exact canonical representation named by the gateway.
            if (bytes.length != artifact.sizeBytes()
                    || !CanonicalCodec.sha256(bytes).equals(artifact.sha256())) {
                throw new IOException("Complete manifest differs from its immutable artifact");
            }
            RecoveryResultFiles.Decoder<Manifest> validate =
                    input -> manifest(input, request, model);
            if (files.read(manifestFile, validate).isEmpty()) {
                files.retain(manifestFile, new ByteArrayInputStream(bytes), validate);
            }
            List<LocalFileRecord> features = featureFiles(handle, manifest);
            var retained = journal.run(stepId, shardIndex).completeResultFiles();
            int alreadyRecorded = retained.map(value -> value.featureFiles().size()).orElse(0);
            requirePrefix(
                    retained.map(CompleteResultFiles::featureFiles).orElse(List.of()), features);
            var initial =
                    new CompleteResultFiles(
                            artifact, manifestFile, features.subList(0, alreadyRecorded));
            if (retained.isPresent() && !retained.orElseThrow().equals(initial)) {
                throw new IOException(
                        "Retained complete-result references differ from the manifest");
            }
            journal.recordCompleteFiles(stepId, shardIndex, manifest.manifestDigest(), initial);
            int[] position = {0};
            return ModelResultReader.read(
                    manifest,
                    request.resultBudget(),
                    (input, remaining) -> {
                        requireRunning(shouldStop);
                        LocalFileRecord file = features.get(position[0]);
                        RecoveryResultFiles.Decoder<FeatureStreamDecoder.ValidatedFeatureStream>
                                decode =
                                        stream ->
                                                validateInput(
                                                        stream,
                                                        remaining,
                                                        Set.copyOf(model.output().emittedClasses()),
                                                        request,
                                                        input,
                                                        shouldStop);
                        var local = files.read(file, decode);
                        var validated =
                                local.isPresent()
                                        ? local.orElseThrow()
                                        : downloads.consume(
                                                input.featureStreamArtifact(),
                                                "retain model input " + input.inputId(),
                                                stream -> files.retain(file, stream, decode));
                        position[0]++;
                        if (position[0] > alreadyRecorded) {
                            journal.recordCompleteFiles(
                                    stepId,
                                    shardIndex,
                                    manifest.manifestDigest(),
                                    new CompleteResultFiles(
                                            artifact,
                                            manifestFile,
                                            features.subList(0, position[0])));
                        }
                        return validated;
                    });
        };
    }

    /**
     * No gateway or mutable module-list access is needed when every exact result file is present.
     */
    static Optional<DurableRecipeModelInputAdapter.RecoveredRun> readRetained(
            LargeActionHandleStore.ResumableAction action,
            LargeActionHandleStore.AuthorizedModelRun run,
            RecoveryResultFiles files,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        var reference =
                action.handle().steps().stream()
                        .filter(step -> step.stepId().equals(run.stepId()))
                        .findFirst()
                        .orElseThrow()
                        .runs()
                        .get(run.shardIndex())
                        .completeResultFiles();
        if (reference.isEmpty()) return Optional.empty();
        var retained = reference.orElseThrow();
        var model = action.handle().module().activatedModels().get(run.stepId());
        requireRunning(shouldStop);
        var localManifest =
                files.read(retained.manifestFile(), input -> manifest(input, run.request(), model));
        if (localManifest.isEmpty()) return Optional.empty();
        Manifest manifest = localManifest.orElseThrow();
        if (!manifest.manifestDigest()
                .equals(run.execution().orElseThrow().resultManifestDigest())) {
            throw new IOException("Retained complete manifest differs from its recorded execution");
        }
        List<LocalFileRecord> expectedFiles = featureFiles(action.handle(), manifest);
        requirePrefix(retained.featureFiles(), expectedFiles);
        try {
            int[] position = {0};
            Map<Integer, FeatureStreamDecoder.ValidatedFeatureStream> validated =
                    ModelResultReader.read(
                            manifest,
                            run.request().resultBudget(),
                            (input, remaining) -> {
                                requireRunning(shouldStop);
                                var found =
                                        files.read(
                                                expectedFiles.get(position[0]++),
                                                stream ->
                                                        validateInput(
                                                                stream,
                                                                remaining,
                                                                Set.copyOf(
                                                                        model.output()
                                                                                .emittedClasses()),
                                                                run.request(),
                                                                input,
                                                                shouldStop));
                                return found.orElseThrow(MissingResultFile::new);
                            });
            return Optional.of(
                    new DurableRecipeModelInputAdapter.RecoveredRun(
                            run,
                            manifest,
                            retainedInputs(action.handle(), manifest, validated, files)));
        } catch (MissingResultFile missing) {
            return Optional.empty();
        }
    }

    /** Adopt exact files published before a crash interrupted the corresponding handle update. */
    static void recordLocalCompletion(
            DurableStepJournal journal, DurableRecipeModelInputAdapter.RecoveredRun recovered)
            throws IOException {
        var run = recovered.authorized();
        var previous =
                journal.run(run.stepId(), run.shardIndex()).completeResultFiles().orElseThrow();
        journal.recordCompleteFiles(
                run.stepId(),
                run.shardIndex(),
                recovered.manifest().manifestDigest(),
                new CompleteResultFiles(
                        previous.manifestArtifact(),
                        previous.manifestFile(),
                        featureFiles(journal.currentAction().handle(), recovered.manifest())));
    }

    private static void requirePrefix(
            List<LocalFileRecord> recorded, List<LocalFileRecord> expected) throws IOException {
        if (recorded.size() > expected.size()
                || !recorded.equals(expected.subList(0, recorded.size()))) {
            throw new IOException("Retained feature references differ from the complete manifest");
        }
    }

    private static FeatureStreamDecoder.ValidatedFeatureStream validateInput(
            InputStream stream,
            qupath.ext.histoforge.contract.FiniteInferenceCodec.ClientResultBudget budget,
            Set<String> declaredClasses,
            ModelRunRequest request,
            ModelRunPublicationCodec.InputResult input,
            BooleanSupplier shouldStop)
            throws IOException {
        var validation =
                FeatureStreamDecoder.validate(
                        stream,
                        budget,
                        declaredClasses,
                        input.featureCount(),
                        input.vertexCount(),
                        shouldStop);
        var requested =
                request.inputs().stream()
                        .filter(value -> value.inputId() == input.inputId())
                        .findFirst()
                        .orElseThrow();
        validation.requireInsideRaster(requested.rasterInput().frame().transform().raster());
        return validation;
    }

    private static Manifest manifest(
            InputStream input, ModelRunRequest request, ModelSnapshot model) throws IOException {
        byte[] bytes =
                input.readNBytes(
                        Math.toIntExact(GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES + 1));
        if (bytes.length > GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES) {
            throw new IOException("Complete manifest exceeds the control-response limit");
        }
        var value = CanonicalCodec.parseJson(bytes);
        if (!Arrays.equals(bytes, CanonicalCodec.canonicalBytes(value)))
            throw new IOException("Manifest is not canonical JSON");
        Manifest manifest =
                ModelRunPublicationCodec.validateManifest(
                        value, request, model.capabilities().durableRun().orElseThrow().run());
        if (!manifest.complete())
            throw new IOException("Partial results cannot be retained as complete model output");
        return manifest;
    }

    private static List<LocalFileRecord> featureFiles(LargeActionHandle handle, Manifest manifest) {
        return manifest.completedInputs().stream()
                .map(
                        input ->
                                RecoveryResultFiles.record(
                                        handle,
                                        manifest.modelRunId(),
                                        input.featureStreamArtifact()))
                .toList();
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean())
            throw new CancellationException("Result retention cancelled");
    }

    private static final class MissingResultFile extends IOException {}
}

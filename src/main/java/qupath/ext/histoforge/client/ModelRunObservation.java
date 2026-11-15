package qupath.ext.histoforge.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.FeatureStreamDecoder;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;

/** Validated active or terminal observation of one durable model run. */
public sealed interface ModelRunObservation
        permits ModelRunObservation.Active, ModelRunObservation.Terminal {

    ModelRunStatusCodec.Status status();

    /** A QUEUED or RUNNING status; no biological output is visible. */
    record Active(ModelRunStatusCodec.Status status) implements ModelRunObservation {
        public Active {
            status = Objects.requireNonNull(status, "status");
            if (status.phase() != ModelRunStatusCodec.Phase.QUEUED
                    && status.phase() != ModelRunStatusCodec.Phase.RUNNING) {
                throw new IllegalArgumentException("active observation requires QUEUED or RUNNING");
            }
        }
    }

    /**
     * A terminal status and validation counts for every completed biological input.
     *
     * <p>PARTIAL retains completed inputs for an explicit replacement run but is not a complete
     * recipe-step result. FAILED, CANCELLED, and TIMED_OUT have no manifest or completed input.
     */
    record Terminal(
            ModelRunStatusCodec.Status status,
            Optional<ModelRunPublicationCodec.Manifest> manifest,
            Map<Integer, FeatureStreamDecoder.ValidatedFeatureStream> completedInputs)
            implements ModelRunObservation {
        public Terminal {
            status = Objects.requireNonNull(status, "status");
            manifest = Objects.requireNonNull(manifest, "manifest");
            if (status.phase() == ModelRunStatusCodec.Phase.QUEUED
                    || status.phase() == ModelRunStatusCodec.Phase.RUNNING) {
                throw new IllegalArgumentException("terminal observation cannot be active");
            }
            boolean hasManifest =
                    status.phase() == ModelRunStatusCodec.Phase.PARTIAL
                            || status.phase() == ModelRunStatusCodec.Phase.COMPLETE;
            if (manifest.isPresent() != hasManifest) {
                throw new IllegalArgumentException(
                        "terminal manifest presence does not match the status phase");
            }
            if (manifest.isPresent()
                    && manifest.orElseThrow().complete()
                            != (status.phase() == ModelRunStatusCodec.Phase.COMPLETE)) {
                throw new IllegalArgumentException(
                        "terminal manifest completeness does not match the status phase");
            }

            Map<Integer, FeatureStreamDecoder.ValidatedFeatureStream> copy = new LinkedHashMap<>();
            Objects.requireNonNull(completedInputs, "completedInputs")
                    .forEach(
                            (inputId, value) -> {
                                if (copy.put(
                                                Objects.requireNonNull(inputId, "inputId"),
                                                Objects.requireNonNull(value, "completed input"))
                                        != null) {
                                    throw new IllegalArgumentException(
                                            "completedInputs contains duplicate input IDs");
                                }
                            });
            Set<Integer> expected = new LinkedHashSet<>();
            manifest.ifPresent(
                    value ->
                            value.completedInputs()
                                    .forEach(input -> expected.add(input.inputId())));
            if (!copy.keySet().equals(expected)) {
                throw new IllegalArgumentException(
                        "validated completed inputs do not match the terminal manifest");
            }
            completedInputs = Collections.unmodifiableMap(copy);
        }

        /** Whether this terminal observation is a complete model-step result. */
        public boolean complete() {
            return status.phase() == ModelRunStatusCodec.Phase.COMPLETE;
        }

        /** Build the provenance execution reference only for a complete terminal result. */
        public BackendExecutionCodec.DurableRun executionReference() {
            if (!complete()) {
                throw new IllegalStateException(
                        "partial or failed run has no committable execution");
            }
            var value = manifest.orElseThrow();
            return new BackendExecutionCodec.DurableRun(
                    status.modelRunId(), value.manifestDigest());
        }
    }
}

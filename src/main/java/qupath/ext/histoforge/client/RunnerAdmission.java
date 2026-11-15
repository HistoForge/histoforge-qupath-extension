package qupath.ext.histoforge.client;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;

/** Shared client-side admission checks derived from one captured runner capability record. */
final class RunnerAdmission {

    private RunnerAdmission() {}

    static void requireBackend(
            InferenceIdentityCodec.BackendModelIdentity expected,
            RunnerCapabilitiesCodec.RunnerCapabilities runner) {
        if (!expected.runnerBuild().equals(runner.runnerBuild())
                || !expected.rasterizer().equals(runner.rasterizer())
                || !expected.preprocessingContractDigest()
                        .equals(runner.preprocessingContractDigest())) {
            throw new IllegalArgumentException(
                    "expected backend does not match captured runner capabilities");
        }
    }

    static void requireSourceContainer(
            String container, RunnerCapabilitiesCodec.RunnerCapabilities runner) {
        final SourceArtifactCodec.SourceContainer value;
        try {
            value = SourceArtifactCodec.SourceContainer.valueOf(container);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unsupported source container: " + container, error);
        }
        if (!runner.sourceContainers().contains(value)) {
            throw new IllegalArgumentException(
                    "source container is unsupported by captured runner capabilities");
        }
    }

    static void requireResultBudgetWithin(
            FiniteInferenceCodec.ClientResultBudget requested,
            ExecutionLimitsCodec.ResultLimits available) {
        if (requested.maxTransferredBytes() > available.maxTransferredBytes()
                || requested.maxDecodedBytes() > available.maxDecodedBytes()
                || requested.maxRecordBytes() > available.maxRecordBytes()
                || requested.maxFeatures() > available.maxFeatures()
                || requested.maxVertices() > available.maxVertices()) {
            throw new IllegalArgumentException(
                    "result budget exceeds captured runner capabilities");
        }
    }

    static Set<String> normalizeDeclaredClasses(Collection<String> declaredClasses) {
        if (declaredClasses == null || declaredClasses.isEmpty()) {
            throw new IllegalArgumentException("declaredClasses must be nonempty");
        }
        Set<String> result = new LinkedHashSet<>();
        for (String pathClass : declaredClasses) {
            String canonical = CanonicalCodec.requirePathClassName(pathClass, "declaredClasses");
            if (!result.add(canonical)) {
                throw new IllegalArgumentException("declaredClasses contains duplicates");
            }
        }
        return Set.copyOf(result);
    }
}

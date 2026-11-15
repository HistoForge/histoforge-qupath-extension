package qupath.ext.histoforge.pipeline;

import java.util.LinkedHashMap;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;

/** Candidate-only finite capabilities; the shared module fixtures keep their original limits. */
final class FiniteCapacityTestData {
    static final ExecutionLimitsCodec.TargetRasterLimits TARGET =
            new ExecutionLimitsCodec.TargetRasterLimits(6000, 6000, 36_000_000);
    static final ExecutionLimitsCodec.ResultLimits RESULT =
            new ExecutionLimitsCodec.ResultLimits(
                    64L * 1024 * 1024, 64L * 1024 * 1024, 1_000_000, 100_000, 5_000_000);

    private FiniteCapacityTestData() {}

    static ModuleSnapshot withCandidateLimits(ModuleSnapshot source, double targetMpp) {
        var models = new LinkedHashMap<String, ModuleSnapshot.ModelSnapshot>();
        source.activatedModels()
                .forEach(
                        (id, model) -> {
                            var capabilities = RunnerCapabilitiesCodec.toJson(model.capabilities());
                            var finite = capabilities.getAsJsonObject("finite");
                            finite.add("target_raster", ExecutionLimitsCodec.toJson(TARGET));
                            finite.add("result", ExecutionLimitsCodec.toJson(RESULT));
                            if (capabilities.has("durable_run")) {
                                // The routing fixture needs an already-supported larger target.
                                capabilities
                                        .getAsJsonObject("durable_run")
                                        .getAsJsonObject("run")
                                        .add(
                                                "target_raster_per_input",
                                                ExecutionLimitsCodec.toJson(
                                                        new ExecutionLimitsCodec.TargetRasterLimits(
                                                                12_288, 12_288, 150_994_944)));
                            }
                            models.put(
                                    id,
                                    new ModuleSnapshot.ModelSnapshot(
                                            model.architecture(),
                                            model.gpu(),
                                            model.modelConfigDigest(),
                                            new ModuleSnapshot.ModelInputSnapshot(
                                                    model.input().type(),
                                                    targetMpp,
                                                    model.input().fieldProfile(),
                                                    model.input().framing()),
                                            model.output(),
                                            RunnerCapabilitiesCodec.parse(capabilities),
                                            model.largeRegionProfile(),
                                            model.artifactDigests()));
                        });
        return new ModuleSnapshot(
                source.deploymentId(),
                source.module(),
                source.exactRecipe(),
                source.scriptSetDigest(),
                models,
                source.scripts(),
                source.metrics());
    }
}

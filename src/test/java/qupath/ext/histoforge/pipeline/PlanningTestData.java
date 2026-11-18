package qupath.ext.histoforge.pipeline;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.CropFraming;
import qupath.ext.histoforge.modules.ModuleSnapshot.MetricScriptSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputType;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ScriptSnapshot;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Minimal exact values shared by pure planning and producer-mode tests. */
final class PlanningTestData {

    static final ModuleRef MODULE_REF = new ModuleRef("histoforge", "planning-test");
    static final String ROI_A = "11111111-1111-4111-8111-111111111111";
    static final String ROI_B = "22222222-2222-4222-8222-222222222222";
    static final String PARENT = "33333333-3333-4333-8333-333333333333";
    static final String ACTION = "44444444-4444-4444-8444-444444444444";
    static final String GENERATION = "55555555-5555-4555-8555-555555555555";

    private PlanningTestData() {}

    static ModuleSnapshot module(Set<String> activatedModelIds, boolean withMetrics) {
        JsonObject recipe =
                CanonicalCodec.parseJson(
                                """
                                {
                                  "schemaVersion": 3,
                                  "name": "Planning Test",
                                  "description": "Planning fixture",
                                  "models": {
                                    "tubule_semantic": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["Tubule"]
                                      }
                                    },
                                    "lumen": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["Lumen"]
                                      }
                                    },
                                    "germ_cell": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["Germ cell"]
                                      }
                                    }
                                  },
                                  "steps": [
                                    {"model": "tubule_semantic"},
                                    {"model": "lumen"},
                                    {"script": "cross_section.groovy"},
                                    {
                                      "model": "germ_cell",
                                      "parentModel": "tubule_semantic",
                                      "parentClass": ["Tubule (Cross Section)"]
                                    },
                                    {"script": "metrics.groovy"}
                                  ]
                                }
                                """)
                        .getAsJsonObject();
        if (!withMetrics) {
            recipe.getAsJsonArray("steps").remove(recipe.getAsJsonArray("steps").size() - 1);
        }
        Map<String, ModelSnapshot> models = new LinkedHashMap<>();
        for (String modelId : List.of("tubule_semantic", "lumen", "germ_cell")) {
            if (activatedModelIds.contains(modelId)) {
                models.put(
                        modelId,
                        model(
                                modelId,
                                "germ_cell".equals(modelId)
                                        ? ModelInputType.CROP
                                        : ModelInputType.FIELD));
            }
        }
        if (!models.keySet().equals(activatedModelIds)) {
            throw new IllegalArgumentException("unknown activated test model");
        }

        ScriptSnapshot mutation = script("cross_section.groovy", "// mutation test");
        Optional<MetricScriptSnapshot> metrics = Optional.empty();
        if (withMetrics) {
            ScriptSnapshot metricScript =
                    script(
                            "metrics.groovy",
                            """
                            /* @histoforge-metrics
                            [{"name":"count","label":"Count","type":"scalar","decimals":0,"aggregation":"sum"}]
                            */
                            return [count: children.size()]
                            """);
            metrics =
                    Optional.of(
                            new MetricScriptSnapshot(
                                    metricScript,
                                    List.of(
                                            new MetricInfo(
                                                    "count", "Count", "scalar", null, 0, "sum"))));
        }
        return new ModuleSnapshot(
                "deployment-test",
                new InferenceIdentityCodec.ModuleContentIdentity(
                        MODULE_REF, Optional.of("a".repeat(40)), "b".repeat(64), "c".repeat(64)),
                recipe,
                "d".repeat(64),
                models,
                Map.of(mutation.filename(), mutation),
                metrics);
    }

    static BuildIdentityCodec.ClientBuildIdentity clientBuild() {
        return new BuildIdentityCodec.ClientBuildIdentity(
                build("extension"),
                "0.6.0",
                Map.of("java", "21.0.10", "jts", "1.20.0"),
                new ExecutionLimitsCodec.ClientExecutionLimits(
                        100_000,
                        1_000_000,
                        2_000_000,
                        8_000_000,
                        100_000,
                        10_000,
                        100_000,
                        1_073_741_824L));
    }

    static PathObjectHierarchy hierarchyWithRois() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(
                List.of(
                        administrativeRoi(ROI_A, 0, 0, 100, 100, ImagePlane.getDefaultPlane()),
                        administrativeRoi(ROI_B, 100, 0, 100, 100, ImagePlane.getDefaultPlane())));
        return hierarchy;
    }

    static PathObject administrativeRoi(
            String id, double x, double y, double width, double height, ImagePlane plane) {
        PathObject object =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(x, y, width, height, plane));
        object.setID(java.util.UUID.fromString(id));
        object.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return object;
    }

    static PathObject annotation(
            String id, double x, double y, double width, double height, ImagePlane plane) {
        PathObject object =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(x, y, width, height, plane));
        object.setID(java.util.UUID.fromString(id));
        return object;
    }

    static PathObject attributed(
            String objectId, String stepId, ExecutionScopeCodec.ExecutionScope scope) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()));
        object.setID(java.util.UUID.fromString(objectId));
        ProvenanceCodec.writeMetadata(
                object.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(producer(stepId), scope),
                        GENERATION));
        return object;
    }

    static InferenceIdentityCodec.ModelStepIdentity producer(String stepId) {
        return new InferenceIdentityCodec.ModelStepIdentity(MODULE_REF, stepId);
    }

    private static ModelSnapshot model(String modelId, ModelInputType inputType) {
        RunnerCapabilitiesCodec.RunnerCapabilities capabilities = capabilities();
        return new ModelSnapshot(
                "test-architecture",
                "A10G",
                "e".repeat(64),
                new ModelInputSnapshot(
                        inputType,
                        0.5,
                        Optional.empty(),
                        inputType == ModelInputType.CROP
                                ? Optional.of(new CropFraming(64))
                                : Optional.empty()),
                new ModelOutput(
                        "instance_segmentation",
                        List.of(
                                switch (modelId) {
                                    case "tubule_semantic" -> "Tubule";
                                    case "lumen" -> "Lumen";
                                    case "germ_cell" -> "Germ cell";
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "unknown test model: " + modelId);
                                }),
                        List.of()),
                capabilities,
                Optional.empty(),
                Map.of("weights", "f".repeat(64)));
    }

    private static RunnerCapabilitiesCodec.RunnerCapabilities capabilities() {
        ExecutionLimitsCodec.ResultLimits result =
                new ExecutionLimitsCodec.ResultLimits(
                        1_000_000, 2_000_000, 100_000, 10_000, 100_000);
        ExecutionLimitsCodec.TargetRasterLimits raster =
                new ExecutionLimitsCodec.TargetRasterLimits(2048, 2048, 4_194_304);
        return new RunnerCapabilitiesCodec.RunnerCapabilities(
                Set.of(
                        RunnerCapabilitiesCodec.InferenceMode.FINITE_CALL,
                        RunnerCapabilitiesCodec.InferenceMode.DURABLE_MODEL_RUN),
                Set.of(RunnerCapabilitiesCodec.ModelOutputType.INSTANCE_SEGMENTATION),
                Set.of(
                        SourceArtifactCodec.SourceContainer.PNG,
                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY),
                build("runner"),
                new InferenceIdentityCodec.RasterizerIdentity(build("rasterizer"), "1".repeat(64)),
                "2".repeat(64),
                new RunnerCapabilitiesCodec.FiniteCallLimits(
                        60, 1_000_000, raster, 100_000, result),
                Optional.of(
                        new RunnerCapabilitiesCodec.DurableRunLimits(
                                new ExecutionLimitsCodec.ModelRunLimits(
                                        3_600,
                                        1_000_000,
                                        100,
                                        1_000,
                                        raster,
                                        raster,
                                        100_000_000,
                                        1_000_000,
                                        100_000_000,
                                        1_000_000_000,
                                        result,
                                        result),
                                3_600)));
    }

    private static ScriptSnapshot script(String filename, String source) {
        return new ScriptSnapshot(
                filename, source, CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)));
    }

    private static BuildIdentityCodec.SoftwareBuildIdentity build(String version) {
        return new BuildIdentityCodec.SoftwareBuildIdentity(
                version, "3".repeat(64), "4".repeat(64));
    }
}

package qupath.ext.histoforge.pipeline;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.CropFraming;
import qupath.ext.histoforge.modules.ModuleSnapshot.MetricScriptSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputType;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ScriptSnapshot;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;

/** Real planner values and deterministic model results for staged recipe integration tests. */
final class RecipeExecutorTestData {

    static final ModuleRef MODULE = new ModuleRef("histoforge", "recipe-executor-test");
    static final String ACTION_ID = "10000000-0000-4000-8000-000000000001";
    static final String ROI_A = "20000000-0000-4000-8000-000000000001";
    static final String ROI_B = "20000000-0000-4000-8000-000000000002";
    static final String ROI_C = "20000000-0000-4000-8000-000000000003";
    static final String PARENT_ID = "30000000-0000-4000-8000-000000000001";
    static final String CHILD_ID = "30000000-0000-4000-8000-000000000002";
    static final String OLD_PARENT_ID = "30000000-0000-4000-8000-000000000003";
    static final String OLD_CHILD_ID = "30000000-0000-4000-8000-000000000004";
    static final String SLIDE_OBJECT_ID = "30000000-0000-4000-8000-000000000005";
    static final String MANUAL_PARENT_ID = "30000000-0000-4000-8000-000000000006";
    static final String IMAGE_ID = "recipe-executor-image";
    static final String DEPLOYMENT_ID = "recipe-executor-deployment";

    private static final int WIDTH = 256;
    private static final int HEIGHT = 128;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final ExecutionLimitsCodec.TargetRasterLimits TARGET_LIMITS =
            new ExecutionLimitsCodec.TargetRasterLimits(512, 512, 262_144);

    private RecipeExecutorTestData() {}

    static ModuleSnapshot fieldModule() {
        JsonObject recipe =
                CanonicalCodec.parseJson(
                                """
                                {
                                  "schemaVersion": 3,
                                  "name": "Recipe Executor Field",
                                  "description": "Transaction fixture",
                                  "models": {
                                    "parent": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["Parent"]
                                      }
                                    }
                                  },
                                  "steps": [{"model": "parent"}]
                                }
                                """)
                        .getAsJsonObject();
        return module(recipe, "Parent", Optional.empty());
    }

    static ModuleSnapshot fieldMetricModule() {
        JsonObject recipe =
                CanonicalCodec.parseJson(
                                """
                                {
                                  "schemaVersion": 3,
                                  "name": "Recipe Executor Field Metrics",
                                  "description": "Metric transaction fixture",
                                  "models": {
                                    "parent": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["Parent"]
                                      }
                                    }
                                  },
                                  "steps": [
                                    {"model": "parent"},
                                    {"script": "metrics.groovy"}
                                  ]
                                }
                                """)
                        .getAsJsonObject();
        String metricSource =
                """
                /* @histoforge-metrics
                [{"name":"object_count","label":"Object count","type":"scalar","decimals":0,"aggregation":"sum"}]
                */
                return [object_count: children.size()]
                """;
        ScriptSnapshot script = script("metrics.groovy", metricSource);
        return new ModuleSnapshot(
                DEPLOYMENT_ID,
                new InferenceIdentityCodec.ModuleContentIdentity(
                        MODULE, Optional.of("a".repeat(40)), hex('4'), hex('5')),
                recipe,
                hex('6'),
                Map.of("parent", model("parent", ModelInputType.FIELD, "Parent")),
                Map.of(),
                Optional.of(
                        new MetricScriptSnapshot(
                                script,
                                List.of(
                                        new MetricInfo(
                                                "object_count",
                                                "Object count",
                                                "scalar",
                                                null,
                                                0,
                                                "sum")))));
    }

    static ModuleSnapshot fieldMetricOnlyModule() {
        ModuleSnapshot captured = fieldMetricModule();
        return new ModuleSnapshot(
                captured.deploymentId(),
                captured.module(),
                captured.exactRecipe(),
                captured.scriptSetDigest(),
                Map.of(),
                captured.scripts(),
                captured.metrics());
    }

    static ModuleSnapshot twoFieldModule() {
        JsonObject recipe =
                CanonicalCodec.parseJson(
                                """
                                {
                                  "schemaVersion": 3,
                                  "name": "Recipe Executor Two Fields",
                                  "description": "Projection transition fixture",
                                  "models": {
                                    "parent": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["Parent"]
                                      }
                                    },
                                    "second": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["Second"]
                                      }
                                    }
                                  },
                                  "steps": [
                                    {"model": "parent"},
                                    {"model": "second"}
                                  ]
                                }
                                """)
                        .getAsJsonObject();
        Map<String, ModelSnapshot> models = new LinkedHashMap<>();
        models.put("parent", model("parent", ModelInputType.FIELD, "Parent"));
        models.put("second", model("second", ModelInputType.FIELD, "Second"));
        return module(recipe, models, Map.of());
    }

    static ModuleSnapshot scriptedModule(boolean invalidatesExistingChild) {
        String parentClass = invalidatesExistingChild ? "Eligible" : "Parent";
        String replacement = invalidatesExistingChild ? "Ineligible" : "Eligible";
        JsonObject recipe =
                CanonicalCodec.parseJson(
                                """
                                {
                                  "schemaVersion": 3,
                                  "name": "Recipe Executor Scripted",
                                  "description": "Transaction fixture",
                                  "models": {
                                    "parent": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["%s"]
                                      }
                                    },
                                    "child": {
                                      "output": {
                                        "type": "instance_segmentation",
                                        "classes": ["Child"]
                                      }
                                    }
                                  },
                                  "steps": [
                                    {"model": "parent"},
                                    {"script": "classify.groovy"},
                                    {
                                      "model": "child",
                                      "parentModel": "parent",
                                      "parentClass": ["Eligible"]
                                    }
                                  ]
                                }
                                """
                                        .formatted(parentClass))
                        .getAsJsonObject();
        String scriptSource =
                """
                /* @histoforge-classes ["%s"] */
                import qupath.lib.objects.classes.PathClass
                def replacement = PathClass.fromString("%s")
                children.findAll { it.getPathClass()?.toString() == "%s" }
                        .each { it.setPathClass(replacement) }
                return null
                """
                        .formatted(replacement, replacement, parentClass);
        return module(recipe, parentClass, Optional.of(script("classify.groovy", scriptSource)));
    }

    static ImageData<BufferedImage> imageData(String name, String... roiIds) {
        ImageData<BufferedImage> image =
                new ImageData<>(
                        new WrappedBufferedImageServer(
                                name, new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)),
                        ImageType.UNSET);
        for (String roiId : roiIds) {
            image.getHierarchy().getRootObject().addChildObject(administrativeRoi(roiId));
        }
        return image;
    }

    static ActionSelectionCodec.ImageAction allModels(boolean redo, Set<String> roiIds) {
        ActionSelectionCodec.AllModels work =
                new ActionSelectionCodec.AllModels(new ActionSelectionCodec.RoiRegions(roiIds));
        return redo
                ? new ActionSelectionCodec.Redo(work, true)
                : new ActionSelectionCodec.Fill(work, true);
    }

    static ActionSelectionCodec.ImageAction selectedChildRedo(String roiId, String parentId) {
        ExecutionScopeCodec.Roi region = new ExecutionScopeCodec.Roi(roiId);
        return selectedChildRedo(roiId, new ActionSelectionCodec.ParentTarget(region, parentId));
    }

    static ActionSelectionCodec.ImageAction selectedChildRedo(
            String roiId, ActionSelectionCodec.TargetSelector target) {
        ActionSelectionCodec.SelectedModels work =
                new ActionSelectionCodec.SelectedModels(
                        new ActionSelectionCodec.RoiRegions(Set.of(roiId)),
                        List.of(
                                new ActionSelectionCodec.StepTargets(
                                        "child",
                                        new ActionSelectionCodec.TargetSelection(
                                                List.of(target)))));
        return new ActionSelectionCodec.Redo(work, true);
    }

    static ActionSelectionCodec.ImageAction selectedChildFill(
            String roiId, ActionSelectionCodec.TargetSelector target) {
        ActionSelectionCodec.SelectedModels work =
                new ActionSelectionCodec.SelectedModels(
                        new ActionSelectionCodec.RoiRegions(Set.of(roiId)),
                        List.of(
                                new ActionSelectionCodec.StepTargets(
                                        "child",
                                        new ActionSelectionCodec.TargetSelection(
                                                List.of(target)))));
        return new ActionSelectionCodec.Fill(work, true);
    }

    static ModuleSnapshot withActivatedModels(ModuleSnapshot source, String... modelIds) {
        Map<String, ModelSnapshot> models = new LinkedHashMap<>();
        for (String modelId : modelIds) {
            ModelSnapshot model = source.activatedModels().get(modelId);
            if (model == null) {
                throw new IllegalArgumentException("test module has no model " + modelId);
            }
            models.put(modelId, model);
        }
        return new ModuleSnapshot(
                source.deploymentId(),
                source.module(),
                source.exactRecipe(),
                source.scriptSetDigest(),
                models,
                source.scripts(),
                source.metrics());
    }

    static ImageExecutionPlan plan(
            ImageData<?> image, ModuleSnapshot module, ActionSelectionCodec.ImageAction action) {
        return plan(image, module, action, clientBuild(1_000_000));
    }

    static ImageExecutionPlan plan(
            ImageData<?> image,
            ModuleSnapshot module,
            ActionSelectionCodec.ImageAction action,
            BuildIdentityCodec.ClientBuildIdentity clientBuild) {
        return ImageActionPlanner.plan(
                ACTION_ID,
                clientBuild,
                IMAGE_ID,
                module,
                action,
                image.getHierarchy(),
                WIDTH,
                HEIGHT);
    }

    static RecipeExecutor.ExecutionInputs executionInputs(
            ImageExecutionPlan plan, ImageData<?> image) {
        CapturedSourcePyramid source = capturedSourcePyramid();
        SourceInputPlan sourcePlan =
                RecipeSourcePlanner.plan(
                        plan,
                        source,
                        image.getHierarchy(),
                        Optional.empty(),
                        sourcePlanningLimits());
        Map<ExecutionScopeCodec.RegionKey, RecipeExecutor.SourceProvision> provisions =
                new LinkedHashMap<>();
        int suffix = 1;
        for (SourceInputPlan.TargetPlanningResult target : sourcePlan.targets()) {
            if (target.sourcePlan().isEmpty()) {
                continue;
            }
            SourceInputPlan.SourceArtifactPlan artifactPlan =
                    target.sourcePlan().orElseThrow().artifact();
            SourceArtifactCodec.SourceContainer container =
                    artifactPlan instanceof SourceInputPlan.OriginalArtifactPlan original
                            ? original.container()
                            : ((SourceInputPlan.DerivedArtifactPlan) artifactPlan).container();
            provisions.put(
                    target.target().key(),
                    new RecipeExecutor.PublishedSource(
                            new SourceArtifactPublicationCodec.SourceArtifactRef(
                                    "40000000-0000-4000-8000-%012d".formatted(suffix++),
                                    plan.actionId(),
                                    hex('1'),
                                    hex('2'),
                                    hex('3'),
                                    container)));
        }
        return new RecipeExecutor.ExecutionInputs(sourcePlan, provisions, sourceRevision());
    }

    record PublishedSourceFixture(
            SourceInputPlan sourcePlan,
            SourceArtifactCodec.SourceArtifactStartRequest startRequest,
            SourceArtifactPublicationCodec.SourceArtifactRef artifact,
            RecipeExecutor.ExecutionInputs executionInputs) {}

    static PublishedSourceFixture publishedSource(ImageExecutionPlan plan, ImageData<?> image) {
        SourceInputPlan sourcePlan =
                RecipeSourcePlanner.plan(
                        plan,
                        capturedSourcePyramid(),
                        image.getHierarchy(),
                        Optional.empty(),
                        sourcePlanningLimits());
        SourceInputPlan.TargetSourcePlan target =
                sourcePlan.targets().getFirst().sourcePlan().orElseThrow();
        SourceInputPlan.DerivedArtifactPlan derived =
                (SourceInputPlan.DerivedArtifactPlan) target.artifact();
        String artifactId = "40000000-0000-4000-8000-000000000001";
        SourceArtifactCodec.SourceArtifactStartRequest start =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        plan.actionId(),
                        artifactId,
                        derived.container(),
                        derived.conservativeArtifactBytes(),
                        1024,
                        new SourceArtifactCodec.DerivedRegion(
                                sourceRevision().pixelSource(),
                                derived.sourceResolutionLevel(),
                                derived.plane(),
                                derived.sourceRectAtLevel(),
                                derived.artifactPixelsToSlide(),
                                plan.clientBuild().extension()),
                        List.of(
                                new SourceArtifactCodec.SourceLevelRequirement(
                                        0,
                                        0,
                                        derived.sourceRectAtLevel().width(),
                                        derived.sourceRectAtLevel().height())));
        SourceArtifactPublicationCodec.SourceArtifactRef artifact =
                new SourceArtifactPublicationCodec.SourceArtifactRef(
                        artifactId,
                        plan.actionId(),
                        SourceArtifactCodec.startRequestDigest(start),
                        hex('2'),
                        hex('3'),
                        start.container());
        RecipeExecutor.ExecutionInputs inputs =
                new RecipeExecutor.ExecutionInputs(
                        sourcePlan,
                        Map.of(
                                sourcePlan.targets().getFirst().target().key(),
                                new RecipeExecutor.PublishedSource(artifact)),
                        sourceRevision());
        return new PublishedSourceFixture(sourcePlan, start, artifact, inputs);
    }

    static CapturedSourcePyramid capturedSourcePyramid() {
        return new CapturedSourcePyramid(
                sourceRevision(),
                0,
                new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                List.of(
                        new SourceLevel(
                                0,
                                new SourceViewGeometry.Dimensions(WIDTH, HEIGHT),
                                new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0))));
    }

    static SourcePlanningInputs.SourcePlanningLimits sourcePlanningLimits() {
        return new SourcePlanningInputs.SourcePlanningLimits(
                1_000_000, 1L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024);
    }

    static ModuleSnapshot durableModule(ModuleSnapshot source) {
        return durableModule(source, 100);
    }

    static ModuleSnapshot durableModule(ModuleSnapshot source, int maxInputs) {
        return durableModule(source, maxInputs, OptionalLong.empty());
    }

    static ModuleSnapshot durableModule(
            ModuleSnapshot source, int maxInputs, long maxRequestCanonicalBytes) {
        return durableModule(source, maxInputs, OptionalLong.of(maxRequestCanonicalBytes));
    }

    private static ModuleSnapshot durableModule(
            ModuleSnapshot source, int maxInputs, OptionalLong requestBytesOverride) {
        Map<String, ModelSnapshot> models = new LinkedHashMap<>();
        source.activatedModels()
                .forEach(
                        (modelId, model) -> {
                            RunnerCapabilitiesCodec.RunnerCapabilities finite =
                                    model.capabilities();
                            ExecutionLimitsCodec.ModelRunLimits run =
                                    new ExecutionLimitsCodec.ModelRunLimits(
                                            3600,
                                            requestBytesOverride.orElse(
                                                    finite.finite().maxRequestCanonicalBytes()),
                                            maxInputs,
                                            100,
                                            finite.finite().targetRaster(),
                                            finite.finite().targetRaster(),
                                            100_000_000,
                                            1_000_000,
                                            8_000_000,
                                            64_000_000,
                                            finite.finite().result(),
                                            finite.finite().result());
                            RunnerCapabilitiesCodec.RunnerCapabilities durable =
                                    new RunnerCapabilitiesCodec.RunnerCapabilities(
                                            Set.of(
                                                    RunnerCapabilitiesCodec.InferenceMode
                                                            .FINITE_CALL,
                                                    RunnerCapabilitiesCodec.InferenceMode
                                                            .DURABLE_MODEL_RUN),
                                            finite.outputTypes(),
                                            finite.sourceContainers(),
                                            finite.runnerBuild(),
                                            finite.rasterizer(),
                                            finite.preprocessingContractDigest(),
                                            finite.finite(),
                                            Optional.of(
                                                    new RunnerCapabilitiesCodec.DurableRunLimits(
                                                            run, 3600)));
                            models.put(
                                    modelId,
                                    new ModelSnapshot(
                                            model.architecture(),
                                            model.gpu(),
                                            model.modelConfigDigest(),
                                            model.input(),
                                            model.output(),
                                            durable,
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

    /** Keep the real module snapshot but advertise a finite raster cap below this fixture ROI. */
    static ModuleSnapshot forceRecoverableProtocol(ModuleSnapshot source) {
        ExecutionLimitsCodec.TargetRasterLimits smallFiniteRaster =
                new ExecutionLimitsCodec.TargetRasterLimits(128, 128, 16_384);
        JsonObject profilePreimage = new JsonObject();
        profilePreimage.addProperty("type", "global-decode");
        profilePreimage.addProperty("validation_evidence_digest", "e".repeat(64));
        LargeRegionProfileCodec.GlobalDecodeProfile testProfile =
                new LargeRegionProfileCodec.GlobalDecodeProfile(
                        CanonicalCodec.canonicalDigest(profilePreimage), "e".repeat(64));
        Map<String, ModelSnapshot> models = new LinkedHashMap<>();
        source.activatedModels()
                .forEach(
                        (modelId, model) -> {
                            RunnerCapabilitiesCodec.RunnerCapabilities capabilities =
                                    model.capabilities();
                            RunnerCapabilitiesCodec.FiniteCallLimits finite = capabilities.finite();
                            RunnerCapabilitiesCodec.RunnerCapabilities recoverable =
                                    new RunnerCapabilitiesCodec.RunnerCapabilities(
                                            capabilities.modes(),
                                            capabilities.outputTypes(),
                                            capabilities.sourceContainers(),
                                            capabilities.runnerBuild(),
                                            capabilities.rasterizer(),
                                            capabilities.preprocessingContractDigest(),
                                            new RunnerCapabilitiesCodec.FiniteCallLimits(
                                                    finite.maxAttemptExecutionSeconds(),
                                                    finite.maxRequestCanonicalBytes(),
                                                    smallFiniteRaster,
                                                    finite.maxInclusionMaskVertices(),
                                                    finite.result()),
                                            capabilities.durableRun());
                            models.put(
                                    modelId,
                                    new ModelSnapshot(
                                            model.architecture(),
                                            model.gpu(),
                                            model.modelConfigDigest(),
                                            model.input(),
                                            model.output(),
                                            recoverable,
                                            model.largeRegionProfile()
                                                    .or(() -> Optional.of(testProfile)),
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

    static CandidateGeneration generation(
            FiniteModelInputExecutor.PreparedInput prepared,
            List<CandidateGeneration.SlideCandidate> candidates) {
        CandidateGeneration.InputEvidence evidence =
                new CandidateGeneration.InputEvidence(
                        FiniteInferenceCodec.buildBackendInputIdentity(
                                prepared.request().rasterInput()),
                        ExecutionScopeCodec.scopeAcceptanceDigest(
                                prepared.plannedInput().acceptance()),
                        prepared.plannedInput().acceptance(),
                        prepared.request().rasterInput());
        return new CandidateGeneration(
                prepared.reservation(),
                new BackendExecutionCodec.FiniteCall(
                        "call-"
                                + prepared.request().producer().stepId()
                                + "-"
                                + prepared.request().inputId()),
                evidence,
                new GenerationEvidenceCodec.GenerationCounts(
                        candidates.size(), candidates.size(), 0, candidates.size()),
                candidates);
    }

    static CandidateGeneration.SlideCandidate candidate(
            String id, Geometry geometry, String pathClass) {
        return new CandidateGeneration.SlideCandidate(
                id, geometry, pathClass, new SourceIdentityCodec.ImagePlaneIndex(0, 0));
    }

    static PathObject attributed(
            String id,
            String pathClass,
            Geometry geometry,
            String modelId,
            ExecutionScopeCodec.ExecutionScope scope) {
        PathObject object = detection(id, pathClass, geometry);
        ProvenanceCodec.writeMetadata(
                object.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(producer(modelId), scope),
                        "50000000-0000-4000-8000-000000000001"));
        return object;
    }

    static PathObject manual(String id, String pathClass, Geometry geometry) {
        return detection(id, pathClass, geometry);
    }

    static Geometry square(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.createPolygon(
                new CoordinateXY[] {
                    new CoordinateXY(minX, minY),
                    new CoordinateXY(maxX, minY),
                    new CoordinateXY(maxX, maxY),
                    new CoordinateXY(minX, maxY),
                    new CoordinateXY(minX, minY)
                });
    }

    static BuildIdentityCodec.ClientBuildIdentity clientBuild(long generationStoreCap) {
        return new BuildIdentityCodec.ClientBuildIdentity(
                build("extension"),
                "0.6.0",
                Map.of("java", "21", "jts", "1.20.0"),
                new ExecutionLimitsCodec.ClientExecutionLimits(
                        1_000_000,
                        2_000_000,
                        3_000_000,
                        8_000_000,
                        generationStoreCap,
                        100_000,
                        1_000_000,
                        8L * 1024 * 1024 * 1024));
    }

    static InferenceIdentityCodec.ModelStepIdentity producer(String modelId) {
        return new InferenceIdentityCodec.ModelStepIdentity(MODULE, modelId);
    }

    private static ModuleSnapshot module(
            JsonObject recipe, String parentClass, Optional<ScriptSnapshot> script) {
        Map<String, ModelSnapshot> models = new LinkedHashMap<>();
        models.put("parent", model("parent", ModelInputType.FIELD, parentClass));
        if (recipe.getAsJsonObject("models").has("child")) {
            models.put("child", model("child", ModelInputType.CROP, "Child"));
        }
        Map<String, ScriptSnapshot> scripts =
                script.map(value -> Map.of(value.filename(), value)).orElseGet(Map::of);
        return module(recipe, models, scripts);
    }

    private static ModuleSnapshot module(
            JsonObject recipe,
            Map<String, ModelSnapshot> models,
            Map<String, ScriptSnapshot> scripts) {
        return new ModuleSnapshot(
                DEPLOYMENT_ID,
                new InferenceIdentityCodec.ModuleContentIdentity(
                        MODULE, Optional.of("a".repeat(40)), hex('4'), hex('5')),
                recipe,
                hex('6'),
                models,
                scripts,
                Optional.empty());
    }

    /** Durable scripted fixture whose child inputs partition into finite and profile families. */
    static ModuleSnapshot mixedFamilyDurableModule() {
        ModuleSnapshot source = durableModule(scriptedModule(false));
        ModelSnapshot child = source.activatedModels().get("child");
        RunnerCapabilitiesCodec.RunnerCapabilities capabilities = child.capabilities();
        RunnerCapabilitiesCodec.FiniteCallLimits finite = capabilities.finite();
        ExecutionLimitsCodec.TargetRasterLimits finiteRaster =
                new ExecutionLimitsCodec.TargetRasterLimits(20, 20, 400);
        RunnerCapabilitiesCodec.RunnerCapabilities split =
                new RunnerCapabilitiesCodec.RunnerCapabilities(
                        capabilities.modes(),
                        capabilities.outputTypes(),
                        capabilities.sourceContainers(),
                        capabilities.runnerBuild(),
                        capabilities.rasterizer(),
                        capabilities.preprocessingContractDigest(),
                        new RunnerCapabilitiesCodec.FiniteCallLimits(
                                finite.maxAttemptExecutionSeconds(),
                                finite.maxRequestCanonicalBytes(),
                                finiteRaster,
                                finite.maxInclusionMaskVertices(),
                                finite.result()),
                        capabilities.durableRun());
        JsonObject profilePreimage = new JsonObject();
        profilePreimage.addProperty("type", "global-decode");
        profilePreimage.addProperty("validation_evidence_digest", "e".repeat(64));
        LargeRegionProfileCodec.GlobalDecodeProfile profile =
                new LargeRegionProfileCodec.GlobalDecodeProfile(
                        CanonicalCodec.canonicalDigest(profilePreimage), "e".repeat(64));
        ModelSnapshot splitChild =
                new ModelSnapshot(
                        child.architecture(),
                        child.gpu(),
                        child.modelConfigDigest(),
                        child.input(),
                        child.output(),
                        split,
                        Optional.of(profile),
                        child.artifactDigests());
        Map<String, ModelSnapshot> models = new LinkedHashMap<>(source.activatedModels());
        models.put("child", splitChild);
        return new ModuleSnapshot(
                source.deploymentId(),
                source.module(),
                source.exactRecipe(),
                source.scriptSetDigest(),
                models,
                source.scripts(),
                source.metrics());
    }

    private static ModelSnapshot model(
            String modelId, ModelInputType inputType, String outputClass) {
        return new ModelSnapshot(
                "test-runner",
                "A10G",
                modelId.equals("parent") ? hex('7') : hex('8'),
                new ModelInputSnapshot(
                        inputType,
                        1.0,
                        Optional.empty(),
                        inputType == ModelInputType.CROP
                                ? Optional.of(new CropFraming(0))
                                : Optional.empty()),
                new ModelOutput("instance_segmentation", List.of(outputClass), List.of()),
                capabilities(),
                Optional.empty(),
                Map.of("weights", modelId.equals("parent") ? hex('9') : hex('a')));
    }

    private static RunnerCapabilitiesCodec.RunnerCapabilities capabilities() {
        ExecutionLimitsCodec.ResultLimits result =
                new ExecutionLimitsCodec.ResultLimits(
                        1_000_000, 2_000_000, 100_000, 10_000, 100_000);
        return new RunnerCapabilitiesCodec.RunnerCapabilities(
                Set.of(RunnerCapabilitiesCodec.InferenceMode.FINITE_CALL),
                Set.of(RunnerCapabilitiesCodec.ModelOutputType.INSTANCE_SEGMENTATION),
                Set.of(
                        SourceArtifactCodec.SourceContainer.PNG,
                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                        SourceArtifactCodec.SourceContainer.SLIDE_FILE),
                build("runner"),
                new InferenceIdentityCodec.RasterizerIdentity(build("rasterizer"), hex('b')),
                hex('c'),
                new RunnerCapabilitiesCodec.FiniteCallLimits(
                        60, 1_000_000, TARGET_LIMITS, 100_000, result),
                Optional.empty());
    }

    private static ScriptSnapshot script(String filename, String source) {
        return new ScriptSnapshot(
                filename, source, CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)));
    }

    static RasterInputCodec.ActionSourceRevision sourceRevision() {
        SourceIdentityCodec.ImageSourceIdentity source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "recipe.fixture.reader",
                        hex('d'),
                        Optional.empty(),
                        WIDTH,
                        HEIGHT,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1, 1));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        IMAGE_ID, SourceIdentityCodec.imageSourceIdentityDigest(source), hex('e')),
                source);
    }

    private static PathObject administrativeRoi(String roiId) {
        int x =
                switch (roiId) {
                    case ROI_A -> 0;
                    case ROI_B -> 128;
                    case ROI_C -> 230;
                    default -> throw new IllegalArgumentException("unknown test ROI " + roiId);
                };
        int width = ROI_C.equals(roiId) ? 20 : 100;
        PathObject roi =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(x, 0, width, 100, ImagePlane.getDefaultPlane()),
                        RoiManager.HISTOFORGE_ROI_CLASS);
        roi.setID(UUID.fromString(roiId));
        roi.setLocked(true);
        roi.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return roi;
    }

    private static PathObject detection(String id, String pathClass, Geometry geometry) {
        PathObject object =
                PathObjects.createDetectionObject(
                        GeometryTools.geometryToROI(geometry, ImagePlane.getDefaultPlane()),
                        PathClass.fromString(pathClass));
        object.setID(UUID.fromString(id));
        return object;
    }

    private static BuildIdentityCodec.SoftwareBuildIdentity build(String version) {
        return new BuildIdentityCodec.SoftwareBuildIdentity(version, hex('f'), hex('0'));
    }

    private static String hex(char value) {
        return Character.toString(value).repeat(64);
    }
}

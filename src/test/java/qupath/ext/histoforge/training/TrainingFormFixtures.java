package qupath.ext.histoforge.training;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.ModuleCatalogCodec;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModelDescriptor;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry.Dimensions;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAvailable;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingOption;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingGeometryCodec.TrainingObject;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingImageSourcePlan;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingRoiSourcePlan;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PlannedImage;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedImage;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedRoi;
import qupath.ext.histoforge.training.TrainingProjectCapture.LabelCounts;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;
import qupath.ext.histoforge.training.TrainingProjectIndex.SplitUnit;

/** Contract fixtures with explicit model/capability variants for pure form and UI probes. */
public final class TrainingFormFixtures {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private TrainingFormFixtures() {}

    public record PopulatedProject(ProjectPlan plan, TrainingAssignment assignment) {}

    /** Give the native layout fixture the same counts as its detached dataset fixture. */
    public static TrainingProjectPreview preview(ProjectPlan plan) {
        List<TrainingProjectPreview.Image> images = new ArrayList<>();
        for (var image : plan.images()) {
            var capture = image.capture();
            List<TrainingProjectCapture.RoiAnnotationCounts> rois = new ArrayList<>();
            for (var roi : capture.rois()) {
                Map<String, Integer> counts = new HashMap<>();
                roi.targetObjects()
                        .forEach(object -> counts.merge(object.pathClass(), 1, Integer::sum));
                rois.add(
                        new TrainingProjectCapture.RoiAnnotationCounts(
                                roi.sourceRoiUuid(),
                                roi.geometry().getEnvelopeInternal(),
                                counts,
                                roi.counts().noClass(),
                                roi.counts().notUsed()));
            }
            images.add(
                    new TrainingProjectPreview.Image(
                            capture.projectEntryId(),
                            plan.imageNames().get(capture.projectEntryId()),
                            capture.projectGroup(),
                            new TrainingProjectCapture.ImageAnnotations(
                                    rois, capture.annotationClasses())));
        }
        return new TrainingProjectPreview(images);
    }

    public static JsonObject fixture(String name) throws IOException {
        try (var input = TrainingFormFixtures.class.getResourceAsStream("/contracts/" + name)) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    public static ModelTrainingCapability capability(boolean variable) throws IOException {
        var value =
                fixture("training-datasets-v1.json")
                        .getAsJsonObject("capability")
                        .getAsJsonObject("value")
                        .deepCopy();
        if (variable) {
            value.addProperty("small_roi_context_editable", true);
            value.getAsJsonObject("allowed_field_side_model_px").addProperty("minimum", 128);
            value.getAsJsonObject("allowed_field_side_model_px").addProperty("maximum", 1024);
            value.add(
                    "default_field_profile",
                    JsonParser.parseString(
                            """
                    {"planner":"variable-field-v2","minSideModelPx":128,
                     "maxSideModelPx":1024,"preferredTileSideModelPx":768,
                     "alignmentModelPx":16,"outerArtificialPaddingModelPx":8,
                     "smallRoiContext":true,"tileOverlap":0.25,
                     "aggregation":"mean-logits-v1","preprocessing":"brightfield-rgb-v1"}
                    """));
        }
        return TrainingCapabilityCodec.parseCapability(value, 4096);
    }

    public static InstalledModule module(
            ModelTrainingCapability capability,
            Consumer<JsonObject> editRecipe,
            boolean runnableOther)
            throws IOException {
        var gateway =
                GatewayCapabilitiesCodec.parseGatewayCapabilities(
                        fixture("gateway-capabilities-v2.json")
                                .getAsJsonArray("capabilityCases")
                                .get(0)
                                .getAsJsonObject()
                                .get("value"));
        var original =
                ModuleCatalogCodec.parse(fixture("module-catalog-v1.json").get("page"), 4096)
                        .modules()
                        .getFirst();
        var recipe = original.recipe();
        recipe.getAsJsonObject("models")
                .getAsJsonObject("cells")
                .getAsJsonObject("input")
                .addProperty("micronsPerPixel", 0.5);
        editRecipe.accept(recipe);
        var models = new HashMap<>(original.models());
        var cells = models.get("cells");
        models.put(
                "cells",
                new InstalledModelDescriptor(
                        cells.modelConfigDigest(),
                        cells.artifactDigests(),
                        cells.largeRegionProfile(),
                        cells.runnerAvailability(),
                        cells.precisionOverrides(),
                        new TrainingAvailable(
                                List.of(
                                        new TrainingOption(
                                                capability,
                                                "instance_segmentation"
                                                                .equals(capability.outputType())
                                                        ? List.of(Initialization.USE)
                                                        : List.of(
                                                                Initialization.USE,
                                                                Initialization.ADD,
                                                                Initialization.DEFINE))))));
        if (runnableOther) {
            var other = models.get("future");
            models.put(
                    "future",
                    new InstalledModelDescriptor(
                            other.modelConfigDigest(),
                            other.artifactDigests(),
                            other.largeRegionProfile(),
                            cells.runnerAvailability(),
                            cells.precisionOverrides(),
                            other.trainingAvailability()));
        }
        models.keySet().retainAll(recipe.getAsJsonObject("models").keySet());
        return new InstalledModule(
                gateway,
                new InstalledModuleDescriptor(
                        original.identity(),
                        original.installedAt(),
                        original.installedModuleFileBytes(),
                        recipe,
                        original.scripts(),
                        original.scriptSetDigest(),
                        models,
                        original.readmeMarkdown()));
    }

    /** Detached multi-image project state for rendering the populated Train form. */
    public static TrainingTask sourceTask(InstalledModule source, String modelId) {
        var model = source.recipe().models().get(modelId);
        return TrainingCapabilityCodec.taskFromCapability(
                TrainingRunDraft.options(source, modelId).getFirst().capability(), model.output(),
                model.input().micronsPerPixel(),
                        source.gateway().trainingApiLimits().report().maxTextBytes());
    }

    public static PopulatedProject populatedProject(InstalledModule source, String modelId) {
        List<PlannedImage> images = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        TrainingTask task = sourceTask(source, modelId);
        for (int imageIndex = 0; imageIndex < 10; imageIndex++) {
            String imageId = "image-" + imageIndex;
            String roiId = uuid(imageIndex + 1);
            var geometry =
                    GEOMETRY_FACTORY.toGeometry(
                            new Envelope(100, 900, 120 + imageIndex, 920 + imageIndex));
            List<TrainingObject> objects = new ArrayList<>();
            int objectCount = 12 + imageIndex * 3;
            for (int objectIndex = 0; objectIndex < objectCount; objectIndex++) {
                double x = 120 + (objectIndex % 10) * 12;
                double y = 140 + (objectIndex / 10) * 12;
                objects.add(
                        new TrainingObject(
                                uuid(1_000 + imageIndex * 100 + objectIndex),
                                "detection",
                                task.targetClasses().getFirst(),
                                GEOMETRY_FACTORY.toGeometry(new Envelope(x, x + 6, y, y + 6))));
            }
            CapturedRoi roi =
                    new CapturedRoi(
                            roiId,
                            geometry,
                            objects,
                            new LabelCounts(objectCount, imageIndex % 3, (imageIndex + 1) % 4));
            RasterInputCodec.ActionSourceRevision revision = sourceRevision(imageId);
            CapturedImage capture =
                    new CapturedImage(
                            imageId,
                            CanonicalCodec.sha256(imageId.getBytes(StandardCharsets.UTF_8)),
                            Optional.of(imageIndex < 5 ? "Control" : "Low dose"),
                            revision,
                            task,
                            List.of(roi),
                            java.util.Set.copyOf(task.targetClasses()));
            var rectangle = new SourceIdentityCodec.IntRect(100, 120 + imageIndex, 800, 800);
            var level =
                    new PinnedSourceLevel(
                            0,
                            0,
                            new Dimensions(2_000, 2_000),
                            new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0));
            var artifact =
                    new DerivedArtifactPlan(
                            SourceContainer.PNG,
                            0,
                            new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                            rectangle,
                            new SourceIdentityCodec.ScaleTranslate2D(
                                    1, 1, rectangle.x(), rectangle.y()),
                            2_560_000);
            var sourcePlan =
                    new TrainingRoiSourcePlan(
                            roiId,
                            CanonicalGeometryCodec.canonicalDigest(geometry),
                            level,
                            rectangle,
                            artifact);
            images.add(
                    new PlannedImage(
                            capture,
                            new TrainingImageSourcePlan(revision, task, List.of(sourcePlan))));
            names.put(imageId, "Leydig slide " + String.format("%02d", imageIndex + 1));
        }
        ProjectPlan plan = new ProjectPlan(images, names);
        TrainingAssignment assignment =
                TrainingAssignment.generate(
                        TrainingProjectIndex.from(plan, SplitUnit.IMAGE), 20, new Random(7));
        return new PopulatedProject(plan, assignment);
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision(String imageId) {
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "test-source",
                        "a".repeat(64),
                        Optional.empty(),
                        2_000,
                        2_000,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.5, 0.5));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        imageId,
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "b".repeat(64)),
                source);
    }

    private static String uuid(int value) {
        return String.format("00000000-0000-4000-8000-%012x", value);
    }
}

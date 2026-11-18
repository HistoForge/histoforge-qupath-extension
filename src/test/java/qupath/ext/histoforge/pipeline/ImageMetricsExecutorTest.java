package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.spatial.SourcePlanningInputs;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Metrics retain a saved detached capture and load editable state only for successful repairs. */
class ImageMetricsExecutorTest {

    @Test
    void existingParentsNeedOneLiveSaveTwoRevisionCapturesAndNoStoredReadOrViewerSwap()
            throws Exception {
        try (Harness harness = new Harness(true)) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                    result.outcome(),
                    result.toString());
            assertFalse(result.hierarchySaved());
            assertEquals(new Scalar(1), objectCount(result));
            assertEquals(List.of(harness.live), harness.savedImages);
            assertEquals(0, harness.storedReads.get());
            assertEquals(0, harness.viewerSwaps.get());
            assertEquals(2, harness.revisionCaptures.get());
            assertSame(harness.live, harness.active.get());
            assertEquals(List.of("save-live", "evaluate", "publish"), harness.events);
            assertTrue(harness.hasCachedResult());
        }
    }

    @Test
    void successfulParentRepairLoadsAfterEvaluationAndUsesTheOrdinaryFinalSave() throws Exception {
        try (Harness harness = new Harness(false)) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertEquals(new Scalar(1), objectCount(result));
            assertEquals(1, harness.storedReads.get());
            assertEquals(List.of(harness.live, harness.stored), harness.savedImages);
            assertEquals(1, harness.viewerSwaps.get());
            assertSame(harness.stored, harness.active.get());
            assertTrue(harness.liveObject.getParent().isRootObject());
            assertEquals(
                    ImageDataRevisionDigester.capture(
                            harness.request.entry().getID(),
                            SourceIdentityCodec.imageSourceIdentityDigest(
                                    harness.source.identity()),
                            harness.stored,
                            harness.request.clientBuild().limits()),
                    result.metrics().orElseThrow().sourceRevision());
            assertEquals(
                    RecipeExecutorTestData.ROI_A,
                    harness.storedObject.getParent().getID().toString());
            assertEquals(
                    List.of("save-live", "evaluate", "read", "save-working", "swap", "publish"),
                    harness.events);
            assertTrue(harness.hasCachedResult());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentLiveEditConflictsWithTheOriginalCapture(boolean alreadyNormalized)
            throws Exception {
        try (Harness harness = new Harness(alreadyNormalized)) {
            harness.afterEvaluation = () -> harness.live.setProperty("user-note", "edited");

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.CONFLICT, result.outcome(), result.toString());
            assertEquals(
                    RunResults.FailureType.REVISION_CONFLICT,
                    result.imageFailures().getFirst().type());
            harness.assertUnpublished(result);
            assertEquals(List.of(harness.live), harness.savedImages);
            assertEquals(alreadyNormalized ? 0 : 1, harness.storedReads.get());
            assertEquals("edited", harness.live.getProperty("user-note"));
            assertEquals(alreadyNormalized, !harness.liveObject.getParent().isRootObject());
        }
    }

    @Test
    void lateLoadRejectsStoredEditsBeforeApplyingRepairsAndDoesNotSaveLiveAgain() throws Exception {
        try (Harness harness = new Harness(false)) {
            harness.afterEvaluation =
                    () -> {
                        harness.live.setProperty("user-note", "saved during calculation");
                        harness.stored.setProperty("user-note", "saved during calculation");
                    };

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.CONFLICT, result.outcome(), result.toString());
            harness.assertUnpublished(result);
            assertEquals(1, harness.storedReads.get());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertTrue(harness.liveObject.getParent().isRootObject());
            assertTrue(harness.storedObject.getParent().isRootObject());
            assertEquals(List.of("save-live", "evaluate", "read"), harness.events);
        }
    }

    @Test
    void sourceIdentityChangePreventsReadOnlyPublicationWithoutLoadingAWorkingImage()
            throws Exception {
        try (Harness harness = new Harness(true)) {
            harness.afterEvaluation = () -> harness.source = capturedSource("e".repeat(64));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.CONFLICT, result.outcome(), result.toString());
            harness.assertUnpublished(result);
            assertEquals(0, harness.storedReads.get());
            assertEquals(List.of(harness.live), harness.savedImages);
        }
    }

    @Test
    void cancellationAfterEvaluationDoesNotOpenOrMutateAWorkingImage() throws Exception {
        try (Harness harness = new Harness(false)) {
            harness.afterEvaluation = () -> harness.cancel.set(true);

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.CANCELLED, result.outcome());
            harness.assertUnpublished(result);
            assertEquals(0, harness.storedReads.get());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertTrue(harness.liveObject.getParent().isRootObject());
            assertTrue(harness.storedObject.getParent().isRootObject());
        }
    }

    @Test
    void compilationFailureDoesNotOpenAWorkingImageOrPublishValues() throws Exception {
        try (Harness harness = new Harness(false, metricModule("return ["))) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertEquals(RunResults.FailureType.METRICS, result.imageFailures().getFirst().type());
            harness.assertUnpublished(result);
            assertEquals(0, harness.storedReads.get());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertTrue(harness.liveObject.getParent().isRootObject());
        }
    }

    @Test
    void failedRegionProducesNoValuesOrParentRepairAndNeedsNoWorkingImage() throws Exception {
        try (Harness harness = new Harness(false, metricModule("return [wrong: 1]"))) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertTrue(result.metrics().orElseThrow().regionResults().isEmpty());
            assertEquals(1, result.metrics().orElseThrow().regionFailures().size());
            assertFalse(harness.hasCachedResult());
            assertEquals(0, harness.storedReads.get());
            assertEquals(0, harness.viewerSwaps.get());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertTrue(harness.liveObject.getParent().isRootObject());
            assertTrue(harness.storedObject.getParent().isRootObject());
        }
    }

    @Test
    void finalSaveFailureDoesNotPublishOrChangeTheLiveHierarchy() throws Exception {
        try (Harness harness = new Harness(false)) {
            harness.saveWorkingFailure = new IOException("normalization save failed");

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertEquals(
                    RunResults.FailureType.PERSISTENCE, result.imageFailures().getFirst().type());
            harness.assertUnpublished(result);
            assertEquals(1, harness.storedReads.get());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertTrue(harness.liveObject.getParent().isRootObject());
            assertEquals(List.of("save-live", "evaluate", "read", "save-working"), harness.events);
        }
    }

    @Test
    void initialSaveFailureStopsBeforeCaptureEvaluationOrStoredRead() throws Exception {
        try (Harness harness = new Harness(true)) {
            harness.saveLiveFailure = new IOException("initial save failed");

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertEquals(
                    RunResults.FailureType.PERSISTENCE, result.imageFailures().getFirst().type());
            harness.assertUnpublished(result);
            assertEquals(0, harness.storedReads.get());
            assertEquals(0, harness.revisionCaptures.get());
            assertTrue(harness.savedImages.isEmpty());
            assertEquals(List.of("save-live"), harness.events);
        }
    }

    @Test
    void navigationAwayUsesStoredVerificationWithoutNavigatingBackOrSavingAgain() throws Exception {
        try (Harness harness = new Harness(true)) {
            harness.afterEvaluation = () -> harness.active.set(null);

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                    result.outcome(),
                    result.toString());
            assertEquals(new Scalar(1), objectCount(result));
            assertEquals(1, harness.storedReads.get());
            assertEquals(0, harness.viewerSwaps.get());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertEquals(List.of("save-live", "evaluate", "read", "publish"), harness.events);
            assertNull(harness.active.get());
        }
    }

    private static Scalar objectCount(RunResults.ImageRunResult result) {
        return (Scalar)
                result.metrics()
                        .orElseThrow()
                        .regionResults()
                        .values()
                        .iterator()
                        .next()
                        .values()
                        .get("object_count");
    }

    private static final class Harness implements AutoCloseable {

        private final ImageData<BufferedImage> live = image();
        private final ImageData<BufferedImage> stored = image();
        private final PathObject liveObject;
        private final PathObject storedObject;
        private final AtomicReference<ImageData<BufferedImage>> active =
                new AtomicReference<>(live);
        private final AtomicInteger storedReads = new AtomicInteger();
        private final AtomicInteger viewerSwaps = new AtomicInteger();
        private final AtomicInteger revisionCaptures = new AtomicInteger();
        private final AtomicInteger publications = new AtomicInteger();
        private final AtomicBoolean cancel = new AtomicBoolean();
        private final List<ImageData<BufferedImage>> savedImages = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final MetricsEngine metrics = spy(new MetricsEngine());
        private final ImageMetricsExecutor executor;
        private final ImageActionExecutor.Request request;
        private ImageSourceCapture.CapturedSource source = capturedSource("d".repeat(64));
        private Runnable afterEvaluation = () -> {};
        private IOException saveLiveFailure;
        private IOException saveWorkingFailure;

        private Harness(boolean normalized) throws Exception {
            this(normalized, RecipeExecutorTestData.fieldMetricOnlyModule());
        }

        @SuppressWarnings("unchecked")
        private Harness(boolean normalized, ModuleSnapshot module) throws Exception {
            liveObject = addObject(live, normalized);
            storedObject = addObject(stored, normalized);
            QuPathGUI qupath = mock(QuPathGUI.class);
            Project<BufferedImage> project = mock(Project.class);
            ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
            QuPathViewer viewer = mock(QuPathViewer.class);
            when(entry.getID()).thenReturn(RecipeExecutorTestData.IMAGE_ID);
            when(qupath.getProject()).thenReturn(project);
            when(project.getEntry(any(ImageData.class))).thenReturn(entry);
            when(viewer.getImageData()).thenAnswer(ignored -> active.get());
            when(entry.readImageData())
                    .thenAnswer(
                            ignored -> {
                                storedReads.incrementAndGet();
                                events.add("read");
                                return stored;
                            });
            doAnswer(
                            invocation -> {
                                ImageData<BufferedImage> image = invocation.getArgument(0);
                                events.add(image == live ? "save-live" : "save-working");
                                IOException failure =
                                        image == live ? saveLiveFailure : saveWorkingFailure;
                                if (failure != null) {
                                    throw failure;
                                }
                                savedImages.add(image);
                                return null;
                            })
                    .when(entry)
                    .saveImageData(any());
            doAnswer(
                            invocation -> {
                                viewerSwaps.incrementAndGet();
                                events.add("swap");
                                active.set(invocation.getArgument(0));
                                return null;
                            })
                    .when(viewer)
                    .setImageData(any());
            doAnswer(
                            invocation -> {
                                events.add("evaluate");
                                Object result = invocation.callRealMethod();
                                afterEvaluation.run();
                                return result;
                            })
                    .when(metrics)
                    .evaluate(any(), any(), anyBoolean(), any());
            doAnswer(
                            invocation -> {
                                publications.incrementAndGet();
                                events.add("publish");
                                return invocation.callRealMethod();
                            })
                    .when(metrics)
                    .publish(any(), any());
            ImageLifecycle lifecycle =
                    new ImageLifecycle(
                            qupath,
                            () -> List.of(viewer),
                            Runnable::run,
                            (imageId, sourceDigest, image, limits) -> {
                                revisionCaptures.incrementAndGet();
                                return ImageDataRevisionDigester.capture(
                                        imageId, sourceDigest, image, limits);
                            },
                            ignored -> source);
            executor = new ImageMetricsExecutor(new ImageActionRegistry(), lifecycle, metrics);
            request =
                    new ImageActionExecutor.Request(
                            RecipeExecutorTestData.ACTION_ID,
                            RecipeExecutorTestData.clientBuild(1_000_000),
                            entry,
                            module,
                            new ActionSelectionCodec.Metrics(
                                    new ActionSelectionCodec.RoiRegions(
                                            Set.of(RecipeExecutorTestData.ROI_A))));
        }

        private RunResults.ImageRunResult execute() {
            return executor.execute(request, cancel::get);
        }

        private boolean hasCachedResult() {
            return metrics.hasCachedResult(
                    request.entry().getID(), request.module().module().moduleRef());
        }

        private void assertUnpublished(RunResults.ImageRunResult result) {
            assertFalse(result.hierarchySaved());
            assertTrue(result.metrics().isEmpty());
            assertFalse(hasCachedResult());
            assertEquals(0, publications.get());
            assertEquals(0, viewerSwaps.get());
            assertSame(live, active.get());
        }

        @Override
        public void close() throws Exception {
            live.close();
            stored.close();
        }
    }

    private static ImageData<BufferedImage> image() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData("metrics-lifecycle", RecipeExecutorTestData.ROI_A);
        image.getHierarchy()
                .getRootObject()
                .setID(UUID.fromString("90000000-0000-4000-8000-000000000001"));
        return image;
    }

    private static PathObject addObject(ImageData<BufferedImage> image, boolean normalized) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createPolygonROI(
                                new double[] {10, 20, 20, 10},
                                new double[] {10, 10, 20, 20},
                                ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Manual"));
        object.setID(UUID.fromString(RecipeExecutorTestData.MANUAL_PARENT_ID));
        PathObject root = image.getHierarchy().getRootObject();
        PathObject parent = normalized ? root.getChildObjects().iterator().next() : root;
        parent.addChildObject(object);
        return object;
    }

    private static ImageSourceCapture.CapturedSource capturedSource(String descriptorDigest) {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "metrics-lifecycle.fixture",
                        descriptorDigest,
                        Optional.empty(),
                        256,
                        128,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1, 1));
        var transform = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new ImageSourceCapture.CapturedSource(
                identity,
                0,
                transform,
                List.of(
                        new SourcePlanningInputs.SourceLevel(
                                0, new SourceViewGeometry.Dimensions(256, 128), transform)),
                expected -> {});
    }

    private static ModuleSnapshot metricModule(String body) {
        ModuleSnapshot module = RecipeExecutorTestData.fieldMetricOnlyModule();
        String source =
                module.metrics()
                        .orElseThrow()
                        .script()
                        .source()
                        .replace("return [object_count: children.size()]", body);
        var script =
                new ModuleSnapshot.ScriptSnapshot(
                        "metrics.groovy",
                        source,
                        CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)));
        JsonObject scriptDigests = new JsonObject();
        scriptDigests.addProperty(script.filename(), script.digest());
        return new ModuleSnapshot(
                module.deploymentId(),
                module.module(),
                module.exactRecipe(),
                CanonicalCodec.canonicalDigest(scriptDigests),
                module.activatedModels(),
                module.scripts(),
                Optional.of(
                        new ModuleSnapshot.MetricScriptSnapshot(
                                script, module.metrics().orElseThrow().schema())));
    }
}

package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.GenerationStoreTestData;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricImageResolver;
import qupath.ext.histoforge.data.MetricImageResolver.MetricSelection;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Authoritative capture and exact cache behavior at the shared UI/export resolver boundary. */
class MetricImageResolverTest {

    @Test
    @SuppressWarnings("unchecked")
    void exactHitAndMissBothRecaptureBeforeCacheResolution() throws Exception {
        String imageId = "metric-image";
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        var store = GenerationStoreTestData.capturedStore();
        var record = store.currentGenerationRecords().values().iterator().next();
        var attributed =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(12, 12, 5, 5, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Cell"));
        var manual =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(30, 12, 5, 5, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Cell"));
        // Use the persisted generation's exact owner and add its boundary to the selection.
        String owner =
                ((qupath.ext.histoforge.contract.ExecutionScopeCodec.Roi)
                                qupath.ext.histoforge.contract.ExecutionScopeCodec.regionOf(
                                        record.output().scope()))
                        .roiId();
        hierarchy.addObject(
                PlanningTestData.administrativeRoi(
                        owner, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        var provenance =
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        record.output(),
                        record.outputGenerationId());
        ProvenanceCodec.writeMetadata(attributed.getMetadata(), provenance);
        // A manual object's unambiguous owner is ROI B.
        ((qupath.lib.objects.PathROIObject) manual)
                .setROI(ROIs.createRectangleROI(130, 12, 5, 5, ImagePlane.getDefaultPlane()));
        hierarchy.addObjects(List.of(attributed, manual));
        ImageData<BufferedImage> imageData = mock(ImageData.class);
        ImageServer<BufferedImage> server = mock(ImageServer.class);
        doReturn(hierarchy).when(imageData).getHierarchy();
        doReturn(server).when(imageData).getServer();
        String encodedStore = ImageDataStateCodec.encodeGenerationStore(store);
        doReturn(
                        new HashMap<>(
                                java.util.Map.of(
                                        HistoforgePersistenceKeys.GENERATION_STORE, encodedStore)))
                .when(imageData)
                .getProperties();
        doReturn(encodedStore)
                .when(imageData)
                .getProperty(HistoforgePersistenceKeys.GENERATION_STORE);
        doReturn(200).when(server).getWidth();
        doReturn(100).when(server).getHeight();

        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        doReturn(imageId).when(entry).getID();
        AtomicReference<RasterInputCodec.ActionSourceRevision> currentRevision =
                new AtomicReference<>(sourceRevision(imageId, "8".repeat(64)));
        AtomicInteger captures = new AtomicInteger();
        ImageLifecycle lifecycle = mock(ImageLifecycle.class);
        doAnswer(
                        invocation -> {
                            invocation.<Runnable>getArgument(3).run();
                            captures.incrementAndGet();
                            ImageLifecycle.ReadOnlyCaptureOperation<Object> operation =
                                    invocation.getArgument(4);
                            RasterInputCodec.ActionSourceRevision revision = currentRevision.get();
                            Object value =
                                    operation.capture(
                                            imageData,
                                            revision,
                                            mock(ImageSourceCapture.CapturedSource.class));
                            return new ImageLifecycle.ReadOnlyImageCapture<>(
                                    ImageLifecycle.ImageDataMaterialization.STORED_QPDATA,
                                    revision,
                                    value);
                        })
                .when(lifecycle)
                .captureReadOnly(any(), any(), any(), any(), any());

        MetricsEngine engine = new MetricsEngine();
        ImageActionRegistry registry = new ImageActionRegistry();
        MetricImageResolver resolver = new MetricImageResolver(registry, lifecycle, engine);
        ModuleSnapshot module = PlanningTestData.module(Set.of(), true);
        MetricSelection selection =
                new MetricSelection(
                        module,
                        new ActionSelectionCodec.RoiRegions(Set.of(owner, PlanningTestData.ROI_B)));

        MetricImageResolver.ResolvedImage computed;
        try (var reservation = registry.reserve(imageId);
                var executor = Executors.newSingleThreadExecutor()) {
            computed =
                    executor.submit(
                                    () ->
                                            resolver.resolve(
                                                    entry,
                                                    PlanningTestData.clientBuild(),
                                                    List.of(selection),
                                                    true,
                                                    () -> false))
                            .get(5, TimeUnit.SECONDS);
        }
        var firstEvaluation = computed.modules().getFirst().evaluation().orElseThrow();
        assertFalse(firstEvaluation.cacheHit());
        assertTrue(computed.generationStore().isPresent());
        assertEquals(store, computed.generationStore().orElseThrow());
        assertEquals(
                java.util.Map.of(attributed.getID(), provenance),
                computed.modules().getFirst().attributedObjects());
        engine.publish(firstEvaluation, currentRevision.get().imageDataRevision());

        var reused =
                resolver.resolve(
                        entry,
                        PlanningTestData.clientBuild(),
                        List.of(selection),
                        MetricImageResolver.CapturePurpose.PROJECT_CACHE,
                        false,
                        () -> false);
        assertTrue(reused.modules().getFirst().evaluation().orElseThrow().cacheHit());
        assertTrue(reused.generationStore().isEmpty());
        assertTrue(reused.modules().getFirst().attributedObjects().isEmpty());
        assertEquals(
                computed.modules().getFirst().context(), reused.modules().getFirst().context());
        assertEquals(
                firstEvaluation.regionResults(),
                reused.modules().getFirst().evaluation().orElseThrow().regionResults());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        resolver.resolve(
                                entry,
                                PlanningTestData.clientBuild(),
                                List.of(selection),
                                MetricImageResolver.CapturePurpose.PROJECT_CACHE,
                                true,
                                () -> false));

        currentRevision.set(sourceRevision(imageId, "9".repeat(64)));
        var mismatched =
                resolver.resolve(
                        entry,
                        PlanningTestData.clientBuild(),
                        List.of(selection),
                        false,
                        () -> false);
        assertEquals(Optional.empty(), mismatched.modules().getFirst().evaluation());
        assertEquals(3, captures.get());
        assertFalse(engine.hasCachedResult(imageId, PlanningTestData.MODULE_REF));
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision(
            String imageId, String imageDataDigest) {
        SourceIdentityCodec.ImageSourceIdentity source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "metric-resolver-test",
                        "7".repeat(64),
                        Optional.empty(),
                        200,
                        100,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        imageId,
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        imageDataDigest),
                source);
    }
}

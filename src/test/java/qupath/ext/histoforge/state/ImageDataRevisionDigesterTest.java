package qupath.ext.histoforge.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.StainVector;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.DefaultPathObjectConnectionGroup;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnectionGroup;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.DefaultTMAGrid;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

class ImageDataRevisionDigesterTest {

    private static final UUID ROOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID TUBULE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CELL_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DETECTION_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String SEMANTIC_PROPERTY = "histoforge:test-state";
    @TempDir Path tempDirectory;

    @Test
    void deterministicImageHasOneGoldenSemanticRevision() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            String preimage = canonicalPreimage(imageData);
            assertEquals(preimage, canonicalPreimage(imageData));
            String digest = ImageDataRevisionDigester.imageDataContentDigest(imageData);
            assertEquals(CanonicalCodec.sha256(preimage.getBytes(StandardCharsets.UTF_8)), digest);
            JsonFixture fixture = readGoldenFixture("image-data-revision-v1.json");

            assertEquals(fixture.canonicalPreimage(), preimage);
            assertEquals(fixture.imageDataContentDigest(), digest);

            var captured =
                    ImageDataRevisionDigester.capture(
                            "project-image-1", "a".repeat(64), imageData, generousLimits());
            assertEquals("project-image-1", captured.imageId());
            assertEquals("a".repeat(64), captured.imageSourceDigest());
            assertEquals(digest, captured.imageDataContentDigest());
        }
    }

    @Test
    void connectionFreeImageHasItsRecordedRevisionAndResourceTotals() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            imageData.removeProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
            JsonFixture fixture = readGoldenFixture("image-data-revision-no-connections-v1.json");
            assertEquals(fixture.canonicalPreimage(), canonicalPreimage(imageData));
            assertEquals(
                    fixture.imageDataContentDigest(),
                    ImageDataRevisionDigester.imageDataContentDigest(imageData));
            // Recorded on 4b15094 before changing hierarchy identity storage.
            assertEquals(
                    new ImageDataRevisionDigester.CaptureSize(4, 16, 2, 218),
                    ImageDataRevisionDigester.measureCaptureSize(imageData));
        }
    }

    @Test
    void connectionFreePublicSavePreservesRevisionAndResourceTotals() throws Exception {
        var entry = createSavedEntry("connection-free");
        String before;
        ImageDataRevisionDigester.CaptureSize size;
        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            imageData.removeProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
            before = canonicalPreimage(imageData);
            size = ImageDataRevisionDigester.measureCaptureSize(imageData);
            entry.saveImageData(imageData);
            assertEquals(before, canonicalPreimage(imageData));
            assertEquals(size, ImageDataRevisionDigester.measureCaptureSize(imageData));
        }
        try (ImageData<BufferedImage> reloaded = entry.readImageData()) {
            assertEquals(before, canonicalPreimage(reloaded));
            assertEquals(size, ImageDataRevisionDigester.measureCaptureSize(reloaded));
        }
    }

    @Test
    void independentReadsOfOnePublicSaveHaveTheSameRevision() throws Exception {
        ProjectImageEntry<BufferedImage> entry = createSavedEntry("saved-reads");

        try (ImageData<BufferedImage> first = entry.readImageData();
                ImageData<BufferedImage> second = entry.readImageData()) {
            String firstPreimage = canonicalPreimage(first);
            String secondPreimage = canonicalPreimage(second);
            String digest = ImageDataRevisionDigester.imageDataContentDigest(first);

            assertEquals(firstPreimage, secondPreimage);
            assertEquals(digest, ImageDataRevisionDigester.imageDataContentDigest(second));
        }
    }

    @Test
    void publicSaveRoundTripPreservesTheSemanticRevision() throws Exception {
        Path projectDirectory = Files.createDirectory(tempDirectory.resolve("round-trip-project"));
        Path source = tempDirectory.resolve("round-trip.png");
        BufferedImage image = new BufferedImage(48, 36, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "PNG", source.toFile()));

        var project = Projects.createProject(projectDirectory.toFile(), BufferedImage.class);
        ProjectImageEntry<BufferedImage> entry;
        try (var server = ImageServers.buildServer(source.toUri())) {
            entry = project.addImage(server.getBuilder());
        }

        String before;
        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            populateImageData(imageData);
            imageData.setColorDeconvolutionStains(
                    new ColorDeconvolutionStains(
                            "High precision",
                            StainVector.createStainVector("First", 0.12345678901234567, 0.75, 0.25),
                            StainVector.createStainVector(
                                    "Second", 0.875, 0.23456789012345678, 0.375),
                            StainVector.createStainVector(
                                    "Third", 0.625, 0.3125, 0.4567890123456789),
                            254.12345678901235,
                            253.98765432109877,
                            252.5));
            imageData
                    .getHistoryWorkflow()
                    .addStep(
                            new DefaultScriptableWorkflowStep(
                                    "User workflow step",
                                    Map.of("threshold", 0.5, "enabled", true),
                                    "print 'user step'"));
            before = canonicalPreimage(imageData);
            entry.saveImageData(imageData);
            assertEquals(before, canonicalPreimage(imageData));
        }
        try (ImageData<BufferedImage> reloaded = entry.readImageData()) {
            assertEquals(before, canonicalPreimage(reloaded));
            entry.saveImageData(reloaded);
        }
        try (ImageData<BufferedImage> reloadedAgain = entry.readImageData()) {
            assertEquals(before, canonicalPreimage(reloadedAgain));
        }
    }

    @Test
    void stainDigestIsStableUnderQuPathPersistenceNormalization() throws Exception {
        Random random = new Random(0x484953544f464f52L);
        for (int index = 0; index < 100; index++) {
            assertStainProjectionStable(randomStains(random, index));
        }
    }

    @Test
    void stainDigestCanonicalizesRoundingBoundaryAndTwoCycle() throws Exception {
        ColorDeconvolutionStains boundary = roundingBoundaryStains();
        String boundarySaved = savedStains(boundary);
        ColorDeconvolutionStains boundaryProjected = projectedStains(boundarySaved);
        assertNotEquals(boundarySaved, savedStains(boundaryProjected));
        assertStainProjectionStable(boundary);

        ColorDeconvolutionStains cycle = twoCycleStains();
        String first = savedStains(cycle);
        String second = savedStains(projectedStains(first));
        String third = savedStains(projectedStains(second));
        assertNotEquals(first, second);
        assertEquals(first, third);
        assertStainProjectionStable(cycle);
    }

    @Test
    void stainDigestPreservesQuPathPersistedChanges() throws Exception {
        try (ImageData<BufferedImage> baseline = createDeterministicImageData();
                ImageData<BufferedImage> changed = createDeterministicImageData()) {
            var stains = baseline.getColorDeconvolutionStains();
            changed.setColorDeconvolutionStains(
                    stains.changeMaxValues(
                            stains.getMaxRed() - 1e-9, stains.getMaxGreen(), stains.getMaxBlue()));

            assertNotEquals(
                    ImageDataRevisionDigester.imageDataContentDigest(baseline),
                    ImageDataRevisionDigester.imageDataContentDigest(changed));
        }
    }

    @Test
    void userWorkflowLookalikesRemainPartOfTheRevision() throws Exception {
        assertSemanticChange(
                image ->
                        image.getHistoryWorkflow()
                                .addStep(
                                        new DefaultScriptableWorkflowStep(
                                                "Set image type",
                                                Map.of("user", true),
                                                "setImageType('BRIGHTFIELD_H_E')")));
        assertSemanticChange(
                image ->
                        image.getHistoryWorkflow()
                                .addStep(
                                        new DefaultScriptableWorkflowStep(
                                                "Set color deconvolution stains",
                                                Map.of(),
                                                "setColorDeconvolutionStains('not a QuPath"
                                                        + " stain');")));
        assertSemanticChange(
                image -> {
                    String argument =
                            ColorDeconvolutionStains.getColorDeconvolutionStainsAsString(
                                    image.getColorDeconvolutionStains(), 8);
                    image.getHistoryWorkflow()
                            .addStep(
                                    stainWorkflowStep(
                                            argument, GeneralTools.parseArgStringValues(argument)));
                });
        assertSemanticChange(
                image -> {
                    String generated =
                            ColorDeconvolutionStains.getColorDeconvolutionStainsAsString(
                                    image.getColorDeconvolutionStains(), 32);
                    String argument =
                            generated.substring(0, generated.length() - 1)
                                    + ", \"User note\" : \"keep me\"}";
                    image.getHistoryWorkflow()
                            .addStep(
                                    stainWorkflowStep(
                                            argument, GeneralTools.parseArgStringValues(argument)));
                });
    }

    @Test
    void retainedWorkflowStepOrderChangesTheRevision() throws Exception {
        try (ImageData<BufferedImage> first = createDeterministicImageData();
                ImageData<BufferedImage> second = createDeterministicImageData()) {
            String argument =
                    ColorDeconvolutionStains.getColorDeconvolutionStainsAsString(
                            first.getColorDeconvolutionStains(), 8);
            var lookalike =
                    stainWorkflowStep(argument, GeneralTools.parseArgStringValues(argument));
            var user = new DefaultScriptableWorkflowStep("User workflow step", "print 'user'");
            first.getHistoryWorkflow().addSteps(List.of(lookalike, user));
            second.getHistoryWorkflow().addSteps(List.of(user, lookalike));

            assertNotEquals(
                    ImageDataRevisionDigester.imageDataContentDigest(first),
                    ImageDataRevisionDigester.imageDataContentDigest(second));
        }
    }

    @Test
    void everySavedSemanticComponentChangesTheRevision() throws Exception {
        assertSemanticChange(image -> image.setImageType(ImageType.BRIGHTFIELD_H_DAB));
        assertSemanticChange(
                image ->
                        image.setColorDeconvolutionStains(
                                image.getColorDeconvolutionStains().changeName("Changed stains")));
        assertSemanticChange(
                image -> {
                    var stains = image.getColorDeconvolutionStains();
                    image.setColorDeconvolutionStains(
                            stains.changeMaxValues(
                                    stains.getMaxRed() - 1,
                                    stains.getMaxGreen(),
                                    stains.getMaxBlue()));
                });
        assertSemanticChange(
                image ->
                        image.getHistoryWorkflow()
                                .addStep(
                                        new DefaultScriptableWorkflowStep(
                                                "Changed workflow", "print 'changed'")));
        assertSemanticChange(image -> object(image, TUBULE_ID).setName("Changed tubule"));
        assertSemanticChange(image -> object(image, TUBULE_ID).setColor(90, 80, 70));
        assertSemanticChange(image -> object(image, TUBULE_ID).setLocked(false));
        assertSemanticChange(image -> object(image, TUBULE_ID).refreshID());
        assertSemanticChange(
                image ->
                        ((PathROIObject) object(image, TUBULE_ID))
                                .setROI(ROIs.createRectangleROI(2, 3, 31, 25, null)));
        assertSemanticChange(
                image -> {
                    PathROIObject tubule = (PathROIObject) object(image, TUBULE_ID);
                    tubule.setROI(tubule.getROI().updatePlane(ImagePlane.getPlane(1, 2)));
                });
        assertSemanticChange(
                image ->
                        object(image, CELL_ID)
                                .getMeasurementList()
                                .put("Changed measurement", 9.5));
        assertSemanticChange(
                image -> object(image, CELL_ID).getMetadata().put("changed", "metadata"));
        assertSemanticChange(
                image ->
                        object(image, CELL_ID)
                                .setPathClass(PathClass.fromString("Round spermatid"), 0.75));
        assertSemanticChange(
                image ->
                        image.getHierarchy()
                                .getRootObject()
                                .addChildObject(object(image, CELL_ID)));
        assertSemanticChange(
                image -> {
                    PathObject tubule = object(image, TUBULE_ID);
                    PathObject cell = object(image, CELL_ID);
                    tubule.removeChildObject(cell);
                    tubule.addChildObject(cell);
                });
        assertSemanticChange(image -> image.setProperty(SEMANTIC_PROPERTY, "changed"));
    }

    @Test
    void readOnlyLazyStateDoesNotChangeTheRevision() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            PathObject polygon =
                    PathObjects.createAnnotationObject(
                            ROIs.createPolygonROI(
                                    List.of(
                                            new qupath.lib.geom.Point2(1, 1),
                                            new qupath.lib.geom.Point2(9, 1),
                                            new qupath.lib.geom.Point2(7, 7),
                                            new qupath.lib.geom.Point2(1, 6)),
                                    ImagePlane.getDefaultPlane()));
            Area disconnected = new Area(new Rectangle2D.Double(12, 1, 4, 4));
            disconnected.add(new Area(new Rectangle2D.Double(20, 1, 4, 4)));
            PathObject geometry =
                    PathObjects.createAnnotationObject(
                            RoiTools.getShapeROI(disconnected, ImagePlane.getDefaultPlane()));
            PathObject legacyArea = PathObjects.createAnnotationObject(createLegacyAreaRoi());
            assertEquals("qupath.lib.roi.PolygonROI", polygon.getROI().getClass().getName());
            assertEquals("qupath.lib.roi.GeometryROI", geometry.getROI().getClass().getName());
            assertEquals("qupath.lib.roi.AreaROI", legacyArea.getROI().getClass().getName());
            imageData.getHierarchy().addObjects(List.of(polygon, geometry, legacyArea));

            String baseline = ImageDataRevisionDigester.imageDataContentDigest(imageData);
            imageData.getHierarchy().getRootObject().getMeasurementList();
            object(imageData, DETECTION_ID).getMeasurementList();
            for (PathObject object : imageData.getHierarchy().getAllObjects(true)) {
                if (object.getROI() != null) {
                    object.getROI().getArea();
                    object.getROI().getLength();
                    object.getROI().getCentroidX();
                    object.getROI().getBoundsWidth();
                    object.getROI().getGeometry();
                    object.getROI().getShape();
                    object.getROI().getAllPoints();
                }
            }
            assertEquals(baseline, ImageDataRevisionDigester.imageDataContentDigest(imageData));
        }
    }

    @Test
    void persistedPropertyGraphsMustBeReadableByQuPath() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            imageData.setProperty(
                    "histoforge:nested-filter-test",
                    new ArrayList<>(List.of(new java.sql.Date(0))));

            ImageDataRevisionDigester.CorruptImageStateException error =
                    assertThrows(
                            ImageDataRevisionDigester.CorruptImageStateException.class,
                            () -> ImageDataRevisionDigester.imageDataContentDigest(imageData));
            assertTrue(error.getCause().getMessage().contains("cannot be serialized"));
            assertThrows(
                    ImageDataRevisionDigester.CorruptImageStateException.class,
                    () -> ImageDataRevisionDigester.measureCaptureSize(imageData));
        }
    }

    @Test
    void objectConnectionsUseAuthoritativeEndpointsAndIgnoreEndpointLazyState() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            PathObject cell = object(imageData, CELL_ID);
            PathObject detection = object(imageData, DETECTION_ID);
            imageData.setProperty(
                    DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS,
                    connections(cell, detection));

            String baseline = ImageDataRevisionDigester.imageDataContentDigest(imageData);
            detection.getMeasurementList();
            assertEquals(baseline, ImageDataRevisionDigester.imageDataContentDigest(imageData));

            imageData.setProperty(
                    DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS,
                    connections(detection, cell));
            assertNotEquals(baseline, ImageDataRevisionDigester.imageDataContentDigest(imageData));

            PathObject detached =
                    PathObjects.createDetectionObject(detection.getROI(), detection.getPathClass());
            detached.setID(detection.getID());
            imageData.setProperty(
                    DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS,
                    connections(detached, cell));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ImageDataRevisionDigester.imageDataContentDigest(imageData));
            assertThrows(
                    ImageDataRevisionDigester.CorruptImageStateException.class,
                    () -> ImageDataRevisionDigester.measureCaptureSize(imageData));

            imageData.setProperty(
                    DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS,
                    connections(cell, detached));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ImageDataRevisionDigester.imageDataContentDigest(imageData));
            assertThrows(
                    ImageDataRevisionDigester.CorruptImageStateException.class,
                    () -> ImageDataRevisionDigester.measureCaptureSize(imageData));
        }
    }

    @Test
    void presentInvalidConnectionValuesStillFailCapture() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            String key = DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS;
            for (Object invalid : new Object[] {null, "not object connections"}) {
                imageData.setProperty(key, invalid);
                assertTrue(imageData.getProperties().containsKey(key));
                var failure =
                        assertThrows(
                                ImageDataRevisionDigester.CorruptImageStateException.class,
                                () -> ImageDataRevisionDigester.imageDataContentDigest(imageData));
                assertEquals(
                        key + " must contain PathObjectConnections",
                        failure.getCause().getMessage());
                assertThrows(
                        ImageDataRevisionDigester.CorruptImageStateException.class,
                        () -> ImageDataRevisionDigester.measureCaptureSize(imageData));
            }
        }
    }

    @Test
    void nonpersistableConnectionValueIsExcludedFromTheRevision() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            String key = DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS;
            imageData.removeProperty(key);
            String baseline = canonicalPreimage(imageData);
            imageData.setProperty(key, new ExtensionSerializable("not saved by QuPath"));
            assertEquals(baseline, canonicalPreimage(imageData));
            imageData.setProperty(key, new Object());
            assertEquals(baseline, canonicalPreimage(imageData));
        }
    }

    @Test
    void generationStoreUsesItsDedicatedExactUtf8Limit() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            var emptyStore =
                    new GenerationStoreCodec.GenerationStore(
                            GenerationStoreCodec.SCHEMA_VERSION,
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of());
            String encoded =
                    ImageDataStateCodec.encodeGenerationStore(emptyStore, generousLimits());
            imageData.setProperty(HistoforgePersistenceKeys.GENERATION_STORE, encoded);
            long bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            var size = ImageDataRevisionDigester.measureCaptureSize(imageData);

            ImageDataRevisionDigester.capture(
                    "image",
                    "b".repeat(64),
                    imageData,
                    limits(
                            size.objects(),
                            size.geometryVertices(),
                            size.measurements(),
                            size.textUtf8Bytes(),
                            bytes));
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    ImageDataRevisionDigester.capture(
                                            "image",
                                            "b".repeat(64),
                                            imageData,
                                            limits(
                                                    size.objects(),
                                                    size.geometryVertices(),
                                                    size.measurements(),
                                                    size.textUtf8Bytes(),
                                                    bytes - 1)));
            assertTrue(error.getMessage().contains("max_generation_store_utf8_bytes"));
        }
    }

    @Test
    void unorderedMapsDoNotCreateFalseConflicts() throws Exception {
        try (ImageData<BufferedImage> first = createDeterministicImageData();
                ImageData<BufferedImage> second = createDeterministicImageData()) {
            first.setProperty("histoforge:order-a", "first");
            first.setProperty("histoforge:order-b", "second");
            object(first, CELL_ID).getMetadata().put("order-a", "first");
            object(first, CELL_ID).getMetadata().put("order-b", "second");

            second.setProperty("histoforge:order-b", "second");
            second.setProperty("histoforge:order-a", "first");
            object(second, CELL_ID).getMetadata().put("order-b", "second");
            object(second, CELL_ID).getMetadata().put("order-a", "first");

            assertEquals(
                    ImageDataRevisionDigester.imageDataContentDigest(first),
                    ImageDataRevisionDigester.imageDataContentDigest(second));
        }
    }

    @Test
    void tileAndTmaSpecificStateParticipateInTheRevision() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            PathObject tile =
                    PathObjects.createTileObject(
                            ROIs.createRectangleROI(32, 4, 4, 4, null), null, null);
            tile.setID(UUID.fromString("44444444-4444-4444-8444-444444444444"));
            imageData.getHierarchy().addObject(tile);

            TMACoreObject first = PathObjects.createTMACoreObject(10, 10, 6, false);
            first.setID(UUID.fromString("55555555-5555-4555-8555-555555555555"));
            TMACoreObject second = PathObjects.createTMACoreObject(20, 10, 6, false);
            second.setID(UUID.fromString("66666666-6666-4666-8666-666666666666"));
            imageData
                    .getHierarchy()
                    .setTMAGrid(DefaultTMAGrid.create(java.util.List.of(first, second), 2));
            String baseline = ImageDataRevisionDigester.imageDataContentDigest(imageData);

            first.setMissing(true);
            assertNotEquals(baseline, ImageDataRevisionDigester.imageDataContentDigest(imageData));
        }
    }

    @Test
    void operationalAndUnsavedPropertiesDoNotCreateFalseConflicts() throws Exception {
        ProjectImageEntry<BufferedImage> entry = createSavedEntry("excluded-properties");

        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            String baseline = ImageDataRevisionDigester.imageDataContentDigest(imageData);

            imageData.setProperty(
                    HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS,
                    "{\"action_id\":\"changed-operational-witness\"}");
            imageData.setProperty("histoforge:test-unsaved", new ExtensionSerializable("value"));

            assertEquals(baseline, ImageDataRevisionDigester.imageDataContentDigest(imageData));
            entry.saveImageData(imageData);
        }

        try (ImageData<BufferedImage> reloaded = entry.readImageData()) {
            assertNull(reloaded.getProperty("histoforge:test-unsaved"));
            assertEquals(
                    "{\"action_id\":\"changed-operational-witness\"}",
                    reloaded.getProperty(HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS));
        }
    }

    @Test
    void exactHierarchyLimitsPassAndEveryLowerLimitFails() throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            var size = ImageDataRevisionDigester.measureCaptureSize(imageData);
            var exact =
                    limits(
                            size.objects(),
                            size.geometryVertices(),
                            size.measurements(),
                            size.textUtf8Bytes());
            ImageDataRevisionDigester.capture("image", "b".repeat(64), imageData, exact);
            ImageDataRevisionDigester.requireProspectiveHierarchyFits(
                    imageData, List.of(), Map.of(), List.of(), java.util.Optional.empty(), exact);

            assertLimitFailure(
                    imageData,
                    limits(
                            size.objects() - 1,
                            size.geometryVertices(),
                            size.measurements(),
                            size.textUtf8Bytes()),
                    "max_hierarchy_objects");
            assertLimitFailure(
                    imageData,
                    limits(
                            size.objects(),
                            size.geometryVertices() - 1,
                            size.measurements(),
                            size.textUtf8Bytes()),
                    "max_hierarchy_geometry_vertices");
            assertLimitFailure(
                    imageData,
                    limits(
                            size.objects(),
                            size.geometryVertices(),
                            size.measurements() - 1,
                            size.textUtf8Bytes()),
                    "max_hierarchy_measurements");
            assertLimitFailure(
                    imageData,
                    limits(
                            size.objects(),
                            size.geometryVertices(),
                            size.measurements(),
                            size.textUtf8Bytes() - 1),
                    "max_hierarchy_text_utf8_bytes");
        }
    }

    private ProjectImageEntry<BufferedImage> createSavedEntry(String name) throws Exception {
        Path projectDirectory = Files.createDirectory(tempDirectory.resolve(name + "-project"));
        Path source = tempDirectory.resolve(name + ".png");
        BufferedImage image = new BufferedImage(48, 36, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "PNG", source.toFile()));

        var project = Projects.createProject(projectDirectory.toFile(), BufferedImage.class);
        ProjectImageEntry<BufferedImage> entry;
        try (var server = ImageServers.buildServer(source.toUri())) {
            entry = project.addImage(server.getBuilder());
        }

        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            populateImageData(imageData);
            entry.saveImageData(imageData);
        }
        return entry;
    }

    private static ImageData<BufferedImage> createDeterministicImageData() {
        BufferedImage image = new BufferedImage(48, 36, BufferedImage.TYPE_INT_RGB);
        var imageData =
                new ImageData<>(
                        new WrappedBufferedImageServer("semantic-revision-fixture", image),
                        ImageType.UNSET);
        populateImageData(imageData);
        return imageData;
    }

    private static ColorDeconvolutionStains randomStains(Random random, int index) {
        StainVector first = randomStainVector(random, "First");
        StainVector second = randomStainVector(random, "Second");
        double maxRed = 200 + random.nextDouble() * 55;
        double maxGreen = 200 + random.nextDouble() * 55;
        double maxBlue = 200 + random.nextDouble() * 55;
        if (index % 2 == 0) {
            return new ColorDeconvolutionStains(
                    "Random " + index, first, second, maxRed, maxGreen, maxBlue);
        }
        return new ColorDeconvolutionStains(
                "Random " + index,
                first,
                second,
                randomStainVector(random, "Third"),
                maxRed,
                maxGreen,
                maxBlue);
    }

    private static ColorDeconvolutionStains roundingBoundaryStains() {
        return new ColorDeconvolutionStains(
                "Rounding boundary",
                StainVector.createStainVector(
                        "First", 0.6037006456683776, 1.0293755259235164, 0.6030998023147586),
                StainVector.createStainVector("Second", 0.2, 0.3, 0.4),
                255,
                255,
                255);
    }

    private static ColorDeconvolutionStains twoCycleStains() {
        return new ColorDeconvolutionStains(
                "Two-cycle",
                StainVector.createStainVector(
                        "First", 0.0156236776795554, 0.8104077666507904, 0.5856578800356429),
                StainVector.createStainVector(
                        "Second", 0.48315595981211523, 0.23244922557389136, 0.8441135445117156),
                StainVector.createStainVector(
                        "Third", 0.5177024344622692, 0.5894124264241509, 0.6201428713841957),
                216.63977424228398,
                229.33363053044593,
                233.5565764371692);
    }

    private static void assertStainProjectionStable(ColorDeconvolutionStains stains)
            throws Exception {
        ColorDeconvolutionStains projected = projectedStains(savedStains(stains));
        try (ImageData<BufferedImage> before = createDeterministicImageData();
                ImageData<BufferedImage> after = createDeterministicImageData()) {
            before.setColorDeconvolutionStains(stains);
            after.setColorDeconvolutionStains(projected);
            assertEquals(
                    ImageDataRevisionDigester.imageDataContentDigest(before),
                    ImageDataRevisionDigester.imageDataContentDigest(after));
        }
    }

    private static String savedStains(ColorDeconvolutionStains stains) {
        return ColorDeconvolutionStains.getColorDeconvolutionStainsAsString(stains, 32);
    }

    private static ColorDeconvolutionStains projectedStains(String saved) {
        return ColorDeconvolutionStains.parseColorDeconvolutionStainsArg(saved);
    }

    private static DefaultScriptableWorkflowStep stainWorkflowStep(
            String argument, Map<String, ?> parameters) {
        return new DefaultScriptableWorkflowStep(
                "Set color deconvolution stains",
                parameters,
                "setColorDeconvolutionStains('" + argument + "');");
    }

    private static StainVector randomStainVector(Random random, String name) {
        return StainVector.createStainVector(
                name,
                0.1 + random.nextDouble(),
                0.1 + random.nextDouble(),
                0.1 + random.nextDouble());
    }

    private static void populateImageData(ImageData<BufferedImage> imageData) {
        imageData.getHierarchy().getRootObject().setID(ROOT_ID);
        imageData.setImageType(ImageType.BRIGHTFIELD_H_E);
        imageData.setProperty(SEMANTIC_PROPERTY, "schema=1;generation=fixture");
        imageData.setProperty(
                HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS,
                "{\"schema_version\":1,\"action_id\":\"fixture\"}");

        PathObject tubule =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(2, 3, 30, 25, null),
                        PathClass.fromString("Tubule"));
        tubule.setID(TUBULE_ID);
        tubule.setName("Seminiferous tubule");
        tubule.setColor(12, 34, 56);
        tubule.setLocked(true);
        tubule.getMetadata().put("review", "accepted");
        tubule.getMeasurementList().put("Area um^2", 750.0);

        PathObject cell =
                PathObjects.createCellObject(
                        ROIs.createEllipseROI(7, 8, 5, 4, null),
                        ROIs.createEllipseROI(8, 9, 2, 2, null),
                        PathClass.fromString("Spermatocyte"));
        cell.setID(CELL_ID);
        cell.setPathClass(PathClass.fromString("Spermatocyte"), 0.875);
        cell.getMetadata().put("stage", "pachytene");
        cell.getMeasurementList().put("Confidence", 0.875);

        PathObject detection =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(18, 10, 3, 4, null),
                        PathClass.fromString("Sertoli cell"));
        detection.setID(DETECTION_ID);

        tubule.addChildObjects(java.util.List.of(cell, detection));
        imageData.getHierarchy().addObject(tubule);
        imageData.setProperty(
                DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS,
                connections(cell, detection));
    }

    private static void assertSemanticChange(Consumer<ImageData<BufferedImage>> mutation)
            throws Exception {
        try (ImageData<BufferedImage> imageData = createDeterministicImageData()) {
            String before = ImageDataRevisionDigester.imageDataContentDigest(imageData);
            mutation.accept(imageData);
            assertNotEquals(before, ImageDataRevisionDigester.imageDataContentDigest(imageData));
        }
    }

    private static PathObject object(ImageData<BufferedImage> imageData, UUID id) {
        return imageData.getHierarchy().getAllObjects(true).stream()
                .filter(object -> object.getID().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static String canonicalPreimage(ImageData<?> imageData) {
        return new String(
                CanonicalCodec.canonicalBytes(
                        ImageDataRevisionDigester.canonicalContentPreimage(imageData)),
                StandardCharsets.UTF_8);
    }

    private static JsonFixture readGoldenFixture(String name) throws Exception {
        try (InputStream stream =
                ImageDataRevisionDigesterTest.class.getResourceAsStream("/contract/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing image-data revision fixture");
            }
            var object = CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
            return new JsonFixture(
                    new String(
                            CanonicalCodec.canonicalBytes(object.get("canonical_preimage")),
                            StandardCharsets.UTF_8),
                    object.get("image_data_content_digest").getAsString());
        }
    }

    private static void assertLimitFailure(
            ImageData<?> imageData,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            String expectedField) {
        LocalCapacityExceededException error =
                assertThrows(
                        LocalCapacityExceededException.class,
                        () ->
                                ImageDataRevisionDigester.capture(
                                        "image", "b".repeat(64), imageData, limits));
        assertEquals(expectedField, error.resource());
        assertTrue(error.observed() > error.limit());
        LocalCapacityExceededException countError =
                assertThrows(
                        LocalCapacityExceededException.class,
                        () ->
                                ImageDataRevisionDigester.requireProspectiveHierarchyFits(
                                        imageData,
                                        List.of(),
                                        Map.of(),
                                        List.of(),
                                        java.util.Optional.empty(),
                                        limits));
        assertEquals(error.resource(), countError.resource());
        assertEquals(error.observed(), countError.observed());
        assertEquals(error.limit(), countError.limit());
    }

    private static ExecutionLimitsCodec.ClientExecutionLimits generousLimits() {
        return limits(1_000, 100_000, 100_000, 1_000_000);
    }

    private static ExecutionLimitsCodec.ClientExecutionLimits limits(
            long objects, long vertices, long measurements, long textBytes) {
        return limits(objects, vertices, measurements, textBytes, 32L * 1024 * 1024);
    }

    private static ExecutionLimitsCodec.ClientExecutionLimits limits(
            long objects,
            long vertices,
            long measurements,
            long textBytes,
            long generationStoreBytes) {
        return new ExecutionLimitsCodec.ClientExecutionLimits(
                objects,
                vertices,
                measurements,
                textBytes,
                generationStoreBytes,
                1_000_000,
                10_000_000,
                1024L * 1024 * 1024);
    }

    private static PathObjectConnections connections(PathObject first, PathObject second) {
        PathObjectConnectionGroup group =
                new PathObjectConnectionGroup() {
                    @Override
                    public boolean containsObject(PathObject object) {
                        return object == first || object == second;
                    }

                    @Override
                    public Collection<PathObject> getPathObjects() {
                        return List.of(first, second);
                    }

                    @Override
                    public List<PathObject> getConnectedObjects(PathObject object) {
                        if (object == first) {
                            return List.of(second);
                        }
                        if (object == second) {
                            return List.of(first);
                        }
                        return List.of();
                    }
                };
        var connections = new PathObjectConnections();
        connections.addGroup(new DefaultPathObjectConnectionGroup(group));
        return connections;
    }

    private static ROI createLegacyAreaRoi() throws Exception {
        Constructor<?> constructor =
                Class.forName("qupath.lib.roi.AreaROI")
                        .getDeclaredConstructor(float[][].class, float[][].class, ImagePlane.class);
        constructor.setAccessible(true);
        return (ROI)
                constructor.newInstance(
                        new float[][] {{30, 36, 36, 30}},
                        new float[][] {{2, 2, 8, 8}},
                        ImagePlane.getDefaultPlane());
    }

    private record ExtensionSerializable(String value) implements Serializable {}

    private record JsonFixture(String canonicalPreimage, String imageDataContentDigest) {}
}

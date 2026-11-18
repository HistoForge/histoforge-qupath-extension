package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

/** Verifies model geometry is stable across QuPath's real ROI persistence boundary. */
class SourceInputGeometryTest {

    private static final SourceIdentityCodec.ImagePlaneIndex PLANE =
            new SourceIdentityCodec.ImagePlaneIndex(0, 0);
    private static final List<UUID> OBJECT_IDS =
            List.of(
                    UUID.fromString("11111111-1111-4111-8111-111111111111"),
                    UUID.fromString("22222222-2222-4222-8222-222222222222"));

    @TempDir Path tempDirectory;

    @ParameterizedTest
    @CsvSource({"false, 0", "true, 0", "false, 50000", "true, 50000"})
    void materializedGeometryMatchesNativeQuPathAcrossTwoSaveReloadCycles(
            boolean clip, double slideOffset) throws Exception {
        var translation = AffineTransformation.translationInstance(slideOffset, 2.5 * slideOffset);
        List<Geometry> source =
                List.of(
                                geometry(
                                        "POLYGON ((1.123456789 2.987654321, 28.765432109 3.123456789, "
                                                + "27.987654321 20.876543219, 2.234567891 21.234567891, "
                                                + "1.123456789 2.987654321))"),
                                geometry(
                                        "POLYGON ((3.123456789 3.234567891, 29.123456789 3.345678912, "
                                                + "28.876543219 22.123456789, 3.234567891 21.876543219, "
                                                + "3.123456789 3.234567891), "
                                                + "(9.123456789 8.234567891, 9.234567891 15.123456789, "
                                                + "20.123456789 15.234567891, 20.234567891 8.345678912, "
                                                + "9.123456789 8.234567891))"))
                        .stream()
                        .map(translation::transform)
                        .toList();
        Geometry target =
                translation.transform(
                        geometry(
                                "MULTIPOLYGON (((8 0, 12 0, 16 24, 8 24, 8 0)), "
                                        + "((22 0, 32 0, 32 24, 19 24, 22 0)))"));
        List<Geometry> clipped =
                source.stream()
                        .map(
                                value ->
                                        clip
                                                ? SourceInputGeometry.clipToTarget(
                                                                value,
                                                                PreparedGeometryFactory.prepare(
                                                                        target))
                                                        .orElseThrow()
                                                : value)
                        .toList();
        // Diagonal clipping at slide coordinates exposes native ROI rounding. Compare with
        // QuPath's saved representation, not mathematically exact post-save containment.
        List<Geometry> expected =
                clipped.stream()
                        .map(
                                value ->
                                        SourceInputGeometry.geometryForQuPathPersistence(
                                                value, PLANE))
                        .toList();

        for (int index = 0; index < source.size(); index++) {
            assertTrue(
                    PredictionRoiTest.nativeGeometry(
                                    PredictionRoiTest.referenceRoi(clipped.get(index)))
                            .equalsExact(expected.get(index)));
            assertTrue(
                    expected.get(index)
                            .equalsExact(
                                    SourceInputGeometry.geometryForQuPathPersistence(
                                            expected.get(index), PLANE)));
        }

        ProjectImageEntry<BufferedImage> entry = createEntry();
        try (var imageData = entry.readImageData()) {
            for (int index = 0; index < expected.size(); index++) {
                PathObject object =
                        PathObjects.createDetectionObject(
                                GeometryTools.geometryToROI(
                                        clipped.get(index), ImagePlane.getDefaultPlane()),
                                PathClass.fromString("Leydig cell"));
                object.setID(OBJECT_IDS.get(index));
                imageData.getHierarchy().addObject(object);
            }
            entry.saveImageData(imageData);
        }

        try (var reloaded = entry.readImageData()) {
            assertExpectedGeometry(expected, reloaded.getHierarchy().getAllObjects(false));
            entry.saveImageData(reloaded);
        }
        try (var reloadedAgain = entry.readImageData()) {
            assertExpectedGeometry(expected, reloadedAgain.getHierarchy().getAllObjects(false));
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 50000, 500000, 1000000})
    void candidatesMatchNativePersistedPointsAcrossTwoSaveReloadCycles(double offset)
            throws Exception {
        var translation = AffineTransformation.translationInstance(offset, 2.5 * offset);
        Map<UUID, ROI> expectedOutlines = new LinkedHashMap<>();
        Map<UUID, String> expectedDigests = new LinkedHashMap<>();
        ProjectImageEntry<BufferedImage> entry = createEntry();
        try (var imageData = entry.readImageData()) {
            var referenceHierarchy = new PathObjectHierarchy();
            // Object digests include the parent UUID, so both hierarchies need the same root.
            referenceHierarchy
                    .getRootObject()
                    .setID(imageData.getHierarchy().getRootObject().getID());
            int index = 0;
            for (String wkt : PredictionRoiTest.geometryCases()) {
                Geometry input = translation.transform(geometry(wkt));
                UUID objectId = new UUID(0, ++index);
                ROI reference = PredictionRoiTest.referenceRoi(input);
                var candidate =
                        new CandidateGeneration.SlideCandidate(
                                objectId.toString(), input, "Leydig cell", PLANE);
                PathObject expected =
                        PathObjects.createDetectionObject(
                                reference, PathClass.fromString(candidate.pathClass()));
                expected.setID(objectId);
                PathObject actual =
                        PathObjects.createDetectionObject(
                                candidate.roiSlide(), PathClass.fromString(candidate.pathClass()));
                actual.setID(objectId);
                referenceHierarchy.addObject(expected);
                imageData.getHierarchy().addObject(actual);
                expectedOutlines.put(objectId, reference);
                expectedDigests.put(
                        objectId, ImageDataRevisionDigester.objectSemanticDigest(expected));
                assertEquals(
                        expectedDigests.get(objectId),
                        ImageDataRevisionDigester.objectSemanticDigest(actual));
            }
            entry.saveImageData(imageData);
        }

        for (int cycle = 0; cycle < 2; cycle++) {
            try (var reloaded = entry.readImageData()) {
                var objects = reloaded.getHierarchy().getDetectionObjects();
                assertEquals(expectedOutlines.size(), objects.size());
                for (PathObject object : objects) {
                    ROI reference = expectedOutlines.get(object.getID());
                    assertNotNull(reference);
                    assertEquals(reference.getClass(), object.getROI().getClass());
                    assertEquals(reference.getAllPoints(), object.getROI().getAllPoints());
                    assertTrue(
                            PredictionRoiTest.nativeGeometry(reference)
                                    .equalsExact(
                                            PredictionRoiTest.nativeGeometry(object.getROI())));
                    assertEquals(
                            expectedDigests.get(object.getID()),
                            ImageDataRevisionDigester.objectSemanticDigest(object));
                }
                if (cycle == 0) {
                    entry.saveImageData(reloaded);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 1000000})
    void preparedClippingMatchesRawPredicatesAndPreservesExactCoveredOrdering(double offset)
            throws Exception {
        var translation = AffineTransformation.translationInstance(offset, 2.5 * offset);
        List<String> targets =
                List.of(
                        "POLYGON ((0 0, 20 0, 20 6, 6 6, 6 20, 0 20, 0 0))",
                        "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0), (8 8, 8 12, 12 12, 12 8, 8 8))",
                        "MULTIPOLYGON (((0 0, 6 0, 6 20, 0 20, 0 0)), ((14 0, 20 0, 20 20, 14 20, 14 0)))");
        List<String> predictions =
                List.of(
                        "POLYGON ((5 1, 1 1, 1 5, 5 5, 5 1))",
                        "POLYGON ((0 0, 5 0, 5 5, 0 5, 0 0))",
                        "POLYGON ((4 4, 16 4, 16 16, 4 16, 4 4))",
                        "POLYGON ((20 0, 22 0, 22 2, 20 2, 20 0))",
                        "POLYGON ((30 30, 32 30, 32 32, 30 32, 30 30))",
                        "POLYGON ((9 9, 11 9, 11 11, 9 11, 9 9))",
                        "MULTIPOLYGON (((1 1, 5 1, 5 5, 1 5, 1 1)), ((15 1, 19 1, 19 5, 15 5, 15 1)))");
        for (String targetText : targets) {
            Geometry target = translation.transform(geometry(targetText));
            Geometry targetBefore = target.copy();
            var prepared = PreparedGeometryFactory.prepare(target);
            for (String predictionText : predictions) {
                Geometry prediction = translation.transform(geometry(predictionText));
                Geometry before = prediction.copy();
                boolean covered = target.covers(prediction);
                assertEquals(covered, prepared.covers(prediction));
                assertEquals(
                        target.covers(prediction.getInteriorPoint()),
                        prepared.covers(prediction.getInteriorPoint()));
                Geometry expected =
                        covered
                                ? prediction.copy()
                                : GeometryTools.ensurePolygonal(
                                        OverlayNGRobust.overlay(
                                                prediction, target, OverlayNG.INTERSECTION));
                var actual = SourceInputGeometry.clipToTarget(prediction, prepared);
                assertEquals(expected.isEmpty(), actual.isEmpty());
                if (actual.isPresent()) {
                    assertTrue(expected.equalsExact(actual.orElseThrow()));
                    assertNotSame(prediction, actual.orElseThrow());
                }
                assertTrue(before.equalsExact(prediction));
                assertTrue(targetBefore.equalsExact(target));
            }
        }
    }

    private ProjectImageEntry<BufferedImage> createEntry() throws Exception {
        Path projectDirectory = Files.createDirectory(tempDirectory.resolve("project"));
        Path source = tempDirectory.resolve("source.png");
        assertTrue(
                ImageIO.write(
                        new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB),
                        "PNG",
                        source.toFile()));
        var project = Projects.createProject(projectDirectory.toFile(), BufferedImage.class);
        try (var server = ImageServers.buildServer(source.toUri())) {
            assertNotNull(server.getBuilder());
            ProjectImageEntry<BufferedImage> entry = project.addImage(server.getBuilder());
            assertNotNull(entry);
            return entry;
        }
    }

    private static void assertExpectedGeometry(
            List<Geometry> expected, Iterable<PathObject> objects) {
        Map<UUID, Geometry> observed = new LinkedHashMap<>();
        for (PathObject object : objects) {
            observed.put(object.getID(), PredictionRoiTest.nativeGeometry(object.getROI()));
        }
        for (int index = 0; index < expected.size(); index++) {
            assertTrue(expected.get(index).equalsExact(observed.get(OBJECT_IDS.get(index))));
        }
    }

    private static Geometry geometry(String wkt) throws Exception {
        return new WKTReader().read(wkt);
    }
}

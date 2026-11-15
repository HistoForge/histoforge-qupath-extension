package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.ext.histoforge.pipeline.ImageActionPlanner;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.PolygonOutlineFixture;
import qupath.lib.geom.Point2;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/** Public project persistence, not a geometry approximation, defines saved Metrics inputs. */
class MetricSavedGeometryParityTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();
    private static final UUID BOUNDARY_ID = UUID.fromString(MetricTestData.ROI_A);
    private static final UUID PARENT_ID = MetricTestData.objectId(1);
    private static final UUID CHILD_ID = MetricTestData.objectId(2);
    private static final String HOLES =
            "POLYGON ((2.12 2,2.12 20,20 20,20 2,2.12 2)," + "(8 8,12 8,12 12,8 12,8 8))";
    private static final String MULTIPART =
            "MULTIPOLYGON (((2 2,2 20,20 20,20 2,2 2))," + "((25 25,25 28,28 28,28 25,25 25)))";

    // This is a diagnostic script probe, run directly without MetricsEngine result validation.
    // The exact public objects seen by a script include more than the canonical XY digest.
    private static final String SCRIPT =
            """
            /* @histoforge-metrics
            [{"name":"count","label":"Count","type":"scalar","decimals":0,"aggregation":"sum"}]
            */
            def bits = { value -> Double.doubleToRawLongBits(value as double) }
            def describe = { object ->
                def outline = object.ROI
                def geometry = outline.geometry
                [id: object.ID.toString(), kind: object.class.name,
                 pathClass: object.pathClass?.toString(), parent: object.parent?.ID?.toString(),
                 roiClass: outline.class.name, plane: [outline.c, outline.z, outline.t],
                 area: bits(outline.getArea()), length: bits(outline.getLength()),
                 centroid: [bits(outline.getCentroidX()), bits(outline.getCentroidY())],
                 bounds: [bits(outline.getBoundsX()), bits(outline.getBoundsY()),
                          bits(outline.getBoundsWidth()), bits(outline.getBoundsHeight())],
                 points: outline.allPoints.collect { [bits(it.x), bits(it.y)] },
                 geometryType: geometry.geometryType, srid: geometry.SRID,
                 metadata: geometry.userData, precision: geometry.precisionModel.toString(),
                 rings: (0..<geometry.numGeometries).collectMany { index ->
                     def polygon = geometry.getGeometryN(index)
                     ([polygon.exteriorRing] + (0..<polygon.numInteriorRing).collect {
                         polygon.getInteriorRingN(it)
                     }).collect { ring ->
                         def sequence = ring.coordinateSequence
                         [type: sequence.class.name, dimension: sequence.dimension,
                          measures: sequence.measures, srid: ring.SRID, metadata: ring.userData,
                          values: (0..<sequence.size()).collect { point ->
                              (0..<sequence.dimension).collect {
                                  bits(sequence.getOrdinate(point, it))
                              }
                          }]
                     }
                 }]
            }
            return [target: describe(roi), objects: children.collect { describe(it) }]
            """;

    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "polygon",
                "dense-polygon",
                "rectangle",
                "ellipse",
                "persisted-holes",
                "persisted-multipart",
                "legacy-area",
                "legacy-awt",
                "off-grid-geometry",
                "double-geometry",
                "geometry-metadata",
                "geometry-xyz",
                "legacy-mutated-cache"
            })
    void comparesLiveAndIndependentRoiCopiesWithPublicProjectReload(String shape) throws Exception {
        ROI outline = outline(shape);
        ProjectImageEntry<BufferedImage> entry = createEntry();
        try (var live = entry.readImageData()) {
            populate(live.getHierarchy(), outline, outline);
            boolean admitted =
                    Set.of(
                                    "polygon",
                                    "dense-polygon",
                                    "rectangle",
                                    "ellipse",
                                    "persisted-holes",
                                    "persisted-multipart")
                            .contains(shape);
            assertEquals(
                    admitted, MetricSnapshotBuilder.canCaptureWithoutReload(live.getHierarchy()));
            entry.saveImageData(live);
            try (var saved = entry.readImageData()) {
                var roiCopies = new PathObjectHierarchy();
                populate(roiCopies, persistedCopy(outline), persistedCopy(outline));
                MetricScriptRunner scripts = new MetricScriptRunner();
                for (var selection : selections()) {
                    var current = prepare(live.getHierarchy(), selection);
                    var reloaded = prepare(saved.getHierarchy(), selection);
                    var copied = prepare(roiCopies, selection);
                    if (shape.equals("dense-polygon")
                            && !(selection instanceof ActionSelectionCodec.SlideRegion slide
                                    && slide.region() instanceof ActionSelectionCodec.FullSlide)) {
                        var expected = PolygonOutlineFixture.rawPolygon(outline);
                        var target = current.snapshot().metricInput().regions().getFirst().target();
                        assertTrue(expected.equalsTopo(target.geometrySlide()));
                        assertEquals(expected.getArea(), target.geometrySlide().getArea(), 1e-9);
                    }
                    assertExactInputsAndScripts(reloaded, copied, scripts);
                    assertEquals(expectedParents(selection), reloaded.parentNormalization());
                    assertEquals(reloaded.parentNormalization(), current.parentNormalization());

                    switch (shape) {
                        case "off-grid-geometry", "double-geometry", "legacy-mutated-cache" -> {
                            assertNotEquals(digest(reloaded), digest(current), shape);
                            assertNotEquals(
                                    scriptView(reloaded, scripts), scriptView(current, scripts));
                        }
                        case "geometry-metadata", "geometry-xyz" -> {
                            assertEquals(digest(reloaded), digest(current), shape);
                            assertNotEquals(
                                    scriptView(reloaded, scripts), scriptView(current, scripts));
                        }
                        default -> assertExactInputsAndScripts(reloaded, current, scripts);
                    }
                }
            }
        }
    }

    private static List<ActionSelectionCodec.RegionSelection> selections() {
        return List.of(
                new ActionSelectionCodec.SlideRegion(new ActionSelectionCodec.FullSlide()),
                new ActionSelectionCodec.RoiRegions(Set.of(BOUNDARY_ID.toString())),
                new ActionSelectionCodec.SlideRegion(
                        new ActionSelectionCodec.AnnotationUnion(Set.of(BOUNDARY_ID.toString()))));
    }

    private static List<HierarchyDelta.ParentAssignment> expectedParents(
            ActionSelectionCodec.RegionSelection selection) {
        var child = new HierarchyDelta.ParentAssignment(CHILD_ID, Optional.of(PARENT_ID));
        return selection instanceof ActionSelectionCodec.RoiRegions
                ? List.of(
                        new HierarchyDelta.ParentAssignment(PARENT_ID, Optional.of(BOUNDARY_ID)),
                        child)
                : List.of();
    }

    private static void populate(PathObjectHierarchy hierarchy, ROI boundary, ROI parent) {
        var selected = PathObjects.createAnnotationObject(boundary);
        selected.setID(BOUNDARY_ID);
        var biological = PathObjects.createDetectionObject(parent, PathClass.fromString("Tubule"));
        biological.setID(PARENT_ID);
        var child =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(5, 5, 1, 1, PLANE), PathClass.fromString("Cell"));
        child.setID(CHILD_ID);
        hierarchy.getRootObject().addChildObjects(List.of(selected, biological, child));
    }

    private ProjectImageEntry<BufferedImage> createEntry() throws Exception {
        Path source = temporaryDirectory.resolve("source.png");
        assertTrue(
                ImageIO.write(
                        new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB),
                        "PNG",
                        source.toFile()));
        var project =
                Projects.createProject(
                        Files.createDirectory(temporaryDirectory.resolve("project")).toFile(),
                        BufferedImage.class);
        try (var server = ImageServers.buildServer(source.toUri())) {
            return project.addImage(server.getBuilder());
        }
    }

    private static MetricPreparation prepare(
            PathObjectHierarchy hierarchy, ActionSelectionCodec.RegionSelection selection) {
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "metric-parity",
                        "7".repeat(64),
                        Optional.empty(),
                        32,
                        32,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                MetricTestData.IMAGE_ID,
                                SourceIdentityCodec.imageSourceIdentityDigest(source),
                                "8".repeat(64)),
                        source);
        var module =
                new ModuleSnapshot(
                        "parity-test",
                        MetricTestData.MODULE,
                        new JsonObject(),
                        "1".repeat(64),
                        Map.of(),
                        Map.of(),
                        Optional.of(
                                new ModuleSnapshot.MetricScriptSnapshot(
                                        new ModuleSnapshot.ScriptSnapshot(
                                                "metrics.groovy",
                                                SCRIPT,
                                                CanonicalCodec.sha256(
                                                        CanonicalCodec.utf8Bytes(SCRIPT))),
                                        ScriptHeaderParser.parseMetrics(SCRIPT))));
        return MetricSnapshotBuilder.prepare(
                MetricTestData.clientBuild(10, 20),
                module,
                ImageActionPlanner.captureRegions(selection, hierarchy, 32, 32),
                revision,
                hierarchy);
    }

    private static void assertExactInputsAndScripts(
            MetricPreparation expected, MetricPreparation actual, MetricScriptRunner scripts) {
        assertEquals(digest(expected), digest(actual));
        assertArrayEquals(
                MetricInputEncodingTest.encoded(expected.snapshot().metricInput()),
                MetricInputEncodingTest.encoded(actual.snapshot().metricInput()));
        assertEquals(expected.parentNormalization(), actual.parentNormalization());
        assertEquals(scriptView(expected, scripts), scriptView(actual, scripts));
    }

    private static String digest(MetricPreparation preparation) {
        return preparation.snapshot().metricInput().metricInputDigest();
    }

    private static Object scriptView(MetricPreparation preparation, MetricScriptRunner scripts) {
        var input = preparation.snapshot().metricInput();
        scripts.prepare(input.script());
        return scripts.run(
                input.script(), input.regions().getFirst(), MetricTestData.IMAGE_ID, 0.25, 0.25);
    }

    /** Calls the same QuPath serialization proxies used by public project persistence. */
    private static ROI persistedCopy(ROI roi) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(roi);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (ROI) input.readObject();
        }
    }

    private static ROI outline(String shape) throws Exception {
        if (shape.equals("dense-polygon")) {
            return PolygonOutlineFixture.dense(16, 16, 14, 14);
        }
        if (shape.equals("rectangle")) {
            return ROIs.createRectangleROI(2.125, 2, 18, 18, PLANE);
        }
        if (shape.equals("ellipse")) {
            return ROIs.createEllipseROI(2.125, 2, 18, 18, PLANE);
        }
        if (shape.equals("polygon")) {
            return ROIs.createPolygonROI(
                    List.of(new Point2(2.125, 2), new Point2(2.125, 20), new Point2(20, 2)), PLANE);
        }
        if (shape.startsWith("legacy-")) {
            var path = new Path2D.Float();
            path.moveTo(2.125, 2);
            path.lineTo(2.125, 20);
            path.lineTo(20, 20);
            path.lineTo(20, 2);
            path.closePath();
            var constructor =
                    Class.forName("qupath.lib.roi.AWTAreaROI")
                            .getDeclaredConstructor(Shape.class, ImagePlane.class);
            constructor.setAccessible(true);
            ROI awt = (ROI) constructor.newInstance(path, PLANE);
            ROI roi = shape.equals("legacy-awt") ? awt : persistedCopy(awt);
            if (shape.equals("legacy-mutated-cache")) {
                shiftLeftEdge(roi.getGeometry());
            }
            return roi;
        }
        Geometry geometry =
                new WKTReader(
                                shape.equals("double-geometry")
                                        ? new GeometryFactory()
                                        : GeometryTools.getDefaultFactory())
                        .read(shape.equals("persisted-multipart") ? MULTIPART : HOLES);
        if (!shape.equals("double-geometry") && !shape.equals("geometry-xyz")) {
            geometry =
                    new WKBReader(GeometryTools.getDefaultFactory())
                            .read(new WKBWriter(2).write(geometry));
        }
        if (shape.equals("off-grid-geometry")) {
            shiftLeftEdge(geometry);
        } else if (shape.equals("geometry-metadata")) {
            geometry.setSRID(4326);
            geometry.setUserData("script-visible metadata");
        }
        return GeometryTools.geometryToROI(geometry, PLANE);
    }

    private static void shiftLeftEdge(Geometry geometry) {
        geometry.apply(
                new CoordinateSequenceFilter() {
                    public void filter(CoordinateSequence sequence, int index) {
                        if (sequence.getX(index) < 3) {
                            sequence.setOrdinate(index, 0, 2.125);
                        }
                    }

                    public boolean isDone() {
                        return false;
                    }

                    public boolean isGeometryChanged() {
                        return true;
                    }
                });
    }
}

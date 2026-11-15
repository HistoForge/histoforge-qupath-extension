package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.spatial.PolygonOutlineFixture;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

class MetricUtilsTest {

    @Test
    void shapeMeasurementsUseTheCompleteNativePolygonWithoutHostReconstruction() {
        var outline = spy(PolygonOutlineFixture.dense(50000, 100000, 40000, 80000));
        var expectedOutline = mock(ROI.class);
        var expectedGeometry = PolygonOutlineFixture.rawPolygon(outline);
        when(expectedOutline.getGeometry()).thenReturn(expectedGeometry);
        var expected = PathObjects.createDetectionObject(expectedOutline);
        var actual = PathObjects.createDetectionObject(outline);
        doThrow(new AssertionError("Dense polygon must not traverse host reconstruction"))
                .when(outline)
                .getGeometry();
        var metrics = MetricUtils.INSTANCE;
        assertEquals(metrics.eccentricity(expected), metrics.eccentricity(actual));
        assertEquals(metrics.minorAxis(expected), metrics.minorAxis(actual));
        assertEquals(metrics.majorAxis(expected), metrics.majorAxis(actual));
        assertEquals(metrics.solidity(expected), metrics.solidity(actual));
        assertTrue(metrics.majorAxis(actual) > metrics.minorAxis(actual));
        verify(outline, never()).getGeometry();
    }

    @Test
    void indexedCentroidMatchingPreservesFirstParentLargestChildAndResultOrder() {
        PathObject firstParent = rectangle(0, 0, 10, 10);
        PathObject overlappingLaterParent = rectangle(-1, -1, 12, 12);
        PathObject separateParent = rectangle(20, 0, 10, 10);

        PathObject firstSmallChild = rectangle(4, 4, 1, 1);
        PathObject separateChild = rectangle(24, 4, 1, 1);
        PathObject firstLargeChild = rectangle(3, 3, 3, 3);
        PathObject boundaryChild = rectangle(-0.5, 4, 1, 1);
        PathObject unmatchedChild = rectangle(40, 40, 1, 1);

        List<PathObject[]> matches =
                MetricUtils.INSTANCE.matchLargestByCentroid(
                        List.of(
                                firstSmallChild,
                                separateChild,
                                firstLargeChild,
                                boundaryChild,
                                unmatchedChild),
                        List.of(firstParent, overlappingLaterParent, separateParent));

        assertSame(firstLargeChild, matches.get(0)[0]);
        assertSame(firstParent, matches.get(0)[1]);
        assertSame(separateChild, matches.get(1)[0]);
        assertSame(separateParent, matches.get(1)[1]);
        assertEquals(2, matches.size()); // Native rectangle includes its left boundary.
    }

    @Test
    void overlappingParentsStillUseCallerOrderRatherThanIndexTraversalOrder() {
        PathObject smallFirst = rectangle(2, 2, 4, 4);
        PathObject largeSecond = rectangle(0, 0, 10, 10);
        PathObject child = rectangle(3, 3, 1, 1);

        List<PathObject[]> matches =
                MetricUtils.INSTANCE.matchLargestByCentroid(
                        List.of(child), List.of(smallFirst, largeSecond));

        assertSame(child, matches.get(0)[0]);
        assertSame(smallFirst, matches.get(0)[1]);
    }

    @Test
    void polygonMatchingPreservesHolesPartsConcavityBoundariesAndAreaTies() throws Exception {
        PathObject holed =
                polygon("POLYGON ((0 0,12 0,12 12,0 12,0 0)," + "(4 4,4 8,8 8,8 4,4 4))");
        PathObject multipart =
                polygon(
                        "MULTIPOLYGON (((20 0,24 0,24 4,20 4,20 0)),"
                                + "((30 0,34 0,34 4,30 4,30 0)))");
        PathObject concave = polygon("POLYGON ((40 0,46 0,46 2,42 2,42 6,40 6,40 0))");
        PathObject fractional = polygon("POLYGON ((50.25 0.5,55.75 0.5,53.125 8.25,50.25 0.5))");
        PathObject overlapping = rectangle(-1, -1, 15, 15);
        PathObject first = rectangle(1, 1, 1, 1);
        PathObject equalArea = rectangle(2, 1, 1, 1);
        PathObject hole = rectangle(5, 5, 1, 1);
        PathObject holeBoundary = rectangle(3.5, 5, 1, 1);
        PathObject outerBoundary = rectangle(-0.5, 1, 1, 1);
        PathObject secondPart = rectangle(31, 1, 1, 1);
        PathObject betweenParts = rectangle(26, 1, 1, 1);
        PathObject concaveInterior = rectangle(40.5, 3, 1, 1);
        PathObject concaveGap = rectangle(44, 4, 1, 1);
        PathObject fractionalInterior = rectangle(52.5, 2, 0.25, 0.25);
        PathObject fractionalBoundary = rectangle(50.125, 0.375, 0.25, 0.25);
        // Check each native containment rule independently; an earlier equal-area child must not
        // hide a wrong boundary match.
        for (PathObject child : List.of(hole, holeBoundary, outerBoundary)) {
            assertEquals(
                    holed.getROI()
                                    .contains(
                                            child.getROI().getCentroidX(),
                                            child.getROI().getCentroidY())
                            ? 1
                            : 0,
                    MetricUtils.INSTANCE
                            .matchLargestByCentroid(List.of(child), List.of(holed))
                            .size());
        }
        assertEquals(
                0,
                MetricUtils.INSTANCE
                        .matchLargestByCentroid(List.of(betweenParts), List.of(multipart))
                        .size());
        assertEquals(
                0,
                MetricUtils.INSTANCE
                        .matchLargestByCentroid(List.of(concaveGap), List.of(concave))
                        .size());
        assertEquals(
                fractional
                                .getROI()
                                .contains(
                                        fractionalBoundary.getROI().getCentroidX(),
                                        fractionalBoundary.getROI().getCentroidY())
                        ? 1
                        : 0,
                MetricUtils.INSTANCE
                        .matchLargestByCentroid(List.of(fractionalBoundary), List.of(fractional))
                        .size());
        List<PathObject> children =
                List.of(
                        first,
                        equalArea,
                        hole,
                        holeBoundary,
                        outerBoundary,
                        secondPart,
                        betweenParts,
                        concaveInterior,
                        concaveGap,
                        fractionalInterior);
        List<PathObject> parents = List.of(holed, multipart, concave, fractional, overlapping);

        List<PathObject[]> actual = MetricUtils.INSTANCE.matchLargestByCentroid(children, parents);
        assertPairsEqual(orderedScan(children, parents), actual);
        assertEquals(5, actual.size());
        assertSame(first, actual.get(0)[0]);
        assertSame(holed, actual.get(0)[1]);
        assertSame(hole, actual.get(1)[0]);
        assertSame(overlapping, actual.get(1)[1]);
        assertSame(secondPart, actual.get(2)[0]);
        assertSame(multipart, actual.get(2)[1]);
        assertSame(concaveInterior, actual.get(3)[0]);
        assertSame(concave, actual.get(3)[1]);
        assertSame(fractionalInterior, actual.get(4)[0]);
        assertSame(fractional, actual.get(4)[1]);
    }

    @Test
    void matchingUsesTheParentOutlineCapturedBeforeChildrenAreVisited() {
        PathROIObject parent = (PathROIObject) rectangle(0, 0, 10, 10);
        PathObject child = rectangle(1, 1, 1, 1);
        List<PathObject> children =
                new AbstractList<>() {
                    @Override
                    public PathObject get(int index) {
                        // Change the object after indexing; the captured outline must still match.
                        parent.setROI(ROIs.createRectangleROI(100, 100, 10, 10, null));
                        return child;
                    }

                    @Override
                    public int size() {
                        return 1;
                    }
                };

        List<PathObject[]> actual =
                MetricUtils.INSTANCE.matchLargestByCentroid(children, List.of(parent));

        assertEquals(1, actual.size());
        assertSame(child, actual.getFirst()[0]);
        assertSame(parent, actual.getFirst()[1]);
        assertEquals(100, parent.getROI().getBoundsX());
    }

    @Test
    void spatialIndexIsExactlyEquivalentToTheNativeOrderedScan() {
        Random random = new Random(0x4c4559444947L);
        for (int round = 0; round < 50; round++) {
            List<PathObject> parents = new ArrayList<>();
            List<PathObject> children = new ArrayList<>();
            for (int index = 0; index < 75; index++) {
                parents.add(
                        rectangle(
                                random.nextDouble(100),
                                random.nextDouble(100),
                                2 + random.nextDouble(25),
                                2 + random.nextDouble(25)));
            }
            for (int index = 0; index < 150; index++) {
                children.add(
                        rectangle(
                                random.nextDouble(125),
                                random.nextDouble(125),
                                0.25 + random.nextDouble(2),
                                0.25 + random.nextDouble(2)));
            }

            List<PathObject[]> expected = orderedScan(children, parents);
            List<PathObject[]> actual =
                    MetricUtils.INSTANCE.matchLargestByCentroid(children, parents);

            assertPairsEqual(expected, actual);
        }
    }

    private static void assertPairsEqual(List<PathObject[]> expected, List<PathObject[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index)[0], actual.get(index)[0]);
            assertSame(expected.get(index)[1], actual.get(index)[1]);
        }
    }

    private static List<PathObject[]> orderedScan(
            List<PathObject> children, List<PathObject> parents) {
        Map<PathObject, PathObject> best = new LinkedHashMap<>();
        for (PathObject child : children) {
            double x = child.getROI().getCentroidX();
            double y = child.getROI().getCentroidY();
            for (PathObject parent : parents) {
                if (parent.getROI().contains(x, y)) {
                    PathObject current = best.get(parent);
                    if (current == null || child.getROI().getArea() > current.getROI().getArea()) {
                        best.put(parent, child);
                    }
                    break;
                }
            }
        }
        List<PathObject[]> result = new ArrayList<>();
        best.forEach((parent, child) -> result.add(new PathObject[] {child, parent}));
        return result;
    }

    private static PathObject rectangle(double x, double y, double width, double height) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, y, width, height, null));
    }

    @Test
    void matchingDoesNotConvertNativeOutlinesToJts() {
        var outline =
                spy(ROIs.createEllipseROI(240000, 120000, 18, 18, ImagePlane.getDefaultPlane()));
        doThrow(new AssertionError("unnecessary JTS conversion")).when(outline).getGeometry();
        var parent = PathObjects.createDetectionObject(outline);
        var child = rectangle(240008, 120008, 1, 1);
        var actual = MetricUtils.INSTANCE.matchLargestByCentroid(List.of(child), List.of(parent));
        assertEquals(1, actual.size());
        assertSame(parent, actual.getFirst()[1]);
        verify(outline, never()).getGeometry();
    }

    private static PathObject polygon(String wkt) throws Exception {
        return PathObjects.createDetectionObject(
                GeometryTools.geometryToROI(
                        new WKTReader().read(wkt), ImagePlane.getDefaultPlane()));
    }
}

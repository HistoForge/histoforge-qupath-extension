package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.spatial.HierarchyDelta;

class MetricParentNormalizerTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final UUID ANCHOR = id(900);
    private static final UUID LARGE = id(100);
    private static final UUID SMALL = id(200);
    private static final UUID CHILD = id(300);

    @Test
    void nodeOwnsItsGeometryAndDoesNotExposeMutableState() {
        Geometry geometry = rectangle(20, 20, 21, 21);
        Geometry expected = geometry.copy();
        var captured = node(CHILD, geometry, ANCHOR);

        geometry.apply((CoordinateFilter) coordinate -> coordinate.x += 100);
        geometry.geometryChanged();
        Geometry exposed = captured.geometry().copy();
        exposed.apply((CoordinateFilter) coordinate -> coordinate.y += 100);
        exposed.geometryChanged();

        assertEquals(expected, captured.geometry().copy());
        assertTrue(
                MetricParentNormalizer.normalize(
                                List.of(captured), Optional.of(ANCHOR), Set.of(ANCHOR))
                        .isEmpty());
    }

    @Test
    void preservesValidExistingParentEvenWhenASmallerContainerExists() {
        var result =
                MetricParentNormalizer.normalize(
                        List.of(
                                node(LARGE, rectangle(0, 0, 100, 100), ANCHOR),
                                node(SMALL, rectangle(10, 10, 40, 40), LARGE),
                                node(CHILD, rectangle(20, 20, 21, 21), LARGE)),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));

        assertTrue(result.isEmpty());
    }

    @Test
    void repairsInvalidParentToSmallestContainerWithUuidTieBreak() {
        UUID sameAreaLargerUuid = id(250);
        UUID missing = id(999);
        var result =
                MetricParentNormalizer.normalize(
                        List.of(
                                node(SMALL, rectangle(10, 10, 40, 40), ANCHOR),
                                node(sameAreaLargerUuid, rectangle(10, 10, 40, 40), ANCHOR),
                                node(CHILD, rectangle(20, 20, 21, 21), missing)),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));

        assertEquals(
                List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.of(SMALL))), result);
    }

    @Test
    void fallsBackToRegionAnchorAndSecondRunIsIdempotent() {
        var first =
                MetricParentNormalizer.normalize(
                        List.of(node(CHILD, rectangle(20, 20, 21, 21), id(999))),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));
        assertEquals(
                List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.of(ANCHOR))), first);

        var second =
                MetricParentNormalizer.normalize(
                        List.of(node(CHILD, rectangle(20, 20, 21, 21), ANCHOR)),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));
        assertTrue(second.isEmpty());
    }

    @Test
    void slideRegionUsesHierarchyRootAsItsDefaultAnchor() {
        var result =
                MetricParentNormalizer.normalize(
                        List.of(node(CHILD, rectangle(1, 1, 2, 2), id(999))),
                        Optional.empty(),
                        Set.of());

        assertEquals(List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.empty())), result);
    }

    @Test
    void slideRegionPreservesAnyCapturedAdministrativeRoiParent() {
        var result =
                MetricParentNormalizer.normalize(
                        List.of(node(CHILD, rectangle(1, 1, 2, 2), ANCHOR)),
                        Optional.empty(),
                        Set.of(ANCHOR));

        assertTrue(result.isEmpty());
    }

    @Test
    void validRootAndAdministrativeParentsDoNotReconstructOutlines() {
        var geometry = spy(MetricObjectGeometry.copyOf(rectangle(1, 1, 2, 2)));
        var rooted = fields(CHILD, geometry, Optional.empty());
        var result = MetricParentNormalizer.normalize(List.of(rooted), Optional.empty(), Set.of());
        assertTrue(result.isEmpty());
        verify(geometry, never()).copy();

        var anchored = fields(CHILD, geometry, Optional.of(ANCHOR));
        result =
                MetricParentNormalizer.normalize(
                        List.of(anchored), Optional.of(ANCHOR), Set.of(ANCHOR));
        assertTrue(result.isEmpty());
        verify(geometry, never()).copy();
    }

    @Test
    void rootParentInRoiMetricsMustBeRepaired() {
        var child =
                fields(
                        CHILD,
                        MetricObjectGeometry.copyOf(rectangle(20, 20, 21, 21)),
                        Optional.empty());
        var result =
                MetricParentNormalizer.normalize(
                        List.of(node(LARGE, rectangle(0, 0, 100, 100), ANCHOR), child),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));
        assertEquals(
                List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.of(LARGE))), result);
    }

    @Test
    void identityErrorsAreRejectedEvenWhenAllParentsAreAlreadyValid() {
        var geometry = spy(MetricObjectGeometry.copyOf(rectangle(1, 1, 2, 2)));
        var child = fields(CHILD, geometry, Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MetricParentNormalizer.normalize(
                                List.of(child, child), Optional.empty(), Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MetricParentNormalizer.normalize(
                                List.of(child), Optional.empty(), Set.of(CHILD)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MetricParentNormalizer.normalize(
                                List.of(child), Optional.of(ANCHOR), Set.of()));
        assertThrows(
                IllegalArgumentException.class, () -> fields(CHILD, geometry, Optional.of(CHILD)));
        verify(geometry, never()).copy();
    }

    @Test
    void allShapesMustBeValidatedBeforeParentShortcuts() throws Exception {
        Geometry selfCrossing = new WKTReader().read("POLYGON ((0 0,8 8,8 0,0 8,0 0))");
        assertThrows(IllegalArgumentException.class, () -> node(CHILD, selfCrossing, ANCHOR));
        assertThrows(
                IllegalArgumentException.class,
                () -> node(CHILD, GEOMETRY_FACTORY.createPolygon(), ANCHOR));
    }

    @Test
    void binarySearchUsesSignedUuidOrderAndRejectsNonadjacentDuplicates() {
        UUID negativeId = new UUID(Long.MIN_VALUE, 1L);
        var parent = node(negativeId, rectangle(0, 0, 100, 100), ANCHOR);
        var child = node(CHILD, rectangle(1, 1, 2, 2), negativeId);
        var unrelated = node(LARGE, rectangle(200, 200, 201, 201), ANCHOR);
        assertTrue(
                MetricParentNormalizer.normalize(
                                List.of(child, unrelated, parent),
                                Optional.of(ANCHOR),
                                Set.of(ANCHOR))
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MetricParentNormalizer.normalize(
                                List.of(child, parent, child),
                                Optional.of(ANCHOR),
                                Set.of(ANCHOR)));
    }

    @Test
    void sortedSearchSkipsHolesAndEnvelopeOnlyCandidates() throws Exception {
        Geometry hole =
                new WKTReader(GEOMETRY_FACTORY)
                        .read("POLYGON ((0 0,0 10,10 10,10 0,0 0),(3 3,7 3,7 7,3 7,3 3))");
        var result =
                MetricParentNormalizer.normalize(
                        List.of(
                                node(SMALL, hole, ANCHOR),
                                node(LARGE, rectangle(0, 0, 20, 20), ANCHOR),
                                node(CHILD, rectangle(4, 4, 6, 6), id(999))),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));
        assertEquals(
                List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.of(LARGE))), result);
    }

    @Test
    void repairedParentsUseInteriorPointCoverageNotCentroidOrCompleteContainment()
            throws Exception {
        Geometry concave =
                new WKTReader(GEOMETRY_FACTORY)
                        .read("POLYGON ((0 0,0 6,6 6,6 4,2 4,2 2,6 2,6 0,0 0))");
        assertFalse(concave.covers(concave.getCentroid()));
        Geometry parent = rectangle(0, -10, 2, 10);
        assertTrue(parent.getArea() > concave.getArea());
        assertTrue(parent.covers(concave.getInteriorPoint()));
        assertFalse(parent.covers(concave.getCentroid()));
        var result =
                MetricParentNormalizer.normalize(
                        List.of(node(LARGE, parent, ANCHOR), node(CHILD, concave, id(999))),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));
        assertEquals(
                List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.of(LARGE))), result);

        Geometry partiallyCovered = rectangle(6, 2, 8, 4);
        var boundaryResult =
                MetricParentNormalizer.normalize(
                        List.of(
                                node(LARGE, rectangle(-1, -1, 7, 7), ANCHOR),
                                node(CHILD, partiallyCovered, id(999))),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));
        assertEquals(
                List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.of(LARGE))),
                boundaryResult);
    }

    @Test
    void smallestMultipartContainerCanOwnAChild() throws Exception {
        Geometry multipart =
                new WKTReader(GEOMETRY_FACTORY)
                        .read("MULTIPOLYGON (((0 0,0 4,4 4,4 0,0 0)),((8 0,8 4,12 4,12 0,8 0)))");
        var result =
                MetricParentNormalizer.normalize(
                        List.of(
                                node(LARGE, rectangle(-1, -1, 13, 5), ANCHOR),
                                node(SMALL, multipart, ANCHOR),
                                node(CHILD, rectangle(9, 1, 10, 2), id(999))),
                        Optional.of(ANCHOR),
                        Set.of(ANCHOR));
        assertEquals(
                List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.of(SMALL))), result);
    }

    @Test
    void sameAreaParentIsInvalidAndInputOrderDoesNotChangeTheRepairs() {
        var nodes =
                List.of(
                        node(LARGE, rectangle(0, 0, 5, 5), ANCHOR),
                        node(CHILD, rectangle(0, 0, 5, 5), LARGE));
        var result = MetricParentNormalizer.normalize(nodes, Optional.of(ANCHOR), Set.of(ANCHOR));
        assertEquals(
                List.of(new HierarchyDelta.ParentAssignment(CHILD, Optional.of(ANCHOR))), result);
        assertEquals(
                result,
                MetricParentNormalizer.normalize(
                        nodes.reversed(), Optional.of(ANCHOR), Set.of(ANCHOR)));
    }

    @Test
    void returnsOnlyOrderedImmutableChangesIncludingAMoveToRoot() {
        UUID toRoot = id(400);
        UUID unchangedRoot = id(500);
        var nodes =
                List.of(
                        node(LARGE, rectangle(0, 0, 100, 100), ANCHOR),
                        node(SMALL, rectangle(10, 10, 40, 40), id(999)),
                        node(CHILD, rectangle(20, 20, 21, 21), LARGE),
                        node(toRoot, rectangle(120, 0, 122, 2), id(999)),
                        fields(
                                unchangedRoot,
                                MetricObjectGeometry.copyOf(rectangle(140, 0, 141, 1)),
                                Optional.empty()));

        var changes =
                MetricParentNormalizer.normalize(
                        nodes.reversed(), Optional.empty(), Set.of(ANCHOR));

        assertEquals(
                List.of(
                        new HierarchyDelta.ParentAssignment(SMALL, Optional.of(LARGE)),
                        new HierarchyDelta.ParentAssignment(toRoot, Optional.empty())),
                changes);
        assertThrows(UnsupportedOperationException.class, changes::clear);
    }

    private static MetricPopulationCapture.ObjectFields node(
            UUID id, Geometry geometry, UUID parentId) {
        return fields(id, MetricObjectGeometry.copyOf(geometry), Optional.of(parentId));
    }

    private static MetricPopulationCapture.ObjectFields fields(
            UUID id, MetricObjectGeometry geometry, Optional<UUID> parentId) {
        return new MetricPopulationCapture.ObjectFields(
                id, MetricTypes.MetricObjectType.DETECTION, "Cell", geometry, parentId);
    }

    private static Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.toGeometry(new Envelope(minX, maxX, minY, maxY));
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}

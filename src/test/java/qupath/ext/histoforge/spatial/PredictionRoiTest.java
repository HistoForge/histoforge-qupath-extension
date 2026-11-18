package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec.ImagePlaneIndex;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

class PredictionRoiTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final ImagePlaneIndex PLANE = new ImagePlaneIndex(0, 0);

    @ParameterizedTest
    @ValueSource(doubles = {0, 50000, 500000, 1000000})
    void keepsTheFinalNativeTypeOrderedCoordinatesAndSemanticDigest(double offset)
            throws Exception {
        var translation = AffineTransformation.translationInstance(offset, 2.5 * offset);
        for (String wkt : geometryCases()) {
            Geometry input = translation.transform(new WKTReader().read(wkt));
            ROI previous = referenceRoi(input);
            var outline = SourceInputGeometry.materializePrediction(input, PLANE);
            assertTrue(!outline.precisionRepaired());
            assertEquals(previous.getClass(), outline.roi().orElseThrow().getClass());
            assertEquals(previous.getAllPoints(), outline.roi().orElseThrow().getAllPoints());
            assertTrue(
                    nativeGeometry(previous)
                            .equalsExact(nativeGeometry(outline.roi().orElseThrow())));
            var candidate = new CandidateGeneration.SlideCandidate(ID, input, "Cell", PLANE);
            assertEquals(previous.getClass(), candidate.roiSlide().getClass());
            assertEquals(previous.getAllPoints(), candidate.roiSlide().getAllPoints());
            assertTrue(nativeGeometry(previous).equalsExact(candidate.geometrySlide()));
            assertEquals(
                    CanonicalGeometryCodec.canonicalDigest(nativeGeometry(previous)),
                    CanonicalGeometryCodec.canonicalDigest(candidate.geometrySlide()));
            var expected =
                    PathObjects.createDetectionObject(previous, PathClass.fromString("Cell"));
            var actual =
                    PathObjects.createDetectionObject(
                            candidate.roiSlide(), PathClass.fromString("Cell"));
            expected.setID(UUID.fromString(ID));
            actual.setID(expected.getID());
            assertEquals(
                    ImageDataRevisionDigester.objectSemanticDigest(expected),
                    ImageDataRevisionDigester.objectSemanticDigest(actual));
        }
    }

    @Test
    void finalConversionPreservesNativeTypeChangesFromRounding() throws Exception {
        Geometry nearRectangle =
                new WKTReader()
                        .read(
                                "POLYGON ((10000 10000,10020.0001 10000,10020 10020,10000 10020,10000 10000))");
        assertNotEquals(
                GeometryTools.geometryToROI(nearRectangle, ImagePlane.getDefaultPlane()).getClass(),
                SourceInputGeometry.roiForQuPathPersistence(nearRectangle, PLANE).getClass());
    }

    @Test
    void sharesNativeOutlineButReturnsDetachedGeometry() {
        ROI roi = ROIs.createRectangleROI(1, 2, 30, 40, ImagePlane.getDefaultPlane());
        var candidate = new CandidateGeneration.SlideCandidate(ID, roi, "Cell", PLANE);
        assertSame(roi, candidate.roiSlide());
        Geometry before = roi.getGeometry().copy();
        Geometry detached = candidate.geometrySlide();
        detached.apply(AffineTransformation.translationInstance(100, 100));
        assertTrue(before.equalsExact(candidate.geometrySlide()));
    }

    @Test
    void materializedPredictionHandoffDoesNotRebuildTheValidatedGeometry() {
        ROI roi = mock(ROI.class);
        var outline = new SourceInputGeometry.PredictionOutline(Optional.of(roi), false);
        var candidate =
                CandidateGeneration.SlideCandidate.fromMaterializedPrediction(
                        ID, outline, "Cell", PLANE, java.util.OptionalDouble.empty());
        assertSame(roi, candidate.roiSlide());
        assertEquals(ID, candidate.candidateId());
        assertEquals(PLANE, candidate.plane());
        verify(roi, never()).getGeometry();
    }

    @Test
    void classAndDraftHandoffsKeepTheValidatedOutlineWithoutRebuildingIt() throws Exception {
        ROI roi = mock(ROI.class);
        when(roi.getImagePlane()).thenReturn(ImagePlane.getDefaultPlane());
        when(roi.getGeometry()).thenReturn(new WKTReader().read(geometryCases().get(1)));
        var candidate = new CandidateGeneration.SlideCandidate(ID, roi, "Cell", PLANE);
        var changed = candidate.withPathClass("Reviewed cell");
        var draft = HierarchyDelta.ObjectDraft.fromCandidate(changed, provenance());

        assertSame(candidate, candidate.withPathClass("Cell"));
        assertSame(roi, changed.roiSlide());
        assertSame(roi, draft.roiSlide());
        assertEquals(UUID.fromString(ID), draft.objectId());
        assertEquals("Reviewed cell", draft.pathClass());
        assertEquals(PLANE, draft.plane());
        assertEquals(provenance(), draft.provenance());
        assertThrows(IllegalArgumentException.class, () -> candidate.withPathClass(" "));
        verify(roi, times(1)).getGeometry();

        var direct =
                new HierarchyDelta.ObjectDraft(
                        draft.objectId(), roi, draft.pathClass(), PLANE, provenance());
        assertEquals(direct, draft);
        assertEquals(direct.hashCode(), draft.hashCode());
        assertEquals(direct.toString(), draft.toString());
        assertNotEquals(candidate, changed);
        var equalCandidate =
                new CandidateGeneration.SlideCandidate(ID, roi, changed.pathClass(), PLANE);
        assertEquals(equalCandidate, changed);
        assertEquals(equalCandidate.hashCode(), changed.hashCode());
        assertEquals(equalCandidate.toString(), changed.toString());
        verify(roi, times(3)).getGeometry();
    }

    @Test
    void hostRoundingThatCollapsesAValidPolygonRemainsAFailure() throws Exception {
        for (String wkt :
                List.of(
                        "POLYGON ((0 0,0.004 0,0.004 0.004,0 0.004,0 0))",
                        "POLYGON ((0 0,0.0040000001 0,0.004 0.004,0 0.004,0 0))",
                        "POLYGON ((1000000 1000000,1000000.01 1000000,1000000.01 1000000.01,"
                                + "1000000 1000000.01,1000000 1000000))")) {
            Geometry input = new WKTReader().read(wkt);
            CanonicalGeometryCodec.validatePolygonalGeometry(input);
            assertThrows(IllegalArgumentException.class, () -> referenceRoi(input));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CandidateGeneration.SlideCandidate(ID, input, "Cell", PLANE));
            var omitted = SourceInputGeometry.materializePrediction(input, PLANE);
            assertTrue(omitted.precisionRepaired());
            assertTrue(omitted.roi().isEmpty());
        }
        Geometry smallValid =
                new WKTReader().read("POLYGON ((0 0,0.006 0,0.006 0.006,0 0.006,0 0))");
        var candidate = new CandidateGeneration.SlideCandidate(ID, smallValid, "Cell", PLANE);
        assertTrue(referenceRoi(smallValid).getGeometry().equalsExact(candidate.geometrySlide()));
    }

    @Test
    void rejectsAPlaneOrChannelMismatch() {
        for (ImagePlane plane :
                List.of(
                        ImagePlane.getPlane(1, 0),
                        ImagePlane.getPlane(0, 1),
                        ImagePlane.getPlaneWithChannel(0, 0, 0))) {
            ROI roi = ROIs.createRectangleROI(0, 0, 10, 10, plane);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CandidateGeneration.SlideCandidate(ID, roi, "Cell", PLANE));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new HierarchyDelta.ObjectDraft(
                                    UUID.fromString(ID), roi, "Cell", PLANE, provenance()));
        }
    }

    @Test
    void preservesSmallNonRectangularNativePolygonWithoutArtificialRounding() throws Exception {
        Geometry input = new WKTReader().read("POLYGON ((0 0,0.004 0,0.003 0.004,0 0))");
        ROI nativeOutline = GeometryTools.geometryToROI(input, ImagePlane.getDefaultPlane());
        assertEquals(PolygonROI.class, nativeOutline.getClass());
        var candidate = new CandidateGeneration.SlideCandidate(ID, input, "Cell", PLANE);
        assertTrue(candidate.geometrySlide().getArea() > 0);
        assertTrue(nativeGeometry(nativeOutline).equalsExact(candidate.geometrySlide()));
        assertEquals(nativeOutline.getAllPoints(), candidate.roiSlide().getAllPoints());
    }

    @Test
    void rejectsEmptyNonAreaAndInvalidOutlines() throws Exception {
        for (String wkt :
                List.of(
                        "POLYGON EMPTY",
                        "LINESTRING (0 0,1 1)",
                        "POLYGON ((0 0,10 10,0 10,10 0,0 0))")) {
            ROI roi = mock(ROI.class);
            when(roi.getImagePlane()).thenReturn(ImagePlane.getDefaultPlane());
            when(roi.getGeometry()).thenReturn(new WKTReader().read(wkt));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CandidateGeneration.SlideCandidate(ID, roi, "Cell", PLANE));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new HierarchyDelta.ObjectDraft(
                                    UUID.fromString(ID), roi, "Cell", PLANE, provenance()));
        }
    }

    @Test
    void validationOnlyCopiesStillRejectEmptyHolesAndMalformedCoordinates() throws Exception {
        Polygon shell = (Polygon) new WKTReader().read("POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        var factory = shell.getFactory();
        Geometry emptyHole =
                factory.createPolygon(
                        shell.getExteriorRing(), new LinearRing[] {factory.createLinearRing()});
        for (Geometry malformed :
                List.of(
                        emptyHole,
                        new WKTReader().read("POLYGON ((0 0,0 0,0 0,0 0))"),
                        new WKTReader().read("POLYGON Z ((0 0 1,20 0 1,20 20 1,0 20 1,0 0 1))"),
                        new WKTReader().read("POLYGON ((0 0,NaN 0,20 20,0 20,0 0))"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CanonicalGeometryCodec.canonicalCoordinates(malformed));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SourceInputGeometry.validatedGeometryCopy(malformed, "test polygon"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SourceInputGeometry.materializePrediction(malformed, PLANE));
        }
    }

    static List<String> geometryCases() {
        return List.of(
                "POLYGON ((10000 10000,10020.0001 10000,10020 10020,10000 10020,10000 10000))",
                "POLYGON ((1.123456789 2,20 3.987654321,18 25,2 20,1.123456789 2))",
                "POLYGON ((0 0,10 0.001,20 0,20 20,0 20,0 0))",
                "POLYGON ((0 0,0 0,30 0,30 30,0 30,0 0))",
                "POLYGON ((0 0,30 0,30 30,0 30,0 0),(5 5,5 10,10 10,10 5,5 5))",
                "MULTIPOLYGON (((0 0,5 0,5 5,0 5,0 0)),((10 0,15 0,15 5,10 5,10 0)))",
                "MULTIPOLYGON (((0 0,5 0,5 5,0 5,0 0)))",
                PolygonOutlineFixture.rawPolygon(PolygonOutlineFixture.dense(40, 80, 30, 60))
                        .toText());
    }

    /**
     * Independent native-persistence oracle. The previous ae658c1 oracle included the defective AWT
     * round trip; read PolygonROI's stored points instead, then verify public save/reload in
     * SourceInputGeometryTest. This deliberately does not call the production converter.
     */
    static ROI referenceRoi(Geometry input) throws Exception {
        Geometry materialized = input.copy();
        CanonicalGeometryCodec.canonicalCoordinates(materialized);
        if (materialized.getNumGeometries() > 1
                || materialized instanceof Polygon polygon && polygon.getNumInteriorRing() > 0) {
            materialized =
                    roundTripRoi(
                                    GeometryTools.geometryToROI(
                                            materialized, ImagePlane.getDefaultPlane()))
                            .getGeometry();
        }
        Geometry persistent =
                nativeGeometry(
                        GeometryTools.geometryToROI(materialized, ImagePlane.getDefaultPlane()));
        CanonicalGeometryCodec.canonicalCoordinates(persistent);
        if (!(persistent.getArea() > 0.0)) {
            throw new IllegalArgumentException("reference outline must have positive area");
        }
        ROI result = GeometryTools.geometryToROI(persistent, ImagePlane.getDefaultPlane());
        CanonicalGeometryCodec.validatePolygonalGeometry(nativeGeometry(result));
        assertTrue(nativeGeometry(result).getArea() > 0.0);
        return result;
    }

    static ROI roundTripRoi(ROI roi) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(roi);
        }
        try (var input =
                PathIO.createObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (ROI) input.readObject();
        }
    }

    static Geometry nativeGeometry(ROI roi) {
        return roi.getClass() == PolygonROI.class
                ? PolygonOutlineFixture.rawPolygon(roi)
                : roi.getGeometry();
    }

    private static ProvenanceCodec.ObjectProvenance provenance() {
        return new ProvenanceCodec.ObjectProvenance(
                ProvenanceCodec.SCHEMA_VERSION,
                new ExecutionScopeCodec.OutputKey(
                        new InferenceIdentityCodec.ModelStepIdentity(
                                new ModuleRef("histoforge", "test"), "cells"),
                        new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ID))),
                ID);
    }
}

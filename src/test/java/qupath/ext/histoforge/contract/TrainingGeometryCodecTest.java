package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.TrainingGeometryCodec.TrainingObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class TrainingGeometryCodecTest {

    @Test
    void roiAndObjectsRoundTripToTheClosedGeoJsonShapes() {
        var roi = ROIs.createRectangleROI(1, 2, 30, 40, ImagePlane.getDefaultPlane()).getGeometry();
        var late =
                new TrainingObject(
                        "22222222-2222-4222-8222-222222222222",
                        "detection",
                        "Leydig cell",
                        ROIs.createRectangleROI(20, 20, 5, 6, ImagePlane.getDefaultPlane())
                                .getGeometry());
        var early =
                new TrainingObject(
                        "11111111-1111-4111-8111-111111111111",
                        "annotation",
                        "Leydig cell",
                        ROIs.createRectangleROI(5, 6, 7, 8, ImagePlane.getDefaultPlane())
                                .getGeometry());

        var roiJson = TrainingGeometryCodec.roiFeature(roi);
        var parsedRoi =
                TrainingGeometryCodec.parseRoiFeature(
                        JsonParser.parseString(
                                new String(
                                        CanonicalCodec.canonicalBytes(roiJson),
                                        StandardCharsets.UTF_8)));
        assertEquals(
                CanonicalGeometryCodec.canonicalDigest(roi),
                CanonicalGeometryCodec.canonicalDigest(parsedRoi));

        var collection = TrainingGeometryCodec.objectCollection(List.of(late, early));
        var parsed =
                TrainingGeometryCodec.parseObjectCollection(
                        JsonParser.parseString(
                                new String(
                                        CanonicalCodec.canonicalBytes(collection),
                                        StandardCharsets.UTF_8)));
        assertEquals(List.of(early, late), parsed);
        assertEquals(
                "11111111-1111-4111-8111-111111111111",
                collection
                        .getAsJsonArray("features")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("properties")
                        .get("source_object_uuid")
                        .getAsString());
    }

    @Test
    void duplicateObjectUuidAndOpenFeatureShapesFail() {
        var object =
                new TrainingObject(
                        "11111111-1111-4111-8111-111111111111",
                        "detection",
                        "Leydig cell",
                        ROIs.createRectangleROI(1, 1, 2, 2, ImagePlane.getDefaultPlane())
                                .getGeometry());
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingGeometryCodec.objectCollection(List.of(object, object)));

        var feature = TrainingGeometryCodec.roiFeature(object.geometry());
        feature.addProperty("unexpected", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingGeometryCodec.parseRoiFeature(feature));
    }

    @Test
    void cellObjectTypeFails() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TrainingObject(
                                "22222222-2222-4222-8222-222222222222",
                                "cell",
                                "Leydig cell",
                                ROIs.createRectangleROI(1, 1, 2, 2, ImagePlane.getDefaultPlane())
                                        .getGeometry()));
    }
}

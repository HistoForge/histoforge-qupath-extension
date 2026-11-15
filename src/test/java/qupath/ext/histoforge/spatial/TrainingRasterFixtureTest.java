package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.contract.TrainingTaskCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.FieldModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.TargetWork;

/** Exact numeric grid fixtures shared with the backend's training raster constructor. */
class TrainingRasterFixtureTest {

    @TestFactory
    Stream<DynamicTest> retainedTrainingFramesUseTheNormalInferenceGrid() throws Exception {
        JsonArray cases;
        try (var reader =
                new InputStreamReader(
                        getClass().getResourceAsStream("/contracts/training-raster-v1.json"),
                        StandardCharsets.UTF_8)) {
            cases = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases");
        }
        return StreamSupport.stream(cases.spliterator(), false)
                .map(value -> value.getAsJsonObject())
                .map(value -> dynamicTest(value.get("name").getAsString(), () -> verify(value)));
    }

    private static void verify(JsonObject value) {
        JsonArray dimensions = value.getAsJsonArray("source_dimensions");
        JsonArray mpp = value.getAsJsonArray("source_mpp");
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "fixture-reader",
                        "1".repeat(64),
                        Optional.empty(),
                        dimensions.get(0).getAsInt(),
                        dimensions.get(1).getAsInt(),
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(
                                mpp.get(0).getAsDouble(), mpp.get(1).getAsDouble()));
        double targetMpp = value.get("target_mpp").getAsDouble();
        var mapping =
                value.has("level_zero_pixels_to_slide")
                        ? SourceIdentityCodec.parseScaleTranslate2D(
                                value.get("level_zero_pixels_to_slide"))
                        : new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        Envelope roi = envelope(value.getAsJsonArray("roi_bounds"));
        Envelope bounds = SourceInputGeometry.sourceBounds(source, mapping);
        boolean crop = value.get("input_kind").getAsString().equals("crop");
        boolean context = value.get("small_roi_context").getAsBoolean();
        int minimum = value.get("minimum_side_model_px").getAsInt();
        Envelope frame =
                crop
                        ? SourceInputGeometry.parentFrame(
                                roi,
                                value.get("crop_padding_model_px").getAsInt(),
                                targetMpp,
                                source,
                                bounds)
                        : context
                                ? SourceInputGeometry.contextualFieldFrame(
                                        roi, minimum, targetMpp, source, bounds)
                                : roi;
        assertEquals(envelope(value.getAsJsonArray("frame_slide")), frame);
        var expected = RasterInputCodec.parseRasterTransform(value.get("expected_raster"));
        assertEquals(
                expected,
                SourceInputGeometry.rasterTransform(
                        frame, targetMpp, new SourceIdentityCodec.ImagePlaneIndex(0, 0), source));
        if (!crop
                && value.get("planner")
                        .getAsString()
                        .equals(TrainingTaskCodec.VARIABLE_FIELD_PLANNER)) {
            verifyInferencePlan(source, mapping, roi, expected, minimum, context);
        }
    }

    /** Exercise normal inference planning, not only the numeric geometry helper. */
    private static void verifyInferencePlan(
            SourceIdentityCodec.ImageSourceIdentity source,
            SourceIdentityCodec.ScaleTranslate2D mapping,
            Envelope roi,
            RasterInputCodec.RasterTransform expected,
            int minimum,
            boolean context) {
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "fixture-image",
                                SourceIdentityCodec.imageSourceIdentityDigest(source),
                                "2".repeat(64)),
                        source);
        var captured =
                new CapturedSourcePyramid(
                        revision,
                        0,
                        mapping,
                        List.of(
                                new SourceLevel(
                                        0,
                                        new SourceViewGeometry.Dimensions(
                                                source.width(), source.height()),
                                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0))));
        var geometry = new GeometryFactory().toGeometry(roi);
        var target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Roi("11111111-1111-4111-8111-111111111111"),
                        geometry,
                        expected.raster().plane(),
                        CanonicalGeometryCodec.canonicalDigest(geometry));
        var profile =
                new VariableFieldProfile(
                        minimum,
                        minimum,
                        minimum,
                        16,
                        TrainingTaskCodec.VARIABLE_FIELD_OUTER_ARTIFICIAL_PADDING_MODEL_PX,
                        context,
                        0.2,
                        "test-only-aggregation",
                        "test-only-preprocessing");
        var model =
                new FieldModel(
                        new InferenceIdentityCodec.ModelStepIdentity(
                                new ModuleRef("owner", "module"), "field"),
                        expected.raster().targetMpp(),
                        Optional.of(profile),
                        new ExecutionLimitsCodec.TargetRasterLimits(2_048, 2_048, 4_194_304),
                        true,
                        Set.of(SourceArtifactCodec.SourceContainer.PNG));
        var planned =
                SourceInputPlanner.plan(
                                captured,
                                List.of(new TargetWork(target, List.of(model))),
                                Optional.empty(),
                                new SourcePlanningLimits(1_000_000, 8_000_000, 8_000_000))
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .fieldInputs()
                        .getFirst();
        assertEquals(expected, planned.frame().transform());
        assertEquals(target, planned.acceptance().target());
        assertEquals(
                CanonicalGeometryCodec.canonicalDigest(
                        SourceInputGeometry.geometryToRaster(geometry, expected)),
                planned.frame().inclusionMaskDigest());
    }

    private static Envelope envelope(JsonArray bounds) {
        return new Envelope(
                bounds.get(0).getAsDouble(),
                bounds.get(2).getAsDouble(),
                bounds.get(1).getAsDouble(),
                bounds.get(3).getAsDouble());
    }
}

package qupath.ext.histoforge.data;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricImageSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricInputDraft;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricSchema;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;

/** Small exact values shared by the detached metric tests. */
final class MetricTestData {

    static final String IMAGE_ID = "metric-image";
    static final String ROI_A = "10000000-0000-4000-8000-000000000001";
    static final String ROI_B = "10000000-0000-4000-8000-000000000002";
    static final String ROI_C = "10000000-0000-4000-8000-000000000003";
    static final ModuleRef MODULE_REF = new ModuleRef("histoforge", "metric-test");
    static final InferenceIdentityCodec.ModuleContentIdentity MODULE =
            new InferenceIdentityCodec.ModuleContentIdentity(
                    MODULE_REF, Optional.of("a".repeat(40)), "b".repeat(64), "c".repeat(64));

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private MetricTestData() {}

    static BuildIdentityCodec.ClientBuildIdentity clientBuild(
            long regionMetricLimit, long imageMetricLimit) {
        return new BuildIdentityCodec.ClientBuildIdentity(
                new BuildIdentityCodec.SoftwareBuildIdentity(
                        "test", "d".repeat(64), "e".repeat(64)),
                "0.6.0",
                Map.of("java", "21", "jts", "1.20.0"),
                new ExecutionLimitsCodec.ClientExecutionLimits(
                        100_000,
                        1_000_000,
                        1_000_000,
                        8_000_000,
                        1_000_000,
                        regionMetricLimit,
                        imageMetricLimit,
                        8L * 1024 * 1024 * 1024));
    }

    static MetricScriptSnapshot script(String source, MetricDefinition... definitions) {
        return new MetricScriptSnapshot(
                source,
                CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)),
                new MetricSchema(List.of(definitions)));
    }

    static MetricRegionSnapshot region(String roiId) {
        return new MetricRegionSnapshot(target(roiId), List.of());
    }

    static ExecutionScopeCodec.TargetRegion target(String roiId) {
        Geometry geometry = square(0, 0, 100, 100);
        return new ExecutionScopeCodec.TargetRegion(
                new ExecutionScopeCodec.Roi(roiId),
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    static MetricPreparation preparation(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            MetricScriptSnapshot script,
            RasterInputCodec.ImageRevisionToken revision,
            MetricRegionSnapshot... regions) {
        MetricInputDraft input =
                MetricInputDraft.build(
                        clientBuild,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25),
                        MODULE,
                        script,
                        List.of(regions));
        return new MetricPreparation(new MetricImageSnapshot(input, revision), List.of());
    }

    static RasterInputCodec.ImageRevisionToken revision(char contentDigit) {
        return new RasterInputCodec.ImageRevisionToken(
                IMAGE_ID, "f".repeat(64), String.valueOf(contentDigit).repeat(64));
    }

    static Geometry square(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.createPolygon(
                new CoordinateXY[] {
                    new CoordinateXY(minX, minY),
                    new CoordinateXY(maxX, minY),
                    new CoordinateXY(maxX, maxY),
                    new CoordinateXY(minX, maxY),
                    new CoordinateXY(minX, minY)
                });
    }

    static UUID objectId(int suffix) {
        return UUID.fromString("20000000-0000-4000-8000-%012d".formatted(suffix));
    }
}

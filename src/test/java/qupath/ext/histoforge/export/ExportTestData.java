package qupath.ext.histoforge.export;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricImageResolver.ResolvedImage;
import qupath.ext.histoforge.data.MetricImageResolver.ResolvedModule;
import qupath.ext.histoforge.data.MetricResultContext;
import qupath.ext.histoforge.data.MetricSnapshotBuilder.CapturedPreparation;
import qupath.ext.histoforge.data.MetricTypes;
import qupath.ext.histoforge.data.MetricTypes.Distribution;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricImageSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricInputDraft;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionResult;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricSchema;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.ext.histoforge.data.MetricTypes.Missing;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.export.ExportTypes.ExportClaim;
import qupath.ext.histoforge.export.ExportTypes.ExportGenerationEvidence;
import qupath.ext.histoforge.export.ExportTypes.ExportImageEvidence;
import qupath.ext.histoforge.export.ExportTypes.ExportManifest;
import qupath.ext.histoforge.export.ExportTypes.ExportModuleEvidence;
import qupath.ext.histoforge.export.ExportTypes.ProvenanceAuditSummary;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.pipeline.ImageLifecycle;

/** Exact representative values shared by the export transaction tests. */
final class ExportTestData {

    static final String IMAGE_ID = "export-image";
    static final String ROI_ID = "10000000-0000-4000-8000-000000000001";
    static final ModuleRef MODULE_REF = new ModuleRef("histoforge", "export-test");
    static final Instant CAPTURED_AT = Instant.parse("2026-08-16T12:34:56.123Z");
    static final Instant CREATED_AT = Instant.parse("2026-08-16T12:35:00.456Z");
    static final UUID EXPORT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final InferenceIdentityCodec.ModuleContentIdentity MODULE =
            new InferenceIdentityCodec.ModuleContentIdentity(
                    MODULE_REF, Optional.of("a".repeat(40)), "b".repeat(64), "c".repeat(64));

    private ExportTestData() {}

    static BuildIdentityCodec.ClientBuildIdentity clientBuild() {
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
                        1_000_000,
                        2_000_000,
                        8L * 1024 * 1024 * 1024));
    }

    static List<MetricDefinition> definitions() {
        return List.of(
                new MetricDefinition(
                        "zero",
                        "Zero",
                        MetricTypes.MetricType.SCALAR,
                        Optional.of("objects"),
                        0,
                        MetricTypes.AggregationType.SUM),
                new MetricDefinition(
                        "empty",
                        "Empty",
                        MetricTypes.MetricType.DISTRIBUTION,
                        Optional.of("um2"),
                        2,
                        MetricTypes.AggregationType.POOL),
                new MetricDefinition(
                        "optional",
                        "Optional",
                        MetricTypes.MetricType.SCALAR,
                        Optional.empty(),
                        3,
                        MetricTypes.AggregationType.MEAN),
                new MetricDefinition(
                        "values",
                        "Values",
                        MetricTypes.MetricType.DISTRIBUTION,
                        Optional.of("um2"),
                        2,
                        MetricTypes.AggregationType.POOL));
    }

    static ModuleSnapshot module() {
        String source = metricSource();
        ModuleSnapshot.ScriptSnapshot script =
                new ModuleSnapshot.ScriptSnapshot(
                        ModuleContract.METRICS_SCRIPT_NAME,
                        source,
                        CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)));
        List<MetricInfo> schema =
                definitions().stream()
                        .map(
                                definition ->
                                        new MetricInfo(
                                                definition.name(),
                                                definition.label(),
                                                definition.type() == MetricTypes.MetricType.SCALAR
                                                        ? "scalar"
                                                        : "distribution",
                                                definition.unit().orElse(null),
                                                definition.decimalPlaces(),
                                                definition.aggregation().name().toLowerCase()))
                        .toList();
        return new ModuleSnapshot(
                "deployment-test",
                MODULE,
                new JsonObject(),
                "f".repeat(64),
                Map.of(),
                Map.of(),
                Optional.of(new ModuleSnapshot.MetricScriptSnapshot(script, schema)));
    }

    static ResolvedImage resolvedImage() {
        ModuleSnapshot module = module();
        MetricPreparation preparation = preparation();
        ExecutionScopeCodec.TargetRegion target =
                preparation.snapshot().metricInput().regions().getFirst().target();
        Map<String, MetricTypes.MetricValue> values = new LinkedHashMap<>();
        values.put("zero", new Scalar(0));
        values.put("empty", new Distribution(List.of()));
        values.put("optional", new Missing());
        values.put("values", new Distribution(List.of(1.0, 3.0)));
        MetricsEngine.Evaluation evaluation =
                new MetricsEngine.Evaluation(
                        MetricResultContext.from(preparation.snapshot()),
                        Map.of(target.key(), new MetricRegionResult(values)),
                        Map.of(),
                        false);
        ResolvedModule resolvedModule =
                new ResolvedModule(
                        module,
                        new CapturedPreparation(preparation, Map.of()),
                        Optional.of(evaluation));
        return new ResolvedImage(
                CAPTURED_AT,
                ImageLifecycle.ImageDataMaterialization.STORED_QPDATA,
                sourceRevision(),
                Optional.of(GenerationStoreCodec.emptyStore()),
                List.of(resolvedModule));
    }

    static MetricPreparation preparation() {
        ExecutionScopeCodec.TargetRegion target = target();
        MetricScriptSnapshot script =
                new MetricScriptSnapshot(
                        metricSource(),
                        CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(metricSource())),
                        new MetricSchema(definitions()));
        MetricInputDraft input =
                MetricInputDraft.build(
                        clientBuild(),
                        sourceRevision().pixelSource().calibration(),
                        MODULE,
                        script,
                        List.of(new MetricRegionSnapshot(target, List.of())));
        return new MetricPreparation(
                new MetricImageSnapshot(input, sourceRevision().imageDataRevision()), List.of());
    }

    static ExportManifest manifest() {
        ResolvedImage resolved = resolvedImage();
        ResolvedModule module = resolved.modules().getFirst();
        MetricsEngine.Evaluation evaluation = module.evaluation().orElseThrow();
        ExportGenerationEvidence generation =
                ExportManifestCodec.generationEvidence(
                        resolved.generationStore().orElseThrow(), Set.of());
        ExportModuleEvidence moduleEvidence =
                new ExportModuleEvidence(
                        evaluation.context().metricInputDigest(),
                        module.module().module(),
                        evaluation.context().metricsScriptDigest(),
                        evaluation.context().regions(),
                        new ProvenanceAuditSummary(0, 0),
                        generation);
        return new ExportManifest(
                ExportTypes.MANIFEST_SCHEMA_VERSION,
                EXPORT_ID,
                CREATED_AT,
                clientBuild(),
                ExportClaim.CAPTURED_HIERARCHY_METRICS,
                List.of(
                        new ExportImageEvidence(
                                CAPTURED_AT,
                                resolved.materialization(),
                                resolved.sourceRevision().imageDataRevision(),
                                Map.of("group", "Control", "study", "Study A"),
                                List.of(moduleEvidence))));
    }

    static ExportWorkbookWriter.ImageMetrics imageMetrics() {
        return new ExportWorkbookWriter.ImageMetrics(
                IMAGE_ID,
                "Image A",
                Map.of("group", "Control", "study", "Study A"),
                resolvedImage().modules().stream()
                        .map(module -> module.evaluation().orElseThrow())
                        .toList());
    }

    static RasterInputCodec.ActionSourceRevision sourceRevision() {
        SourceIdentityCodec.ImageSourceIdentity source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "fixture.reader",
                        "1".repeat(64),
                        Optional.empty(),
                        100,
                        80,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        IMAGE_ID,
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "2".repeat(64)),
                source);
    }

    static ExecutionScopeCodec.TargetRegion target() {
        Geometry geometry =
                GEOMETRY_FACTORY.createPolygon(
                        new CoordinateXY[] {
                            new CoordinateXY(0, 0),
                            new CoordinateXY(100, 0),
                            new CoordinateXY(100, 80),
                            new CoordinateXY(0, 80),
                            new CoordinateXY(0, 0)
                        });
        return new ExecutionScopeCodec.TargetRegion(
                new ExecutionScopeCodec.Roi(ROI_ID),
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    private static String metricSource() {
        return """
        /* @histoforge-metrics
        [{"name":"zero","label":"Zero","type":"scalar","unit":"objects","decimals":0,"aggregation":"sum"},{"name":"empty","label":"Empty","type":"distribution","unit":"um2","decimals":2,"aggregation":"pool"},{"name":"optional","label":"Optional","type":"scalar","decimals":3,"aggregation":"mean"},{"name":"values","label":"Values","type":"distribution","unit":"um2","decimals":2,"aggregation":"pool"}]
        */
        return [zero: 0, empty: [], optional: null, values: [1.0, 3.0]]
        """;
    }
}

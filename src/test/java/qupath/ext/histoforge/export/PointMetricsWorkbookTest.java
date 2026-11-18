package qupath.ext.histoforge.export;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.data.MetricTypes.*;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class PointMetricsWorkbookTest {
    @Test
    void nativePointCountDensityAndScoreDistributionReachOrdinaryWorkbook(@TempDir Path directory)
            throws Exception {
        String scriptSource =
                """
                /* @histoforge-metrics
                [{"name":"count","label":"Count","type":"scalar","decimals":0,"aggregation":"sum"},
                 {"name":"density","label":"Density","type":"scalar","decimals":8,"aggregation":"mean"},
                 {"name":"scores","label":"Scores","type":"distribution","decimals":8,"aggregation":"pool"}]
                */
                assert children.every { it.ROI.isPoint() && it.ROI.numPoints == 1 }
                return [count: children.size(), density: children.size() / roiAreaPx,
                        scores: children.collect { it.measurementList.get("HistoForge: Detection score") }]
                """;
        var script =
                new MetricScriptSnapshot(
                        scriptSource,
                        CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(scriptSource)),
                        MetricSchema.from(ScriptHeaderParser.parseMetrics(scriptSource)));
        var objects = List.of(point(1, 0.0), point(2, 0.5), point(3, (double) 0.9f));
        var input =
                MetricInputDraft.build(
                        ExportTestData.clientBuild(),
                        new qupath.ext.histoforge.contract.SourceIdentityCodec.PixelCalibration(
                                .25, .25),
                        ExportTestData.module().module(),
                        script,
                        List.of(new MetricRegionSnapshot(ExportTestData.target(), objects)));
        var revision = ExportTestData.preparation().snapshot().sourceRevision();
        var preparation =
                new MetricPreparation(new MetricImageSnapshot(input, revision), List.of());
        var evaluation = new MetricsEngine().evaluate(preparation, Map.of(), false, () -> false);
        assertTrue(evaluation.complete(), evaluation.regionFailures().toString());
        var values = evaluation.regionResults().get(ExportTestData.target().key()).values();
        assertEquals(new Scalar(3), values.get("count"));
        assertEquals(
                new Scalar(3 / ExportTestData.target().geometrySlide().getArea()),
                values.get("density"));
        assertEquals(new Distribution(List.of(0.0, 0.5, (double) 0.9f)), values.get("scores"));
        var original = ExportTestData.manifest();
        var evidence =
                new ExportTypes.ExportModuleEvidence(
                        input.metricInputDigest(),
                        input.module(),
                        script.digest(),
                        List.of(ExportTestData.target()),
                        new ExportTypes.ProvenanceAuditSummary(0, 0),
                        ExportManifestCodec.generationEvidence(
                                GenerationStoreCodec.emptyStore(), Set.of()));
        var manifest =
                new ExportTypes.ExportManifest(
                        original.schemaVersion(),
                        original.exportId(),
                        original.createdAt(),
                        original.clientBuild(),
                        original.claim(),
                        List.of(
                                new ExportTypes.ExportImageEvidence(
                                        ExportTestData.CAPTURED_AT,
                                        original.images().getFirst().materialization(),
                                        revision,
                                        Map.of(),
                                        List.of(evidence))));
        Path path = directory.resolve("points.xlsx");
        ExportWorkbookWriter.write(
                path,
                manifest,
                List.of(
                        new ExportWorkbookWriter.ImageMetrics(
                                ExportTestData.IMAGE_ID,
                                "Point fixture",
                                Map.of(),
                                List.of(evaluation))));
        var workbook = XlsxTestReader.read(path);
        var summary = workbook.sheet("Summary");
        assertEquals("count", summary.cell(1, 4));
        assertEquals(3, Double.parseDouble(summary.cell(1, 10)));
        assertEquals("density", summary.cell(2, 4));
        assertEquals(
                3 / ExportTestData.target().geometrySlide().getArea(),
                Double.parseDouble(summary.cell(2, 10)));
        var raw = workbook.sheet("Raw Distribution");
        assertEquals(4, raw.rowCount());
        for (int i = 0; i < 3; i++) {
            assertEquals("scores", raw.cell(i + 1, 7));
            assertEquals(
                    ((Distribution) values.get("scores")).values().get(i),
                    Double.valueOf(raw.cell(i + 1, 9)));
        }
    }

    private static MetricObjectSnapshot point(int index, double score) {
        return new MetricObjectSnapshot(
                UUID.fromString("20000000-0000-4000-8000-%012d".formatted(index)),
                MetricObjectType.DETECTION,
                "Center",
                NativeRoiGeometry.convert(
                        ROIs.createPointsROI(
                                5.5 * index, 5.25 * index, ImagePlane.getDefaultPlane())),
                Optional.empty(),
                OptionalDouble.of(score));
    }
}

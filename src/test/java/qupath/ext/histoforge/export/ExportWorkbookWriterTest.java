package qupath.ext.histoforge.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static qupath.ext.histoforge.data.MetricResultRetentionAssertions.assertOnlyResultData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.util.AffineTransformation;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricResultContext;
import qupath.ext.histoforge.data.MetricTypes;
import qupath.ext.histoforge.data.MetricsEngine;

/** Golden worksheet structure, value-state fidelity, and evidence reconstruction. */
class ExportWorkbookWriterTest {

    @TempDir Path temporaryDirectory;

    @Test
    void writesNormalizedMetricSheetsAndReconstructableEvidence() throws Exception {
        Path workbook = temporaryDirectory.resolve("metrics.xlsx");
        var manifest = ExportTestData.manifest();
        var image = ExportTestData.imageMetrics();
        assertOnlyResultData(image);

        ExportWorkbookWriter.write(workbook, manifest, List.of(image));

        XlsxTestReader.WorkbookValues values = XlsxTestReader.read(workbook);
        assertEquals(
                List.of("Summary", "Per Region", "Raw Distribution", "Coverage", "Evidence"),
                List.copyOf(values.sheets().keySet()));
        assertEquals(
                List.of(
                        "image_id",
                        "image_name",
                        "group",
                        "module_ref",
                        "metric_name",
                        "label",
                        "unit",
                        "type",
                        "aggregation",
                        "status",
                        "value",
                        "mean",
                        "population_std",
                        "n",
                        "requested_regions",
                        "present_regions",
                        "missing_regions",
                        "failed_regions"),
                values.sheet("Summary").row(0, 18));

        XlsxTestReader.SheetValues summary = values.sheet("Summary");
        assertEquals("A1:R" + summary.rowCount(), summary.dimension());
        int zero = rowWith(summary, 4, "zero");
        int empty = rowWith(summary, 4, "empty");
        int optional = rowWith(summary, 4, "optional");
        int distribution = rowWith(summary, 4, "values");
        assertEquals("PRESENT", summary.cell(zero, 9));
        assertEquals(0.0, number(summary.cell(zero, 10)));
        assertEquals("PRESENT_EMPTY", summary.cell(empty, 9));
        assertNull(summary.cell(empty, 11));
        assertNull(summary.cell(empty, 12));
        assertEquals(0.0, number(summary.cell(empty, 13)));
        assertEquals("MISSING", summary.cell(optional, 9));
        assertNull(summary.cell(optional, 10));
        assertEquals("PRESENT", summary.cell(distribution, 9));
        assertEquals(2.0, number(summary.cell(distribution, 11)));
        assertEquals(1.0, number(summary.cell(distribution, 12)));
        assertEquals(2.0, number(summary.cell(distribution, 13)));

        XlsxTestReader.SheetValues perRegion = values.sheet("Per Region");
        assertEquals("A1:P" + perRegion.rowCount(), perRegion.dimension());
        assertEquals(5, perRegion.rowCount());
        assertEquals("PRESENT", perRegion.cell(rowWith(perRegion, 7, "zero"), 11));
        assertEquals("PRESENT_EMPTY", perRegion.cell(rowWith(perRegion, 7, "empty"), 11));
        assertEquals("MISSING", perRegion.cell(rowWith(perRegion, 7, "optional"), 11));

        XlsxTestReader.SheetValues raw = values.sheet("Raw Distribution");
        assertEquals("A1:J" + raw.rowCount(), raw.dimension());
        assertEquals(3, raw.rowCount());
        assertEquals("values", raw.cell(1, 7));
        assertEquals(0.0, number(raw.cell(1, 8)));
        assertEquals(1.0, number(raw.cell(1, 9)));
        assertEquals("values", raw.cell(2, 7));
        assertEquals(1.0, number(raw.cell(2, 8)));
        assertEquals(3.0, number(raw.cell(2, 9)));

        XlsxTestReader.SheetValues coverage = values.sheet("Coverage");
        assertEquals("A1:I" + coverage.rowCount(), coverage.dimension());
        assertEquals(5, coverage.rowCount());
        for (int row = 1; row < coverage.rowCount(); row++) {
            assertEquals(1.0, number(coverage.cell(row, 5)));
            boolean missing = "optional".equals(coverage.cell(row, 4));
            assertEquals(missing ? 0.0 : 1.0, number(coverage.cell(row, 6)));
            assertEquals(missing ? 1.0 : 0.0, number(coverage.cell(row, 7)));
            assertEquals(0.0, number(coverage.cell(row, 8)));
        }

        XlsxTestReader.SheetValues evidence = values.sheet("Evidence");
        assertEquals("A1:E" + evidence.rowCount(), evidence.dimension());
        JsonElement reconstructed = reconstructEvidence(evidence);
        assertArrayEquals(
                ExportManifestCodec.canonicalBytes(manifest),
                CanonicalCodec.canonicalBytes(reconstructed));
        assertArrayEquals(
                ExportManifestCodec.canonicalBytes(manifest),
                ExportManifestCodec.canonicalBytes(ExportManifestCodec.parse(reconstructed)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 32})
    void preparesRegionIdentityOncePerTableRegardlessOfDistributionLength(int valueCount)
            throws Exception {
        var target = ExportTestData.target();
        var module = ExportTestData.preparation().snapshot().metricInput().module();
        List<Double> rawValues =
                IntStream.range(0, valueCount).mapToObj(index -> index / 2.0).toList();
        var image =
                imageMetrics(
                        ExportTestData.IMAGE_ID,
                        List.of(evaluation(ExportTestData.IMAGE_ID, module, target, rawValues)));
        var manifest = manifestFor(List.of(image));
        String digest = ExecutionScopeCodec.targetRegionDigest(target);
        Path workbook = temporaryDirectory.resolve("distribution-" + valueCount + ".xlsx");

        try (var codec = mockStatic(ExecutionScopeCodec.class, CALLS_REAL_METHODS)) {
            ExportWorkbookWriter.write(workbook, manifest, List.of(image));
            codec.verify(() -> ExecutionScopeCodec.targetRegionDigest(target), times(2));
        }

        var values = XlsxTestReader.read(workbook);
        var perRegion = values.sheet("Per Region");
        assertEquals(5, perRegion.rowCount());
        for (int row = 1; row < perRegion.rowCount(); row++) {
            assertEquals(
                    List.of("ROI", ExportTestData.ROI_ID, digest), regionCells(perRegion, row));
        }
        var raw = values.sheet("Raw Distribution");
        assertEquals(valueCount + 1, raw.rowCount());
        for (int index = 0; index < valueCount; index++) {
            int row = index + 1;
            assertEquals(List.of("ROI", ExportTestData.ROI_ID, digest), regionCells(raw, row));
            assertEquals("values", raw.cell(row, 7));
            assertEquals((double) index, number(raw.cell(row, 8)));
            assertEquals(rawValues.get(index), number(raw.cell(row, 9)));
        }
    }

    @Test
    void keepsRegionIdentityBoundToItsImageModuleGeometryAndPlane() throws Exception {
        var first = ExportTestData.target();
        var changedGeometry =
                AffineTransformation.scaleInstance(0.5, 0.5).transform(first.geometrySlide());
        var second =
                new ExecutionScopeCodec.TargetRegion(
                        first.key(),
                        changedGeometry,
                        new SourceIdentityCodec.ImagePlaneIndex(1, 2),
                        CanonicalGeometryCodec.canonicalDigest(changedGeometry));
        var third =
                new ExecutionScopeCodec.TargetRegion(
                        first.key(), first.geometrySlide(), second.plane(), first.geometryDigest());
        var slide =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Slide(),
                        first.geometrySlide(),
                        first.plane(),
                        first.geometryDigest());
        var module = ExportTestData.preparation().snapshot().metricInput().module();
        var anotherModule =
                new InferenceIdentityCodec.ModuleContentIdentity(
                        new ModuleRef("histoforge", "another-export-test"),
                        module.resolvedCommitSha(),
                        module.recipeDigest(),
                        module.contentDigest());
        List<Double> rawValues = List.of(1.0, 3.0);
        var images =
                List.of(
                        imageMetrics(
                                ExportTestData.IMAGE_ID,
                                List.of(
                                        evaluation(
                                                ExportTestData.IMAGE_ID, module, first, rawValues),
                                        evaluation(
                                                ExportTestData.IMAGE_ID,
                                                anotherModule,
                                                second,
                                                rawValues))),
                        imageMetrics(
                                "another-image",
                                List.of(evaluation("another-image", module, third, rawValues))),
                        imageMetrics(
                                "slide-image",
                                List.of(evaluation("slide-image", module, slide, rawValues))));
        Path workbook = temporaryDirectory.resolve("region-identities.xlsx");

        ExportWorkbookWriter.write(workbook, manifestFor(images), images);

        var values = XlsxTestReader.read(workbook);
        for (String name : List.of("Per Region", "Raw Distribution")) {
            var sheet = values.sheet(name);
            int row = 1;
            for (var image : images) {
                for (var evaluation : image.evaluations()) {
                    var target = evaluation.context().regions().getFirst();
                    boolean roi = target.key() instanceof ExecutionScopeCodec.Roi;
                    var expected =
                            List.of(
                                    roi ? "ROI" : "SLIDE",
                                    roi ? ExportTestData.ROI_ID : "slide",
                                    ExecutionScopeCodec.targetRegionDigest(target));
                    int rows = "Per Region".equals(name) ? 4 : rawValues.size();
                    for (int index = 0; index < rows; index++, row++) {
                        assertEquals(image.imageId(), sheet.cell(row, 0));
                        assertEquals(
                                evaluation.context().module().moduleRef().toString(),
                                sheet.cell(row, 3));
                        assertEquals(expected, regionCells(sheet, row));
                        if ("Raw Distribution".equals(name)) {
                            assertEquals((double) index, number(sheet.cell(row, 8)));
                            assertEquals(rawValues.get(index), number(sheet.cell(row, 9)));
                        }
                    }
                }
            }
            assertEquals(row, sheet.rowCount());
        }
        assertArrayEquals(
                ExportManifestCodec.canonicalBytes(manifestFor(images)),
                CanonicalCodec.canonicalBytes(reconstructEvidence(values.sheet("Evidence"))));
    }

    private static List<String> regionCells(XlsxTestReader.SheetValues sheet, int row) {
        return List.of(sheet.cell(row, 4), sheet.cell(row, 5), sheet.cell(row, 6));
    }

    private static ExportWorkbookWriter.ImageMetrics imageMetrics(
            String imageId, List<MetricsEngine.Evaluation> evaluations) {
        return new ExportWorkbookWriter.ImageMetrics(
                imageId, "Image " + imageId, Map.of("group", "Control"), evaluations);
    }

    private static MetricsEngine.Evaluation evaluation(
            String imageId,
            InferenceIdentityCodec.ModuleContentIdentity module,
            ExecutionScopeCodec.TargetRegion target,
            List<Double> rawValues) {
        var originalInput = ExportTestData.preparation().snapshot().metricInput();
        var input =
                MetricTypes.MetricInputDraft.build(
                        originalInput.clientBuild(),
                        originalInput.calibration(),
                        module,
                        originalInput.script(),
                        List.of(new MetricTypes.MetricRegionSnapshot(target, List.of())));
        var revision = ExportTestData.sourceRevision().imageDataRevision();
        var context =
                MetricResultContext.from(
                        new MetricTypes.MetricImageSnapshot(
                                input,
                                new RasterInputCodec.ImageRevisionToken(
                                        imageId,
                                        revision.imageSourceDigest(),
                                        revision.imageDataContentDigest())));
        var values =
                new LinkedHashMap<>(
                        ExportTestData.resolvedImage()
                                .modules()
                                .getFirst()
                                .evaluation()
                                .orElseThrow()
                                .regionResults()
                                .values()
                                .iterator()
                                .next()
                                .values());
        values.put("values", new MetricTypes.Distribution(rawValues));
        return new MetricsEngine.Evaluation(
                context,
                Map.of(target.key(), new MetricTypes.MetricRegionResult(values)),
                Map.of(),
                false);
    }

    private static ExportTypes.ExportManifest manifestFor(
            List<ExportWorkbookWriter.ImageMetrics> images) {
        var original = ExportTestData.manifest();
        var generation = original.images().getFirst().modules().getFirst().generationEvidence();
        return new ExportTypes.ExportManifest(
                original.schemaVersion(),
                original.exportId(),
                original.createdAt(),
                original.clientBuild(),
                original.claim(),
                images.stream()
                        .map(
                                image ->
                                        new ExportTypes.ExportImageEvidence(
                                                ExportTestData.CAPTURED_AT,
                                                original.images().getFirst().materialization(),
                                                image.evaluations()
                                                        .getFirst()
                                                        .context()
                                                        .sourceRevision(),
                                                image.studyFields(),
                                                image.evaluations().stream()
                                                        .map(
                                                                evaluation ->
                                                                        new ExportTypes
                                                                                .ExportModuleEvidence(
                                                                                evaluation
                                                                                        .context()
                                                                                        .metricInputDigest(),
                                                                                evaluation
                                                                                        .context()
                                                                                        .module(),
                                                                                evaluation
                                                                                        .context()
                                                                                        .metricsScriptDigest(),
                                                                                evaluation
                                                                                        .context()
                                                                                        .regions(),
                                                                                new ExportTypes
                                                                                        .ProvenanceAuditSummary(
                                                                                        0, 0),
                                                                                generation))
                                                        .toList()))
                        .toList());
    }

    private static JsonElement reconstructEvidence(XlsxTestReader.SheetValues sheet) {
        assertEquals(
                List.of("path", "node_type", "value_text", "value_number", "value_boolean"),
                sheet.row(0, 5));
        Map<String, JsonElement> nodes = new LinkedHashMap<>();
        for (int row = 1; row < sheet.rowCount(); row++) {
            String path = sheet.cell(row, 0);
            String type = sheet.cell(row, 1);
            JsonElement value =
                    switch (type) {
                        case "OBJECT" -> new JsonObject();
                        case "ARRAY" -> new JsonArray();
                        case "NULL" -> JsonNull.INSTANCE;
                        case "STRING" -> new JsonPrimitive(sheet.cell(row, 2));
                        case "INTEGER" ->
                                new JsonPrimitive(Long.parseLong(integerText(sheet.cell(row, 3))));
                        case "NUMBER" -> new JsonPrimitive(Double.parseDouble(sheet.cell(row, 3)));
                        case "BOOLEAN" ->
                                new JsonPrimitive(Boolean.parseBoolean(sheet.cell(row, 4)));
                        default ->
                                throw new IllegalArgumentException("unknown evidence node " + type);
                    };
            if ("/".equals(path)) {
                nodes.put(path, value);
                continue;
            }
            int separator = path.lastIndexOf('/');
            String parentPath = separator == 0 ? "/" : path.substring(0, separator);
            String token = unescape(path.substring(separator + 1));
            JsonElement parent = nodes.get(parentPath);
            if (parent instanceof JsonObject object) {
                object.add(token, value);
            } else if (parent instanceof JsonArray array) {
                int index = Integer.parseInt(token);
                if (index != array.size()) {
                    throw new IllegalArgumentException("evidence array index is not canonical");
                }
                array.add(value);
            } else {
                throw new IllegalArgumentException("evidence parent is absent or scalar");
            }
            nodes.put(path, value);
        }
        return nodes.get("/");
    }

    private static int rowWith(XlsxTestReader.SheetValues sheet, int column, String value) {
        for (int row = 1; row < sheet.rowCount(); row++) {
            if (value.equals(sheet.cell(row, column))) {
                return row;
            }
        }
        throw new AssertionError("worksheet row is missing " + value);
    }

    private static double number(String value) {
        return Double.parseDouble(value);
    }

    private static String integerText(String value) {
        double parsed = Double.parseDouble(value);
        long integer = (long) parsed;
        if (parsed != integer) {
            throw new IllegalArgumentException("evidence integer is not integral");
        }
        return Long.toString(integer);
    }

    private static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }
}

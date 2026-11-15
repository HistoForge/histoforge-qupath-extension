package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetPreimage;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingPackageFile;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingDatasetLimits;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingReportLimits;

class TrainingContractFixtureTest {

    @Test
    void flowCapabilityRequiresValidOptionsAndAnAdvertisedDefault() throws Exception {
        var original = fixture().getAsJsonObject("capability").getAsJsonObject("value");
        for (String options :
                List.of("[]", "null", "[\"pixel\",\"pixel\"]", "[\"other\"]", "[0]")) {
            var invalid = original.deepCopy();
            invalid.add("flow_loss_weighting_options", JsonParser.parseString(options));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TrainingCapabilityCodec.parseCapability(invalid, 4096));
        }
        var capability = original.deepCopy();
        capability.add(
                "flow_loss_weighting_options", JsonParser.parseString("[\"equal_instance\"]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingCapabilityCodec.parseCapability(capability, 4096));
        capability
                .getAsJsonObject("default_training_settings")
                .addProperty("flow_loss_weighting", "equal_instance");
        var parsed = TrainingCapabilityCodec.parseCapability(capability, 4096);
        assertEquals(capability, TrainingCapabilityCodec.toJson(parsed, 4096));
        var pixel =
                TrainingCapabilityCodec.parseSettings(original.get("default_training_settings"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingCapabilityCodec.requireSettingsAllowed(pixel, parsed));
        capability.addProperty("output_type", "dense_classification");
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingCapabilityCodec.parseCapability(capability, 4096));
    }

    @Test
    void sharedFlowSettingCasesPreserveAbsenceAndCanonicalBytes() throws Exception {
        var fixture = fixture();
        for (var element : fixture.getAsJsonArray("flowSettingCases")) {
            var example = element.getAsJsonObject();
            var capabilityJson =
                    fixture.getAsJsonObject("capability").getAsJsonObject("value").deepCopy();
            if (!example.get("advertised").getAsBoolean()) {
                capabilityJson.remove("flow_loss_weighting_options");
                capabilityJson
                        .getAsJsonObject("default_training_settings")
                        .remove("flow_loss_weighting");
            }
            var capability = TrainingCapabilityCodec.parseCapability(capabilityJson, 4096);
            var value = example.get("value");
            if (example.get("valid_syntax").getAsBoolean()) {
                var settings = TrainingCapabilityCodec.parseSettings(value);
                assertEquals(value, TrainingCapabilityCodec.toJson(settings));
                assertEquals(
                        example.get("sha256").getAsString(),
                        CanonicalCodec.canonicalDigest(TrainingCapabilityCodec.toJson(settings)));
                if (example.get("allowed").getAsBoolean()) {
                    TrainingCapabilityCodec.requireSettingsAllowed(settings, capability);
                } else {
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    TrainingCapabilityCodec.requireSettingsAllowed(
                                            settings, capability));
                }
            } else {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TrainingCapabilityCodec.parseSettings(value));
            }
        }
    }

    @Test
    void freezesEveryCrossLanguageDigest() throws Exception {
        JsonObject fixture = fixture();
        var limits = TrainingLimitsCodec.parse(fixture.get("trainingApiLimits"));
        assertEquals(
                fixture.get("trainingApiLimitsSha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingLimitsCodec.toJson(limits)));
        long maxTextBytes = limits.report().maxTextBytes();

        JsonObject capabilityFixture = fixture.getAsJsonObject("capability");
        var capability =
                TrainingCapabilityCodec.parseCapability(
                        capabilityFixture.get("value"), maxTextBytes);
        assertEquals(
                capabilityFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(
                        TrainingCapabilityCodec.toJson(capability, maxTextBytes)));

        JsonObject manifestFixture = fixture.getAsJsonObject("manifest");
        var manifest = TrainingDatasetCodec.parseManifest(manifestFixture.get("value"), limits);
        assertEquals(1, manifest.schemaVersion());
        assertEquals(
                manifestFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingDatasetCodec.toJson(manifest, limits)));

        JsonObject splitFixture = fixture.getAsJsonObject("split");
        var split = TrainingSplitCodec.parsePlan(splitFixture.get("value"), manifest, limits);
        assertEquals(
                splitFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingSplitCodec.toJson(split, manifest, limits)));

        JsonObject runRequestFixture = fixture.getAsJsonObject("runRequest");
        var runRequest = TrainingRunRequestCodec.parse(runRequestFixture.get("value"), limits);
        assertEquals(
                runRequestFixture.get("sha256").getAsString(),
                TrainingRunRequestCodec.digest(runRequest, limits));

        JsonObject startFixture = fixture.getAsJsonObject("start");
        var start =
                TrainingDatasetTransferCodec.parseStartRequest(startFixture.get("value"), limits);
        assertEquals(
                startFixture.get("sha256").getAsString(),
                TrainingDatasetTransferCodec.startRequestDigest(start, limits));
        var range = TrainingDatasetTransferCodec.chunkRange(start, 2, limits);
        JsonObject expectedRange = startFixture.getAsJsonObject("chunkRange2");
        assertEquals(expectedRange.get("chunk_index").getAsInt(), range.chunkIndex());
        assertEquals(expectedRange.get("offset").getAsLong(), range.offset());
        assertEquals(expectedRange.get("size_bytes").getAsLong(), range.sizeBytes());

        JsonObject retainedFixture = fixture.getAsJsonObject("retained");
        var retained =
                TrainingDatasetTransferCodec.parseDatasetRecord(
                        retainedFixture.get("value"), limits);
        assertEquals(
                retainedFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(
                        TrainingDatasetTransferCodec.toJson(retained, limits)));

        for (String name : List.of("uploadAccepted", "existing")) {
            JsonObject startResultFixture = fixture.getAsJsonObject(name);
            var startResult =
                    TrainingDatasetTransferCodec.parseStartResult(
                            startResultFixture.get("value"), limits);
            assertEquals(
                    startResultFixture.get("sha256").getAsString(),
                    CanonicalCodec.canonicalDigest(
                            TrainingDatasetTransferCodec.toJson(startResult, limits)));
        }

        JsonObject sealRequestFixture = fixture.getAsJsonObject("sealRequest");
        var sealRequest =
                TrainingDatasetTransferCodec.parseSealRequest(sealRequestFixture.get("value"));
        assertEquals(
                sealRequestFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingDatasetTransferCodec.toJson(sealRequest)));

        JsonObject chunkReceiptFixture = fixture.getAsJsonObject("chunkReceipt");
        var chunkReceipt =
                TrainingDatasetTransferCodec.parseChunkReceipt(chunkReceiptFixture.get("value"));
        assertEquals(
                chunkReceiptFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingDatasetTransferCodec.toJson(chunkReceipt)));

        JsonObject cleanupFixture = fixture.getAsJsonObject("cleanup");
        var cleanup =
                TrainingDatasetTransferCodec.parseCleanupRecord(
                        cleanupFixture.get("value"), limits);
        assertEquals(
                cleanupFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(
                        TrainingDatasetTransferCodec.toJson(cleanup, limits)));

        JsonObject pageFixture = fixture.getAsJsonObject("datasetPage");
        var page = TrainingDatasetTransferCodec.parsePage(pageFixture.get("value"), limits);
        assertEquals(
                pageFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingDatasetTransferCodec.toJson(page, limits)));

        JsonObject sidecarFixture = fixture.getAsJsonObject("splitSidecar");
        var sidecar =
                TrainingSplitCodec.parseSidecar(sidecarFixture.get("value"), manifest, limits);
        assertEquals(
                sidecarFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(
                        TrainingSplitCodec.toJson(sidecar, manifest, limits)));
    }

    @Test
    void schemaTwoUsesTheSamePackageGraphAndDigestContract() throws Exception {
        var limits = TrainingLimitsCodec.parse(fixture().get("trainingApiLimits"));
        try (var stream =
                        getClass()
                                .getResourceAsStream(
                                        "/contracts/training-dataset-manifest-v2.json");
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var expected = JsonParser.parseReader(reader).getAsJsonObject();
            var manifest = TrainingDatasetCodec.parseManifest(expected.get("value"), limits);
            assertEquals(TrainingDatasetCodec.SCHEMA_VERSION, manifest.schemaVersion());
            assertEquals(
                    expected.get("sha256").getAsString(),
                    CanonicalCodec.canonicalDigest(TrainingDatasetCodec.toJson(manifest, limits)));
            assertEquals(manifest, TrainingDatasetCodec.build(manifest.preimage(), limits));
        }
    }

    @Test
    void schemaOneOriginalRequiresRecaptureWithoutInferringAReader() throws Exception {
        var fixture = fixture();
        var limits = TrainingLimitsCodec.parse(fixture.get("trainingApiLimits"));
        var manifest = fixture.getAsJsonObject("manifest").getAsJsonObject("value").deepCopy();
        manifest.getAsJsonArray("artifacts")
                .get(0)
                .getAsJsonObject()
                .addProperty("representation", "original");
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TrainingDatasetCodec.parseManifest(manifest, limits));
        assertEquals(
                "schema-1 original training artifacts must be recaptured with reader settings",
                error.getMessage());
    }

    @Test
    void appliesJsonMemberLimitOnlyToJsonFiles() throws Exception {
        JsonObject fixture = fixture();
        TrainingApiLimits limits =
                withJsonLimit(TrainingLimitsCodec.parse(fixture.get("trainingApiLimits")), 4_096);
        var manifest =
                TrainingDatasetCodec.parseManifest(
                        fixture.getAsJsonObject("manifest").get("value"), limits);

        List<TrainingPackageFile> oversizedStructured =
                manifest.files().stream()
                        .map(
                                file ->
                                        (file.path().endsWith(".json")
                                                        || file.path().endsWith(".geojson"))
                                                ? new TrainingPackageFile(
                                                        file.path(), 4_097, file.sha256())
                                                : file)
                        .toList();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingDatasetCodec.build(
                                new TrainingDatasetPreimage(
                                        manifest.schemaVersion(),
                                        manifest.task(),
                                        manifest.artifacts(),
                                        manifest.projectGroups(),
                                        manifest.images(),
                                        manifest.rois(),
                                        oversizedStructured),
                                limits));

        List<TrainingPackageFile> largeBinary =
                manifest.files().stream()
                        .map(
                                file ->
                                        file.path().endsWith("source.png")
                                                ? new TrainingPackageFile(
                                                        file.path(), 4_097, file.sha256())
                                                : file)
                        .toList();
        TrainingDatasetCodec.build(
                new TrainingDatasetPreimage(
                        manifest.schemaVersion(),
                        manifest.task(),
                        manifest.artifacts(),
                        manifest.projectGroups(),
                        manifest.images(),
                        manifest.rois(),
                        largeBinary),
                limits);
    }

    @Test
    void fieldCapabilityRejectsCropPadding() throws Exception {
        JsonObject fixture = fixture();
        JsonObject capability =
                fixture.getAsJsonObject("capability").getAsJsonObject("value").deepCopy();
        capability.addProperty("crop_padding_model_px", 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingCapabilityCodec.parseCapability(capability, 4_096));
    }

    @Test
    void retainedDatasetTextRemainsReadableAfterLimitReduction() throws Exception {
        JsonObject fixture = fixture();
        TrainingApiLimits limits =
                withTextLimit(TrainingLimitsCodec.parse(fixture.get("trainingApiLimits")), 1);
        JsonObject retained = fixture.getAsJsonObject("retained").getAsJsonObject("value");

        assertEquals(
                "Leydig cell segmentation",
                TrainingDatasetTransferCodec.parseDatasetRecord(retained, limits)
                        .taskDisplayName());

        JsonObject cleanup = retained.deepCopy();
        cleanup.addProperty("type", "cleanup-required");
        cleanup.remove("dataset_digest");
        cleanup.remove("retained_bytes");
        cleanup.addProperty("last_known_remaining_bytes", 1);
        assertEquals(
                "Leydig cell segmentation",
                TrainingDatasetTransferCodec.parseCleanupRecord(cleanup, limits).taskDisplayName());
    }

    private static TrainingApiLimits withJsonLimit(TrainingApiLimits limits, long maximum) {
        TrainingDatasetLimits dataset = limits.dataset();
        return new TrainingApiLimits(
                new TrainingDatasetLimits(
                        dataset.maxRois(),
                        dataset.maxObjectsPerRoi(),
                        dataset.maxTotalObjects(),
                        dataset.maxVerticesPerGeometry(),
                        dataset.maxTotalVertices(),
                        maximum,
                        dataset.maxArchiveBytes(),
                        dataset.maxExtractedBytes()),
                limits.transfer(),
                limits.report(),
                limits.maxPageSize(),
                limits.maxPageEncodedBytes());
    }

    private static TrainingApiLimits withTextLimit(TrainingApiLimits limits, long maximum) {
        TrainingReportLimits report = limits.report();
        return new TrainingApiLimits(
                limits.dataset(),
                limits.transfer(),
                new TrainingReportLimits(
                        report.maxMetricSeries(),
                        report.maxEpochs(),
                        report.maxMessages(),
                        maximum,
                        report.maxComponentParametersBytes(),
                        report.maxComponentParameterDepth(),
                        report.maxComponentCollectionEntries(),
                        report.maxReportBytes()),
                limits.maxPageSize(),
                limits.maxPageEncodedBytes());
    }

    private static JsonObject fixture() throws Exception {
        try (var stream =
                        TrainingContractFixtureTest.class.getResourceAsStream(
                                "/contracts/training-datasets-v1.json");
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}

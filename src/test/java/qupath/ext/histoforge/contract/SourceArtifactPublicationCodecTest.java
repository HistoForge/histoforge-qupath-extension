package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs the shared schema-1 source-artifact publication contract. */
class SourceArtifactPublicationCodecTest {

    @Test
    void sharedPublicationFixtureBindsExactBytesAndDigests() throws IOException {
        JsonObject fixture = publicationFixture();
        JsonObject starts = startFixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        JsonObject encoded = fixture.getAsJsonObject("encodedArtifactRef");
        JsonObject encodedValue =
                ArtifactRefCodec.toJson(ArtifactRefCodec.parse(encoded.get("value")));
        byte[] encodedBytes = CanonicalCodec.canonicalBytes(encodedValue);
        assertEquals(
                encoded.get("canonical").getAsString(),
                new String(encodedBytes, StandardCharsets.UTF_8));
        assertEquals(encoded.get("sha256").getAsString(), CanonicalCodec.sha256(encodedBytes));

        for (JsonElement item : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = item.getAsJsonObject();
            JsonObject startCase = startCase(starts, testCase.get("startCaseName").getAsString());
            var start = SourceArtifactCodec.parseStartRequest(startCase.get("value"));
            var accepted = SourceArtifactCodec.parseAccepted(startCase.get("accepted"));

            var commit = SourceArtifactTransferCodec.parseChunkCommit(testCase.get("chunkCommit"));
            var chunkAccepted =
                    SourceArtifactTransferCodec.validateChunkAcceptance(
                            start,
                            commit,
                            SourceArtifactTransferCodec.parseChunkAccepted(
                                    testCase.get("chunkAccepted")));
            assertExact(
                    testCase, "chunkAccepted", SourceArtifactTransferCodec.toJson(chunkAccepted));

            var seal = SourceArtifactTransferCodec.parseSealRequest(testCase.get("sealRequest"));
            assertExact(testCase, "sealRequest", SourceArtifactTransferCodec.toJson(seal));

            List<SourceArtifactPublicationCodec.ValidatedSourceLevel> levels =
                    parseLevels(testCase.getAsJsonArray("validatedLevels"));
            var manifest = SourceArtifactPublicationCodec.buildManifest(start, seal, levels);
            assertEquals(
                    SourceArtifactPublicationCodec.parseManifest(testCase.get("manifest")),
                    manifest,
                    name(testCase));
            assertEquals(
                    manifest,
                    SourceArtifactPublicationCodec.validatePublication(start, seal, manifest),
                    name(testCase));
            assertExact(testCase, "manifest", SourceArtifactPublicationCodec.toJson(manifest));

            var reference = SourceArtifactPublicationCodec.buildArtifactRef(start, manifest);
            assertEquals(
                    SourceArtifactPublicationCodec.parseArtifactRef(testCase.get("artifactRef")),
                    reference,
                    name(testCase));
            assertEquals(
                    reference,
                    SourceArtifactPublicationCodec.validateReference(start, manifest, reference),
                    name(testCase));
            assertExact(testCase, "artifactRef", SourceArtifactPublicationCodec.toJson(reference));

            var sealed = SourceArtifactPublicationCodec.parseSealed(testCase.get("sealed"));
            assertEquals(
                    sealed,
                    SourceArtifactPublicationCodec.validateSealResponse(
                            start, accepted, seal, manifest, sealed),
                    name(testCase));
            assertExact(testCase, "sealed", SourceArtifactPublicationCodec.toJson(sealed));

            var status = SourceArtifactStatusCodec.parse(testCase.get("status"));
            assertEquals(
                    SourceArtifactCodec.startRequestDigest(start),
                    status.startRequestDigest(),
                    name(testCase));
            if (status.phase() == SourceArtifactStatusCodec.Phase.SEALED) {
                assertEquals(
                        status,
                        SourceArtifactStatusCodec.validateProjection(
                                start,
                                accepted,
                                status,
                                java.util.Optional.of(seal),
                                java.util.Optional.of(sealed)),
                        name(testCase));
            } else {
                assertEquals(
                        status,
                        SourceArtifactStatusCodec.validateProjection(
                                start,
                                accepted,
                                status,
                                java.util.Optional.empty(),
                                java.util.Optional.empty()),
                        name(testCase));
            }
            assertExact(testCase, "status", SourceArtifactStatusCodec.toJson(status));
        }
    }

    @Test
    void validatedLevelsAreSortedImmutableAndUnique() throws IOException {
        JsonObject testCase = publicationFixture().getAsJsonArray("cases").get(0).getAsJsonObject();
        var start = startRequest(testCase);
        var seal = SourceArtifactTransferCodec.parseSealRequest(testCase.get("sealRequest"));
        List<SourceArtifactPublicationCodec.ValidatedSourceLevel> input =
                parseLevels(testCase.getAsJsonArray("validatedLevels"));

        var manifest = SourceArtifactPublicationCodec.buildManifest(start, seal, input);

        assertEquals(
                List.of(3, 0, 2, 1),
                input.stream()
                        .map(SourceArtifactPublicationCodec.ValidatedSourceLevel::resolutionLevel)
                        .toList());
        assertEquals(
                List.of(0, 1, 2, 3),
                manifest.validatedLevels().stream()
                        .map(SourceArtifactPublicationCodec.ValidatedSourceLevel::resolutionLevel)
                        .toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.validatedLevels().add(manifest.validatedLevels().getFirst()));

        List<SourceArtifactPublicationCodec.ValidatedSourceLevel> duplicate =
                new ArrayList<>(input);
        duplicate.add(input.getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactPublicationCodec.buildManifest(start, seal, duplicate));
    }

    @Test
    void manifestRejectsMissingRequiredOrUnrelatedLevels() throws IOException {
        JsonArray cases = publicationFixture().getAsJsonArray("cases");
        JsonObject original = cases.get(0).getAsJsonObject();
        var originalStart = startRequest(original);
        var originalSeal =
                SourceArtifactTransferCodec.parseSealRequest(original.get("sealRequest"));
        List<SourceArtifactPublicationCodec.ValidatedSourceLevel> missing =
                parseLevels(original.getAsJsonArray("validatedLevels"));
        missing.remove(2);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactPublicationCodec.buildManifest(
                                originalStart, originalSeal, missing));

        List<SourceArtifactPublicationCodec.ValidatedSourceLevel> wrongSeries =
                parseLevels(original.getAsJsonArray("validatedLevels"));
        var level = wrongSeries.getFirst();
        wrongSeries.set(
                0,
                new SourceArtifactPublicationCodec.ValidatedSourceLevel(
                        3, level.resolutionLevel(), level.width(), level.height()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactPublicationCodec.buildManifest(
                                originalStart, originalSeal, wrongSeries));

        JsonObject derived = cases.get(1).getAsJsonObject();
        var derivedStart = startRequest(derived);
        var derivedSeal = SourceArtifactTransferCodec.parseSealRequest(derived.get("sealRequest"));
        List<SourceArtifactPublicationCodec.ValidatedSourceLevel> extra =
                parseLevels(derived.getAsJsonArray("validatedLevels"));
        extra.add(new SourceArtifactPublicationCodec.ValidatedSourceLevel(0, 1, 2048, 1536));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactPublicationCodec.buildManifest(
                                derivedStart, derivedSeal, extra));
    }

    @Test
    void crossRecordIdentityMutationsFailClosed() throws IOException {
        JsonObject testCase = publicationFixture().getAsJsonArray("cases").get(0).getAsJsonObject();
        JsonObject startCase =
                startCase(startFixture(), testCase.get("startCaseName").getAsString());
        var start = SourceArtifactCodec.parseStartRequest(startCase.get("value"));
        var accepted = SourceArtifactCodec.parseAccepted(startCase.get("accepted"));
        var commit = SourceArtifactTransferCodec.parseChunkCommit(testCase.get("chunkCommit"));

        JsonObject wrongChunk = testCase.getAsJsonObject("chunkAccepted").deepCopy();
        wrongChunk.getAsJsonObject("chunk").addProperty("sha256", "f".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactTransferCodec.validateChunkAcceptance(
                                start,
                                commit,
                                SourceArtifactTransferCodec.parseChunkAccepted(wrongChunk)));

        JsonObject wrongManifest = testCase.getAsJsonObject("manifest").deepCopy();
        wrongManifest.addProperty("total_bytes", wrongManifest.get("total_bytes").getAsLong() + 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactPublicationCodec.parseManifest(wrongManifest));

        var manifest = SourceArtifactPublicationCodec.parseManifest(testCase.get("manifest"));
        JsonObject wrongReference = testCase.getAsJsonObject("artifactRef").deepCopy();
        wrongReference.addProperty("start_request_digest", "f".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactPublicationCodec.validateReference(
                                start,
                                manifest,
                                SourceArtifactPublicationCodec.parseArtifactRef(wrongReference)));

        var seal = SourceArtifactTransferCodec.parseSealRequest(testCase.get("sealRequest"));
        JsonObject wrongSealed = testCase.getAsJsonObject("sealed").deepCopy();
        wrongSealed.addProperty("absolute_expires_at", "2026-08-13T12:34:56.788Z");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactPublicationCodec.validateSealResponse(
                                start,
                                accepted,
                                seal,
                                manifest,
                                SourceArtifactPublicationCodec.parseSealed(wrongSealed)));
    }

    @Test
    void unknownFieldsAndNoncanonicalScalarsFailClosed() throws IOException {
        JsonObject testCase = publicationFixture().getAsJsonArray("cases").get(0).getAsJsonObject();

        JsonObject chunk = testCase.getAsJsonObject("chunkAccepted").deepCopy();
        chunk.addProperty("extra", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactTransferCodec.parseChunkAccepted(chunk));

        JsonObject seal = new JsonObject();
        seal.addProperty(
                "bytes_digest",
                testCase.getAsJsonObject("sealRequest")
                        .get("bytes_digest")
                        .getAsString()
                        .toUpperCase());
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactTransferCodec.parseSealRequest(seal));

        JsonObject manifest = testCase.getAsJsonObject("manifest").deepCopy();
        manifest.addProperty("schema_version", 1.0);
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactPublicationCodec.parseManifest(manifest));
    }

    @Test
    void sealedStatusIsAuthoritativeAndBoundToItsStartRequest() throws IOException {
        JsonObject testCase = publicationFixture().getAsJsonArray("cases").get(1).getAsJsonObject();
        JsonObject startCase =
                startCase(startFixture(), testCase.get("startCaseName").getAsString());
        var start = SourceArtifactCodec.parseStartRequest(startCase.get("value"));
        var accepted = SourceArtifactCodec.parseAccepted(startCase.get("accepted"));
        var seal = SourceArtifactTransferCodec.parseSealRequest(testCase.get("sealRequest"));
        var status = SourceArtifactStatusCodec.parse(testCase.get("status"));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactStatusCodec.validateProjection(
                                start,
                                accepted,
                                status,
                                java.util.Optional.empty(),
                                java.util.Optional.empty()));
        assertEquals(
                status,
                SourceArtifactStatusCodec.validateProjection(
                        start,
                        accepted,
                        status,
                        java.util.Optional.of(seal),
                        java.util.Optional.empty()));

        assertSealedStatusRefMutationFails(
                testCase, start, accepted, "artifact_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertSealedStatusRefMutationFails(
                testCase,
                start,
                accepted,
                "creation_action_id",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        assertSealedStatusRefMutationFails(
                testCase, start, accepted, "start_request_digest", "f".repeat(64));
        assertSealedStatusRefMutationFails(testCase, start, accepted, "container", "TIFF_FAMILY");

        JsonObject wrongDeadline = testCase.getAsJsonObject("status").deepCopy();
        wrongDeadline.addProperty("absolute_expires_at", "2026-08-15T23:59:59.998Z");
        var wrongDeadlineStatus = SourceArtifactStatusCodec.parse(wrongDeadline);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactStatusCodec.validateProjection(
                                start,
                                accepted,
                                wrongDeadlineStatus,
                                java.util.Optional.of(seal),
                                java.util.Optional.empty()));

        JsonObject wrongStatusDigest = testCase.getAsJsonObject("status").deepCopy();
        wrongStatusDigest
                .getAsJsonObject("sealed_artifact")
                .addProperty("bytes_digest", "f".repeat(64));
        var parsedWrongStatusDigest = SourceArtifactStatusCodec.parse(wrongStatusDigest);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactStatusCodec.validateProjection(
                                start,
                                accepted,
                                parsedWrongStatusDigest,
                                java.util.Optional.of(seal),
                                java.util.Optional.empty()));

        for (String field : List.of("bytes_digest", "manifest_digest")) {
            JsonObject wrongSeal = testCase.getAsJsonObject("sealed").deepCopy();
            wrongSeal.getAsJsonObject("artifact").addProperty(field, "f".repeat(64));
            var parsedWrongSeal = SourceArtifactPublicationCodec.parseSealed(wrongSeal);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            SourceArtifactStatusCodec.validateProjection(
                                    start,
                                    accepted,
                                    status,
                                    java.util.Optional.of(seal),
                                    java.util.Optional.of(parsedWrongSeal)),
                    field);
        }
    }

    @Test
    void statusPhasePayloadsAndArtifactReferencesFailClosed() throws IOException {
        JsonArray cases = publicationFixture().getAsJsonArray("cases");
        JsonObject uploading = cases.get(0).getAsJsonObject();
        JsonObject sealed = cases.get(1).getAsJsonObject();

        JsonObject wrongPhase = uploading.getAsJsonObject("status").deepCopy();
        wrongPhase.add("sealed_artifact", uploading.getAsJsonObject("sealed").get("artifact"));
        assertThrows(
                IllegalArgumentException.class, () -> SourceArtifactStatusCodec.parse(wrongPhase));

        JsonObject missingSealed = sealed.getAsJsonObject("status").deepCopy();
        missingSealed.remove("sealed_artifact");
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactStatusCodec.parse(missingSealed));

        JsonObject duplicateChunks = uploading.getAsJsonObject("status").deepCopy();
        JsonArray duplicates = new JsonArray();
        duplicates.add(0);
        duplicates.add(0);
        duplicateChunks.add("missing_chunks", duplicates);
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactStatusCodec.parse(duplicateChunks));

        JsonObject legacyRequestArtifact = uploading.getAsJsonObject("status").deepCopy();
        legacyRequestArtifact.add("start_request_artifact", new JsonObject());
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactStatusCodec.parse(legacyRequestArtifact));

        JsonObject wrongDigest = uploading.getAsJsonObject("status").deepCopy();
        wrongDigest.addProperty("start_request_digest", "F".repeat(64));
        assertThrows(
                IllegalArgumentException.class, () -> SourceArtifactStatusCodec.parse(wrongDigest));

        var start = startRequest(uploading);
        JsonObject startCase =
                startCase(startFixture(), uploading.get("startCaseName").getAsString());
        var accepted = SourceArtifactCodec.parseAccepted(startCase.get("accepted"));
        JsonObject wrongDeadline = uploading.getAsJsonObject("status").deepCopy();
        wrongDeadline.addProperty("upload_idle_expires_at", "2026-08-14T00:00:00.000Z");
        var status = SourceArtifactStatusCodec.parse(wrongDeadline);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactStatusCodec.validateProjection(
                                start,
                                accepted,
                                status,
                                java.util.Optional.empty(),
                                java.util.Optional.empty()));
    }

    private static void assertSealedStatusRefMutationFails(
            JsonObject testCase,
            SourceArtifactCodec.SourceArtifactStartRequest start,
            SourceArtifactCodec.SourceArtifactAccepted accepted,
            String field,
            String replacement) {
        JsonObject wrong = testCase.getAsJsonObject("status").deepCopy();
        wrong.getAsJsonObject("sealed_artifact").addProperty(field, replacement);
        var parsed = SourceArtifactStatusCodec.parse(wrong);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactStatusCodec.validateProjection(
                                start,
                                accepted,
                                parsed,
                                java.util.Optional.of(
                                        SourceArtifactTransferCodec.parseSealRequest(
                                                testCase.get("sealRequest"))),
                                java.util.Optional.empty()),
                field);
    }

    private static List<SourceArtifactPublicationCodec.ValidatedSourceLevel> parseLevels(
            JsonArray values) {
        List<SourceArtifactPublicationCodec.ValidatedSourceLevel> levels = new ArrayList<>();
        for (JsonElement value : values) {
            levels.add(SourceArtifactPublicationCodec.parseValidatedLevel(value));
        }
        return levels;
    }

    private static SourceArtifactCodec.SourceArtifactStartRequest startRequest(JsonObject item)
            throws IOException {
        JsonObject testCase = startCase(startFixture(), item.get("startCaseName").getAsString());
        return SourceArtifactCodec.parseStartRequest(testCase.get("value"));
    }

    private static JsonObject startCase(JsonObject fixture, String name) {
        for (JsonElement item : fixture.getAsJsonArray("startCases")) {
            JsonObject testCase = item.getAsJsonObject();
            if (name.equals(testCase.get("name").getAsString())) {
                return testCase;
            }
        }
        throw new IllegalArgumentException("unknown fixture case: " + name);
    }

    private static void assertExact(JsonObject testCase, String field, JsonObject value) {
        byte[] encoded = CanonicalCodec.canonicalBytes(value);
        assertEquals(
                testCase.get(field + "Canonical").getAsString(),
                new String(encoded, StandardCharsets.UTF_8),
                name(testCase));
        assertEquals(
                testCase.get(field + "Sha256").getAsString(),
                CanonicalCodec.sha256(encoded),
                name(testCase));
    }

    private static String name(JsonObject testCase) {
        return testCase.get("name").getAsString();
    }

    private static JsonObject publicationFixture() throws IOException {
        return fixture("/contracts/source-artifact-publication-v2.json");
    }

    private static JsonObject startFixture() throws IOException {
        return fixture("/contracts/source-artifacts-v2.json");
    }

    private static JsonObject fixture(String resource) throws IOException {
        try (InputStream stream =
                SourceArtifactPublicationCodecTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing contract fixture: " + resource);
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}

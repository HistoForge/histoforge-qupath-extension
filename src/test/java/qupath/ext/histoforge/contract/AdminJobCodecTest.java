package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Cross-language exact-byte and phase-semantics tests for typed admin jobs. */
class AdminJobCodecTest {

    @Test
    void sharedStatusesMatchExactCanonicalBytesAndDigests() throws IOException {
        JsonObject fixture = fixture();
        JsonObject acceptedCase = fixture.getAsJsonObject("accepted");
        AdminJobCodec.AdminJobAccepted accepted =
                AdminJobCodec.parseAccepted(acceptedCase.get("value"));
        assertCanonical(acceptedCase, AdminJobCodec.toJson(accepted));

        for (var item : fixture.getAsJsonArray("statusCases")) {
            JsonObject testCase = item.getAsJsonObject();
            AdminJobCodec.AdminJobStatus status = AdminJobCodec.parseStatus(testCase.get("value"));
            JsonObject encoded = AdminJobCodec.toJson(status);
            assertCanonical(testCase, encoded);
            assertEquals(status, AdminJobCodec.parseStatus(encoded));
        }

        JsonObject pageCase = fixture.getAsJsonObject("page");
        var statuses = new ArrayList<AdminJobCodec.AdminJobStatus>();
        for (var name : pageCase.getAsJsonArray("statusNames")) {
            statuses.add(AdminJobCodec.parseStatus(status(fixture, name.getAsString())));
        }
        var page =
                new AdminJobCodec.AdminJobPage(
                        statuses, Optional.of(pageCase.get("nextCursor").getAsString()));
        assertCanonical(pageCase, AdminJobCodec.toJson(page));
        assertEquals(page, AdminJobCodec.parsePage(AdminJobCodec.toJson(page)));
    }

    @Test
    void phaseAndIdentityMismatchesFailClosed() throws IOException {
        JsonObject fixture = fixture();
        JsonObject queued = status(fixture, "queued-install");
        queued.add("result", new JsonObject());
        assertThrows(IllegalArgumentException.class, () -> AdminJobCodec.parseStatus(queued));

        JsonObject install = status(fixture, "succeeded-install");
        install.addProperty("phase", "PARTIAL");
        assertThrows(IllegalArgumentException.class, () -> AdminJobCodec.parseStatus(install));

        JsonObject published = status(fixture, "succeeded-publish");
        published.getAsJsonObject("result").addProperty("target_repository", "owner/other");
        assertThrows(IllegalArgumentException.class, () -> AdminJobCodec.parseStatus(published));

        JsonObject partial = status(fixture, "partial-publish");
        JsonObject notRequested = new JsonObject();
        notRequested.addProperty("type", "not-requested");
        partial.getAsJsonObject("result").add("local_sync", notRequested);
        assertThrows(IllegalArgumentException.class, () -> AdminJobCodec.parseStatus(partial));

        JsonObject failed = status(fixture, "failed-install");
        failed.remove("failure");
        assertThrows(IllegalArgumentException.class, () -> AdminJobCodec.parseStatus(failed));

        JsonObject unresolvedInstall = status(fixture, "succeeded-install");
        unresolvedInstall
                .getAsJsonObject("result")
                .getAsJsonObject("installed_module")
                .remove("resolved_commit_sha");
        assertThrows(
                IllegalArgumentException.class, () -> AdminJobCodec.parseStatus(unresolvedInstall));

        JsonObject wrongLocalCommit = status(fixture, "succeeded-publish-with-local-sync");
        wrongLocalCommit
                .getAsJsonObject("result")
                .getAsJsonObject("local_sync")
                .getAsJsonObject("installed_module")
                .addProperty("resolved_commit_sha", "e".repeat(40));
        assertThrows(
                IllegalArgumentException.class, () -> AdminJobCodec.parseStatus(wrongLocalCommit));
    }

    private static void assertCanonical(JsonObject testCase, JsonObject value) {
        byte[] encoded = CanonicalCodec.canonicalBytes(value);
        assertEquals(
                testCase.get("canonical").getAsString(),
                new String(encoded, StandardCharsets.UTF_8));
        assertEquals(testCase.get("sha256").getAsString(), CanonicalCodec.sha256(encoded));
    }

    private static JsonObject status(JsonObject fixture, String name) {
        for (var item : fixture.getAsJsonArray("statusCases")) {
            JsonObject testCase = item.getAsJsonObject();
            if (name.equals(testCase.get("name").getAsString())) {
                return testCase.getAsJsonObject("value").deepCopy();
            }
        }
        throw new IllegalArgumentException("missing status fixture: " + name);
    }

    private static JsonObject fixture() throws IOException {
        try (var stream =
                AdminJobCodecTest.class.getResourceAsStream("/contracts/admin-jobs-v1.json")) {
            if (stream == null) {
                throw new IOException("missing admin-jobs-v1.json");
            }
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}

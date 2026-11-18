package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Runs the shared exact module-catalog, availability, and activation fixture. */
class ModuleCatalogCodecTest {

    private static final long MAX_TRAINING_TEXT_BYTES = 4096;

    @Test
    void precisionOverridesAreRequiredClosedAndDistinctFromNative() throws IOException {
        var page = fixture().getAsJsonObject("page");
        var models =
                page.getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonObject("models");
        var cells = models.getAsJsonObject("cells");
        cells.remove("precision_overrides");
        var missing =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));
        assertTrue(missing.getMessage().contains("Update cloud backend in Settings"));

        var nativeBuild =
                cells.getAsJsonObject("runner_availability")
                        .getAsJsonObject("capabilities")
                        .getAsJsonObject("runner_build");
        var bf16 = new JsonObject();
        var build = nativeBuild.deepCopy();
        build.addProperty("manifest_digest", "b".repeat(64));
        bf16.add("runner_build", build);
        bf16.add("gpus", CanonicalCodec.parseJson("[\"A100\",\"L40S\"]"));
        var overrides = new JsonObject();
        overrides.add("bf16", bf16);
        cells.add("precision_overrides", overrides);
        var parsed = ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES);
        assertEquals(page, ModuleCatalogCodec.toJson(parsed, MAX_TRAINING_TEXT_BYTES));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        parsed.modules()
                                .getFirst()
                                .models()
                                .get("cells")
                                .precisionOverrides()
                                .clear());

        for (String invalid : new String[] {"null", "[]", "{\"fp16\":{}}", "{\"default\":{}}"}) {
            cells.add("precision_overrides", CanonicalCodec.parseJson(invalid));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES),
                    invalid);
        }
        cells.add("precision_overrides", overrides);
        for (String invalid :
                new String[] {"[]", "[\"\"]", "[\"  \"]", "[\"A100\",\"A100\"]", "[42]", "null"}) {
            bf16.add("gpus", CanonicalCodec.parseJson(invalid));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES),
                    invalid);
        }
        bf16.add("gpus", CanonicalCodec.parseJson("[\"A100\"]"));
        bf16.addProperty("unexpected", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));
        bf16.remove("unexpected");
        bf16.add("runner_build", nativeBuild);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));
        bf16.add("runner_build", build);
        models.getAsJsonObject("future").add("precision_overrides", overrides);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));
    }

    @Test
    void savedReadmeHasExactSharedBytesAndDoesNotChangeInferenceIdentity() throws IOException {
        var shared = readFixture("module-readme-v1.json");
        var original =
                ModuleCatalogCodec.parse(fixture().get("page"), MAX_TRAINING_TEXT_BYTES)
                        .modules()
                        .getFirst();
        var page = fixture().getAsJsonObject("page");
        var module = page.getAsJsonArray("modules").get(0).getAsJsonObject();
        module.add("readme_markdown", shared.get("readme_markdown"));
        var parsed = ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES);
        var saved = parsed.modules().getFirst();
        assertEquals(
                shared.get("readme_markdown").getAsString(), saved.readmeMarkdown().orElseThrow());
        assertEquals(original.backendIdentity("cells"), saved.backendIdentity("cells"));
        assertEquals(page, ModuleCatalogCodec.toJson(parsed, MAX_TRAINING_TEXT_BYTES));
        var field = new JsonObject();
        field.addProperty("readme_markdown", saved.readmeMarkdown().orElseThrow());
        assertEquals(
                shared.get("canonical_field").getAsString(),
                new String(CanonicalCodec.canonicalBytes(field), StandardCharsets.UTF_8));
        for (var invalid : shared.getAsJsonArray("invalid_values")) {
            module.add("readme_markdown", invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));
        }
        for (var invalid :
                new String[] {"é".repeat(ModuleCatalogTypes.MAX_README_BYTES), "\ud800"}) {
            module.addProperty("readme_markdown", invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));
        }
        module.addProperty("readme_markdown", "");
        ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES);
        module.getAsJsonObject("identity").remove("resolved_commit_sha");
        module.getAsJsonObject("identity")
                .addProperty("module_ref", "workspace/run-44444444-4444-4444-8444-444444444444");
        page.remove("next_cursor");
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));
    }

    @Test
    void sharedFixtureHasExactBytesDigestAndActivationIdentity() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        var page = ModuleCatalogCodec.parse(fixture.get("page"), MAX_TRAINING_TEXT_BYTES);
        JsonObject encodedValue = ModuleCatalogCodec.toJson(page, MAX_TRAINING_TEXT_BYTES);
        byte[] encoded = CanonicalCodec.canonicalBytes(encodedValue);
        assertEquals(
                fixture.get("canonical").getAsString(),
                new String(encoded, StandardCharsets.UTF_8));
        assertEquals(fixture.get("sha256").getAsString(), CanonicalCodec.sha256(encoded));

        var module = page.modules().getFirst();
        assertEquals("owner/catalog", module.identity().moduleRef().toString());
        assertEquals("Catalog Fixture", module.typedRecipe().name());
        assertEquals(2, module.typedRecipe().models().size());
        assertEquals("b3duZXIvY2F0YWxvZw", page.nextCursor().orElseThrow());

        var cells = module.models().get("cells");
        assertInstanceOf(ModuleCatalogTypes.Available.class, cells.runnerAvailability());
        var backend = module.backendIdentity("cells");
        assertEquals(module.identity(), backend.module());
        assertEquals("cells", backend.modelId());
        assertEquals(cells.modelConfigDigest(), backend.modelConfigDigest());
        assertEquals(cells.artifactDigests(), backend.artifactDigests());
        assertEquals(
                ((ModuleCatalogTypes.Available) cells.runnerAvailability())
                        .capabilities()
                        .runnerBuild(),
                backend.runnerBuild());

        var future = module.models().get("future");
        var unavailable =
                assertInstanceOf(ModuleCatalogTypes.Unavailable.class, future.runnerAvailability());
        assertEquals(
                ModuleCatalogTypes.RunnerUnavailableCode.UNKNOWN_ARCHITECTURE, unavailable.code());
        assertThrows(IllegalStateException.class, () -> module.backendIdentity("future"));
    }

    @Test
    void coreHaloProfileMustMatchTheRecipeEvenWithAValidProfileDigest() throws IOException {
        JsonObject page = fixture().getAsJsonObject("page");
        JsonObject module = page.getAsJsonArray("modules").get(0).getAsJsonObject();
        JsonObject model = module.getAsJsonObject("models").getAsJsonObject("cells");
        JsonObject profile =
                CanonicalCodec.parseJson(
                                "{\"type\":\"core-halo\",\"core_width_px\":8192,\"core_height_px\":8192,\"halo_px\":256}")
                        .getAsJsonObject();
        profile.addProperty("validation_evidence_digest", "e".repeat(64));
        profile.addProperty("profile_digest", CanonicalCodec.canonicalDigest(profile));
        model.add("large_region_profile", profile);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));

        JsonObject recipe = module.getAsJsonObject("recipe");
        JsonObject recipeModel = recipe.getAsJsonObject("models").getAsJsonObject("cells");
        recipeModel
                .getAsJsonObject("input")
                .add(
                        "largeRegion",
                        CanonicalCodec.parseJson(
                                "{\"coreWidthModelPx\":8192,\"coreHeightModelPx\":8192,\"haloPerSideModelPx\":256}"));
        module.getAsJsonObject("identity")
                .addProperty("recipe_digest", CanonicalCodec.canonicalDigest(recipe));
        model.addProperty("model_config_digest", CanonicalCodec.canonicalDigest(recipeModel));
        ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES);
        for (String field : new String[] {"core_width_px", "core_height_px", "halo_px"}) {
            JsonObject changed = profile.deepCopy();
            changed.addProperty(field, changed.get(field).getAsInt() + 1);
            changed.remove("profile_digest");
            changed.addProperty("profile_digest", CanonicalCodec.canonicalDigest(changed));
            model.add("large_region_profile", changed);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModuleCatalogCodec.parse(page, MAX_TRAINING_TEXT_BYTES));
        }
    }

    @Test
    void contentAndCapabilityMutationsFailClosed() throws IOException {
        JsonObject wrongModelDigest = fixture().getAsJsonObject("page").deepCopy();
        wrongModelDigest
                .getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("models")
                .getAsJsonObject("cells")
                .addProperty("model_config_digest", "f".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(wrongModelDigest, MAX_TRAINING_TEXT_BYTES));

        JsonObject missingCommit = fixture().getAsJsonObject("page").deepCopy();
        missingCommit
                .getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("identity")
                .remove("resolved_commit_sha");
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(missingCommit, MAX_TRAINING_TEXT_BYTES));

        JsonObject workspaceResult = fixture().getAsJsonObject("page").deepCopy();
        workspaceResult.remove("next_cursor");
        JsonObject workspaceIdentity =
                workspaceResult
                        .getAsJsonArray("modules")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("identity");
        workspaceIdentity.remove("resolved_commit_sha");
        workspaceIdentity.addProperty(
                "module_ref", "workspace/run-44444444-4444-4444-8444-444444444444");
        ModuleCatalogCodec.parse(workspaceResult, MAX_TRAINING_TEXT_BYTES);

        JsonObject wrongArtifactRole = fixture().getAsJsonObject("page").deepCopy();
        JsonObject artifacts =
                wrongArtifactRole
                        .getAsJsonArray("modules")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("models")
                        .getAsJsonObject("cells")
                        .getAsJsonObject("artifact_digests");
        artifacts.add("not-a-recipe-role", artifacts.remove("weights"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(wrongArtifactRole, MAX_TRAINING_TEXT_BYTES));

        JsonObject missingDurableLimits = fixture().getAsJsonObject("page").deepCopy();
        var capabilities =
                missingDurableLimits
                        .getAsJsonArray("modules")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("models")
                        .getAsJsonObject("cells")
                        .getAsJsonObject("runner_availability")
                        .getAsJsonObject("capabilities");
        capabilities.remove("durable_run");
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(missingDurableLimits, MAX_TRAINING_TEXT_BYTES));

        JsonObject wrongCursor = fixture().getAsJsonObject("page").deepCopy();
        wrongCursor.addProperty("next_cursor", "b3duZXIvb3RoZXI");
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleCatalogCodec.parse(wrongCursor, MAX_TRAINING_TEXT_BYTES));
    }

    @Test
    void returnedMapsAndRecipeTreeAreDetached() throws IOException {
        var module =
                ModuleCatalogCodec.parse(fixture().get("page"), MAX_TRAINING_TEXT_BYTES)
                        .modules()
                        .getFirst();
        assertThrows(
                UnsupportedOperationException.class,
                () -> module.models().put("other", module.models().get("cells")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> module.scripts().put("other.groovy", "return []"));

        JsonObject exposed = module.recipe();
        exposed.addProperty("name", "Changed");
        assertEquals("Catalog Fixture", module.recipe().get("name").getAsString());
    }

    private static JsonObject fixture() throws IOException {
        return readFixture("module-catalog-v1.json");
    }

    private static JsonObject readFixture(String name) throws IOException {
        try (InputStream input =
                ModuleCatalogCodecTest.class.getResourceAsStream("/contracts/" + name)) {
            if (input == null) {
                throw new IOException("missing module-catalog-v1.json");
            }
            return CanonicalCodec.parseJson(input.readAllBytes()).getAsJsonObject();
        }
    }
}

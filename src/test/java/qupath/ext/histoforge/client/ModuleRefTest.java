package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies canonical module identity separately from install-field convenience parsing. */
class ModuleRefTest {

    @Test
    void canonicalFormIsExactOwnerNameOnly() {
        assertEquals(
                Optional.of(new ModuleRef("histoforge", "leydig")),
                ModuleRef.parse("histoforge/leydig"));
        assertTrue(ModuleRef.parse(" histoforge/leydig ").isEmpty());
        assertTrue(ModuleRef.parse("histoforge/leydig@main").isEmpty());
        assertTrue(ModuleRef.parse("https://huggingface.co/histoforge/leydig").isEmpty());
        assertEquals("histoforge/leydig", new ModuleRef("histoforge", "leydig").toString());
        assertEquals("histoforge/leydig", new ModuleRef("histoforge", "leydig").repo());
    }

    @Test
    void canonicalFormEnforcesSegmentAndCombinedBoundaries() {
        String owner = "a".repeat(47);
        String name = "b".repeat(48);
        assertEquals(owner + "/" + name, new ModuleRef(owner, name).toString());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModuleRef("a".repeat(48), "b".repeat(48)));
        assertThrows(IllegalArgumentException.class, () -> new ModuleRef("é", "module"));
        assertThrows(IllegalArgumentException.class, () -> new ModuleRef(".", "module"));
    }

    @Test
    void installSourcePreservesRefRevisionAndUrlConvenience() {
        assertSource("histoforge/leydig", "histoforge/leydig", null);
        assertSource("  histoforge/leydig@main  ", "histoforge/leydig", "main");
        assertSource("https://huggingface.co/histoforge/leydig", "histoforge/leydig", null);
        assertSource(
                "https://huggingface.co/histoforge/leydig/commit/2bfa3f0",
                "histoforge/leydig",
                "2bfa3f0");
        assertSource("huggingface.co/histoforge/leydig", "histoforge/leydig", null);
        assertSource(
                "https://huggingface.co/histoforge/leydig/tree/dev", "histoforge/leydig", "dev");
    }

    @Test
    void installSourceRejectsMalformedOrDifferentHosts() {
        assertTrue(ModuleRef.parseHuggingFaceSource(null).isEmpty());
        assertTrue(ModuleRef.parseHuggingFaceSource("leydig").isEmpty());
        assertTrue(ModuleRef.parseHuggingFaceSource("histoforge/leydig@").isEmpty());
        assertTrue(ModuleRef.parseHuggingFaceSource("histoforge/leydig@main@other").isEmpty());
        assertTrue(
                ModuleRef.parseHuggingFaceSource(
                                "https://huggingface.co.evil.example/histoforge/leydig")
                        .isEmpty());
        assertTrue(
                ModuleRef.parseHuggingFaceSource(
                                "https://huggingface.co/histoforge/leydig/blob/main/recipe.json")
                        .isEmpty());
        assertTrue(
                ModuleRef.parseHuggingFaceSource(
                                "https://huggingface.co/histoforge/leydig/tree/main/scripts")
                        .isEmpty());
    }

    private static void assertSource(String input, String module, String revision) {
        ModuleRef.HuggingFaceSource source = ModuleRef.parseHuggingFaceSource(input).orElseThrow();
        assertEquals(module, source.moduleRef().toString());
        assertEquals(Optional.ofNullable(revision), source.revision());
    }
}

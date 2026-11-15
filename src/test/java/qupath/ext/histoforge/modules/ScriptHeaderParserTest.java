package qupath.ext.histoforge.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;

/**
 * Verifies {@link ScriptHeaderParser} — the required metrics header and the optional classes header
 * — how a module's metrics script is looked up ({@link ModuleContract#METRICS_SCRIPT_NAME} only),
 * and the classes union into {@code InstalledModule.classifications()}.
 */
class ScriptHeaderParserTest {

    // ── @histoforge-metrics (required) ───────────────────────────────

    @Test
    void metrics_readsAllFields() {
        String script =
                """
                /* @histoforge-metrics
                [
                  {"name": "count", "label": "Tubule Count", "type": "scalar",
                   "decimals": 0, "aggregation": "sum"},
                  {"name": "diameter", "label": "Tubule Diameter", "type": "distribution",
                   "unit": "µm", "decimals": 1, "aggregation": "pool"}
                ]
                */
                return [count: 1]
                """;

        List<MetricInfo> metrics = ScriptHeaderParser.parseMetrics(script);

        assertEquals(2, metrics.size());
        assertEquals("count", metrics.get(0).name());
        assertEquals("Tubule Count", metrics.get(0).label());
        assertEquals("scalar", metrics.get(0).type());
        assertEquals("sum", metrics.get(0).aggregation());

        assertEquals("diameter", metrics.get(1).name());
        assertEquals("distribution", metrics.get(1).type());
        assertEquals("µm", metrics.get(1).unit());
        assertEquals(1, metrics.get(1).decimals());
    }

    @Test
    void metrics_missingHeader_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScriptHeaderParser.parseMetrics("// no header here\nreturn [:]"));
    }

    @Test
    void metrics_emptyArray_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScriptHeaderParser.parseMetrics("/* @histoforge-metrics\n[]\n*/"));
    }

    @Test
    void metrics_malformedJson_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScriptHeaderParser.parseMetrics("/* @histoforge-metrics\n[not json]\n*/"));
    }

    @Test
    void metrics_rejectsUnknownFieldsAndIncompatibleAggregation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseMetrics(
                                """
                                /* @histoforge-metrics
                                [{"name":"x","label":"X","type":"scalar","decimals":0,
                                  "aggregation":"sum","unknown":true}]
                                */
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseMetrics(
                                """
                                /* @histoforge-metrics
                                [{"name":"x","label":"X","type":"scalar","decimals":0,
                                  "aggregation":"sum","nullable":true}]
                                */
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseMetrics(
                                """
                                /* @histoforge-metrics
                                [{"name":"x","label":"X","type":"scalar",
                                  "decimals":2147483648,"aggregation":"sum"}]
                                */
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseMetrics(
                                """
                                /* @histoforge-metrics
                                [{"name":"x","label":"X","type":"scalar","decimals":0.0,
                                  "aggregation":"sum"}]
                                */
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseMetrics(
                                """
                                /* @histoforge-metrics
                                [{"name":"x","label":"X","type":"distribution","decimals":0,
                                  "aggregation":"mean"}]
                                */
                                """));
    }

    @Test
    void header_mustBeFirstAndUnique() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseClasses(
                                """
                                /* explanation */
                                /* @histoforge-classes ["A"] */
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseClasses(
                                """
                                /* @histoforge-classes ["A"] */
                                /* @histoforge-classes ["B"] */
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseClasses(
                                "/*\u00a0@histoforge-classes\u00a0[\"A\"]\u00a0*/"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScriptHeaderParser.parseClasses("/* @histoforge-classes [\"\u00a0\"] */"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseClasses(
                                "/* @histoforge-classes [\"Tumor:Positive\"] */"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ScriptHeaderParser.parseClasses(
                                "/* @histoforge-classes [\"" + '\uD800' + "\"] */"));
    }

    // ── metrics-script lookup (by reserved filename) ────────────────

    @Test
    void metricsScript_returnsSourceUnderReservedName() {
        String source =
                """
                /* @histoforge-metrics
                [{"name": "count", "label": "Count", "type": "scalar",
                  "decimals": 0, "aggregation": "sum"}]
                */
                return [count: 1]
                """;
        var module = moduleWithScript(ModuleContract.METRICS_SCRIPT_NAME, source);

        assertEquals(source, module.metricsScript());
        assertTrue(module.toModuleInfo().hasMetrics());
        assertEquals("count", module.toModuleInfo().metrics().get(0).name());
    }

    @Test
    void metricsScript_nullWhenModuleShipsNoMetricsFile() {
        var module =
                moduleWithScript("cross_section.groovy", "/* @histoforge-classes [\"A\"] */\n");

        assertNull(module.metricsScript());
        assertFalse(module.toModuleInfo().hasMetrics());
    }

    /** A one-script module, for the metrics-script lookup cases. */
    private static InstalledModule moduleWithScript(String filename, String source) {
        var recipe =
                new ModuleRecipe(
                        1,
                        "M",
                        null,
                        null,
                        null,
                        Map.of(),
                        List.of(new ModuleRecipe.Step(null, null, null, filename)));
        return installed("histoforge/m", recipe, Map.of(filename, source));
    }

    // ── @histoforge-classes (optional) ───────────────────────────────

    @Test
    void classes_readsClasses() {
        String script =
                "/* @histoforge-classes [\"Tubule (Cross Section)\", \"Foo\"] */\n"
                        + "def c = PathClass.fromString(\"Tubule (Cross Section)\")\n";
        assertEquals(
                List.of("Tubule (Cross Section)", "Foo"), ScriptHeaderParser.parseClasses(script));
    }

    @Test
    void classes_noHeader_empty() {
        assertTrue(ScriptHeaderParser.parseClasses("// a metrics script\nreturn [:]\n").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ScriptHeaderParser.parseClasses(null));
    }

    @Test
    void classes_malformedJson_throws() {
        // Two strings, no comma between them — invalid JSON.
        assertThrows(
                IllegalArgumentException.class,
                () -> ScriptHeaderParser.parseClasses("/* @histoforge-classes [\"a\" \"b\"] */"));
    }

    // ── classes union into the module's class vocabulary ─────────────

    @Test
    void classifications_unionsModelOutputsAndScriptHeader() {
        // A one-model module whose script step re-classes into a new class via its header.
        var output = new ModelOutput("instance_segmentation", List.of("Tubule"), List.of());
        var model =
                new ModelSpec("dinov3-adapter-hovernet", "A10G", null, null, output, null, null);
        var recipe =
                new ModuleRecipe(
                        1,
                        "Tubule",
                        null,
                        null,
                        null,
                        Map.of("tubule", model),
                        List.of(
                                new ModuleRecipe.Step("tubule", null, null, null),
                                new ModuleRecipe.Step(null, null, null, "cross_section.groovy")));
        var scripts =
                Map.of(
                        "cross_section.groovy",
                        "/* @histoforge-classes [\"Tubule (Cross Section)\"] */\n");
        var module = installed("histoforge/tubule", recipe, scripts);

        List<String> classes = module.toModuleInfo().classifications();
        assertTrue(classes.contains("Tubule"), "model output class");
        assertTrue(classes.contains("Tubule (Cross Section)"), "script @histoforge-classes class");
    }

    private static InstalledModule installed(
            String ref, ModuleRecipe recipe, Map<String, String> scripts) {
        String[] segments = ref.split("/", -1);
        InstalledModuleDescriptor descriptor = mock(InstalledModuleDescriptor.class);
        when(descriptor.identity())
                .thenReturn(
                        new ModuleContentIdentity(
                                new ModuleRef(segments[0], segments[1]),
                                Optional.empty(),
                                "1".repeat(64),
                                "2".repeat(64)));
        when(descriptor.typedRecipe()).thenReturn(recipe);
        when(descriptor.scripts()).thenReturn(scripts);
        return new InstalledModule(mock(GatewayCapabilities.class), descriptor);
    }
}

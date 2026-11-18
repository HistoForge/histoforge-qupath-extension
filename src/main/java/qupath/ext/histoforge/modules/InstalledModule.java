package qupath.ext.histoforge.modules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.client.dto.ModuleInfo;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;

/**
 * One exact module catalog item captured under one authenticated gateway deployment.
 *
 * <p>The descriptor is the sole module-content authority. This class adds only the deployment
 * capabilities captured with that descriptor and typed convenience projections for UI consumers; it
 * never reparses a reduced module shape or persists catalog state.
 */
public final class InstalledModule {

    private final GatewayCapabilities gateway;
    private final InstalledModuleDescriptor descriptor;
    private final ModuleRecipe recipe;

    public InstalledModule(GatewayCapabilities gateway, InstalledModuleDescriptor descriptor) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.recipe = descriptor.typedRecipe();
    }

    /** Exact authenticated capabilities under which this catalog descriptor was captured. */
    public GatewayCapabilities gateway() {
        return gateway;
    }

    /** Complete validated catalog descriptor used by action activation. */
    public InstalledModuleDescriptor descriptor() {
        return descriptor;
    }

    /** Canonical qualified {@code owner/name} identity. */
    public String ref() {
        return descriptor.identity().moduleRef().toString();
    }

    /** Exact module-owned installed bytes reported by the catalog. */
    public long sizeBytes() {
        return descriptor.installedModuleFileBytes();
    }

    /** Exact catalog installation timestamp. */
    public Instant installedAt() {
        return descriptor.installedAt();
    }

    /** Validated typed recipe projection retained for UI display and action selection. */
    public ModuleRecipe recipe() {
        return recipe;
    }

    /** Immutable exact script filename-to-source map from the catalog descriptor. */
    public Map<String, String> scripts() {
        return descriptor.scripts();
    }

    public String name() {
        return recipe.name();
    }

    public String description() {
        return recipe.description();
    }

    /** Exact metrics script source, or {@code null} when the recipe has none. */
    public String metricsScript() {
        return scripts().get(ModuleContract.METRICS_SCRIPT_NAME);
    }

    /** Flat display/export projection; execution always consumes {@link #descriptor()}. */
    public ModuleInfo toModuleInfo() {
        return new ModuleInfo(ref(), name(), description(), metrics(), classifications());
    }

    private List<String> classifications() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (ModuleRecipe.Step step : recipe.steps()) {
            if (step.isModelStep()) {
                ModelSpec model = recipe.models().get(step.model());
                if (model.output() != null) {
                    all.addAll(model.output().emittedClasses());
                }
            } else if (!ModuleContract.METRICS_SCRIPT_NAME.equals(step.script())) {
                all.addAll(ScriptHeaderParser.parseClasses(scripts().get(step.script())));
            }
        }
        return new ArrayList<>(all);
    }

    private List<MetricInfo> metrics() {
        String source = metricsScript();
        return source == null ? List.of() : ScriptHeaderParser.parseMetrics(source);
    }
}

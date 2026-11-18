package qupath.ext.histoforge.data;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;

/** Publication facts retained after evaluation; never retains the biological input population. */
public record MetricResultContext(
        RasterInputCodec.ImageRevisionToken sourceRevision,
        String metricInputDigest,
        BuildIdentityCodec.ClientBuildIdentity clientBuild,
        InferenceIdentityCodec.ModuleContentIdentity module,
        String metricsScriptDigest,
        MetricTypes.MetricSchema schema,
        List<ExecutionScopeCodec.TargetRegion> regions) {

    public MetricResultContext {
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        metricInputDigest = CanonicalCodec.requireSha256(metricInputDigest, "metric_input_digest");
        clientBuild = Objects.requireNonNull(clientBuild, "clientBuild");
        module = Objects.requireNonNull(module, "module");
        metricsScriptDigest =
                CanonicalCodec.requireSha256(metricsScriptDigest, "metrics_script_digest");
        schema = Objects.requireNonNull(schema, "schema");
        regions = List.copyOf(regions);
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("metric result regions must be nonempty");
        }
        Set<ExecutionScopeCodec.RegionKey> keys = new HashSet<>();
        for (ExecutionScopeCodec.TargetRegion target : regions) {
            if (!keys.add(target.key())) {
                throw new IllegalArgumentException("metric result region keys must be unique");
            }
        }
    }

    /** Preserve the already-derived input identity and canonical region order without rehashing. */
    public static MetricResultContext from(MetricTypes.MetricImageSnapshot snapshot) {
        MetricTypes.MetricInputDraft input = snapshot.metricInput();
        return new MetricResultContext(
                snapshot.sourceRevision(),
                input.metricInputDigest(),
                input.clientBuild(),
                input.module(),
                input.script().digest(),
                input.script().schema(),
                input.regions().stream().map(MetricTypes.MetricRegionSnapshot::target).toList());
    }
}

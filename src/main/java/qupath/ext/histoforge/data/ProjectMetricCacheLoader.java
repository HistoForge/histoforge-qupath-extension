package qupath.ext.histoforge.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.data.MetricImageResolver.MetricSelection;
import qupath.ext.histoforge.data.ProjectSummaryData.CachedImageMetrics;
import qupath.ext.histoforge.data.ProjectSummaryData.ImageSummary;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleActivationValidator;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;

/** Resolves Project-tab display values from exact process-local metric cache entries only. */
public final class ProjectMetricCacheLoader {

    private final Function<LocalResourceEnvelope, ClientBuildIdentity> clientBuildFactory;
    private final MetricsEngine metricsEngine;
    private final MetricImageResolver resolver;

    public ProjectMetricCacheLoader(
            Function<LocalResourceEnvelope, ClientBuildIdentity> clientBuildFactory,
            MetricsEngine metricsEngine,
            MetricImageResolver resolver) {
        this.clientBuildFactory = Objects.requireNonNull(clientBuildFactory, "clientBuildFactory");
        this.metricsEngine = Objects.requireNonNull(metricsEngine, "metricsEngine");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Attach only values whose freshly captured revision, normalized input, script, and region set
     * exactly match a complete cache entry. A miss is an ordinary empty display; a capture or
     * activation failure is reported per image and never falls back to an older value.
     */
    public ProjectSummaryData attachExactResults(
            ProjectSummaryData summary,
            List<InstalledModule> installedModules,
            LocalResourceEnvelope resourceEnvelope,
            boolean wholeSlide,
            BooleanSupplier shouldStop) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(installedModules, "installedModules");
        Objects.requireNonNull(resourceEnvelope, "resourceEnvelope");
        Objects.requireNonNull(shouldStop, "shouldStop");
        Map<String, InstalledModule> byRef = new LinkedHashMap<>();
        installedModules.forEach(module -> byRef.put(module.ref(), module));

        List<Candidate> candidates = new ArrayList<>();
        for (ImageSummary image : summary.getImageSummaries()) {
            String moduleRef = image.metadata().moduleRef();
            InstalledModule installed = byRef.get(moduleRef);
            if (installed == null
                    || installed.metricsScript() == null
                    || (!wholeSlide && image.rois().isEmpty())
                    || !metricsEngine.hasCachedResult(
                            image.entry().getID(), installed.descriptor().identity().moduleRef())) {
                continue;
            }
            candidates.add(new Candidate(image, installed));
        }
        if (candidates.isEmpty()) {
            return summary.withoutMetrics();
        }

        final ClientBuildIdentity clientBuild;
        try {
            clientBuild =
                    Objects.requireNonNull(
                            clientBuildFactory.apply(resourceEnvelope), "clientBuild");
        } catch (RuntimeException failure) {
            Map<String, String> failures = new LinkedHashMap<>();
            candidates.forEach(
                    candidate ->
                            failures.put(candidate.image().entry().getID(), describe(failure)));
            return summary.withMetrics(List.of(), failures);
        }

        List<CachedImageMetrics> metrics = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            requireRunning(shouldStop);
            ImageSummary image = candidate.image();
            InstalledModule installed = candidate.module();
            ActionSelectionCodec.RegionSelection regions = regions(image, wholeSlide);
            try {
                var snapshot =
                        ModuleActivationValidator.activate(
                                installed.gateway().deploymentId(),
                                installed.descriptor(),
                                new ActionSelectionCodec.Metrics(regions));
                var resolved =
                        resolver.resolve(
                                image.entry(),
                                clientBuild,
                                List.of(
                                        new MetricSelection(
                                                snapshot,
                                                regions,
                                                summary.scope().selectedClass())),
                                MetricImageResolver.CapturePurpose.PROJECT_CACHE,
                                false,
                                shouldStop);
                var module = resolved.modules().getFirst();
                if (module.evaluation().isPresent()) {
                    metrics.add(
                            new CachedImageMetrics(
                                    image.entry().getID(),
                                    image.metadata().imageName(),
                                    image.group(),
                                    module.context().module(),
                                    module.module().recipe().name(),
                                    module.context().schema(),
                                    module.evaluation().orElseThrow().result()));
                }
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (IOException | RuntimeException failure) {
                failures.put(image.entry().getID(), describe(failure));
            }
        }
        return summary.withMetrics(metrics, failures);
    }

    static ActionSelectionCodec.RegionSelection regions(ImageSummary image, boolean wholeSlide) {
        Objects.requireNonNull(image, "image");
        return wholeSlide
                ? new ActionSelectionCodec.SlideRegion(new ActionSelectionCodec.FullSlide())
                : new ActionSelectionCodec.RoiRegions(image.roiIds());
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("project metric cache refresh cancelled");
        }
    }

    private record Candidate(ImageSummary image, InstalledModule module) {}
}

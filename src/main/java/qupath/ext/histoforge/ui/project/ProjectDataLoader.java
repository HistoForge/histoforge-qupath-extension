package qupath.ext.histoforge.ui.project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qupath.ext.histoforge.client.dto.ModuleInfo;
import qupath.ext.histoforge.data.ProjectInferenceScope;
import qupath.ext.histoforge.data.ProjectMetricCacheLoader;
import qupath.ext.histoforge.data.ProjectSummaryData;
import qupath.ext.histoforge.data.ProjectSummaryData.ImageMetadata;
import qupath.ext.histoforge.data.ProjectSummaryData.ViewerHierarchyCapture;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;
import qupath.lib.projects.ProjectImageEntry;

/** Background owner for one immutable Project-tab hierarchy and exact metric-cache snapshot. */
final class ProjectDataLoader {

    private final ProjectMetricCacheLoader metricCacheLoader;
    private final Consumer<Runnable> uiDispatcher;

    ProjectDataLoader(ProjectMetricCacheLoader metricCacheLoader, Consumer<Runnable> uiDispatcher) {
        this.metricCacheLoader = Objects.requireNonNull(metricCacheLoader, "metricCacheLoader");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
    }

    /** Owner-thread facts captured before background project entries are opened. */
    record Request(
            List<ImageMetadata> entries,
            Optional<ViewerHierarchyCapture> viewerCapture,
            Optional<ProjectImageEntry<BufferedImage>> viewerEntry,
            List<InstalledModule> installedModules,
            LocalResourceEnvelope resourceEnvelope,
            ProjectInferenceScope scope) {
        Request {
            entries = List.copyOf(entries);
            viewerCapture = Objects.requireNonNull(viewerCapture, "viewerCapture");
            viewerEntry = Objects.requireNonNull(viewerEntry, "viewerEntry");
            installedModules = List.copyOf(installedModules);
            resourceEnvelope = Objects.requireNonNull(resourceEnvelope, "resourceEnvelope");
            scope = Objects.requireNonNull(scope, "scope");
            if (viewerCapture.isPresent() != viewerEntry.isPresent()) {
                throw new IllegalArgumentException(
                        "viewer hierarchy capture and project entry must be present together");
            }
        }
    }

    /** Complete immutable data and module display descriptors from one registry snapshot. */
    record Result(ProjectSummaryData summary, List<ModuleInfo> modules) {
        Result {
            summary = Objects.requireNonNull(summary, "summary");
            modules = List.copyOf(modules);
        }
    }

    /**
     * Run blocking project reads off the FX thread and return exactly once on the UI dispatcher.
     */
    void loadSummary(
            Request request,
            BooleanSupplier shouldStop,
            Consumer<Result> onSuccess,
            Consumer<Exception> onFailure) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(shouldStop, "shouldStop");
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                requireRunning(shouldStop);
                                ProjectSummaryData hierarchy =
                                        ProjectSummaryData.load(
                                                request.entries(),
                                                request.viewerCapture().orElse(null),
                                                request.viewerEntry().orElse(null),
                                                ignored -> requireRunning(shouldStop),
                                                request.scope());
                                List<ModuleInfo> modules =
                                        request.installedModules().stream()
                                                .map(InstalledModule::toModuleInfo)
                                                .toList();
                                requireRunning(shouldStop);
                                dispatch(() -> onSuccess.accept(new Result(hierarchy, modules)));
                            } catch (IOException | RuntimeException failure) {
                                dispatch(() -> onFailure.accept(failure));
                            }
                        });
    }

    /** No hierarchy capture from the summary stage is retained by this worker. */
    void validateMetrics(
            ProjectSummaryData summary,
            List<InstalledModule> modules,
            LocalResourceEnvelope envelope,
            BooleanSupplier shouldStop,
            Consumer<ProjectSummaryData> onSuccess,
            Consumer<Exception> onFailure) {
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                requireRunning(shouldStop);
                                var resolved =
                                        metricCacheLoader.attachExactResults(
                                                summary,
                                                modules,
                                                envelope,
                                                summary.scope().mode().wholeSlide(),
                                                shouldStop);
                                requireRunning(shouldStop);
                                dispatch(() -> onSuccess.accept(resolved));
                            } catch (RuntimeException failure) {
                                dispatch(() -> onFailure.accept(failure));
                            }
                        });
    }

    private void dispatch(Runnable callback) {
        uiDispatcher.accept(callback);
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("project data load cancelled");
        }
    }
}

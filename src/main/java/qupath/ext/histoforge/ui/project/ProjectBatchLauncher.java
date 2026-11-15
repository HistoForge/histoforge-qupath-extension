package qupath.ext.histoforge.ui.project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.data.ProjectInferenceScope.Mode;
import qupath.ext.histoforge.data.ProjectInferenceSettings;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.modules.InferencePrecision;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleActivationValidator;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.pipeline.BatchOrchestrator;
import qupath.ext.histoforge.pipeline.ImageActionExecutor;
import qupath.ext.histoforge.ui.HistoforgePrefs;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.lib.projects.Project;

/** Captures exact module/action requests and starts one bounded Project-tab batch. */
final class ProjectBatchLauncher {

    private final ModulesRegistry registry;
    private final ProjectActionRuntimeFactory runtimeFactory;
    private final ProjectBatchSession batchSession;
    private final ProjectInferenceJobs jobs;

    ProjectBatchLauncher(
            ModulesRegistry registry,
            ProjectActionRuntimeFactory runtimeFactory,
            ProjectBatchSession batchSession,
            ProjectInferenceJobs jobs) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.batchSession = Objects.requireNonNull(batchSession, "batchSession");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    void launch(
            List<ImageRowModel> rows,
            List<String> modelIds,
            ProjectRunCoordinator.RequestedAction requestedAction,
            Mode regionMode,
            Project<?> project,
            ClientBuildIdentity clientBuild,
            LargeActionHandleStore handles)
            throws IOException {
        Map<String, InstalledModule> modules = modules(rows);
        Map<String, Map<String, Integer>> haloOverrides =
                requestedAction == ProjectRunCoordinator.RequestedAction.METRICS
                        ? Map.of()
                        : ProjectInferenceSettings.read(project);
        GatewayCapabilities gatewayCapabilities = requireOneGateway(modules.values());
        String actionId = UUID.randomUUID().toString();
        boolean removeDuplicates = HistoforgePrefs.removeOverlappingSameClassDuplicates();
        var inferenceGpuOverride =
                requestedAction == ProjectRunCoordinator.RequestedAction.METRICS
                        ? java.util.Optional.<String>empty()
                        : HistoforgePrefs.inferenceGpuOverride(gatewayCapabilities.inferenceGpus());
        var precision =
                requestedAction == ProjectRunCoordinator.RequestedAction.METRICS
                        ? InferencePrecision.DEFAULT
                        : HistoforgePrefs.inferencePrecision();

        List<ImageActionExecutor.Request> requests = new ArrayList<>(rows.size());
        Map<String, ModuleContentIdentity> moduleByImage = new LinkedHashMap<>();
        for (ImageRowModel row : rows) {
            InstalledModule installed = modules.get(row.moduleId);
            ActionSelectionCodec.ImageAction action =
                    action(row, modelIds, requestedAction, regionMode, removeDuplicates);
            ModuleSnapshot module =
                    ModuleActivationValidator.activate(
                            gatewayCapabilities.deploymentId(),
                            installed.descriptor(),
                            action,
                            inferenceGpuOverride,
                            haloOverrides.getOrDefault(installed.ref(), Map.of()),
                            precision);
            requests.add(
                    new ImageActionExecutor.Request(
                            actionId, clientBuild, row.entry, module, action, row.annotationClass));
            moduleByImage.put(row.entry.getID(), installed.descriptor().identity());
        }

        ProjectActionRuntime runtime =
                runtimeFactory.open(
                        gatewayCapabilities,
                        clientBuild,
                        handles,
                        status -> jobs.observedStatus(project, status),
                        progress -> jobs.observedSourceProgress(project, progress));
        try {
            int concurrency =
                    Math.min(
                            gatewayCapabilities.limits().maxActiveSourceArtifacts(),
                            gatewayCapabilities.limits().maxActiveModelRuns());
            BatchOrchestrator batch = new BatchOrchestrator(runtime.coordinator(), concurrency);
            batchSession.start(
                    batch,
                    runtime,
                    requests,
                    project,
                    moduleByImage,
                    requestedAction.displayName(),
                    regionMode.wholeSlide());
        } catch (RuntimeException failure) {
            runtime.close();
            throw failure;
        }
    }

    static ActionSelectionCodec.ImageAction action(
            ImageRowModel row,
            List<String> modelIds,
            ProjectRunCoordinator.RequestedAction requestedAction,
            Mode regionMode,
            boolean removeDuplicates) {
        ActionSelectionCodec.RegionSelection regions = regions(row, regionMode);
        if (requestedAction == ProjectRunCoordinator.RequestedAction.METRICS) {
            return new ActionSelectionCodec.Metrics(regions);
        }

        ActionSelectionCodec.ModelWorkSelection work;
        if (modelIds.isEmpty()) {
            work = new ActionSelectionCodec.AllModels(regions);
        } else {
            ActionSelectionCodec.TargetSelection targets =
                    new ActionSelectionCodec.TargetSelection(targets(row, regionMode));
            List<ActionSelectionCodec.StepTargets> steps =
                    modelIds.stream()
                            .map(modelId -> new ActionSelectionCodec.StepTargets(modelId, targets))
                            .toList();
            work = new ActionSelectionCodec.SelectedModels(regions, steps);
        }
        return requestedAction == ProjectRunCoordinator.RequestedAction.FILL
                ? new ActionSelectionCodec.Fill(work, removeDuplicates)
                : new ActionSelectionCodec.Redo(work, removeDuplicates);
    }

    static ActionSelectionCodec.RegionSelection regions(ImageRowModel row, Mode regionMode) {
        return regionMode.wholeSlide()
                ? new ActionSelectionCodec.SlideRegion(new ActionSelectionCodec.FullSlide())
                : new ActionSelectionCodec.RoiRegions(row.roiIds);
    }

    private static List<ActionSelectionCodec.TargetSelector> targets(
            ImageRowModel row, Mode regionMode) {
        if (regionMode.wholeSlide()) {
            return List.of(new ActionSelectionCodec.SlideTarget());
        }
        return row.roiIds.stream()
                .map(ActionSelectionCodec.RoiTarget::new)
                .map(ActionSelectionCodec.TargetSelector.class::cast)
                .toList();
    }

    private Map<String, InstalledModule> modules(List<ImageRowModel> rows) {
        Map<String, InstalledModule> result = new LinkedHashMap<>();
        for (ImageRowModel row : rows) {
            result.putIfAbsent(row.moduleId, registry.get(row.moduleId).orElseThrow());
        }
        return result;
    }

    private static GatewayCapabilities requireOneGateway(
            Iterable<InstalledModule> installedModules) {
        GatewayCapabilities expected = null;
        for (InstalledModule module : installedModules) {
            if (expected == null) {
                expected = module.gateway();
            } else if (!expected.equals(module.gateway())) {
                throw new IllegalStateException(
                        "selected modules were captured from different gateway states; refresh"
                                + " modules and try again");
            }
        }
        return Objects.requireNonNull(expected, "selected modules");
    }
}

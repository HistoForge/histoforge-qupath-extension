package qupath.ext.histoforge.ui.project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.export.ExportTypes.ExportArtifact;
import qupath.ext.histoforge.export.ExportTypes.ExportImageSelection;
import qupath.ext.histoforge.export.ExportTypes.ExportModuleSelection;
import qupath.ext.histoforge.export.ExportTypes.ExportOverwritePolicy;
import qupath.ext.histoforge.export.ExportTypes.ExportRequest;
import qupath.ext.histoforge.export.MetricsExporter;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleActivationValidator;
import qupath.lib.projects.Project;

/** Activates exact module snapshots and builds one fail-closed Project-tab export transaction. */
final class ProjectExportCoordinator {

    private final MetricsExporter exporter;

    ProjectExportCoordinator(MetricsExporter exporter) {
        this.exporter = Objects.requireNonNull(exporter, "exporter");
    }

    /** One image's already-validated UI selection before exact module activation. */
    record ImageIntent(
            String imageId,
            ActionSelectionCodec.RegionSelection regions,
            String group,
            InstalledModule module,
            Optional<String> annotationClass) {
        ImageIntent(
                String imageId,
                ActionSelectionCodec.RegionSelection regions,
                String group,
                InstalledModule module) {
            this(imageId, regions, group, module, Optional.empty());
        }

        ImageIntent {
            imageId = Objects.requireNonNull(imageId, "imageId");
            regions = Objects.requireNonNull(regions, "regions");
            group = Objects.requireNonNull(group, "group");
            module = Objects.requireNonNull(module, "module");
            annotationClass = Objects.requireNonNull(annotationClass, "annotationClass");
            if (module.metricsScript() == null) {
                throw new IllegalArgumentException("export module has no metrics.groovy");
            }
        }
    }

    /** Activate every selected module once, then resolve and atomically publish the workbook. */
    ExportArtifact export(
            Project<BufferedImage> project,
            ClientBuildIdentity clientBuild,
            Path destination,
            ExportOverwritePolicy overwrite,
            List<ImageIntent> intents,
            BooleanSupplier shouldStop)
            throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(clientBuild, "clientBuild");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(overwrite, "overwrite");
        intents = List.copyOf(intents);
        if (intents.isEmpty()) {
            throw new IllegalArgumentException("export requires at least one image");
        }
        Objects.requireNonNull(shouldStop, "shouldStop");

        requireRunning(shouldStop);
        List<ExportImageSelection> images = new ArrayList<>(intents.size());
        for (ImageIntent intent : intents) {
            requireRunning(shouldStop);
            var snapshot =
                    ModuleActivationValidator.activate(
                            intent.module().gateway().deploymentId(),
                            intent.module().descriptor(),
                            new ActionSelectionCodec.Metrics(intent.regions()));
            images.add(
                    new ExportImageSelection(
                            intent.imageId(),
                            List.of(
                                    new ExportModuleSelection(
                                            snapshot, intent.regions(), intent.annotationClass())),
                            Map.of("group", intent.group())));
        }
        ExportRequest request =
                new ExportRequest(UUID.randomUUID(), destination, overwrite, images);
        return exporter.export(project, clientBuild, request, shouldStop);
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("export cancelled");
        }
    }
}

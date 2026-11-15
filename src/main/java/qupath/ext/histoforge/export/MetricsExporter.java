package qupath.ext.histoforge.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.data.MetricImageResolver;
import qupath.ext.histoforge.data.MetricImageResolver.MetricSelection;
import qupath.ext.histoforge.data.MetricImageResolver.ResolvedImage;
import qupath.ext.histoforge.data.MetricImageResolver.ResolvedModule;
import qupath.ext.histoforge.export.ExportTypes.ExportArtifact;
import qupath.ext.histoforge.export.ExportTypes.ExportGenerationEvidence;
import qupath.ext.histoforge.export.ExportTypes.ExportImageEvidence;
import qupath.ext.histoforge.export.ExportTypes.ExportImageSelection;
import qupath.ext.histoforge.export.ExportTypes.ExportManifest;
import qupath.ext.histoforge.export.ExportTypes.ExportModuleEvidence;
import qupath.ext.histoforge.export.ExportTypes.ExportOverwritePolicy;
import qupath.ext.histoforge.export.ExportTypes.ExportRequest;
import qupath.ext.histoforge.export.ExportTypes.ProvenanceAuditSummary;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Fail-closed exact metric resolution, evidence construction, and atomic XLSX publication. */
public final class MetricsExporter {

    private static final Logger logger = LoggerFactory.getLogger(MetricsExporter.class);

    private final MetricImageResolver resolver;
    private final WorkbookSink workbookSink;
    private final Clock clock;

    /** Use the production normalized workbook writer and UTC evidence timestamps. */
    public MetricsExporter(MetricImageResolver resolver) {
        this(resolver, ExportWorkbookWriter::write, Clock.systemUTC());
    }

    MetricsExporter(MetricImageResolver resolver, WorkbookSink workbookSink, Clock clock) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.workbookSink = Objects.requireNonNull(workbookSink, "workbookSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolve every requested image and publish one closed workbook as a destination transaction.
     *
     * <p>Any missing image, metric miss that fails to recompute, incomplete region result, evidence
     * inconsistency, workbook failure, hash failure, or unsupported atomic publication leaves the
     * requested final destination unchanged.
     */
    public ExportArtifact export(
            Project<BufferedImage> project,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ExportRequest request,
            BooleanSupplier shouldStop)
            throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(clientBuild, "clientBuild");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(shouldStop, "shouldStop");
        Path destination = request.destination();
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("export destination directory does not exist");
        }
        if (request.overwrite() == ExportOverwritePolicy.FAIL_IF_EXISTS
                && Files.exists(destination)) {
            throw new FileAlreadyExistsException(destination.toString());
        }

        Map<String, ProjectImageEntry<BufferedImage>> entries = projectEntries(project);
        List<ExportWorkbookWriter.ImageMetrics> workbookImages = new ArrayList<>();
        List<ExportImageEvidence> imageEvidence = new ArrayList<>();
        for (ExportImageSelection selection : request.images()) {
            requireRunning(shouldStop);
            ProjectImageEntry<BufferedImage> entry = entries.get(selection.imageId());
            if (entry == null) {
                throw new IllegalArgumentException(
                        "selected project image is missing: " + selection.imageId());
            }
            ResolvedImage resolved =
                    resolver.resolve(
                            entry,
                            clientBuild,
                            selection.modules().stream()
                                    .map(
                                            module ->
                                                    new MetricSelection(
                                                            module.module(),
                                                            module.regions(),
                                                            module.annotationClass()))
                                    .toList(),
                            true,
                            shouldStop);
            if (!resolved.sourceRevision()
                    .imageDataRevision()
                    .imageId()
                    .equals(selection.imageId())) {
                throw new IllegalStateException("metric capture belongs to another project image");
            }
            List<ExportModuleEvidence> modules = moduleEvidence(resolved, clientBuild);
            imageEvidence.add(
                    new ExportImageEvidence(
                            resolved.capturedAt(),
                            resolved.materialization(),
                            resolved.sourceRevision().imageDataRevision(),
                            selection.studyFields(),
                            modules));
            workbookImages.add(
                    new ExportWorkbookWriter.ImageMetrics(
                            selection.imageId(),
                            entry.getImageName(),
                            selection.studyFields(),
                            resolved.modules().stream()
                                    .map(module -> module.evaluation().orElseThrow())
                                    .toList()));
        }

        ExportManifest manifest =
                new ExportManifest(
                        ExportTypes.MANIFEST_SCHEMA_VERSION,
                        request.exportId(),
                        clock.instant().truncatedTo(ChronoUnit.MILLIS),
                        clientBuild,
                        ExportTypes.ExportClaim.CAPTURED_HIERARCHY_METRICS,
                        imageEvidence);

        Path temporary = Files.createTempFile(parent, ".histoforge-export-", ".xlsx.tmp");
        boolean published = false;
        try {
            workbookSink.write(temporary, manifest, workbookImages);
            long size = Files.size(temporary);
            String digest = sha256(temporary);
            atomicPublish(temporary, destination, request.overwrite());
            published = true;
            return new ExportArtifact(destination, size, digest, manifest);
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            } else {
                // FAIL_IF_EXISTS publishes by adding a hard link, so its private temporary name
                // remains after the destination becomes authoritative. Cleanup is replaceable
                // housekeeping and must not turn a completed atomic publication into a reported
                // failure.
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    logger.warn(
                            "Could not remove completed export temporary file {}",
                            temporary,
                            cleanupFailure);
                    temporary.toFile().deleteOnExit();
                }
            }
        }
    }

    private static List<ExportModuleEvidence> moduleEvidence(
            ResolvedImage image, BuildIdentityCodec.ClientBuildIdentity clientBuild) {
        var generationStore =
                image.generationStore()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "export requires captured generation evidence"));
        List<ExportModuleEvidence> result = new ArrayList<>(image.modules().size());
        for (ResolvedModule module : image.modules()) {
            var evaluation =
                    module.evaluation()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "export metric result is absent after"
                                                            + " recompute"));
            if (!evaluation.complete()) {
                throw new IllegalStateException("export metric evaluation contains failed regions");
            }
            if (!evaluation.context().clientBuild().equals(clientBuild)) {
                throw new IllegalStateException(
                        "export metric evaluation belongs to another client build");
            }
            Map<java.util.UUID, ProvenanceCodec.ObjectProvenance> attributed =
                    module.attributedObjects();
            GenerationStoreCodec.SweepResult swept =
                    GenerationStoreCodec.markAndSweep(generationStore, attributed.values());
            long linked =
                    attributed.values().stream()
                            .filter(
                                    provenance ->
                                            generationStore
                                                    .currentGenerationRecords()
                                                    .containsKey(provenance.outputGenerationId()))
                            .count();
            ExportGenerationEvidence generation =
                    ExportManifestCodec.generationEvidence(
                            swept.store(), swept.missingGenerationRecordIds());
            result.add(
                    new ExportModuleEvidence(
                            evaluation.context().metricInputDigest(),
                            evaluation.context().module(),
                            evaluation.context().metricsScriptDigest(),
                            evaluation.context().regions(),
                            new ProvenanceAuditSummary(attributed.size(), linked),
                            generation));
        }
        return List.copyOf(result);
    }

    private static Map<String, ProjectImageEntry<BufferedImage>> projectEntries(
            Project<BufferedImage> project) {
        Map<String, ProjectImageEntry<BufferedImage>> result = new LinkedHashMap<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            if (result.putIfAbsent(entry.getID(), entry) != null) {
                throw new IllegalArgumentException(
                        "project contains duplicate image ID " + entry.getID());
            }
        }
        return result;
    }

    private static void atomicPublish(
            Path temporary, Path destination, ExportOverwritePolicy overwrite) throws IOException {
        try {
            if (overwrite == ExportOverwritePolicy.REPLACE_EXISTING) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                // With ATOMIC_MOVE, Java deliberately leaves target-exists behavior
                // implementation-specific; Unix rename replaces the target even without
                // REPLACE_EXISTING. A same-filesystem hard link publishes the already-closed
                // complete bytes atomically and fails if the destination appeared meanwhile.
                try {
                    Files.createLink(destination, temporary);
                } catch (UnsupportedOperationException unsupported) {
                    throw new IOException(
                            "destination filesystem does not support atomic no-clobber workbook"
                                    + " publication",
                            unsupported);
                }
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException(
                    "destination filesystem does not support atomic workbook publication",
                    unsupported);
        }
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("export cancelled");
        }
    }

    @FunctionalInterface
    interface WorkbookSink {
        void write(
                Path destination,
                ExportManifest manifest,
                List<ExportWorkbookWriter.ImageMetrics> images)
                throws IOException;
    }
}

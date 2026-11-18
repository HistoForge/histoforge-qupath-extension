package qupath.ext.histoforge.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static qupath.ext.histoforge.data.MetricResultRetentionAssertions.assertOnlyResultData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.data.MetricImageResolver;
import qupath.ext.histoforge.data.MetricImageResolver.MetricSelection;
import qupath.ext.histoforge.data.MetricImageResolver.ResolvedImage;
import qupath.ext.histoforge.data.MetricImageResolver.ResolvedModule;
import qupath.ext.histoforge.data.MetricResultContext;
import qupath.ext.histoforge.data.MetricSnapshotBuilder.CapturedPreparation;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.export.ExportTypes.ExportArtifact;
import qupath.ext.histoforge.export.ExportTypes.ExportImageSelection;
import qupath.ext.histoforge.export.ExportTypes.ExportModuleSelection;
import qupath.ext.histoforge.export.ExportTypes.ExportOverwritePolicy;
import qupath.ext.histoforge.export.ExportTypes.ExportRequest;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Fail-closed metric resolution and destination-level publication transaction. */
class MetricsExporterTest {

    @TempDir Path temporaryDirectory;

    @Test
    void publishesClosedWorkbookBytesOnlyAfterCompleteResolution() throws Exception {
        Fixture fixture = fixture(ExportTestData.resolvedImage());
        byte[] workbook = "closed-workbook".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MetricsExporter exporter =
                new MetricsExporter(
                        fixture.resolver(),
                        (destination, manifest, images) -> {
                            assertOnlyResultData(images);
                            assertEquals(
                                    ExportTestData.resolvedImage()
                                            .modules()
                                            .getFirst()
                                            .evaluation()
                                            .orElseThrow()
                                            .context(),
                                    images.getFirst().evaluations().getFirst().context());
                            Files.write(destination, workbook);
                        },
                        Clock.fixed(ExportTestData.CREATED_AT, ZoneOffset.UTC));
        Path destination = temporaryDirectory.resolve("metrics.xlsx");
        ExportRequest request = request(destination, ExportOverwritePolicy.FAIL_IF_EXISTS);

        ExportArtifact artifact =
                exporter.export(
                        fixture.project(), ExportTestData.clientBuild(), request, fixture.stop());

        assertArrayEquals(workbook, Files.readAllBytes(destination));
        assertEquals(workbook.length, artifact.sizeBytes());
        assertEquals(CanonicalCodec.sha256(workbook), artifact.sha256());
        assertEquals(destination.toAbsolutePath(), artifact.destination());
        assertEquals(ExportTestData.EXPORT_ID, artifact.manifest().exportId());
        assertEquals(ExportTestData.CREATED_AT, artifact.manifest().createdAt());
        assertEquals(1, artifact.manifest().images().size());
        assertArrayEquals(
                ExportManifestCodec.canonicalBytes(ExportTestData.manifest()),
                ExportManifestCodec.canonicalBytes(artifact.manifest()));
        assertEquals(1, fileCount(temporaryDirectory));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MetricSelection>> selections = ArgumentCaptor.forClass(List.class);
        verify(fixture.resolver())
                .resolve(
                        eq(fixture.entry()),
                        eq(ExportTestData.clientBuild()),
                        selections.capture(),
                        eq(true),
                        eq(fixture.stop()));
        assertEquals(
                ExportTestData.module().module(),
                selections.getValue().getFirst().module().module());
    }

    @Test
    void workbookFailureLeavesExistingDestinationUnchangedAndRemovesTemporaryFile()
            throws Exception {
        Fixture fixture = fixture(ExportTestData.resolvedImage());
        Path destination = temporaryDirectory.resolve("metrics.xlsx");
        byte[] original = "prior-workbook".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(destination, original);
        MetricsExporter exporter =
                new MetricsExporter(
                        fixture.resolver(),
                        (temporary, manifest, images) -> {
                            Files.writeString(temporary, "partial replacement");
                            throw new IOException("workbook failed");
                        },
                        Clock.fixed(ExportTestData.CREATED_AT, ZoneOffset.UTC));

        IOException error =
                assertThrows(
                        IOException.class,
                        () ->
                                exporter.export(
                                        fixture.project(),
                                        ExportTestData.clientBuild(),
                                        request(
                                                destination,
                                                ExportOverwritePolicy.REPLACE_EXISTING),
                                        fixture.stop()));

        assertEquals("workbook failed", error.getMessage());
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertEquals(1, fileCount(temporaryDirectory));
    }

    @Test
    void incompleteMetricEvaluationAbortsBeforeWorkbookPublication() throws Exception {
        Fixture fixture = fixture(incompleteResolvedImage());
        Path destination = temporaryDirectory.resolve("metrics.xlsx");
        byte[] original = "prior-workbook".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(destination, original);
        java.util.concurrent.atomic.AtomicBoolean wrote =
                new java.util.concurrent.atomic.AtomicBoolean();
        MetricsExporter exporter =
                new MetricsExporter(
                        fixture.resolver(),
                        (temporary, manifest, images) -> wrote.set(true),
                        Clock.fixed(ExportTestData.CREATED_AT, ZoneOffset.UTC));

        assertThrows(
                IllegalStateException.class,
                () ->
                        exporter.export(
                                fixture.project(),
                                ExportTestData.clientBuild(),
                                request(destination, ExportOverwritePolicy.REPLACE_EXISTING),
                                fixture.stop()));

        assertFalse(wrote.get());
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertEquals(1, fileCount(temporaryDirectory));
    }

    @Test
    void evaluationFromAnotherClientBuildAbortsBeforeWorkbookPublication() throws Exception {
        Fixture fixture = fixture(resolvedWithDifferentClientBuild());
        Path destination = temporaryDirectory.resolve("metrics.xlsx");
        java.util.concurrent.atomic.AtomicBoolean wrote =
                new java.util.concurrent.atomic.AtomicBoolean();
        MetricsExporter exporter =
                new MetricsExporter(
                        fixture.resolver(),
                        (temporary, manifest, images) -> wrote.set(true),
                        Clock.fixed(ExportTestData.CREATED_AT, ZoneOffset.UTC));

        assertThrows(
                IllegalStateException.class,
                () ->
                        exporter.export(
                                fixture.project(),
                                ExportTestData.clientBuild(),
                                request(destination, ExportOverwritePolicy.FAIL_IF_EXISTS),
                                fixture.stop()));

        assertFalse(wrote.get());
        assertFalse(Files.exists(destination));
        assertEquals(0, fileCount(temporaryDirectory));
    }

    @Test
    void failIfExistsRejectsBeforeOpeningOrResolvingAnyImage() throws Exception {
        Fixture fixture = fixture(ExportTestData.resolvedImage());
        Path destination = temporaryDirectory.resolve("metrics.xlsx");
        Files.writeString(destination, "existing");
        MetricsExporter exporter =
                new MetricsExporter(
                        fixture.resolver(),
                        (temporary, manifest, images) -> {
                            throw new AssertionError("writer must not run");
                        },
                        Clock.fixed(ExportTestData.CREATED_AT, ZoneOffset.UTC));

        assertThrows(
                FileAlreadyExistsException.class,
                () ->
                        exporter.export(
                                fixture.project(),
                                ExportTestData.clientBuild(),
                                request(destination, ExportOverwritePolicy.FAIL_IF_EXISTS),
                                fixture.stop()));

        verify(fixture.resolver(), never()).resolve(any(), any(), any(), any(Boolean.class), any());
        assertTrue(Files.exists(destination));
    }

    @Test
    void failIfExistsDoesNotClobberDestinationCreatedDuringWorkbookConstruction() throws Exception {
        Fixture fixture = fixture(ExportTestData.resolvedImage());
        Path destination = temporaryDirectory.resolve("metrics.xlsx");
        byte[] concurrent = "concurrent-workbook".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MetricsExporter exporter =
                new MetricsExporter(
                        fixture.resolver(),
                        (temporary, manifest, images) -> {
                            Files.writeString(temporary, "completed-histoforge-workbook");
                            Files.write(destination, concurrent);
                        },
                        Clock.fixed(ExportTestData.CREATED_AT, ZoneOffset.UTC));

        assertThrows(
                FileAlreadyExistsException.class,
                () ->
                        exporter.export(
                                fixture.project(),
                                ExportTestData.clientBuild(),
                                request(destination, ExportOverwritePolicy.FAIL_IF_EXISTS),
                                fixture.stop()));

        assertArrayEquals(concurrent, Files.readAllBytes(destination));
        assertEquals(1, fileCount(temporaryDirectory));
    }

    private static ResolvedImage incompleteResolvedImage() {
        ResolvedImage complete = ExportTestData.resolvedImage();
        ResolvedModule module = complete.modules().getFirst();
        var key = module.context().regions().getFirst().key();
        MetricsEngine.Evaluation incomplete =
                new MetricsEngine.Evaluation(
                        module.context(),
                        Map.of(),
                        Map.of(
                                key,
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.METRICS, "failed")),
                        false);
        return new ResolvedImage(
                complete.capturedAt(),
                complete.materialization(),
                complete.sourceRevision(),
                complete.generationStore(),
                List.of(
                        new ResolvedModule(
                                module.module(),
                                new CapturedPreparation(ExportTestData.preparation(), Map.of()),
                                java.util.Optional.of(incomplete))));
    }

    private static ResolvedImage resolvedWithDifferentClientBuild() {
        ResolvedImage original = ExportTestData.resolvedImage();
        ResolvedModule module = original.modules().getFirst();
        var oldPreparation = ExportTestData.preparation();
        var oldInput = oldPreparation.snapshot().metricInput();
        var oldBuild = oldInput.clientBuild();
        var differentBuild =
                new qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity(
                        oldBuild.extension(),
                        oldBuild.quPathVersion(),
                        Map.of("java", "22", "jts", "1.20.0"),
                        oldBuild.limits());
        var input =
                qupath.ext.histoforge.data.MetricTypes.MetricInputDraft.build(
                        differentBuild,
                        oldInput.calibration(),
                        oldInput.module(),
                        oldInput.script(),
                        oldInput.regions());
        var preparation =
                new qupath.ext.histoforge.data.MetricTypes.MetricPreparation(
                        new qupath.ext.histoforge.data.MetricTypes.MetricImageSnapshot(
                                input, oldPreparation.snapshot().sourceRevision()),
                        oldPreparation.parentNormalization());
        MetricsEngine.Evaluation evaluation =
                new MetricsEngine.Evaluation(
                        MetricResultContext.from(preparation.snapshot()),
                        module.evaluation().orElseThrow().regionResults(),
                        Map.of(),
                        false);
        return new ResolvedImage(
                original.capturedAt(),
                original.materialization(),
                original.sourceRevision(),
                original.generationStore(),
                List.of(
                        new ResolvedModule(
                                module.module(),
                                new CapturedPreparation(preparation, module.attributedObjects()),
                                java.util.Optional.of(evaluation))));
    }

    private static ExportRequest request(Path destination, ExportOverwritePolicy overwrite) {
        ActionSelectionCodec.RegionSelection regions =
                new ActionSelectionCodec.RoiRegions(java.util.Set.of(ExportTestData.ROI_ID));
        return new ExportRequest(
                ExportTestData.EXPORT_ID,
                destination,
                overwrite,
                List.of(
                        new ExportImageSelection(
                                ExportTestData.IMAGE_ID,
                                List.of(
                                        new ExportModuleSelection(
                                                ExportTestData.module(), regions)),
                                Map.of("group", "Control", "study", "Study A"))));
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(ResolvedImage resolved) throws Exception {
        MetricImageResolver resolver = mock(MetricImageResolver.class);
        Project<BufferedImage> project = mock(Project.class);
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        BooleanSupplier stop = () -> false;
        when(entry.getID()).thenReturn(ExportTestData.IMAGE_ID);
        when(entry.getImageName()).thenReturn("Image A");
        when(project.getImageList()).thenReturn(List.of(entry));
        when(resolver.resolve(
                        eq(entry), eq(ExportTestData.clientBuild()), any(), eq(true), eq(stop)))
                .thenReturn(resolved);
        return new Fixture(resolver, project, entry, stop);
    }

    private static long fileCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    private record Fixture(
            MetricImageResolver resolver,
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> entry,
            BooleanSupplier stop) {}
}

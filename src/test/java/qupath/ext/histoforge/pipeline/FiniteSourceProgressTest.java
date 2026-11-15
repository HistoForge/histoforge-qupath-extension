package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.readerSettings;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.client.SourceTransferProgress;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactStartRequest;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactRef;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactSealed;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.lib.images.ImageData;

/** Source-owner callbacks remain separate from per-ROI execution and shared-source ownership. */
class FiniteSourceProgressTest {

    private static final SourceTransferProgress UPLOADING =
            new SourceTransferProgress(SourceTransferProgress.Phase.UPLOADING, 4, 8);
    private static final SourceTransferProgress READY =
            new SourceTransferProgress(SourceTransferProgress.Phase.READY, 8, 8);
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @TempDir Path temporaryRoot;

    private final ModelGatewayExecutor gateway = mock(ModelGatewayExecutor.class);
    private final SourceArtifactWriter writer = mock(SourceArtifactWriter.class);
    private final ImageLifecycle.WorkingImage working = mock(ImageLifecycle.WorkingImage.class);
    private final List<ImageSourceProgress> observed = new ArrayList<>();
    private ImageData<BufferedImage> image;
    private ImageExecutionPlan plan;
    private FiniteSourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        image =
                RecipeExecutorTestData.imageData(
                        "finite-progress",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B);
        plan =
                RecipeExecutorTestData.plan(
                        image,
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                false,
                                Set.of(
                                        RecipeExecutorTestData.ROI_A,
                                        RecipeExecutorTestData.ROI_B)));
        when(working.sourceRevision()).thenReturn(RecipeExecutorTestData.sourceRevision());
        when(working.sourceRevisionVerifier()).thenReturn(ignored -> {});
        AtomicInteger artifactSequence = new AtomicInteger();
        provisioner =
                new FiniteSourceProvisioner(
                        gateway,
                        writer,
                        () ->
                                "60000000-0000-4000-8000-%012d"
                                        .formatted(artifactSequence.incrementAndGet()),
                        temporaryRoot,
                        observed::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        image.close();
    }

    @Test
    void lastDerivedUploadFailureClearsProgressBeforeTheEarlierRoiRuns() throws Exception {
        SourceInputPlan sources =
                RecipeSourcePlanner.plan(
                        plan,
                        RecipeExecutorTestData.capturedSourcePyramid(),
                        image.getHierarchy(),
                        Optional.empty(),
                        RecipeExecutorTestData.sourcePlanningLimits());
        AtomicInteger materializations = new AtomicInteger();
        List<Path> preparedImages = new ArrayList<>();
        when(writer.materialize(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(
                                    event(SourceTransferProgress.preparing()), observed.getLast());
                            SourceInputPlan.TargetSourcePlan source = invocation.getArgument(0);
                            assertInstanceOf(
                                    SourceInputPlan.DerivedArtifactPlan.class, source.artifact());
                            preparedImages.forEach(path -> assertFalse(Files.exists(path)));
                            Path destination = invocation.getArgument(2);
                            Files.write(destination, new byte[] {1, 2, 3});
                            preparedImages.add(destination);
                            materializations.incrementAndGet();
                            return mock(PreparedSourceArtifact.class);
                        });
        IOException failure = new IOException("second ROI upload failed");
        AtomicInteger publications = new AtomicInteger();
        SourceArtifactRef firstArtifact =
                new SourceArtifactRef(
                        "60000000-0000-4000-8000-000000000001",
                        plan.actionId(),
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        SourceArtifactCodec.SourceContainer.PNG);
        when(gateway.publishSource(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<SourceTransferProgress> progress = invocation.getArgument(2);
                            progress.accept(UPLOADING);
                            if (publications.incrementAndGet() == 2) throw failure;
                            progress.accept(READY);
                            return new SourceArtifactSealed(
                                    SourceArtifactCodec.SCHEMA_VERSION, firstArtifact, NOW);
                        });

        try (var session = provisioner.open()) {
            var provisions =
                    session.provision(
                            plan,
                            sources,
                            image,
                            working,
                            RecipeExecutorTestData.sourcePlanningLimits(),
                            () -> false);
            var first =
                    assertInstanceOf(
                            RecipeExecutor.PublishedSource.class,
                            provisions.get(
                                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A)));
            var second =
                    assertInstanceOf(
                            RecipeExecutor.FailedSource.class,
                            provisions.get(
                                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_B)));
            assertEquals(firstArtifact, first.artifact());
            preparedImages.forEach(path -> assertFalse(Files.exists(path)));
            assertSame(failure, second.cause());
            assertEquals(RunResults.FailureType.SUBMISSION, second.failure().type());
            var cleared =
                    new ImageSourceProgress(plan.actionId(), plan.imageId(), Optional.empty());
            assertEquals(
                    List.of(
                            event(SourceTransferProgress.preparing()),
                            event(UPLOADING),
                            event(READY),
                            event(SourceTransferProgress.preparing()),
                            event(UPLOADING),
                            cleared),
                    observed);

            AtomicInteger modelCalls = new AtomicInteger();
            var recipe =
                    new RecipeExecutor(
                            (input, stop) -> {
                                assertEquals(cleared, observed.getLast());
                                modelCalls.incrementAndGet();
                                return RecipeExecutorTestData.generation(input, List.of());
                            },
                            () -> "70000000-0000-4000-8000-000000000001",
                            Clock.fixed(NOW, ZoneOffset.UTC));
            var result =
                    recipe.execute(
                            plan,
                            new RecipeExecutor.ExecutionInputs(
                                    sources, provisions, working.sourceRevision()),
                            image,
                            () -> false);
            assertEquals(1, modelCalls.get());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY,
                            RunResults.ScopeOutcomeType.FAILED),
                    result.scopeResults().stream().map(RunResults.ScopeWorkResult::type).toList());
        }
        assertEquals(2, materializations.get());
        verify(gateway, times(2)).releaseSource(any());
    }

    @Test
    void threePreparedTargetsReleaseEachImageBeforeEncodingTheNext() throws Exception {
        image.close();
        image =
                RecipeExecutorTestData.imageData(
                        "three-uploads",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B,
                        RecipeExecutorTestData.ROI_C);
        plan =
                RecipeExecutorTestData.plan(
                        image,
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                false,
                                Set.of(
                                        RecipeExecutorTestData.ROI_A,
                                        RecipeExecutorTestData.ROI_B,
                                        RecipeExecutorTestData.ROI_C)));
        var sources =
                RecipeSourcePlanner.plan(
                        plan,
                        RecipeExecutorTestData.capturedSourcePyramid(),
                        image.getHierarchy(),
                        Optional.empty(),
                        RecipeExecutorTestData.sourcePlanningLimits());
        List<Path> preparedImages = new ArrayList<>();
        when(writer.materialize(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            preparedImages.forEach(path -> assertFalse(Files.exists(path)));
                            Path destination = invocation.getArgument(2);
                            Files.write(destination, new byte[] {1, 2, 3});
                            preparedImages.add(destination);
                            return mock(PreparedSourceArtifact.class);
                        });
        when(gateway.publishSource(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertTrue(Files.isRegularFile(preparedImages.getLast()));
                            return new SourceArtifactSealed(
                                    SourceArtifactCodec.SCHEMA_VERSION,
                                    new SourceArtifactRef(
                                            "60000000-0000-4000-8000-%012d"
                                                    .formatted(preparedImages.size()),
                                            plan.actionId(),
                                            "1".repeat(64),
                                            "2".repeat(64),
                                            "3".repeat(64),
                                            SourceArtifactCodec.SourceContainer.PNG),
                                    NOW);
                        });
        try (var session = provisioner.open()) {
            var results =
                    session.provision(
                            plan,
                            sources,
                            image,
                            working,
                            RecipeExecutorTestData.sourcePlanningLimits(),
                            () -> false);
            assertEquals(3, results.size());
            results.values()
                    .forEach(
                            value -> assertInstanceOf(RecipeExecutor.PublishedSource.class, value));
            assertEquals(3, preparedImages.size());
            preparedImages.forEach(path -> assertFalse(Files.exists(path)));
        }
    }

    @Test
    void derivedImageIsNotEncodedWhileTwoRetainedImagesOccupyTheSharedBudget() throws Exception {
        var budget = new PreparedImageBudget();
        for (String name : List.of("first.png", "second.png")) {
            Path retained = Files.writeString(temporaryRoot.resolve(name), "unfinished upload");
            try (var owner = budget.resume(retained)) {}
        }
        var blocked =
                new FiniteSourceProvisioner(
                        gateway,
                        writer,
                        () -> "60000000-0000-4000-8000-000000000001",
                        temporaryRoot,
                        observed::add,
                        budget);
        var sources =
                RecipeSourcePlanner.plan(
                        plan,
                        RecipeExecutorTestData.capturedSourcePyramid(),
                        image.getHierarchy(),
                        Optional.empty(),
                        RecipeExecutorTestData.sourcePlanningLimits());

        try (var session = blocked.open()) {
            var failure =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.provision(
                                            plan,
                                            sources,
                                            image,
                                            working,
                                            RecipeExecutorTestData.sourcePlanningLimits(),
                                            () -> false));
            assertTrue(failure.getMessage().contains("Jobs"));
        }
        verify(writer, never()).materialize(any(), any(), any(), any(), any(), any(), any());
        verify(gateway, never()).publishSource(any(), any(), any());
        assertTrue(observed.isEmpty());
    }

    @Test
    void sharedOriginalForwardsProgressOnceAndReuseDoesNotRestartTheTransfer() throws Exception {
        var budget = new PreparedImageBudget();
        for (String name : List.of("first.png", "second.png")) {
            Path retained = Files.writeString(temporaryRoot.resolve(name), "unfinished upload");
            try (var owner = budget.resume(retained)) {}
        }
        provisioner =
                new FiniteSourceProvisioner(
                        gateway,
                        writer,
                        () -> "60000000-0000-4000-8000-000000000001",
                        temporaryRoot,
                        observed::add,
                        budget);
        assertThrows(IOException.class, () -> budget.acquire(() -> false));
        var captured = originalSource();
        when(working.sourceRevision()).thenReturn(captured.sourceRevision());
        when(working.unchangedOriginalPath())
                .thenReturn(Optional.of(temporaryRoot.resolve("original.tif")));
        SourceInputPlan sources =
                RecipeSourcePlanner.plan(
                        plan,
                        captured,
                        image.getHierarchy(),
                        Optional.of(
                                new EligibleOriginalSource(
                                        8,
                                        SourceArtifactCodec.SourceContainer.SLIDE_FILE,
                                        "original.tif",
                                        readerSettings(captured))),
                        new SourcePlanningLimits(1_000_000, 1, 8_000_000));
        when(writer.materialize(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(
                                    List.of(event(SourceTransferProgress.preparing())), observed);
                            SourceInputPlan.TargetSourcePlan target = invocation.getArgument(0);
                            var original =
                                    assertInstanceOf(
                                            SourceInputPlan.OriginalArtifactPlan.class,
                                            target.artifact());
                            var start =
                                    new SourceArtifactStartRequest(
                                            SourceArtifactCodec.SCHEMA_VERSION,
                                            plan.actionId(),
                                            invocation.getArgument(5),
                                            SourceArtifactCodec.SourceContainer.SLIDE_FILE,
                                            original.totalBytes(),
                                            4,
                                            new SourceArtifactCodec.OriginalWsi(
                                                    captured.sourceRevision().pixelSource(),
                                                    original.pyramidSeriesIndex(),
                                                    original.levelZeroPixelsToSlide(),
                                                    original.entryPath(),
                                                    original.readerSettings(),
                                                    List.of()),
                                            original.requiredLevels());
                            var prepared = mock(PreparedSourceArtifact.class);
                            when(prepared.startRequest()).thenReturn(start);
                            return prepared;
                        });
        when(gateway.publishSource(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            PreparedSourceArtifact prepared = invocation.getArgument(0);
                            SourceArtifactStartRequest start = prepared.startRequest();
                            Consumer<SourceTransferProgress> progress = invocation.getArgument(2);
                            progress.accept(UPLOADING);
                            progress.accept(READY);
                            return new SourceArtifactSealed(
                                    SourceArtifactCodec.SCHEMA_VERSION,
                                    new SourceArtifactRef(
                                            start.artifactId(),
                                            plan.actionId(),
                                            SourceArtifactCodec.startRequestDigest(start),
                                            "2".repeat(64),
                                            "3".repeat(64),
                                            start.container()),
                                    NOW);
                        });

        try (var owner = provisioner.open()) {
            var prepared = owner.prepareOriginal(plan, sources, image, working, () -> false);
            var shared =
                    owner.publishOriginal(plan, captured.sourceRevision(), prepared, () -> false);
            List<ImageSourceProgress> completed = List.copyOf(observed);
            assertEquals(
                    List.of(
                            event(SourceTransferProgress.preparing()),
                            event(UPLOADING),
                            event(READY)),
                    completed);
            try (var consumer = provisioner.open()) {
                var provisions =
                        consumer.provision(
                                plan,
                                sources,
                                image,
                                working,
                                Optional.of(shared),
                                RecipeExecutorTestData.sourcePlanningLimits(),
                                () -> false);
                assertEquals(2, provisions.size());
                for (var provision : provisions.values()) {
                    assertEquals(
                            shared.artifact(),
                            assertInstanceOf(RecipeExecutor.PublishedSource.class, provision)
                                    .artifact());
                }
                assertEquals(completed, observed);
            }
            verify(gateway, times(1)).publishSource(any(), any(), any());
            verify(gateway, never()).releaseSource(any());
        }
        verify(writer, times(1)).materialize(any(), any(), any(), any(), any(), any(), any());
        verify(gateway, times(1)).releaseSource(any());
    }

    @ParameterizedTest
    @MethodSource("uploadFailures")
    void cleanupFailureDoesNotReplaceTheOriginalUploadOutcome(Exception failure) throws Exception {
        plan =
                RecipeExecutorTestData.plan(
                        image,
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        var sources =
                RecipeSourcePlanner.plan(
                        plan,
                        RecipeExecutorTestData.capturedSourcePyramid(),
                        image.getHierarchy(),
                        Optional.empty(),
                        RecipeExecutorTestData.sourcePlanningLimits());
        List<Path> preparedImages = new ArrayList<>();
        when(writer.materialize(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Path destination = invocation.getArgument(2);
                            Files.write(destination, new byte[] {1, 2, 3});
                            preparedImages.add(destination);
                            return mock(PreparedSourceArtifact.class);
                        });
        when(gateway.publishSource(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            // A nonempty directory reliably makes deletion fail on every supported
                            // OS.
                            Path destination = preparedImages.getLast();
                            Files.delete(destination);
                            Files.createDirectory(destination);
                            Files.writeString(destination.resolve("retained.txt"), "retain");
                            throw failure;
                        });
        try (var session = provisioner.open()) {
            if (failure instanceof IOException) {
                var results =
                        session.provision(
                                plan,
                                sources,
                                image,
                                working,
                                RecipeExecutorTestData.sourcePlanningLimits(),
                                () -> false);
                var failed =
                        assertInstanceOf(
                                RecipeExecutor.FailedSource.class,
                                results.values().iterator().next());
                assertSame(failure, failed.cause());
                assertEquals(RunResults.FailureType.SUBMISSION, failed.failure().type());
            } else {
                assertSame(
                        failure,
                        assertThrows(
                                failure.getClass(),
                                () ->
                                        session.provision(
                                                plan,
                                                sources,
                                                image,
                                                working,
                                                RecipeExecutorTestData.sourcePlanningLimits(),
                                                () -> false)));
            }
            assertEquals(1, failure.getSuppressed().length);
            assertInstanceOf(DirectoryNotEmptyException.class, failure.getSuppressed()[0]);
        }
    }

    private static Stream<Exception> uploadFailures() {
        return Stream.of(
                new CancellationException("cancelled"),
                new InterruptedException("interrupted"),
                new IOException("upload failed"));
    }

    private ImageSourceProgress event(SourceTransferProgress transfer) {
        return new ImageSourceProgress(plan.actionId(), plan.imageId(), transfer);
    }

    private static CapturedSourcePyramid originalSource() {
        var captured = RecipeExecutorTestData.capturedSourcePyramid();
        var source = captured.sourceRevision().pixelSource();
        var versioned =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "qupath.bioformats.local",
                        source.credentialFreeDescriptorDigest(),
                        Optional.of(new SourceIdentityCodec.LocalFile(8, 10_000, Optional.empty())),
                        source.width(),
                        source.height(),
                        source.zSlices(),
                        source.timepoints(),
                        source.calibration());
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                RecipeExecutorTestData.IMAGE_ID,
                                SourceIdentityCodec.imageSourceIdentityDigest(versioned),
                                "e".repeat(64)),
                        versioned);
        return new CapturedSourcePyramid(
                revision,
                captured.seriesIndex(),
                captured.levelZeroPixelsToSlide(),
                captured.levels());
    }
}

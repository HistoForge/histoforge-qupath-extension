package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.lib.images.ImageData;

class FiniteInputImageUploadsTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    @TempDir Path temporaryRoot;
    private final ModelGatewayExecutor gateway = mock(ModelGatewayExecutor.class);
    private final SourceArtifactWriter writer = mock(SourceArtifactWriter.class);
    private final ImageLifecycle.WorkingImage working = mock(ImageLifecycle.WorkingImage.class);
    private final AtomicInteger encoded = new AtomicInteger();
    private final AtomicInteger submitted = new AtomicInteger();
    private ImageData<BufferedImage> image;
    private ImageExecutionPlan plan;
    private SourceInputPlan sources;
    private FiniteSourceProvisioner provisioner;

    @BeforeEach
    void setUp() throws Exception {
        image =
                RecipeExecutorTestData.imageData(
                        "bounded-inputs",
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
        // Force the incremental route on a tiny fixture; the independent writer tests verify
        // actual selected-level bytes. Individual uploads below still obey real per-file limits.
        sources =
                RecipeSourcePlanner.plan(
                        plan,
                        RecipeExecutorTestData.capturedSourcePyramid(),
                        image.getHierarchy(),
                        Optional.empty(),
                        new SourcePlanningLimits(32_000_000, 1, 32L << 30));
        when(working.sourceRevision()).thenReturn(RecipeExecutorTestData.sourceRevision());
        when(working.sourceRevisionVerifier()).thenReturn(ignored -> {});
        var ids = new AtomicInteger();
        provisioner =
                new FiniteSourceProvisioner(
                        gateway,
                        writer,
                        () -> "60000000-0000-4000-8000-%012d".formatted(ids.incrementAndGet()),
                        temporaryRoot);
        when(writer.materializeCapturedImage(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            encoded.incrementAndGet();
                            SourceCaptureCodec.Capture capture = invocation.getArgument(0);
                            SourceInputPlan.DerivedArtifactPlan imagePlan =
                                    invocation.getArgument(1);
                            Path path = invocation.getArgument(3);
                            Files.write(path, new byte[] {1, 2, 3});
                            assertEquals(1, imageFileCount());
                            var rectangle = imagePlan.sourceRectAtLevel();
                            var start =
                                    new SourceArtifactCodec.SourceArtifactStartRequest(
                                            SourceArtifactCodec.SCHEMA_VERSION,
                                            invocation.getArgument(4),
                                            invocation.getArgument(5),
                                            imagePlan.container(),
                                            3,
                                            3,
                                            SourceCaptureCodec.imageOrigin(capture, rectangle),
                                            List.of(
                                                    new SourceArtifactCodec.SourceLevelRequirement(
                                                            0,
                                                            0,
                                                            rectangle.width(),
                                                            rectangle.height())));
                            return new SourceArtifactWriter.PreparedSourceArtifact(
                                    path,
                                    start,
                                    new SourceArtifactTransferCodec.SourceArtifactSealRequest(
                                            "1".repeat(64)));
                        });
        when(gateway.publishSource(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            submitted.incrementAndGet();
                            SourceArtifactWriter.PreparedSourceArtifact prepared =
                                    invocation.getArgument(0);
                            assertTrue(Files.exists(prepared.path()));
                            assertEquals(1, imageFileCount());
                            var start = prepared.startRequest();
                            return new SourceArtifactPublicationCodec.SourceArtifactSealed(
                                    SourceArtifactCodec.SCHEMA_VERSION,
                                    new SourceArtifactPublicationCodec.SourceArtifactRef(
                                            start.artifactId(),
                                            start.creationActionId(),
                                            SourceArtifactCodec.startRequestDigest(start),
                                            prepared.sealRequest().bytesDigest(),
                                            "2".repeat(64),
                                            start.container()),
                                    NOW);
                        });
    }

    @AfterEach
    void closeImage() throws Exception {
        image.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturesWithoutIoThenUploadsAndCommitsCompleteFiniteInputsOneAtATime(
            boolean sourceAboveOneGib) throws Exception {
        var limits = RecipeExecutorTestData.sourcePlanningLimits();
        if (sourceAboveOneGib) {
            // Metadata-only fine source sampling makes each unchanged 100-pixel model input
            // need more than 1 GiB of source RGB. The mocked writer never allocates those pixels.
            var source = fineSourceCapture();
            limits = new SourcePlanningLimits(32_000_000, 8L << 30, 32L << 30);
            sources =
                    RecipeSourcePlanner.plan(
                            plan, source, image.getHierarchy(), Optional.empty(), limits);
            when(working.sourceRevision()).thenReturn(source.sourceRevision());
            for (var target : sources.targets()) {
                var captured = target.sourcePlan().orElseThrow();
                assertInstanceOf(SourceInputPlan.IncrementalSourcePlan.class, captured.artifact());
                assertEquals(SourceInputPlan.ExecutionProtocol.FINITE, captured.protocol());
                var input = captured.fieldInputs().getFirst();
                assertEquals(100, input.frame().transform().raster().width());
                assertEquals(100, input.frame().transform().raster().height());
            }
        }
        var modelCalls = new AtomicInteger();
        var generations = new AtomicInteger();
        try (var session = provisioner.open()) {
            var provisions = session.provision(plan, sources, image, working, limits, () -> false);
            assertEquals(0, encoded.get());
            verifyNoInteractions(writer, gateway);
            provisions
                    .values()
                    .forEach(value -> assertInstanceOf(RecipeExecutor.CapturedSource.class, value));
            var recipe =
                    new RecipeExecutor(
                            (input, stop) -> {
                                assertEquals(modelCalls.incrementAndGet(), submitted.get());
                                assertEquals(0, imageFileCount());
                                if (sourceAboveOneGib) {
                                    var view =
                                            assertInstanceOf(
                                                    SourceCaptureCodec.View.class,
                                                    input.request().rasterInput().sourceView());
                                    var binding =
                                            FiniteInferenceCodec.inputIdentity(input.request())
                                                    .sourceBindings()
                                                    .getFirst()
                                                    .source();
                                    var rectangle = binding.sourceRectAtLevel();
                                    assertTrue(
                                            3L * rectangle.width() * rectangle.height()
                                                    > (1L << 30));
                                    assertEquals(
                                            view.capture().captureDigest(),
                                            binding.captureDigest());
                                }
                                return emptyGeneration(input);
                            },
                            () ->
                                    "70000000-0000-4000-8000-%012d"
                                            .formatted(generations.incrementAndGet()),
                            Clock.fixed(NOW, ZoneOffset.UTC));
            var result =
                    recipe.execute(
                            plan,
                            new RecipeExecutor.ExecutionInputs(
                                    sources,
                                    provisions,
                                    working.sourceRevision(),
                                    session.imageUploads()),
                            image,
                            () -> false);
            assertEquals(List.of(), result.failures());
            assertEquals(2, result.scopeResults().size());
            assertTrue(
                    result.scopeResults().stream()
                            .allMatch(
                                    value ->
                                            value.type()
                                                    == RunResults.ScopeOutcomeType
                                                            .SUCCEEDED_EMPTY));
            assertEquals(2, modelCalls.get());
            assertEquals(2, encoded.get());
            // One completed input does not occupy the source store while the next one is prepared.
            verify(gateway).releaseSource("60000000-0000-4000-8000-000000000001");
        }
        verify(gateway).releaseSource("60000000-0000-4000-8000-000000000002");
        assertEquals(0, imageFileCount());
    }

    @Test
    void completeStepReservationsPrecedeAnyUploadAndRemainUnchangedAfterBinding() throws Exception {
        try (var session = provisioner.open()) {
            var provisions =
                    session.provision(
                            plan,
                            sources,
                            image,
                            working,
                            RecipeExecutorTestData.sourcePlanningLimits(),
                            () -> false);
            var targetSource = sources.targets().getFirst().sourcePlan().orElseThrow();
            var input = targetSource.fieldInputs().getFirst();
            var pending =
                    assertInstanceOf(
                            FiniteModelInputExecutor.InputAwaitingUpload.class,
                            FiniteModelInputExecutor.plan(
                                    plan,
                                    0,
                                    "70000000-0000-4000-8000-000000000001",
                                    input,
                                    (RecipeExecutor.AvailableSource)
                                            provisions.get(targetSource.target().key()),
                                    List.of()));
            verifyNoInteractions(writer, gateway);
            var reservation = pending.reservation();
            var binding =
                    session.imageUploads()
                            .publish(pending.rasterInput(), pending.model(), () -> false);
            var bound = pending.bind(binding);
            assertSame(reservation, bound.reservation());
            var generation = emptyGeneration(bound);
            reservation.requireCompletedEvidence(generation.inputEvidence().committedEvidence());
            assertEquals(
                    List.of(new SourceCaptureCodec.UnitBinding(0, binding)),
                    FiniteInferenceCodec.inputIdentity(bound.request()).sourceBindings());
            // A compatible later input reuses the sealed file, without retaining local bytes.
            assertEquals(
                    binding,
                    session.imageUploads()
                            .publish(pending.rasterInput(), pending.model(), () -> false));
            assertEquals(1, encoded.get());
            assertEquals(1, submitted.get());
        }
    }

    @Test
    void boundedInputUploadAlsoUsesTheApplicationPreparationBudget() throws Exception {
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
                        ignored -> {},
                        budget);
        try (var session = blocked.open()) {
            var provisions =
                    session.provision(
                            plan,
                            sources,
                            image,
                            working,
                            RecipeExecutorTestData.sourcePlanningLimits(),
                            () -> false);
            var target = sources.targets().getFirst().sourcePlan().orElseThrow();
            var pending =
                    (FiniteModelInputExecutor.InputAwaitingUpload)
                            FiniteModelInputExecutor.plan(
                                    plan,
                                    0,
                                    "70000000-0000-4000-8000-000000000001",
                                    target.fieldInputs().getFirst(),
                                    (RecipeExecutor.AvailableSource)
                                            provisions.get(target.target().key()),
                                    List.of());

            assertThrows(
                    IOException.class,
                    () ->
                            session.imageUploads()
                                    .publish(pending.rasterInput(), pending.model(), () -> false));

            verifyNoInteractions(writer, gateway);
        }
    }

    @Test
    void failedUploadDoesNotRunTheInputOrLeaveTemporaryImageBytes() throws Exception {
        doThrow(new IOException("image upload failed"))
                .when(gateway)
                .publishSource(any(), any(), any());
        var modelCalls = new AtomicInteger();
        var generations = new AtomicInteger();
        try (var session = provisioner.open()) {
            var provisions =
                    session.provision(
                            plan,
                            sources,
                            image,
                            working,
                            RecipeExecutorTestData.sourcePlanningLimits(),
                            () -> false);
            var recipe =
                    new RecipeExecutor(
                            (input, stop) -> {
                                modelCalls.incrementAndGet();
                                return emptyGeneration(input);
                            },
                            () ->
                                    "70000000-0000-4000-8000-%012d"
                                            .formatted(generations.incrementAndGet()),
                            Clock.fixed(NOW, ZoneOffset.UTC));
            var result =
                    recipe.execute(
                            plan,
                            new RecipeExecutor.ExecutionInputs(
                                    sources,
                                    provisions,
                                    working.sourceRevision(),
                                    session.imageUploads()),
                            image,
                            () -> false);
            assertEquals(0, modelCalls.get());
            assertEquals(2, result.failures().size());
            assertEquals(0, imageFileCount());
        }
    }

    @Test
    void cancellationBeforePreparationDoesNotUpload() throws Exception {
        try (var session = provisioner.open()) {
            var provisions =
                    session.provision(
                            plan,
                            sources,
                            image,
                            working,
                            RecipeExecutorTestData.sourcePlanningLimits(),
                            () -> false);
            var target = sources.targets().getFirst().sourcePlan().orElseThrow();
            var pending =
                    (FiniteModelInputExecutor.InputAwaitingUpload)
                            FiniteModelInputExecutor.plan(
                                    plan,
                                    0,
                                    "70000000-0000-4000-8000-000000000001",
                                    target.fieldInputs().getFirst(),
                                    (RecipeExecutor.AvailableSource)
                                            provisions.get(target.target().key()),
                                    List.of());
            assertThrows(
                    CancellationException.class,
                    () ->
                            session.imageUploads()
                                    .publish(pending.rasterInput(), pending.model(), () -> true));
            verifyNoInteractions(writer, gateway);
            assertEquals(0, imageFileCount());
        }
    }

    @ParameterizedTest
    @MethodSource("uploadFailures")
    void cleanupFailureDoesNotReplaceTheOriginalUploadOutcome(Exception failure) throws Exception {
        doAnswer(
                        invocation -> {
                            SourceArtifactWriter.PreparedSourceArtifact prepared =
                                    invocation.getArgument(0);
                            // A nonempty directory reliably makes deletion fail on every supported
                            // OS.
                            Files.delete(prepared.path());
                            Files.createDirectory(prepared.path());
                            Files.writeString(prepared.path().resolve("retained.txt"), "retain");
                            throw failure;
                        })
                .when(gateway)
                .publishSource(any(), any(), any());
        try (var session = provisioner.open()) {
            var provisions =
                    session.provision(
                            plan,
                            sources,
                            image,
                            working,
                            RecipeExecutorTestData.sourcePlanningLimits(),
                            () -> false);
            var target = sources.targets().getFirst().sourcePlan().orElseThrow();
            var pending =
                    (FiniteModelInputExecutor.InputAwaitingUpload)
                            FiniteModelInputExecutor.plan(
                                    plan,
                                    0,
                                    "70000000-0000-4000-8000-000000000001",
                                    target.fieldInputs().getFirst(),
                                    (RecipeExecutor.AvailableSource)
                                            provisions.get(target.target().key()),
                                    List.of());
            assertSame(
                    failure,
                    assertThrows(
                            failure.getClass(),
                            () ->
                                    session.imageUploads()
                                            .publish(
                                                    pending.rasterInput(),
                                                    pending.model(),
                                                    () -> false)));
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

    private static CapturedSourcePyramid fineSourceCapture() {
        var original = RecipeExecutorTestData.sourceRevision().pixelSource();
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        original.sourceAdapterId(),
                        original.credentialFreeDescriptorDigest(),
                        original.contentVersion(),
                        original.width() * 200,
                        original.height() * 200,
                        1,
                        1,
                        original.calibration());
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                RecipeExecutorTestData.IMAGE_ID,
                                SourceIdentityCodec.imageSourceIdentityDigest(source),
                                RecipeExecutorTestData.sourceRevision()
                                        .imageDataRevision()
                                        .imageDataContentDigest()),
                        source);
        return new CapturedSourcePyramid(
                revision,
                0,
                new SourceIdentityCodec.ScaleTranslate2D(0.005, 0.005, 0, 0),
                List.of(
                        new SourceLevel(
                                0,
                                new SourceViewGeometry.Dimensions(source.width(), source.height()),
                                new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0))));
    }

    private long imageFileCount() throws IOException {
        try (var paths = Files.walk(temporaryRoot)) {
            return paths.filter(
                            path ->
                                    Files.isRegularFile(path)
                                            && (path.toString().endsWith(".png")
                                                    || path.toString().endsWith(".tif")))
                    .count();
        }
    }

    private static CandidateGeneration emptyGeneration(
            FiniteModelInputExecutor.PreparedInput input) {
        var evidence =
                new CandidateGeneration.InputEvidence(
                        FiniteInferenceCodec.inputIdentity(input.request()),
                        ExecutionScopeCodec.scopeAcceptanceDigest(
                                input.plannedInput().acceptance()),
                        input.plannedInput().acceptance(),
                        input.request().rasterInput());
        return new CandidateGeneration(
                input.reservation(),
                new BackendExecutionCodec.FiniteCall("call-test"),
                evidence,
                new GenerationEvidenceCodec.GenerationCounts(0, 0, 0, 0),
                List.of());
    }
}

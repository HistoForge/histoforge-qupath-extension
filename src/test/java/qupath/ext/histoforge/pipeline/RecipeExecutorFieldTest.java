package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

/** Complete field-model target transaction tests through the real planners and committer. */
class RecipeExecutorFieldTest {

    @Test
    void fillExecutesAbsentOutputAndPresenceOnlySkipsItsCost() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> absent =
                        RecipeExecutorTestData.imageData(
                                "fill-absent", RecipeExecutorTestData.ROI_A);
                ImageData<?> present =
                        RecipeExecutorTestData.imageData(
                                "fill-present", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan absentPlan = RecipeExecutorTestData.plan(absent, module, action);
            AtomicInteger calls = new AtomicInteger();
            RecipeExecutor absentExecutor =
                    executor(
                            (input, stop) -> {
                                calls.incrementAndGet();
                                return RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        RecipeExecutorTestData.PARENT_ID,
                                                        RecipeExecutorTestData.square(
                                                                10, 10, 30, 30),
                                                        "Parent")));
                            });

            RecipeExecutor.Result added =
                    absentExecutor.execute(
                            absentPlan,
                            RecipeExecutorTestData.executionInputs(absentPlan, absent),
                            absent,
                            () -> false);

            assertEquals(1, calls.get());
            assertEquals(1, added.appliedTransactions().size());
            assertTrue(added.failures().isEmpty());
            assertEquals(1, added.appliedTransactions().getFirst().applyResult().addedObjects());
            assertEquals(List.of(RunResults.ScopeOutcomeType.PRODUCED), types(added));
            assertTrue(added.scopeResults().getFirst().changesHierarchy());
            PathObject committed = object(absent, RecipeExecutorTestData.PARENT_ID);
            ProvenanceCodec.ObjectProvenance provenance =
                    ProvenanceCodec.readMetadata(committed.getMetadata()).orElseThrow();
            assertEquals("parent", provenance.output().producer().stepId());
            assertEquals(
                    new ExecutionScopeCodec.FieldScope(
                            new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A)),
                    provenance.output().scope());

            PathObject existing =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.OLD_PARENT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(
                                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A)));
            present.getHierarchy().getRootObject().addChildObject(existing);
            ImageExecutionPlan presentPlan = RecipeExecutorTestData.plan(present, module, action);
            RecipeExecutor skipExecutor =
                    executor(
                            (input, stop) -> {
                                throw new AssertionError("Fill presence must skip the model call");
                            });

            RecipeExecutor.Result skipped =
                    skipExecutor.execute(
                            presentPlan,
                            RecipeExecutorTestData.executionInputs(presentPlan, present),
                            present,
                            () -> false);

            assertSame(existing, object(present, RecipeExecutorTestData.OLD_PARENT_ID));
            assertTrue(skipped.failures().isEmpty());
            assertFalse(skipped.appliedTransactions().getFirst().applyResult().changed());
            assertEquals(List.of(RunResults.ScopeOutcomeType.SKIPPED_PRESENT), types(skipped));
        }
    }

    @Test
    void redoValidEmptyRemovesCurrentOutputOnlyAfterSuccessfulStaging() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("redo-empty", RecipeExecutorTestData.ROI_A)) {
            PathObject existing =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.OLD_PARENT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(
                                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A)));
            image.getHierarchy().getRootObject().addChildObject(existing);
            var action =
                    RecipeExecutorTestData.allModels(true, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);

            RecipeExecutor.Result result =
                    executor((input, stop) -> RecipeExecutorTestData.generation(input, List.of()))
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertTrue(result.failures().isEmpty());
            assertEquals(1, result.appliedTransactions().getFirst().applyResult().removedObjects());
            assertEquals(List.of(RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY), types(result));
            assertTrue(result.scopeResults().getFirst().changesHierarchy());
            assertFalse(hasObject(image, RecipeExecutorTestData.OLD_PARENT_ID));
        }
    }

    @Test
    void independentRoiFailureDoesNotBlockLaterRoiAndInputIdsRemainActionLocal() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "independent-rois",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            var action =
                    RecipeExecutorTestData.allModels(
                            false,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            List<Integer> inputIds = new ArrayList<>();
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                inputIds.add(input.request().inputId());
                                if (input.plannedInput()
                                        .acceptance()
                                        .target()
                                        .key()
                                        .equals(
                                                new ExecutionScopeCodec.Roi(
                                                        RecipeExecutorTestData.ROI_A))) {
                                    throw new IllegalStateException("injected ROI A failure");
                                }
                                return RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        RecipeExecutorTestData.PARENT_ID,
                                                        RecipeExecutorTestData.square(
                                                                140, 10, 160, 30),
                                                        "Parent")));
                            });

            RecipeExecutor.Result result =
                    executor.execute(
                            plan,
                            RecipeExecutorTestData.executionInputs(plan, image),
                            image,
                            () -> false);

            assertEquals(List.of(0, 1), inputIds);
            assertEquals(1, result.failures().size());
            assertEquals(
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A),
                    result.failures().getFirst().targets().getFirst().key());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.FAILED,
                            RunResults.ScopeOutcomeType.PRODUCED),
                    types(result));
            assertEquals(
                    RunResults.FailureType.BACKEND,
                    result.scopeResults().getFirst().failure().orElseThrow().type());
            assertEquals(1, result.appliedTransactions().size());
            assertEquals(
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_B),
                    result.appliedTransactions().getFirst().targets().getFirst().key());
            assertTrue(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void failedSourceIsOwnedByItsRoiAndDoesNotBlockLaterRoi() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "source-failure",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            var action =
                    RecipeExecutorTestData.allModels(
                            false,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            RecipeExecutor.ExecutionInputs inputs =
                    RecipeExecutorTestData.executionInputs(plan, image);
            Map<ExecutionScopeCodec.RegionKey, RecipeExecutor.SourceProvision> provisions =
                    new LinkedHashMap<>(inputs.sourceProvisions());
            var roiA = new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A);
            var sourceFailure =
                    new RunResults.FailureDetail(
                            RunResults.FailureType.EXTRACTION,
                            "injected ROI A source capture failure");
            provisions.put(
                    roiA,
                    new RecipeExecutor.FailedSource(
                            new java.io.IOException(sourceFailure.message()), sourceFailure));
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.incrementAndGet();
                                        return RecipeExecutorTestData.generation(input, List.of());
                                    })
                            .execute(
                                    plan,
                                    new RecipeExecutor.ExecutionInputs(
                                            inputs.sourcePlan(),
                                            provisions,
                                            inputs.sourceRevision()),
                                    image,
                                    () -> false);

            assertEquals(1, calls.get());
            assertEquals(1, result.failures().size());
            assertEquals(roiA, result.failures().getFirst().targets().getFirst().key());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.FAILED,
                            RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY),
                    types(result));
            assertEquals(
                    RunResults.FailureType.EXTRACTION,
                    result.scopeResults().getFirst().failure().orElseThrow().type());
        }
    }

    @Test
    void terminalRecoverableSourceFailureDoesNotEnterTheFiniteRunner() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "recoverable-source-failure", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            RecipeExecutor.ExecutionInputs inputs =
                    RecipeExecutorTestData.executionInputs(plan, image);
            SourceInputPlan.TargetPlanningResult target = inputs.sourcePlan().targets().getFirst();
            SourceInputPlan.TargetSourcePlan finite = target.sourcePlan().orElseThrow();
            SourceInputPlan.TargetSourcePlan recoverable =
                    new SourceInputPlan.TargetSourcePlan(
                            finite.target(),
                            finite.sourceRevision(),
                            finite.levelZeroPixelsToSlide(),
                            SourceInputPlan.ExecutionProtocol.RECOVERABLE,
                            finite.sourceLevel(),
                            finite.sourceEnvelopeAtLevel(),
                            finite.artifact(),
                            finite.fieldInputs(),
                            finite.cropInputs());
            SourceInputPlan sourcePlan =
                    new SourceInputPlan(
                            List.of(
                                    new SourceInputPlan.TargetPlanningResult(
                                            target.target(), Optional.of(recoverable))));
            var failure =
                    new RunResults.FailureDetail(
                            RunResults.FailureType.UNSUPPORTED_MODULE,
                            "recoverable execution is not available before Phase 6");
            Map<ExecutionScopeCodec.RegionKey, RecipeExecutor.SourceProvision> provisions =
                    Map.of(
                            target.target().key(),
                            new RecipeExecutor.FailedSource(
                                    new UnsupportedOperationException(failure.message()), failure));
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.incrementAndGet();
                                        return RecipeExecutorTestData.generation(input, List.of());
                                    })
                            .execute(
                                    plan,
                                    new RecipeExecutor.ExecutionInputs(
                                            sourcePlan, provisions, inputs.sourceRevision()),
                                    image,
                                    () -> false);

            assertEquals(0, calls.get());
            assertEquals(List.of(RunResults.ScopeOutcomeType.FAILED), types(result));
            assertEquals(
                    RunResults.FailureType.UNSUPPORTED_MODULE,
                    result.scopeResults().getFirst().failure().orElseThrow().type());
        }
    }

    @Test
    void fatalApplyFailureRetainsPriorCurrentAndUnstartedRoiWork() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "fatal-apply",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B,
                        RecipeExecutorTestData.ROI_C)) {
            var action =
                    RecipeExecutorTestData.allModels(
                            false,
                            Set.of(
                                    RecipeExecutorTestData.ROI_A,
                                    RecipeExecutorTestData.ROI_B,
                                    RecipeExecutorTestData.ROI_C));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                int call = calls.getAndIncrement();
                                String objectId =
                                        switch (call) {
                                            case 0 -> RecipeExecutorTestData.PARENT_ID;
                                            case 1 -> RecipeExecutorTestData.CHILD_ID;
                                            case 2 -> RecipeExecutorTestData.OLD_PARENT_ID;
                                            default -> throw new AssertionError("unexpected call");
                                        };
                                double x =
                                        switch (call) {
                                            case 0 -> 10;
                                            case 1 -> 140;
                                            case 2 -> 232;
                                            default -> throw new AssertionError("unexpected call");
                                        };
                                return RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        objectId,
                                                        RecipeExecutorTestData.square(
                                                                x,
                                                                10,
                                                                x + (call == 2 ? 16 : 20),
                                                                30),
                                                        "Parent")));
                            });
            AtomicInteger applyEvents = new AtomicInteger();
            image.getHierarchy()
                    .addListener(
                            event -> {
                                if (event.getSource() instanceof ModelOutputCommitter
                                        && applyEvents.incrementAndGet() == 2) {
                                    throw new IllegalStateException("injected apply failure");
                                }
                            });

            RecipeExecutor.ExecutionFailedException failure =
                    assertThrows(
                            RecipeExecutor.ExecutionFailedException.class,
                            () ->
                                    executor.execute(
                                            plan,
                                            RecipeExecutorTestData.executionInputs(plan, image),
                                            image,
                                            () -> false));

            assertEquals(2, calls.get());
            assertEquals(RunResults.FailureType.CORRUPT_IMAGE_STATE, failure.failure().type());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.PRODUCED,
                            RunResults.ScopeOutcomeType.FAILED,
                            RunResults.ScopeOutcomeType.FAILED),
                    failure.scopeResults().stream().map(RunResults.ScopeWorkResult::type).toList());
            assertEquals(
                    new BackendExecutionCodec.FiniteCall("call-parent-0"),
                    failure.scopeResults().get(0).execution().orElseThrow());
            assertEquals(
                    new BackendExecutionCodec.FiniteCall("call-parent-1"),
                    failure.scopeResults().get(1).execution().orElseThrow());
            assertTrue(failure.scopeResults().get(2).execution().isEmpty());
        }
    }

    @Test
    void generationProjectionFailureOccursBeforeTheModelCall() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("projection", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan =
                    RecipeExecutorTestData.plan(
                            image, module, action, RecipeExecutorTestData.clientBuild(1));
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.incrementAndGet();
                                        return RecipeExecutorTestData.generation(input, List.of());
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(0, calls.get());
            assertTrue(result.appliedTransactions().isEmpty());
            assertEquals(1, result.failures().size());
            assertEquals(List.of(RunResults.ScopeOutcomeType.FAILED), types(result));
            assertEquals(
                    RunResults.FailureType.RESOURCE_LIMIT,
                    result.scopeResults().getFirst().failure().orElseThrow().type());
            assertTrue(
                    result.failures().getFirst().cause().getMessage().contains("generation store"));
        }
    }

    @Test
    void completedEmptyInputReleasesTheNextStepsProjectionCapacity() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.twoFieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "projection-transition", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan sizingPlan = RecipeExecutorTestData.plan(image, module, action);
            RecipeExecutor.ExecutionInputs sizingInputs =
                    RecipeExecutorTestData.executionInputs(sizingPlan, image);
            var targetPlan = sizingInputs.sourcePlan().targets().getFirst();
            var targetSource = targetPlan.sourcePlan().orElseThrow();
            var firstInput = targetSource.fieldInputs().getFirst();
            var artifact =
                    ((RecipeExecutor.PublishedSource)
                                    sizingInputs.sourceProvisions().get(targetPlan.target().key()))
                            .artifact();
            var sizingReservation =
                    FiniteModelInputExecutor.prepare(
                                    sizingPlan,
                                    0,
                                    "70000000-0000-4000-8000-000000000001",
                                    firstInput,
                                    artifact,
                                    List.of())
                            .reservation();
            long oneMaximumRecordBytes =
                    ImageDataStateCodec.requireGenerationStoreProjectionFits(
                            GenerationStoreCodec.emptyStore(),
                            List.of(),
                            List.of(),
                            List.of(sizingReservation),
                            sizingPlan.clientBuild().limits());
            ImageExecutionPlan tightPlan =
                    RecipeExecutorTestData.plan(
                            image,
                            module,
                            action,
                            RecipeExecutorTestData.clientBuild(oneMaximumRecordBytes));
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.incrementAndGet();
                                        return RecipeExecutorTestData.generation(input, List.of());
                                    })
                            .execute(
                                    tightPlan,
                                    RecipeExecutorTestData.executionInputs(tightPlan, image),
                                    image,
                                    () -> false);

            assertEquals(2, calls.get());
            assertTrue(result.failures().isEmpty());
            assertEquals(1, result.appliedTransactions().size());
            assertFalse(result.appliedTransactions().getFirst().applyResult().changed());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY,
                            RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY),
                    types(result));
        }
    }

    @Test
    void rejectsMismatchedSourceImageBeforeExecution() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("identity", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            RecipeExecutor.ExecutionInputs correct =
                    RecipeExecutorTestData.executionInputs(plan, image);
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                throw new AssertionError(
                                        "identity mismatch must fail before model execution");
                            });

            RasterInputCodec.ImageRevisionToken token =
                    correct.sourceRevision().imageDataRevision();
            RasterInputCodec.ActionSourceRevision wrongImage =
                    new RasterInputCodec.ActionSourceRevision(
                            new RasterInputCodec.ImageRevisionToken(
                                    "another-image",
                                    token.imageSourceDigest(),
                                    token.imageDataContentDigest()),
                            correct.sourceRevision().pixelSource());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            executor.execute(
                                    plan,
                                    new RecipeExecutor.ExecutionInputs(
                                            correct.sourcePlan(),
                                            correct.sourceProvisions(),
                                            wrongImage),
                                    image,
                                    () -> false));
        }
    }

    @Test
    void allowsSealedArtifactReuseAcrossConsumingActions() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("artifact-reuse", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            RecipeExecutor.ExecutionInputs correct =
                    RecipeExecutorTestData.executionInputs(plan, image);
            Map<ExecutionScopeCodec.RegionKey, RecipeExecutor.SourceProvision> reusedArtifacts =
                    new LinkedHashMap<>(correct.sourceProvisions());
            var entry = reusedArtifacts.entrySet().iterator().next();
            SourceArtifactPublicationCodec.SourceArtifactRef artifact =
                    ((RecipeExecutor.PublishedSource) entry.getValue()).artifact();
            entry.setValue(
                    new RecipeExecutor.PublishedSource(
                            new SourceArtifactPublicationCodec.SourceArtifactRef(
                                    artifact.artifactId(),
                                    "10000000-0000-4000-8000-000000000099",
                                    artifact.startRequestDigest(),
                                    artifact.bytesDigest(),
                                    artifact.manifestDigest(),
                                    artifact.container())));
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.incrementAndGet();
                                        return RecipeExecutorTestData.generation(input, List.of());
                                    })
                            .execute(
                                    plan,
                                    new RecipeExecutor.ExecutionInputs(
                                            correct.sourcePlan(),
                                            reusedArtifacts,
                                            correct.sourceRevision()),
                                    image,
                                    () -> false);

            assertEquals(1, calls.get());
            assertTrue(result.failures().isEmpty());
            assertEquals(List.of(RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY), types(result));
        }
    }

    @Test
    void crossModeRedoKeepsSuccessfulRoiWhenLaterRoiFails() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "cross-mode", RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)) {
            PathObject slideOutput =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            image.getHierarchy().getRootObject().addChildObject(slideOutput);
            var action =
                    RecipeExecutorTestData.allModels(
                            true,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        int call = calls.getAndIncrement();
                                        if (call == 1) {
                                            throw new IllegalStateException(
                                                    "injected second target failure");
                                        }
                                        return RecipeExecutorTestData.generation(
                                                input,
                                                List.of(
                                                        RecipeExecutorTestData.candidate(
                                                                RecipeExecutorTestData.PARENT_ID,
                                                                RecipeExecutorTestData.square(
                                                                        10, 10, 30, 30),
                                                                "Parent")));
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(2, calls.get());
            assertEquals(1, result.appliedTransactions().size());
            assertEquals(1, result.failures().size());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.PRODUCED,
                            RunResults.ScopeOutcomeType.FAILED),
                    types(result));
            assertEquals(
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A),
                    result.appliedTransactions().getFirst().targets().getFirst().key());
            assertEquals(
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_B),
                    result.failures().getFirst().targets().getFirst().key());
            assertEquals(1, result.appliedTransactions().getFirst().applyResult().removedObjects());
            assertFalse(hasObject(image, RecipeExecutorTestData.SLIDE_OBJECT_ID));
            assertTrue(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void crossModeRedoUsesLaterSuccessWhenTheFirstRoiSourceFails() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "cross-mode-source-failure",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            PathObject slideOutput =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            image.getHierarchy().getRootObject().addChildObject(slideOutput);
            var action =
                    RecipeExecutorTestData.allModels(
                            true,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            RecipeExecutor.ExecutionInputs inputs =
                    RecipeExecutorTestData.executionInputs(plan, image);
            Map<ExecutionScopeCodec.RegionKey, RecipeExecutor.SourceProvision> provisions =
                    new LinkedHashMap<>(inputs.sourceProvisions());
            var failure =
                    new RunResults.FailureDetail(
                            RunResults.FailureType.EXTRACTION,
                            "injected ROI A source capture failure");
            provisions.put(
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A),
                    new RecipeExecutor.FailedSource(
                            new java.io.IOException(failure.message()), failure));
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.incrementAndGet();
                                        return RecipeExecutorTestData.generation(
                                                input,
                                                List.of(
                                                        RecipeExecutorTestData.candidate(
                                                                RecipeExecutorTestData.PARENT_ID,
                                                                RecipeExecutorTestData.square(
                                                                        10, 10, 30, 30),
                                                                "Parent")));
                                    })
                            .execute(
                                    plan,
                                    new RecipeExecutor.ExecutionInputs(
                                            inputs.sourcePlan(),
                                            provisions,
                                            inputs.sourceRevision()),
                                    image,
                                    () -> false);

            assertEquals(1, calls.get());
            assertEquals(1, result.appliedTransactions().size());
            assertEquals(1, result.failures().size());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.FAILED,
                            RunResults.ScopeOutcomeType.PRODUCED),
                    types(result));
            assertTrue(result.scopeResults().getFirst().execution().isEmpty());
            assertFalse(hasObject(image, RecipeExecutorTestData.SLIDE_OBJECT_ID));
            assertTrue(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void crossModeFailureDoesNotRemoveAnotherTargetsProducer() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.twoFieldModule();
        String secondSlideObjectId = "30000000-0000-4000-8000-000000000007";
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "cross-mode-producer-isolation",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            PathObject parentSlide =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            PathObject secondSlide =
                    RecipeExecutorTestData.attributed(
                            secondSlideObjectId,
                            "Second",
                            RecipeExecutorTestData.square(140, 10, 160, 30),
                            "second",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            image.getHierarchy().getRootObject().addChildObjects(List.of(parentSlide, secondSlide));
            ActionSelectionCodec.SelectedModels work =
                    new ActionSelectionCodec.SelectedModels(
                            new ActionSelectionCodec.RoiRegions(
                                    Set.of(
                                            RecipeExecutorTestData.ROI_A,
                                            RecipeExecutorTestData.ROI_B)),
                            List.of(
                                    new ActionSelectionCodec.StepTargets(
                                            "parent",
                                            new ActionSelectionCodec.TargetSelection(
                                                    List.of(
                                                            new ActionSelectionCodec.RoiTarget(
                                                                    RecipeExecutorTestData
                                                                            .ROI_A)))),
                                    new ActionSelectionCodec.StepTargets(
                                            "second",
                                            new ActionSelectionCodec.TargetSelection(
                                                    List.of(
                                                            new ActionSelectionCodec.RoiTarget(
                                                                    RecipeExecutorTestData
                                                                            .ROI_B))))));
            ImageExecutionPlan plan =
                    RecipeExecutorTestData.plan(
                            image, module, new ActionSelectionCodec.Redo(work, true));
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        if (calls.getAndIncrement() == 1) {
                                            throw new IllegalStateException(
                                                    "injected second producer failure");
                                        }
                                        return RecipeExecutorTestData.generation(
                                                input,
                                                List.of(
                                                        RecipeExecutorTestData.candidate(
                                                                RecipeExecutorTestData.PARENT_ID,
                                                                RecipeExecutorTestData.square(
                                                                        10, 10, 30, 30),
                                                                "Parent")));
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(2, calls.get());
            assertEquals(1, result.appliedTransactions().size());
            assertEquals(1, result.failures().size());
            assertFalse(hasObject(image, RecipeExecutorTestData.SLIDE_OBJECT_ID));
            assertSame(secondSlide, object(image, secondSlideObjectId));
            assertTrue(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void crossModeRedoRetainsSlideOutputWhenEveryRoiFails() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "cross-mode-all-fail",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            PathObject slideOutput =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            image.getHierarchy().getRootObject().addChildObject(slideOutput);
            var action =
                    RecipeExecutorTestData.allModels(
                            true,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.incrementAndGet();
                                        throw new IllegalStateException("injected target failure");
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(2, calls.get());
            assertTrue(result.appliedTransactions().isEmpty());
            assertEquals(2, result.failures().size());
            assertEquals(
                    List.of(RunResults.ScopeOutcomeType.FAILED, RunResults.ScopeOutcomeType.FAILED),
                    types(result));
            assertSame(slideOutput, object(image, RecipeExecutorTestData.SLIDE_OBJECT_ID));
        }
    }

    @Test
    void crossModeRedoReplacesSlideOnceAndKeepsEverySuccessfulRoi() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "cross-mode-all-succeed",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            PathObject slideOutput =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            image.getHierarchy().getRootObject().addChildObject(slideOutput);
            var action =
                    RecipeExecutorTestData.allModels(
                            true,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        int call = calls.getAndIncrement();
                                        return RecipeExecutorTestData.generation(
                                                input,
                                                List.of(
                                                        RecipeExecutorTestData.candidate(
                                                                call == 0
                                                                        ? RecipeExecutorTestData
                                                                                .PARENT_ID
                                                                        : RecipeExecutorTestData
                                                                                .CHILD_ID,
                                                                RecipeExecutorTestData.square(
                                                                        call == 0 ? 10 : 140,
                                                                        10,
                                                                        call == 0 ? 30 : 160,
                                                                        30),
                                                                "Parent")));
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(2, calls.get());
            assertTrue(result.failures().isEmpty());
            assertEquals(2, result.appliedTransactions().size());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.PRODUCED,
                            RunResults.ScopeOutcomeType.PRODUCED),
                    types(result));
            assertEquals(1, result.appliedTransactions().get(0).applyResult().removedObjects());
            assertEquals(0, result.appliedTransactions().get(1).applyResult().removedObjects());
            assertFalse(hasObject(image, RecipeExecutorTestData.SLIDE_OBJECT_ID));
            assertTrue(hasObject(image, RecipeExecutorTestData.PARENT_ID));
            assertTrue(hasObject(image, RecipeExecutorTestData.CHILD_ID));
        }
    }

    @Test
    void conflictingModeRepairDoesNotRemoveTheFirstNewRoiFromTheSecondTransaction()
            throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "conflicting-mode-repair",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            PathObject slideOutput =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            PathObject oldRoiAOutput =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.OLD_PARENT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(
                                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A)));
            image.getHierarchy()
                    .getRootObject()
                    .addChildObjects(List.of(slideOutput, oldRoiAOutput));
            var action =
                    RecipeExecutorTestData.allModels(
                            true,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        int call = calls.getAndIncrement();
                                        return RecipeExecutorTestData.generation(
                                                input,
                                                List.of(
                                                        RecipeExecutorTestData.candidate(
                                                                call == 0
                                                                        ? RecipeExecutorTestData
                                                                                .PARENT_ID
                                                                        : RecipeExecutorTestData
                                                                                .CHILD_ID,
                                                                RecipeExecutorTestData.square(
                                                                        call == 0 ? 10 : 140,
                                                                        10,
                                                                        call == 0 ? 30 : 160,
                                                                        30),
                                                                "Parent")));
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(2, calls.get());
            assertTrue(result.failures().isEmpty());
            assertEquals(2, result.appliedTransactions().size());
            assertEquals(2, result.appliedTransactions().get(0).applyResult().removedObjects());
            assertEquals(0, result.appliedTransactions().get(1).applyResult().removedObjects());
            assertFalse(hasObject(image, RecipeExecutorTestData.SLIDE_OBJECT_ID));
            assertFalse(hasObject(image, RecipeExecutorTestData.OLD_PARENT_ID));
            assertTrue(hasObject(image, RecipeExecutorTestData.PARENT_ID));
            assertTrue(hasObject(image, RecipeExecutorTestData.CHILD_ID));
        }
    }

    @Test
    void crossModeCancellationRetainsPriorWorkForWholeImageDiscard() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "cross-mode-cancel",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            PathObject slideOutput =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            image.getHierarchy().getRootObject().addChildObject(slideOutput);
            var action =
                    RecipeExecutorTestData.allModels(
                            true,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();
            AtomicBoolean cancelled = new AtomicBoolean();
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                int call = calls.incrementAndGet();
                                var generation =
                                        RecipeExecutorTestData.generation(
                                                input,
                                                List.of(
                                                        RecipeExecutorTestData.candidate(
                                                                RecipeExecutorTestData.PARENT_ID,
                                                                RecipeExecutorTestData.square(
                                                                        10, 10, 30, 30),
                                                                "Parent")));
                                if (call == 2) {
                                    cancelled.set(true);
                                }
                                return generation;
                            });

            RecipeExecutor.ExecutionCancelledException failure =
                    assertThrows(
                            RecipeExecutor.ExecutionCancelledException.class,
                            () ->
                                    executor.execute(
                                            plan,
                                            RecipeExecutorTestData.executionInputs(plan, image),
                                            image,
                                            cancelled::get));

            assertEquals(2, calls.get());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.PRODUCED,
                            RunResults.ScopeOutcomeType.CANCELLED),
                    failure.scopeResults().stream().map(RunResults.ScopeWorkResult::type).toList());
            assertFalse(hasObject(image, RecipeExecutorTestData.SLIDE_OBJECT_ID));
            assertTrue(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void fatalCrossModeApplyFailureStopsBeforeTheNextRoiCall() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "cross-mode-apply",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            PathObject slideOutput =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            image.getHierarchy().getRootObject().addChildObject(slideOutput);
            var action =
                    RecipeExecutorTestData.allModels(
                            true,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                int call = calls.getAndIncrement();
                                return RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        call == 0
                                                                ? RecipeExecutorTestData.PARENT_ID
                                                                : RecipeExecutorTestData.CHILD_ID,
                                                        RecipeExecutorTestData.square(
                                                                call == 0 ? 10 : 140,
                                                                10,
                                                                call == 0 ? 30 : 160,
                                                                30),
                                                        "Parent")));
                            });
            image.getHierarchy()
                    .addListener(
                            event -> {
                                if (event.getSource() instanceof ModelOutputCommitter) {
                                    throw new IllegalStateException(
                                            "injected atomic apply failure");
                                }
                            });

            RecipeExecutor.ExecutionFailedException failure =
                    assertThrows(
                            RecipeExecutor.ExecutionFailedException.class,
                            () ->
                                    executor.execute(
                                            plan,
                                            RecipeExecutorTestData.executionInputs(plan, image),
                                            image,
                                            () -> false));

            assertEquals(1, calls.get());
            assertEquals(RunResults.FailureType.CORRUPT_IMAGE_STATE, failure.failure().type());
            assertEquals(
                    List.of(RunResults.ScopeOutcomeType.FAILED, RunResults.ScopeOutcomeType.FAILED),
                    failure.scopeResults().stream().map(RunResults.ScopeWorkResult::type).toList());
            assertEquals(
                    new BackendExecutionCodec.FiniteCall("call-parent-0"),
                    failure.scopeResults().getFirst().execution().orElseThrow());
            assertTrue(failure.scopeResults().get(1).execution().isEmpty());
        }
    }

    @Test
    void cancellationAfterModelCompletionStillDiscardsTheTargetDelta() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("cancel", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicBoolean cancelled = new AtomicBoolean();
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                var generation =
                                        RecipeExecutorTestData.generation(
                                                input,
                                                List.of(
                                                        RecipeExecutorTestData.candidate(
                                                                RecipeExecutorTestData.PARENT_ID,
                                                                RecipeExecutorTestData.square(
                                                                        10, 10, 30, 30),
                                                                "Parent")));
                                cancelled.set(true);
                                return generation;
                            });

            RecipeExecutor.ExecutionCancelledException failure =
                    assertThrows(
                            RecipeExecutor.ExecutionCancelledException.class,
                            () ->
                                    executor.execute(
                                            plan,
                                            RecipeExecutorTestData.executionInputs(plan, image),
                                            image,
                                            cancelled::get));
            assertEquals(
                    List.of(RunResults.ScopeOutcomeType.CANCELLED),
                    failure.scopeResults().stream().map(RunResults.ScopeWorkResult::type).toList());
            assertFalse(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void failureAfterAcceptanceRetainsTheCallAndItsTypedCause() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "accepted-failure", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                BackendExecutionCodec.FiniteCall execution =
                                        (BackendExecutionCodec.FiniteCall)
                                                RecipeExecutorTestData.generation(input, List.of())
                                                        .execution();
                                InferenceException expired =
                                        new InferenceException(
                                                410,
                                                new GatewayErrorCodec.Error(
                                                        1,
                                                        GatewayErrorCodec.Code.CALL_EXPIRED,
                                                        "finite result expired",
                                                        Optional.empty(),
                                                        Optional.empty()));
                                throw new ModelGatewayExecutor.AcceptedFiniteCallFailure(
                                        execution, expired);
                            });

            RecipeExecutor.Result result =
                    executor.execute(
                            plan,
                            RecipeExecutorTestData.executionInputs(plan, image),
                            image,
                            () -> false);

            RunResults.ScopeWorkResult outcome = result.scopeResults().getFirst();
            assertEquals(RunResults.ScopeOutcomeType.FAILED, outcome.type());
            assertEquals(RunResults.FailureType.TIMEOUT, outcome.failure().orElseThrow().type());
            assertEquals(
                    new BackendExecutionCodec.FiniteCall("call-parent-0"),
                    outcome.execution().orElseThrow());
            assertFalse(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void cancellationAfterAcceptanceRetainsTheAbandonedCall() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "accepted-cancellation", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                BackendExecutionCodec.FiniteCall execution =
                                        (BackendExecutionCodec.FiniteCall)
                                                RecipeExecutorTestData.generation(input, List.of())
                                                        .execution();
                                throw new ModelGatewayExecutor.AcceptedFiniteCallCancellation(
                                        execution,
                                        new CancellationException("finite call cancelled"));
                            });

            RecipeExecutor.ExecutionCancelledException cancelled =
                    assertThrows(
                            RecipeExecutor.ExecutionCancelledException.class,
                            () ->
                                    executor.execute(
                                            plan,
                                            RecipeExecutorTestData.executionInputs(plan, image),
                                            image,
                                            () -> false));

            RunResults.ScopeWorkResult outcome = cancelled.scopeResults().getFirst();
            assertEquals(RunResults.ScopeOutcomeType.CANCELLED, outcome.type());
            assertEquals(RunResults.FailureType.CANCELLED, outcome.failure().orElseThrow().type());
            assertEquals(
                    new BackendExecutionCodec.FiniteCall("call-parent-0"),
                    outcome.execution().orElseThrow());
            assertFalse(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void interruptionBeforeTheNextAcceptanceRetainsPriorCallAndCancelsRemainingWork()
            throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.twoFieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "pre-accept-interruption", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                if (calls.getAndIncrement() == 0) {
                                    return RecipeExecutorTestData.generation(input, List.of());
                                }
                                throw new InterruptedException("interrupted before acceptance");
                            });

            try {
                RecipeExecutor.ExecutionCancelledException cancelled =
                        assertThrows(
                                RecipeExecutor.ExecutionCancelledException.class,
                                () ->
                                        executor.execute(
                                                plan,
                                                RecipeExecutorTestData.executionInputs(plan, image),
                                                image,
                                                () -> false));

                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(2, calls.get());
                assertEquals(
                        List.of(
                                RunResults.ScopeOutcomeType.CANCELLED,
                                RunResults.ScopeOutcomeType.CANCELLED),
                        cancelled.scopeResults().stream()
                                .map(RunResults.ScopeWorkResult::type)
                                .toList());
                assertEquals(
                        new BackendExecutionCodec.FiniteCall("call-parent-0"),
                        cancelled.scopeResults().getFirst().execution().orElseThrow());
                assertTrue(cancelled.scopeResults().get(1).execution().isEmpty());
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static RecipeExecutor executor(RecipeExecutor.InputRunner runner) {
        AtomicInteger generations = new AtomicInteger();
        return new RecipeExecutor(
                runner,
                () -> "60000000-0000-4000-8000-%012d".formatted(generations.incrementAndGet()),
                Clock.fixed(Instant.parse("2026-08-16T12:34:56.789Z"), ZoneOffset.UTC));
    }

    private static PathObject object(ImageData<?> image, String id) {
        return image.getHierarchy().getAllObjects(false).stream()
                .filter(value -> value.getID().toString().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasObject(ImageData<?> image, String id) {
        return image.getHierarchy().getAllObjects(false).stream()
                .anyMatch(value -> value.getID().toString().equals(id));
    }

    private static List<RunResults.ScopeOutcomeType> types(RecipeExecutor.Result result) {
        return result.scopeResults().stream().map(RunResults.ScopeWorkResult::type).toList();
    }
}

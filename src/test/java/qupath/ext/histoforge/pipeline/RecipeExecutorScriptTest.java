package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

/** Recipe-barrier and dynamic parent-scope transaction tests. */
class RecipeExecutorScriptTest {

    @Test
    void explicitMissingParentIsSkippedWithoutInventingAConcreteOutput() throws Exception {
        ModuleSnapshot module =
                RecipeExecutorTestData.withActivatedModels(
                        RecipeExecutorTestData.scriptedModule(false), "child");
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "missing-explicit-parent", RecipeExecutorTestData.ROI_A)) {
            ExecutionScopeCodec.Roi region =
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A);
            var action =
                    RecipeExecutorTestData.selectedChildFill(
                            RecipeExecutorTestData.ROI_A,
                            new qupath.ext.histoforge.contract.ActionSelectionCodec.ParentTarget(
                                    region, RecipeExecutorTestData.MANUAL_PARENT_ID));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        throw new AssertionError(
                                                "ineligible parent must not invoke the model");
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertTrue(result.failures().isEmpty());
            assertFalse(result.appliedTransactions().getFirst().applyResult().changed());
            assertEquals(List.of(RunResults.ScopeOutcomeType.SKIPPED_INELIGIBLE), types(result));
            assertInstanceOf(
                    RunResults.RequestedTarget.class, result.scopeResults().getFirst().key());
        }
    }

    @Test
    void broadChildFillWithoutSelectedPrerequisiteIsBlocked() throws Exception {
        ModuleSnapshot module =
                RecipeExecutorTestData.withActivatedModels(
                        RecipeExecutorTestData.scriptedModule(false), "child");
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "blocked-prerequisite", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.selectedChildFill(
                            RecipeExecutorTestData.ROI_A,
                            new qupath.ext.histoforge.contract.ActionSelectionCodec.RoiTarget(
                                    RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        throw new AssertionError(
                                                "blocked prerequisite must not invoke the model");
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertTrue(result.appliedTransactions().isEmpty());
            assertEquals(List.of(RunResults.ScopeOutcomeType.BLOCKED_PREREQUISITE), types(result));
            assertEquals(
                    RunResults.FailureType.PREREQUISITE,
                    result.scopeResults().getFirst().failure().orElseThrow().type());
        }
    }

    @Test
    void successfulEmptyUpstreamReportsNoChildParents() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.scriptedModule(false);
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "no-child-parents", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);

            RecipeExecutor.Result result =
                    executor((input, stop) -> RecipeExecutorTestData.generation(input, List.of()))
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertTrue(result.failures().isEmpty());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY,
                            RunResults.ScopeOutcomeType.SKIPPED_NO_PARENTS),
                    types(result));
        }
    }

    @Test
    void scriptRunsAfterSelectedUpstreamFillSkipsPresentOutput() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.scriptedModule(false);
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "script-after-present", RecipeExecutorTestData.ROI_A)) {
            ExecutionScopeCodec.RegionKey region =
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A);
            PathObject parent =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.PARENT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(region));
            image.getHierarchy().getRootObject().addChildObject(parent);
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            List<String> calls = new ArrayList<>();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.add(input.request().producer().stepId());
                                        return RecipeExecutorTestData.generation(input, List.of());
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(List.of("child"), calls);
            assertEquals("Eligible", parent.getPathClass().toString());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.SKIPPED_PRESENT,
                            RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY),
                    types(result));
            assertTrue(result.appliedTransactions().getFirst().applyResult().changed());
        }
    }

    @Test
    void broadChildOnlyRedoWithNoCurrentParentsIsASuccessfulNoOp() throws Exception {
        ModuleSnapshot module =
                RecipeExecutorTestData.withActivatedModels(
                        RecipeExecutorTestData.scriptedModule(false), "child");
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "redo-no-current-parents", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.selectedChildRedo(
                            RecipeExecutorTestData.ROI_A,
                            new qupath.ext.histoforge.contract.ActionSelectionCodec.RoiTarget(
                                    RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        throw new AssertionError(
                                                "child-only Redo without parents must not infer");
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertTrue(result.failures().isEmpty());
            assertFalse(result.appliedTransactions().getFirst().applyResult().changed());
            assertEquals(List.of(RunResults.ScopeOutcomeType.SKIPPED_NO_PARENTS), types(result));
        }
    }

    @Test
    void broadChildFailureReportsEveryResolvedParentScope() throws Exception {
        ModuleSnapshot module =
                RecipeExecutorTestData.withActivatedModels(
                        RecipeExecutorTestData.scriptedModule(false), "child");
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "resolved-child-failure", RecipeExecutorTestData.ROI_A)) {
            ExecutionScopeCodec.RegionKey region =
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A);
            PathObject first =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.PARENT_ID,
                            "Eligible",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(region));
            PathObject second =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.OLD_PARENT_ID,
                            "Eligible",
                            RecipeExecutorTestData.square(40, 10, 60, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(region));
            image.getHierarchy().getRootObject().addChildObjects(List.of(first, second));
            var action =
                    RecipeExecutorTestData.selectedChildRedo(
                            RecipeExecutorTestData.ROI_A,
                            new qupath.ext.histoforge.contract.ActionSelectionCodec.RoiTarget(
                                    RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            AtomicInteger calls = new AtomicInteger();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        if (calls.incrementAndGet() == 2) {
                                            throw new IllegalStateException(
                                                    "injected second parent failure");
                                        }
                                        return RecipeExecutorTestData.generation(input, List.of());
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(2, calls.get());
            assertEquals(
                    List.of(RunResults.ScopeOutcomeType.FAILED, RunResults.ScopeOutcomeType.FAILED),
                    types(result));
            assertTrue(
                    result.scopeResults().stream()
                            .allMatch(value -> value.key() instanceof RunResults.ExecutionOutput));
            assertTrue(result.scopeResults().getFirst().execution().isPresent());
            assertTrue(result.scopeResults().get(1).execution().isEmpty());
            assertSame(first, object(image, RecipeExecutorTestData.PARENT_ID));
            assertSame(second, object(image, RecipeExecutorTestData.OLD_PARENT_ID));
        }
    }

    @Test
    void scriptReclassifiedCandidateBecomesTheDynamicChildScope() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.scriptedModule(false);
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("script-child", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            List<String> calls = new ArrayList<>();
            RecipeExecutor executor =
                    executor(
                            (input, stop) -> {
                                String model = input.request().producer().stepId();
                                calls.add(model);
                                if (model.equals("parent")) {
                                    return RecipeExecutorTestData.generation(
                                            input,
                                            List.of(
                                                    RecipeExecutorTestData.candidate(
                                                            RecipeExecutorTestData.PARENT_ID,
                                                            RecipeExecutorTestData.square(
                                                                    10, 10, 40, 40),
                                                            "Parent")));
                                }
                                ExecutionScopeCodec.ParentScope scope =
                                        (ExecutionScopeCodec.ParentScope)
                                                input.plannedInput().output().scope();
                                assertEquals(RecipeExecutorTestData.PARENT_ID, scope.parentId());
                                assertTrue(
                                        RecipeExecutorTestData.square(10, 10, 40, 40)
                                                .equalsTopo(
                                                        input.plannedInput()
                                                                .acceptance()
                                                                .parentGeometrySlide()
                                                                .orElseThrow()));
                                return RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        RecipeExecutorTestData.CHILD_ID,
                                                        RecipeExecutorTestData.square(
                                                                15, 15, 20, 20),
                                                        "Child")));
                            });

            RecipeExecutor.Result result =
                    executor.execute(
                            plan,
                            RecipeExecutorTestData.executionInputs(plan, image),
                            image,
                            () -> false);

            assertTrue(result.failures().isEmpty(), () -> result.failures().toString());
            assertEquals(List.of("parent", "child"), calls);
            assertEquals(2, result.appliedTransactions().getFirst().applyResult().addedObjects());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.PRODUCED,
                            RunResults.ScopeOutcomeType.PRODUCED),
                    types(result));
            PathObject parent = object(image, RecipeExecutorTestData.PARENT_ID);
            PathObject child = object(image, RecipeExecutorTestData.CHILD_ID);
            assertEquals("Eligible", parent.getPathClass().toString());
            assertSame(parent, child.getParent());
            assertEquals(
                    RecipeExecutorTestData.PARENT_ID,
                    ((ExecutionScopeCodec.ParentScope)
                                    ProvenanceCodec.readMetadata(child.getMetadata())
                                            .orElseThrow()
                                            .output()
                                            .scope())
                            .parentId());
        }
    }

    @Test
    void downstreamFailureLeavesTheEntireOldTargetTransactionUnchanged() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.scriptedModule(false);
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "downstream-failure", RecipeExecutorTestData.ROI_A)) {
            ExecutionScopeCodec.Roi region =
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A);
            PathObject oldParent =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.OLD_PARENT_ID,
                            "Eligible",
                            RecipeExecutorTestData.square(10, 10, 40, 40),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(region));
            PathObject oldChild =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.OLD_CHILD_ID,
                            "Child",
                            RecipeExecutorTestData.square(15, 15, 20, 20),
                            "child",
                            new ExecutionScopeCodec.ParentScope(
                                    region, RecipeExecutorTestData.OLD_PARENT_ID));
            oldParent.addChildObject(oldChild);
            image.getHierarchy().getRootObject().addChildObject(oldParent);
            var action =
                    RecipeExecutorTestData.allModels(true, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        if (input.request().producer().stepId().equals("child")) {
                                            throw new IllegalStateException(
                                                    "injected downstream failure");
                                        }
                                        return RecipeExecutorTestData.generation(
                                                input,
                                                List.of(
                                                        RecipeExecutorTestData.candidate(
                                                                RecipeExecutorTestData.PARENT_ID,
                                                                RecipeExecutorTestData.square(
                                                                        50, 10, 80, 40),
                                                                "Parent")));
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertTrue(result.appliedTransactions().isEmpty());
            assertEquals(1, result.failures().size());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.FAILED,
                            RunResults.ScopeOutcomeType.FAILED,
                            RunResults.ScopeOutcomeType.FAILED),
                    types(result));
            assertSame(oldParent, object(image, RecipeExecutorTestData.OLD_PARENT_ID));
            assertSame(oldChild, object(image, RecipeExecutorTestData.OLD_CHILD_ID));
            assertSame(oldParent, oldChild.getParent());
            assertFalse(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void fillScriptCannotInvalidateAnExistingChildScope() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.scriptedModule(true);
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "fill-requires-redo", RecipeExecutorTestData.ROI_A)) {
            ExecutionScopeCodec.Roi region =
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A);
            PathObject manualParent =
                    RecipeExecutorTestData.manual(
                            RecipeExecutorTestData.MANUAL_PARENT_ID,
                            "Eligible",
                            RecipeExecutorTestData.square(10, 10, 40, 40));
            PathObject child =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.OLD_CHILD_ID,
                            "Child",
                            RecipeExecutorTestData.square(15, 15, 20, 20),
                            "child",
                            new ExecutionScopeCodec.ParentScope(
                                    region, RecipeExecutorTestData.MANUAL_PARENT_ID));
            manualParent.addChildObject(child);
            image.getHierarchy().getRootObject().addChildObject(manualParent);
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
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
                                                                        50, 10, 80, 40),
                                                                "Eligible")));
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(1, calls.get());
            assertTrue(result.appliedTransactions().isEmpty());
            assertInstanceOf(
                    RecipeExecutor.FillRequiresRedoException.class,
                    result.failures().getFirst().cause());
            assertEquals(
                    List.of(RunResults.ScopeOutcomeType.FAILED, RunResults.ScopeOutcomeType.FAILED),
                    types(result));
            assertEquals(
                    RunResults.FailureType.FILL_REQUIRES_REDO,
                    result.failures().getFirst().failure().type());
            assertSame(manualParent, object(image, RecipeExecutorTestData.MANUAL_PARENT_ID));
            assertEquals("Eligible", manualParent.getPathClass().toString());
            assertSame(child, object(image, RecipeExecutorTestData.OLD_CHILD_ID));
            assertSame(manualParent, child.getParent());
            assertFalse(hasObject(image, RecipeExecutorTestData.PARENT_ID));
        }
    }

    @Test
    void redoClearsAnOrphanedChildWithoutCallingTheChildModel() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.scriptedModule(false);
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("clear-orphan", RecipeExecutorTestData.ROI_A)) {
            ExecutionScopeCodec.Roi region =
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A);
            PathObject orphanedChild =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.OLD_CHILD_ID,
                            "Child",
                            RecipeExecutorTestData.square(15, 15, 20, 20),
                            "child",
                            new ExecutionScopeCodec.ParentScope(
                                    region, RecipeExecutorTestData.OLD_PARENT_ID));
            image.getHierarchy().getRootObject().addChildObject(orphanedChild);
            var action =
                    RecipeExecutorTestData.allModels(true, Set.of(RecipeExecutorTestData.ROI_A));
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);
            List<String> calls = new ArrayList<>();

            RecipeExecutor.Result result =
                    executor(
                                    (input, stop) -> {
                                        calls.add(input.request().producer().stepId());
                                        return RecipeExecutorTestData.generation(input, List.of());
                                    })
                            .execute(
                                    plan,
                                    RecipeExecutorTestData.executionInputs(plan, image),
                                    image,
                                    () -> false);

            assertEquals(List.of("parent"), calls);
            assertTrue(result.failures().isEmpty());
            assertEquals(1, result.appliedTransactions().getFirst().applyResult().removedObjects());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY,
                            RunResults.ScopeOutcomeType.CLEARED_ORPHANED),
                    types(result));
            assertFalse(hasObject(image, RecipeExecutorTestData.OLD_CHILD_ID));
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

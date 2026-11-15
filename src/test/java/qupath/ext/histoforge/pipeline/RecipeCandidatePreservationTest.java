package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

/** The recipe prepares suppression snapshots only when its captured action enables the filter. */
class RecipeCandidatePreservationTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void fillAndRedoUseTheirCapturedSuppressionSetting(boolean redo, boolean enabled)
            throws Exception {
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "suppression-setting", RecipeExecutorTestData.ROI_A)) {
            PathObject manual =
                    RecipeExecutorTestData.manual(
                            RecipeExecutorTestData.MANUAL_PARENT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30));
            image.getHierarchy().getRootObject().addChildObject(manual);
            var work =
                    new ActionSelectionCodec.AllModels(
                            new ActionSelectionCodec.RoiRegions(
                                    Set.of(RecipeExecutorTestData.ROI_A)));
            ActionSelectionCodec.ImageAction action =
                    redo
                            ? new ActionSelectionCodec.Redo(work, enabled)
                            : new ActionSelectionCodec.Fill(work, enabled);
            var plan =
                    RecipeExecutorTestData.plan(
                            image, RecipeExecutorTestData.fieldModule(), action);
            AtomicInteger calls = new AtomicInteger();
            var executor =
                    new RecipeExecutor(
                            (input, stop) -> {
                                calls.incrementAndGet();
                                assertEquals(enabled ? 1 : 0, input.existingObjects().size());
                                assertEquals(
                                        enabled,
                                        input.reservation().removeOverlappingSameClassDuplicates());
                                var request = input.request();
                                var execution =
                                        new BackendExecutionCodec.FiniteCall(
                                                "call-suppression-fixture");
                                var meta =
                                        new FiniteInferenceCodec.BackendResultMeta(
                                                FiniteInferenceCodec.SCHEMA_VERSION,
                                                request.actionId(),
                                                request.outputGenerationId(),
                                                execution.backendCallId(),
                                                request.inputId(),
                                                request.producer(),
                                                request.expectedBackend(),
                                                FiniteInferenceCodec.inputIdentity(request),
                                                FiniteInferenceCodec.CoordinateSpace.RASTER_LOCAL);
                                var features =
                                        new InferenceFeatureCodec.FeatureCollection(
                                                List.of(
                                                        new InferenceFeatureCodec.Feature(
                                                                "1".repeat(64),
                                                                RecipeExecutorTestData.square(
                                                                        10, 10, 30, 30),
                                                                "Parent")));
                                return CandidatePipeline.generateFinite(
                                        request,
                                        new FiniteInferenceCodec.InferenceResult(meta, features),
                                        input.plannedInput(),
                                        execution,
                                        input.reservation(),
                                        input.existingObjects());
                            },
                            () -> UUID.randomUUID().toString(),
                            Clock.systemUTC());

            var result =
                    executor.execute(
                            plan,
                            RecipeExecutorTestData.executionInputs(plan, image),
                            image,
                            () -> false);

            assertEquals(1, calls.get());
            assertTrue(result.failures().isEmpty(), result.failures().toString());
            assertEquals(
                    enabled ? 0 : 1,
                    result.appliedTransactions().getFirst().applyResult().addedObjects());
            assertSame(
                    manual,
                    image.getHierarchy().getAllObjects(false).stream()
                            .filter(object -> object.getID().equals(manual.getID()))
                            .findFirst()
                            .orElseThrow());
            assertTrue(manual.getMetadata().isEmpty());
        }
    }
}

package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourceInputPlanner;
import qupath.lib.images.ImageData;

class DurableRequestSharderTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedFamiliesKeepWholeStepIdsAndReproduceFromCanonicalOutputs(boolean captured) {
        Planning fixture = planning(coreHaloModule(Optional.of(0)));
        var source = fixture.source(captured);
        List<SourceInputPlan.PlannedModelInput> canonical =
                fixture.inputs(
                        List.of(
                                RecipeExecutorTestData.square(5, 5, 12, 12),
                                RecipeExecutorTestData.square(5, 5, 45, 45),
                                RecipeExecutorTestData.square(50, 5, 60, 15)));
        List<SourceInputPlan.PlannedModelInput> scrambled =
                List.of(canonical.get(2), canonical.get(0), canonical.get(1));

        DurableRequestSharder.StepPlan initial =
                DurableRequestSharder.initial(
                        fixture.plan(),
                        fixture.step(),
                        scrambled,
                        source,
                        Integer.MAX_VALUE,
                        ids('7'),
                        ids('6'));

        assertEquals(List.of(0, 1, 2), inputIds(initial.inputs()));
        assertEquals(
                canonical.stream().map(SourceInputPlan.PlannedModelInput::output).toList(),
                initial.inputs().stream().map(value -> value.requestInput().output()).toList());
        assertEquals(2, initial.shards().size());
        assertEquals(
                List.of(0, 2),
                initial.shards().getFirst().request().inputs().stream()
                        .map(value -> value.inputId())
                        .toList());
        assertEquals(
                List.of(1),
                initial.shards().get(1).request().inputs().stream()
                        .map(value -> value.inputId())
                        .toList());
        assertEquals(false, initial.shards().getFirst().request().expectedProfile().isPresent());
        assertEquals(true, initial.shards().get(1).request().expectedProfile().isPresent());
        assertEquals(Optional.empty(), initial.shards().getFirst().request().haloPxOverride());
        var profiledRequest = initial.shards().get(1).request();
        assertEquals(Optional.of(0), profiledRequest.haloPxOverride());
        JsonObject changedHalo = ModelRunRequestCodec.toJson(profiledRequest);
        changedHalo.addProperty("halo_px_override", 1);
        assertNotEquals(
                RecipeStepPlanDigester.inputPlanDigest(
                        List.of(new RecipeStepPlanDigester.RequestShard(0, profiledRequest))),
                RecipeStepPlanDigester.inputPlanDigest(
                        List.of(
                                new RecipeStepPlanDigester.RequestShard(
                                        0, ModelRunRequestCodec.parseRequest(changedHalo)))));

        DurableRequestSharder.StepPlan reproduced =
                DurableRequestSharder.reproduce(
                        fixture.plan(), fixture.step(), scrambled, source, initial.digestShards());
        assertEquals(initial.inputPlanDigest(), reproduced.inputPlanDigest());
        assertEquals(
                initial.inputs().stream()
                        .map(DurableRequestSharder.AssignedInput::requestInput)
                        .toList(),
                reproduced.inputs().stream()
                        .map(DurableRequestSharder.AssignedInput::requestInput)
                        .toList());

        List<SourceInputPlan.PlannedModelInput> changed = new ArrayList<>(canonical);
        changed.set(1, fixture.input(2, RecipeExecutorTestData.square(5, 5, 44, 44)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DurableRequestSharder.reproduce(
                                fixture.plan(),
                                fixture.step(),
                                changed,
                                source,
                                initial.digestShards()));
    }

    @Test
    void replayStillRejectsChangesToCapturedHaloAndResultBudget() {
        Planning fixture = planning(coreHaloModule(Optional.of(256)));
        var inputs = fixture.inputs(List.of(RecipeExecutorTestData.square(5, 5, 45, 45)));
        var initial =
                DurableRequestSharder.initial(
                        fixture.plan(),
                        fixture.step(),
                        inputs,
                        fixture.source(),
                        Integer.MAX_VALUE,
                        ids('7'),
                        ids('6'));
        var retained = initial.shards().getFirst().request();
        var changedHalo = ModelRunRequestCodec.toJson(retained);
        changedHalo.addProperty("halo_px_override", 128);
        var changedBudget = ModelRunRequestCodec.toJson(retained);
        changedBudget.getAsJsonObject("result_budget").addProperty("max_features", 1);
        for (var changed : List.of(changedHalo, changedBudget)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            DurableRequestSharder.reproduce(
                                    fixture.plan(),
                                    fixture.step(),
                                    inputs,
                                    fixture.source(),
                                    List.of(
                                            new RecipeStepPlanDigester.RequestShard(
                                                    0,
                                                    ModelRunRequestCodec.parseRequest(changed)))));
        }
    }

    @Test
    void customHaloIsIncludedInExactResumeSizing() {
        Planning fixture = planning(coreHaloModule(Optional.of(256)));
        var inputs = fixture.inputs(List.of(RecipeExecutorTestData.square(5, 5, 45, 45)));
        var initial =
                DurableRequestSharder.initial(
                        fixture.plan(),
                        fixture.step(),
                        inputs,
                        fixture.source(),
                        Integer.MAX_VALUE,
                        ids('7'),
                        ids('6'));
        var request = initial.shards().getFirst().request();
        assertEquals(Optional.of(256), request.haloPxOverride());
        long exactResumeBytes = resumeSizedBytes(request);
        DurableRequestSharder.initial(
                fixture.plan(),
                fixture.step(),
                inputs,
                fixture.source(),
                exactResumeBytes,
                ids('7'),
                ids('6'));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DurableRequestSharder.initial(
                                fixture.plan(),
                                fixture.step(),
                                inputs,
                                fixture.source(),
                                exactResumeBytes - 1,
                                ids('7'),
                                ids('6')));
    }

    @Test
    void runnerAndGatewayCapsCreateDeterministicContiguousShards() {
        Planning runnerLimited =
                planning(
                        RecipeExecutorTestData.durableModule(
                                RecipeExecutorTestData.scriptedModule(false), 1));
        List<SourceInputPlan.PlannedModelInput> inputs =
                runnerLimited.inputs(
                        List.of(
                                RecipeExecutorTestData.square(5, 5, 10, 10),
                                RecipeExecutorTestData.square(15, 5, 20, 10),
                                RecipeExecutorTestData.square(25, 5, 30, 10)));
        DurableRequestSharder.StepPlan byRunner =
                DurableRequestSharder.initial(
                        runnerLimited.plan(),
                        runnerLimited.step(),
                        inputs,
                        runnerLimited.source(),
                        Integer.MAX_VALUE,
                        ids('7'),
                        ids('6'));
        assertEquals(List.of(0, 1, 2), shardIndices(byRunner));
        assertEquals(List.of(1, 1, 1), shardSizes(byRunner));

        Planning gatewayLimited =
                planning(
                        RecipeExecutorTestData.durableModule(
                                RecipeExecutorTestData.scriptedModule(false)));
        List<SourceInputPlan.PlannedModelInput> gatewayInputs =
                gatewayLimited.inputs(
                        List.of(
                                RecipeExecutorTestData.square(5, 5, 10, 10),
                                RecipeExecutorTestData.square(15, 5, 20, 10),
                                RecipeExecutorTestData.square(25, 5, 30, 10)));
        long largestSingle = 0;
        SourceInputPlan.PlannedModelInput largest = null;
        for (SourceInputPlan.PlannedModelInput input : gatewayInputs) {
            DurableRequestSharder.StepPlan single =
                    DurableRequestSharder.initial(
                            gatewayLimited.plan(),
                            gatewayLimited.step(),
                            List.of(input),
                            gatewayLimited.source(),
                            Integer.MAX_VALUE,
                            ids('7'),
                            ids('6'));
            long bytes = resumeSizedBytes(single.shards().getFirst().request());
            if (bytes > largestSingle) {
                largestSingle = bytes;
                largest = input;
            }
        }
        DurableRequestSharder.StepPlan byGateway =
                DurableRequestSharder.initial(
                        gatewayLimited.plan(),
                        gatewayLimited.step(),
                        gatewayInputs,
                        gatewayLimited.source(),
                        largestSingle,
                        ids('7'),
                        ids('6'));
        assertEquals(List.of(0, 1, 2), shardIndices(byGateway));
        assertEquals(List.of(1, 1, 1), shardSizes(byGateway));

        // A new request could now fit all inputs, but recovery preserves the accepted shards.
        var largerAdmission =
                DurableRequestSharder.initial(
                        gatewayLimited.plan(),
                        gatewayLimited.step(),
                        gatewayInputs,
                        gatewayLimited.source(),
                        Integer.MAX_VALUE,
                        ids('7'),
                        ids('6'));
        assertEquals(List.of(3), shardSizes(largerAdmission));
        var replayed =
                DurableRequestSharder.reproduce(
                        gatewayLimited.plan(),
                        gatewayLimited.step(),
                        gatewayInputs,
                        gatewayLimited.source(),
                        byGateway.digestShards());
        assertEquals(byGateway, replayed);
        assertEquals(byGateway.inputPlanDigest(), replayed.inputPlanDigest());

        SourceInputPlan.PlannedModelInput rejected = largest;
        long belowOneInput = largestSingle - 1;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DurableRequestSharder.initial(
                                gatewayLimited.plan(),
                                gatewayLimited.step(),
                                List.of(rejected),
                                gatewayLimited.source(),
                                belowOneInput,
                                ids('7'),
                                ids('6')));
    }

    @Test
    void initialShardReservesExactRunnerAndGatewayBytesForPartialResume() {
        Planning unconstrained =
                planning(
                        RecipeExecutorTestData.durableModule(
                                RecipeExecutorTestData.scriptedModule(false)));
        Geometry geometry = RecipeExecutorTestData.square(5, 5, 12, 12);
        DurableRequestSharder.StepPlan sample =
                DurableRequestSharder.initial(
                        unconstrained.plan(),
                        unconstrained.step(),
                        unconstrained.inputs(List.of(geometry)),
                        unconstrained.source(),
                        Integer.MAX_VALUE,
                        ids('7'),
                        ids('6'));
        ModelRunRequestCodec.ModelRunRequest initial = sample.shards().getFirst().request();
        long initialBytes =
                CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(initial)).length;
        long resumeBytes = resumeSizedBytes(initial);
        assertTrue(resumeBytes > initialBytes);

        Planning exactRunner =
                planning(
                        RecipeExecutorTestData.durableModule(
                                RecipeExecutorTestData.scriptedModule(false), 100, resumeBytes));
        DurableRequestSharder.initial(
                exactRunner.plan(),
                exactRunner.step(),
                exactRunner.inputs(List.of(geometry)),
                exactRunner.source(),
                Integer.MAX_VALUE,
                ids('7'),
                ids('6'));

        Planning belowRunner =
                planning(
                        RecipeExecutorTestData.durableModule(
                                RecipeExecutorTestData.scriptedModule(false),
                                100,
                                resumeBytes - 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DurableRequestSharder.initial(
                                belowRunner.plan(),
                                belowRunner.step(),
                                belowRunner.inputs(List.of(geometry)),
                                belowRunner.source(),
                                Integer.MAX_VALUE,
                                ids('7'),
                                ids('6')));

        DurableRequestSharder.initial(
                unconstrained.plan(),
                unconstrained.step(),
                unconstrained.inputs(List.of(geometry)),
                unconstrained.source(),
                resumeBytes,
                ids('7'),
                ids('6'));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DurableRequestSharder.initial(
                                unconstrained.plan(),
                                unconstrained.step(),
                                unconstrained.inputs(List.of(geometry)),
                                unconstrained.source(),
                                resumeBytes - 1,
                                ids('7'),
                                ids('6')));
    }

    private static long resumeSizedBytes(ModelRunRequestCodec.ModelRunRequest request) {
        String previousRunId =
                request.modelRunId().equals("ffffffff-ffff-4fff-bfff-ffffffffffff")
                        ? "eeeeeeee-eeee-4eee-beee-eeeeeeeeeeee"
                        : "ffffffff-ffff-4fff-bfff-ffffffffffff";
        ModelRunRequestCodec.ModelRunRequest resumed =
                new ModelRunRequestCodec.ModelRunRequest(
                        request.schemaVersion(),
                        request.actionId(),
                        request.modelRunId(),
                        request.producer(),
                        request.expectedBackend(),
                        request.expectedProfile(),
                        request.inputs(),
                        request.resultBudget(),
                        Optional.of(
                                new ModelRunRequestCodec.ModelRunResumeSource(
                                        previousRunId, "0".repeat(64))),
                        request.haloPxOverride());
        return CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(resumed)).length;
    }

    private static List<Integer> inputIds(List<DurableRequestSharder.AssignedInput> inputs) {
        return inputs.stream().map(value -> value.requestInput().inputId()).toList();
    }

    private static List<Integer> shardIndices(DurableRequestSharder.StepPlan plan) {
        return plan.shards().stream().map(DurableRequestSharder.PlannedShard::shardIndex).toList();
    }

    private static List<Integer> shardSizes(DurableRequestSharder.StepPlan plan) {
        return plan.shards().stream().map(value -> value.inputs().size()).toList();
    }

    private static Supplier<String> ids(char prefix) {
        AtomicInteger next = new AtomicInteger();
        return () -> prefix + "0000000-0000-4000-8000-" + "%012d".formatted(next.incrementAndGet());
    }

    private static Planning planning(ModuleSnapshot module) {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData("durable-sharder", RecipeExecutorTestData.ROI_A);
        ImageExecutionPlan plan =
                RecipeExecutorTestData.plan(
                        image,
                        module,
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        RecipeExecutorTestData.PublishedSourceFixture published =
                RecipeExecutorTestData.publishedSource(plan, image);
        ModuleRecipe.Step step =
                module.recipe().steps().stream()
                        .filter(value -> value.isModelStep() && "child".equals(value.model()))
                        .findFirst()
                        .orElseThrow();
        return new Planning(image, plan, published, step);
    }

    private static ModuleSnapshot coreHaloModule(Optional<Integer> haloPxOverride) {
        var module = RecipeExecutorTestData.mixedFamilyDurableModule();
        var child = module.activatedModels().get("child");
        JsonObject profile = new JsonObject();
        profile.addProperty("type", "core-halo");
        profile.addProperty("core_width_px", 20);
        profile.addProperty("core_height_px", 20);
        profile.addProperty("halo_px", 4);
        profile.addProperty("validation_evidence_digest", "e".repeat(64));
        profile.addProperty("profile_digest", CanonicalCodec.canonicalDigest(profile));
        var models = new LinkedHashMap<>(module.activatedModels());
        models.put(
                "child",
                new ModuleSnapshot.ModelSnapshot(
                        child.architecture(),
                        child.gpu(),
                        child.modelConfigDigest(),
                        child.input(),
                        child.output(),
                        child.capabilities(),
                        Optional.of(LargeRegionProfileCodec.parse(profile)),
                        child.artifactDigests(),
                        haloPxOverride));
        return new ModuleSnapshot(
                module.deploymentId(),
                module.module(),
                module.exactRecipe(),
                module.scriptSetDigest(),
                models,
                module.scripts(),
                module.metrics());
    }

    private record Planning(
            ImageData<BufferedImage> image,
            ImageExecutionPlan plan,
            RecipeExecutorTestData.PublishedSourceFixture published,
            ModuleRecipe.Step step) {

        private RecipeExecutor.AvailableSource source() {
            return new RecipeExecutor.PublishedSource(published.artifact());
        }

        private RecipeExecutor.AvailableSource source(boolean captured) {
            if (!captured) return source();
            var target = published.sourcePlan().targets().getFirst().sourcePlan().orElseThrow();
            var incremental =
                    new SourceInputPlan.IncrementalSourcePlan(
                            target.sourceLevel(),
                            target.target().plane(),
                            target.sourceEnvelopeAtLevel(),
                            target.levelZeroPixelsToSlide());
            return new RecipeExecutor.CapturedSource(
                    incremental.capture(
                            target.sourceRevision().pixelSource(), plan.clientBuild().extension()));
        }

        private List<SourceInputPlan.PlannedModelInput> inputs(List<Geometry> geometries) {
            List<SourceInputPlan.PlannedModelInput> values = new ArrayList<>();
            for (int index = 0; index < geometries.size(); index++) {
                values.add(input(index + 1, geometries.get(index)));
            }
            return List.copyOf(values);
        }

        private SourceInputPlan.PlannedModelInput input(int suffix, Geometry geometry) {
            SourceInputPlan.TargetSourcePlan source =
                    published.sourcePlan().targets().getFirst().sourcePlan().orElseThrow();
            return SourceInputPlanner.planParentInput(
                    source,
                    RecipeExecutorTestData.producer("child"),
                    "30000000-0000-4000-8000-%012d".formatted(suffix),
                    geometry);
        }
    }
}

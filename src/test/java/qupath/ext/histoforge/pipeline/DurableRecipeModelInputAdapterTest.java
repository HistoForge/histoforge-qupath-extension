package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.contract.ArtifactRefCodec;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.ModelExecutionPlanCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.CropFraming;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourceInputPlanner;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

class DurableRecipeModelInputAdapterTest {

    @TempDir Path directory;

    @Test
    void completeDurableResultsReplayTheSameScriptedRecipeTransaction() throws Exception {
        Fixture fixture = fixture(false);

        RecipeStaging.Target durable = replay(fixture);

        assertEquals(
                SourceInputPlan.ExecutionProtocol.RECOVERABLE,
                fixture.published()
                        .sourcePlan()
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .protocol());
        assertEquivalent(fixture.finiteStaging(), durable);
        assertEquals(List.of("parent", "child"), fixture.recording().stepIds());
        assertEquals(2, durable.generations().size());
        assertEquals(
                "Eligible", durable.generations().getFirst().candidates().getFirst().pathClass());
        assertEquals(
                durable.generations().getFirst().candidates().getFirst().candidateId(),
                ((ExecutionScopeCodec.ParentScope) durable.generations().get(1).output().scope())
                        .parentId());
    }

    @Test
    void alteredFileAfterValidationRejectsReplayWithoutChangingExistingObjects() throws Exception {
        Fixture fixture = fixture(false);
        var existing =
                RecipeExecutorTestData.manual(
                        RecipeExecutorTestData.MANUAL_PARENT_ID,
                        "Unrelated",
                        RecipeExecutorTestData.square(60, 60, 70, 70));
        fixture.image().getHierarchy().addObject(existing);
        var before = List.copyOf(fixture.image().getHierarchy().getFlattenedObjectList(null));
        var geometry = existing.getROI().getGeometry().copy();
        var pathClass = existing.getPathClass();
        var metadata = Map.copyOf(existing.getMetadata());
        var retained =
                fixture.recoveredRuns().getFirst().retainedInputs().values().iterator().next();
        var path = fixture.store().requireFile(retained.file());
        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 1;
        Files.write(path, bytes);

        var failure =
                assertThrows(
                        TargetRecipeTransaction.TargetTransactionFailure.class,
                        () -> replay(fixture));

        assertTrue(failure.originalCause() instanceof IOException);
        assertTrue(failure.originalCause().getMessage().contains("missing or changed"));
        assertEquals(before, fixture.image().getHierarchy().getFlattenedObjectList(null));
        assertSame(
                existing,
                before.stream()
                        .filter(object -> object.getID().equals(existing.getID()))
                        .findFirst()
                        .orElseThrow());
        assertEquals(pathClass, existing.getPathClass());
        assertEquals(metadata, existing.getMetadata());
        assertTrue(geometry.equalsExact(existing.getROI().getGeometry()));
    }

    @Test
    void emptyParentResultReplaysAValidEmptyChildStepWithoutPlaceholderRun() throws Exception {
        Fixture fixture = fixture(true);

        RecipeStaging.Target durable = replay(fixture);

        assertEquivalent(fixture.finiteStaging(), durable);
        assertEquals(List.of("parent"), fixture.recording().stepIds());
        assertEquals(List.of(), fixture.resumable().handle().steps().get(1).runs());
        assertEquals(1, durable.generations().size());
        assertEquals(List.of(), durable.generations().getFirst().candidates());
    }

    @Test
    void alteredParentPlanFailsBeforeRetainedChildFeaturesAreConsumed() throws Exception {
        Fixture fixture = fixture(false);
        LargeActionTypes.LargeActionHandle handle = fixture.resumable().handle();
        List<LargeActionTypes.StepRunRef> alteredSteps = new ArrayList<>(handle.steps());
        LargeActionTypes.StepRunRef child = alteredSteps.get(1);
        alteredSteps.set(
                1,
                new LargeActionTypes.StepRunRef(
                        child.stepId(),
                        child.inputPlanDigest(),
                        Optional.of("f".repeat(64)),
                        child.runs()));
        LargeActionTypes.LargeActionHandle alteredHandle =
                copyHandle(handle, List.copyOf(alteredSteps));
        LargeActionHandleStore.ResumableAction altered =
                new LargeActionHandleStore.ResumableAction(
                        alteredHandle,
                        fixture.resumable().sourceStartRequest(),
                        fixture.resumable().authorizedInputs(),
                        fixture.resumable().authorizedRuns());
        DurableRecipeModelInputAdapter adapter =
                new DurableRecipeModelInputAdapter(
                        fixture.plan(), altered, fixture.recoveredRuns());
        TargetRecipeTransaction transaction =
                new TargetRecipeTransaction(
                        fixture.plan(),
                        fixture.published().executionInputs(),
                        fixture.image(),
                        adapter);

        assertThrows(
                LargeActionTransactionValidator.Conflict.class,
                () ->
                        transaction.stage(
                                fixture.plan().targetRegions().getFirst(),
                                fixture.published().sourcePlan().targets().getFirst(),
                                OutputIndex.fromHierarchy(fixture.image().getHierarchy()),
                                Set.of(),
                                new RecipeStaging.Projection(),
                                () -> false));
    }

    @Test
    void alteredRetainedInputPlanIsADeterministicConflict() throws Exception {
        Fixture fixture = fixture(false);
        LargeActionTypes.LargeActionHandle handle = fixture.resumable().handle();
        List<LargeActionTypes.StepRunRef> alteredSteps = new ArrayList<>(handle.steps());
        LargeActionTypes.StepRunRef first = alteredSteps.getFirst();
        alteredSteps.set(
                0,
                new LargeActionTypes.StepRunRef(
                        first.stepId(), "f".repeat(64), first.parentPlanDigest(), first.runs()));
        LargeActionHandleStore.ResumableAction altered =
                new LargeActionHandleStore.ResumableAction(
                        copyHandle(handle, List.copyOf(alteredSteps)),
                        fixture.resumable().sourceStartRequest(),
                        fixture.resumable().authorizedInputs(),
                        fixture.resumable().authorizedRuns());

        assertThrows(
                LargeActionTransactionValidator.Conflict.class,
                () ->
                        new DurableRecipeModelInputAdapter(
                                fixture.plan(), altered, fixture.recoveredRuns()));
    }

    @Test
    void finiteTargetCannotBeReplayedAsADurableLargeAction() throws Exception {
        Fixture fixture =
                fixture(
                        false,
                        RecipeExecutorTestData.durableModule(
                                RecipeExecutorTestData.scriptedModule(false)));

        assertEquals(
                SourceInputPlan.ExecutionProtocol.FINITE,
                fixture.published()
                        .sourcePlan()
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .protocol());
        assertThrows(LargeActionTransactionValidator.Conflict.class, () -> replay(fixture));
    }

    @Test
    void recoveryRejectsAnotherGatewayDeploymentBeforeObservingAnyRun() throws Exception {
        Fixture fixture = fixture(false);
        DurableModelRunExecutor executor = mock(DurableModelRunExecutor.class);
        org.mockito.Mockito.when(executor.deploymentId()).thenReturn("another-deployment");

        assertThrows(
                GatewayTransport.DeploymentChangedException.class,
                () ->
                        LargeActionRecipeReconstructor.prepare(
                                fixture.resumable(),
                                mock(LargeActionHandleStore.class),
                                executor,
                                () -> false));
    }

    @Test
    void fillWithExistingFieldOutputConsumesItsRetainedEmptyStep() throws Exception {
        Fixture fixture =
                fixture(
                        false,
                        RecipeExecutorTestData.durableModule(RecipeExecutorTestData.fieldModule()));
        ExecutionScopeCodec.TargetRegion target = fixture.plan().targetRegions().getFirst();
        PathObject existing =
                RecipeExecutorTestData.attributed(
                        "30000000-0000-4000-8000-000000000099",
                        "Parent",
                        RecipeExecutorTestData.square(10, 10, 30, 30),
                        "parent",
                        new ExecutionScopeCodec.FieldScope(target.key()));
        fixture.image().getHierarchy().getRootObject().getChildObjects().stream()
                .findFirst()
                .orElseThrow()
                .addChildObject(existing);

        LargeActionTypes.StepRunRef emptyStep =
                new LargeActionTypes.StepRunRef(
                        "parent",
                        RecipeStepPlanDigester.inputPlanDigest(List.of()),
                        Optional.empty(),
                        List.of());
        LargeActionHandleStore.ResumableAction empty =
                new LargeActionHandleStore.ResumableAction(
                        copyHandle(fixture.resumable().handle(), List.of(emptyStep)),
                        fixture.resumable().sourceStartRequest(),
                        Map.of(),
                        List.of());
        DurableRecipeModelInputAdapter adapter =
                new DurableRecipeModelInputAdapter(fixture.plan(), empty, List.of());
        RecipeStaging.Target staged =
                new TargetRecipeTransaction(
                                fixture.plan(),
                                fixture.published().executionInputs(),
                                fixture.image(),
                                adapter)
                        .stage(
                                target,
                                fixture.published().sourcePlan().targets().getFirst(),
                                OutputIndex.fromHierarchy(fixture.image().getHierarchy()),
                                Set.of(),
                                new RecipeStaging.Projection(),
                                () -> false);

        assertEquals(List.of(), staged.generations());
        assertEquals(1, staged.outcomes().size());
        assertEquals(
                RunResults.ScopeOutcomeType.SKIPPED_PRESENT, staged.outcomes().getFirst().type());
    }

    @Test
    void mixedPlanFamilyShardsRebindInputsByOutputRatherThanShardPosition() throws Exception {
        Fixture fixture = fixture(false, RecipeExecutorTestData.mixedFamilyDurableModule());
        ModuleRecipe.Step childStep =
                fixture.plan().module().recipe().steps().stream()
                        .filter(step -> step.isModelStep() && "child".equals(step.model()))
                        .findFirst()
                        .orElseThrow();
        ModelSnapshot child = fixture.plan().module().activatedModels().get("child");
        ExecutionScopeCodec.TargetRegion target = fixture.plan().targetRegions().getFirst();
        SourceInputPlan.TargetSourcePlan targetSource =
                fixture.published().sourcePlan().targets().getFirst().sourcePlan().orElseThrow();
        List<PathObject> parents =
                List.of(
                        RecipeExecutorTestData.manual(
                                "30000000-0000-4000-8000-000000000011",
                                "Eligible",
                                RecipeExecutorTestData.square(10, 10, 20, 20)),
                        RecipeExecutorTestData.manual(
                                "30000000-0000-4000-8000-000000000012",
                                "Eligible",
                                RecipeExecutorTestData.square(30, 10, 60, 40)),
                        RecipeExecutorTestData.manual(
                                "30000000-0000-4000-8000-000000000013",
                                "Eligible",
                                RecipeExecutorTestData.square(70, 10, 80, 20)));
        List<SourceInputPlan.PlannedModelInput> planned =
                parents.stream()
                        .map(
                                parent ->
                                        SourceInputPlanner.planParentInput(
                                                targetSource,
                                                RecipeExecutorTestData.producer("child"),
                                                parent.getID().toString(),
                                                parent.getROI().getGeometry()))
                        .toList();
        List<ModelRunRequestCodec.ModelRunInput> requestInputs = new ArrayList<>();
        for (int index = 0; index < planned.size(); index++) {
            requestInputs.add(
                    new ModelRunRequestCodec.ModelRunInput(
                            index,
                            "71000000-0000-4000-8000-%012d".formatted(index + 1),
                            planned.get(index).output(),
                            rasterInput(
                                    fixture.published().artifact(), planned.get(index), child)));
        }
        LargeRegionProfileCodec.LargeRegionProfile profile =
                child.largeRegionProfile().orElseThrow();
        ModelRunRequestCodec.ModelRunRequest finite =
                durableRequest(
                        fixture.plan(),
                        child,
                        "61000000-0000-4000-8000-000000000001",
                        Optional.empty(),
                        List.of(requestInputs.get(0), requestInputs.get(2)));
        ModelRunRequestCodec.ModelRunRequest profiled =
                durableRequest(
                        fixture.plan(),
                        child,
                        "61000000-0000-4000-8000-000000000002",
                        Optional.of(profile),
                        List.of(requestInputs.get(1)));
        List<ModelRunRequestCodec.ModelRunRequest> requests = List.of(finite, profiled);
        List<LargeActionHandleStore.AuthorizedModelRun> childRuns = new ArrayList<>();
        List<DurableRecipeModelInputAdapter.RecoveredRun> childRecovered = new ArrayList<>();
        List<LargeActionTypes.ModelRunRef> runRefs = new ArrayList<>();
        for (int shard = 0; shard < requests.size(); shard++) {
            ModelRunRequestCodec.ModelRunRequest request = requests.get(shard);
            List<InferenceFeatureCodec.FeatureCollection> emptyFeatures =
                    request.inputs().stream()
                            .map(ignored -> new InferenceFeatureCodec.FeatureCollection(List.of()))
                            .toList();
            ModelRunPublicationCodec.Manifest manifest =
                    completeManifest(request, child, emptyFeatures, 20 + shard);
            BackendExecutionCodec.DurableRun execution =
                    new BackendExecutionCodec.DurableRun(
                            request.modelRunId(), manifest.manifestDigest());
            LargeActionHandleStore.AuthorizedModelRun authorized =
                    new LargeActionHandleStore.AuthorizedModelRun(
                            "child", shard, request, Optional.of(execution));
            Map<Integer, CompleteModelResults.RetainedFeatures> retained = new LinkedHashMap<>();
            for (var result : manifest.completedInputs()) {
                retained.put(
                        result.inputId(),
                        FeatureStreamTestData.retain(
                                fixture.store(),
                                fixture.resumable().handle(),
                                request,
                                child,
                                result,
                                new InferenceFeatureCodec.FeatureCollection(List.of())));
            }
            childRuns.add(authorized);
            childRecovered.add(
                    new DurableRecipeModelInputAdapter.RecoveredRun(
                            authorized, manifest, retained));
            runRefs.add(
                    new LargeActionTypes.ModelRunRef(
                            shard,
                            file(
                                    "requests/child-" + shard + ".json",
                                    ModelRunRequestCodec.toJson(request)),
                            request.modelRunId(),
                            Optional.of(manifest.manifestDigest())));
        }
        List<RecipeStepPlanDigester.RequestShard> shards =
                List.of(
                        new RecipeStepPlanDigester.RequestShard(0, finite),
                        new RecipeStepPlanDigester.RequestShard(1, profiled));
        LargeActionTypes.StepRunRef childRef =
                new LargeActionTypes.StepRunRef(
                        "child",
                        RecipeStepPlanDigester.inputPlanDigest(shards),
                        Optional.of(
                                RecipeStepPlanDigester.parentPlanDigest(
                                        child.input(),
                                        RecipeStepPlanDigester.captureParents(target, parents))),
                        runRefs);
        LargeActionTypes.LargeActionHandle base = fixture.resumable().handle();
        LargeActionHandleStore.AuthorizedModelRun parentRun =
                fixture.resumable().authorizedRuns().getFirst();
        Map<String, LargeActionHandleStore.AuthorizedModelInput> authorizedInputs =
                new LinkedHashMap<>();
        fixture.resumable().authorizedInputs().values().stream()
                .filter(value -> "parent".equals(value.input().output().producer().stepId()))
                .forEach(value -> authorizedInputs.put(value.input().outputGenerationId(), value));
        for (LargeActionHandleStore.AuthorizedModelRun run : childRuns) {
            for (ModelRunRequestCodec.ModelRunInput input : run.request().inputs()) {
                authorizedInputs.put(
                        input.outputGenerationId(),
                        new LargeActionHandleStore.AuthorizedModelInput(
                                input, run.request().expectedBackend(), run.execution()));
            }
        }
        LargeActionHandleStore.ResumableAction mixed =
                new LargeActionHandleStore.ResumableAction(
                        copyHandle(base, List.of(base.steps().getFirst(), childRef)),
                        fixture.resumable().sourceStartRequest(),
                        authorizedInputs,
                        List.of(parentRun, childRuns.get(0), childRuns.get(1)));
        List<DurableRecipeModelInputAdapter.RecoveredRun> recovered =
                new ArrayList<>(List.of(fixture.recoveredRuns().getFirst()));
        recovered.addAll(childRecovered);
        DurableRecipeModelInputAdapter adapter =
                new DurableRecipeModelInputAdapter(fixture.plan(), mixed, recovered);

        List<DurableRecipeModelInputAdapter.PreparedDurableInput> rebound =
                adapter.prepareStep(
                        childStep,
                        planned,
                        parents,
                        new RecipeExecutor.PublishedSource(fixture.published().artifact()),
                        List.of());

        assertEquals(
                planned.stream().map(SourceInputPlan.PlannedModelInput::output).toList(),
                rebound.stream().map(value -> value.requestInput().output()).toList());
    }

    private static RecipeStaging.Target replay(Fixture fixture) throws Exception {
        DurableRecipeModelInputAdapter adapter =
                new DurableRecipeModelInputAdapter(
                        fixture.plan(), fixture.resumable(), fixture.recoveredRuns());
        TargetRecipeTransaction transaction =
                new TargetRecipeTransaction(
                        fixture.plan(),
                        fixture.published().executionInputs(),
                        fixture.image(),
                        adapter);
        return transaction.stage(
                fixture.plan().targetRegions().getFirst(),
                fixture.published().sourcePlan().targets().getFirst(),
                OutputIndex.fromHierarchy(fixture.image().getHierarchy()),
                Set.of(),
                new RecipeStaging.Projection(),
                () -> false);
    }

    private Fixture fixture(boolean emptyParent) throws Exception {
        return fixture(emptyParent, recoverableRoiModule());
    }

    private Fixture fixture(boolean emptyParent, ModuleSnapshot module) throws Exception {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData("durable-recipe", RecipeExecutorTestData.ROI_A);
        ImageExecutionPlan plan =
                RecipeExecutorTestData.plan(
                        image,
                        module,
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        RecipeExecutorTestData.PublishedSourceFixture published =
                RecipeExecutorTestData.publishedSource(plan, image);
        RecordingRunner recording = new RecordingRunner(emptyParent);
        List<String> generationIds =
                new ArrayList<>(
                        List.of(
                                "70000000-0000-4000-8000-000000000001",
                                "70000000-0000-4000-8000-000000000002"));
        TargetRecipeTransaction finite =
                new TargetRecipeTransaction(
                        plan,
                        published.executionInputs(),
                        image,
                        recording,
                        () -> generationIds.removeFirst());
        RecipeStaging.Target finiteStaging =
                finite.stage(
                        plan.targetRegions().getFirst(),
                        published.sourcePlan().targets().getFirst(),
                        OutputIndex.fromHierarchy(image.getHierarchy()),
                        Set.of(),
                        new RecipeStaging.Projection(),
                        () -> false);

        RecoveryFixture recovery =
                recovery(plan, published, finiteStaging, recording, emptyParent, directory);
        return new Fixture(
                image,
                plan,
                published,
                finiteStaging,
                recording,
                recovery.resumable(),
                recovery.recoveredRuns(),
                recovery.store());
    }

    /**
     * Make the ROI recoverable because the child model's worst-case crop exceeds its finite limit,
     * while the small realized parent crop used by this fixture still fits that limit.
     */
    private static ModuleSnapshot recoverableRoiModule() {
        ModuleSnapshot source =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.scriptedModule(false));
        ModelSnapshot child = source.activatedModels().get("child");
        RunnerCapabilitiesCodec.RunnerCapabilities capabilities = child.capabilities();
        ExecutionLimitsCodec.TargetRasterLimits targetRaster =
                new ExecutionLimitsCodec.TargetRasterLimits(200, 200, 40_000);
        RunnerCapabilitiesCodec.FiniteCallLimits finite = capabilities.finite();
        RunnerCapabilitiesCodec.DurableRunLimits durable = capabilities.durableRun().orElseThrow();
        ExecutionLimitsCodec.ModelRunLimits run = durable.run();
        RunnerCapabilitiesCodec.RunnerCapabilities constrained =
                new RunnerCapabilitiesCodec.RunnerCapabilities(
                        capabilities.modes(),
                        capabilities.outputTypes(),
                        capabilities.sourceContainers(),
                        capabilities.runnerBuild(),
                        capabilities.rasterizer(),
                        capabilities.preprocessingContractDigest(),
                        new RunnerCapabilitiesCodec.FiniteCallLimits(
                                finite.maxAttemptExecutionSeconds(),
                                finite.maxRequestCanonicalBytes(),
                                targetRaster,
                                finite.maxInclusionMaskVertices(),
                                finite.result()),
                        Optional.of(
                                new RunnerCapabilitiesCodec.DurableRunLimits(
                                        new ExecutionLimitsCodec.ModelRunLimits(
                                                run.maxExecutionSeconds(),
                                                run.maxRequestCanonicalBytes(),
                                                run.maxInputs(),
                                                run.maxUnits(),
                                                targetRaster,
                                                run.processingRaster(),
                                                run.maxAggregateTargetRasterPixels(),
                                                run.maxAggregateInclusionMaskVertices(),
                                                run.maxRecoveryStateBytesPerUnit(),
                                                run.maxRecoveryStateBytesPerRun(),
                                                run.intermediateResult(),
                                                run.result()),
                                        durable.terminalRetentionSeconds())));
        ModelSnapshot constrainedChild =
                new ModelSnapshot(
                        child.architecture(),
                        child.gpu(),
                        child.modelConfigDigest(),
                        new ModelInputSnapshot(
                                child.input().type(),
                                child.input().targetMpp(),
                                child.input().fieldProfile(),
                                Optional.of(new CropFraming(150))),
                        child.output(),
                        constrained,
                        child.largeRegionProfile(),
                        child.artifactDigests());
        Map<String, ModelSnapshot> models = new LinkedHashMap<>(source.activatedModels());
        models.put("child", constrainedChild);
        return new ModuleSnapshot(
                source.deploymentId(),
                source.module(),
                source.exactRecipe(),
                source.scriptSetDigest(),
                models,
                source.scripts(),
                source.metrics());
    }

    private static RecoveryFixture recovery(
            ImageExecutionPlan plan,
            RecipeExecutorTestData.PublishedSourceFixture published,
            RecipeStaging.Target finite,
            RecordingRunner recording,
            boolean emptyParent,
            Path directory)
            throws Exception {
        Map<String, FiniteModelInputExecutor.PreparedInput> preparedByStep =
                recording.preparedByStep();
        Map<String, InferenceFeatureCodec.FeatureCollection> featuresByStep =
                recording.featuresByStep();
        List<LargeActionTypes.StepRunRef> steps = new ArrayList<>();
        List<LargeActionHandleStore.AuthorizedModelRun> authorizedRuns = new ArrayList<>();
        Map<String, LargeActionHandleStore.AuthorizedModelInput> authorizedInputs =
                new LinkedHashMap<>();
        Map<String, ModelRunPublicationCodec.Manifest> manifests = new LinkedHashMap<>();

        PathObject parent = null;
        if (!emptyParent) {
            CandidateGeneration parentGeneration = finite.generations().getFirst();
            CandidateGeneration.SlideCandidate candidate = parentGeneration.candidates().getFirst();
            parent =
                    RecipeExecutorTestData.attributed(
                            candidate.candidateId(),
                            candidate.pathClass(),
                            candidate.geometrySlide(),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(
                                    plan.targetRegions().getFirst().key()));
        }

        int runPosition = 0;
        List<String> modelSteps =
                plan.module().recipe().steps().stream()
                        .filter(qupath.ext.histoforge.modules.ModuleRecipe.Step::isModelStep)
                        .map(qupath.ext.histoforge.modules.ModuleRecipe.Step::model)
                        .filter(plan.module().activatedModels()::containsKey)
                        .toList();
        for (String stepId : modelSteps) {
            FiniteModelInputExecutor.PreparedInput finiteInput = preparedByStep.get(stepId);
            if (finiteInput == null) {
                String parentDigest =
                        RecipeStepPlanDigester.parentPlanDigest(
                                plan.module().activatedModels().get(stepId).input(), List.of());
                steps.add(
                        new LargeActionTypes.StepRunRef(
                                stepId,
                                RecipeStepPlanDigester.inputPlanDigest(List.of()),
                                Optional.of(parentDigest),
                                List.of()));
                continue;
            }

            String runId = "60000000-0000-4000-8000-%012d".formatted(++runPosition);
            ModelRunRequestCodec.ModelRunRequest request = durableRequest(plan, finiteInput, runId);
            ModelRunPublicationCodec.Manifest manifest =
                    completeManifest(
                            request,
                            plan.module().activatedModels().get(stepId),
                            featuresByStep.get(stepId),
                            runPosition);
            BackendExecutionCodec.DurableRun execution =
                    new BackendExecutionCodec.DurableRun(runId, manifest.manifestDigest());
            LargeActionHandleStore.AuthorizedModelRun authorized =
                    new LargeActionHandleStore.AuthorizedModelRun(
                            stepId, 0, request, Optional.of(execution));
            authorizedRuns.add(authorized);
            ModelRunRequestCodec.ModelRunInput requestInput = request.inputs().getFirst();
            authorizedInputs.put(
                    requestInput.outputGenerationId(),
                    new LargeActionHandleStore.AuthorizedModelInput(
                            requestInput, request.expectedBackend(), Optional.of(execution)));
            manifests.put(stepId, manifest);
            LargeActionTypes.LocalFileRecord requestFile =
                    file("requests/" + stepId + ".json", ModelRunRequestCodec.toJson(request));
            Optional<String> parentPlan =
                    "child".equals(stepId)
                            ? Optional.of(
                                    RecipeStepPlanDigester.parentPlanDigest(
                                            plan.module().activatedModels().get(stepId).input(),
                                            RecipeStepPlanDigester.captureParents(
                                                    plan.targetRegions().getFirst(),
                                                    List.of(parent))))
                            : Optional.empty();
            steps.add(
                    new LargeActionTypes.StepRunRef(
                            stepId,
                            RecipeStepPlanDigester.inputPlanDigest(
                                    List.of(new RecipeStepPlanDigester.RequestShard(0, request))),
                            parentPlan,
                            List.of(
                                    new LargeActionTypes.ModelRunRef(
                                            0,
                                            requestFile,
                                            runId,
                                            Optional.of(manifest.manifestDigest())))));
        }

        SourceArtifactCodec.SourceArtifactStartRequest start = published.startRequest();
        LargeActionTypes.SourceUploadRef source =
                new LargeActionTypes.SourceUploadRef(
                        start.artifactId(),
                        file("source/start.json", SourceArtifactCodec.toJson(start)),
                        Optional.of(
                                new LargeActionTypes.LocalFileRecord(
                                        "source/source.bin",
                                        start.totalBytes(),
                                        published.artifact().bytesDigest())),
                        Optional.of(published.artifact()));
        LargeActionTypes.LargeActionHandle handle =
                new LargeActionTypes.LargeActionHandle(
                        LargeActionTypes.SCHEMA_VERSION,
                        plan.actionId(),
                        "50000000-0000-4000-8000-000000000001",
                        plan.clientBuild(),
                        plan.imageId(),
                        plan.module(),
                        plan.action(),
                        plan.targetRegions().getFirst(),
                        new LargeActionTypes.LargeActionBaseline(
                                RecipeExecutorTestData.sourceRevision().pixelSource(),
                                new LargeActionTypes.HierarchyTransactionIdentity(
                                        Set.of(), Set.of(), "e".repeat(64))),
                        published
                                .sourcePlan()
                                .targets()
                                .getFirst()
                                .sourcePlan()
                                .orElseThrow()
                                .artifact(),
                        Optional.of(source),
                        steps,
                        Optional.empty(),
                        LargeActionTypes.LargeActionPhase.EXECUTING,
                        Optional.empty(),
                        Instant.parse("2026-08-17T01:02:03.004Z"),
                        Instant.parse("2026-08-17T01:02:03.004Z"));
        LargeActionHandleStore.ResumableAction resumable =
                new LargeActionHandleStore.ResumableAction(
                        handle,
                        Optional.of(start),
                        Map.copyOf(authorizedInputs),
                        List.copyOf(authorizedRuns));
        var store = new LargeActionHandleStore(directory, plan.clientBuild());
        List<DurableRecipeModelInputAdapter.RecoveredRun> recovered = new ArrayList<>();
        for (var authorized : authorizedRuns) {
            var manifest = manifests.get(authorized.stepId());
            var result = manifest.completedInputs().getFirst();
            var retained =
                    FeatureStreamTestData.retain(
                            store,
                            handle,
                            authorized.request(),
                            plan.module().activatedModels().get(authorized.stepId()),
                            result,
                            featuresByStep.get(authorized.stepId()));
            recovered.add(
                    new DurableRecipeModelInputAdapter.RecoveredRun(
                            authorized, manifest, Map.of(result.inputId(), retained)));
        }
        return new RecoveryFixture(resumable, List.copyOf(recovered), store);
    }

    private static ModelRunRequestCodec.ModelRunRequest durableRequest(
            ImageExecutionPlan plan, FiniteModelInputExecutor.PreparedInput finite, String runId) {
        FiniteInferenceCodec.FiniteInferenceRequest input = finite.request();
        return new ModelRunRequestCodec.ModelRunRequest(
                ModelRunRequestCodec.SCHEMA_VERSION,
                input.actionId(),
                runId,
                input.producer(),
                input.expectedBackend(),
                Optional.empty(),
                List.of(
                        new ModelRunRequestCodec.ModelRunInput(
                                input.inputId(),
                                input.outputGenerationId(),
                                finite.plannedInput().output(),
                                input.rasterInput())),
                input.resultBudget(),
                Optional.empty(),
                java.util.Optional.empty());
    }

    private static ModelRunRequestCodec.ModelRunRequest durableRequest(
            ImageExecutionPlan plan,
            ModelSnapshot model,
            String runId,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> profile,
            List<ModelRunRequestCodec.ModelRunInput> inputs) {
        var result = model.capabilities().durableRun().orElseThrow().run().result();
        return new ModelRunRequestCodec.ModelRunRequest(
                ModelRunRequestCodec.SCHEMA_VERSION,
                plan.actionId(),
                runId,
                RecipeExecutorTestData.producer("child"),
                plan.module().backendIdentity("child"),
                profile,
                inputs,
                new FiniteInferenceCodec.ClientResultBudget(
                        result.maxTransferredBytes(),
                        result.maxDecodedBytes(),
                        result.maxRecordBytes(),
                        result.maxFeatures(),
                        result.maxVertices()),
                Optional.empty(),
                java.util.Optional.empty());
    }

    private static RasterInputCodec.ModelRasterInput rasterInput(
            SourceArtifactPublicationCodec.SourceArtifactRef artifact,
            SourceInputPlan.PlannedModelInput planned,
            ModelSnapshot model) {
        RasterInputCodec.SourceView sourceView =
                RasterInputCodec.buildSourceView(
                        artifact,
                        planned.sourceView().seriesIndex(),
                        planned.sourceView().resolutionLevel(),
                        planned.sourceView().plane(),
                        planned.sourceView().pixelRect(),
                        planned.sourceView().viewPixelsToSlide());
        return RasterInputCodec.buildModelRasterInput(
                sourceView, planned.frame(), model.capabilities().rasterizer());
    }

    private static ModelRunPublicationCodec.Manifest completeManifest(
            ModelRunRequestCodec.ModelRunRequest request,
            ModuleSnapshot.ModelSnapshot model,
            InferenceFeatureCodec.FeatureCollection features,
            int suffix) {
        return completeManifest(request, model, List.of(features), suffix);
    }

    private static ModelRunPublicationCodec.Manifest completeManifest(
            ModelRunRequestCodec.ModelRunRequest request,
            ModuleSnapshot.ModelSnapshot model,
            List<InferenceFeatureCodec.FeatureCollection> features,
            int suffix) {
        if (request.inputs().size() != features.size()) {
            throw new IllegalArgumentException("one feature stream is required per input");
        }
        var limits = model.capabilities().durableRun().orElseThrow().run();
        List<ModelRunPublicationCodec.InputResult> results = new ArrayList<>();
        for (int index = 0; index < request.inputs().size(); index++) {
            ModelRunRequestCodec.ModelRunInput input = request.inputs().get(index);
            InferenceFeatureCodec.FeatureCollection inputFeatures = features.get(index);
            ModelExecutionPlanCodec.ModelExecutionPlan executionPlan =
                    ModelExecutionPlanCodec.buildExpected(
                            request.expectedProfile(),
                            input.rasterInput().frame().transform().raster(),
                            ModelRunPublicationCodec.preprocessingContextDigest(
                                    input, request.expectedBackend()),
                            limits.maxUnits(),
                            request.haloPxOverride());
            long featureCount = inputFeatures.features().size();
            long vertexCount = featureCount == 0 ? 0 : 4;
            byte[] compressed = FeatureStreamTestData.compressed(inputFeatures);
            int artifactSuffix = suffix * 10 + index;
            results.add(
                    new ModelRunPublicationCodec.InputResult(
                            input.inputId(),
                            FiniteInferenceCodec.buildBackendInputIdentity(input.rasterInput()),
                            executionPlan,
                            new ArtifactRefCodec.ArtifactRef(
                                    "80000000-0000-4000-8000-%012d".formatted(artifactSuffix),
                                    ModelRunPublicationCodec.FEATURE_STREAM_MEDIA_TYPE,
                                    Optional.of(
                                            ModelRunPublicationCodec
                                                    .FEATURE_STREAM_CONTENT_ENCODING),
                                    compressed.length,
                                    CanonicalCodec.sha256(compressed)),
                            featureCount,
                            vertexCount));
        }
        ModelRunPublicationCodec.Manifest provisional =
                new ModelRunPublicationCodec.Manifest(
                        ModelRunPublicationCodec.SCHEMA_VERSION,
                        request.modelRunId(),
                        request.actionId(),
                        request.expectedBackend(),
                        ModelRunRequestCodec.requestDigest(request),
                        List.of(),
                        List.of(),
                        List.of(),
                        results,
                        true,
                        Optional.empty(),
                        "0".repeat(64));
        JsonObject preimage = ModelRunPublicationCodec.toJson(provisional);
        preimage.remove("manifest_digest");
        ModelRunPublicationCodec.Manifest manifest =
                new ModelRunPublicationCodec.Manifest(
                        provisional.schemaVersion(),
                        provisional.modelRunId(),
                        provisional.actionId(),
                        provisional.backend(),
                        provisional.requestDigest(),
                        provisional.completedUnits(),
                        provisional.checkpoints(),
                        provisional.failedUnits(),
                        provisional.completedInputs(),
                        true,
                        provisional.failure(),
                        CanonicalCodec.canonicalDigest(preimage));
        return ModelRunPublicationCodec.validateManifest(
                ModelRunPublicationCodec.toJson(manifest), request, limits);
    }

    private static LargeActionTypes.LocalFileRecord file(String path, JsonObject value) {
        byte[] bytes = CanonicalCodec.canonicalBytes(value);
        return new LargeActionTypes.LocalFileRecord(
                path, bytes.length, CanonicalCodec.sha256(bytes));
    }

    private static LargeActionTypes.LargeActionHandle copyHandle(
            LargeActionTypes.LargeActionHandle source, List<LargeActionTypes.StepRunRef> steps) {
        return new LargeActionTypes.LargeActionHandle(
                source.schemaVersion(),
                source.actionId(),
                source.handleGenerationId(),
                source.clientBuild(),
                source.imageId(),
                source.module(),
                source.action(),
                source.target(),
                source.baseline(),
                source.sourcePlan(),
                source.source(),
                steps,
                source.expectedCommittedHierarchy(),
                source.phase(),
                source.failure(),
                source.createdAt(),
                source.updatedAt());
    }

    private static void assertEquivalent(
            RecipeStaging.Target finite, RecipeStaging.Target durable) {
        assertEquals(finite.targets(), durable.targets());
        assertEquals(finite.replacementClosure(), durable.replacementClosure());
        assertEquals(finite.classChanges(), durable.classChanges());
        assertEquals(finite.generations().size(), durable.generations().size());
        for (int index = 0; index < finite.generations().size(); index++) {
            CandidateGeneration left = finite.generations().get(index);
            CandidateGeneration right = durable.generations().get(index);
            assertEquals(left.output(), right.output());
            assertEquals(left.inputId(), right.inputId());
            assertEquals(left.outputGenerationId(), right.outputGenerationId());
            assertEquals(left.inputEvidence(), right.inputEvidence());
            assertEquals(left.counts(), right.counts());
            assertEquals(left.candidates(), right.candidates());
        }
        assertEquals(
                finite.outcomes().stream().map(RunResults.ScopeWorkResult::key).toList(),
                durable.outcomes().stream().map(RunResults.ScopeWorkResult::key).toList());
        assertEquals(
                finite.outcomes().stream().map(RunResults.ScopeWorkResult::type).toList(),
                durable.outcomes().stream().map(RunResults.ScopeWorkResult::type).toList());
        assertEquals(
                finite.outcomes().stream()
                        .map(RunResults.ScopeWorkResult::changesHierarchy)
                        .toList(),
                durable.outcomes().stream()
                        .map(RunResults.ScopeWorkResult::changesHierarchy)
                        .toList());
    }

    private record Fixture(
            ImageData<BufferedImage> image,
            ImageExecutionPlan plan,
            RecipeExecutorTestData.PublishedSourceFixture published,
            RecipeStaging.Target finiteStaging,
            RecordingRunner recording,
            LargeActionHandleStore.ResumableAction resumable,
            List<DurableRecipeModelInputAdapter.RecoveredRun> recoveredRuns,
            LargeActionHandleStore store) {}

    private record RecoveryFixture(
            LargeActionHandleStore.ResumableAction resumable,
            List<DurableRecipeModelInputAdapter.RecoveredRun> recoveredRuns,
            LargeActionHandleStore store) {}

    private static final class RecordingRunner implements RecipeExecutor.InputRunner {

        private final boolean emptyParent;
        private final Map<String, FiniteModelInputExecutor.PreparedInput> prepared =
                new LinkedHashMap<>();
        private final Map<String, InferenceFeatureCodec.FeatureCollection> features =
                new LinkedHashMap<>();

        private RecordingRunner(boolean emptyParent) {
            this.emptyParent = emptyParent;
        }

        @Override
        public CandidateGeneration execute(
                FiniteModelInputExecutor.PreparedInput input,
                java.util.function.BooleanSupplier shouldStop) {
            String stepId = input.request().producer().stepId();
            prepared.put(stepId, input);
            InferenceFeatureCodec.FeatureCollection output =
                    emptyParent && "parent".equals(stepId)
                            ? new InferenceFeatureCodec.FeatureCollection(List.of())
                            : featureCollection(
                                    "parent".equals(stepId) ? "Parent" : "Child",
                                    "parent".equals(stepId)
                                            ? RecipeExecutorTestData.square(10, 10, 30, 30)
                                            : RecipeExecutorTestData.square(2, 2, 6, 6));
            features.put(stepId, output);
            String callId = "call-" + stepId;
            FiniteInferenceCodec.InferenceResult raw =
                    new FiniteInferenceCodec.InferenceResult(
                            new FiniteInferenceCodec.BackendResultMeta(
                                    FiniteInferenceCodec.SCHEMA_VERSION,
                                    input.request().actionId(),
                                    input.request().outputGenerationId(),
                                    callId,
                                    input.request().inputId(),
                                    input.request().producer(),
                                    input.request().expectedBackend(),
                                    FiniteInferenceCodec.buildBackendInputIdentity(
                                            input.request().rasterInput()),
                                    FiniteInferenceCodec.CoordinateSpace.RASTER_LOCAL),
                            output);
            FiniteInferenceCodec.InferenceResult validated =
                    FiniteInferenceCodec.validateResult(
                            FiniteInferenceCodec.toJson(raw),
                            input.request(),
                            callId,
                            Set.copyOf(input.model().output().emittedClasses()));
            return CandidatePipeline.generateFinite(
                    input.request(),
                    validated,
                    input.plannedInput(),
                    new BackendExecutionCodec.FiniteCall(callId),
                    input.reservation(),
                    input.existingObjects());
        }

        List<String> stepIds() {
            return List.copyOf(prepared.keySet());
        }

        Map<String, FiniteModelInputExecutor.PreparedInput> preparedByStep() {
            return Map.copyOf(prepared);
        }

        Map<String, InferenceFeatureCodec.FeatureCollection> featuresByStep() {
            return Map.copyOf(features);
        }
    }

    private static InferenceFeatureCodec.FeatureCollection featureCollection(
            String pathClass, Geometry geometry) {
        JsonArray identity = new JsonArray();
        identity.add(pathClass);
        identity.add(CanonicalGeometryCodec.canonicalCoordinates(geometry));
        identity.add(0);
        InferenceFeatureCodec.Feature feature =
                new InferenceFeatureCodec.Feature(
                        CanonicalCodec.sha256(CanonicalCodec.canonicalBytes(identity)),
                        geometry,
                        pathClass);
        List<InferenceFeatureCodec.Feature> values = new ArrayList<>(List.of(feature));
        values.sort(Comparator.comparing(InferenceFeatureCodec.Feature::id));
        return new InferenceFeatureCodec.FeatureCollection(values);
    }
}

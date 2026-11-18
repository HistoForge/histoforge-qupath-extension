package qupath.ext.histoforge.pipeline;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProcessingEvidenceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.large.HierarchyTransactionDigester;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.HierarchyPolicy;

/** Pure final fence between recovered computation and one captured hierarchy transaction. */
final class LargeActionTransactionValidator {

    private LargeActionTransactionValidator() {}

    /** Deterministic reconstruction no longer matches the captured transaction or requests. */
    static final class Conflict extends IllegalArgumentException {
        Conflict(String message) {
            super(message);
        }

        Conflict(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Require the exact Redo removals, generation authority, class domain, and parent edges. */
    static void requireModelDelta(
            LargeActionHandle handle,
            HierarchyDelta delta,
            HierarchyTransactionDigester.RebaseCapture capture,
            Map<String, LargeActionHandleStore.AuthorizedModelInput> authorizedInputs,
            List<LargeActionHandleStore.AuthorizedModelRun> authorizedRuns) {
        if (!delta.actionId().equals(handle.actionId())) {
            throw new Conflict("reconstructed hierarchy delta belongs to another action");
        }
        if (!handle.baseline()
                .hierarchy()
                .replacementClosure()
                .equals(delta.replacementClosure())) {
            throw new Conflict(
                    "reconstructed delta does not retain the action's replacement closure");
        }
        if (!capture.replacementObjectIds().equals(delta.changes().removeObjectIds())) {
            throw new Conflict(
                    "reconstructed delta does not remove the exact captured replacement objects");
        }
        if (!capture.modelObjectIds()
                .containsAll(
                        delta.changes().classChanges().stream()
                                .map(HierarchyDelta.ClassChange::objectId)
                                .toList())) {
            throw new Conflict(
                    "reconstructed delta changes an object outside the captured transaction");
        }

        requireAuthorizedGenerations(handle, delta, authorizedInputs, authorizedRuns);
        requireExactModelParents(delta, capture.preservedDescendantAssignments());
    }

    private static void requireExactModelParents(
            HierarchyDelta delta,
            List<HierarchyDelta.ParentAssignment> preservedDescendantAssignments) {
        Set<HierarchyDelta.ParentAssignment> expected = new LinkedHashSet<>();
        for (HierarchyDelta.ObjectDraft draft : delta.changes().addObjects()) {
            expected.add(
                    new HierarchyDelta.ParentAssignment(
                            draft.objectId(),
                            HierarchyPolicy.expectedParent(draft.provenance().output().scope())));
        }
        expected.addAll(preservedDescendantAssignments);
        Set<HierarchyDelta.ParentAssignment> actual =
                new LinkedHashSet<>(delta.changes().parentAssignments());
        if (actual.size() != delta.changes().parentAssignments().size()
                || !actual.equals(expected)) {
            throw new Conflict(
                    "reconstructed delta does not retain the exact model hierarchy-policy edges");
        }
    }

    private static void requireAuthorizedGenerations(
            LargeActionHandle handle,
            HierarchyDelta delta,
            Map<String, LargeActionHandleStore.AuthorizedModelInput> authorizedInputs,
            List<LargeActionHandleStore.AuthorizedModelRun> authorizedRuns) {
        GenerationStoreCodec.GenerationStore store = delta.stagedGenerationStore();
        Map<String, ProcessingEvidenceCodec.ProcessingEvidence> expectedProcessing =
                new LinkedHashMap<>();
        for (var run : authorizedRuns) {
            var model = handle.module().activatedModels().get(run.stepId());
            for (var input : run.request().inputs()) {
                if (store.currentGenerationRecords().containsKey(input.outputGenerationId())) {
                    expectedProcessing.put(
                            input.outputGenerationId(),
                            ProcessingEvidenceCodec.expected(
                                    run.request(),
                                    input,
                                    model.capabilities()
                                            .durableRun()
                                            .orElseThrow()
                                            .run()
                                            .maxUnits()));
                }
            }
        }
        for (GenerationEvidenceCodec.GenerationRecord record :
                store.currentGenerationRecords().values()) {
            LargeActionHandleStore.AuthorizedModelInput authorized =
                    authorizedInputs.get(record.outputGenerationId());
            if (authorized == null || authorized.execution().isEmpty()) {
                throw new Conflict("staged generation has no completed canonical model-run input");
            }
            var input = authorized.input();
            var rasterInput = input.rasterInput();
            var evidence = record.inputEvidence();
            String clientDigest =
                    BuildIdentityCodec.clientBuildIdentityDigest(handle.clientBuild());
            String backendDigest =
                    InferenceIdentityCodec.backendModelIdentityDigest(authorized.backend());
            String sourceDigest =
                    RasterInputCodec.actionSourceRevisionDigest(
                            rasterInput.frame().sourceRevision());
            String targetDigest = ExecutionScopeCodec.targetRegionDigest(handle.target());

            if (!record.actionId().equals(handle.actionId())
                    || record.removeOverlappingSameClassDuplicates() != overlapPolicy(handle)
                    || !record.clientBuildDigest().equals(clientDigest)
                    || !record.deploymentId().equals(handle.module().deploymentId())
                    || !record.output().equals(input.output())
                    || record.inputId() != input.inputId()
                    || !record.backendIdentityDigest().equals(backendDigest)
                    || !record.execution().equals(authorized.execution().orElseThrow())
                    || !record.processingEvidence()
                            .equals(
                                    Optional.ofNullable(
                                            expectedProcessing.get(record.outputGenerationId())))
                    || record.processingEvidence().isEmpty()
                    || !evidence.modelRasterInputDigest().equals(rasterInput.inputDigest())
                    || !evidence.sourceRevisionDigest().equals(sourceDigest)
                    || !evidence.targetRegionDigest().equals(targetDigest)
                    || !evidence.sourceView().equals(rasterInput.sourceView())
                    || !evidence.rasterTransform().equals(rasterInput.frame().transform())
                    || !evidence.inclusionMaskDigest()
                            .equals(rasterInput.frame().inclusionMaskDigest())
                    || !evidence.rasterFrameDigest().equals(rasterInput.frame().frameDigest())) {
                throw new Conflict(
                        "staged generation does not match its canonical model-run input");
            }
            requireStoreReferences(handle, store, record, authorized);
        }
    }

    private static void requireStoreReferences(
            LargeActionHandle handle,
            GenerationStoreCodec.GenerationStore store,
            GenerationEvidenceCodec.GenerationRecord record,
            LargeActionHandleStore.AuthorizedModelInput authorized) {
        GenerationEvidenceCodec.CommittedInputEvidence evidence = record.inputEvidence();
        if (!Optional.ofNullable(store.sourceRevisions().get(evidence.sourceRevisionDigest()))
                        .filter(authorized.input().rasterInput().frame().sourceRevision()::equals)
                        .isPresent()
                || !Optional.ofNullable(store.targetRegions().get(evidence.targetRegionDigest()))
                        .filter(handle.target()::equals)
                        .isPresent()
                || !Optional.ofNullable(store.clientBuilds().get(record.clientBuildDigest()))
                        .filter(handle.clientBuild()::equals)
                        .isPresent()
                || !Optional.ofNullable(
                                store.backendIdentities().get(record.backendIdentityDigest()))
                        .filter(authorized.backend()::equals)
                        .isPresent()) {
            throw new Conflict(
                    "staged generation references state outside the captured large action");
        }
    }

    private static boolean overlapPolicy(LargeActionHandle handle) {
        return handle.action() instanceof qupath.ext.histoforge.contract.ActionSelectionCodec.Fill
                ? ((qupath.ext.histoforge.contract.ActionSelectionCodec.Fill) handle.action())
                        .removeOverlappingSameClassDuplicates()
                : ((qupath.ext.histoforge.contract.ActionSelectionCodec.Redo) handle.action())
                        .removeOverlappingSameClassDuplicates();
    }
}

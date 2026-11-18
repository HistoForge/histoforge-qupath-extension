package qupath.ext.histoforge.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.HierarchyPolicy;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Pure candidate-generation to hierarchy-delta transformation behind the committer boundary. */
final class ModelOutputStager {

    private ModelOutputStager() {}

    static HierarchyDelta stage(
            ModelOutputCommitter.StagingRequest request,
            PathObjectHierarchy hierarchy,
            OutputIndex outputIndex,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            long workingEpoch) {
        if (!limits.equals(request.clientBuild().limits())) {
            throw new IllegalArgumentException(
                    "staging client limits do not match the working-image limits");
        }

        Set<UUID> removals = replacementObjects(request.replacementClosure(), outputIndex);
        List<HierarchyDelta.ObjectDraft> drafts = new ArrayList<>();
        List<HierarchyDelta.ParentAssignment> assignments = new ArrayList<>();
        List<GenerationStoreCodec.GenerationStore> stagedStores = new ArrayList<>();
        Set<ExecutionScopeCodec.OutputKey> generationOutputs = new LinkedHashSet<>();
        Set<String> generationIds = new LinkedHashSet<>();
        Set<UUID> candidateIds = new LinkedHashSet<>();

        for (CandidateGeneration generation : request.generations()) {
            requireReservationMatchesRequest(request, generation.reservation());
            if (!generationOutputs.add(generation.output())) {
                throw new IllegalArgumentException(
                        "a target transaction contains multiple generations for one output");
            }
            if (!generationIds.add(generation.outputGenerationId())) {
                throw new IllegalArgumentException(
                        "a target transaction contains a duplicate output-generation ID");
            }
            if (outputIndex.contains(generation.output())
                    && !request.replacementClosure().contains(generation.output())) {
                throw new IllegalArgumentException(
                        "existing generation output is absent from the replacement closure");
            }
            if (!generation.candidates().isEmpty()) {
                ProvenanceCodec.ObjectProvenance provenance =
                        new ProvenanceCodec.ObjectProvenance(
                                ProvenanceCodec.SCHEMA_VERSION,
                                generation.output(),
                                generation.outputGenerationId());
                for (CandidateGeneration.SlideCandidate candidate : generation.candidates()) {
                    UUID candidateId = UUID.fromString(candidate.candidateId());
                    if (!candidateIds.add(candidateId)) {
                        throw new IllegalArgumentException(
                                "candidate UUIDs must be unique across the target transaction");
                    }
                    drafts.add(HierarchyDelta.ObjectDraft.fromCandidate(candidate, provenance));
                }
            }
            assignments.addAll(HierarchyPolicy.modelAssignments(generation));
            if (!generation.candidates().isEmpty()) {
                stagedStores.add(generationEvidence(generation, request.prospectiveCommittedAt()));
            }
        }

        if (!removals.isEmpty()) {
            RoiRegistry currentRois = RoiRegistry.fromHierarchy(hierarchy);
            assignments.addAll(
                    HierarchyPolicy.preservedDescendantAssignments(
                            hierarchy, removals, currentRois::findRoi));
        }
        return new HierarchyDelta(
                request.actionId(),
                request.replacementClosure(),
                workingEpoch,
                new HierarchyDelta.ChangeSet(removals, request.classChanges(), drafts, assignments),
                GenerationStoreCodec.merge(stagedStores));
    }

    private static Set<UUID> replacementObjects(
            Set<ExecutionScopeCodec.OutputKey> closure, OutputIndex outputIndex) {
        List<ExecutionScopeCodec.OutputKey> outputs = new ArrayList<>(closure);
        outputs.sort(
                (left, right) ->
                        Arrays.compareUnsigned(
                                ExecutionScopeCodec.canonicalBytes(left),
                                ExecutionScopeCodec.canonicalBytes(right)));
        Set<UUID> result = new LinkedHashSet<>();
        for (ExecutionScopeCodec.OutputKey output : outputs) {
            for (PathObject object : outputIndex.objects(output)) {
                result.add(
                        java.util.Objects.requireNonNull(object.getID(), "persistent object UUID"));
            }
        }
        return Set.copyOf(result);
    }

    /** Build the exact normalized evidence fragment for one nonempty completed generation. */
    static GenerationStoreCodec.GenerationStore generationEvidence(
            CandidateGeneration generation, Instant prospectiveCommittedAt) {
        Objects.requireNonNull(generation, "generation");
        Instant committedAt =
                Objects.requireNonNull(prospectiveCommittedAt, "prospective_committed_at");
        CanonicalCodec.rfc3339Millis(committedAt, "prospective_committed_at");
        ImageDataStateCodec.GenerationStoreReservation reservation = generation.reservation();
        var sourceRevision = reservation.sourceRevision();
        ExecutionScopeCodec.TargetRegion target = reservation.targetRegion();
        String sourceDigest = RasterInputCodec.actionSourceRevisionDigest(sourceRevision);
        String targetDigest = ExecutionScopeCodec.targetRegionDigest(target);
        String clientDigest =
                BuildIdentityCodec.clientBuildIdentityDigest(reservation.clientBuild());
        String backendDigest =
                InferenceIdentityCodec.backendModelIdentityDigest(reservation.backendIdentity());
        GenerationEvidenceCodec.GenerationRecord record =
                new GenerationEvidenceCodec.GenerationRecord(
                        generation.outputGenerationId(),
                        reservation.actionId(),
                        reservation.removeOverlappingSameClassDuplicates(),
                        clientDigest,
                        reservation.deploymentId(),
                        generation.output(),
                        generation.inputId(),
                        backendDigest,
                        generation.execution(),
                        generation.inputEvidence().committedEvidence(),
                        generation.counts(),
                        committedAt,
                        generation.processingEvidence());
        return new GenerationStoreCodec.GenerationStore(
                GenerationStoreCodec.SCHEMA_VERSION,
                Map.of(sourceDigest, sourceRevision),
                Map.of(targetDigest, target),
                Map.of(clientDigest, reservation.clientBuild()),
                Map.of(backendDigest, reservation.backendIdentity()),
                Map.of(record.outputGenerationId(), record));
    }

    private static void requireReservationMatchesRequest(
            ModelOutputCommitter.StagingRequest request,
            ImageDataStateCodec.GenerationStoreReservation reservation) {
        if (!reservation.actionId().equals(request.actionId())
                || reservation.removeOverlappingSameClassDuplicates()
                        != request.removeOverlappingSameClassDuplicates()
                || !reservation.clientBuild().equals(request.clientBuild())
                || !reservation.deploymentId().equals(request.deploymentId())) {
            throw new IllegalArgumentException(
                    "candidate generation context does not match its target transaction");
        }
    }
}

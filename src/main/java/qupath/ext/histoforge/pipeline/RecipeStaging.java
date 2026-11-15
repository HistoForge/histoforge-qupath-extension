package qupath.ext.histoforge.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.HierarchyDelta;

/** Process-local state needed to stage one recipe target transaction. */
final class RecipeStaging {

    private RecipeStaging() {}

    /** Exact completed evidence plus maximum reservations for only unfinished model inputs. */
    static final class Projection {
        private static final Instant SIZE_ONLY_COMMITTED_AT = Instant.EPOCH;

        private final List<GenerationStoreCodec.GenerationStore> stagedStores = new ArrayList<>();
        private final Map<String, ImageDataStateCodec.GenerationStoreReservation> plannedInputs =
                new LinkedHashMap<>();

        void plan(Collection<? extends RecipeModelInputAdapter.PreparedInput> inputs) {
            for (RecipeModelInputAdapter.PreparedInput input : inputs) {
                var reservation = Objects.requireNonNull(input, "prepared input").reservation();
                if (plannedInputs.putIfAbsent(reservation.outputGenerationId(), reservation)
                        != null) {
                    throw new IllegalArgumentException(
                            "projection contains a duplicate output generation ID");
                }
            }
        }

        void complete(CandidateGeneration generation) {
            CandidateGeneration value = Objects.requireNonNull(generation, "generation");
            ImageDataStateCodec.GenerationStoreReservation planned =
                    plannedInputs.remove(value.outputGenerationId());
            if (planned == null || !planned.equals(value.reservation())) {
                throw new IllegalArgumentException(
                        "completed generation does not match its planned reservation");
            }
            if (!value.candidates().isEmpty()) {
                stagedStores.add(
                        ModelOutputStager.generationEvidence(value, SIZE_ONLY_COMMITTED_AT));
            }
        }

        List<GenerationStoreCodec.GenerationStore> stagedStores() {
            return List.copyOf(stagedStores);
        }

        List<ImageDataStateCodec.GenerationStoreReservation> plannedReservations() {
            return List.copyOf(plannedInputs.values());
        }

        void requireComplete() {
            if (!plannedInputs.isEmpty()) {
                throw new IllegalStateException(
                        "target transaction completed with unresolved model inputs");
            }
        }
    }

    /** Complete immutable target work ready for hierarchy-delta validation. */
    record Target(
            List<ExecutionScopeCodec.TargetRegion> targets,
            Set<ExecutionScopeCodec.OutputKey> replacementClosure,
            List<CandidateGeneration> generations,
            List<HierarchyDelta.ClassChange> classChanges,
            List<RunResults.ScopeWorkResult> outcomes) {
        Target {
            targets = List.copyOf(targets);
            replacementClosure =
                    Collections.unmodifiableSet(new LinkedHashSet<>(replacementClosure));
            generations = List.copyOf(generations);
            classChanges = List.copyOf(classChanges);
            outcomes = List.copyOf(outcomes);
            Set<RunResults.OutcomeKey> keys = new LinkedHashSet<>();
            for (RunResults.ScopeWorkResult outcome : outcomes) {
                if (!keys.add(outcome.key())) {
                    throw new IllegalArgumentException(
                            "target transaction outcomes must have unique keys");
                }
            }
        }
    }
}

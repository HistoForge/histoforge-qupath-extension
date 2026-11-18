package qupath.ext.histoforge.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.state.OutputIndex;

/** Pure ROI-versus-slide materialization preflight over the derived current output index. */
public final class ProducerModePolicy {

    private ProducerModePolicy() {}

    /** Requested output mode derived from the plan's one nonmixed logical region family. */
    public enum RequestedMode {
        ROI,
        SLIDE
    }

    /**
     * Closed preflight result; Fill conflicts require explicit Redo rather than implicit clearing.
     */
    public sealed interface Decision permits Ready, CrossModeRequiresRedo {}

    /**
     * The action may proceed; nonempty entries are producer-wide outputs a Redo conversion owns.
     */
    public record Ready(
            RequestedMode requestedMode,
            Map<InferenceIdentityCodec.ModelStepIdentity, Set<ExecutionScopeCodec.OutputKey>>
                    producerWideReplacements)
            implements Decision {
        public Ready {
            requestedMode = Objects.requireNonNull(requestedMode, "requestedMode");
            producerWideReplacements = immutableReplacements(producerWideReplacements);
        }
    }

    /** Fill requested a producer mode that can only be entered by explicit producer-wide Redo. */
    public record CrossModeRequiresRedo(
            RequestedMode requestedMode,
            Set<InferenceIdentityCodec.ModelStepIdentity> conflictingProducers)
            implements Decision {
        public CrossModeRequiresRedo {
            requestedMode = Objects.requireNonNull(requestedMode, "requestedMode");
            if (conflictingProducers == null || conflictingProducers.isEmpty()) {
                throw new IllegalArgumentException("conflictingProducers must be nonempty");
            }
            conflictingProducers =
                    Collections.unmodifiableSet(new LinkedHashSet<>(conflictingProducers));
        }
    }

    /** Evaluate only materialization mode; ordinary Fill presence remains executor-owned. */
    public static Decision evaluate(ImageExecutionPlan plan, OutputIndex outputIndex) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(outputIndex, "outputIndex");
        if (plan.action() instanceof ActionSelectionCodec.Metrics) {
            throw new IllegalArgumentException("Metrics has no producer materialization mode");
        }
        RequestedMode requestedMode = requestedMode(plan.targetRegions());
        boolean redo = plan.action() instanceof ActionSelectionCodec.Redo;
        Set<InferenceIdentityCodec.ModelStepIdentity> conflicts = new LinkedHashSet<>();
        Map<InferenceIdentityCodec.ModelStepIdentity, Set<ExecutionScopeCodec.OutputKey>>
                replacements = new LinkedHashMap<>();

        for (ImageExecutionPlan.PlannedModelStep model : plan.modelsInRecipeOrder()) {
            InferenceIdentityCodec.ModelStepIdentity producer =
                    new InferenceIdentityCodec.ModelStepIdentity(
                            plan.module().module().moduleRef(), model.stepId());
            OutputIndex.ProducerMaterialization current = outputIndex.materialization(producer);
            if (!requiresConversion(requestedMode, current.mode())) {
                continue;
            }
            if (!redo) {
                conflicts.add(producer);
            } else {
                replacements.put(producer, current.currentOutputs());
            }
        }
        if (!conflicts.isEmpty()) {
            return new CrossModeRequiresRedo(requestedMode, conflicts);
        }
        return new Ready(requestedMode, replacements);
    }

    private static RequestedMode requestedMode(
            List<ExecutionScopeCodec.TargetRegion> targetRegions) {
        RequestedMode result = null;
        for (ExecutionScopeCodec.TargetRegion target : targetRegions) {
            RequestedMode current =
                    target.key() instanceof ExecutionScopeCodec.Roi
                            ? RequestedMode.ROI
                            : RequestedMode.SLIDE;
            if (result != null && result != current) {
                throw new IllegalArgumentException("execution plan mixes ROI and slide regions");
            }
            result = current;
        }
        return Objects.requireNonNull(result, "requested mode");
    }

    private static boolean requiresConversion(
            RequestedMode requested, OutputIndex.ProducerMaterializationMode current) {
        return switch (current) {
            case NONE -> false;
            case ROI -> requested == RequestedMode.SLIDE;
            case SLIDE -> requested == RequestedMode.ROI;
            case CONFLICTING -> true;
        };
    }

    private static Map<InferenceIdentityCodec.ModelStepIdentity, Set<ExecutionScopeCodec.OutputKey>>
            immutableReplacements(
                    Map<
                                    InferenceIdentityCodec.ModelStepIdentity,
                                    Set<ExecutionScopeCodec.OutputKey>>
                            replacements) {
        Objects.requireNonNull(replacements, "producerWideReplacements");
        Map<InferenceIdentityCodec.ModelStepIdentity, Set<ExecutionScopeCodec.OutputKey>> result =
                new LinkedHashMap<>();
        replacements.forEach(
                (producer, outputs) ->
                        result.put(
                                Objects.requireNonNull(producer, "producer"),
                                Collections.unmodifiableSet(
                                        new LinkedHashSet<>(
                                                Objects.requireNonNull(outputs, "outputs")))));
        return Collections.unmodifiableMap(result);
    }
}

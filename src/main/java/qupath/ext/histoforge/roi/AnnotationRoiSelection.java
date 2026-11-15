package qupath.ext.histoforge.roi;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Resolves an exact QuPath class to ordinary annotation boundaries, without modifying them. */
public final class AnnotationRoiSelection {
    private AnnotationRoiSelection() {}

    public static Set<String> ids(PathObjectHierarchy hierarchy, String pathClass) {
        String selected = CanonicalCodec.requirePathClassName(pathClass, "Annotation class");
        Set<String> ids = new TreeSet<>();
        for (var object : hierarchy.getAnnotationObjects()) {
            if (matches(object, selected)) {
                ids.add(object.getID().toString());
            }
        }
        return Set.copyOf(ids);
    }

    /** The same eligibility rule is used for project previews and authoritative capture. */
    public static boolean matches(PathObject object, String pathClass) {
        return RoiRegistry.isOrdinaryAreaAnnotation(object)
                && object.getPathClass() != null
                && object.getPathClass().toString().equals(pathClass);
    }

    /** Replace preview IDs with the current class selection before capturing any execution. */
    public static ActionSelectionCodec.ImageAction resolve(
            ActionSelectionCodec.ImageAction action,
            String pathClass,
            PathObjectHierarchy hierarchy) {
        var regions = regions(hierarchy, pathClass);
        Set<String> ids = regions.roiIds();
        if (action instanceof ActionSelectionCodec.Metrics) {
            return new ActionSelectionCodec.Metrics(regions);
        }
        var original =
                action instanceof ActionSelectionCodec.Fill fill
                        ? fill.work()
                        : ((ActionSelectionCodec.Redo) action).work();
        ActionSelectionCodec.ModelWorkSelection work;
        if (original instanceof ActionSelectionCodec.AllModels) {
            work = new ActionSelectionCodec.AllModels(regions);
        } else {
            List<ActionSelectionCodec.TargetSelector> targets =
                    ids.stream()
                            .map(ActionSelectionCodec.RoiTarget::new)
                            .map(ActionSelectionCodec.TargetSelector.class::cast)
                            .toList();
            var steps =
                    ((ActionSelectionCodec.SelectedModels) original)
                            .steps().stream()
                                    .map(
                                            step ->
                                                    new ActionSelectionCodec.StepTargets(
                                                            step.stepId(),
                                                            new ActionSelectionCodec
                                                                    .TargetSelection(targets)))
                                    .toList();
            work = new ActionSelectionCodec.SelectedModels(regions, steps);
        }
        return action instanceof ActionSelectionCodec.Fill fill
                ? new ActionSelectionCodec.Fill(work, fill.removeOverlappingSameClassDuplicates())
                : new ActionSelectionCodec.Redo(
                        work,
                        ((ActionSelectionCodec.Redo) action)
                                .removeOverlappingSameClassDuplicates());
    }

    public static ActionSelectionCodec.RoiRegions regions(
            PathObjectHierarchy hierarchy, String pathClass) {
        Set<String> ids = ids(hierarchy, pathClass);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException(
                    "No area annotations have class \"" + pathClass + "\" in this image.");
        }
        return new ActionSelectionCodec.RoiRegions(ids);
    }
}

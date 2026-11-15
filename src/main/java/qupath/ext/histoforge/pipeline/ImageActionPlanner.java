package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputType;
import qupath.ext.histoforge.modules.RecipeDependencyGraph;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.interfaces.ROI;

/** Resolves one explicit action into immutable regions and recipe-ordered model work. */
public final class ImageActionPlanner {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private ImageActionPlanner() {}

    /**
     * Build one nonmutating image plan from a captured module and current hierarchy snapshot.
     *
     * <p>Dynamic source-parent eligibility and Fill presence are deliberately resolved later by the
     * staged executor. This method owns only static selection, downstream closure, target geometry
     * capture, and metric-region derivation.
     */
    public static ImageExecutionPlan plan(
            String actionId,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            String imageId,
            ModuleSnapshot module,
            ActionSelectionCodec.ImageAction action,
            PathObjectHierarchy hierarchy,
            int imageWidth,
            int imageHeight) {
        Objects.requireNonNull(clientBuild, "clientBuild");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(hierarchy, "hierarchy");
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }

        RoiRegistry roiRegistry = RoiRegistry.fromHierarchy(hierarchy);
        RecipeDependencyGraph graph = RecipeDependencyGraph.from(module.recipe());
        List<String> requiredModels = graph.requiredModelIds(action);
        requireActivatedModels(module, requiredModels);

        final List<ImageExecutionPlan.PlannedModelStep> models;
        final ActionSelectionCodec.RegionSelection affectedRegions;
        final Optional<ActionSelectionCodec.RegionSelection> metricSelection;
        if (action instanceof ActionSelectionCodec.Metrics metrics) {
            if (module.metrics().isEmpty()) {
                throw new IllegalArgumentException("Metrics requires a captured metrics script");
            }
            models = List.of();
            affectedRegions = metrics.targets();
            metricSelection = Optional.of(affectedRegions);
        } else {
            ActionSelectionCodec.ModelWorkSelection work = modelWork(action);
            models = planModels(module, action, work, graph, requiredModels);
            affectedRegions = affectedRegions(work, models);
            metricSelection =
                    module.metrics().isPresent() ? Optional.of(affectedRegions) : Optional.empty();
        }

        List<ExecutionScopeCodec.TargetRegion> targets =
                captureRegions(affectedRegions, roiRegistry, hierarchy, imageWidth, imageHeight);
        return new ImageExecutionPlan(
                actionId, clientBuild, imageId, module, action, targets, models, metricSelection);
    }

    /** Capture exact ROI or slide targets without deriving or executing model work. */
    public static List<ExecutionScopeCodec.TargetRegion> captureRegions(
            ActionSelectionCodec.RegionSelection selection,
            PathObjectHierarchy hierarchy,
            int imageWidth,
            int imageHeight) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(hierarchy, "hierarchy");
        return captureRegions(
                selection,
                RoiRegistry.fromHierarchy(hierarchy),
                hierarchy,
                imageWidth,
                imageHeight);
    }

    private static List<ImageExecutionPlan.PlannedModelStep> planModels(
            ModuleSnapshot module,
            ActionSelectionCodec.ImageAction action,
            ActionSelectionCodec.ModelWorkSelection work,
            RecipeDependencyGraph graph,
            List<String> requiredModels) {
        if (work instanceof ActionSelectionCodec.AllModels all) {
            ActionSelectionCodec.TargetSelection targets = broadTargets(all.regions());
            return requiredModels.stream()
                    .map(
                            stepId -> {
                                requireCompatibleTargets(
                                        module.activatedModels().get(stepId), targets, stepId);
                                return new ImageExecutionPlan.PlannedModelStep(stepId, targets);
                            })
                    .toList();
        }

        ActionSelectionCodec.SelectedModels selected = (ActionSelectionCodec.SelectedModels) work;
        Map<String, ActionSelectionCodec.TargetSelection> explicit = new LinkedHashMap<>();
        for (ActionSelectionCodec.StepTargets step : selected.steps()) {
            ModuleSnapshot.ModelSnapshot model = module.activatedModels().get(step.stepId());
            if (model == null) {
                throw new IllegalArgumentException(
                        "selected model is absent from the executable snapshot: " + step.stepId());
            }
            requireCompatibleTargets(model, step.targets(), step.stepId());
            explicit.put(step.stepId(), step.targets());
        }

        Map<String, LinkedHashSet<ActionSelectionCodec.TargetSelector>> targetsByModel =
                new LinkedHashMap<>();
        requiredModels.forEach(stepId -> targetsByModel.put(stepId, new LinkedHashSet<>()));
        if (action instanceof ActionSelectionCodec.Fill) {
            explicit.forEach(
                    (stepId, targets) -> targetsByModel.get(stepId).addAll(targets.selectors()));
        } else {
            for (Map.Entry<String, ActionSelectionCodec.TargetSelection> seed :
                    explicit.entrySet()) {
                targetsByModel.get(seed.getKey()).addAll(seed.getValue().selectors());
                Set<ActionSelectionCodec.TargetSelector> broad =
                        coarseTargets(seed.getValue().selectors());
                for (String downstream : graph.downstreamModelClosure(Set.of(seed.getKey()))) {
                    if (!downstream.equals(seed.getKey())) {
                        targetsByModel.get(downstream).addAll(broad);
                    }
                }
            }
        }

        List<ImageExecutionPlan.PlannedModelStep> planned = new ArrayList<>(requiredModels.size());
        for (String stepId : requiredModels) {
            ActionSelectionCodec.TargetSelection targets =
                    normalizedTargets(targetsByModel.get(stepId));
            requireCompatibleTargets(module.activatedModels().get(stepId), targets, stepId);
            planned.add(new ImageExecutionPlan.PlannedModelStep(stepId, targets));
        }
        return List.copyOf(planned);
    }

    private static void requireActivatedModels(ModuleSnapshot module, List<String> requiredModels) {
        Set<String> expected = new LinkedHashSet<>(requiredModels);
        if (!module.activatedModels().keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    "captured module models do not match the action dependency closure");
        }
    }

    private static void requireCompatibleTargets(
            ModuleSnapshot.ModelSnapshot model,
            ActionSelectionCodec.TargetSelection targets,
            String stepId) {
        Objects.requireNonNull(model, "activated model " + stepId);
        if (model.input().type() == ModelInputType.CROP) {
            return;
        }
        for (ActionSelectionCodec.TargetSelector selector : targets.selectors()) {
            if (selector instanceof ActionSelectionCodec.ParentTarget) {
                throw new IllegalArgumentException(
                        "field model cannot use parent-target: " + stepId);
            }
        }
    }

    private static ActionSelectionCodec.TargetSelection broadTargets(
            ActionSelectionCodec.RegionSelection regions) {
        if (regions instanceof ActionSelectionCodec.SlideRegion) {
            return new ActionSelectionCodec.TargetSelection(
                    List.of(new ActionSelectionCodec.SlideTarget()));
        }
        List<ActionSelectionCodec.TargetSelector> targets =
                ((ActionSelectionCodec.RoiRegions) regions)
                        .roiIds().stream()
                                .map(ActionSelectionCodec.RoiTarget::new)
                                .map(ActionSelectionCodec.TargetSelector.class::cast)
                                .toList();
        return new ActionSelectionCodec.TargetSelection(targets);
    }

    private static Set<ActionSelectionCodec.TargetSelector> coarseTargets(
            Collection<ActionSelectionCodec.TargetSelector> selectors) {
        Set<ActionSelectionCodec.TargetSelector> result = new LinkedHashSet<>();
        for (ActionSelectionCodec.TargetSelector selector : selectors) {
            if (selector instanceof ActionSelectionCodec.ParentTarget parent) {
                ExecutionScopeCodec.Roi roi = (ExecutionScopeCodec.Roi) parent.region();
                result.add(new ActionSelectionCodec.RoiTarget(roi.roiId()));
            } else {
                result.add(selector);
            }
        }
        return result;
    }

    private static ActionSelectionCodec.TargetSelection normalizedTargets(
            Collection<ActionSelectionCodec.TargetSelector> selectors) {
        if (selectors == null || selectors.isEmpty()) {
            throw new IllegalArgumentException("planned model targets must be nonempty");
        }
        Set<ExecutionScopeCodec.RegionKey> broadRegions = new LinkedHashSet<>();
        for (ActionSelectionCodec.TargetSelector selector : selectors) {
            if (selector instanceof ActionSelectionCodec.RoiTarget roi) {
                broadRegions.add(new ExecutionScopeCodec.Roi(roi.roiId()));
            } else if (selector instanceof ActionSelectionCodec.SlideTarget) {
                broadRegions.add(new ExecutionScopeCodec.Slide());
            }
        }
        List<ActionSelectionCodec.TargetSelector> normalized = new ArrayList<>();
        for (ActionSelectionCodec.TargetSelector selector : selectors) {
            if (selector instanceof ActionSelectionCodec.ParentTarget parent
                    && broadRegions.contains(parent.region())) {
                continue;
            }
            normalized.add(selector);
        }
        return new ActionSelectionCodec.TargetSelection(normalized);
    }

    private static ActionSelectionCodec.RegionSelection affectedRegions(
            ActionSelectionCodec.ModelWorkSelection work,
            List<ImageExecutionPlan.PlannedModelStep> models) {
        ActionSelectionCodec.RegionSelection owner = regions(work);
        if (owner instanceof ActionSelectionCodec.SlideRegion) {
            return owner;
        }
        Set<String> roiIds = new TreeSet<>();
        for (ImageExecutionPlan.PlannedModelStep model : models) {
            for (ActionSelectionCodec.TargetSelector selector : model.targets().selectors()) {
                if (selector instanceof ActionSelectionCodec.RoiTarget roi) {
                    roiIds.add(roi.roiId());
                } else if (selector instanceof ActionSelectionCodec.ParentTarget parent) {
                    roiIds.add(((ExecutionScopeCodec.Roi) parent.region()).roiId());
                }
            }
        }
        return new ActionSelectionCodec.RoiRegions(roiIds);
    }

    private static List<ExecutionScopeCodec.TargetRegion> captureRegions(
            ActionSelectionCodec.RegionSelection selection,
            RoiRegistry registry,
            PathObjectHierarchy hierarchy,
            int imageWidth,
            int imageHeight) {
        if (selection instanceof ActionSelectionCodec.RoiRegions rois) {
            return registry.captureTargets(rois.roiIds(), imageWidth, imageHeight);
        }
        ActionSelectionCodec.SlideRegion slide = (ActionSelectionCodec.SlideRegion) selection;
        return List.of(captureSlideRegion(slide.region(), hierarchy, imageWidth, imageHeight));
    }

    private static ExecutionScopeCodec.TargetRegion captureSlideRegion(
            ActionSelectionCodec.SlideRegionSelection selection,
            PathObjectHierarchy hierarchy,
            int imageWidth,
            int imageHeight) {
        final Geometry geometry;
        final SourceIdentityCodec.ImagePlaneIndex plane;
        if (selection instanceof ActionSelectionCodec.FullSlide) {
            geometry = GEOMETRY_FACTORY.toGeometry(new Envelope(0, imageWidth, 0, imageHeight));
            plane = new SourceIdentityCodec.ImagePlaneIndex(0, 0);
        } else {
            ActionSelectionCodec.AnnotationUnion union =
                    (ActionSelectionCodec.AnnotationUnion) selection;
            Map<String, PathObject> objects = objectsById(hierarchy);
            List<Geometry> geometries = new ArrayList<>(union.annotationIds().size());
            SourceIdentityCodec.ImagePlaneIndex selectedPlane = null;
            for (String annotationId : union.annotationIds()) {
                PathObject object = objects.get(annotationId);
                if (object == null || !object.isAnnotation()) {
                    throw new IllegalArgumentException(
                            "annotation-union object is missing or is not an annotation: "
                                    + annotationId);
                }
                ROI roi = object.getROI();
                if (roi == null || !roi.isArea()) {
                    throw new IllegalArgumentException(
                            "annotation-union object must have area geometry: " + annotationId);
                }
                Geometry item =
                        validatedGeometry(
                                NativeRoiGeometry.convert(roi), "annotation-union geometry");
                requireInsideImage(item, imageWidth, imageHeight, annotationId);
                SourceIdentityCodec.ImagePlaneIndex itemPlane =
                        new SourceIdentityCodec.ImagePlaneIndex(
                                roi.getImagePlane().getZ(), roi.getImagePlane().getT());
                if (selectedPlane != null && !selectedPlane.equals(itemPlane)) {
                    throw new IllegalArgumentException(
                            "annotation-union objects must lie on one image plane");
                }
                selectedPlane = itemPlane;
                geometries.add(item);
            }
            geometry = validatedGeometry(UnaryUnionOp.union(geometries), "annotation union");
            plane = Objects.requireNonNull(selectedPlane, "annotation-union plane");
        }
        return new ExecutionScopeCodec.TargetRegion(
                new ExecutionScopeCodec.Slide(),
                geometry,
                plane,
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    private static Map<String, PathObject> objectsById(PathObjectHierarchy hierarchy) {
        Map<String, PathObject> result = new LinkedHashMap<>();
        for (PathObject object : hierarchy.getAllObjects(false)) {
            String objectId = object.getID().toString();
            if (result.putIfAbsent(objectId, object) != null) {
                throw new IllegalArgumentException(
                        "hierarchy contains duplicate object UUID " + objectId);
            }
        }
        return result;
    }

    private static Geometry validatedGeometry(Geometry geometry, String path) {
        Geometry copy = Objects.requireNonNull(geometry, path).copy();
        try {
            CanonicalGeometryCodec.canonicalCoordinates(copy);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    path + " must be a valid Polygon/MultiPolygon", error);
        }
        return copy;
    }

    private static void requireInsideImage(
            Geometry geometry, int width, int height, String objectId) {
        Envelope bounds = geometry.getEnvelopeInternal();
        if (bounds.getMinX() < 0.0
                || bounds.getMinY() < 0.0
                || bounds.getMaxX() > width
                || bounds.getMaxY() > height) {
            throw new IllegalArgumentException(
                    "selected annotation lies outside image bounds: " + objectId);
        }
    }

    private static ActionSelectionCodec.ModelWorkSelection modelWork(
            ActionSelectionCodec.ImageAction action) {
        return action instanceof ActionSelectionCodec.Fill fill
                ? fill.work()
                : ((ActionSelectionCodec.Redo) action).work();
    }

    private static ActionSelectionCodec.RegionSelection regions(
            ActionSelectionCodec.ModelWorkSelection work) {
        return work instanceof ActionSelectionCodec.AllModels all
                ? all.regions()
                : ((ActionSelectionCodec.SelectedModels) work).regions();
    }
}

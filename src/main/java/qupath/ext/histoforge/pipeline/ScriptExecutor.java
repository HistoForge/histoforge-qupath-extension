package qupath.ext.histoforge.pipeline;

import groovy.lang.Binding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.ObjectIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathTileObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryROI;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

/** Executes one trusted ordinary Groovy step against a detached, class-only object population. */
public final class ScriptExecutor {

    private ScriptExecutor() {}

    /** Exact target and image calibration bound into one target-local script invocation. */
    public record ScriptContext(
            // Stable project image identity used in the synthetic target UUID.
            String imageId,
            // Captured target geometry and plane represented by the legacy {@code roi} binding.
            ExecutionScopeCodec.TargetRegion target,
            // Authoritative source-pixel calibration exposed to the script API.
            SourceIdentityCodec.PixelCalibration calibration) {
        public ScriptContext {
            imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
            target = Objects.requireNonNull(target, "target");
            calibration = Objects.requireNonNull(calibration, "calibration");
            if (!(target.geometrySlide().getArea() > 0.0)) {
                throw new IllegalArgumentException("script target must have positive area");
            }
        }
    }

    /**
     * Run one ordinary script and return only its validated class transitions.
     *
     * <p>{@code stagedRegionObjects} is the caller's complete target-local staged view. This method
     * derives recipe visibility, clones and UUID-sorts the eligible objects, and never exposes the
     * originals to Groovy. Any mutation outside classification fails after execution.
     */
    public static List<HierarchyDelta.ClassChange> execute(
            ModuleSnapshot module,
            String scriptFilename,
            ScriptContext context,
            Collection<PathObject> stagedRegionObjects) {
        return execute(module, scriptFilename, context, stagedRegionObjects, object -> false);
    }

    /** Reuse accepted model outlines; scripts still receive independent, checked copies. */
    static List<HierarchyDelta.ClassChange> executeStaged(
            ModuleSnapshot module,
            String scriptFilename,
            ScriptContext context,
            StagedRegionView view) {
        return execute(
                module, scriptFilename, context, view.objects(), view::isValidatedPolygonCandidate);
    }

    private static List<HierarchyDelta.ClassChange> execute(
            ModuleSnapshot module,
            String scriptFilename,
            ScriptContext context,
            Collection<PathObject> stagedRegionObjects,
            Predicate<PathObject> validatedSourceOutline) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(context, "context");
        RecipePopulationPolicy.OrdinaryScriptPlan plan =
                RecipePopulationPolicy.ordinaryScriptPlan(module, scriptFilename);
        SourceIdentityCodec.PixelCalibration calibration =
                ScriptCalibrationGate.requireSupported(
                        context.calibration().mppX(), context.calibration().mppY());
        List<PathObject> sourcePopulation =
                sourcePopulation(
                        module,
                        plan,
                        context.target(),
                        stagedRegionObjects,
                        validatedSourceOutline);
        List<PathObject> clones =
                sourcePopulation.stream().map(ScriptExecutor::detachedClone).toList();
        List<PathObject> children = Collections.unmodifiableList(new ArrayList<>(clones));
        PathObject targetBinding = targetBinding(context);

        List<String> beforeClasses = clones.stream().map(ScriptExecutor::requiredClass).toList();
        List<String> beforeNonClass =
                clones.stream()
                        .map(ImageDataRevisionDigester::objectNonClassSemanticDigest)
                        .toList();
        List<GeometryState> beforeGeometries =
                clones.stream().map(ScriptExecutor::geometryState).toList();
        String targetBefore = ImageDataRevisionDigester.objectSemanticDigest(targetBinding);

        Binding binding = new Binding();
        ScriptBindings.applyCommon(
                binding,
                children,
                context.target().geometrySlide().getArea(),
                calibration.mppX(),
                calibration.mppY());
        binding.setVariable(ScriptBindings.ROI, targetBinding);
        Object configBinding = binding.getVariable(ScriptBindings.CONFIG);
        Object utilityBinding = binding.getVariable(ScriptBindings.UTILS);

        var script = ScriptRuntime.newShell().parse(plan.script().source());
        script.setBinding(binding);
        script.run();

        requireSameBinding(binding, ScriptBindings.CHILDREN, children);
        requireSameBinding(binding, ScriptBindings.ROI, targetBinding);
        requireSameBinding(binding, ScriptBindings.CONFIG, configBinding);
        requireSameBinding(binding, ScriptBindings.UTILS, utilityBinding);
        requireEqualBinding(
                binding, ScriptBindings.ROI_AREA_PX, context.target().geometrySlide().getArea());
        requireEqualBinding(binding, ScriptBindings.PIXEL_WIDTH_MICRONS, calibration.mppX());
        requireEqualBinding(binding, ScriptBindings.PIXEL_HEIGHT_MICRONS, calibration.mppY());
        if (!targetBefore.equals(ImageDataRevisionDigester.objectSemanticDigest(targetBinding))) {
            throw new IllegalArgumentException("ordinary script mutated the target binding");
        }

        List<HierarchyDelta.ClassChange> mutations = new ArrayList<>();
        for (int index = 0; index < clones.size(); index++) {
            PathObject clone = clones.get(index);
            if (children.get(index) != clone) {
                throw new IllegalArgumentException(
                        "ordinary script changed the children list identity or order");
            }
            if (!beforeNonClass
                    .get(index)
                    .equals(ImageDataRevisionDigester.objectNonClassSemanticDigest(clone))) {
                throw new IllegalArgumentException(
                        "ordinary script changed a non-class object field: " + clone.getID());
            }
            if (!beforeGeometries.get(index).matches(clone, sourcePopulation.get(index))) {
                throw new IllegalArgumentException(
                        "ordinary script changed object geometry: " + clone.getID());
            }
            String afterClass = requiredClass(clone);
            String beforeClass = beforeClasses.get(index);
            if (beforeClass.equals(afterClass)) {
                continue;
            }
            if (!plan.allowedResultClasses().contains(afterClass)) {
                throw new IllegalArgumentException(
                        "ordinary script assigned an undeclared class: " + afterClass);
            }
            mutations.add(new HierarchyDelta.ClassChange(clone.getID(), beforeClass, afterClass));
        }
        return List.copyOf(mutations);
    }

    private static List<PathObject> sourcePopulation(
            ModuleSnapshot module,
            RecipePopulationPolicy.OrdinaryScriptPlan plan,
            ExecutionScopeCodec.TargetRegion target,
            Collection<PathObject> stagedRegionObjects,
            Predicate<PathObject> validatedSourceOutline) {
        Objects.requireNonNull(stagedRegionObjects, "staged_region_objects");
        List<PathObject> population = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (PathObject object : stagedRegionObjects) {
            PathObject value = Objects.requireNonNull(object, "staged region object");
            if ((!validatedSourceOutline.test(value) && !isPolygonalDetectionOrAnnotation(value))
                    || RoiRegistry.isAdministrativeRoi(value)) {
                continue;
            }
            if (value.getPathClass() == null) {
                continue;
            }
            String pathClass = requiredClass(value);
            Optional<ProvenanceCodec.ObjectProvenance> provenance =
                    value.hasMetadata()
                            ? ProvenanceCodec.readMetadata(value.getMetadata())
                            : Optional.empty();
            boolean visible;
            if (provenance.isPresent()) {
                var output = provenance.orElseThrow().output();
                visible =
                        output.producer().moduleRef().equals(module.module().moduleRef())
                                && plan.earlierModels().contains(output.producer().stepId())
                                && ExecutionScopeCodec.regionOf(output.scope())
                                        .equals(target.key());
            } else {
                visible = plan.visibleManualClasses().contains(pathClass);
            }
            if (!visible) {
                continue;
            }
            UUID id = Objects.requireNonNull(value.getID(), "persistent object UUID");
            if (!ids.add(id)) {
                throw new IllegalArgumentException(
                        "staged script population contains duplicate object UUID " + id);
            }
            population.add(value);
        }
        population.sort(
                (left, right) -> left.getID().toString().compareTo(right.getID().toString()));
        return List.copyOf(population);
    }

    private static boolean isPolygonalDetectionOrAnnotation(PathObject object) {
        if ((!object.isDetection() && !object.isAnnotation())
                || object.getROI() == null
                || !object.getROI().isArea()) {
            return false;
        }
        Geometry geometry = NativeRoiGeometry.convert(object.getROI());
        if (!(geometry instanceof Polygon || geometry instanceof MultiPolygon)) {
            return false;
        }
        CanonicalGeometryCodec.validatePolygonalGeometry(geometry);
        if (!(geometry.getArea() > 0.0)) {
            throw new IllegalArgumentException(
                    "script population object must have positive-area geometry");
        }
        return true;
    }

    /** Deep detached copy used by both the script boundary and target transaction staging. */
    static PathObject detachedClone(PathObject source) {
        ROI roi = detachedRoi(Objects.requireNonNull(source.getROI(), "source ROI"));
        PathObject clone;
        if (source instanceof PathCellObject cell) {
            ROI nucleus = cell.getNucleusROI();
            clone =
                    PathObjects.createCellObject(
                            roi,
                            nucleus == null ? null : detachedRoi(nucleus),
                            source.getPathClass());
        } else if (source instanceof PathTileObject) {
            clone = PathObjects.createTileObject(roi, source.getPathClass());
        } else if (source instanceof PathDetectionObject) {
            clone = PathObjects.createDetectionObject(roi, source.getPathClass());
        } else if (source instanceof PathAnnotationObject) {
            clone = PathObjects.createAnnotationObject(roi, source.getPathClass());
        } else {
            throw new IllegalArgumentException(
                    "unsupported script population object type " + source.getClass().getName());
        }
        if (source.hasMeasurements()) {
            clone.getMeasurementList().putAll(source.getMeasurementList());
            clone.getMeasurementList().close();
        }
        clone.setName(source.getName());
        clone.setColor(source.getColor());
        clone.setLocked(source.isLocked());
        if (source.hasMetadata()) {
            clone.getMetadata().putAll(source.getMetadata());
        }
        clone.setID(source.getID());
        clone.setPathClass(source.getPathClass(), source.getClassProbability());
        return clone;
    }

    private static ROI detachedRoi(ROI source) {
        ROI copy = source.duplicate();
        // QuPath 0.6/0.7 GeometryROI copies may keep the source geometry in their point-location
        // cache. A second copy makes that cache private too, without changing the native ROI type.
        return copy instanceof GeometryROI ? copy.duplicate() : copy;
    }

    private static GeometryState geometryState(PathObject object) {
        // Snapshot the detached host representation here, not the scientific native-point
        // conversion. Ordinary scripts can access its mutable geometry cache; those edits must
        // still fail, even though HistoForge's spatial calculations do not use that cache.
        Geometry nucleus =
                object instanceof PathCellObject cell && cell.getNucleusROI() != null
                        ? cell.getNucleusROI().getGeometry().copy()
                        : null;
        return new GeometryState(
                ScriptGeometrySnapshot.capture(object.getROI().getGeometry()), nucleus);
    }

    private static PathObject targetBinding(ScriptContext context) {
        ExecutionScopeCodec.TargetRegion target = context.target();
        ImagePlane plane = ImagePlane.getPlane(target.plane().z(), target.plane().t());
        PathObject binding =
                PathObjects.createAnnotationObject(
                        GeometryTools.geometryToROI(target.geometrySlide(), plane));
        binding.setID(
                UUID.fromString(
                        ObjectIdentityCodec.scriptTargetId(
                                context.imageId(), target.key(), target.geometryDigest())));
        return binding;
    }

    private static String requiredClass(PathObject object) {
        if (object.getPathClass() == null) {
            throw new IllegalArgumentException(
                    "script population objects must have a PathClass: " + object.getID());
        }
        return CanonicalCodec.requirePathClassName(
                object.getPathClass().toString(), "script object PathClass");
    }

    private static void requireSameBinding(Binding binding, String name, Object expected) {
        if (!binding.hasVariable(name) || binding.getVariable(name) != expected) {
            throw new IllegalArgumentException("ordinary script replaced binding " + name);
        }
    }

    private static void requireEqualBinding(Binding binding, String name, Object expected) {
        if (!binding.hasVariable(name) || !Objects.equals(binding.getVariable(name), expected)) {
            throw new IllegalArgumentException("ordinary script replaced binding " + name);
        }
    }

    private record GeometryState(ScriptGeometrySnapshot outline, Geometry nucleus) {
        private boolean matches(PathObject object, PathObject source) {
            if (!outline.matches(
                    object.getROI().getGeometry(),
                    () -> detachedRoi(source.getROI()).getGeometry())) {
                return false;
            }
            Geometry currentNucleus =
                    object instanceof PathCellObject cell && cell.getNucleusROI() != null
                            ? cell.getNucleusROI().getGeometry()
                            : null;
            return nucleus == null
                    ? currentNucleus == null
                    : currentNucleus != null && nucleus.equalsExact(currentNucleus);
        }
    }
}

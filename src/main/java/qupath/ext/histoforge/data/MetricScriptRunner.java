package qupath.ext.histoforge.data;

import groovy.lang.Binding;
import groovy.lang.Script;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.ObjectIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.ext.histoforge.pipeline.ScriptBindings;
import qupath.ext.histoforge.pipeline.ScriptRuntime;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;

/** Exact-source Groovy compilation, detached hierarchy reconstruction, and metric bindings. */
final class MetricScriptRunner implements MetricsEngine.ScriptInvoker {

    private static final Logger logger = LoggerFactory.getLogger(MetricScriptRunner.class);
    private final Map<String, Class<? extends Script>> compiled = new ConcurrentHashMap<>();

    @Override
    public void prepare(MetricScriptSnapshot source) {
        compiled.computeIfAbsent(source.digest(), ignored -> compile(source));
    }

    @Override
    public Object run(
            MetricScriptSnapshot source,
            MetricRegionSnapshot region,
            String imageId,
            double pixelWidthMicrons,
            double pixelHeightMicrons) {
        Class<? extends Script> type = compiled.get(source.digest());
        if (type == null) {
            throw new IllegalStateException("metric script was not prepared");
        }
        Script script = instantiate(type);
        long started = System.nanoTime();
        logger.debug(
                "Metrics script-object construction started: {} objects for image {}, region {}",
                region.objects().size(),
                imageId,
                region.target().key());
        ReconstructedRegion reconstructed = reconstruct(region, imageId);
        logger.debug(
                "Metrics script-object construction finished in {} ms for image {}, region {}",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                imageId,
                region.target().key());
        Binding binding = new Binding();
        ScriptBindings.applyCommon(
                binding,
                reconstructed.children(),
                region.target().geometrySlide().getArea(),
                pixelWidthMicrons,
                pixelHeightMicrons);
        binding.setVariable(ScriptBindings.ROI, reconstructed.target());
        script.setBinding(binding);
        started = System.nanoTime();
        logger.debug(
                "Metrics script execution started for image {}, region {}, script {}",
                imageId,
                region.target().key(),
                source.digest());
        Object result = script.run();
        logger.debug(
                "Metrics script execution finished in {} ms for image {}, region {}, script {}",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                imageId,
                region.target().key(),
                source.digest());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Script> compile(MetricScriptSnapshot source) {
        long started = System.nanoTime();
        logger.debug("Metrics script compilation started for {}", source.digest());
        Script parsed = ScriptRuntime.newShell().parse(source.source());
        logger.debug(
                "Metrics script compilation finished in {} ms for {}",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                source.digest());
        return (Class<? extends Script>) parsed.getClass();
    }

    private static Script instantiate(Class<? extends Script> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException
                | NoSuchMethodException failure) {
            throw new IllegalStateException("cannot instantiate compiled metric script", failure);
        }
    }

    private static ReconstructedRegion reconstruct(MetricRegionSnapshot region, String imageId) {
        ImagePlane plane =
                ImagePlane.getPlane(region.target().plane().z(), region.target().plane().t());
        PathObject target =
                PathObjects.createAnnotationObject(
                        GeometryTools.geometryToROI(region.target().geometrySlide(), plane));
        target.setID(
                UUID.fromString(
                        ObjectIdentityCodec.scriptTargetId(
                                imageId, region.target().key(), region.target().geometryDigest())));

        Map<UUID, PathObject> objects = new LinkedHashMap<>();
        for (MetricObjectSnapshot snapshot : region.objects()) {
            PathObject object =
                    snapshot.objectType() == MetricTypes.MetricObjectType.ANNOTATION
                            ? PathObjects.createAnnotationObject(
                                    snapshot.scriptRoi(plane),
                                    PathClass.fromString(snapshot.pathClass()))
                            : PathObjects.createDetectionObject(
                                    snapshot.scriptRoi(plane),
                                    PathClass.fromString(snapshot.pathClass()));
            object.setID(snapshot.objectId());
            snapshot.score()
                    .ifPresent(
                            score ->
                                    object.getMeasurementList()
                                            .put(
                                                    InferenceFeatureCodec
                                                            .DETECTION_SCORE_MEASUREMENT,
                                                    score));
            objects.put(snapshot.objectId(), object);
        }
        for (MetricObjectSnapshot snapshot : region.objects()) {
            PathObject object = objects.get(snapshot.objectId());
            PathObject parent = snapshot.parentObjectId().map(objects::get).orElse(target);
            if (parent == null) {
                throw new IllegalArgumentException(
                        "detached metric parent is absent from the region");
            }
            parent.addChildObject(object);
        }
        return new ReconstructedRegion(target, List.copyOf(objects.values()));
    }

    private record ReconstructedRegion(PathObject target, List<PathObject> children) {}
}

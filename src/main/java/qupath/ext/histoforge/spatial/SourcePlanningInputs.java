package qupath.ext.histoforge.spatial;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.SourceArtifactLimits;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SlideFileSetCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;

/**
 * Captured source, model, target, and resource-limit inputs accepted by {@link SourceInputPlanner}.
 */
public final class SourcePlanningInputs {

    private SourcePlanningInputs() {}

    /** One closure-expanded selected model whose possible spatial inputs are bounded. */
    public sealed interface SelectedModel permits FieldModel, CropModel {
        InferenceIdentityCodec.ModelStepIdentity producer();

        double targetMpp();

        ExecutionLimitsCodec.TargetRasterLimits finiteTargetRasterLimits();

        boolean durableModelRunSupported();

        Set<SourceArtifactCodec.SourceContainer> sourceContainers();
    }

    /** A field model runs on the target and any small-ROI context its saved profile requires. */
    public record FieldModel(
            InferenceIdentityCodec.ModelStepIdentity producer,
            double targetMpp,
            Optional<VariableFieldProfile> fieldProfile,
            ExecutionLimitsCodec.TargetRasterLimits finiteTargetRasterLimits,
            boolean durableModelRunSupported,
            Set<SourceArtifactCodec.SourceContainer> sourceContainers)
            implements SelectedModel {
        public FieldModel {
            producer = Objects.requireNonNull(producer, "producer");
            targetMpp = CanonicalCodec.requirePositiveFiniteDouble(targetMpp, "model.target_mpp");
            fieldProfile = Objects.requireNonNull(fieldProfile, "fieldProfile");
            finiteTargetRasterLimits =
                    Objects.requireNonNull(finiteTargetRasterLimits, "finiteTargetRasterLimits");
            sourceContainers = nonemptyContainers(sourceContainers);
        }
    }

    /**
     * A region-gated model and the conservative current/upstream parent domain it may consume.
     *
     * @param parentClasses exact recipe classes accepted by the child step
     * @param scriptVisibleClasses current classes that expose unattributed objects to an earlier
     *     ordinary script
     * @param discoverParentsAcrossRegion whether new upstream parents anywhere in the target may
     *     feed this model
     * @param targetedCurrentParents current parent snapshots resolved through this step's targets
     */
    public record CropModel(
            InferenceIdentityCodec.ModelStepIdentity producer,
            double targetMpp,
            ExecutionLimitsCodec.TargetRasterLimits finiteTargetRasterLimits,
            boolean durableModelRunSupported,
            Set<SourceArtifactCodec.SourceContainer> sourceContainers,
            int paddingModelPx,
            InferenceIdentityCodec.ModelStepIdentity parentProducer,
            Set<String> parentClasses,
            Set<String> scriptVisibleClasses,
            boolean discoverParentsAcrossRegion,
            List<ParentCandidate> targetedCurrentParents)
            implements SelectedModel {
        public CropModel {
            producer = Objects.requireNonNull(producer, "producer");
            targetMpp = CanonicalCodec.requirePositiveFiniteDouble(targetMpp, "model.target_mpp");
            finiteTargetRasterLimits =
                    Objects.requireNonNull(finiteTargetRasterLimits, "finiteTargetRasterLimits");
            sourceContainers = nonemptyContainers(sourceContainers);
            if (paddingModelPx < 0) {
                throw new IllegalArgumentException("padding_model_px must be nonnegative");
            }
            parentProducer = Objects.requireNonNull(parentProducer, "parentProducer");
            parentClasses = pathClasses(parentClasses, "parent_classes", true);
            scriptVisibleClasses =
                    pathClasses(scriptVisibleClasses, "script_visible_classes", false);
            targetedCurrentParents =
                    List.copyOf(
                            Objects.requireNonNull(
                                    targetedCurrentParents, "targetedCurrentParents"));
        }
    }

    /** Current polygon that may supply one crop model's source-parent geometry. */
    public record ParentCandidate(
            Optional<String> currentPathClass,
            Optional<InferenceIdentityCodec.ModelStepIdentity> producer,
            Geometry geometry) {
        public ParentCandidate {
            currentPathClass =
                    Objects.requireNonNull(currentPathClass, "currentPathClass")
                            .map(
                                    value ->
                                            CanonicalCodec.requirePathClassName(
                                                    value, "parent.path_class"));
            producer = Objects.requireNonNull(producer, "producer");
            geometry = canonicalGeometry(geometry, "parent.geometry");
        }

        @Override
        public Geometry geometry() {
            return geometry.copy();
        }
    }

    /** Exact source pyramid observed through one validated client source adapter. */
    public record CapturedSourcePyramid(
            RasterInputCodec.ActionSourceRevision sourceRevision,
            int seriesIndex,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            List<SourceLevel> levels) {
        public CapturedSourcePyramid {
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            seriesIndex = CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index");
            levelZeroPixelsToSlide =
                    Objects.requireNonNull(levelZeroPixelsToSlide, "levelZeroPixelsToSlide");
            if (levels == null || levels.isEmpty()) {
                throw new IllegalArgumentException("source pyramid levels must be nonempty");
            }
            List<SourceLevel> normalized = new ArrayList<>(levels.size());
            Set<Integer> indices = new HashSet<>();
            for (SourceLevel level : levels) {
                SourceLevel value = Objects.requireNonNull(level, "source level");
                if (!indices.add(value.resolutionLevel())) {
                    throw new IllegalArgumentException(
                            "source pyramid resolution levels must be unique");
                }
                normalized.add(value);
            }
            normalized.sort(java.util.Comparator.comparingInt(SourceLevel::resolutionLevel));
            SourceLevel levelZero =
                    normalized.stream()
                            .filter(level -> level.resolutionLevel() == 0)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "source pyramid must contain level zero"));
            SourceIdentityCodec.ImageSourceIdentity source = sourceRevision.pixelSource();
            if (levelZero.dimensions().width() != source.width()
                    || levelZero.dimensions().height() != source.height()
                    || !levelZero
                            .pixelsToLevelZero()
                            .equals(new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0))) {
                throw new IllegalArgumentException(
                        "source pyramid level zero must match the captured pixel source");
            }
            levels = List.copyOf(normalized);
        }
    }

    /** Reader-validated shape and mapping of one source-pyramid level. */
    public record SourceLevel(
            int resolutionLevel,
            SourceViewGeometry.Dimensions dimensions,
            SourceIdentityCodec.ScaleTranslate2D pixelsToLevelZero) {
        public SourceLevel {
            resolutionLevel =
                    CanonicalCodec.requireNonnegativeInt32(resolutionLevel, "resolution_level");
            dimensions = Objects.requireNonNull(dimensions, "dimensions");
            pixelsToLevelZero = Objects.requireNonNull(pixelsToLevelZero, "pixelsToLevelZero");
        }
    }

    /**
     * Stable original bytes admitted by the source adapter for size-based upload selection.
     *
     * <p>Eligibility binds the selected original reader and complete transport size, including
     * packaging where companions are required. The source planner, not the caller, decides whether
     * the original is needed.
     */
    public record EligibleOriginalSource(
            long totalBytes,
            SourceArtifactCodec.SourceContainer container,
            String entryPath,
            OriginalReaderSettingsCodec.Settings readerSettings) {
        public EligibleOriginalSource {
            totalBytes = CanonicalCodec.requirePositiveSafeInteger(totalBytes, "total_bytes");
            if (container != SourceArtifactCodec.SourceContainer.SLIDE_FILE
                    && container != SourceArtifactCodec.SourceContainer.SLIDE_FILE_SET)
                throw new IllegalArgumentException(
                        "original eligibility requires an original container");
            entryPath = SlideFileSetCodec.relativePath(entryPath);
            readerSettings = Objects.requireNonNull(readerSettings, "readerSettings");
        }
    }

    /** Independent client-derived and backend source-artifact admission ceilings. */
    public record SourcePlanningLimits(
            long maxCompleteDecodePixels,
            long maxDerivedSourceArtifactBytes,
            long maxSourceArtifactBytes) {
        /**
         * Bound each prepared image independently of the crop-versus-original selection rule. The
         * 50-percent comparison uses the actual original file size, not either limit.
         */
        public static SourcePlanningLimits forInference(
                SourceArtifactLimits gateway, ExecutionLimitsCodec.ClientExecutionLimits client) {
            return new SourcePlanningLimits(
                    gateway.maxCompleteDecodePixels(),
                    Math.min(gateway.maxArtifactBytes(), client.maxLocalSourceArtifactBytes()),
                    gateway.maxArtifactBytes());
        }

        public SourcePlanningLimits {
            maxCompleteDecodePixels =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxCompleteDecodePixels, "max_complete_decode_pixels");
            maxDerivedSourceArtifactBytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxDerivedSourceArtifactBytes, "max_derived_source_artifact_bytes");
            maxSourceArtifactBytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxSourceArtifactBytes, "max_source_artifact_bytes");
            if (maxDerivedSourceArtifactBytes > maxSourceArtifactBytes) {
                throw new IllegalArgumentException(
                        "derived source-artifact ceiling must not exceed the backend ceiling");
            }
        }
    }

    /** One captured target and the closure-expanded models selected for it. */
    public record TargetWork(
            ExecutionScopeCodec.TargetRegion target, List<? extends SelectedModel> selectedModels) {
        public TargetWork {
            target = Objects.requireNonNull(target, "target");
            if (selectedModels == null || selectedModels.isEmpty()) {
                throw new IllegalArgumentException("selectedModels must be nonempty");
            }
            List<SelectedModel> copy = new ArrayList<>(selectedModels.size());
            Set<InferenceIdentityCodec.ModelStepIdentity> producers = new HashSet<>();
            for (SelectedModel model : selectedModels) {
                SelectedModel value = Objects.requireNonNull(model, "selected model");
                if (!producers.add(value.producer())) {
                    throw new IllegalArgumentException("selected model producers must be unique");
                }
                copy.add(value);
            }
            selectedModels = List.copyOf(copy);
        }
    }

    private static Set<String> pathClasses(
            Set<String> values, String path, boolean requireNonempty) {
        if (values == null || (requireNonempty && values.isEmpty())) {
            throw new IllegalArgumentException(path + " must be nonempty");
        }
        TreeSet<String> result = new TreeSet<>();
        for (String value : values) {
            result.add(CanonicalCodec.requirePathClassName(value, path));
        }
        return Set.copyOf(result);
    }

    private static Set<SourceArtifactCodec.SourceContainer> nonemptyContainers(
            Set<SourceArtifactCodec.SourceContainer> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("source_containers must be nonempty");
        }
        return Set.copyOf(values);
    }

    private static Geometry canonicalGeometry(Geometry geometry, String path) {
        Geometry copy = Objects.requireNonNull(geometry, path).copy();
        try {
            CanonicalGeometryCodec.canonicalCoordinates(copy);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(path + " is invalid", error);
        }
        return copy;
    }
}

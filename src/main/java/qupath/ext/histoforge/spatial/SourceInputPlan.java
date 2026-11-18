package qupath.ext.histoforge.spatial;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SlideFileSetCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;

/** Immutable outputs of pure source-input planning. */
public record SourceInputPlan(List<TargetPlanningResult> targets) {

    public SourceInputPlan {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("targets must be nonempty");
        }
        List<TargetPlanningResult> copy = List.copyOf(targets);
        Set<ExecutionScopeCodec.RegionKey> keys = new HashSet<>();
        for (TargetPlanningResult target : copy) {
            if (!keys.add(target.target().key())) {
                throw new IllegalArgumentException("target region keys must be unique");
            }
        }
        targets = copy;
    }

    /** Finite calls or durable model runs selected once for one top-level target. */
    public enum ExecutionProtocol {
        FINITE,
        RECOVERABLE
    }

    /** Explicit result for one target; an empty plan means no current or possible model input. */
    public record TargetPlanningResult(
            ExecutionScopeCodec.TargetRegion target, Optional<TargetSourcePlan> sourcePlan) {
        public TargetPlanningResult {
            target = Objects.requireNonNull(target, "target");
            sourcePlan = Objects.requireNonNull(sourcePlan, "sourcePlan");
            if (sourcePlan.isPresent() && !sourcePlan.orElseThrow().target().equals(target)) {
                throw new IllegalArgumentException("target planning result and source plan differ");
            }
        }
    }

    /** Selected source level with the reader mapping needed for exact later view composition. */
    public record PinnedSourceLevel(
            int seriesIndex,
            int resolutionLevel,
            SourceViewGeometry.Dimensions dimensions,
            SourceIdentityCodec.ScaleTranslate2D pixelsToLevelZero) {
        public PinnedSourceLevel {
            seriesIndex = CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index");
            resolutionLevel =
                    CanonicalCodec.requireNonnegativeInt32(resolutionLevel, "resolution_level");
            dimensions = Objects.requireNonNull(dimensions, "dimensions");
            pixelsToLevelZero = Objects.requireNonNull(pixelsToLevelZero, "pixelsToLevelZero");
        }
    }

    /**
     * Chosen image delivery: an original, a complete prepared image, or separate bounded images.
     */
    public sealed interface SourceArtifactPlan
            permits OriginalArtifactPlan, DerivedArtifactPlan, IncrementalSourcePlan {

        /** Only generated upload files consume preparation slots and belong to cleanup. */
        default boolean ownsUploadFile() {
            return this instanceof DerivedArtifactPlan
                    || this instanceof OriginalArtifactPlan original
                            && original.container()
                                    == SourceArtifactCodec.SourceContainer.SLIDE_FILE_SET;
        }

        /** Check that the encoded upload preserves this captured representation and geometry. */
        default void requireMatchingStart(SourceArtifactCodec.SourceArtifactStartRequest start) {
            boolean matches =
                    switch (this) {
                        case OriginalArtifactPlan original ->
                                start.container() == original.container()
                                        && start.totalBytes() == original.totalBytes()
                                        && start.requiredLevels().equals(original.requiredLevels())
                                        && start.origin()
                                                instanceof SourceArtifactCodec.OriginalWsi origin
                                        && origin.pyramidSeriesIndex()
                                                == original.pyramidSeriesIndex()
                                        && origin.levelZeroPixelsToSlide()
                                                .equals(original.levelZeroPixelsToSlide())
                                        && origin.entryPath().equals(original.entryPath())
                                        && origin.readerSettings()
                                                .equals(original.readerSettings());
                        case DerivedArtifactPlan derived ->
                                start.container() == derived.container()
                                        && start.totalBytes() <= derived.conservativeArtifactBytes()
                                        && start.requiredLevels()
                                                .equals(
                                                        List.of(
                                                                new SourceArtifactCodec
                                                                        .SourceLevelRequirement(
                                                                        0,
                                                                        0,
                                                                        derived.sourceRectAtLevel()
                                                                                .width(),
                                                                        derived.sourceRectAtLevel()
                                                                                .height())))
                                        && start.origin()
                                                instanceof SourceArtifactCodec.DerivedRegion origin
                                        && origin.sourceResolutionLevel()
                                                == derived.sourceResolutionLevel()
                                        && origin.plane().equals(derived.plane())
                                        && origin.sourceRectAtLevel()
                                                .equals(derived.sourceRectAtLevel())
                                        && origin.artifactPixelsToSlide()
                                                .equals(derived.artifactPixelsToSlide());
                        case IncrementalSourcePlan ignored -> false;
                    };
            if (!matches) {
                throw new IllegalArgumentException(
                        "source upload does not match its captured source plan");
            }
        }
    }

    /**
     * Fixed source-level envelope delivered as separate finite-input or core-plus-halo images. This
     * records the reader geometry, not a new tiling plan; the accepted model request chooses which
     * rectangles are needed. A file's origin must never replace this complete envelope.
     */
    public record IncrementalSourcePlan(
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect sourceRectAtLevel,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide)
            implements SourceArtifactPlan {
        public IncrementalSourcePlan {
            Objects.requireNonNull(sourceLevel, "sourceLevel");
            Objects.requireNonNull(plane, "plane");
            Objects.requireNonNull(sourceRectAtLevel, "sourceRectAtLevel");
            Objects.requireNonNull(levelZeroPixelsToSlide, "levelZeroPixelsToSlide");
            SourceViewGeometry.deriveOriginalViewTransform(
                    levelZeroPixelsToSlide,
                    sourceLevel.pixelsToLevelZero(),
                    sourceLevel.dimensions(),
                    sourceRectAtLevel);
        }

        public SourceCaptureCodec.Capture capture(
                SourceIdentityCodec.ImageSourceIdentity source,
                BuildIdentityCodec.SoftwareBuildIdentity writerBuild) {
            return new SourceCaptureCodec.Capture(
                    source,
                    plane,
                    new SourceArtifactCodec.SourceLevelRequirement(
                            sourceLevel.seriesIndex(), sourceLevel.resolutionLevel(),
                            sourceLevel.dimensions().width(), sourceLevel.dimensions().height()),
                    sourceLevel.pixelsToLevelZero(),
                    levelZeroPixelsToSlide,
                    sourceRectAtLevel,
                    writerBuild);
        }

        public SourceIdentityCodec.ScaleTranslate2D envelopePixelsToSlide() {
            return SourceViewGeometry.deriveOriginalViewTransform(
                    levelZeroPixelsToSlide,
                    sourceLevel.pixelsToLevelZero(),
                    sourceLevel.dimensions(),
                    sourceRectAtLevel);
        }
    }

    /** One eligible original slide or packaged fileset shared by all planned targets. */
    public record OriginalArtifactPlan(
            long totalBytes,
            int pyramidSeriesIndex,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            List<SourceArtifactCodec.SourceLevelRequirement> requiredLevels,
            SourceArtifactCodec.SourceContainer container,
            String entryPath,
            OriginalReaderSettingsCodec.Settings readerSettings)
            implements SourceArtifactPlan {
        public OriginalArtifactPlan {
            totalBytes = CanonicalCodec.requirePositiveSafeInteger(totalBytes, "total_bytes");
            pyramidSeriesIndex =
                    CanonicalCodec.requireNonnegativeInt32(
                            pyramidSeriesIndex, "pyramid_series_index");
            levelZeroPixelsToSlide =
                    Objects.requireNonNull(levelZeroPixelsToSlide, "levelZeroPixelsToSlide");
            requiredLevels = List.copyOf(Objects.requireNonNull(requiredLevels, "requiredLevels"));
            if (requiredLevels.isEmpty()) {
                throw new IllegalArgumentException("original required levels must be nonempty");
            }
            if (container != SourceArtifactCodec.SourceContainer.SLIDE_FILE
                    && container != SourceArtifactCodec.SourceContainer.SLIDE_FILE_SET)
                throw new IllegalArgumentException("original plan requires an original container");
            entryPath = SlideFileSetCodec.relativePath(entryPath);
            readerSettings = Objects.requireNonNull(readerSettings, "readerSettings");
        }
    }

    /** One lossless target-local source-level rectangle awaiting byte materialization. */
    public record DerivedArtifactPlan(
            SourceArtifactCodec.SourceContainer container,
            int sourceResolutionLevel,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect sourceRectAtLevel,
            SourceIdentityCodec.ScaleTranslate2D artifactPixelsToSlide,
            long conservativeArtifactBytes)
            implements SourceArtifactPlan {
        public DerivedArtifactPlan {
            container = Objects.requireNonNull(container, "container");
            sourceResolutionLevel =
                    CanonicalCodec.requireNonnegativeInt32(
                            sourceResolutionLevel, "source_resolution_level");
            plane = Objects.requireNonNull(plane, "plane");
            sourceRectAtLevel = Objects.requireNonNull(sourceRectAtLevel, "sourceRectAtLevel");
            artifactPixelsToSlide =
                    Objects.requireNonNull(artifactPixelsToSlide, "artifactPixelsToSlide");
            conservativeArtifactBytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            conservativeArtifactBytes, "conservative_artifact_bytes");
        }
    }

    /** Source-view fields known before an immutable artifact reference exists. */
    public record SourceViewPlan(
            int seriesIndex,
            int resolutionLevel,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect pixelRect,
            SourceIdentityCodec.ScaleTranslate2D viewPixelsToSlide) {
        public SourceViewPlan {
            seriesIndex = CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index");
            resolutionLevel =
                    CanonicalCodec.requireNonnegativeInt32(resolutionLevel, "resolution_level");
            plane = Objects.requireNonNull(plane, "plane");
            pixelRect = Objects.requireNonNull(pixelRect, "pixelRect");
            viewPixelsToSlide = Objects.requireNonNull(viewPixelsToSlide, "viewPixelsToSlide");
        }
    }

    /** Complete logical/frame/view plan for one field or resolved source-parent model input. */
    public record PlannedModelInput(
            ExecutionScopeCodec.OutputKey output,
            ExecutionScopeCodec.ScopeAcceptance acceptance,
            RasterInputCodec.RasterFrame frame,
            SourceViewPlan sourceView) {
        public PlannedModelInput {
            output = Objects.requireNonNull(output, "output");
            acceptance = Objects.requireNonNull(acceptance, "acceptance");
            frame = Objects.requireNonNull(frame, "frame");
            sourceView = Objects.requireNonNull(sourceView, "sourceView");
            if (!ExecutionScopeCodec.regionOf(output.scope()).equals(frame.region())
                    || !frame.region().equals(acceptance.target().key())) {
                throw new IllegalArgumentException(
                        "planned input output, frame, and acceptance regions must match");
            }
            if (!sourceView.plane().equals(frame.transform().raster().plane())
                    || !sourceView.plane().equals(acceptance.target().plane())) {
                throw new IllegalArgumentException(
                        "planned input source, frame, and acceptance planes must match");
            }
        }
    }

    /** Retained crop geometry needed to plan exact parents after upstream barriers resolve. */
    public record CropInputTemplate(
            InferenceIdentityCodec.ModelStepIdentity producer,
            double targetMpp,
            ExecutionLimitsCodec.TargetRasterLimits finiteTargetRasterLimits,
            int paddingModelPx) {
        public CropInputTemplate {
            producer = Objects.requireNonNull(producer, "producer");
            targetMpp = CanonicalCodec.requirePositiveFiniteDouble(targetMpp, "target_mpp");
            finiteTargetRasterLimits =
                    Objects.requireNonNull(finiteTargetRasterLimits, "finiteTargetRasterLimits");
            if (paddingModelPx < 0) {
                throw new IllegalArgumentException("padding_model_px must be nonnegative");
            }
        }
    }

    /** Complete pure plan consumed later by source materialization and staged model execution. */
    public record TargetSourcePlan(
            ExecutionScopeCodec.TargetRegion target,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            ExecutionProtocol protocol,
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.IntRect sourceEnvelopeAtLevel,
            SourceArtifactPlan artifact,
            List<PlannedModelInput> fieldInputs,
            List<CropInputTemplate> cropInputs) {
        public TargetSourcePlan {
            target = Objects.requireNonNull(target, "target");
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            levelZeroPixelsToSlide =
                    Objects.requireNonNull(levelZeroPixelsToSlide, "levelZeroPixelsToSlide");
            protocol = Objects.requireNonNull(protocol, "protocol");
            sourceLevel = Objects.requireNonNull(sourceLevel, "sourceLevel");
            sourceEnvelopeAtLevel =
                    Objects.requireNonNull(sourceEnvelopeAtLevel, "sourceEnvelopeAtLevel");
            artifact = Objects.requireNonNull(artifact, "artifact");
            fieldInputs = List.copyOf(Objects.requireNonNull(fieldInputs, "fieldInputs"));
            cropInputs = List.copyOf(Objects.requireNonNull(cropInputs, "cropInputs"));
            if (fieldInputs.isEmpty() && cropInputs.isEmpty()) {
                throw new IllegalArgumentException("source plan must contain model input work");
            }
        }
    }
}

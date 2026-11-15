package qupath.ext.histoforge.spatial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingTaskCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.FixedFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.spatial.SourceArtifactPlanning.Candidate;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceArtifactPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;

/** Pure source-level, envelope, and representation planning for one training image. */
public final class TrainingSourcePlanner {

    private static final SourceIdentityCodec.ImagePlaneIndex TRAINING_PLANE =
            new SourceIdentityCodec.ImagePlaneIndex(0, 0);
    private static final Set<SourceArtifactCodec.SourceContainer> TRAINING_CONTAINERS =
            Set.of(
                    SourceArtifactCodec.SourceContainer.PNG,
                    SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                    SourceArtifactCodec.SourceContainer.SLIDE_FILE);

    private TrainingSourcePlanner() {}

    /** One immutable ROI geometry awaiting source planning. */
    public record TrainingRoiGeometry(String sourceRoiUuid, Geometry geometrySlide) {
        public TrainingRoiGeometry {
            sourceRoiUuid = CanonicalCodec.requireCanonicalUuid(sourceRoiUuid, "source_roi_uuid");
            geometrySlide = canonicalArea(geometrySlide);
        }

        @Override
        public Geometry geometrySlide() {
            return geometrySlide.copy();
        }
    }

    /** Exact source view and artifact representation selected for one ROI. */
    public record TrainingRoiSourcePlan(
            String sourceRoiUuid,
            String roiGeometryDigest,
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.IntRect sourceEnvelopeAtLevel,
            SourceArtifactPlan artifact) {
        public TrainingRoiSourcePlan {
            sourceRoiUuid = CanonicalCodec.requireCanonicalUuid(sourceRoiUuid, "source_roi_uuid");
            roiGeometryDigest =
                    CanonicalCodec.requireSha256(roiGeometryDigest, "roi_geometry_digest");
            sourceLevel = Objects.requireNonNull(sourceLevel, "sourceLevel");
            sourceEnvelopeAtLevel =
                    Objects.requireNonNull(sourceEnvelopeAtLevel, "sourceEnvelopeAtLevel");
            artifact = Objects.requireNonNull(artifact, "artifact");
        }
    }

    /** Source plans in the same canonical ROI order supplied by the capture. */
    public record TrainingImageSourcePlan(
            RasterInputCodec.ActionSourceRevision sourceRevision,
            TrainingTask task,
            List<TrainingRoiSourcePlan> rois) {
        public TrainingImageSourcePlan {
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            task = TrainingTaskCodec.parseTask(TrainingTaskCodec.toJson(task));
            rois = List.copyOf(rois);
            if (rois.isEmpty()) {
                throw new IllegalArgumentException("training source plan must contain ROIs");
            }
        }

        public boolean usesOriginal() {
            return rois.getFirst().artifact() instanceof OriginalArtifactPlan;
        }
    }

    /**
     * Plan one image. Field input captures the maximum real context the selected capability may
     * later use; crop input uses the selected recipe's explicit padding.
     */
    public static TrainingImageSourcePlan plan(
            CapturedSourcePyramid source,
            List<TrainingRoiGeometry> rois,
            TrainingTask task,
            ModelTrainingCapability capability,
            Optional<EligibleOriginalSource> eligibleOriginal,
            SourcePlanningLimits limits) {
        Objects.requireNonNull(source, "source");
        List<TrainingRoiGeometry> targets = List.copyOf(rois);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("training source planning requires ROIs");
        }
        TrainingTask selectedTask =
                TrainingTaskCodec.parseTask(
                        TrainingTaskCodec.toJson(Objects.requireNonNull(task, "task")));
        ModelTrainingCapability selectedCapability =
                Objects.requireNonNull(capability, "capability");
        TrainingCapabilityCodec.requireTaskMatchesCapability(selectedTask, selectedCapability);
        String kind = selectedTask.inputKind();
        double targetMpp = selectedTask.modelMicronsPerPixel();
        Objects.requireNonNull(eligibleOriginal, "eligibleOriginal");
        Objects.requireNonNull(limits, "limits");
        SourceInputGeometry.requireSchemaOnePlane(
                TRAINING_PLANE, source.sourceRevision().pixelSource());

        PinnedSourceLevel level = SourceLevelSelection.select(source, targetMpp);
        Envelope sourceBounds =
                SourceInputGeometry.sourceBounds(
                        source.sourceRevision().pixelSource(), source.levelZeroPixelsToSlide());
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (TrainingRoiGeometry roi : targets) {
            SourceInputGeometry.requireCovered(
                    roi.geometrySlide().getEnvelopeInternal(), sourceBounds, "training ROI");
            Envelope frame =
                    sourceFrame(
                            roi.geometrySlide().getEnvelopeInternal(),
                            sourceBounds,
                            source.sourceRevision().pixelSource(),
                            kind,
                            selectedCapability.cropPaddingModelPx(),
                            targetMpp,
                            maximumContextSide(selectedCapability),
                            permitsSmallRoiContext(selectedCapability));
            RasterInputCodec.RasterTransform transform =
                    SourceInputGeometry.rasterTransform(
                            frame,
                            targetMpp,
                            TRAINING_PLANE,
                            source.sourceRevision().pixelSource());
            SourceIdentityCodec.IntRect envelope =
                    sourceEnvelope(frame, transform, source.levelZeroPixelsToSlide(), level);
            var target =
                    new ExecutionScopeCodec.TargetRegion(
                            new ExecutionScopeCodec.Roi(roi.sourceRoiUuid()),
                            roi.geometrySlide(),
                            TRAINING_PLANE,
                            CanonicalGeometryCodec.canonicalDigest(roi.geometrySlide()));
            candidates.put(
                    roi.sourceRoiUuid(),
                    SourceArtifactPlanning.candidate(target, level, envelope, TRAINING_CONTAINERS));
        }

        boolean requiresOriginal =
                candidates.values().stream()
                        .anyMatch(
                                candidate ->
                                        candidate.conservativeArtifactBytes()
                                                > limits.maxDerivedSourceArtifactBytes());
        OriginalArtifactPlan original =
                requiresOriginal
                        ? SourceArtifactPlanning.eligibleOriginalPlan(
                                source,
                                candidates.values(),
                                eligibleOriginal.orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "derived training artifact exceeds the byte"
                                                                + " ceiling and no eligible original"
                                                                + " is available")),
                                limits)
                        : null;

        List<TrainingRoiSourcePlan> plans = new ArrayList<>(targets.size());
        for (TrainingRoiGeometry roi : targets) {
            Candidate candidate = candidates.get(roi.sourceRoiUuid());
            SourceArtifactPlan artifact =
                    original != null
                            ? original
                            : SourceArtifactPlanning.derivedPlan(
                                    candidate, source.levelZeroPixelsToSlide(), limits);
            plans.add(
                    new TrainingRoiSourcePlan(
                            roi.sourceRoiUuid(),
                            CanonicalGeometryCodec.canonicalDigest(roi.geometrySlide()),
                            level,
                            candidate.envelope(),
                            artifact));
        }
        return new TrainingImageSourcePlan(source.sourceRevision(), selectedTask, plans);
    }

    /** Capture the continuous real frame as well as its exact sampled-pixel footprint. */
    private static SourceIdentityCodec.IntRect sourceEnvelope(
            Envelope frame,
            RasterInputCodec.RasterTransform target,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            PinnedSourceLevel level) {
        var dimensions = level.dimensions();
        var footprint =
                SourceViewGeometry.requiredOriginalSourcePixelRect(
                        levelZeroPixelsToSlide, level.pixelsToLevelZero(), dimensions, target);
        var mapping =
                SourceViewGeometry.deriveOriginalViewTransform(
                        levelZeroPixelsToSlide,
                        level.pixelsToLevelZero(),
                        dimensions,
                        new SourceIdentityCodec.IntRect(
                                0, 0, dimensions.width(), dimensions.height()));
        int minX =
                Math.min(
                        footprint.x(),
                        Math.max(
                                0,
                                (int)
                                        Math.floor(
                                                (frame.getMinX() - mapping.translateX())
                                                        / mapping.scaleX())));
        int minY =
                Math.min(
                        footprint.y(),
                        Math.max(
                                0,
                                (int)
                                        Math.floor(
                                                (frame.getMinY() - mapping.translateY())
                                                        / mapping.scaleY())));
        int maxX =
                Math.max(
                        footprint.x() + footprint.width(),
                        Math.min(
                                dimensions.width(),
                                (int)
                                        Math.ceil(
                                                (frame.getMaxX() - mapping.translateX())
                                                        / mapping.scaleX())));
        int maxY =
                Math.max(
                        footprint.y() + footprint.height(),
                        Math.min(
                                dimensions.height(),
                                (int)
                                        Math.ceil(
                                                (frame.getMaxY() - mapping.translateY())
                                                        / mapping.scaleY())));
        return new SourceIdentityCodec.IntRect(minX, minY, maxX - minX, maxY - minY);
    }

    private static Envelope sourceFrame(
            Envelope roi,
            Envelope sourceBounds,
            SourceIdentityCodec.ImageSourceIdentity source,
            String inputKind,
            int cropPaddingModelPx,
            double targetMpp,
            int maximumContextSide,
            boolean includeContext) {
        if ("crop".equals(inputKind)) {
            return SourceInputGeometry.parentFrame(
                    roi, cropPaddingModelPx, targetMpp, source, sourceBounds);
        }
        if (!includeContext) {
            return new Envelope(roi);
        }
        return SourceInputGeometry.contextualFieldFrame(
                roi, maximumContextSide, targetMpp, source, sourceBounds);
    }

    private static int maximumContextSide(ModelTrainingCapability capability) {
        return Math.toIntExact(capability.allowedFieldSideModelPx().maximum());
    }

    private static boolean permitsSmallRoiContext(ModelTrainingCapability capability) {
        if (!"field".equals(capability.inputKind())) {
            return false;
        }
        boolean defaultContext =
                switch (capability.defaultFieldProfile()) {
                    case FixedFieldProfile fixed -> fixed.smallRoiContext();
                    case VariableFieldProfile variable -> variable.smallRoiContext();
                };
        return defaultContext || capability.smallRoiContextEditable();
    }

    private static Geometry canonicalArea(Geometry geometry) {
        Geometry copy = Objects.requireNonNull(geometry, "geometrySlide").copy();
        CanonicalGeometryCodec.canonicalCoordinates(copy);
        if (!(copy.getArea() > 0.0)) {
            throw new IllegalArgumentException("training ROI geometry must have positive area");
        }
        return copy;
    }
}

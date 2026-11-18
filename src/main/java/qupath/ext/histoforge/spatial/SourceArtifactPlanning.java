package qupath.ext.histoforge.spatial;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceArtifactPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;

/** Chooses lossless source delivery while enforcing the separate per-file resource limits. */
public final class SourceArtifactPlanning {

    // Routes preparation; a required finite-input or core-plus-halo image may still be larger.
    private static final long COMPLETE_INFERENCE_IMAGE_THRESHOLD_BYTES = 1L << 30;
    private static final int DERIVED_TILE_SIZE = 512;
    private static final long DERIVED_FIXED_OVERHEAD_BYTES = 1L << 20;
    private static final long DERIVED_OVERHEAD_BYTES_PER_TILE = 512;

    private SourceArtifactPlanning() {}

    static Candidate candidate(
            ExecutionScopeCodec.TargetRegion target,
            PinnedSourceLevel level,
            SourceIdentityCodec.IntRect envelope,
            Set<SourceArtifactCodec.SourceContainer> containers) {
        return new Candidate(
                target,
                level,
                envelope,
                Set.copyOf(containers),
                conservativeDerivedArtifactBytes(envelope));
    }

    static OriginalArtifactPlan eligibleOriginalPlan(
            CapturedSourcePyramid source,
            java.util.Collection<Candidate> targets,
            EligibleOriginalSource original,
            SourcePlanningLimits limits) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("eligible original requires model source work");
        }
        if (original.totalBytes() > limits.maxSourceArtifactBytes()) {
            throw new IllegalArgumentException(
                    "eligible original exceeds the source-artifact byte ceiling");
        }
        if (targets.stream()
                .anyMatch(target -> !target.containers().contains(original.container()))) {
            throw new IllegalArgumentException(
                    "selected models do not all accept the original container");
        }
        return originalPlan(source, targets, original);
    }

    /**
     * Prefer one usable original when preparing the targets would exceed half its actual size or a
     * prepared file would exceed its own limit. Equality keeps the prepared sources.
     */
    static boolean preferOriginal(
            java.util.Collection<Candidate> targets,
            EligibleOriginalSource original,
            SourcePlanningLimits limits) {
        if (targets.isEmpty()
                || original.totalBytes() > limits.maxSourceArtifactBytes()
                || targets.stream()
                        .anyMatch(target -> !target.containers().contains(original.container()))) {
            return false;
        }
        // Subtract from the comparison budget instead of overflowing a sum of large estimates.
        long remainingBytes = original.totalBytes() / 2;
        for (Candidate target : targets) {
            long estimate = target.conservativeArtifactBytes();
            if (estimate > limits.maxDerivedSourceArtifactBytes() || estimate > remainingBytes) {
                return true;
            }
            remainingBytes -= estimate;
        }
        return false;
    }

    static OriginalArtifactPlan originalPlan(
            CapturedSourcePyramid source,
            java.util.Collection<Candidate> targets,
            EligibleOriginalSource original) {
        if (!original.readerSettings()
                        .adapter()
                        .equals(source.sourceRevision().pixelSource().sourceAdapterId())
                || original.readerSettings().levels().size() != source.levels().size())
            throw new IllegalArgumentException(
                    "original reader settings differ from captured source");
        for (var mapping : original.readerSettings().levels()) {
            if (!mapping.pixelsToLevelZero()
                    .equals(level(source, mapping.resolutionLevel()).pixelsToLevelZero()))
                throw new IllegalArgumentException(
                        "original reader mapping differs from captured source level");
        }
        Map<Integer, SourceLevel> required = new LinkedHashMap<>();
        required.put(0, level(source, 0));
        for (Candidate target : targets) {
            int resolutionLevel = target.level().resolutionLevel();
            required.put(resolutionLevel, level(source, resolutionLevel));
        }
        List<SourceArtifactCodec.SourceLevelRequirement> requirements =
                required.values().stream()
                        .sorted(Comparator.comparingInt(SourceLevel::resolutionLevel))
                        .map(
                                item ->
                                        new SourceArtifactCodec.SourceLevelRequirement(
                                                source.seriesIndex(),
                                                item.resolutionLevel(),
                                                item.dimensions().width(),
                                                item.dimensions().height()))
                        .toList();
        return new OriginalArtifactPlan(
                original.totalBytes(),
                source.seriesIndex(),
                source.levelZeroPixelsToSlide(),
                requirements,
                original.container(),
                original.entryPath(),
                original.readerSettings());
    }

    static OriginalArtifactPlan recoveredOriginalPlan(
            CapturedSourcePyramid source,
            java.util.Collection<Candidate> targets,
            EligibleOriginalSource original,
            List<SourceArtifactCodec.SourceLevelRequirement> retainedLevels) {
        OriginalArtifactPlan required = originalPlan(source, targets, original);
        List<SourceArtifactCodec.SourceLevelRequirement> retained = List.copyOf(retainedLevels);
        if (!retained.containsAll(required.requiredLevels())) {
            throw new IllegalArgumentException(
                    "published original omits a freshly required source level");
        }
        for (SourceArtifactCodec.SourceLevelRequirement retainedLevel : retained) {
            if (retainedLevel.seriesIndex() != source.seriesIndex()) {
                throw new IllegalArgumentException(
                        "published original source level uses another series");
            }
            SourceLevel current =
                    source.levels().stream()
                            .filter(
                                    level ->
                                            level.resolutionLevel()
                                                    == retainedLevel.resolutionLevel())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "published original source level is unavailable"));
            if (retainedLevel.width() != current.dimensions().width()
                    || retainedLevel.height() != current.dimensions().height()) {
                throw new IllegalArgumentException(
                        "published original source-level dimensions changed");
            }
        }
        return new OriginalArtifactPlan(
                original.totalBytes(),
                source.seriesIndex(),
                source.levelZeroPixelsToSlide(),
                retained,
                original.container(),
                original.entryPath(),
                original.readerSettings());
    }

    static DerivedArtifactPlan derivedPlan(
            Candidate target,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourcePlanningLimits limits) {
        return derivedPlan(
                target.level(),
                target.target().plane(),
                target.envelope(),
                target.containers(),
                levelZeroPixelsToSlide,
                limits);
    }

    /** Plan one bounded lossless image without changing the captured sampling grid. */
    public static DerivedArtifactPlan capturedImage(
            SourceCaptureCodec.Capture capture,
            SourceIdentityCodec.IntRect sourceRectangle,
            Set<SourceArtifactCodec.SourceContainer> containers,
            SourcePlanningLimits limits) {
        SourceCaptureCodec.requireContained(sourceRectangle, capture.sourceRectAtLevel());
        var level = capture.sourceLevel();
        return derivedPlan(
                new PinnedSourceLevel(
                        level.seriesIndex(),
                        level.resolutionLevel(),
                        new SourceViewGeometry.Dimensions(level.width(), level.height()),
                        capture.sourceLevelPixelsToLevelZero()),
                capture.plane(),
                sourceRectangle,
                containers,
                capture.levelZeroPixelsToSlide(),
                limits);
    }

    private static DerivedArtifactPlan derivedPlan(
            PinnedSourceLevel level,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect envelope,
            Set<SourceArtifactCodec.SourceContainer> containers,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourcePlanningLimits limits) {
        long estimatedBytes = conservativeDerivedArtifactBytes(envelope);
        if (estimatedBytes > limits.maxDerivedSourceArtifactBytes()) {
            throw new IllegalArgumentException(
                    "derived source artifact exceeds the derived-artifact byte ceiling");
        }
        long pixels = pixelCount(envelope);
        SourceArtifactCodec.SourceContainer container;
        if (pixels <= limits.maxCompleteDecodePixels()
                && containers.contains(SourceArtifactCodec.SourceContainer.PNG)) {
            container = SourceArtifactCodec.SourceContainer.PNG;
        } else if (containers.contains(SourceArtifactCodec.SourceContainer.TIFF_FAMILY)) {
            container = SourceArtifactCodec.SourceContainer.TIFF_FAMILY;
        } else {
            throw new IllegalArgumentException(
                    "selected models do not share a source container for the derived artifact");
        }
        return new DerivedArtifactPlan(
                container,
                level.resolutionLevel(),
                plane,
                envelope,
                SourceViewGeometry.deriveOriginalViewTransform(
                        levelZeroPixelsToSlide,
                        level.pixelsToLevelZero(),
                        level.dimensions(),
                        envelope),
                estimatedBytes);
    }

    /**
     * Prepare smaller envelopes as one file; supply larger ones when each model needs them. The
     * hard per-file ceiling still applies independently to every image actually prepared.
     */
    static SourceArtifactPlan inferencePlan(
            Candidate target,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourcePlanningLimits limits) {
        long completeImageThreshold =
                Math.min(
                        COMPLETE_INFERENCE_IMAGE_THRESHOLD_BYTES,
                        limits.maxDerivedSourceArtifactBytes());
        if (target.conservativeArtifactBytes() <= completeImageThreshold) {
            return derivedPlan(target, levelZeroPixelsToSlide, limits);
        }
        return new IncrementalSourcePlan(
                target.level(), target.target().plane(), target.envelope(), levelZeroPixelsToSlide);
    }

    /** Check recovered geometry without repeating the original image-wide size comparison. */
    static void requireCapturedPlan(
            CapturedSourcePyramid source, Candidate target, SourceArtifactPlan artifact) {
        SourceArtifactPlan expected;
        if (artifact instanceof OriginalArtifactPlan original) {
            if (!target.containers().contains(original.container())) {
                throw new IllegalArgumentException("model does not accept the captured original");
            }
            if (source.sourceRevision().pixelSource().contentVersion().orElse(null)
                            instanceof SourceIdentityCodec.LocalFile local
                    && local.sizeBytes() != original.totalBytes()) {
                throw new IllegalArgumentException("captured original byte length changed");
            }
            expected =
                    recoveredOriginalPlan(
                            source,
                            List.of(target),
                            new EligibleOriginalSource(
                                    original.totalBytes(),
                                    original.container(),
                                    original.entryPath(),
                                    original.readerSettings()),
                            original.requiredLevels());
        } else if (artifact instanceof IncrementalSourcePlan) {
            expected =
                    new IncrementalSourcePlan(
                            target.level(),
                            target.target().plane(),
                            target.envelope(),
                            source.levelZeroPixelsToSlide());
        } else {
            DerivedArtifactPlan derived = (DerivedArtifactPlan) artifact;
            if (!target.containers().contains(derived.container())) {
                throw new IllegalArgumentException(
                        "model does not accept the captured source container");
            }
            expected =
                    new DerivedArtifactPlan(
                            derived.container(),
                            target.level().resolutionLevel(),
                            target.target().plane(),
                            target.envelope(),
                            SourceViewGeometry.deriveOriginalViewTransform(
                                    source.levelZeroPixelsToSlide(),
                                            target.level().pixelsToLevelZero(),
                                    target.level().dimensions(), target.envelope()),
                            target.conservativeArtifactBytes());
        }
        if (!expected.equals(artifact)) {
            throw new IllegalArgumentException(
                    "current source geometry differs from the captured source plan");
        }
    }

    private static long conservativeDerivedArtifactBytes(SourceIdentityCodec.IntRect envelope) {
        long pixels = pixelCount(envelope);
        long rgbBytes = Math.multiplyExact(pixels, 3L);
        long tilesX = (envelope.width() + DERIVED_TILE_SIZE - 1L) / DERIVED_TILE_SIZE;
        long tilesY = (envelope.height() + DERIVED_TILE_SIZE - 1L) / DERIVED_TILE_SIZE;
        long overhead =
                Math.addExact(
                        DERIVED_FIXED_OVERHEAD_BYTES,
                        Math.multiplyExact(
                                Math.multiplyExact(tilesX, tilesY),
                                DERIVED_OVERHEAD_BYTES_PER_TILE));
        return Math.addExact(rgbBytes, overhead);
    }

    private static long pixelCount(SourceIdentityCodec.IntRect rectangle) {
        return Math.multiplyExact((long) rectangle.width(), rectangle.height());
    }

    private static SourceLevel level(CapturedSourcePyramid source, int resolutionLevel) {
        return source.levels().stream()
                .filter(level -> level.resolutionLevel() == resolutionLevel)
                .findFirst()
                .orElseThrow();
    }

    record Candidate(
            ExecutionScopeCodec.TargetRegion target,
            PinnedSourceLevel level,
            SourceIdentityCodec.IntRect envelope,
            Set<SourceArtifactCodec.SourceContainer> containers,
            long conservativeArtifactBytes) {
        Candidate {
            target = Objects.requireNonNull(target, "target");
            level = Objects.requireNonNull(level, "level");
            envelope = Objects.requireNonNull(envelope, "envelope");
            containers = Set.copyOf(containers);
        }
    }
}

package qupath.ext.histoforge.spatial;

import static qupath.ext.histoforge.spatial.SourceInputGeometry.clippedFrame;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.contextualFieldFrame;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.geometryToRaster;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.hasPositiveIntersection;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.intersection;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.parentFrame;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.rasterDomain;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.requireContained;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.requireCovered;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.requireSchemaOnePlane;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.sourceBounds;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.spatialFrame;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.unionRect;
import static qupath.ext.histoforge.spatial.SourceInputGeometry.validatedGeometryCopy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.spatial.SourceArtifactPlanning.Candidate;
import qupath.ext.histoforge.spatial.SourceInputGeometry.SpatialFrame;
import qupath.ext.histoforge.spatial.SourceInputPlan.CropInputTemplate;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.ExecutionProtocol;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.SourceInputPlan.PlannedModelInput;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceViewPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.TargetPlanningResult;
import qupath.ext.histoforge.spatial.SourceInputPlan.TargetSourcePlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CropModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.FieldModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.ParentCandidate;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SelectedModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.TargetWork;

/** Pure owner of source level, representation, view, target-grid, and protocol planning. */
public final class SourceInputPlanner {

    private SourceInputPlanner() {}

    /**
     * Plan all model-bearing targets against one captured image source.
     *
     * <p>An eligible original is supplied only after the source adapter has admitted that exact
     * path. Prefer prepared pixels when every target fits its hard file ceiling and their combined
     * estimate is at most half the actual original file size; otherwise share one usable original.
     * Without a usable original, use prepared pixels. Prepared delivery may be one complete target
     * image or on-demand finite-input/core-plus-halo images; {@link SourceArtifactPlanning} owns
     * that routing choice. Connection speed, cache state and estimated duration never participate.
     * Source representation never affects execution-protocol selection.
     */
    public static SourceInputPlan plan(
            CapturedSourcePyramid source,
            List<TargetWork> work,
            Optional<EligibleOriginalSource> eligibleOriginal,
            SourcePlanningLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(eligibleOriginal, "eligibleOriginal");
        Objects.requireNonNull(limits, "limits");
        List<TargetWork> targets = normalizedTargets(work);

        Map<ExecutionScopeCodec.RegionKey, TargetDraft> drafts = new LinkedHashMap<>();
        for (TargetWork target : targets) {
            buildTargetDraft(source, target)
                    .ifPresent(draft -> drafts.put(target.target().key(), draft));
        }

        List<Candidate> candidates =
                drafts.values().stream().map(TargetDraft::artifactCandidate).toList();
        OriginalArtifactPlan sharedOriginal =
                eligibleOriginal
                        .filter(
                                original ->
                                        SourceArtifactPlanning.preferOriginal(
                                                candidates, original, limits))
                        .map(
                                original ->
                                        SourceArtifactPlanning.eligibleOriginalPlan(
                                                source,
                                                candidates,
                                                requireMatchingOriginal(source, original),
                                                limits))
                        .orElse(null);

        List<TargetPlanningResult> results = new ArrayList<>(targets.size());
        for (TargetWork target : targets) {
            TargetDraft draft = drafts.get(target.target().key());
            SourceArtifactPlan artifact =
                    draft == null
                            ? null
                            : sharedOriginal != null
                                    ? sharedOriginal
                                    : SourceArtifactPlanning.inferencePlan(
                                            draft.artifactCandidate(),
                                            source.levelZeroPixelsToSlide(),
                                            limits);
            results.add(
                    new TargetPlanningResult(
                            target.target(),
                            draft == null
                                    ? Optional.empty()
                                    : Optional.of(finishTargetPlan(source, draft, artifact))));
        }
        return new SourceInputPlan(results);
    }

    /**
     * Replan current recipe geometry against the exact source representation already published.
     *
     * <p>This is the recovery counterpart to {@link #plan}. It does not rerun source-representation
     * selection. Instead it proves that the reader-derived source pyramid and freshly reconstructed
     * target work still produce the source level, envelope, and mapping recorded by the canonical
     * start request.
     */
    public static SourceInputPlan planAgainstPublishedSource(
            CapturedSourcePyramid source,
            List<TargetWork> work,
            SourceArtifactCodec.SourceArtifactStartRequest startRequest) {
        CapturedSourcePyramid captured = Objects.requireNonNull(source, "source");
        SourceArtifactCodec.SourceArtifactStartRequest start =
                Objects.requireNonNull(startRequest, "startRequest");
        List<TargetWork> targets = normalizedTargets(work);

        Map<ExecutionScopeCodec.RegionKey, TargetDraft> drafts = new LinkedHashMap<>();
        for (TargetWork target : targets) {
            buildTargetDraft(captured, target)
                    .ifPresent(draft -> drafts.put(target.target().key(), draft));
        }

        SourceArtifactPlan artifact = publishedArtifactPlan(captured, drafts.values(), start);
        List<TargetPlanningResult> results = new ArrayList<>(targets.size());
        for (TargetWork target : targets) {
            TargetDraft draft = drafts.get(target.target().key());
            results.add(
                    new TargetPlanningResult(
                            target.target(),
                            draft == null
                                    ? Optional.empty()
                                    : Optional.of(finishTargetPlan(captured, draft, artifact))));
        }
        return new SourceInputPlan(results);
    }

    /** Rebuild target inputs without changing the source choice saved before image preparation. */
    public static SourceInputPlan planAgainstCapturedSource(
            CapturedSourcePyramid source, List<TargetWork> work, SourceArtifactPlan artifact) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(artifact, "artifact");
        List<TargetPlanningResult> results = new ArrayList<>();
        for (TargetWork target : normalizedTargets(work)) {
            Optional<TargetDraft> draft = buildTargetDraft(source, target);
            if (draft.isPresent()) {
                SourceArtifactPlanning.requireCapturedPlan(
                        source, draft.orElseThrow().artifactCandidate(), artifact);
            }
            results.add(
                    new TargetPlanningResult(
                            target.target(),
                            draft.map(value -> finishTargetPlan(source, value, artifact))));
        }
        return new SourceInputPlan(results);
    }

    /** Plan one exact parent input after scripts/upstream model barriers resolve. */
    public static PlannedModelInput planParentInput(
            TargetSourcePlan targetPlan,
            InferenceIdentityCodec.ModelStepIdentity producer,
            String parentId,
            Geometry parentGeometry) {
        Objects.requireNonNull(targetPlan, "targetPlan");
        Objects.requireNonNull(producer, "producer");
        CropInputTemplate crop =
                targetPlan.cropInputs().stream()
                        .filter(item -> item.producer().equals(producer))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "producer has no planned crop input: " + producer));
        Geometry parent = validatedGeometryCopy(parentGeometry, "parentGeometry");
        Geometry accepted = intersection(targetPlan.target().geometrySlide(), parent);
        Envelope sourceBounds =
                sourceBounds(
                        targetPlan.sourceRevision().pixelSource(),
                        targetPlan.levelZeroPixelsToSlide());
        Envelope frame =
                parentFrame(
                        parent.getEnvelopeInternal(),
                        crop.paddingModelPx(),
                        crop.targetMpp(),
                        targetPlan.sourceRevision().pixelSource(),
                        sourceBounds);
        SpatialFrame spatial =
                spatialFrame(
                        frame,
                        crop.targetMpp(),
                        targetPlan.target().plane(),
                        targetPlan.sourceRevision().pixelSource(),
                        targetPlan.levelZeroPixelsToSlide(),
                        targetPlan.sourceLevel(),
                        crop.finiteTargetRasterLimits());
        if (targetPlan.protocol() == ExecutionProtocol.FINITE && !spatial.fitsFinite()) {
            throw new IllegalArgumentException(
                    "resolved parent exceeds the target's finite protocol bound");
        }
        requireContained(spatial.sourceRect(), targetPlan.sourceEnvelopeAtLevel());
        ExecutionScopeCodec.ParentScope scope =
                new ExecutionScopeCodec.ParentScope(targetPlan.target().key(), parentId);
        return plannedInput(
                targetPlan,
                new InputDraft(
                        new ExecutionScopeCodec.OutputKey(producer, scope),
                        new ExecutionScopeCodec.ScopeAcceptance(
                                targetPlan.target(), Optional.of(parent)),
                        RasterInputCodec.buildRasterFrame(
                                targetPlan.sourceRevision(),
                                targetPlan.target().key(),
                                spatial.transform(),
                                geometryToRaster(accepted, spatial.transform()))));
    }

    private static Optional<TargetDraft> buildTargetDraft(
            CapturedSourcePyramid source, TargetWork work) {
        ExecutionScopeCodec.TargetRegion target = work.target();
        requireSchemaOnePlane(target.plane(), source.sourceRevision().pixelSource());
        Geometry targetGeometry = validatedGeometryCopy(target.geometrySlide(), "target.geometry");
        Envelope sourceBounds =
                sourceBounds(
                        source.sourceRevision().pixelSource(), source.levelZeroPixelsToSlide());
        requireCovered(targetGeometry.getEnvelopeInternal(), sourceBounds, "target geometry");
        Map<InferenceIdentityCodec.ModelStepIdentity, SelectedModel> models =
                selectedModelMap(work.selectedModels());
        PinnedSourceLevel pinned = selectSourceLevel(source, models.values());

        List<InputDraft> fields = new ArrayList<>();
        List<CropInputTemplate> crops = new ArrayList<>();
        List<SpatialFrame> possibleFrames = new ArrayList<>();
        Map<InferenceIdentityCodec.ModelStepIdentity, InputDraft> fieldsByProducer =
                new LinkedHashMap<>();
        Envelope targetFrame =
                clippedFrame(targetGeometry.getEnvelopeInternal(), 0, 0, sourceBounds);

        for (SelectedModel model : models.values()) {
            if (!(model instanceof FieldModel field)) {
                continue;
            }
            Envelope fieldFrame =
                    field.fieldProfile()
                            .filter(VariableFieldProfile::smallRoiContext)
                            .map(
                                    profile ->
                                            contextualFieldFrame(
                                                    targetFrame,
                                                    profile.minSideModelPx(),
                                                    field.targetMpp(),
                                                    source.sourceRevision().pixelSource(),
                                                    sourceBounds))
                            .orElse(targetFrame);
            SpatialFrame spatial =
                    spatialFrame(
                            fieldFrame,
                            field.targetMpp(),
                            target.plane(),
                            source.sourceRevision().pixelSource(),
                            source.levelZeroPixelsToSlide(),
                            pinned,
                            field.finiteTargetRasterLimits());
            ExecutionScopeCodec.FieldScope scope = new ExecutionScopeCodec.FieldScope(target.key());
            InputDraft input =
                    new InputDraft(
                            new ExecutionScopeCodec.OutputKey(field.producer(), scope),
                            new ExecutionScopeCodec.ScopeAcceptance(target, Optional.empty()),
                            RasterInputCodec.buildRasterFrame(
                                    source.sourceRevision(),
                                    target.key(),
                                    spatial.transform(),
                                    geometryToRaster(targetGeometry, spatial.transform())));
            fields.add(input);
            fieldsByProducer.put(field.producer(), input);
            possibleFrames.add(spatial);
        }

        for (SelectedModel model : models.values()) {
            if (!(model instanceof CropModel crop)) {
                continue;
            }
            crops.add(
                    new CropInputTemplate(
                            crop.producer(),
                            crop.targetMpp(),
                            crop.finiteTargetRasterLimits(),
                            crop.paddingModelPx()));
            for (ParentCandidate parent : crop.targetedCurrentParents()) {
                Geometry geometry = parent.geometry();
                if (canSupplyParent(parent, crop)
                        && hasPositiveIntersection(targetGeometry, geometry)) {
                    possibleFrames.add(
                            spatialFrame(
                                    parentFrame(
                                            geometry.getEnvelopeInternal(),
                                            crop.paddingModelPx(),
                                            crop.targetMpp(),
                                            source.sourceRevision().pixelSource(),
                                            sourceBounds),
                                    crop.targetMpp(),
                                    target.plane(),
                                    source.sourceRevision().pixelSource(),
                                    source.levelZeroPixelsToSlide(),
                                    pinned,
                                    crop.finiteTargetRasterLimits()));
                }
            }

            InputDraft upstream = fieldsByProducer.get(crop.parentProducer());
            if (crop.discoverParentsAcrossRegion() && upstream != null) {
                possibleFrames.add(
                        spatialFrame(
                                parentFrame(
                                        rasterDomain(upstream.frame().transform()),
                                        crop.paddingModelPx(),
                                        crop.targetMpp(),
                                        source.sourceRevision().pixelSource(),
                                        sourceBounds),
                                crop.targetMpp(),
                                target.plane(),
                                source.sourceRevision().pixelSource(),
                                source.levelZeroPixelsToSlide(),
                                pinned,
                                crop.finiteTargetRasterLimits()));
            }
        }

        if (possibleFrames.isEmpty()) {
            return Optional.empty();
        }
        ExecutionProtocol protocol =
                target.key() instanceof ExecutionScopeCodec.Slide
                                || possibleFrames.stream().anyMatch(frame -> !frame.fitsFinite())
                        ? ExecutionProtocol.RECOVERABLE
                        : ExecutionProtocol.FINITE;
        if (protocol == ExecutionProtocol.RECOVERABLE
                && models.values().stream().anyMatch(model -> !model.durableModelRunSupported())) {
            throw new IllegalArgumentException(
                    "One or more selected models cannot analyze this large region or whole slide. "
                            + "Use a smaller ROI.");
        }

        SourceIdentityCodec.IntRect envelope =
                unionRect(possibleFrames.stream().map(SpatialFrame::sourceRect).toList());
        Set<SourceArtifactCodec.SourceContainer> containers = commonContainers(models.values());
        Candidate artifactCandidate =
                SourceArtifactPlanning.candidate(target, pinned, envelope, containers);
        return Optional.of(
                new TargetDraft(
                        protocol, artifactCandidate, List.copyOf(fields), List.copyOf(crops)));
    }

    private static TargetSourcePlan finishTargetPlan(
            CapturedSourcePyramid source, TargetDraft draft, SourceArtifactPlan artifact) {
        List<PlannedModelInput> fields =
                draft.fieldInputs().stream()
                        .map(input -> plannedInput(source, draft, artifact, input))
                        .toList();
        return new TargetSourcePlan(
                draft.artifactCandidate().target(),
                source.sourceRevision(),
                source.levelZeroPixelsToSlide(),
                draft.protocol(),
                draft.artifactCandidate().level(),
                draft.artifactCandidate().envelope(),
                artifact,
                fields,
                draft.cropInputs());
    }

    private static SourceArtifactPlan publishedArtifactPlan(
            CapturedSourcePyramid source,
            java.util.Collection<TargetDraft> drafts,
            SourceArtifactCodec.SourceArtifactStartRequest start) {
        SourceIdentityCodec.ImageSourceIdentity current = source.sourceRevision().pixelSource();
        SourceIdentityCodec.ImageSourceIdentity recorded =
                switch (start.origin()) {
                    case SourceArtifactCodec.OriginalWsi original -> original.source();
                    case SourceArtifactCodec.DerivedRegion derived -> derived.source();
                };
        if (!current.equals(recorded)) {
            throw new IllegalArgumentException(
                    "published source origin differs from the current pixel source");
        }

        if (start.origin() instanceof SourceArtifactCodec.OriginalWsi original) {
            OriginalArtifactPlan expected =
                    SourceArtifactPlanning.recoveredOriginalPlan(
                            source,
                            drafts.stream().map(TargetDraft::artifactCandidate).toList(),
                            new EligibleOriginalSource(
                                    start.totalBytes(),
                                    start.container(),
                                    original.entryPath(),
                                    original.readerSettings()),
                            start.requiredLevels());
            if (original.pyramidSeriesIndex() != expected.pyramidSeriesIndex()
                    || !original.levelZeroPixelsToSlide().equals(expected.levelZeroPixelsToSlide())
                    || drafts.stream()
                            .map(TargetDraft::artifactCandidate)
                            .anyMatch(
                                    candidate ->
                                            !candidate.containers().contains(start.container()))) {
                throw new IllegalArgumentException(
                        "published original source differs from the fresh source plan");
            }
            return expected;
        }

        if (drafts.size() != 1) {
            throw new IllegalArgumentException(
                    "one derived published source must belong to exactly one planned target");
        }
        SourceArtifactCodec.DerivedRegion derived =
                (SourceArtifactCodec.DerivedRegion) start.origin();
        Candidate candidate = drafts.iterator().next().artifactCandidate();
        SourceIdentityCodec.ScaleTranslate2D expectedTransform =
                SourceViewGeometry.deriveOriginalViewTransform(
                        source.levelZeroPixelsToSlide(),
                        candidate.level().pixelsToLevelZero(),
                        candidate.level().dimensions(),
                        candidate.envelope());
        if (derived.sourceResolutionLevel() != candidate.level().resolutionLevel()
                || !derived.plane().equals(candidate.target().plane())
                || !derived.sourceRectAtLevel().equals(candidate.envelope())
                || !derived.artifactPixelsToSlide().equals(expectedTransform)
                || !candidate.containers().contains(start.container())) {
            throw new IllegalArgumentException(
                    "published derived source differs from the fresh source plan");
        }
        return new DerivedArtifactPlan(
                start.container(),
                derived.sourceResolutionLevel(),
                derived.plane(),
                derived.sourceRectAtLevel(),
                derived.artifactPixelsToSlide(),
                candidate.conservativeArtifactBytes());
    }

    private static PlannedModelInput plannedInput(
            CapturedSourcePyramid source,
            TargetDraft target,
            SourceArtifactPlan artifact,
            InputDraft input) {
        SourceViewPlan view =
                sourceViewPlan(
                        target.artifactCandidate().target().plane(),
                        target.artifactCandidate().level(),
                        source.levelZeroPixelsToSlide(),
                        artifact,
                        input.frame().transform());
        return new PlannedModelInput(input.output(), input.acceptance(), input.frame(), view);
    }

    private static PlannedModelInput plannedInput(TargetSourcePlan target, InputDraft input) {
        SourceViewPlan view =
                sourceViewPlan(
                        target.target().plane(),
                        target.sourceLevel(),
                        target.levelZeroPixelsToSlide(),
                        target.artifact(),
                        input.frame().transform());
        return new PlannedModelInput(input.output(), input.acceptance(), input.frame(), view);
    }

    private static SourceViewPlan sourceViewPlan(
            SourceIdentityCodec.ImagePlaneIndex plane,
            PinnedSourceLevel level,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourceArtifactPlan artifact,
            RasterInputCodec.RasterTransform targetTransform) {
        if (artifact instanceof OriginalArtifactPlan) {
            SourceIdentityCodec.IntRect absolute =
                    SourceViewGeometry.requiredOriginalSourcePixelRect(
                            levelZeroPixelsToSlide,
                            level.pixelsToLevelZero(),
                            level.dimensions(),
                            targetTransform);
            return new SourceViewPlan(
                    level.seriesIndex(),
                    level.resolutionLevel(),
                    plane,
                    absolute,
                    SourceViewGeometry.deriveOriginalViewTransform(
                            levelZeroPixelsToSlide,
                            level.pixelsToLevelZero(),
                            level.dimensions(),
                            absolute));
        }
        var envelope =
                artifact instanceof DerivedArtifactPlan derived
                        ? derived.sourceRectAtLevel()
                        : ((IncrementalSourcePlan) artifact).sourceRectAtLevel();
        var mapping =
                artifact instanceof DerivedArtifactPlan derived
                        ? derived.artifactPixelsToSlide()
                        : ((IncrementalSourcePlan) artifact).envelopePixelsToSlide();
        SourceViewGeometry.Dimensions dimensions =
                new SourceViewGeometry.Dimensions(envelope.width(), envelope.height());
        SourceIdentityCodec.IntRect relative =
                SourceViewGeometry.requiredSourcePixelRect(mapping, dimensions, targetTransform);
        return new SourceViewPlan(
                0,
                0,
                plane,
                relative,
                SourceViewGeometry.deriveDerivedViewTransform(mapping, dimensions, relative));
    }

    private static PinnedSourceLevel selectSourceLevel(
            CapturedSourcePyramid source, java.util.Collection<SelectedModel> models) {
        double finestTargetMpp =
                models.stream().mapToDouble(SelectedModel::targetMpp).min().orElseThrow();
        return SourceLevelSelection.select(source, finestTargetMpp);
    }

    private static boolean canSupplyParent(ParentCandidate parent, CropModel crop) {
        if (parent.producer().isPresent()) {
            return parent.producer().orElseThrow().equals(crop.parentProducer());
        }
        return parent.currentPathClass()
                .map(
                        pathClass ->
                                crop.parentClasses().contains(pathClass)
                                        || crop.scriptVisibleClasses().contains(pathClass))
                .orElse(false);
    }

    private static Set<SourceArtifactCodec.SourceContainer> commonContainers(
            java.util.Collection<SelectedModel> models) {
        Set<SourceArtifactCodec.SourceContainer> result = null;
        for (SelectedModel model : models) {
            if (result == null) {
                result = new LinkedHashSet<>(model.sourceContainers());
            } else {
                result.retainAll(model.sourceContainers());
            }
        }
        if (result == null || result.isEmpty()) {
            throw new IllegalArgumentException(
                    "selected models do not share an accepted source container");
        }
        return Set.copyOf(result);
    }

    private static Map<InferenceIdentityCodec.ModelStepIdentity, SelectedModel> selectedModelMap(
            List<? extends SelectedModel> selectedModels) {
        Map<InferenceIdentityCodec.ModelStepIdentity, SelectedModel> result = new LinkedHashMap<>();
        for (SelectedModel model : selectedModels) {
            if (result.putIfAbsent(model.producer(), model) != null) {
                throw new IllegalArgumentException("selected model producers must be unique");
            }
        }
        for (SelectedModel model : result.values()) {
            if (model instanceof CropModel crop) {
                SelectedModel parent = result.get(crop.parentProducer());
                if (parent != null && !(parent instanceof FieldModel)) {
                    throw new IllegalArgumentException(
                            "selected crop parent producer must be a field model");
                }
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static List<TargetWork> normalizedTargets(List<TargetWork> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("target work must be nonempty");
        }
        List<TargetWork> result = List.copyOf(values);
        Set<ExecutionScopeCodec.RegionKey> keys = new LinkedHashSet<>();
        for (TargetWork target : result) {
            if (target == null || !keys.add(target.target().key())) {
                throw new IllegalArgumentException("target work region keys must be unique");
            }
        }
        return result;
    }

    private static EligibleOriginalSource requireMatchingOriginal(
            CapturedSourcePyramid source, EligibleOriginalSource original) {
        SourceIdentityCodec.SourceContentVersion version =
                source.sourceRevision()
                        .pixelSource()
                        .contentVersion()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "eligible original source requires"
                                                        + " content_version"));
        if (version instanceof SourceIdentityCodec.LocalFile local
                && local.sizeBytes() != original.totalBytes()) {
            throw new IllegalArgumentException(
                    "original byte length does not match local source version");
        }
        return original;
    }

    private record InputDraft(
            ExecutionScopeCodec.OutputKey output,
            ExecutionScopeCodec.ScopeAcceptance acceptance,
            RasterInputCodec.RasterFrame frame) {}

    private record TargetDraft(
            ExecutionProtocol protocol,
            Candidate artifactCandidate,
            List<InputDraft> fieldInputs,
            List<CropInputTemplate> cropInputs) {}
}

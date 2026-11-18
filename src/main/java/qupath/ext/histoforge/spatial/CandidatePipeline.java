package qupath.ext.histoforge.spatial;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FeatureStreamDecoder;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ObjectIdentityCodec;
import qupath.ext.histoforge.contract.ProcessingEvidenceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration.SlideCandidate;
import qupath.ext.histoforge.spatial.SourceInputPlan.PlannedModelInput;

/** Pure validation and preservation rules for accepted slide-coordinate model candidates. */
public final class CandidatePipeline {
    private static final Logger logger = LoggerFactory.getLogger(CandidatePipeline.class);
    private static final int PRECISION_FEATURE_SAMPLE_LIMIT = 8;

    private CandidatePipeline() {}

    /** Existing QuPath object families relevant to candidate preservation. */
    public enum ExistingObjectType {
        /** Editable QuPath annotation. */
        ANNOTATION,
        /** QuPath detection produced manually or by another tool. */
        DETECTION,
        /** Any object family that cannot preserve a model candidate. */
        OTHER
    }

    /** Read-only fields needed to decide whether one current object suppresses a candidate. */
    public record ExistingObjectSnapshot(
            Geometry geometrySlide,
            Optional<String> pathClass,
            SourceIdentityCodec.ImagePlaneIndex plane,
            ExistingObjectType objectType,
            boolean attributed,
            boolean administrative) {
        public ExistingObjectSnapshot {
            geometrySlide = Objects.requireNonNull(geometrySlide, "existing.geometry_slide").copy();
            pathClass =
                    Objects.requireNonNull(pathClass, "existing.path_class")
                            .map(
                                    value ->
                                            CanonicalCodec.requirePathClassName(
                                                    value, "existing.path_class"));
            plane = Objects.requireNonNull(plane, "existing.plane");
            objectType = Objects.requireNonNull(objectType, "existing.object_type");
        }

        @Override
        public Geometry geometrySlide() {
            return geometrySlide.copy();
        }
    }

    /** Retained candidates and the once-per-candidate suppression count. */
    public record Result(List<SlideCandidate> retainedCandidates, long suppressedCount) {
        public Result {
            retainedCandidates = List.copyOf(retainedCandidates);
            if (suppressedCount < 0) {
                throw new IllegalArgumentException("suppressed_count must be nonnegative");
            }
        }
    }

    /**
     * Transform and scope-filter one result already validated by {@link FiniteInferenceCodec}.
     *
     * <p>Out-of-scope features are expected padding output and are omitted. Any identity or plan
     * mismatch fails the whole logical input before the optional existing-object filter runs.
     */
    public static CandidateGeneration generateFinite(
            FiniteInferenceCodec.FiniteInferenceRequest request,
            FiniteInferenceCodec.InferenceResult validatedResult,
            PlannedModelInput plannedInput,
            BackendExecutionCodec.FiniteCall execution,
            ImageDataStateCodec.GenerationStoreReservation reservation,
            Collection<ExistingObjectSnapshot> existingObjects) {
        FiniteInferenceCodec.FiniteInferenceRequest finiteRequest =
                Objects.requireNonNull(request, "request");
        FiniteInferenceCodec.InferenceResult result =
                Objects.requireNonNull(validatedResult, "validatedResult");
        PlannedModelInput planned = Objects.requireNonNull(plannedInput, "plannedInput");
        BackendExecutionCodec.FiniteCall finiteExecution =
                Objects.requireNonNull(execution, "execution");
        ImageDataStateCodec.GenerationStoreReservation plannedReservation =
                Objects.requireNonNull(reservation, "reservation");
        requirePlanMatchesRequest(planned, finiteRequest);
        requireResultMatchesRequest(result, finiteRequest, finiteExecution);

        ExecutionScopeCodec.ScopeAcceptance acceptance = planned.acceptance();
        CandidateGeneration.InputEvidence evidence =
                new CandidateGeneration.InputEvidence(
                        result.meta().inputIdentity(),
                        ExecutionScopeCodec.scopeAcceptanceDigest(acceptance),
                        acceptance,
                        finiteRequest.rasterInput());
        requireReservationMatchesRequest(plannedReservation, finiteRequest, planned, evidence);
        return generateAccepted(
                finiteRequest.outputGenerationId(),
                finiteRequest.inputId(),
                finiteRequest.rasterInput(),
                planned.acceptance(),
                result.geojson(),
                finiteExecution,
                plannedReservation,
                evidence,
                Optional.empty(),
                existingObjects);
    }

    /** Supplies records from the exact local file that has already passed complete validation. */
    @FunctionalInterface
    public interface ValidatedFeatureReader {
        void read(Consumer<InferenceFeatureCodec.Feature> consumer) throws IOException;
    }

    /** Transform a validated durable result without retaining its incoming polygon population. */
    public static CandidateGeneration generateDurable(
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunInput requestInput,
            ModelRunPublicationCodec.InputResult manifestInput,
            FeatureStreamDecoder.ValidatedFeatureStream validation,
            ValidatedFeatureReader features,
            PlannedModelInput plannedInput,
            BackendExecutionCodec.DurableRun execution,
            ImageDataStateCodec.GenerationStoreReservation reservation,
            Collection<ExistingObjectSnapshot> existingObjects)
            throws IOException {
        ModelRunRequestCodec.ModelRunRequest run = Objects.requireNonNull(request, "request");
        ModelRunRequestCodec.ModelRunInput input =
                Objects.requireNonNull(requestInput, "requestInput");
        ModelRunPublicationCodec.InputResult result =
                Objects.requireNonNull(manifestInput, "manifestInput");
        FeatureStreamDecoder.ValidatedFeatureStream stream =
                Objects.requireNonNull(validation, "validation");
        PlannedModelInput planned = Objects.requireNonNull(plannedInput, "plannedInput");
        BackendExecutionCodec.DurableRun durableExecution =
                Objects.requireNonNull(execution, "execution");
        ImageDataStateCodec.GenerationStoreReservation plannedReservation =
                Objects.requireNonNull(reservation, "reservation");
        if (!run.inputs().contains(input)
                || !run.actionId().equals(plannedReservation.actionId())
                || !run.producer().equals(input.output().producer())
                || !run.expectedBackend().equals(plannedReservation.backendIdentity())
                || !run.modelRunId().equals(durableExecution.modelRunId())
                || result.inputId() != input.inputId()
                || !result.inputIdentity()
                        .equals(
                                FiniteInferenceCodec.buildBackendInputIdentity(
                                        input.rasterInput(),
                                        result.inputIdentity().sourceBindings()))
                || (!result.inputIdentity().sourceBindings().isEmpty()
                        && result.inputIdentity().sourceBindings().size()
                                != qupath.ext.histoforge.contract.ModelExecutionPlanCodec.unitCount(
                                        result.executionPlan()))
                || result.featureCount() != stream.featureCount()
                || result.vertexCount() != stream.vertexCount()) {
            throw new IllegalArgumentException(
                    "durable feature stream does not match its canonical request and manifest");
        }
        stream.requireInsideRaster(input.rasterInput().frame().transform().raster());
        requirePlanMatchesDurableInput(planned, input);
        ExecutionScopeCodec.ScopeAcceptance acceptance = planned.acceptance();
        CandidateGeneration.InputEvidence evidence =
                new CandidateGeneration.InputEvidence(
                        result.inputIdentity(),
                        ExecutionScopeCodec.scopeAcceptanceDigest(acceptance),
                        acceptance,
                        input.rasterInput());
        requireReservationMatchesDurableInput(plannedReservation, input, planned, evidence);
        CandidateCollector collector =
                new CandidateCollector(
                        input.outputGenerationId(),
                        input.inputId(),
                        input.rasterInput(),
                        acceptance,
                        durableExecution);
        Objects.requireNonNull(features, "features").read(collector::accept);
        if (collector.featureCount != stream.featureCount()) {
            throw new IllegalArgumentException(
                    "Retained feature count differs from its validated result");
        }
        return collector.finish(
                durableExecution,
                plannedReservation,
                evidence,
                Optional.of(ProcessingEvidenceCodec.fromPlan(run, input, result.executionPlan())),
                existingObjects);
    }

    private static CandidateGeneration generateAccepted(
            String outputGenerationId,
            int inputId,
            RasterInputCodec.ModelRasterInput rasterInput,
            ExecutionScopeCodec.ScopeAcceptance acceptance,
            InferenceFeatureCodec.FeatureCollection features,
            BackendExecutionCodec.BackendExecutionRef execution,
            ImageDataStateCodec.GenerationStoreReservation reservation,
            CandidateGeneration.InputEvidence evidence,
            Optional<ProcessingEvidenceCodec.ProcessingEvidence> processingEvidence,
            Collection<ExistingObjectSnapshot> existingObjects) {
        CandidateCollector collector =
                new CandidateCollector(
                        outputGenerationId, inputId, rasterInput, acceptance, execution);
        features.features().forEach(collector::accept);
        return collector.finish(
                execution, reservation, evidence, processingEvidence, existingObjects);
    }

    /** Owns only retained slide candidates; each incoming raster geometry is temporary. */
    private static final class CandidateCollector {
        private final String outputGenerationId;
        private final int inputId;
        private final RasterInputCodec.ModelRasterInput rasterInput;
        private final BackendExecutionCodec.BackendExecutionRef execution;
        private final PreparedGeometry target;
        private final Optional<PreparedGeometry> parent;
        private final SourceIdentityCodec.ImagePlaneIndex plane;
        private final List<SlideCandidate> accepted = new ArrayList<>();
        private final List<String> repairedFeatureSample = new ArrayList<>();
        private final List<String> precisionEmptyFeatureSample = new ArrayList<>();
        private String priorFeatureId;
        private long featureCount;
        private long repairedFeatureCount;
        private long precisionEmptyFeatureCount;

        private CandidateCollector(
                String outputGenerationId,
                int inputId,
                RasterInputCodec.ModelRasterInput rasterInput,
                ExecutionScopeCodec.ScopeAcceptance acceptance,
                BackendExecutionCodec.BackendExecutionRef execution) {
            this.outputGenerationId = outputGenerationId;
            this.inputId = inputId;
            this.rasterInput = rasterInput;
            this.execution = execution;
            // These detached boundaries stay fixed for the entire logical result. Reuse their
            // spatial indexes while preserving the exact covers predicate and clipping geometry.
            target = PreparedGeometryFactory.prepare(acceptance.target().geometrySlide());
            parent = acceptance.parentGeometrySlide().map(PreparedGeometryFactory::prepare);
            plane = rasterInput.frame().transform().raster().plane();
        }

        private void accept(InferenceFeatureCodec.Feature feature) {
            String stage = "feature order";
            try {
                if (priorFeatureId != null && priorFeatureId.compareTo(feature.id()) >= 0) {
                    throw new IllegalArgumentException(
                            "validated result features must have unique ascending IDs");
                }
                priorFeatureId = feature.id();
                featureCount = Math.addExact(featureCount, 1);
                stage = "raster validation";
                Geometry geometryRaster = feature.geometry();
                RasterInputCodec.requireGeometryInsideRaster(
                        geometryRaster,
                        rasterInput.frame().transform().raster(),
                        "inference feature geometry");
                stage = "slide transform";
                Geometry geometrySlide =
                        SourceInputGeometry.geometryToSlide(
                                geometryRaster, rasterInput.frame().transform());
                stage = "parent admission";
                if (parent.isPresent()
                        && !parent.orElseThrow().covers(geometrySlide.getInteriorPoint())) {
                    return;
                }
                stage = "target clipping";
                Optional<Geometry> clipped =
                        geometrySlide instanceof Point
                                ? (target.covers(geometrySlide)
                                        ? Optional.of(geometrySlide)
                                        : Optional.empty())
                                : SourceInputGeometry.clipToTarget(geometrySlide, target);
                if (clipped.isEmpty()) {
                    return;
                }
                stage = "native ROI materialization";
                var outline =
                        SourceInputGeometry.materializePrediction(clipped.orElseThrow(), plane);
                if (outline.roi().isEmpty()) {
                    precisionEmptyFeatureCount++;
                    sampleFeature(precisionEmptyFeatureSample, feature.id());
                    return;
                }
                if (outline.precisionRepaired()) {
                    repairedFeatureCount++;
                    sampleFeature(repairedFeatureSample, feature.id());
                }
                accepted.add(
                        SlideCandidate.fromMaterializedPrediction(
                                ObjectIdentityCodec.candidateId(
                                        outputGenerationId, inputId, feature.id()),
                                outline,
                                feature.pathClass(),
                                plane,
                                feature.score()));
            } catch (IllegalArgumentException error) {
                // Keep the original failure and reject the complete input. These bounded IDs
                // locate the retained record without serializing the full geometry or rebuilding
                // it.
                throw new IllegalArgumentException(
                        "Feature "
                                + feature.id()
                                + " failed during "
                                + stage
                                + " (input="
                                + inputId
                                + ", generation="
                                + outputGenerationId
                                + ", execution="
                                + BackendExecutionCodec.toJson(execution)
                                + "): "
                                + error.getMessage(),
                        error);
            }
        }

        private CandidateGeneration finish(
                BackendExecutionCodec.BackendExecutionRef execution,
                ImageDataStateCodec.GenerationStoreReservation reservation,
                CandidateGeneration.InputEvidence evidence,
                Optional<ProcessingEvidenceCodec.ProcessingEvidence> processingEvidence,
                Collection<ExistingObjectSnapshot> existingObjects) {
            Result filtered =
                    suppressOverlappingSameClassDuplicates(
                            accepted,
                            Objects.requireNonNull(existingObjects, "existingObjects"),
                            reservation.removeOverlappingSameClassDuplicates());
            GenerationEvidenceCodec.GenerationCounts counts =
                    new GenerationEvidenceCodec.GenerationCounts(
                            featureCount,
                            accepted.size(),
                            filtered.suppressedCount(),
                            filtered.retainedCandidates().size());
            if (repairedFeatureCount != 0 || precisionEmptyFeatureCount != 0) {
                logger.warn(
                        "Native ROI conversion (not save): input={}, generation={}, execution={}, "
                                + "backend_features={}, native_survivors={}, repaired_survivors={}, "
                                + "precision_empty={}, repaired_feature_sample={}, "
                                + "precision_empty_feature_sample={}, unsampled_repaired={}, "
                                + "unsampled_precision_empty={}",
                        inputId,
                        outputGenerationId,
                        BackendExecutionCodec.toJson(execution),
                        featureCount,
                        accepted.size(),
                        repairedFeatureCount,
                        precisionEmptyFeatureCount,
                        repairedFeatureSample,
                        precisionEmptyFeatureSample,
                        repairedFeatureCount - repairedFeatureSample.size(),
                        precisionEmptyFeatureCount - precisionEmptyFeatureSample.size());
            }
            return new CandidateGeneration(
                    reservation,
                    execution,
                    evidence,
                    counts,
                    filtered.retainedCandidates(),
                    processingEvidence);
        }

        private static void sampleFeature(List<String> sample, String featureId) {
            if (sample.size() < PRECISION_FEATURE_SAMPLE_LIMIT) {
                sample.add(featureId);
            }
        }
    }

    /**
     * Apply the action-captured same-class duplicate setting.
     *
     * <p>The method consumes detached snapshots and returns only candidate omissions. It cannot
     * emit removals, geometry/class/provenance changes, or parent assignments for existing objects.
     */
    public static Result suppressOverlappingSameClassDuplicates(
            List<SlideCandidate> candidates,
            Collection<ExistingObjectSnapshot> existingObjects,
            boolean enabled) {
        List<SlideCandidate> input = CandidateGeneration.validatedCandidates(candidates);
        Objects.requireNonNull(existingObjects, "existingObjects");
        if (input.stream().noneMatch(candidate -> candidate.roiSlide().isArea())) {
            // Positive-area overlap cannot suppress centers. Do not inspect or index manual
            // outlines merely to obtain a zero-area intersection for every point.
            return new Result(input, 0);
        }
        List<ExistingObjectSnapshot> existing = List.copyOf(existingObjects);
        if (!enabled || input.isEmpty() || existing.isEmpty()) {
            return new Result(input, 0);
        }

        Map<CompatibilityKey, STRtree> indexes = indexes(existing);
        if (indexes.isEmpty()) {
            return new Result(input, 0);
        }

        List<SlideCandidate> retained = new ArrayList<>(input.size());
        long suppressed = 0;
        for (SlideCandidate candidate : input) {
            if (candidate.roiSlide().isPoint()) {
                retained.add(candidate);
                continue;
            }
            STRtree index =
                    indexes.get(new CompatibilityKey(candidate.plane(), candidate.pathClass()));
            if (index != null && isSuppressed(candidate, index)) {
                suppressed++;
            } else {
                retained.add(candidate);
            }
        }
        return new Result(retained, suppressed);
    }

    private static void requirePlanMatchesRequest(
            PlannedModelInput planned, FiniteInferenceCodec.FiniteInferenceRequest request) {
        RasterInputCodec.ModelRasterInput rasterInput = request.rasterInput();
        var sourceView = rasterInput.sourceView();
        SourceInputPlan.SourceViewPlan plannedView = planned.sourceView();
        if (!planned.output().producer().equals(request.producer())
                || !planned.frame().frameDigest().equals(rasterInput.frame().frameDigest())
                || !sourceLevelMatches(plannedView, sourceView)
                || !plannedView.plane().equals(sourceView.plane())
                || !plannedView.pixelRect().equals(sourceView.pixelRect())
                || !plannedView.viewPixelsToSlide().equals(sourceView.viewPixelsToSlide())) {
            throw new IllegalArgumentException(
                    "planned model input does not match submitted finite request");
        }
        boolean parentScope = planned.output().scope() instanceof ExecutionScopeCodec.ParentScope;
        if (parentScope != planned.acceptance().parentGeometrySlide().isPresent()) {
            throw new IllegalArgumentException(
                    "planned parent geometry must be present exactly for a parent scope");
        }
    }

    private static void requireResultMatchesRequest(
            FiniteInferenceCodec.InferenceResult result,
            FiniteInferenceCodec.FiniteInferenceRequest request,
            BackendExecutionCodec.FiniteCall execution) {
        FiniteInferenceCodec.BackendResultMeta meta = result.meta();
        if (!meta.actionId().equals(request.actionId())
                || !meta.outputGenerationId().equals(request.outputGenerationId())
                || !meta.backendCallId().equals(execution.backendCallId())
                || meta.inputId() != request.inputId()
                || !meta.producer().equals(request.producer())
                || !meta.backend().equals(request.expectedBackend())
                || !meta.inputIdentity().equals(FiniteInferenceCodec.inputIdentity(request))
                || meta.coordinateSpace() != FiniteInferenceCodec.CoordinateSpace.RASTER_LOCAL) {
            throw new IllegalArgumentException(
                    "validated result does not match finite request and execution");
        }
    }

    private static void requireReservationMatchesRequest(
            ImageDataStateCodec.GenerationStoreReservation reservation,
            FiniteInferenceCodec.FiniteInferenceRequest request,
            PlannedModelInput planned,
            CandidateGeneration.InputEvidence evidence) {
        if (!reservation.actionId().equals(request.actionId())
                || !reservation.outputGenerationId().equals(request.outputGenerationId())
                || !reservation.output().equals(planned.output())
                || reservation.inputId() != request.inputId()
                || reservation.executionType()
                        != ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL
                || !reservation.backendIdentity().equals(request.expectedBackend())
                || !reservation
                        .sourceRevision()
                        .equals(request.rasterInput().frame().sourceRevision())
                || !reservation.targetRegion().equals(planned.acceptance().target())
                || !reservation
                        .modelRasterInputDigest()
                        .equals(evidence.rasterInput().inputDigest())
                || !reservation.scopeAcceptanceDigest().equals(evidence.scopeAcceptanceDigest())) {
            throw new IllegalArgumentException(
                    "pre-dispatch reservation does not match the finite request and input plan");
        }
    }

    private static void requirePlanMatchesDurableInput(
            PlannedModelInput planned, ModelRunRequestCodec.ModelRunInput input) {
        RasterInputCodec.ModelRasterInput rasterInput = input.rasterInput();
        var sourceView = rasterInput.sourceView();
        SourceInputPlan.SourceViewPlan plannedView = planned.sourceView();
        if (!planned.output().equals(input.output())
                || !planned.frame().frameDigest().equals(rasterInput.frame().frameDigest())
                || !sourceLevelMatches(plannedView, sourceView)
                || !plannedView.plane().equals(sourceView.plane())
                || !plannedView.pixelRect().equals(sourceView.pixelRect())
                || !plannedView.viewPixelsToSlide().equals(sourceView.viewPixelsToSlide())) {
            throw new IllegalArgumentException(
                    "reconstructed model input does not match its retained durable request");
        }
        boolean parentScope = input.output().scope() instanceof ExecutionScopeCodec.ParentScope;
        if (parentScope != planned.acceptance().parentGeometrySlide().isPresent()) {
            throw new IllegalArgumentException(
                    "reconstructed parent geometry must occur exactly for a parent scope");
        }
    }

    private static void requireReservationMatchesDurableInput(
            ImageDataStateCodec.GenerationStoreReservation reservation,
            ModelRunRequestCodec.ModelRunInput input,
            PlannedModelInput planned,
            CandidateGeneration.InputEvidence evidence) {
        if (!reservation.outputGenerationId().equals(input.outputGenerationId())
                || !reservation.output().equals(input.output())
                || !reservation.output().equals(planned.output())
                || reservation.inputId() != input.inputId()
                || reservation.executionType()
                        != ImageDataStateCodec.ProjectionExecutionType.DURABLE_RUN
                || !reservation
                        .sourceRevision()
                        .equals(input.rasterInput().frame().sourceRevision())
                || !reservation.targetRegion().equals(planned.acceptance().target())
                || !reservation
                        .modelRasterInputDigest()
                        .equals(evidence.rasterInput().inputDigest())
                || !reservation.scopeAcceptanceDigest().equals(evidence.scopeAcceptanceDigest())) {
            throw new IllegalArgumentException(
                    "pre-dispatch reservation does not match the durable request and input plan");
        }
    }

    private static boolean sourceLevelMatches(
            SourceInputPlan.SourceViewPlan planned, RasterInputCodec.RasterSourceView view) {
        if (view instanceof RasterInputCodec.SourceView sealed) {
            return planned.seriesIndex() == sealed.seriesIndex()
                    && planned.resolutionLevel() == sealed.resolutionLevel();
        }
        // Prepared captures use single-resolution files. The original pyramid level is in the
        // capture.
        return planned.seriesIndex() == 0 && planned.resolutionLevel() == 0;
    }

    private static Map<CompatibilityKey, STRtree> indexes(
            Collection<ExistingObjectSnapshot> existingObjects) {
        Map<CompatibilityKey, STRtree> indexes = new HashMap<>();
        for (ExistingObjectSnapshot existing : existingObjects) {
            if (!isSuppressionSource(
                            existing.objectType,
                            existing.pathClass,
                            existing.attributed,
                            existing.administrative)
                    || !isValidAreaGeometry(existing.geometrySlide)) {
                continue;
            }
            CompatibilityKey key =
                    new CompatibilityKey(existing.plane, existing.pathClass.orElseThrow());
            indexes.computeIfAbsent(key, ignored -> new STRtree())
                    .insert(existing.geometrySlide.getEnvelopeInternal(), existing);
        }
        indexes.values().forEach(STRtree::build);
        return indexes;
    }

    /**
     * Whether an object's type and attribution permit it to suppress a prediction.
     *
     * <p>Callers can exclude ineligible objects before copying their geometry. Valid geometry,
     * matching class and plane, and positive-area overlap are checked separately by the filter.
     */
    public static boolean isSuppressionSource(
            ExistingObjectType objectType,
            Optional<String> pathClass,
            boolean attributed,
            boolean administrative) {
        return !attributed
                && !administrative
                && pathClass.isPresent()
                && (objectType == ExistingObjectType.ANNOTATION
                        || objectType == ExistingObjectType.DETECTION);
    }

    private static boolean isSuppressed(SlideCandidate candidate, STRtree index) {
        Geometry candidateGeometry = candidate.geometrySlide();
        @SuppressWarnings("unchecked")
        List<ExistingObjectSnapshot> possible =
                index.query(candidateGeometry.getEnvelopeInternal());
        for (ExistingObjectSnapshot existing : possible) {
            Geometry intersection = candidateGeometry.intersection(existing.geometrySlide);
            if (intersection.getArea() > 0.0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidAreaGeometry(Geometry geometry) {
        try {
            CanonicalGeometryCodec.canonicalCoordinates(geometry);
        } catch (IllegalArgumentException error) {
            return false;
        }
        return geometry.getArea() > 0.0;
    }

    private record CompatibilityKey(SourceIdentityCodec.ImagePlaneIndex plane, String pathClass) {}
}

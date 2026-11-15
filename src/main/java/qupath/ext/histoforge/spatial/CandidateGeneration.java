package qupath.ext.histoforge.spatial;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProcessingEvidenceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.lib.roi.interfaces.ROI;

/** Complete candidates and production evidence for one validated logical input. */
public record CandidateGeneration(
        // Exact pre-dispatch action, output, input, build, deployment, and backend reservation.
        ImageDataStateCodec.GenerationStoreReservation reservation,
        // Accepted finite call or immutable durable result that supplied the features.
        BackendExecutionCodec.BackendExecutionRef execution,
        // Complete source, raster, transform, and acceptance evidence for this input.
        InputEvidence inputEvidence,
        // Auditable feature counts through acceptance and optional suppression.
        GenerationEvidenceCodec.GenerationCounts counts,
        // Scope-accepted, persistence-stable candidates remaining after optional suppression.
        List<SlideCandidate> candidates,
        // Compact facts projected from the validated durable manifest plan, absent for finite
        // calls.
        Optional<ProcessingEvidenceCodec.ProcessingEvidence> processingEvidence) {

    public CandidateGeneration(
            ImageDataStateCodec.GenerationStoreReservation reservation,
            BackendExecutionCodec.BackendExecutionRef execution,
            InputEvidence inputEvidence,
            GenerationEvidenceCodec.GenerationCounts counts,
            List<SlideCandidate> candidates) {
        this(
                reservation,
                execution,
                inputEvidence,
                counts,
                candidates,
                reservation.processingEvidence());
    }

    public CandidateGeneration {
        reservation = Objects.requireNonNull(reservation, "reservation");
        execution = Objects.requireNonNull(execution, "execution");
        inputEvidence = Objects.requireNonNull(inputEvidence, "input_evidence");
        counts = Objects.requireNonNull(counts, "counts");
        candidates = validatedCandidates(candidates);
        processingEvidence = Objects.requireNonNull(processingEvidence, "processing_evidence");
        if (!processingEvidence.equals(reservation.processingEvidence())) {
            throw new IllegalArgumentException(
                    "candidate processing evidence does not match its pre-dispatch reservation");
        }
        if (!reservation
                        .output()
                        .producer()
                        .moduleRef()
                        .equals(reservation.backendIdentity().module().moduleRef())
                || !reservation
                        .output()
                        .producer()
                        .stepId()
                        .equals(reservation.backendIdentity().modelId())) {
            throw new IllegalArgumentException(
                    "candidate output producer does not match backend identity");
        }
        if (!ExecutionScopeCodec.regionOf(reservation.output().scope())
                .equals(inputEvidence.acceptance().target().key())) {
            throw new IllegalArgumentException(
                    "candidate output scope does not match acceptance target");
        }
        boolean parentScope =
                reservation.output().scope() instanceof ExecutionScopeCodec.ParentScope;
        if (parentScope != inputEvidence.acceptance().parentGeometrySlide().isPresent()) {
            throw new IllegalArgumentException(
                    "parent acceptance geometry must be present exactly for a parent scope");
        }
        reservation.requireCompletedEvidence(inputEvidence.committedEvidence());
        if (!reservation
                        .sourceRevision()
                        .equals(inputEvidence.rasterInput().frame().sourceRevision())
                || !reservation.targetRegion().equals(inputEvidence.acceptance().target())) {
            throw new IllegalArgumentException(
                    "candidate input evidence does not match its pre-dispatch reservation");
        }
        boolean finiteExecution = execution instanceof BackendExecutionCodec.FiniteCall;
        boolean finiteReservation =
                reservation.executionType()
                        == ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL;
        if (finiteExecution != finiteReservation) {
            throw new IllegalArgumentException(
                    "candidate execution family does not match its pre-dispatch reservation");
        }
        if (counts.committedObjects() != candidates.size()) {
            throw new IllegalArgumentException(
                    "candidate count does not match generation committed_objects");
        }
        for (SlideCandidate candidate : candidates) {
            if (!candidate.plane().equals(inputEvidence.acceptance().target().plane())) {
                throw new IllegalArgumentException(
                        "candidate plane does not match acceptance target plane");
            }
        }
    }

    /** Stable identity of the logical output generation being staged. */
    public String outputGenerationId() {
        return reservation.outputGenerationId();
    }

    /** Producer and logical scope that own later replacement of this output. */
    public ExecutionScopeCodec.OutputKey output() {
        return reservation.output();
    }

    /** Exact immutable model implementation that produced the result. */
    public InferenceIdentityCodec.BackendModelIdentity backend() {
        return reservation.backendIdentity();
    }

    /** Stable logical input position in the action-local model plan. */
    public int inputId() {
        return reservation.inputId();
    }

    /** One deterministic candidate after scope acceptance and QuPath ROI materialization. */
    public static final class SlideCandidate {
        private final String candidateId;
        private final ROI roiSlide;
        private final String pathClass;
        private final SourceIdentityCodec.ImagePlaneIndex plane;

        /** Validate a supplied native outline before sharing it read-only within the extension. */
        public SlideCandidate(
                String candidateId,
                ROI roiSlide,
                String pathClass,
                SourceIdentityCodec.ImagePlaneIndex plane) {
            this(
                    SourceInputGeometry.validatedPredictionRoi(roiSlide, plane),
                    candidateId,
                    pathClass,
                    plane);
        }

        /**
         * Convert an accepted model polygon once to the native outline used for staging and save.
         */
        public SlideCandidate(
                String candidateId,
                Geometry geometrySlide,
                String pathClass,
                SourceIdentityCodec.ImagePlaneIndex plane) {
            this(
                    SourceInputGeometry.roiForQuPathPersistence(geometrySlide, plane),
                    candidateId,
                    pathClass,
                    plane);
        }

        /** Reuse the native outline already validated by the shared prediction conversion. */
        static SlideCandidate fromMaterializedPrediction(
                String candidateId,
                SourceInputGeometry.PredictionOutline outline,
                String pathClass,
                SourceIdentityCodec.ImagePlaneIndex plane) {
            return new SlideCandidate(outline.roi().orElseThrow(), candidateId, pathClass, plane);
        }

        // Public constructors validate the outline. Internal materialization and class-only
        // handoffs preserve it without rebuilding and validating its native geometry again.
        private SlideCandidate(
                ROI validatedOutline,
                String candidateId,
                String pathClass,
                SourceIdentityCodec.ImagePlaneIndex plane) {
            this.candidateId = CanonicalCodec.requireCanonicalUuid(candidateId, "candidate_id");
            this.roiSlide = validatedOutline;
            this.pathClass = CanonicalCodec.requirePathClassName(pathClass, "candidate.path_class");
            this.plane = Objects.requireNonNull(plane, "candidate.plane");
        }

        public String candidateId() {
            return candidateId;
        }

        public ROI roiSlide() {
            return roiSlide;
        }

        public String pathClass() {
            return pathClass;
        }

        public SourceIdentityCodec.ImagePlaneIndex plane() {
            return plane;
        }

        /** Preserve the validated native outline when a checked script changes only its class. */
        public SlideCandidate withPathClass(String newClass) {
            return pathClass.equals(newClass)
                    ? this
                    : new SlideCandidate(roiSlide, candidateId, newClass, plane);
        }

        /** Detached geometry for spatial operations; the native outline remains read-only. */
        public Geometry geometrySlide() {
            return NativeRoiGeometry.convert(roiSlide).copy();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SlideCandidate candidate
                    && candidateId.equals(candidate.candidateId)
                    && roiSlide.equals(candidate.roiSlide)
                    && pathClass.equals(candidate.pathClass)
                    && plane.equals(candidate.plane);
        }

        @Override
        public int hashCode() {
            int hash = candidateId.hashCode();
            hash = 31 * hash + roiSlide.hashCode();
            hash = 31 * hash + pathClass.hashCode();
            return 31 * hash + plane.hashCode();
        }

        @Override
        public String toString() {
            return "SlideCandidate[candidateId="
                    + candidateId
                    + ", roiSlide="
                    + roiSlide
                    + ", pathClass="
                    + pathClass
                    + ", plane="
                    + plane
                    + "]";
        }
    }

    /** Exact full input and scope evidence retained while this output generation is staged. */
    public record InputEvidence(
            // Backend echo binding all contributing files, source view, and raster input.
            FiniteInferenceCodec.BackendInputIdentity backendInputIdentity,
            // Canonical digest of the complete target and optional parent acceptance value.
            String scopeAcceptanceDigest,
            // Full slide-coordinate target and optional exact source-parent geometry.
            ExecutionScopeCodec.ScopeAcceptance acceptance,
            // Exact source view, target raster, transform, mask, and rasterizer request.
            RasterInputCodec.ModelRasterInput rasterInput) {
        public InputEvidence {
            backendInputIdentity =
                    Objects.requireNonNull(backendInputIdentity, "backend_input_identity");
            scopeAcceptanceDigest =
                    CanonicalCodec.requireSha256(scopeAcceptanceDigest, "scope_acceptance_digest");
            acceptance = Objects.requireNonNull(acceptance, "acceptance");
            rasterInput = Objects.requireNonNull(rasterInput, "raster_input");
            if (!backendInputIdentity.equals(
                    FiniteInferenceCodec.buildBackendInputIdentity(
                            rasterInput, backendInputIdentity.sourceBindings()))) {
                throw new IllegalArgumentException(
                        "backend input identity does not match model raster input");
            }
            if (!scopeAcceptanceDigest.equals(
                    ExecutionScopeCodec.scopeAcceptanceDigest(acceptance))) {
                throw new IllegalArgumentException(
                        "scope acceptance digest does not match acceptance geometry");
            }
            if (!acceptance.target().key().equals(rasterInput.frame().region())
                    || !acceptance
                            .target()
                            .plane()
                            .equals(rasterInput.frame().transform().raster().plane())) {
                throw new IllegalArgumentException(
                        "acceptance target does not match model raster input region and plane");
            }
        }

        /** Derive the completed input proof, including every accepted uploaded file. */
        public GenerationEvidenceCodec.CommittedInputEvidence committedEvidence() {
            RasterInputCodec.ActionSourceRevision sourceRevision =
                    rasterInput.frame().sourceRevision();
            ExecutionScopeCodec.TargetRegion target = acceptance.target();
            return new GenerationEvidenceCodec.CommittedInputEvidence(
                    rasterInput.inputDigest(),
                    RasterInputCodec.actionSourceRevisionDigest(sourceRevision),
                    ExecutionScopeCodec.targetRegionDigest(target),
                    acceptance.parentGeometrySlide().map(CanonicalGeometryCodec::canonicalDigest),
                    rasterInput.sourceView(),
                    rasterInput.frame().transform(),
                    rasterInput.frame().inclusionMaskDigest(),
                    scopeAcceptanceDigest,
                    rasterInput.frame().frameDigest(),
                    backendInputIdentity.sourceBindings());
        }
    }

    static List<SlideCandidate> validatedCandidates(List<SlideCandidate> candidates) {
        List<SlideCandidate> result = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        Set<String> ids = new HashSet<>();
        for (SlideCandidate candidate : result) {
            Objects.requireNonNull(candidate, "candidate");
            if (!ids.add(candidate.candidateId())) {
                throw new IllegalArgumentException("candidate IDs must be unique");
            }
        }
        return result;
    }
}

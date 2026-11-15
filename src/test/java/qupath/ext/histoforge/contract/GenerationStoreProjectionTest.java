package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

/** Pins conservative pre-dispatch sizing without making projection records persistent state. */
class GenerationStoreProjectionTest {

    private static final long MAX_SAFE_COUNT = 9_007_199_254_740_991L;
    private static final long MAX_SUPPRESSED_COUNT = 1_000_000_000_000_000L;
    private static final long MAX_COMMITTED_COUNT = MAX_SAFE_COUNT - MAX_SUPPRESSED_COUNT;
    private static final Instant MAX_TIMESTAMP = Instant.parse("9999-12-31T23:59:59.999Z");

    @Test
    void projectionSweepsCurrentEvidenceAndIncludesStagedAndPlannedRecords() {
        GenerationStoreCodec.GenerationStore current = GenerationStoreTestData.validStore();
        GenerationStoreCodec.GenerationStore staged = GenerationStoreTestData.validStore();
        var planned = reservation(1, true, ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL);

        long projected = project(current, List.of(), List.of(staged), List.of(planned), 1_000_000);
        long withoutCurrent =
                project(emptyStore(), List.of(), List.of(staged), List.of(planned), 1_000_000);
        long stagedOnly = project(emptyStore(), List.of(), List.of(staged), List.of(), 1_000_000);

        assertEquals(withoutCurrent, projected);
        assertTrue(projected > stagedOnly);
        assertEquals(
                projected,
                project(current, List.of(), List.of(staged), List.of(planned), projected));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        project(
                                current,
                                List.of(),
                                List.of(staged),
                                List.of(planned),
                                projected - 1));
    }

    @Test
    void finiteProjectionUsesTheLargestValidJcsCallIdAndCountEncoding() {
        var reservation =
                reservation(2, true, ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL);
        long projected =
                project(emptyStore(), List.of(), List.of(), List.of(reservation), 1_000_000);
        GenerationStoreCodec.GenerationStore maximumActual =
                storeFor(
                        reservation,
                        new BackendExecutionCodec.FiniteCall(
                                "\u0001"
                                        .repeat(
                                                BackendExecutionCodec
                                                        .MAX_BACKEND_CALL_ID_UTF8_BYTES)),
                        maximumCounts(true));

        assertEquals(GenerationStoreCodec.canonicalBytes(maximumActual).length, projected);
    }

    @Test
    void durableProjectionReservesTheCompleteFixedWidthExecutionReference() {
        var reservation =
                reservation(3, false, ImageDataStateCodec.ProjectionExecutionType.DURABLE_RUN);
        long projected =
                project(emptyStore(), List.of(), List.of(), List.of(reservation), 1_000_000);
        GenerationStoreCodec.GenerationStore maximumActual =
                storeFor(
                        reservation,
                        new BackendExecutionCodec.DurableRun(
                                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                GenerationStoreTestData.hex('f')),
                        maximumCounts(false));

        assertEquals(GenerationStoreCodec.canonicalBytes(maximumActual).length, projected);
    }

    @Test
    void conflictingPlannedGenerationIdentityFailsClosed() {
        var first = reservation(4, true, ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL);
        var conflicting =
                new ImageDataStateCodec.GenerationStoreReservation(
                        first.outputGenerationId(),
                        first.actionId(),
                        first.removeOverlappingSameClassDuplicates(),
                        first.deploymentId(),
                        first.output(),
                        first.inputId() + 1,
                        first.executionType(),
                        first.modelRasterInputDigest(),
                        first.scopeAcceptanceDigest(),
                        first.maxInputEvidenceUtf8Bytes(),
                        first.sourceRevision(),
                        first.targetRegion(),
                        first.clientBuild(),
                        first.backendIdentity(),
                        first.processingEvidence());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        project(
                                emptyStore(),
                                List.of(),
                                List.of(),
                                List.of(first, conflicting),
                                1_000_000));
    }

    @Test
    void fiveThousandParentInputsFitWithMoreThanDoubleByteCapacity() {
        assertEquals(33_554_432, ImageDataStateCodec.SUPPORTED_MAX_GENERATION_STORE_UTF8_BYTES);
        var planned = parentReservations(5_000);
        long projected =
                ImageDataStateCodec.requireGenerationStoreProjectionFits(
                        emptyStore(),
                        List.of(),
                        List.of(),
                        planned,
                        planned.getFirst().clientBuild().limits());

        assertEquals(16_466_929, projected);
        assertTrue(
                Math.multiplyExact(projected, 2)
                        < ImageDataStateCodec.SUPPORTED_MAX_GENERATION_STORE_UTF8_BYTES);
    }

    private static long project(
            GenerationStoreCodec.GenerationStore current,
            List<ProvenanceCodec.ObjectProvenance> provenance,
            List<GenerationStoreCodec.GenerationStore> staged,
            List<ImageDataStateCodec.GenerationStoreReservation> planned,
            long cap) {
        return ImageDataStateCodec.requireGenerationStoreProjectionFits(
                current,
                provenance,
                staged,
                planned,
                GenerationStoreTestData.limitsWithStoreCap(cap));
    }

    private static ImageDataStateCodec.GenerationStoreReservation reservation(
            int ordinal,
            boolean suppressionEnabled,
            ImageDataStateCodec.ProjectionExecutionType executionType) {
        return reservation(
                ordinal, suppressionEnabled, executionType, GenerationStoreTestData.clientBuild());
    }

    private static ImageDataStateCodec.GenerationStoreReservation reservation(
            int ordinal,
            boolean suppressionEnabled,
            ImageDataStateCodec.ProjectionExecutionType executionType,
            BuildIdentityCodec.ClientBuildIdentity client) {
        RasterInputCodec.ActionSourceRevision source = GenerationStoreTestData.sourceRevision();
        ExecutionScopeCodec.TargetRegion target = GenerationStoreTestData.targetRegion();
        InferenceIdentityCodec.BackendModelIdentity backend =
                GenerationStoreTestData.backendIdentity();
        GenerationEvidenceCodec.GenerationRecord base =
                GenerationStoreTestData.generationRecord(source, target, client, backend);
        var output =
                new ExecutionScopeCodec.OutputKey(
                        base.output().producer(),
                        new ExecutionScopeCodec.ParentScope(target.key(), uuid(ordinal + 1_000)));
        return ImageDataStateCodec.GenerationStoreReservation.forInput(
                uuid(ordinal + 2_000),
                base.actionId(),
                suppressionEnabled,
                base.deploymentId(),
                output,
                ordinal,
                executionType,
                GenerationStoreTestData.rasterInput(source, target, backend),
                new ExecutionScopeCodec.ScopeAcceptance(
                        target, Optional.of(parentGeometry(ordinal))),
                1,
                client,
                backend,
                executionType == ImageDataStateCodec.ProjectionExecutionType.DURABLE_RUN
                        ? Optional.of(
                                new ProcessingEvidenceCodec.CoreHaloProcessing(
                                        GenerationStoreTestData.hex('a'),
                                        GenerationStoreTestData.hex('b'),
                                        Integer.MAX_VALUE,
                                        Integer.MAX_VALUE,
                                        Integer.MAX_VALUE))
                        : Optional.empty());
    }

    private static List<ImageDataStateCodec.GenerationStoreReservation> parentReservations(
            int count) {
        var template =
                reservation(
                        10,
                        true,
                        ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL,
                        GenerationStoreTestData.clientBuildWithStoreCap(
                                ImageDataStateCodec.SUPPORTED_MAX_GENERATION_STORE_UTF8_BYTES));
        var planned = new java.util.ArrayList<ImageDataStateCodec.GenerationStoreReservation>();
        for (int index = 0; index < count; index++) {
            planned.add(
                    new ImageDataStateCodec.GenerationStoreReservation(
                            uuid(index + 10_000),
                            template.actionId(),
                            template.removeOverlappingSameClassDuplicates(),
                            template.deploymentId(),
                            new ExecutionScopeCodec.OutputKey(
                                    template.output().producer(),
                                    new ExecutionScopeCodec.ParentScope(
                                            template.targetRegion().key(), uuid(index + 20_000))),
                            index,
                            template.executionType(),
                            template.modelRasterInputDigest(),
                            template.scopeAcceptanceDigest(),
                            template.maxInputEvidenceUtf8Bytes(),
                            template.sourceRevision(),
                            template.targetRegion(),
                            template.clientBuild(),
                            template.backendIdentity(),
                            template.processingEvidence()));
        }
        return List.copyOf(planned);
    }

    private static GenerationStoreCodec.GenerationStore storeFor(
            ImageDataStateCodec.GenerationStoreReservation reservation,
            BackendExecutionCodec.BackendExecutionRef execution,
            GenerationEvidenceCodec.GenerationCounts counts) {
        String clientDigest =
                BuildIdentityCodec.clientBuildIdentityDigest(reservation.clientBuild());
        String backendDigest =
                InferenceIdentityCodec.backendModelIdentityDigest(reservation.backendIdentity());
        var record =
                new GenerationEvidenceCodec.GenerationRecord(
                        reservation.outputGenerationId(),
                        reservation.actionId(),
                        reservation.removeOverlappingSameClassDuplicates(),
                        clientDigest,
                        reservation.deploymentId(),
                        reservation.output(),
                        reservation.inputId(),
                        backendDigest,
                        execution,
                        evidence(reservation.inputId()),
                        counts,
                        MAX_TIMESTAMP,
                        reservation.processingEvidence());
        return new GenerationStoreCodec.GenerationStore(
                GenerationStoreCodec.SCHEMA_VERSION,
                Map.of(
                        RasterInputCodec.actionSourceRevisionDigest(reservation.sourceRevision()),
                        reservation.sourceRevision()),
                Map.of(
                        ExecutionScopeCodec.targetRegionDigest(reservation.targetRegion()),
                        reservation.targetRegion()),
                Map.of(clientDigest, reservation.clientBuild()),
                Map.of(backendDigest, reservation.backendIdentity()),
                Map.of(record.outputGenerationId(), record));
    }

    private static GenerationEvidenceCodec.CommittedInputEvidence evidence(int ordinal) {
        var target = GenerationStoreTestData.targetRegion();
        var base =
                GenerationStoreTestData.generationRecord(
                        GenerationStoreTestData.sourceRevision(),
                        target,
                        GenerationStoreTestData.clientBuild(),
                        GenerationStoreTestData.backendIdentity());
        Geometry parentGeometry = parentGeometry(ordinal);
        return new GenerationEvidenceCodec.CommittedInputEvidence(
                base.inputEvidence().modelRasterInputDigest(),
                base.inputEvidence().sourceRevisionDigest(),
                base.inputEvidence().targetRegionDigest(),
                Optional.of(CanonicalGeometryCodec.canonicalDigest(parentGeometry)),
                base.inputEvidence().sourceView(),
                base.inputEvidence().rasterTransform(),
                base.inputEvidence().inclusionMaskDigest(),
                ExecutionScopeCodec.scopeAcceptanceDigest(
                        new ExecutionScopeCodec.ScopeAcceptance(
                                target, Optional.of(parentGeometry))),
                base.inputEvidence().rasterFrameDigest());
    }

    private static Geometry parentGeometry(int ordinal) {
        return GenerationStoreTestData.square(0, 0, 150 + ordinal, 150 + ordinal);
    }

    private static GenerationEvidenceCodec.GenerationCounts maximumCounts(
            boolean suppressionEnabled) {
        return suppressionEnabled
                ? new GenerationEvidenceCodec.GenerationCounts(
                        MAX_SAFE_COUNT, MAX_SAFE_COUNT, MAX_SUPPRESSED_COUNT, MAX_COMMITTED_COUNT)
                : new GenerationEvidenceCodec.GenerationCounts(
                        MAX_SAFE_COUNT, MAX_SAFE_COUNT, 0, MAX_SAFE_COUNT);
    }

    private static GenerationStoreCodec.GenerationStore emptyStore() {
        return new GenerationStoreCodec.GenerationStore(
                GenerationStoreCodec.SCHEMA_VERSION,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private static String uuid(int value) {
        return String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", value);
    }
}

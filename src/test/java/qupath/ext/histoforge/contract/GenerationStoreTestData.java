package qupath.ext.histoforge.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;

/** Shared realistic generation-store data for persistence and projection tests. */
public final class GenerationStoreTestData {

    static final String ACTION_ID = "11111111-1111-4111-8111-111111111111";
    static final String GENERATION_ID = "22222222-2222-4222-8222-222222222222";
    static final String ROI_ID = "33333333-3333-4333-8333-333333333333";
    static final String SECOND_GENERATION_ID = "44444444-4444-4444-8444-444444444444";
    static final String MISSING_GENERATION_ID = "55555555-5555-4555-8555-555555555555";
    static final String PARENT_ID = "77777777-7777-4777-8777-777777777777";

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private GenerationStoreTestData() {}

    static GenerationStoreCodec.GenerationStore validStore() {
        RasterInputCodec.ActionSourceRevision source = sourceRevision();
        ExecutionScopeCodec.TargetRegion target =
                targetRegion(new ExecutionScopeCodec.Roi(ROI_ID), square(10, 20, 110, 120));
        BuildIdentityCodec.ClientBuildIdentity client = clientBuild();
        InferenceIdentityCodec.BackendModelIdentity backend = backendIdentity();
        GenerationEvidenceCodec.GenerationRecord record =
                generationRecord(source, target, client, backend);
        return store(source, target, client, backend, Map.of(record.outputGenerationId(), record));
    }

    /** A second generation with one exact captured-source file, shared by store/export tests. */
    public static GenerationStoreCodec.GenerationStore capturedStore() {
        var source = sourceRevision();
        var sealedRecord = generationRecord();
        var original = sealedRecord.inputEvidence();
        var sealedView = (RasterInputCodec.SourceView) original.sourceView();
        var identityMapping = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        var capture =
                new SourceCaptureCodec.Capture(
                        source.pixelSource(),
                        sealedView.plane(),
                        new SourceArtifactCodec.SourceLevelRequirement(
                                0, 0, source.pixelSource().width(), source.pixelSource().height()),
                        identityMapping,
                        identityMapping,
                        sealedView.pixelRect(),
                        clientBuild().extension());
        var view = new SourceCaptureCodec.View(capture, sealedView.pixelRect());
        var binding =
                new SourceCaptureCodec.UnitBinding(
                        0,
                        new SourceCaptureCodec.Binding(
                                capture.captureDigest(),
                                sealedView.artifact(),
                                capture.sourceRectAtLevel()));
        var evidence =
                new GenerationEvidenceCodec.CommittedInputEvidence(
                        RasterInputCodec.modelRasterInputDigest(
                                view.viewDigest(),
                                original.rasterFrameDigest(),
                                backendIdentity().rasterizer()),
                        original.sourceRevisionDigest(),
                        original.targetRegionDigest(),
                        original.parentGeometryDigest(),
                        view,
                        original.rasterTransform(),
                        original.inclusionMaskDigest(),
                        original.scopeAcceptanceDigest(),
                        original.rasterFrameDigest(),
                        List.of(binding));
        var record =
                copyRecord(sealedRecord, SECOND_GENERATION_ID, sealedRecord.output(), evidence);
        return store(
                source,
                targetRegion(),
                clientBuild(),
                backendIdentity(),
                Map.of(record.outputGenerationId(), record));
    }

    static GenerationStoreCodec.GenerationStore store(
            RasterInputCodec.ActionSourceRevision source,
            ExecutionScopeCodec.TargetRegion target,
            BuildIdentityCodec.ClientBuildIdentity client,
            InferenceIdentityCodec.BackendModelIdentity backend,
            Map<String, GenerationEvidenceCodec.GenerationRecord> records) {
        return new GenerationStoreCodec.GenerationStore(
                GenerationStoreCodec.SCHEMA_VERSION,
                Map.of(RasterInputCodec.actionSourceRevisionDigest(source), source),
                Map.of(ExecutionScopeCodec.targetRegionDigest(target), target),
                Map.of(BuildIdentityCodec.clientBuildIdentityDigest(client), client),
                Map.of(InferenceIdentityCodec.backendModelIdentityDigest(backend), backend),
                records);
    }

    static GenerationEvidenceCodec.GenerationRecord generationRecord() {
        return generationRecord(sourceRevision(), targetRegion(), clientBuild(), backendIdentity());
    }

    static GenerationEvidenceCodec.GenerationRecord generationRecord(
            RasterInputCodec.ActionSourceRevision source,
            ExecutionScopeCodec.TargetRegion target,
            BuildIdentityCodec.ClientBuildIdentity client,
            InferenceIdentityCodec.BackendModelIdentity backend) {
        var input = rasterInput(source, target, backend);
        var frame = input.frame();
        GenerationEvidenceCodec.CommittedInputEvidence evidence =
                new GenerationEvidenceCodec.CommittedInputEvidence(
                        input.inputDigest(),
                        RasterInputCodec.actionSourceRevisionDigest(source),
                        ExecutionScopeCodec.targetRegionDigest(target),
                        Optional.empty(),
                        input.sourceView(),
                        frame.transform(),
                        frame.inclusionMaskDigest(),
                        ExecutionScopeCodec.scopeAcceptanceDigest(
                                new ExecutionScopeCodec.ScopeAcceptance(target, Optional.empty())),
                        frame.frameDigest());
        ExecutionScopeCodec.OutputKey output =
                new ExecutionScopeCodec.OutputKey(
                        new InferenceIdentityCodec.ModelStepIdentity(moduleRef(), "leydig"),
                        new ExecutionScopeCodec.FieldScope(target.key()));
        return new GenerationEvidenceCodec.GenerationRecord(
                GENERATION_ID,
                ACTION_ID,
                true,
                BuildIdentityCodec.clientBuildIdentityDigest(client),
                "fixture-deployment",
                output,
                7,
                InferenceIdentityCodec.backendModelIdentityDigest(backend),
                new BackendExecutionCodec.FiniteCall("fc-fixture"),
                evidence,
                new GenerationEvidenceCodec.GenerationCounts(5, 4, 1, 3),
                Instant.parse("2026-08-15T01:02:03.004Z"));
    }

    static RasterInputCodec.ModelRasterInput rasterInput(
            RasterInputCodec.ActionSourceRevision source,
            ExecutionScopeCodec.TargetRegion target,
            InferenceIdentityCodec.BackendModelIdentity backend) {
        SourceIdentityCodec.ImagePlaneIndex plane = target.plane();
        SourceArtifactPublicationCodec.SourceArtifactRef artifact =
                new SourceArtifactPublicationCodec.SourceArtifactRef(
                        "66666666-6666-4666-8666-666666666666",
                        ACTION_ID,
                        hex('1'),
                        hex('2'),
                        hex('3'),
                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY);
        RasterInputCodec.SourceView sourceView =
                RasterInputCodec.buildSourceView(
                        artifact,
                        0,
                        0,
                        plane,
                        new SourceIdentityCodec.IntRect(0, 0, 200, 200),
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0));
        RasterInputCodec.RasterTransform transform =
                new RasterInputCodec.RasterTransform(
                        new RasterInputCodec.RasterDescriptor(
                                50,
                                50,
                                plane,
                                1.0,
                                RasterInputCodec.ResamplingType.BILINEAR,
                                RasterInputCodec.RasterPixelFormat.SRGB_UINT8),
                        new SourceIdentityCodec.ScaleTranslate2D(2, 2, 10, 20));
        RasterInputCodec.RasterFrame frame =
                RasterInputCodec.buildRasterFrame(
                        source, target.key(), transform, square(0, 0, 50, 50));
        return RasterInputCodec.buildModelRasterInput(sourceView, frame, backend.rasterizer());
    }

    static RasterInputCodec.ActionSourceRevision sourceRevision() {
        SourceIdentityCodec.ImageSourceIdentity source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "fixture.reader",
                        hex('5'),
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        123456, 987654321, Optional.empty())),
                        1000,
                        800,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.5, 0.5));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        "fixture-image",
                        CanonicalCodec.canonicalDigest(SourceIdentityCodec.toJson(source)),
                        hex('6')),
                source);
    }

    static ExecutionScopeCodec.TargetRegion targetRegion() {
        return targetRegion(new ExecutionScopeCodec.Roi(ROI_ID), square(10, 20, 110, 120));
    }

    static ExecutionScopeCodec.TargetRegion targetRegion(
            ExecutionScopeCodec.RegionKey key, Geometry geometry) {
        return new ExecutionScopeCodec.TargetRegion(
                key,
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    static BuildIdentityCodec.ClientBuildIdentity clientBuild() {
        return clientBuildWithStoreCap(100_000);
    }

    static BuildIdentityCodec.ClientBuildIdentity clientBuildWithStoreCap(long storeCap) {
        return new BuildIdentityCodec.ClientBuildIdentity(
                softwareBuild("extension"),
                "0.6.0",
                Map.of("java", "21.0.6", "groovy", "4.0.22", "jts", "1.20.0"),
                new ExecutionLimitsCodec.ClientExecutionLimits(
                        100_000,
                        1_000_000,
                        2_000_000,
                        8_000_000,
                        storeCap,
                        10_000,
                        100_000,
                        1_000_000_000));
    }

    static ExecutionLimitsCodec.ClientExecutionLimits limitsWithStoreCap(long cap) {
        return new ExecutionLimitsCodec.ClientExecutionLimits(1, 1, 1, 1, cap, 1, 1, 1);
    }

    static InferenceIdentityCodec.BackendModelIdentity backendIdentity() {
        InferenceIdentityCodec.ModuleContentIdentity module =
                new InferenceIdentityCodec.ModuleContentIdentity(
                        moduleRef(), Optional.of("a".repeat(40)), hex('7'), hex('8'));
        InferenceIdentityCodec.RasterizerIdentity rasterizer =
                new InferenceIdentityCodec.RasterizerIdentity(
                        softwareBuild("rasterizer"), hex('9'));
        return new InferenceIdentityCodec.BackendModelIdentity(
                module,
                "leydig",
                "cellpose-sam",
                "A10G",
                hex('a'),
                Map.of("weights", hex('b')),
                softwareBuild("runner"),
                rasterizer,
                hex('c'));
    }

    static ProvenanceCodec.ObjectProvenance provenance(
            ExecutionScopeCodec.OutputKey output, String generationId) {
        return new ProvenanceCodec.ObjectProvenance(1, output, generationId);
    }

    static GenerationEvidenceCodec.GenerationRecord copyRecord(
            GenerationEvidenceCodec.GenerationRecord source,
            String generationId,
            ExecutionScopeCodec.OutputKey output,
            GenerationEvidenceCodec.CommittedInputEvidence evidence) {
        return new GenerationEvidenceCodec.GenerationRecord(
                generationId,
                source.actionId(),
                source.removeOverlappingSameClassDuplicates(),
                source.clientBuildDigest(),
                source.deploymentId(),
                output,
                source.inputId(),
                source.backendIdentityDigest(),
                source.execution(),
                evidence,
                source.counts(),
                source.committedAt());
    }

    static GenerationEvidenceCodec.GenerationRecord copyRecordWithIdentityDigests(
            GenerationEvidenceCodec.GenerationRecord source,
            String clientDigest,
            String backendDigest) {
        return new GenerationEvidenceCodec.GenerationRecord(
                source.outputGenerationId(),
                source.actionId(),
                source.removeOverlappingSameClassDuplicates(),
                clientDigest,
                source.deploymentId(),
                source.output(),
                source.inputId(),
                backendDigest,
                source.execution(),
                source.inputEvidence(),
                source.counts(),
                source.committedAt());
    }

    static GenerationEvidenceCodec.GenerationRecord copyRecordWithCounts(
            GenerationEvidenceCodec.GenerationRecord source,
            boolean suppressionEnabled,
            GenerationEvidenceCodec.GenerationCounts counts) {
        return new GenerationEvidenceCodec.GenerationRecord(
                source.outputGenerationId(),
                source.actionId(),
                suppressionEnabled,
                source.clientBuildDigest(),
                source.deploymentId(),
                source.output(),
                source.inputId(),
                source.backendIdentityDigest(),
                source.execution(),
                source.inputEvidence(),
                counts,
                source.committedAt());
    }

    static GenerationEvidenceCodec.CommittedInputEvidence copyEvidence(
            GenerationEvidenceCodec.CommittedInputEvidence source,
            String sourceDigest,
            String targetDigest,
            Optional<String> parentDigest,
            RasterInputCodec.RasterSourceView sourceView,
            RasterInputCodec.RasterTransform transform) {
        return new GenerationEvidenceCodec.CommittedInputEvidence(
                source.modelRasterInputDigest(),
                sourceDigest,
                targetDigest,
                parentDigest,
                sourceView,
                transform,
                source.inclusionMaskDigest(),
                source.scopeAcceptanceDigest(),
                source.rasterFrameDigest());
    }

    static Geometry square(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.createPolygon(
                new CoordinateXY[] {
                    new CoordinateXY(minX, minY),
                    new CoordinateXY(maxX, minY),
                    new CoordinateXY(maxX, maxY),
                    new CoordinateXY(minX, maxY),
                    new CoordinateXY(minX, minY)
                });
    }

    static String hex(char value) {
        return Character.toString(value).repeat(64);
    }

    private static BuildIdentityCodec.SoftwareBuildIdentity softwareBuild(String version) {
        return new BuildIdentityCodec.SoftwareBuildIdentity(version, hex('d'), hex('e'));
    }

    private static ModuleRef moduleRef() {
        return ModuleRef.parse("histoforge/leydig").orElseThrow();
    }
}

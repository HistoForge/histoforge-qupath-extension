package qupath.ext.histoforge.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration;

/** Shared realistic model-output values for staged pipeline tests. */
final class PipelineTestData {

    static final String ACTION_ID = "11111111-1111-4111-8111-111111111111";
    static final String ROI_ID = "22222222-2222-4222-8222-222222222222";
    static final String DEPLOYMENT_ID = "fixture-deployment";

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private PipelineTestData() {}

    static CandidateGeneration generation(
            ModuleRef module,
            String modelId,
            String generationId,
            ExecutionScopeCodec.ExecutionScope scope,
            ExecutionScopeCodec.TargetRegion target,
            Optional<Geometry> parentGeometry,
            List<CandidateGeneration.SlideCandidate> candidates) {
        InferenceIdentityCodec.ModelStepIdentity producer =
                new InferenceIdentityCodec.ModelStepIdentity(module, modelId);
        InferenceIdentityCodec.BackendModelIdentity backend = backendIdentity(module, modelId);
        RasterInputCodec.ActionSourceRevision source = sourceRevision();
        SourceArtifactPublicationCodec.SourceArtifactRef artifact =
                new SourceArtifactPublicationCodec.SourceArtifactRef(
                        "33333333-3333-4333-8333-333333333333",
                        ACTION_ID,
                        hex('1'),
                        hex('2'),
                        hex('3'),
                        SourceArtifactCodec.SourceContainer.PNG);
        RasterInputCodec.SourceView sourceView =
                RasterInputCodec.buildSourceView(
                        artifact,
                        0,
                        0,
                        target.plane(),
                        new SourceIdentityCodec.IntRect(0, 0, 128, 128),
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0));
        RasterInputCodec.RasterTransform transform =
                new RasterInputCodec.RasterTransform(
                        new RasterInputCodec.RasterDescriptor(
                                128,
                                128,
                                target.plane(),
                                1.0,
                                RasterInputCodec.ResamplingType.BILINEAR,
                                RasterInputCodec.RasterPixelFormat.SRGB_UINT8),
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0));
        RasterInputCodec.RasterFrame frame =
                RasterInputCodec.buildRasterFrame(
                        source, target.key(), transform, square(0, 0, 100, 100));
        RasterInputCodec.ModelRasterInput input =
                RasterInputCodec.buildModelRasterInput(sourceView, frame, backend.rasterizer());
        ExecutionScopeCodec.ScopeAcceptance acceptance =
                new ExecutionScopeCodec.ScopeAcceptance(target, parentGeometry);
        CandidateGeneration.InputEvidence evidence =
                new CandidateGeneration.InputEvidence(
                        FiniteInferenceCodec.buildBackendInputIdentity(input),
                        ExecutionScopeCodec.scopeAcceptanceDigest(acceptance),
                        acceptance,
                        input);
        ImageDataStateCodec.GenerationStoreReservation reservation =
                ImageDataStateCodec.GenerationStoreReservation.forInput(
                        generationId,
                        ACTION_ID,
                        true,
                        DEPLOYMENT_ID,
                        new ExecutionScopeCodec.OutputKey(producer, scope),
                        0,
                        ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL,
                        input,
                        acceptance,
                        1,
                        clientBuild(),
                        backend,
                        Optional.empty());
        return new CandidateGeneration(
                reservation,
                new BackendExecutionCodec.FiniteCall("fc-fixture"),
                evidence,
                new GenerationEvidenceCodec.GenerationCounts(
                        candidates.size(), candidates.size(), 0, candidates.size()),
                candidates);
    }

    static CandidateGeneration.SlideCandidate candidate(
            String candidateId, Geometry geometry, String pathClass) {
        return new CandidateGeneration.SlideCandidate(
                candidateId, geometry, pathClass, new SourceIdentityCodec.ImagePlaneIndex(0, 0));
    }

    static ExecutionScopeCodec.TargetRegion target(ExecutionScopeCodec.RegionKey region) {
        Geometry geometry = square(0, 0, 100, 100);
        return new ExecutionScopeCodec.TargetRegion(
                region,
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
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

    private static RasterInputCodec.ActionSourceRevision sourceRevision() {
        SourceIdentityCodec.ImageSourceIdentity source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "fixture.reader",
                        hex('4'),
                        Optional.empty(),
                        128,
                        128,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1, 1));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        "fixture-image",
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        hex('5')),
                source);
    }

    private static InferenceIdentityCodec.BackendModelIdentity backendIdentity(
            ModuleRef module, String modelId) {
        BuildIdentityCodec.SoftwareBuildIdentity software = softwareBuild("fixture");
        return new InferenceIdentityCodec.BackendModelIdentity(
                new InferenceIdentityCodec.ModuleContentIdentity(
                        module, Optional.of("a".repeat(40)), hex('6'), hex('7')),
                modelId,
                "cellpose-sam",
                "A10G",
                hex('8'),
                Map.of("weights", hex('9')),
                software,
                new InferenceIdentityCodec.RasterizerIdentity(software, hex('a')),
                hex('b'));
    }

    private static BuildIdentityCodec.ClientBuildIdentity clientBuild() {
        return new BuildIdentityCodec.ClientBuildIdentity(
                softwareBuild("extension"),
                "0.6.0",
                Map.of("java", "21", "jts", "1.20.0"),
                new ExecutionLimitsCodec.ClientExecutionLimits(
                        1_000_000,
                        2_000_000,
                        3_000_000,
                        8_000_000,
                        1_000_000,
                        100_000,
                        1_000_000,
                        8L * 1024 * 1024 * 1024));
    }

    private static BuildIdentityCodec.SoftwareBuildIdentity softwareBuild(String version) {
        return new BuildIdentityCodec.SoftwareBuildIdentity(version, hex('c'), hex('d'));
    }

    private static String hex(char value) {
        return Character.toString(value).repeat(64);
    }
}

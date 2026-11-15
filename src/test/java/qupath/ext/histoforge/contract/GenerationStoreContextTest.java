package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

/** Pins contextual identity, scope, plane, and transform validation across store records. */
class GenerationStoreContextTest {

    @Test
    void parentPlaneAndTransformAreContextuallyValidated() {
        GenerationStoreCodec.GenerationStore store = GenerationStoreTestData.validStore();
        GenerationEvidenceCodec.GenerationRecord record =
                GenerationStoreTestData.generationRecord();

        GenerationEvidenceCodec.CommittedInputEvidence unexpectedParent =
                GenerationStoreTestData.copyEvidence(
                        record.inputEvidence(),
                        record.inputEvidence().sourceRevisionDigest(),
                        record.inputEvidence().targetRegionDigest(),
                        Optional.of(GenerationStoreTestData.hex('a')),
                        record.inputEvidence().sourceView(),
                        record.inputEvidence().rasterTransform());
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        record.output(),
                        unexpectedParent));

        ExecutionScopeCodec.OutputKey parentOutput =
                new ExecutionScopeCodec.OutputKey(
                        record.output().producer(),
                        new ExecutionScopeCodec.ParentScope(
                                ((ExecutionScopeCodec.FieldScope) record.output().scope()).region(),
                                GenerationStoreTestData.PARENT_ID));
        ExecutionScopeCodec.TargetRegion target = store.targetRegions().values().iterator().next();
        Geometry parentGeometry = GenerationStoreTestData.square(0, 0, 150, 150);
        GenerationEvidenceCodec.CommittedInputEvidence parentEvidence =
                new GenerationEvidenceCodec.CommittedInputEvidence(
                        record.inputEvidence().modelRasterInputDigest(),
                        record.inputEvidence().sourceRevisionDigest(),
                        record.inputEvidence().targetRegionDigest(),
                        Optional.of(CanonicalGeometryCodec.canonicalDigest(parentGeometry)),
                        record.inputEvidence().sourceView(),
                        record.inputEvidence().rasterTransform(),
                        record.inputEvidence().inclusionMaskDigest(),
                        ExecutionScopeCodec.scopeAcceptanceDigest(
                                new ExecutionScopeCodec.ScopeAcceptance(
                                        target, Optional.of(parentGeometry))),
                        record.inputEvidence().rasterFrameDigest());
        GenerationEvidenceCodec.GenerationRecord parentRecord =
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        parentOutput,
                        parentEvidence);
        GenerationStoreCodec.GenerationStore parentStore =
                new GenerationStoreCodec.GenerationStore(
                        1,
                        store.sourceRevisions(),
                        store.targetRegions(),
                        store.clientBuilds(),
                        store.backendIdentities(),
                        Map.of(parentRecord.outputGenerationId(), parentRecord));
        assertEquals(
                parentRecord,
                parentStore.currentGenerationRecords().get(parentRecord.outputGenerationId()));
        assertEquals(
                parentStore,
                GenerationStoreCodec.parseGenerationStore(
                        GenerationStoreCodec.toJson(parentStore)));

        SourceIdentityCodec.ImagePlaneIndex wrongPlane =
                new SourceIdentityCodec.ImagePlaneIndex(0, 1);
        RasterInputCodec.SourceView wrongView =
                RasterInputCodec.buildSourceView(
                        ((RasterInputCodec.SourceView) record.inputEvidence().sourceView())
                                .artifact(),
                        0,
                        0,
                        wrongPlane,
                        record.inputEvidence().sourceView().pixelRect(),
                        record.inputEvidence().sourceView().viewPixelsToSlide());
        GenerationEvidenceCodec.CommittedInputEvidence planeMismatch =
                GenerationStoreTestData.copyEvidence(
                        record.inputEvidence(),
                        record.inputEvidence().sourceRevisionDigest(),
                        record.inputEvidence().targetRegionDigest(),
                        Optional.empty(),
                        wrongView,
                        record.inputEvidence().rasterTransform());
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        record.output(),
                        planeMismatch));

        RasterInputCodec.RasterTransform wrongScale =
                new RasterInputCodec.RasterTransform(
                        record.inputEvidence().rasterTransform().raster(),
                        new SourceIdentityCodec.ScaleTranslate2D(3, 2, 10, 20));
        GenerationEvidenceCodec.CommittedInputEvidence scaleMismatch =
                GenerationStoreTestData.copyEvidence(
                        record.inputEvidence(),
                        record.inputEvidence().sourceRevisionDigest(),
                        record.inputEvidence().targetRegionDigest(),
                        Optional.empty(),
                        record.inputEvidence().sourceView(),
                        wrongScale);
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        record.output(),
                        scaleMismatch));
    }

    @Test
    void slideCoordinatesAreNotComparedToRawSourcePixelDimensions() {
        RasterInputCodec.ActionSourceRevision source = GenerationStoreTestData.sourceRevision();
        BuildIdentityCodec.ClientBuildIdentity client = GenerationStoreTestData.clientBuild();
        InferenceIdentityCodec.BackendModelIdentity backend =
                GenerationStoreTestData.backendIdentity();
        ExecutionScopeCodec.TargetRegion target =
                GenerationStoreTestData.targetRegion(
                        new ExecutionScopeCodec.Roi(GenerationStoreTestData.ROI_ID),
                        GenerationStoreTestData.square(1010, 2020, 1110, 2120));
        GenerationEvidenceCodec.GenerationRecord baseline =
                GenerationStoreTestData.generationRecord(source, target, client, backend);
        RasterInputCodec.SourceView sourceView =
                RasterInputCodec.buildSourceView(
                        ((RasterInputCodec.SourceView) baseline.inputEvidence().sourceView())
                                .artifact(),
                        0,
                        0,
                        target.plane(),
                        new SourceIdentityCodec.IntRect(0, 0, 200, 200),
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 1000, 2000));
        RasterInputCodec.RasterTransform transform =
                new RasterInputCodec.RasterTransform(
                        baseline.inputEvidence().rasterTransform().raster(),
                        new SourceIdentityCodec.ScaleTranslate2D(2, 2, 1010, 2020));
        RasterInputCodec.RasterFrame frame =
                RasterInputCodec.buildRasterFrame(
                        source,
                        target.key(),
                        transform,
                        GenerationStoreTestData.square(0, 0, 50, 50));
        RasterInputCodec.ModelRasterInput input =
                RasterInputCodec.buildModelRasterInput(sourceView, frame, backend.rasterizer());
        GenerationEvidenceCodec.CommittedInputEvidence evidence =
                new GenerationEvidenceCodec.CommittedInputEvidence(
                        input.inputDigest(),
                        RasterInputCodec.actionSourceRevisionDigest(source),
                        ExecutionScopeCodec.targetRegionDigest(target),
                        Optional.empty(),
                        sourceView,
                        transform,
                        frame.inclusionMaskDigest(),
                        ExecutionScopeCodec.scopeAcceptanceDigest(
                                new ExecutionScopeCodec.ScopeAcceptance(target, Optional.empty())),
                        frame.frameDigest());
        GenerationEvidenceCodec.GenerationRecord record =
                GenerationStoreTestData.copyRecord(
                        baseline,
                        GenerationStoreTestData.GENERATION_ID,
                        baseline.output(),
                        evidence);

        assertEquals(
                record,
                GenerationStoreTestData.store(
                                source,
                                target,
                                client,
                                backend,
                                Map.of(record.outputGenerationId(), record))
                        .currentGenerationRecords()
                        .get(record.outputGenerationId()));
    }

    @Test
    void retainedInputAndAcceptancePreimagesMustMatchTheirDigests() {
        GenerationStoreCodec.GenerationStore store = GenerationStoreTestData.validStore();
        GenerationEvidenceCodec.GenerationRecord record =
                GenerationStoreTestData.generationRecord();
        GenerationEvidenceCodec.CommittedInputEvidence evidence = record.inputEvidence();

        assertEquals(
                "c1b846963057e8e3afbe8afaf6932ad88bb6b1ef191d144116c0c87a5884a6e9",
                evidence.scopeAcceptanceDigest());

        GenerationEvidenceCodec.CommittedInputEvidence wrongModelInput =
                new GenerationEvidenceCodec.CommittedInputEvidence(
                        GenerationStoreTestData.hex('f'),
                        evidence.sourceRevisionDigest(),
                        evidence.targetRegionDigest(),
                        evidence.parentGeometryDigest(),
                        evidence.sourceView(),
                        evidence.rasterTransform(),
                        evidence.inclusionMaskDigest(),
                        evidence.scopeAcceptanceDigest(),
                        evidence.rasterFrameDigest());
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        record.output(),
                        wrongModelInput));

        RasterInputCodec.SourceView sourceView =
                (RasterInputCodec.SourceView) evidence.sourceView();
        RasterInputCodec.SourceView falseSelfDigest =
                new RasterInputCodec.SourceView(
                        sourceView.artifact(),
                        sourceView.seriesIndex(),
                        sourceView.resolutionLevel(),
                        sourceView.plane(),
                        sourceView.pixelRect(),
                        sourceView.viewPixelsToSlide(),
                        GenerationStoreTestData.hex('f'));
        GenerationEvidenceCodec.CommittedInputEvidence wrongView =
                new GenerationEvidenceCodec.CommittedInputEvidence(
                        RasterInputCodec.modelRasterInputDigest(
                                falseSelfDigest.viewDigest(),
                                evidence.rasterFrameDigest(),
                                store.backendIdentities().values().iterator().next().rasterizer()),
                        evidence.sourceRevisionDigest(),
                        evidence.targetRegionDigest(),
                        evidence.parentGeometryDigest(),
                        falseSelfDigest,
                        evidence.rasterTransform(),
                        evidence.inclusionMaskDigest(),
                        evidence.scopeAcceptanceDigest(),
                        evidence.rasterFrameDigest());
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record, GenerationStoreTestData.GENERATION_ID, record.output(), wrongView));

        GenerationEvidenceCodec.CommittedInputEvidence wrongAcceptance =
                new GenerationEvidenceCodec.CommittedInputEvidence(
                        evidence.modelRasterInputDigest(),
                        evidence.sourceRevisionDigest(),
                        evidence.targetRegionDigest(),
                        evidence.parentGeometryDigest(),
                        evidence.sourceView(),
                        evidence.rasterTransform(),
                        evidence.inclusionMaskDigest(),
                        GenerationStoreTestData.hex('f'),
                        evidence.rasterFrameDigest());
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        record.output(),
                        wrongAcceptance));
    }

    private static void assertInvalidRecord(
            GenerationStoreCodec.GenerationStore store,
            GenerationEvidenceCodec.GenerationRecord record) {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GenerationStoreCodec.GenerationStore(
                                1,
                                store.sourceRevisions(),
                                store.targetRegions(),
                                store.clientBuilds(),
                                store.backendIdentities(),
                                Map.of(record.outputGenerationId(), record)));
    }
}

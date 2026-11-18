package qupath.ext.histoforge.spatial;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.TargetSourcePlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.FieldModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.TargetWork;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.regions.RegionRequest;

/** Shared exact rounded-pyramid source and plan builders for source-writer tests. */
final class SourceArtifactWriterTestData {

    static final int LEVEL_ZERO_WIDTH = 1_199;
    static final int LEVEL_ZERO_HEIGHT = 1_649;
    static final int SELECTED_WIDTH = 600;
    static final int SELECTED_HEIGHT = 550;
    static final double SOURCE_MPP = 0.25;
    static final SourceIdentityCodec.ScaleTranslate2D LEVEL_MAPPING =
            new SourceIdentityCodec.ScaleTranslate2D(2.0, 3.0, 0.0, 0.0);
    static final BuildIdentityCodec.SoftwareBuildIdentity WRITER_BUILD =
            new BuildIdentityCodec.SoftwareBuildIdentity(
                    "test-writer", "a".repeat(64), "b".repeat(64));
    static final String ACTION_ID = "11111111-1111-4111-8111-111111111111";
    static final String ARTIFACT_ID = "22222222-2222-4222-8222-222222222222";

    private static final ModuleRef MODULE = ModuleRef.parse("histoforge/test").orElseThrow();
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final ExecutionLimitsCodec.TargetRasterLimits FINITE_LIMITS =
            new ExecutionLimitsCodec.TargetRasterLimits(2_048, 2_048, 4_194_304);
    private static final SourcePlanningLimits LIMITS =
            new SourcePlanningLimits(32_000_000, 1L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024);

    private SourceArtifactWriterTestData() {}

    static DeterministicPyramidServer sourceServer() {
        return new DeterministicPyramidServer();
    }

    static DeterministicPyramidServer sourceServerWithOneLaterTileFailure() {
        return new DeterministicPyramidServer(true);
    }

    static TargetSourcePlan derivedPlan(SourceArtifactCodec.SourceContainer container) {
        return plan(
                sourceRevision(Optional.of(new SourceIdentityCodec.S3VersionId("version-1"))),
                Set.of(container),
                Optional.empty());
    }

    static TargetSourcePlan fullDerivedPlan(SourceArtifactCodec.SourceContainer container) {
        return plan(
                sourceRevision(Optional.of(new SourceIdentityCodec.S3VersionId("version-1"))),
                Set.of(container),
                Optional.empty(),
                rectangle(0, 0, LEVEL_ZERO_WIDTH, LEVEL_ZERO_HEIGHT));
    }

    static TargetSourcePlan originalPlan(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        SourceIdentityCodec.LocalFile version =
                new SourceIdentityCodec.LocalFile(
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis(),
                        Optional.ofNullable(attributes.fileKey()).map(Object::toString));
        return plan(
                sourceRevision(Optional.of(version)),
                Set.of(SourceArtifactCodec.SourceContainer.SLIDE_FILE),
                Optional.of(
                        new EligibleOriginalSource(
                                attributes.size(),
                                SourceArtifactCodec.SourceContainer.SLIDE_FILE,
                                path.getFileName().toString(),
                                new OriginalReaderSettingsCodec.OpenSlide(
                                        List.of(
                                                new OriginalReaderSettingsCodec.Level(
                                                        0,
                                                        0,
                                                        new SourceIdentityCodec.ScaleTranslate2D(
                                                                1, 1, 0, 0)),
                                                new OriginalReaderSettingsCodec.Level(
                                                        1,
                                                        1,
                                                        new SourceIdentityCodec.ScaleTranslate2D(
                                                                2, 2, 0, 0))),
                                        0,
                                        0,
                                        List.of(255, 255, 255),
                                        64,
                                        64))),
                rectangle(17.25, 9.5, 1_099.25, 1_569.5),
                new SourcePlanningLimits(32_000_000, attributes.size(), attributes.size()));
    }

    static int expectedRgb(int level, int x, int y) {
        int value = x * 0x1f123bb5 ^ y * 0x5f356495 ^ (level + 1) * 0x6c8e9cf5;
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return 0xff000000 | (value & 0x00ffffff);
    }

    private static TargetSourcePlan plan(
            RasterInputCodec.ActionSourceRevision revision,
            Set<SourceArtifactCodec.SourceContainer> containers,
            Optional<EligibleOriginalSource> eligibleOriginal) {
        return plan(
                revision,
                containers,
                eligibleOriginal,
                rectangle(17.25, 9.5, 1_099.25, 1_569.5),
                LIMITS);
    }

    private static TargetSourcePlan plan(
            RasterInputCodec.ActionSourceRevision revision,
            Set<SourceArtifactCodec.SourceContainer> containers,
            Optional<EligibleOriginalSource> eligibleOriginal,
            Geometry geometry) {
        return plan(revision, containers, eligibleOriginal, geometry, LIMITS);
    }

    private static TargetSourcePlan plan(
            RasterInputCodec.ActionSourceRevision revision,
            Set<SourceArtifactCodec.SourceContainer> containers,
            Optional<EligibleOriginalSource> eligibleOriginal,
            Geometry geometry,
            SourcePlanningLimits limits) {
        CapturedSourcePyramid source =
                new CapturedSourcePyramid(
                        revision,
                        0,
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                        List.of(
                                new SourceLevel(
                                        0,
                                        new SourceViewGeometry.Dimensions(
                                                LEVEL_ZERO_WIDTH, LEVEL_ZERO_HEIGHT),
                                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0)),
                                new SourceLevel(
                                        1,
                                        new SourceViewGeometry.Dimensions(
                                                SELECTED_WIDTH, SELECTED_HEIGHT),
                                        eligibleOriginal.isPresent()
                                                ? new SourceIdentityCodec.ScaleTranslate2D(
                                                        2, 2, 0, 0)
                                                : LEVEL_MAPPING)));
        InferenceIdentityCodec.ModelStepIdentity producer =
                new InferenceIdentityCodec.ModelStepIdentity(MODULE, "field");
        FieldModel model =
                new FieldModel(producer, 0.75, Optional.empty(), FINITE_LIMITS, true, containers);
        ExecutionScopeCodec.TargetRegion target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Roi("33333333-3333-4333-8333-333333333333"),
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry));
        return SourceInputPlanner.plan(
                        source,
                        List.of(new TargetWork(target, List.of(model))),
                        eligibleOriginal,
                        limits)
                .targets()
                .getFirst()
                .sourcePlan()
                .orElseThrow();
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision(
            Optional<SourceIdentityCodec.SourceContentVersion> version) {
        SourceIdentityCodec.ImageSourceIdentity source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        version.orElse(null) instanceof SourceIdentityCodec.LocalFile
                                ? OriginalReaderSettingsCodec.OPENSLIDE_ADAPTER
                                : "test-rounded-pyramid",
                        "c".repeat(64),
                        version,
                        LEVEL_ZERO_WIDTH,
                        LEVEL_ZERO_HEIGHT,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(SOURCE_MPP, SOURCE_MPP));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        "test-image",
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "d".repeat(64)),
                source);
    }

    private static Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.createPolygon(
                new Coordinate[] {
                    new Coordinate(minX, minY),
                    new Coordinate(maxX, minY),
                    new Coordinate(maxX, maxY),
                    new Coordinate(minX, maxY),
                    new Coordinate(minX, minY)
                });
    }

    /** Two-level source whose actual axis mappings differ from its rounded dimension ratios. */
    static final class DeterministicPyramidServer extends AbstractTileableImageServer {

        private static final int SOURCE_TILE_SIZE = 64;
        private static final AtomicLong IDS = new AtomicLong();
        private final ImageServerMetadata metadata;
        private final String id;
        private final AtomicBoolean failLaterTileOnce;
        private final AtomicBoolean laterTileFailureObserved = new AtomicBoolean();
        private final AtomicInteger regionReads = new AtomicInteger();
        private boolean closed;

        DeterministicPyramidServer() {
            this(false);
        }

        private DeterministicPyramidServer(boolean failLaterTileOnce) {
            this.failLaterTileOnce = new AtomicBoolean(failLaterTileOnce);
            this.id = getClass().getName() + ":" + IDS.incrementAndGet();
            var levels =
                    new ImageServerMetadata.ImageResolutionLevel.Builder(
                                    LEVEL_ZERO_WIDTH, LEVEL_ZERO_HEIGHT)
                            .addLevel(1.0, LEVEL_ZERO_WIDTH, LEVEL_ZERO_HEIGHT)
                            .addLevel(2.0, SELECTED_WIDTH, SELECTED_HEIGHT)
                            .build();
            metadata =
                    new ImageServerMetadata.Builder()
                            .width(LEVEL_ZERO_WIDTH)
                            .height(LEVEL_ZERO_HEIGHT)
                            .sizeZ(1)
                            .sizeT(1)
                            .pixelType(PixelType.UINT8)
                            .channels(ImageChannel.getDefaultRGBChannels())
                            .rgb(true)
                            .pixelSizeMicrons(SOURCE_MPP, SOURCE_MPP)
                            .preferredTileSize(SOURCE_TILE_SIZE, SOURCE_TILE_SIZE)
                            .levels(levels)
                            .build();
        }

        boolean laterTileFailureObserved() {
            return laterTileFailureObserved.get();
        }

        int regionReads() {
            return regionReads.get();
        }

        boolean closed() {
            return closed;
        }

        @Override
        public BufferedImage readRegion(RegionRequest request) throws IOException {
            regionReads.incrementAndGet();
            return super.readRegion(request);
        }

        @Override
        public void close() throws Exception {
            closed = true;
            super.close();
        }

        @Override
        protected BufferedImage readTile(TileRequest request) throws IOException {
            if (request.getLevel() == 1
                    && request.getTileX() == 512
                    && request.getTileY() == 0
                    && failLaterTileOnce.compareAndSet(true, false)) {
                laterTileFailureObserved.set(true);
                throw new IOException("injected later source-tile failure");
            }
            BufferedImage image =
                    new BufferedImage(
                            request.getTileWidth(),
                            request.getTileHeight(),
                            BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < request.getTileHeight(); y++) {
                for (int x = 0; x < request.getTileWidth(); x++) {
                    image.setRGB(
                            x,
                            y,
                            expectedRgb(
                                    request.getLevel(),
                                    request.getTileX() + x,
                                    request.getTileY() + y));
                }
            }
            return image;
        }

        @Override
        public ImageServerMetadata getOriginalMetadata() {
            return metadata;
        }

        @Override
        protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
            return null;
        }

        @Override
        protected String createID() {
            return id;
        }

        @Override
        public String getServerType() {
            return "HistoForge rounded-pyramid test source";
        }

        @Override
        public Collection<URI> getURIs() {
            return List.of();
        }
    }
}

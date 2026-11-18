package qupath.ext.histoforge.spatial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.cloud.S3ImageServerBuilder;
import qupath.ext.histoforge.cloud.S3SlideImageServer;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.SourceRevisionVerifier;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.bioformats.BioFormatsImageServer;
import qupath.lib.images.servers.openslide.OpenslideImageServer;
import qupath.lib.io.GsonTools;

/** Captures the supported QuPath reader identity, exact source version, and pyramid geometry. */
public final class ImageSourceCapture {

    /** Stable behavior-policy key for a local source decoded by QuPath Bio-Formats. */
    public static final String BIOFORMATS_LOCAL_ADAPTER_ID =
            OriginalReaderSettingsCodec.BIOFORMATS_ADAPTER;

    /** Stable behavior-policy key for a local source decoded by QuPath OpenSlide 0.6. */
    public static final String OPENSLIDE_LOCAL_ADAPTER_ID =
            OriginalReaderSettingsCodec.OPENSLIDE_ADAPTER;

    /** Stable behavior-policy key for HistoForge's direct S3 TIFF reader. */
    public static final String S3_TIFF_ADAPTER_ID = "qupath.s3.generic-tiff";

    private static final String OPENSLIDE_VENDOR = "openslide.vendor";
    private static final String OPENSLIDE_LEVEL_ZERO_WIDTH = "openslide.level[0].width";
    private static final String OPENSLIDE_LEVEL_ZERO_HEIGHT = "openslide.level[0].height";
    private static final String OPENSLIDE_BACKGROUND_COLOR = "openslide.background-color";
    private static final String OPENSLIDE_BOUNDS_X = "openslide.bounds-x";
    private static final String OPENSLIDE_BOUNDS_Y = "openslide.bounds-y";
    private static final String OPENSLIDE_BOUNDS_WIDTH = "openslide.bounds-width";
    private static final String OPENSLIDE_BOUNDS_HEIGHT = "openslide.bounds-height";
    private static final Set<String> OPENSLIDE_BOUNDS_KEYS =
            Set.of(
                    OPENSLIDE_BOUNDS_X,
                    OPENSLIDE_BOUNDS_Y,
                    OPENSLIDE_BOUNDS_WIDTH,
                    OPENSLIDE_BOUNDS_HEIGHT);

    /**
     * Capture one supported source without reading model pixels.
     *
     * <p>Bio-Formats contributes its reader-provided level downsample values. The current custom S3
     * reader contributes only level zero because its additional level metadata does not yet prove a
     * reader-owned mapping independently of rounded level dimensions.
     */
    public CapturedSource capture(ImageServer<BufferedImage> source) throws IOException {
        ImageServer<BufferedImage> server = Objects.requireNonNull(source, "source");
        requireSupportedPixels(server);
        if (server instanceof OpenslideImageServer openSlide) {
            return captureOpenSlide(openSlide);
        }
        if (server instanceof BioFormatsImageServer bioFormats) {
            return captureBioFormats(bioFormats);
        }
        if (server instanceof S3SlideImageServer s3) {
            return captureS3(s3);
        }
        return captureGeneratedPyramid(server);
    }

    /**
     * Keep managed training on its previously qualified single-file OpenSlide TIFF profile. Other
     * supported views remain available through prepared pixels; broader inference-original
     * eligibility does not expand training's format or reader contract.
     */
    public static Optional<EligibleOriginalSource> eligibleTrainingOriginal(
            ImageServer<BufferedImage> server, CapturedSource captured) {
        Optional<EligibleOriginalSource> eligible = captured.eligibleOriginal();
        if (eligible.isEmpty()
                || !(server instanceof OpenslideImageServer openSlide)
                || server.nZSlices() != 1
                || server.nTimepoints() != 1) {
            return Optional.empty();
        }
        EligibleOriginalSource original = eligible.orElseThrow();
        if (original.container() != SourceContainer.SLIDE_FILE
                || !(original.readerSettings()
                        instanceof OriginalReaderSettingsCodec.OpenSlide settings)
                || !settings.backgroundRgb().equals(List.of(0, 0, 0))
                || settings.levels().stream()
                        .mapToDouble(level -> level.pixelsToLevelZero().scaleX())
                        .anyMatch(downsample -> downsample != Math.rint(downsample))) {
            return Optional.empty();
        }
        try {
            JsonObject properties =
                    JsonParser.parseString(openSlide.dumpMetadata()).getAsJsonObject();
            String background =
                    properties.has(OPENSLIDE_BACKGROUND_COLOR)
                                    && !properties.get(OPENSLIDE_BACKGROUND_COLOR).isJsonNull()
                            ? requiredString(properties, OPENSLIDE_BACKGROUND_COLOR)
                            : "000000";
            if (!"generic-tiff".equals(requiredString(properties, OPENSLIDE_VENDOR))
                    || !Set.of("000000", "#000000").contains(background)
                    || !captureOpenSlideBounds(openSlide, properties).defaultLogicalBounds()) {
                return Optional.empty();
            }
        } catch (IOException | RuntimeException error) {
            // Unproven metadata cannot qualify unchanged bytes; prepared pixels remain usable.
            return Optional.empty();
        }
        return eligible;
    }

    private static CapturedSource captureBioFormats(BioFormatsImageServer source)
            throws IOException {
        File sourceFile = source.getFile();
        if (sourceFile == null) {
            throw new IOException("Bio-Formats source is not backed by a local file");
        }
        Path file = sourceFile.toPath().toRealPath();
        int seriesIndex =
                CanonicalCodec.requireNonnegativeInt32(source.getSeries(), "series_index");
        String format = CanonicalCodec.requireNonblank(source.getFormat(), "bioformats.format");
        String readerConfiguration =
                CanonicalCodec.requireNonblank(source.getPath(), "bioformats.reader_configuration");
        SourceIdentityCodec.LocalFile version = LocalFileSourceVersion.capture(file);

        SourceIdentityCodec.ImageSourceIdentity identity =
                identity(
                        source,
                        BIOFORMATS_LOCAL_ADAPTER_ID,
                        bioFormatsDescriptorDigest(
                                file.toUri().toASCIIString(),
                                format,
                                readerConfiguration,
                                seriesIndex),
                        Optional.of(version));
        List<SourceLevel> levels = captureLevels(source);
        requireDefaultBioFormatsArguments(builderArguments(source), seriesIndex);
        return new CapturedSource(
                identity,
                seriesIndex,
                identityTransform(),
                levels,
                expected -> {
                    requireExpectedIdentity(identity, expected);
                    LocalFileSourceVersion.requireCurrent(file, version);
                },
                Optional.of(
                        new LocalSource(
                                file,
                                Optional.of(
                                        new OriginalReaderSettingsCodec.BioFormats(
                                                nativeLevels(levels))),
                                Optional.empty(),
                                Optional.empty())));
    }

    private static CapturedSource captureOpenSlide(OpenslideImageServer source) throws IOException {
        var uris = Objects.requireNonNull(source.getURIs(), "OpenSlide source URIs");
        if (uris.size() != 1) {
            throw new IOException("OpenSlide source must expose exactly one URI");
        }
        URI uri = Objects.requireNonNull(uris.iterator().next(), "OpenSlide source URI");
        final Path file;
        try {
            file = Path.of(uri).toRealPath();
        } catch (IllegalArgumentException | FileSystemNotFoundException error) {
            throw new IOException("OpenSlide source is not backed by one local file", error);
        }

        final JsonObject properties;
        try {
            properties = JsonParser.parseString(source.dumpMetadata()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("OpenSlide source returned invalid metadata", error);
        }
        String vendor = requiredString(properties, OPENSLIDE_VENDOR);
        // Multi-file families without a complete dependency rule are not admitted as single files.
        if (Set.of("dicom", "hamamatsu-vms").contains(vendor))
            throw new IOException(
                    "This OpenSlide file-set family is not yet supported by HistoForge: " + vendor);
        CapturedOpenSlideBounds bounds = captureOpenSlideBounds(source, properties);
        int seriesIndex = 0;
        SourceIdentityCodec.LocalFile version = LocalFileSourceVersion.capture(file);
        List<SourceLevel> levels = captureLevels(source);
        var settings =
                new OriginalReaderSettingsCodec.OpenSlide(
                        nativeLevels(levels),
                        bounds.logicalBounds().x(),
                        bounds.logicalBounds().y(),
                        backgroundRgb(properties),
                        source.getMetadata().getPreferredTileWidth(),
                        source.getMetadata().getPreferredTileHeight());
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty(
                "base",
                openSlideDescriptorDigest(
                        file.toUri().toASCIIString(), vendor, bounds.logicalBounds(), seriesIndex));
        descriptor.add("builder", builderJson(source));
        descriptor.add("reader_settings", OriginalReaderSettingsCodec.toJson(settings));
        SourceIdentityCodec.ImageSourceIdentity identity =
                identity(
                        source,
                        OPENSLIDE_LOCAL_ADAPTER_ID,
                        CanonicalCodec.canonicalDigest(descriptor),
                        Optional.of(version));
        var args = builderArguments(source);
        if (args.stream().anyMatch(arg -> !Set.of("--no-crop", "--icc-profile").contains(arg)))
            throw new IOException("OpenSlide settings are not captured by HistoForge");
        // An ICC subclass changes pixels. Keep it on the active reader's prepared-pixel path.
        boolean rawOriginal =
                source.getClass().equals(OpenslideImageServer.class)
                        && !args.contains("--icc-profile");
        Optional<Path> directory =
                "mirax".equals(vendor)
                        ? Optional.of(
                                file.resolveSibling(
                                        file.getFileName()
                                                .toString()
                                                .replaceFirst("(?i)\\.mrxs$", "")))
                        : Optional.empty();
        return new CapturedSource(
                identity,
                seriesIndex,
                identityTransform(),
                levels,
                expected -> {
                    requireExpectedIdentity(identity, expected);
                    LocalFileSourceVersion.requireCurrent(file, version);
                },
                Optional.of(
                        new LocalSource(
                                file,
                                rawOriginal ? Optional.of(settings) : Optional.empty(),
                                Optional.empty(),
                                directory)));
    }

    private static CapturedSource captureS3(S3SlideImageServer source) throws IOException {
        var uris = Objects.requireNonNull(source.getURIs(), "S3 source URIs");
        if (uris.size() != 1) {
            throw new IOException("S3 source must expose exactly one URI");
        }
        URI uri = Objects.requireNonNull(uris.iterator().next(), "S3 source URI");
        String bucket = CanonicalCodec.requireNonblank(uri.getHost(), "s3.bucket");
        String path = CanonicalCodec.requireNonblank(uri.getPath(), "s3.path");
        if (!S3ImageServerBuilder.SCHEME.equals(uri.getScheme()) || !path.startsWith("/")) {
            throw new IOException("invalid HistoForge S3 source URI");
        }
        String key = CanonicalCodec.requireNonblank(path.substring(1), "s3.key");
        int seriesIndex = 0;

        SourceIdentityCodec.ImageSourceIdentity identity =
                identity(
                        source,
                        S3_TIFF_ADAPTER_ID,
                        s3DescriptorDigest(bucket, key, seriesIndex),
                        Optional.of(source.getObjectSnapshot().contentVersion()));
        List<SourceLevel> levels =
                List.of(
                        new SourceLevel(
                                0,
                                new SourceViewGeometry.Dimensions(
                                        source.getWidth(), source.getHeight()),
                                identityTransform()));
        return new CapturedSource(
                identity,
                seriesIndex,
                identityTransform(),
                levels,
                expected -> {
                    requireExpectedIdentity(identity, expected);
                    source.requireCurrentSource();
                });
    }

    private static SourceIdentityCodec.ImageSourceIdentity identity(
            ImageServer<BufferedImage> source,
            String adapterId,
            String descriptorDigest,
            Optional<SourceIdentityCodec.SourceContentVersion> contentVersion) {
        var calibration = source.getPixelCalibration();
        return new SourceIdentityCodec.ImageSourceIdentity(
                adapterId,
                descriptorDigest,
                contentVersion,
                source.getWidth(),
                source.getHeight(),
                source.nZSlices(),
                source.nTimepoints(),
                new SourceIdentityCodec.PixelCalibration(
                        calibration.getPixelWidthMicrons(), calibration.getPixelHeightMicrons()));
    }

    private static void requireSupportedPixels(ImageServer<BufferedImage> source)
            throws IOException {
        if (!source.isRGB()
                || source.getPixelType() != PixelType.UINT8
                || source.nChannels() != 3) {
            throw new IOException(
                    "HistoForge source capture requires three-channel uint8 RGB pixels");
        }
    }

    static String bioFormatsDescriptorDigest(
            String fileUri, String format, String readerConfiguration, int seriesIndex) {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty(
                "file_uri", CanonicalCodec.requireNonblank(fileUri, "bioformats.file_uri"));
        descriptor.addProperty(
                "format", CanonicalCodec.requireNonblank(format, "bioformats.format"));
        descriptor.addProperty(
                "reader_configuration",
                CanonicalCodec.requireNonblank(
                        readerConfiguration, "bioformats.reader_configuration"));
        descriptor.addProperty(
                "series_index",
                CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index"));
        return CanonicalCodec.canonicalDigest(descriptor);
    }

    static String openSlideDescriptorDigest(
            String fileUri,
            String vendor,
            SourceIdentityCodec.IntRect logicalBounds,
            int seriesIndex) {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty(
                "file_uri", CanonicalCodec.requireNonblank(fileUri, "openslide.file_uri"));
        descriptor.addProperty(
                "vendor", CanonicalCodec.requireNonblank(vendor, "openslide.vendor"));
        SourceIdentityCodec.IntRect bounds = Objects.requireNonNull(logicalBounds, "logicalBounds");
        JsonObject encodedBounds = new JsonObject();
        encodedBounds.addProperty("x", bounds.x());
        encodedBounds.addProperty("y", bounds.y());
        encodedBounds.addProperty("width", bounds.width());
        encodedBounds.addProperty("height", bounds.height());
        descriptor.add("logical_bounds", encodedBounds);
        descriptor.addProperty(
                "series_index",
                CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index"));
        return CanonicalCodec.canonicalDigest(descriptor);
    }

    static String s3DescriptorDigest(String bucket, String key, int seriesIndex) {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("bucket", CanonicalCodec.requireNonblank(bucket, "s3.bucket"));
        descriptor.addProperty("key", CanonicalCodec.requireNonblank(key, "s3.key"));
        descriptor.addProperty(
                "series_index",
                CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index"));
        return CanonicalCodec.canonicalDigest(descriptor);
    }

    private static SourceIdentityCodec.ScaleTranslate2D identityTransform() {
        return new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
    }

    private static List<SourceLevel> captureLevels(ImageServer<BufferedImage> source) {
        List<SourceLevel> levels = new ArrayList<>(source.nResolutions());
        for (int index = 0; index < source.nResolutions(); index++) {
            var level = source.getMetadata().getLevel(index);
            double downsample = level.getDownsample();
            levels.add(
                    new SourceLevel(
                            index,
                            new SourceViewGeometry.Dimensions(level.getWidth(), level.getHeight()),
                            new SourceIdentityCodec.ScaleTranslate2D(
                                    downsample, downsample, 0, 0)));
        }
        return List.copyOf(levels);
    }

    private static CapturedOpenSlideBounds captureOpenSlideBounds(
            OpenslideImageServer source, JsonObject properties) throws IOException {
        int fullWidth = requiredPositiveInt(properties, OPENSLIDE_LEVEL_ZERO_WIDTH);
        int fullHeight = requiredPositiveInt(properties, OPENSLIDE_LEVEL_ZERO_HEIGHT);
        long presentBounds = OPENSLIDE_BOUNDS_KEYS.stream().filter(properties::has).count();
        SourceIdentityCodec.IntRect fullBounds =
                new SourceIdentityCodec.IntRect(0, 0, fullWidth, fullHeight);
        SourceIdentityCodec.IntRect defaultBounds = fullBounds;
        boolean defaultBoundsAdmitted = true;
        if (presentBounds == OPENSLIDE_BOUNDS_KEYS.size()) {
            try {
                int candidateX = requiredNonnegativeInt(properties, OPENSLIDE_BOUNDS_X);
                int candidateY = requiredNonnegativeInt(properties, OPENSLIDE_BOUNDS_Y);
                int candidateWidth = requiredPositiveInt(properties, OPENSLIDE_BOUNDS_WIDTH);
                int candidateHeight = requiredPositiveInt(properties, OPENSLIDE_BOUNDS_HEIGHT);
                if (candidateWidth != fullWidth && candidateHeight != fullHeight) {
                    defaultBounds =
                            new SourceIdentityCodec.IntRect(
                                    candidateX, candidateY, candidateWidth, candidateHeight);
                    defaultBoundsAdmitted =
                            (long) candidateX + candidateWidth <= fullWidth
                                    && (long) candidateY + candidateHeight <= fullHeight;
                }
            } catch (IOException ignored) {
                // QuPath 0.6 falls back to the complete level-zero image when bounds parsing fails.
            }
        }

        if (source.getWidth() == defaultBounds.width()
                && source.getHeight() == defaultBounds.height()) {
            return new CapturedOpenSlideBounds(defaultBounds, defaultBoundsAdmitted);
        }
        if (source.getWidth() == fullWidth && source.getHeight() == fullHeight) {
            return new CapturedOpenSlideBounds(fullBounds, defaultBounds.equals(fullBounds));
        }
        throw new IOException(
                "OpenSlide logical bounds do not match its captured image dimensions");
    }

    private static String requiredString(JsonObject properties, String key) throws IOException {
        if (!properties.has(key) || properties.get(key).isJsonNull()) {
            throw new IOException("OpenSlide source is missing " + key);
        }
        try {
            return CanonicalCodec.requireNonblank(properties.get(key).getAsString(), key);
        } catch (RuntimeException error) {
            throw new IOException("OpenSlide source has invalid " + key, error);
        }
    }

    private static List<Integer> backgroundRgb(JsonObject properties) {
        if (!properties.has(OPENSLIDE_BACKGROUND_COLOR)
                || properties.get(OPENSLIDE_BACKGROUND_COLOR).isJsonNull()) {
            return List.of(0, 0, 0);
        }
        final String value;
        try {
            value = requiredString(properties, OPENSLIDE_BACKGROUND_COLOR);
        } catch (IOException error) {
            return List.of(0, 0, 0);
        }
        String color = value;
        if (color.startsWith("#")) {
            color = color.substring(1);
        }
        try {
            int rgb = java.awt.Color.decode("#" + color).getRGB();
            return List.of((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
        } catch (NumberFormatException error) {
            return List.of(0, 0, 0);
        }
    }

    private static List<OriginalReaderSettingsCodec.Level> nativeLevels(List<SourceLevel> levels) {
        return levels.stream()
                .map(
                        level ->
                                new OriginalReaderSettingsCodec.Level(
                                        level.resolutionLevel(),
                                        level.resolutionLevel(),
                                        level.pixelsToLevelZero()))
                .toList();
    }

    private static List<String> builderArguments(ImageServer<BufferedImage> source)
            throws IOException {
        if (!(source.getBuilder()
                instanceof ImageServerBuilder.DefaultImageServerBuilder<?> builder))
            throw new IOException("Reader has no supported public source builder");
        return builder.getArgs() == null ? List.of() : List.of(builder.getArgs());
    }

    private static void requireDefaultBioFormatsArguments(List<String> args, int series)
            throws IOException {
        if (!args.isEmpty() && !args.equals(List.of("--series", Integer.toString(series))))
            throw new IOException(
                    "HistoForge cannot yet capture custom Bio-Formats dimension/reader settings");
    }

    private static JsonObject builderJson(ImageServer<BufferedImage> source) {
        return GsonTools.getInstance()
                .newBuilder()
                .registerTypeAdapterFactory(ImageServers.getServerBuilderFactory())
                .create()
                .toJsonTree(source.getBuilder(), ImageServerBuilder.ServerBuilder.class)
                .getAsJsonObject();
    }

    /**
     * Only the host's public generated-pyramid wrapper is supported; its resampled levels stay
     * prepared-only.
     */
    private static CapturedSource captureGeneratedPyramid(ImageServer<BufferedImage> source)
            throws IOException {
        JsonObject wrapper = builderJson(source);
        if (!wrapper.has("builderType")
                || !"pyramidize".equals(wrapper.get("builderType").getAsString())
                || !wrapper.has("builder")
                || !wrapper.get("builder").isJsonObject())
            throw new IOException("unsupported QuPath image-source adapter or wrapper");
        var underlying = wrapper.getAsJsonObject("builder");
        if (!underlying.has("builderType")
                || !"uri".equals(underlying.get("builderType").getAsString()))
            throw new IOException("generated pyramid has no supported direct reader");
        String provider =
                underlying.has("providerClassName")
                        ? underlying.get("providerClassName").getAsString()
                        : "";
        String adapter;
        if (provider.equals("qupath.lib.images.servers.bioformats.BioFormatsServerBuilder"))
            adapter = BIOFORMATS_LOCAL_ADAPTER_ID;
        else if (provider.equals("qupath.lib.images.servers.openslide.OpenslideServerBuilder"))
            adapter = OPENSLIDE_LOCAL_ADAPTER_ID;
        else throw new IOException("generated pyramid uses an unsupported reader");
        var args = new ArrayList<String>();
        if (underlying.has("args") && underlying.get("args").isJsonArray())
            underlying.getAsJsonArray("args").forEach(arg -> args.add(arg.getAsString()));
        int series = 0;
        if (adapter.equals(BIOFORMATS_LOCAL_ADAPTER_ID)) {
            if (args.size() != 2 || !args.getFirst().equals("--series"))
                throw new IOException(
                        "generated Bio-Formats pyramid must identify its resolved series");
            try {
                series = Integer.parseInt(args.get(1));
            } catch (NumberFormatException error) {
                throw new IOException("invalid generated-pyramid series", error);
            }
            requireDefaultBioFormatsArguments(args, series);
        } else if (!args.isEmpty()) {
            throw new IOException("generated OpenSlide pyramid settings are not qualified");
        }
        Path file;
        try {
            file = Path.of(URI.create(underlying.get("uri").getAsString())).toRealPath();
        } catch (RuntimeException error) {
            throw new IOException("generated pyramid is not backed by a local entry", error);
        }
        var version = LocalFileSourceVersion.capture(file);
        var identity =
                identity(
                        source,
                        adapter,
                        CanonicalCodec.canonicalDigest(wrapper),
                        Optional.of(version));
        return new CapturedSource(
                identity,
                series,
                identityTransform(),
                captureLevels(source),
                expected -> {
                    requireExpectedIdentity(identity, expected);
                    LocalFileSourceVersion.requireCurrent(file, version);
                },
                Optional.of(
                        new LocalSource(
                                file, Optional.empty(), Optional.empty(), Optional.empty())));
    }

    private static int requiredPositiveInt(JsonObject properties, String key) throws IOException {
        int value = requiredNonnegativeInt(properties, key);
        if (value == 0) {
            throw new IOException("OpenSlide source has invalid " + key);
        }
        return value;
    }

    private static int requiredNonnegativeInt(JsonObject properties, String key)
            throws IOException {
        String text = requiredString(properties, key);
        try {
            return CanonicalCodec.requireNonnegativeInt32(Integer.parseInt(text), key);
        } catch (RuntimeException error) {
            throw new IOException("OpenSlide source has invalid " + key, error);
        }
    }

    private static void requireExpectedIdentity(
            SourceIdentityCodec.ImageSourceIdentity captured,
            SourceIdentityCodec.ImageSourceIdentity expected) {
        if (!captured.equals(expected)) {
            throw new IllegalArgumentException(
                    "source revision verifier received another source identity");
        }
    }

    /** Captured logical coordinates plus whether QuPath used its default bounds policy. */
    private record CapturedOpenSlideBounds(
            // Exact level-zero bounds represented by this QuPath server's local coordinates.
            SourceIdentityCodec.IntRect logicalBounds,
            // Whether those bounds are also admitted for byte-for-byte unchanged publication.
            boolean defaultLogicalBounds) {
        private CapturedOpenSlideBounds {
            logicalBounds = Objects.requireNonNull(logicalBounds, "logicalBounds");
        }
    }

    /**
     * Reader capture retained for planning, materialization checks, and final commit validation.
     */
    public record CapturedSource(
            SourceIdentityCodec.ImageSourceIdentity identity,
            int seriesIndex,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            List<SourceLevel> levels,
            SourceRevisionVerifier revisionVerifier,
            Optional<LocalSource> localSource) {
        public CapturedSource(
                SourceIdentityCodec.ImageSourceIdentity identity,
                int seriesIndex,
                SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
                List<SourceLevel> levels,
                SourceRevisionVerifier revisionVerifier) {
            this(
                    identity,
                    seriesIndex,
                    levelZeroPixelsToSlide,
                    levels,
                    revisionVerifier,
                    Optional.empty());
        }

        public CapturedSource {
            identity = Objects.requireNonNull(identity, "identity");
            seriesIndex = CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index");
            levelZeroPixelsToSlide =
                    Objects.requireNonNull(levelZeroPixelsToSlide, "levelZeroPixelsToSlide");
            levels = List.copyOf(Objects.requireNonNull(levels, "levels"));
            revisionVerifier = Objects.requireNonNull(revisionVerifier, "revisionVerifier");
            localSource = Objects.requireNonNull(localSource, "localSource");
            if (levels.isEmpty()) {
                throw new IllegalArgumentException("captured source levels must be nonempty");
            }
        }

        /**
         * Discover dependencies once on the caller's preparation worker, before revision binding.
         */
        public CapturedSource prepare() throws IOException {
            if (localSource.isEmpty() || localSource.orElseThrow().inventory().isPresent())
                return this;
            return LocalSlideDiscovery.prepare(this);
        }

        private void requirePrepared() {
            if (localSource.isPresent() && localSource.orElseThrow().inventory().isEmpty())
                throw new IllegalStateException(
                        "local source dependencies must be prepared before binding");
        }

        /** Recheck cheap host view facts, then restat the already discovered member set. */
        public void requireSameView(CapturedSource current) throws IOException {
            requirePrepared();
            var expected = identity;
            var actual = current.identity();
            if (localSource.isEmpty() && !expected.equals(actual)
                    || !expected.sourceAdapterId().equals(actual.sourceAdapterId())
                    || !expected.credentialFreeDescriptorDigest()
                            .equals(actual.credentialFreeDescriptorDigest())
                    || expected.width() != actual.width()
                    || expected.height() != actual.height()
                    || expected.zSlices() != actual.zSlices()
                    || expected.timepoints() != actual.timepoints()
                    || !expected.calibration().equals(actual.calibration())
                    || seriesIndex != current.seriesIndex()
                    || !levels.equals(current.levels())
                    || !levelZeroPixelsToSlide.equals(current.levelZeroPixelsToSlide()))
                throw new LocalFileSourceVersion.SourceVersionChangedException(
                        "QuPath pixel-source interpretation changed during preparation or execution");
            // Object-store addressability is checked on the worker, not on the FX thread.
            if (localSource.isPresent()) requireCurrent();
        }

        /** Bind the pixel-source facts to the exact semantic ImageData revision for one action. */
        public CapturedSourcePyramid bind(RasterInputCodec.ImageRevisionToken imageDataRevision) {
            requirePrepared();
            return new CapturedSourcePyramid(
                    new RasterInputCodec.ActionSourceRevision(imageDataRevision, identity),
                    seriesIndex,
                    levelZeroPixelsToSlide,
                    levels);
        }

        /** Revalidate the captured immutable source immediately before a consequential boundary. */
        public void requireCurrent() throws IOException {
            requirePrepared();
            revisionVerifier.requireCurrent(identity);
        }

        /** Pure planning input only after matching metadata and complete local membership. */
        public Optional<SourcePlanningInputs.EligibleOriginalSource> eligibleOriginal() {
            requirePrepared();
            return localSource.flatMap(
                    local ->
                            local.settings()
                                    .map(
                                            settings -> {
                                                var files = local.inventory().orElseThrow();
                                                return new SourcePlanningInputs
                                                        .EligibleOriginalSource(
                                                        files.packaged()
                                                                ? OriginalSlideArchive.size(
                                                                        files.members())
                                                                : files.members()
                                                                        .getFirst()
                                                                        .sizeBytes(),
                                                        files.packaged()
                                                                ? qupath.ext.histoforge.contract
                                                                        .SourceArtifactCodec
                                                                        .SourceContainer
                                                                        .SLIDE_FILE_SET
                                                                : qupath.ext.histoforge.contract
                                                                        .SourceArtifactCodec
                                                                        .SourceContainer.SLIDE_FILE,
                                                        files.entryPath(),
                                                        settings);
                                            }));
        }

        /** Original entry only; a companion-file ZIP always uses a separate caller-owned path. */
        public Optional<Path> unchangedOriginalPath() {
            return eligibleOriginal().flatMap(ignored -> localSource.map(LocalSource::entry));
        }
    }

    /**
     * Local discovery inputs and, after preparation, the frozen inventory. No reader is retained.
     */
    public record LocalSource(
            Path entry,
            Optional<OriginalReaderSettingsCodec.Settings> settings,
            Optional<SlideFileInventory> inventory,
            Optional<Path> membershipDirectory) {
        public LocalSource {
            entry = entry.toAbsolutePath().normalize();
            Objects.requireNonNull(settings);
            Objects.requireNonNull(inventory);
            Objects.requireNonNull(membershipDirectory);
        }
    }
}

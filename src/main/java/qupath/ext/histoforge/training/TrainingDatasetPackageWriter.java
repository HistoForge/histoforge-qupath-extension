package qupath.ext.histoforge.training;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.DerivedRegion;
import qupath.ext.histoforge.contract.SourceArtifactCodec.OriginalWsi;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingArtifact;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetManifest;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetPreimage;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingImage;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingPackageFile;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingRoi;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingSourceEnvelope;
import qupath.ext.histoforge.contract.TrainingGeometryCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.MaterializedSourceArtifact;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceArtifactPlan;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingImageSourcePlan;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingRoiSourcePlan;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedImage;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedRoi;

/** Writes one canonical immutable training package and its reusable ZIP64 upload bytes. */
public final class TrainingDatasetPackageWriter {

    private static final int HASH_BUFFER_BYTES = 8 * 1024 * 1024;

    private TrainingDatasetPackageWriter() {}

    /** One detached image-label capture paired with its already-frozen source plan. */
    public record PlannedImage(CapturedImage capture, TrainingImageSourcePlan sourcePlan) {
        public PlannedImage {
            capture = Objects.requireNonNull(capture, "capture");
            sourcePlan = Objects.requireNonNull(sourcePlan, "sourcePlan");
            List<String> capturedIds =
                    capture.rois().stream().map(CapturedRoi::sourceRoiUuid).toList();
            List<String> plannedIds =
                    sourcePlan.rois().stream().map(TrainingRoiSourcePlan::sourceRoiUuid).toList();
            if (!capturedIds.equals(plannedIds)) {
                throw new IllegalArgumentException("captured and source-planned ROI order differs");
            }
            if (!capture.sourceRevision().equals(sourcePlan.sourceRevision())) {
                throw new IllegalArgumentException(
                        "captured and source-planned image revisions differ");
            }
            if (!capture.task().equals(sourcePlan.task())) {
                throw new IllegalArgumentException("captured and source-planned tasks differ");
            }
            for (int index = 0; index < capture.rois().size(); index++) {
                String capturedDigest =
                        CanonicalGeometryCodec.canonicalDigest(
                                capture.rois().get(index).geometry());
                if (!capturedDigest.equals(sourcePlan.rois().get(index).roiGeometryDigest())) {
                    throw new IllegalArgumentException(
                            "captured and source-planned ROI geometries differ");
                }
            }
        }
    }

    /** One open image-source session reused for every artifact from that project image. */
    public interface SourceSession extends AutoCloseable {
        MaterializedSourceArtifact materialize(TrainingRoiSourcePlan sourcePlan, Path destination)
                throws IOException;

        @Override
        void close() throws IOException;
    }

    /** Opens the current pixel source corresponding to one detached image capture. */
    @FunctionalInterface
    public interface SourceSessionFactory {
        SourceSession open(PlannedImage image) throws IOException;
    }

    /** Stable archive bytes and scientific manifest ready for export or chunk upload. */
    public record PreparedTrainingDataset(
            Path archivePath,
            long archiveBytes,
            String archiveBytesDigest,
            TrainingDatasetManifest manifest) {
        public PreparedTrainingDataset {
            archivePath =
                    Objects.requireNonNull(archivePath, "archivePath").toAbsolutePath().normalize();
            archiveBytes = CanonicalCodec.requirePositiveSafeInteger(archiveBytes, "archive_bytes");
            archiveBytesDigest =
                    CanonicalCodec.requireSha256(archiveBytesDigest, "archive_bytes_digest");
            manifest = Objects.requireNonNull(manifest, "manifest");
        }
    }

    /** Build and atomically publish one archive at a caller-selected path. */
    public static PreparedTrainingDataset write(
            Path destination,
            List<PlannedImage> images,
            TrainingApiLimits limits,
            SourceSessionFactory sourceSessions)
            throws IOException {
        Path archive =
                Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(archive.getParent(), "archive parent");
        Files.createDirectories(parent);
        TrainingApiLimits apiLimits = Objects.requireNonNull(limits, "limits");
        List<PlannedImage> ordered = canonicalImages(images);
        TrainingTask selectedTask = ordered.getFirst().capture().task();
        if (ordered.stream().anyMatch(image -> !image.capture().task().equals(selectedTask))) {
            throw new IllegalArgumentException("training images use different tasks");
        }
        TrainingProjectCapture.requireWithinLimits(
                ordered.stream().map(PlannedImage::capture).toList(), apiLimits.dataset());
        SourceSessionFactory sessions = Objects.requireNonNull(sourceSessions, "sourceSessions");

        Path root = Files.createTempDirectory(parent, ".histoforge-training-package-");
        Path incomplete = Files.createTempFile(parent, ".histoforge-training-", ".zip");
        try {
            PackageState state = writeMembers(root, selectedTask, ordered, apiLimits, sessions);
            List<TrainingPackageFile> files = state.files().stream().sorted(byPath()).toList();
            TrainingDatasetManifest manifest =
                    TrainingDatasetCodec.build(
                            new TrainingDatasetPreimage(
                                    TrainingDatasetCodec.SCHEMA_VERSION,
                                    selectedTask,
                                    state.artifacts(),
                                    state.projectGroups(),
                                    state.images(),
                                    state.rois(),
                                    files),
                            apiLimits);
            writeCanonical(
                    root,
                    "dataset.json",
                    TrainingDatasetCodec.toJson(manifest, apiLimits),
                    apiLimits.dataset().maxJsonMemberBytes());

            List<String> members = new ArrayList<>();
            members.add("dataset.json");
            files.forEach(file -> members.add(file.path()));
            TrainingDatasetArchiveWriter.write(root, members, incomplete);
            force(incomplete);
            long size = Files.size(incomplete);
            if (size > apiLimits.dataset().maxArchiveBytes()) {
                throw new IOException("training dataset archive exceeds max_archive_bytes");
            }
            String digest = sha256(incomplete);
            Files.move(
                    incomplete,
                    archive,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return new PreparedTrainingDataset(archive, size, digest, manifest);
        } finally {
            Files.deleteIfExists(incomplete);
            deleteTree(root);
        }
    }

    private static PackageState writeMembers(
            Path root,
            TrainingTask task,
            List<PlannedImage> images,
            TrainingApiLimits limits,
            SourceSessionFactory sourceSessions)
            throws IOException {
        List<String> groups =
                images.stream()
                        .flatMap(image -> image.capture().projectGroup().stream())
                        .distinct()
                        .sorted(TrainingDatasetCodec.canonicalProjectGroupOrder())
                        .toList();
        Map<String, Integer> groupIndices = new HashMap<>();
        for (int index = 0; index < groups.size(); index++) {
            groupIndices.put(groups.get(index), index);
        }

        List<TrainingImage> manifestImages = new ArrayList<>();
        List<TrainingRoi> manifestRois = new ArrayList<>();
        List<TrainingArtifact> artifacts = new ArrayList<>();
        List<TrainingPackageFile> files = new ArrayList<>();
        int roiIndex = 0;
        for (int imageIndex = 0; imageIndex < images.size(); imageIndex++) {
            PlannedImage image = images.get(imageIndex);
            OptionalInt group =
                    image.capture().projectGroup().isEmpty()
                            ? OptionalInt.empty()
                            : OptionalInt.of(
                                    groupIndices.get(image.capture().projectGroup().orElseThrow()));
            manifestImages.add(new TrainingImage(image.capture().projectEntryIdDigest(), group));

            Map<String, TrainingSourceEnvelope> envelopes;
            try (SourceSession session = sourceSessions.open(image)) {
                envelopes =
                        writeImageArtifacts(
                                root,
                                imageIndex,
                                image.sourcePlan(),
                                session,
                                artifacts,
                                files,
                                limits.dataset().maxJsonMemberBytes());
            }
            for (CapturedRoi roi : image.capture().rois()) {
                String roiPath = TrainingDatasetCodec.roiGeometryPath(roiIndex);
                String objectsPath = TrainingDatasetCodec.roiObjectsPath(roiIndex);
                files.add(
                        writeCanonical(
                                root,
                                roiPath,
                                TrainingGeometryCodec.roiFeature(roi.geometry()),
                                limits.dataset().maxJsonMemberBytes()));
                files.add(
                        writeCanonical(
                                root,
                                objectsPath,
                                TrainingGeometryCodec.objectCollection(roi.targetObjects()),
                                limits.dataset().maxJsonMemberBytes()));
                manifestRois.add(
                        new TrainingRoi(
                                roi.sourceRoiUuid(),
                                imageIndex,
                                Objects.requireNonNull(
                                        envelopes.get(roi.sourceRoiUuid()), "source envelope")));
                roiIndex++;
            }
        }
        return new PackageState(groups, manifestImages, manifestRois, artifacts, files);
    }

    private static Map<String, TrainingSourceEnvelope> writeImageArtifacts(
            Path root,
            int imageIndex,
            TrainingImageSourcePlan sourcePlan,
            SourceSession session,
            List<TrainingArtifact> artifacts,
            List<TrainingPackageFile> files,
            long maxJsonMemberBytes)
            throws IOException {
        Map<String, TrainingSourceEnvelope> envelopes = new LinkedHashMap<>();
        if (sourcePlan.usesOriginal()) {
            TrainingRoiSourcePlan representative = sourcePlan.rois().getFirst();
            int artifactIndex = artifacts.size();
            MaterializedSourceArtifact materialized =
                    materialize(root, artifactIndex, "original", representative, session);
            requireOrigin(materialized, "original", sourcePlan, representative);
            addArtifactFiles(
                    root, artifactIndex, "original", materialized, files, maxJsonMemberBytes);
            artifacts.add(new TrainingArtifact(imageIndex, "original"));
            for (TrainingRoiSourcePlan roi : sourcePlan.rois()) {
                if (!roi.artifact().equals(representative.artifact())) {
                    throw new IllegalArgumentException("original source plan differs within image");
                }
                envelopes.put(
                        roi.sourceRoiUuid(),
                        new TrainingSourceEnvelope(
                                artifactIndex,
                                roi.sourceLevel().seriesIndex(),
                                roi.sourceLevel().resolutionLevel(),
                                roi.sourceEnvelopeAtLevel()));
            }
            return Map.copyOf(envelopes);
        }

        for (TrainingRoiSourcePlan roi : sourcePlan.rois()) {
            DerivedArtifactPlan derived = (DerivedArtifactPlan) roi.artifact();
            String representation =
                    derived.container() == SourceContainer.PNG ? "png" : "ome_bigtiff";
            int artifactIndex = artifacts.size();
            MaterializedSourceArtifact materialized =
                    materialize(root, artifactIndex, representation, roi, session);
            requireOrigin(materialized, representation, sourcePlan, roi);
            addArtifactFiles(
                    root, artifactIndex, representation, materialized, files, maxJsonMemberBytes);
            artifacts.add(new TrainingArtifact(imageIndex, representation));
            envelopes.put(
                    roi.sourceRoiUuid(),
                    new TrainingSourceEnvelope(
                            artifactIndex,
                            0,
                            0,
                            new SourceIdentityCodec.IntRect(
                                    0,
                                    0,
                                    derived.sourceRectAtLevel().width(),
                                    derived.sourceRectAtLevel().height())));
        }
        return Map.copyOf(envelopes);
    }

    private static MaterializedSourceArtifact materialize(
            Path root,
            int artifactIndex,
            String representation,
            TrainingRoiSourcePlan sourcePlan,
            SourceSession session)
            throws IOException {
        Path destination =
                root.resolve(
                        TrainingDatasetCodec.artifactSourcePath(artifactIndex, representation));
        Files.createDirectories(destination.getParent());
        MaterializedSourceArtifact result = session.materialize(sourcePlan, destination);
        if (!result.path().equals(destination.toAbsolutePath().normalize())
                || !Files.isRegularFile(destination)
                || Files.size(destination) != result.sizeBytes()
                || !sha256(destination).equals(result.sha256())) {
            throw new IOException("source materializer did not publish its declared exact bytes");
        }
        return result;
    }

    private static void requireOrigin(
            MaterializedSourceArtifact artifact,
            String representation,
            TrainingImageSourcePlan imagePlan,
            TrainingRoiSourcePlan roiPlan) {
        boolean original = "original".equals(representation);
        SourceContainer expectedContainer =
                switch (representation) {
                    case "png" -> SourceContainer.PNG;
                    case "ome_bigtiff" -> SourceContainer.TIFF_FAMILY;
                    case "original" -> SourceContainer.SLIDE_FILE;
                    default ->
                            throw new IllegalArgumentException(
                                    "unsupported training representation");
                };
        if (original != (artifact.origin() instanceof OriginalWsi)
                || !original && !(artifact.origin() instanceof DerivedRegion)
                || artifact.container() != expectedContainer) {
            throw new IllegalArgumentException(
                    "materialized source does not match its training representation");
        }
        SourceArtifactPlan planned = roiPlan.artifact();
        if (planned instanceof OriginalArtifactPlan expected) {
            OriginalWsi actual = (OriginalWsi) artifact.origin();
            if (artifact.sizeBytes() != expected.totalBytes()
                    || !actual.source().equals(imagePlan.sourceRevision().pixelSource())
                    || actual.pyramidSeriesIndex() != expected.pyramidSeriesIndex()
                    || !actual.levelZeroPixelsToSlide().equals(expected.levelZeroPixelsToSlide())
                    || !actual.entryPath().equals(expected.entryPath())
                    || !actual.readerSettings().equals(expected.readerSettings())
                    || !actual.members().isEmpty()
                    || !artifact.requiredLevels().equals(expected.requiredLevels())) {
                throw new IllegalArgumentException(
                        "materialized original source differs from its frozen plan");
            }
            return;
        }
        DerivedArtifactPlan expected = (DerivedArtifactPlan) planned;
        DerivedRegion actual = (DerivedRegion) artifact.origin();
        List<SourceArtifactCodec.SourceLevelRequirement> required =
                List.of(
                        new SourceArtifactCodec.SourceLevelRequirement(
                                0,
                                0,
                                expected.sourceRectAtLevel().width(),
                                expected.sourceRectAtLevel().height()));
        if (!actual.source().equals(imagePlan.sourceRevision().pixelSource())
                || actual.sourceResolutionLevel() != expected.sourceResolutionLevel()
                || !actual.plane().equals(expected.plane())
                || !actual.sourceRectAtLevel().equals(expected.sourceRectAtLevel())
                || !actual.artifactPixelsToSlide().equals(expected.artifactPixelsToSlide())
                || !artifact.requiredLevels().equals(required)) {
            throw new IllegalArgumentException(
                    "materialized derived source differs from its frozen plan");
        }
    }

    private static void addArtifactFiles(
            Path root,
            int artifactIndex,
            String representation,
            MaterializedSourceArtifact materialized,
            List<TrainingPackageFile> files,
            long maxJsonMemberBytes)
            throws IOException {
        files.add(
                writeCanonical(
                        root,
                        TrainingDatasetCodec.artifactOriginPath(artifactIndex),
                        SourceArtifactCodec.toJson(materialized.origin()),
                        maxJsonMemberBytes));
        files.add(
                new TrainingPackageFile(
                        TrainingDatasetCodec.artifactSourcePath(artifactIndex, representation),
                        materialized.sizeBytes(),
                        materialized.sha256()));
    }

    private static TrainingPackageFile writeCanonical(
            Path root, String relativePath, JsonObject value, long maximumBytes)
            throws IOException {
        Path packageRoot = root.toAbsolutePath().normalize();
        Path path = packageRoot.resolve(relativePath).normalize();
        if (!path.startsWith(packageRoot)) {
            throw new IllegalArgumentException("training package member escapes its root");
        }
        byte[] encoded = CanonicalCodec.canonicalBytes(value);
        if (encoded.length > maximumBytes) {
            throw new IllegalArgumentException(
                    "training package JSON exceeds max_json_member_bytes");
        }
        Files.createDirectories(path.getParent());
        Files.write(path, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new TrainingPackageFile(
                relativePath, encoded.length, CanonicalCodec.sha256(encoded));
    }

    private static List<PlannedImage> canonicalImages(List<PlannedImage> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("training package requires project images");
        }
        List<PlannedImage> values = new ArrayList<>(images);
        values.forEach(image -> Objects.requireNonNull(image, "planned image"));
        values.sort(
                java.util.Comparator.comparing(image -> image.capture().projectEntryIdDigest()));
        Set<String> digests = new java.util.HashSet<>();
        for (PlannedImage image : values) {
            if (!digests.add(image.capture().projectEntryIdDigest())) {
                throw new IllegalArgumentException("project image digests must be unique");
            }
        }
        return List.copyOf(values);
    }

    private static java.util.Comparator<TrainingPackageFile> byPath() {
        return java.util.Comparator.comparing(TrainingPackageFile::path);
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record PackageState(
            List<String> projectGroups,
            List<TrainingImage> images,
            List<TrainingRoi> rois,
            List<TrainingArtifact> artifacts,
            List<TrainingPackageFile> files) {
        private PackageState {
            projectGroups = List.copyOf(projectGroups);
            images = List.copyOf(images);
            rois = List.copyOf(rois);
            artifacts = List.copyOf(artifacts);
            files = List.copyOf(files);
        }
    }
}

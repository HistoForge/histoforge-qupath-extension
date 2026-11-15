package qupath.ext.histoforge.spatial;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.ImageSourceCapture.CapturedSource;
import qupath.ext.histoforge.spatial.ImageSourceCapture.LocalSource;
import qupath.lib.images.servers.openslide.jna.OpenSlideLoader;

/** Background-only discovery using the captured reader; no image server or decoder escapes. */
final class LocalSlideDiscovery {
    private LocalSlideDiscovery() {}

    static CapturedSource prepare(CapturedSource captured) throws IOException {
        if (Platform.isFxApplicationThread())
            throw new IllegalStateException(
                    "slide dependency discovery must run off the FX thread");
        var local = captured.localSource().orElseThrow();
        captured.revisionVerifier().requireCurrent(captured.identity());
        List<Path> dependencies = new ArrayList<>();
        dependencies.add(local.entry());
        Optional<Path> membershipDirectory = local.membershipDirectory();
        boolean originalPixels = true;
        if (captured.identity()
                .sourceAdapterId()
                .equals(ImageSourceCapture.BIOFORMATS_LOCAL_ADAPTER_ID)) {
            try (var reader = new ImageReader()) {
                reader.setFlattenedResolutions(false);
                reader.setMetadataStore(MetadataTools.createOMEXMLMetadata());
                reader.setId(local.entry().toString());
                if (captured.seriesIndex() >= reader.getSeriesCount())
                    throw new IOException("captured Bio-Formats series is unavailable");
                reader.setSeries(captured.seriesIndex());
                var used = reader.getUsedFiles(false);
                if (used != null) for (String file : used) dependencies.add(Path.of(file));
                originalPixels =
                        reader.isRGB()
                                && reader.getPixelType() == FormatTools.UINT8
                                && (reader.getRGBChannelCount() == 3
                                        || reader.getRGBChannelCount() == 4)
                                && reader.getEffectiveSizeC() == 1
                                && reader.getSizeX() == captured.identity().width()
                                && reader.getSizeY() == captured.identity().height()
                                && reader.getSizeZ() == captured.identity().zSlices()
                                && reader.getSizeT() == captured.identity().timepoints()
                                && reader.getResolutionCount() == captured.levels().size();
                if (originalPixels) {
                    for (var level : captured.levels()) {
                        reader.setResolution(level.resolutionLevel());
                        if (reader.getSizeX() != level.dimensions().width()
                                || reader.getSizeY() != level.dimensions().height())
                            originalPixels = false;
                    }
                }
            } catch (FormatException error) {
                throw new IOException(
                        "Bio-Formats could not discover the captured slide dependencies", error);
            }
        } else if (captured.identity()
                .sourceAdapterId()
                .equals(ImageSourceCapture.OPENSLIDE_LOCAL_ADAPTER_ID)) {
            // Generated-pyramid wrappers expose the entry but not vendor properties. Inspect
            // with the same native reader off FX so prepared delivery has complete identity too.
            try (var reader = OpenSlideLoader.openImage(local.entry().toString())) {
                String vendor = reader.getProperties().get("openslide.vendor");
                if (java.util.Set.of("dicom", "hamamatsu-vms").contains(vendor))
                    throw new IOException("Unsupported OpenSlide companion-file family: " + vendor);
                if ("mirax".equals(vendor))
                    membershipDirectory =
                            Optional.of(
                                    local.entry()
                                            .resolveSibling(
                                                    local.entry()
                                                            .getFileName()
                                                            .toString()
                                                            .replaceFirst("(?i)\\.mrxs$", "")));
            }
        }
        var inventory =
                SlideFileInventory.capture(local.entry(), dependencies, membershipDirectory);
        captured.revisionVerifier().requireCurrent(captured.identity());
        inventory.requireCurrent();
        var before = captured.identity();
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        before.sourceAdapterId(),
                        before.credentialFreeDescriptorDigest(),
                        Optional.of(inventory.contentVersion()),
                        before.width(),
                        before.height(),
                        before.zSlices(),
                        before.timepoints(),
                        before.calibration());
        return new CapturedSource(
                identity,
                captured.seriesIndex(),
                captured.levelZeroPixelsToSlide(),
                captured.levels(),
                expected -> {
                    if (!identity.equals(expected))
                        throw new IllegalArgumentException(
                                "source verifier received another identity");
                    inventory.requireCurrent();
                },
                Optional.of(
                        new LocalSource(
                                local.entry(),
                                originalPixels ? local.settings() : Optional.empty(),
                                Optional.of(inventory),
                                membershipDirectory)));
    }
}

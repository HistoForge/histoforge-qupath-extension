package qupath.ext.histoforge.spatial;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;

/** Valid original-source records for tests that do not open a slide decoder. */
public final class OriginalSourceTestData {

    private OriginalSourceTestData() {}

    public static OriginalReaderSettingsCodec.Settings readerSettings(
            SourceIdentityCodec.ImageSourceIdentity source, double... downsamples) {
        var levels = new ArrayList<OriginalReaderSettingsCodec.Level>();
        for (int index = 0; index < downsamples.length; index++) {
            levels.add(
                    new OriginalReaderSettingsCodec.Level(
                            index,
                            index,
                            new SourceIdentityCodec.ScaleTranslate2D(
                                    downsamples[index], downsamples[index], 0, 0)));
        }
        return readerSettings(source.sourceAdapterId(), levels);
    }

    public static OriginalReaderSettingsCodec.Settings readerSettings(
            CapturedSourcePyramid source) {
        return readerSettings(
                source.sourceRevision().pixelSource().sourceAdapterId(),
                source.levels().stream()
                        .map(
                                level ->
                                        new OriginalReaderSettingsCodec.Level(
                                                level.resolutionLevel(),
                                                level.resolutionLevel(),
                                                level.pixelsToLevelZero()))
                        .toList());
    }

    private static OriginalReaderSettingsCodec.Settings readerSettings(
            String adapter, List<OriginalReaderSettingsCodec.Level> levels) {
        return switch (adapter) {
            case OriginalReaderSettingsCodec.OPENSLIDE_ADAPTER ->
                    new OriginalReaderSettingsCodec.OpenSlide(
                            levels, 0, 0, List.of(0, 0, 0), 256, 256);
            case OriginalReaderSettingsCodec.BIOFORMATS_ADAPTER ->
                    new OriginalReaderSettingsCodec.BioFormats(levels);
            default ->
                    throw new IllegalArgumentException(
                            "test original requires a known reader: " + adapter);
        };
    }

    public static EligibleOriginalSource eligible(long totalBytes, CapturedSourcePyramid source) {
        return new EligibleOriginalSource(
                totalBytes, SourceContainer.SLIDE_FILE, "slide.tif", readerSettings(source));
    }

    public static ImageSourceCapture.LocalSource localSource(
            Path entry, OriginalReaderSettingsCodec.Settings settings) throws IOException {
        return new ImageSourceCapture.LocalSource(
                entry,
                Optional.of(settings),
                Optional.of(SlideFileInventory.capture(entry, List.of(entry), Optional.empty())),
                Optional.empty());
    }
}

package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceIdentityCodec.ScaleTranslate2D;
import qupath.ext.histoforge.spatial.ImageSourceCapture.CapturedSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.lib.images.servers.bioformats.BioFormatsImageServer;
import qupath.lib.images.servers.openslide.OpenslideImageServer;

class TrainingOriginalSourceEligibilityTest {

    @Test
    void acceptsTheExistingGenericTiffProfileWithoutUsingItsFilename() {
        var server = server(properties());
        var original = original(SourceContainer.SLIDE_FILE, settings(2));
        assertEquals(
                Optional.of(original),
                ImageSourceCapture.eligibleTrainingOriginal(server, captured(original)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aperio", "hamamatsu", "mirax"})
    void rejectsOtherOpenSlideVendors(String vendor) {
        var properties = properties();
        properties.addProperty("openslide.vendor", vendor);
        assertTrue(
                ImageSourceCapture.eligibleTrainingOriginal(
                                server(properties),
                                captured(original(SourceContainer.SLIDE_FILE, settings(2))))
                        .isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ffffff", "invalid", "0", ""})
    void preservesTheOldBlackBackgroundQualification(String background) {
        var properties = properties();
        properties.addProperty("openslide.background-color", background);
        assertTrue(
                ImageSourceCapture.eligibleTrainingOriginal(
                                server(properties),
                                captured(original(SourceContainer.SLIDE_FILE, settings(2))))
                        .isEmpty());
    }

    @Test
    void rejectsNondefaultBoundsButAcceptsTheSameDefaultCrop() {
        var properties = properties();
        properties.addProperty("openslide.bounds-x", "10");
        properties.addProperty("openslide.bounds-y", "10");
        properties.addProperty("openslide.bounds-width", "50");
        properties.addProperty("openslide.bounds-height", "40");
        var server = server(properties);
        var original = original(SourceContainer.SLIDE_FILE, settings(2));
        assertTrue(
                ImageSourceCapture.eligibleTrainingOriginal(server, captured(original)).isEmpty());

        when(server.getWidth()).thenReturn(50);
        when(server.getHeight()).thenReturn(40);
        var cropped =
                new OriginalReaderSettingsCodec.OpenSlide(
                        settings(2).levels(), 10, 10, List.of(0, 0, 0), 256, 256);
        var defaultCrop = original(SourceContainer.SLIDE_FILE, cropped);
        assertEquals(
                Optional.of(defaultCrop),
                ImageSourceCapture.eligibleTrainingOriginal(server, captured(defaultCrop)));
    }

    @Test
    void rejectsCompanionsBioFormatsIrregularLevelsAndExtraPlanes() {
        var server = server(properties());
        assertTrue(
                ImageSourceCapture.eligibleTrainingOriginal(
                                server,
                                captured(original(SourceContainer.SLIDE_FILE_SET, settings(2))))
                        .isEmpty());
        assertTrue(
                ImageSourceCapture.eligibleTrainingOriginal(
                                server,
                                captured(original(SourceContainer.SLIDE_FILE, settings(1.5))))
                        .isEmpty());
        var bioFormats =
                original(
                        SourceContainer.SLIDE_FILE,
                        new OriginalReaderSettingsCodec.BioFormats(settings(2).levels()));
        assertTrue(
                ImageSourceCapture.eligibleTrainingOriginal(
                                mock(BioFormatsImageServer.class), captured(bioFormats))
                        .isEmpty());
        var qualified = captured(original(SourceContainer.SLIDE_FILE, settings(2)));
        when(server.nZSlices()).thenReturn(2);
        assertTrue(ImageSourceCapture.eligibleTrainingOriginal(server, qualified).isEmpty());
        when(server.nZSlices()).thenReturn(1);
        when(server.nTimepoints()).thenReturn(2);
        assertTrue(ImageSourceCapture.eligibleTrainingOriginal(server, qualified).isEmpty());
    }

    private static CapturedSource captured(EligibleOriginalSource original) {
        var captured = mock(CapturedSource.class);
        when(captured.eligibleOriginal()).thenReturn(Optional.of(original));
        return captured;
    }

    private static EligibleOriginalSource original(
            SourceContainer container, OriginalReaderSettingsCodec.Settings settings) {
        return new EligibleOriginalSource(1_000, container, "specimen.dat", settings);
    }

    private static OriginalReaderSettingsCodec.OpenSlide settings(double downsample) {
        return new OriginalReaderSettingsCodec.OpenSlide(
                List.of(
                        new OriginalReaderSettingsCodec.Level(
                                0, 0, new ScaleTranslate2D(1, 1, 0, 0)),
                        new OriginalReaderSettingsCodec.Level(
                                1, 1, new ScaleTranslate2D(downsample, downsample, 0, 0))),
                0,
                0,
                List.of(0, 0, 0),
                256,
                256);
    }

    private static OpenslideImageServer server(JsonObject properties) {
        var server = mock(OpenslideImageServer.class);
        when(server.nZSlices()).thenReturn(1);
        when(server.nTimepoints()).thenReturn(1);
        when(server.getWidth()).thenReturn(100);
        when(server.getHeight()).thenReturn(80);
        when(server.dumpMetadata()).thenReturn(properties.toString());
        return server;
    }

    private static JsonObject properties() {
        var properties = new JsonObject();
        properties.addProperty("openslide.vendor", "generic-tiff");
        properties.addProperty("openslide.level[0].width", "100");
        properties.addProperty("openslide.level[0].height", "80");
        return properties;
    }
}

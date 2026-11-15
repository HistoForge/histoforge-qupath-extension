package qupath.ext.histoforge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelDisplayTest {
    @Test
    void namesCuratedArchitecturesWithoutInferringTheirTask() {
        assertEquals(
                "DINOv3 + Cellpose head", ModelDisplay.architecture("dinov3-adapter-cellpose"));
        assertEquals(
                "DINOv3 + HoVer-Net head", ModelDisplay.architecture("dinov3-adapter-hovernet"));
        assertEquals(
                "DINOv3 + classification head",
                ModelDisplay.architecture("dinov3-adapter-semantic"));
        assertTrue(
                ModelDisplay.architectureHelp("dinov3-adapter-semantic")
                        .contains("does not identify separate objects"));
    }

    @Test
    void namesAllExplicitOutputContracts() {
        assertEquals("Instance segmentation", ModelDisplay.task("instance_segmentation"));
        assertEquals(
                "Instance segmentation + classification",
                ModelDisplay.task("instance_segmentation_and_classification"));
        assertEquals(
                "Pixel classification (semantic segmentation)",
                ModelDisplay.task("dense_classification"));
        assertEquals("Region classification", ModelDisplay.task("region_classification"));
    }

    @Test
    void retainsExactUnknownIdentifiersRatherThanGuessingABackboneOrTask() {
        assertEquals("future-encoder-head", ModelDisplay.architecture("future-encoder-head"));
        assertEquals("future_output", ModelDisplay.task("future_output"));
        for (String arch :
                new String[] {
                    "dinov3-adapter-cellpose",
                    "dinov3-adapter-hovernet",
                    "dinov3-adapter-semantic",
                    "future-encoder-head"
                }) {
            assertTrue(ModelDisplay.architectureHelp(arch).endsWith("Architecture ID: " + arch));
        }
    }
}

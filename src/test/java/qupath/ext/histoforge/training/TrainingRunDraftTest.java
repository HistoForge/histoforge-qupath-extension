package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAugmentationSelection;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingSettings;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.training.TrainingRunDraft.ResultForm;

class TrainingRunDraftTest {
    @Test
    void offersOnlyExecutableResultForms() throws Exception {
        var cap = TrainingFormFixtures.capability(false);
        assertEquals(
                List.of(ResultForm.COMPLETE_WORKFLOW, ResultForm.STANDALONE),
                TrainingRunDraft.resultForms(
                        TrainingFormFixtures.module(cap, ignored -> {}, true), "cells"));
        assertEquals(
                List.of(ResultForm.STANDALONE),
                TrainingRunDraft.resultForms(
                        TrainingFormFixtures.module(cap, ignored -> {}, false), "cells"));
        var single =
                TrainingFormFixtures.module(
                        cap,
                        recipe -> {
                            recipe.getAsJsonObject("models").remove("future");
                            recipe.add("steps", JsonParser.parseString("[{\"model\":\"cells\"}]"));
                        },
                        true);
        assertEquals(List.of(ResultForm.STANDALONE), TrainingRunDraft.resultForms(single, "cells"));
        var crop =
                TrainingFormFixtures.module(
                        cap,
                        recipe ->
                                recipe.getAsJsonObject("models")
                                        .getAsJsonObject("cells")
                                        .getAsJsonObject("input")
                                        .addProperty("type", "crop"),
                        false);
        assertEquals(List.of(), TrainingRunDraft.resultForms(crop, "cells"));
    }

    @Test
    void rejectsAnUnusedModelAndInvalidChoicesBeforeUpload() throws Exception {
        var cap = TrainingFormFixtures.capability(false);
        var unused =
                TrainingFormFixtures.module(
                        cap, recipe -> recipe.add("steps", new JsonArray()), true);
        assertThrows(
                IllegalArgumentException.class, () -> TrainingRunDraft.options(unused, "cells"));
        var source = TrainingFormFixtures.module(cap, ignored -> {}, true);
        for (String name : List.of("", "bad\nname", "x".repeat(4097))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> draft(source, name, cap.defaultTrainingSettings()));
        }
        for (var settings :
                List.of(
                        new TrainingSettings("unknown", 1, 2, 1, 80, "auto", 10, "pixel", null),
                        new TrainingSettings("l4", 9, 2, 1, 80, "auto", 10, "pixel", null),
                        new TrainingSettings("l4", 1, 9, 1, 80, "auto", 10, "pixel", null),
                        new TrainingSettings("l4", 1, 2, 17, 80, "auto", 10, "pixel", null),
                        new TrainingSettings("l4", 1, 2, 1, 0, "auto", 0, "pixel", null),
                        new TrainingSettings("l4", 1, 2, 1, 1001, "auto", 10, "pixel", null),
                        new TrainingSettings("l4", 1, 2, 1, 80, "auto", 9, "pixel", null),
                        new TrainingSettings("l4", 1, 2, 1, 80, "custom", 80, "pixel", null))) {
            assertThrows(IllegalArgumentException.class, () -> draft(source, "Result", settings));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TrainingRunDraft(
                                source,
                                "cells",
                                TrainingFormFixtures.sourceTask(source, "cells"),
                                Initialization.USE,
                                ResultForm.COMPLETE_WORKFLOW,
                                "Result",
                                "A100",
                                cap.defaultFieldProfile(),
                                15,
                                new TrainingAugmentationSelection(
                                        "he-v1", List.of("flip-v1", "rotate90-v1")),
                                cap.defaultTrainingSettings()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TrainingRunDraft(
                                source,
                                "cells",
                                TrainingFormFixtures.sourceTask(source, "cells"),
                                Initialization.USE,
                                ResultForm.COMPLETE_WORKFLOW,
                                "Result",
                                "T4",
                                cap.defaultFieldProfile(),
                                15,
                                new TrainingAugmentationSelection(
                                        "he-v1", List.of("rotate90-v1", "flip-v1")),
                                cap.defaultTrainingSettings()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TrainingRunDraft(
                                source,
                                "cells",
                                TrainingFormFixtures.sourceTask(source, "cells"),
                                Initialization.USE,
                                ResultForm.COMPLETE_WORKFLOW,
                                "Result",
                                "A100",
                                cap.defaultFieldProfile(),
                                -1,
                                new TrainingAugmentationSelection("he-v1", List.of()),
                                cap.defaultTrainingSettings()));
    }

    @Test
    void acceptsChangedVariableSizesButNotFrozenScientificSettings() throws Exception {
        var cap = TrainingFormFixtures.capability(true);
        var source = TrainingFormFixtures.module(cap, ignored -> {}, true);
        var profile =
                TrainingFieldSizes.resolve(
                        cap, 0.5, TrainingFieldSizes.Unit.MODEL_PIXELS, "256", "512", "384", false);
        new TrainingRunDraft(
                source,
                "cells",
                TrainingFormFixtures.sourceTask(source, "cells"),
                Initialization.USE,
                ResultForm.STANDALONE,
                "Result",
                "A100",
                profile,
                0,
                new TrainingAugmentationSelection("he-v1", List.of()),
                cap.defaultTrainingSettings());
        var encoded = TrainingTaskCodec.toJson(profile);
        encoded.addProperty("tileOverlap", 0.5);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TrainingRunDraft(
                                source,
                                "cells",
                                TrainingFormFixtures.sourceTask(source, "cells"),
                                Initialization.USE,
                                ResultForm.STANDALONE,
                                "Result",
                                "A100",
                                TrainingTaskCodec.parseFieldProfile(encoded),
                                15,
                                new TrainingAugmentationSelection("he-v1", List.of()),
                                cap.defaultTrainingSettings()));
    }

    @Test
    void bindsSealedIdentityAndExactEditedSplitWithoutReadingTheFormAgain() throws Exception {
        var fixture = TrainingFormFixtures.fixture("training-datasets-v1.json");
        var cap = TrainingFormFixtures.capability(false);
        var source = TrainingFormFixtures.module(cap, ignored -> {}, true);
        var limits = source.gateway().trainingApiLimits();
        var manifest =
                TrainingDatasetCodec.parseManifest(
                        fixture.getAsJsonObject("manifest").get("value"), limits);
        var draft =
                new TrainingRunDraft(
                        source,
                        "cells",
                        manifest.task(),
                        Initialization.USE,
                        ResultForm.COMPLETE_WORKFLOW,
                        "Result",
                        "A100",
                        cap.defaultFieldProfile(),
                        15,
                        new TrainingAugmentationSelection(
                                "he-v1", List.of("rotate90-v1", "flip-v1")),
                        cap.defaultTrainingSettings());
        var record =
                TrainingDatasetTransferCodec.parseDatasetRecord(
                        fixture.getAsJsonObject("retained").get("value"), limits);
        var example =
                TrainingRunRequestCodec.parse(
                        fixture.getAsJsonObject("runRequest").get("value"), limits);
        var assignment = mock(TrainingAssignment.class);
        when(assignment.requirePlan()).thenReturn(example.splitPlan());
        var request =
                draft.request(
                        new TrainingDatasetWorkflow.SealedDataset(record, manifest, assignment),
                        73);
        assertEquals(source.descriptor().identity(), request.sourceModule());
        assertEquals(record.datasetId(), request.datasetId());
        assertEquals(manifest.datasetDigest(), request.datasetDigest());
        assertEquals(example.splitPlan(), request.splitPlan());
        assertEquals("A100", request.resultInferenceGpu());
        assertEquals(15, request.minInstancePixels());
        assertEquals(73, request.trainingSeed());
        assertEquals(
                request,
                TrainingRunRequestCodec.parse(
                        TrainingRunRequestCodec.toJson(request, limits), limits));

        // A valid but different target must not be submitted with an older sealed capture.
        for (String changed : List.of("annotation_class_map", "model_microns_per_pixel")) {
            var target = TrainingTaskCodec.toJson(manifest.task());
            if (changed.equals("annotation_class_map")) {
                target.add(changed, new JsonObject());
            } else {
                target.addProperty(changed, manifest.task().modelMicronsPerPixel() * 2);
            }
            var edited =
                    new TrainingRunDraft(
                            draft.source(),
                            draft.modelId(),
                            TrainingTaskCodec.parseTask(target),
                            draft.classInitialization(),
                            draft.resultForm(),
                            draft.resultName(),
                            draft.resultInferenceGpu(),
                            draft.fieldProfile(),
                            draft.minInstancePixels(),
                            draft.augmentation(),
                            draft.settings());
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    edited.request(
                                            new TrainingDatasetWorkflow.SealedDataset(
                                                    record, manifest, assignment),
                                            73));
            assertEquals(
                    "Captured data differs from the frozen training target.", failure.getMessage());
        }
    }

    @Test
    void readsTheRecipeDefaultAndAllowsZeroWithoutFilteringTrainingTargets() throws Exception {
        var cap = TrainingFormFixtures.capability(false);
        var source = TrainingFormFixtures.module(cap, ignored -> {}, true);
        assertEquals(15, TrainingRunDraft.sourceMinInstancePixels(source, "cells"));

        var zero =
                TrainingFormFixtures.module(
                        cap,
                        recipe ->
                                recipe.getAsJsonObject("models")
                                        .getAsJsonObject("cells")
                                        .getAsJsonObject("params")
                                        .addProperty("min_instance_pixels", 0),
                        true);
        assertEquals(0, TrainingRunDraft.sourceMinInstancePixels(zero, "cells"));
        new TrainingRunDraft(
                zero,
                "cells",
                TrainingFormFixtures.sourceTask(zero, "cells"),
                Initialization.USE,
                ResultForm.COMPLETE_WORKFLOW,
                "Result",
                "A100",
                cap.defaultFieldProfile(),
                0,
                new TrainingAugmentationSelection("he-v1", List.of()),
                cap.defaultTrainingSettings());

        var missing =
                TrainingFormFixtures.module(
                        cap,
                        recipe ->
                                recipe.getAsJsonObject("models")
                                        .getAsJsonObject("cells")
                                        .getAsJsonObject("params")
                                        .remove("min_instance_pixels"),
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunDraft.sourceMinInstancePixels(missing, "cells"));
    }

    private static TrainingRunDraft draft(
            InstalledModule source, String name, TrainingSettings settings) {
        return new TrainingRunDraft(
                source,
                "cells",
                TrainingFormFixtures.sourceTask(source, "cells"),
                Initialization.USE,
                ResultForm.COMPLETE_WORKFLOW,
                name,
                "A100",
                TrainingRunDraft.options(source, "cells")
                        .getFirst()
                        .capability()
                        .defaultFieldProfile(),
                TrainingRunDraft.sourceMinInstancePixels(source, "cells"),
                new TrainingAugmentationSelection("he-v1", List.of("rotate90-v1", "flip-v1")),
                settings);
    }
}

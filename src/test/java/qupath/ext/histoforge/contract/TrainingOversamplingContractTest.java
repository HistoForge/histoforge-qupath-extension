package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.training.TrainingFormFixtures;

class TrainingOversamplingContractTest {
    @Test
    void sharedBooleanSettingsAndOpaqueSamplerEvidenceRoundTrip() throws Exception {
        var fixture = TrainingFormFixtures.fixture("training-oversampling-v1.json");
        var base = fixture.getAsJsonObject("report");
        assertEquals(
                base.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(base.get("value")));
        for (var value : fixture.getAsJsonArray("cases")) {
            var example = value.getAsJsonObject();
            var settings = TrainingCapabilityCodec.parseSettings(example.get("settings"));
            assertEquals(
                    example.get("sha256").getAsString(),
                    CanonicalCodec.canonicalDigest(TrainingCapabilityCodec.toJson(settings)));
            for (var invalid : fixture.getAsJsonArray("invalid_values")) {
                var changed = example.getAsJsonObject("settings").deepCopy();
                changed.add("oversample", invalid);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TrainingCapabilityCodec.parseSettings(changed));
            }
            // The client preserves the backend-owned scientific component, without
            // duplicating its executable profile registry or scoring formula.
            var report = base.getAsJsonObject("value").deepCopy();
            var originalSettings = report.getAsJsonObject("trainer_settings");
            originalSettings.addProperty("oversample", settings.oversample());
            report.getAsJsonObject("trainer_configuration").add("sampler", example.get("sampler"));
            var limits =
                    TrainingLimitsCodec.parse(
                            TrainingFormFixtures.fixture("training-datasets-v1.json")
                                    .get("trainingApiLimits"));
            var parsed = TrainingReportCodec.parseReport(report, limits.report());
            assertEquals(settings.oversample(), parsed.trainerSettings().oversample());
            assertEquals(
                    CanonicalCodec.canonicalDigest(report),
                    CanonicalCodec.canonicalDigest(
                            TrainingReportCodec.toJson(parsed, limits.report())));
            report.getAsJsonObject("task")
                    .getAsJsonObject("model_output")
                    .addProperty("type", "dense_classification");
            report.remove("min_instance_pixels");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TrainingReportCodec.parseReport(report, limits.report()));
        }
    }

    @Test
    void presenceAdvertisesSupportAndAbsenceIsNotFalse() throws Exception {
        var capability =
                TrainingCapabilityCodec.toJson(TrainingFormFixtures.capability(false), 4096);
        capability.addProperty("output_type", "instance_segmentation_and_classification");
        var defaults = capability.getAsJsonObject("default_training_settings");
        var omitted = TrainingCapabilityCodec.parseSettings(defaults.deepCopy());
        defaults.addProperty("oversample", false);
        var advertised = TrainingCapabilityCodec.parseCapability(capability, 4096);
        assertEquals(false, advertised.defaultTrainingSettings().oversample());
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingCapabilityCodec.requireSettingsAllowed(omitted, advertised));
        defaults.addProperty("oversample", true);
        var enabled = TrainingCapabilityCodec.parseSettings(defaults);
        TrainingCapabilityCodec.requireSettingsAllowed(enabled, advertised);
        for (String output : new String[] {"instance_segmentation", "dense_classification"}) {
            capability.addProperty("output_type", output);
            capability.remove("flow_loss_weighting_options");
            defaults.remove("flow_loss_weighting");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TrainingCapabilityCodec.parseCapability(capability, 4096));
        }
        var unsupported = TrainingFormFixtures.capability(false);
        assertNull(unsupported.defaultTrainingSettings().oversample());
        assertFalse(
                TrainingCapabilityCodec.toJson(unsupported.defaultTrainingSettings())
                        .has("oversample"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingCapabilityCodec.requireSettingsAllowed(enabled, unsupported));
    }
}

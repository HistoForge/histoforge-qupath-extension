package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetManifest;
import qupath.ext.histoforge.contract.TrainingLimitsCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingSplitCodec;
import qupath.ext.histoforge.contract.TrainingSplitCodec.NoValidationPlan;
import qupath.ext.histoforge.contract.TrainingSplitCodec.SplitUnitAssignment;
import qupath.ext.histoforge.contract.TrainingSplitCodec.TrainingSplitSidecar;
import qupath.ext.histoforge.contract.TrainingSplitCodec.ValidationSplitPlan;

class TrainingSplitGeneratorTest {

    @TempDir Path temporaryDirectory;

    @Test
    void zeroPercentUsesEveryRoiForTrainingWithoutAssignments() throws Exception {
        Fixture fixture = fixture();

        assertInstanceOf(
                NoValidationPlan.class,
                TrainingSplitGenerator.generate(fixture.manifest(), "image", 0, new Random(1)));
    }

    @Test
    void generatedImageSplitRoundsHalfUpAndStoresIndexOrder() throws Exception {
        Fixture fixture = fixture();

        ValidationSplitPlan split =
                assertInstanceOf(
                        ValidationSplitPlan.class,
                        TrainingSplitGenerator.generate(
                                fixture.manifest(), "image", 25, new Random(7)));

        assertEquals("image", split.unit());
        assertEquals("generated", split.assignmentSource());
        assertEquals(OptionalInt.of(25), split.requestedValidationPercent());
        assertEquals(
                List.of(0, 1), split.units().stream().map(SplitUnitAssignment::unitIndex).toList());
        assertEquals(
                1,
                split.units().stream()
                        .filter(item -> "validation".equals(item.partition()))
                        .count());
        TrainingSplitCodec.toJson(split, fixture.manifest(), fixture.limits());
    }

    @Test
    void positiveProjectGroupSplitRejectsOneGroup() throws Exception {
        Fixture fixture = fixture();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingSplitGenerator.generate(
                                fixture.manifest(), "project_group", 20, new Random(1)));
    }

    @Test
    void sidecarIsCanonicalAndReplacesOnlyAfterValidation() throws Exception {
        Fixture fixture = fixture();
        var split = TrainingSplitGenerator.generate(fixture.manifest(), "image", 50, new Random(2));
        Path target = temporaryDirectory.resolve("dataset.split.json");

        TrainingSplitSidecarWriter.write(target, fixture.manifest(), split, fixture.limits());

        byte[] bytes = Files.readAllBytes(target);
        TrainingSplitSidecar parsed =
                TrainingSplitCodec.parseSidecar(
                        JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)),
                        fixture.manifest(),
                        fixture.limits());
        assertEquals(fixture.manifest().datasetDigest(), parsed.datasetDigest());
        assertEquals(
                bytes.length,
                CanonicalCodec.canonicalBytes(
                                TrainingSplitCodec.toJson(
                                        parsed, fixture.manifest(), fixture.limits()))
                        .length);

        byte[] prior = bytes.clone();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingSplitSidecarWriter.write(
                                target,
                                fixture.manifest(),
                                new ValidationSplitPlan(
                                        "image",
                                        "custom",
                                        OptionalInt.empty(),
                                        List.of(
                                                new SplitUnitAssignment(0, "training"),
                                                new SplitUnitAssignment(1, "training"))),
                                fixture.limits()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(prior, Files.readAllBytes(target));
    }

    private static Fixture fixture() throws Exception {
        try (var input =
                TrainingSplitGeneratorTest.class.getResourceAsStream(
                        "/contracts/training-datasets-v1.json")) {
            if (input == null) {
                throw new IllegalStateException("missing training fixture");
            }
            JsonObject root =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
            TrainingApiLimits limits = TrainingLimitsCodec.parse(root.get("trainingApiLimits"));
            TrainingDatasetManifest manifest =
                    TrainingDatasetCodec.parseManifest(
                            root.getAsJsonObject("manifest").get("value"), limits);
            return new Fixture(limits, manifest);
        }
    }

    private record Fixture(TrainingApiLimits limits, TrainingDatasetManifest manifest) {}
}

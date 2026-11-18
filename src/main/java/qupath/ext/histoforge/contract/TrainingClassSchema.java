package qupath.ext.histoforge.contract;

import java.util.List;
import qupath.ext.histoforge.contract.ModelOutputCodec.ClassChannel;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;

/** Class-initialization modes and the source/target channel invariants of each. */
public final class TrainingClassSchema {
    private TrainingClassSchema() {}

    /**
     * How the trained model's class outputs start. The Train form derives the mode from the
     * researcher's choice; its wording lives in {@code ModelDisplay}.
     */
    public enum Initialization {
        /** Keep every class output and its learned weights. */
        USE("use"),
        /** Keep every class output and append new ones. */
        ADD("add"),
        /** Replace all class outputs; only the rest of the model keeps its weights. */
        DEFINE("define");

        private final String wireValue;

        Initialization(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static Initialization parse(String value) {
            for (var choice : values()) {
                if (choice.wireValue.equals(value)) return choice;
            }
            throw new IllegalArgumentException("class_initialization must be use, add, or define");
        }
    }

    public static void requireTransition(
            ModelOutput source, ModelOutput target, Initialization mode) {
        if (mode == null) throw new IllegalArgumentException("Select how to initialize classes.");
        if ("instance_segmentation".equals(source.type())) {
            String expected =
                    mode == Initialization.USE
                            ? source.type()
                            : "instance_segmentation_and_classification";
            if (!target.type().equals(expected)) {
                throw new IllegalArgumentException(
                        "Class initialization does not match the target task.");
            }
            return;
        }
        if (!source.type().equals(target.type())) {
            throw new IllegalArgumentException(
                    "Class initialization cannot change this model's task.");
        }
        var before = source.classChannels();
        var after = target.classChannels();
        if (mode == Initialization.USE && after.size() != before.size()) {
            throw new IllegalArgumentException(
                    "Keeping the model's classes must retain every existing channel.");
        }
        if (mode == Initialization.ADD && after.size() <= before.size()) {
            throw new IllegalArgumentException("Add at least one new class.");
        }
        if (mode != Initialization.DEFINE) {
            for (int index = 0; index < before.size(); index++) {
                if (!before.get(index).role().equals(after.get(index).role())) {
                    throw new IllegalArgumentException(
                            "Existing class-channel roles must be retained.");
                }
            }
        }
        if (mode == Initialization.ADD
                && after.subList(before.size(), after.size()).stream()
                        .anyMatch(channel -> "background".equals(channel.role()))) {
            throw new IllegalArgumentException("Added channels must be foreground classes.");
        }
    }

    /**
     * The target's named classes that start without learned weights: the appended ones after {@code
     * add}, and every one after {@code define} or when a segmentation-only model gains a
     * classifier. Such a class can be learned only from training examples.
     *
     * @param source the starting model's output
     * @param target an output that passes {@link #requireTransition} for {@code mode}
     */
    public static List<String> classesWithoutLearnedWeights(
            ModelOutput source, ModelOutput target, Initialization mode) {
        if (mode == Initialization.USE) return List.of();
        var channels = target.classChannels();
        // A segmentation-only source has no class channels, so everything it gains is new.
        int learned = mode == Initialization.ADD ? source.classChannels().size() : 0;
        return channels.subList(learned, channels.size()).stream()
                .filter(channel -> !"background".equals(channel.role()))
                .map(ClassChannel::pathClass)
                .toList();
    }
}

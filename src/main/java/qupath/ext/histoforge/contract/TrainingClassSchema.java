package qupath.ext.histoforge.contract;

import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;

/** Explicit class-initialization choices and source/target channel invariants. */
public final class TrainingClassSchema {
    private TrainingClassSchema() {}

    public enum Initialization {
        USE("use", "Use model classes"),
        ADD("add", "Add classes"),
        DEFINE("define", "Define new classes");

        private final String wireValue;
        private final String label;

        Initialization(String wireValue, String label) {
            this.wireValue = wireValue;
            this.label = label;
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

        @Override
        public String toString() {
            return label;
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
                    "Use model classes must retain every existing channel.");
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
}

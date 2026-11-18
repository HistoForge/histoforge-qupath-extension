package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ModelOutputCodec.ClassChannel;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.TrainingClassSchema;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.training.TrainingClassPlan.FixedChoice;
import qupath.ext.histoforge.training.TrainingClassPlan.ModelClass;
import qupath.ext.histoforge.training.TrainingClassPlan.NewClass;
import qupath.ext.histoforge.training.TrainingClassPlan.TrainAs;

class TrainingClassPlanTest {
    private static final List<Initialization> ALL = List.of(Initialization.values());

    /** A classifier with two predicted classes and a training-only class, as germ_cell has. */
    private static final ModelOutput CLASSIFIED =
            new ModelOutput(
                    "instance_segmentation_and_classification",
                    List.of(),
                    List.of(
                            new ClassChannel(0, "background", null),
                            new ClassChannel(1, "emitted", "Round Leydig Nuclei"),
                            new ClassChannel(2, "emitted", "Spindle Leydig Nuclei"),
                            new ClassChannel(3, "training_only", "Other")));

    /** A segmentation-only starter: one label and no class outputs. */
    private static final ModelOutput STARTER =
            new ModelOutput("instance_segmentation", List.of("Object"), List.of());

    @Test
    void keepingClassesPresetsExactNamesAndLeavesTheRestAsBackground() {
        var plan = new TrainingClassPlan(CLASSIFIED, ALL);
        var beforeDiscovery = plan.annotationClassMap();
        plan.showQuPathClasses(Set.of("Myoid", "Round Leydig Nuclei", "round leydig nuclei"));

        assertEquals(Initialization.USE, plan.initialization());
        assertEquals(
                List.of("Myoid", "Round Leydig Nuclei", "round leydig nuclei"),
                plan.quPathClasses());
        assertEquals("Round Leydig Nuclei", name(plan.trainAs("Round Leydig Nuclei")));
        assertEquals(FixedChoice.BACKGROUND, plan.trainAs("Myoid"));
        assertEquals(FixedChoice.BACKGROUND, plan.trainAs("round leydig nuclei"));
        // Discovery must not change the request that the first project read was made with.
        assertEquals(beforeDiscovery, plan.annotationClassMap());
        assertEquals(CLASSIFIED, plan.output());
        assertEquals(
                List.of("Spindle Leydig Nuclei"),
                plan.untrainedLearnedClasses().stream().map(ModelClass::name).toList());
    }

    @Test
    void addingAClassToKeptClassesAppendsItAndBecomesAdd() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Myoid", "Round Leydig Nuclei");
        assertEquals(
                List.of(
                        "BACKGROUND",
                        "Round Leydig Nuclei",
                        "Spindle Leydig Nuclei",
                        "Other",
                        "new Myoid"),
                choices(plan, "Myoid"));
        assertFalse(choices(plan, "Round Leydig Nuclei").contains("new Round Leydig Nuclei"));

        plan.assign("Myoid", new NewClass("Myoid"));

        assertEquals(Initialization.ADD, plan.initialization());
        var channels = plan.output().classChannels();
        assertEquals(CLASSIFIED.classChannels(), channels.subList(0, 4));
        assertEquals(new ClassChannel(4, "emitted", "Myoid"), channels.get(4));
        assertEquals("Myoid", plan.annotationClassMap().get("Myoid"));
        assertFalse(choices(plan, "Myoid").contains("new Myoid"));
    }

    @Test
    void aClassMadeByARowLeavesWithItsLastRow() {
        var plan =
                found(
                        new TrainingClassPlan(CLASSIFIED, ALL),
                        "Immune",
                        "Myoid",
                        "Round Leydig Nuclei");
        plan.assign("Myoid", new NewClass("Myoid"));
        var myoid = plan.classes().get(4);
        plan.assign("Immune", myoid);

        plan.assign("Myoid", FixedChoice.BACKGROUND);
        assertTrue(plan.classes().contains(myoid), "another row still trains the class");

        // Trying the option and moving on, as the arrow keys do, must leave nothing behind.
        plan.assign("Immune", FixedChoice.BACKGROUND);
        assertFalse(plan.classes().contains(myoid));
        assertEquals(Initialization.USE, plan.initialization());
        assertEquals("", plan.problem());
        assertTrue(choices(plan, "Myoid").contains("new Myoid"));
    }

    @Test
    void renamingAKeptClassKeepsItsChannelAndFollowsItsAnnotations() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Round Leydig Nuclei");
        var round = plan.classes().get(1);

        plan.rename(round, "  Round  ");

        assertEquals("Round", round.name());
        assertEquals("Round Leydig Nuclei", round.learnedAs());
        assertEquals(Initialization.USE, plan.initialization());
        assertEquals(new ClassChannel(1, "emitted", "Round"), plan.output().classChannels().get(1));
        assertEquals("Round", plan.annotationClassMap().get("Round Leydig Nuclei"));
    }

    @Test
    void renamingChangesWhichRowsMayCreateAClassOfTheirOwn() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Immune", "Myoid");
        plan.assign("Myoid", new NewClass("Myoid"));

        plan.rename(plan.classes().get(4), "Immune");

        assertFalse(choices(plan, "Immune").contains("new Immune"));
        assertTrue(choices(plan, "Myoid").contains("new Myoid"));
    }

    @Test
    void invalidClassNamesBlockTheTargetWithAReasonBesideTheField() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Myoid", "Round Leydig Nuclei");
        plan.assign("Myoid", new NewClass("Myoid"));
        var created = plan.classes().get(4);

        for (String blank : List.of("   ", " ")) {
            plan.rename(created, blank);
            assertEquals("Enter a class name or remove this row.", plan.nameProblem(created));
        }
        plan.rename(created, "Parent:Child");
        assertEquals(
                "Enter a valid QuPath class name (for subclasses, use Parent: Child).",
                plan.nameProblem(created));
        plan.rename(created, " Other ");
        assertEquals(
                "Use a different name; this class name is already used.",
                plan.nameProblem(created));
        assertEquals(
                "Use a different name; this class name is already used.",
                plan.nameProblem(plan.classes().get(3)));
        assertEquals(
                "Model classes: Use a different name; this class name is already used.",
                plan.targetProblem());
        assertEquals(plan.targetProblem(), plan.problem());
        assertThrows(IllegalArgumentException.class, plan::output);
        assertThrows(IllegalArgumentException.class, plan::annotationClassMap);

        plan.rename(plan.classes().get(1), "");
        assertEquals("Enter a class name.", plan.nameProblem(plan.classes().get(1)));
        plan.rename(plan.classes().get(1), "Round Leydig Nuclei");
        for (String valid : List.of("Parent: Child", "Background", "Myoid")) {
            plan.rename(created, valid);
            assertEquals("", plan.problem(), valid);
        }
    }

    @Test
    void removingAClassAsksAgainForItsAnnotationsWithoutBlockingAProjectRead() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Myoid", "Round Leydig Nuclei");
        plan.assign("Myoid", new NewClass("Myoid"));

        plan.remove(plan.classes().get(4));

        assertEquals(FixedChoice.NOT_CHOSEN, plan.trainAs("Myoid"));
        assertEquals("NOT_CHOSEN", choices(plan, "Myoid").getFirst());
        assertEquals("Choose Train as for Myoid.", plan.problem());
        // The target is complete, so the project can still be read while the row waits.
        assertEquals("", plan.targetProblem());
        assertFalse(plan.annotationClassMap().containsKey("Myoid"));
        assertThrows(IllegalArgumentException.class, () -> plan.remove(plan.classes().get(1)));
    }

    @Test
    void trainingNothingButBackgroundIsBlockedOnceClassesAreFound() {
        var plan = new TrainingClassPlan(CLASSIFIED, ALL);
        // Before the project is read there are no rows, and the first read needs a valid plan.
        assertEquals("", plan.problem());

        plan.showQuPathClasses(Set.of("Myoid", "Immune"));

        assertEquals(
                "Set at least one QuPath class to a model class. With every class set to"
                        + " Background, the model has nothing to learn.",
                plan.problem());
        assertEquals("", plan.targetProblem());
        plan.assign("Myoid", plan.classes().get(3));
        assertEquals("", plan.problem());
        // A training-only class has examples now; the two predicted classes have none.
        assertEquals(2, plan.untrainedLearnedClasses().size());
    }

    @Test
    void aReadThatFindsNoClassesBlocksTrainingButNotTheNextRead() {
        var plan = new TrainingClassPlan(CLASSIFIED, ALL);
        assertFalse(plan.foundNoQuPathClasses());

        plan.showQuPathClasses(Set.of());

        assertTrue(plan.foundNoQuPathClasses());
        assertTrue(plan.problem().startsWith("No classified annotations or detections were found"));
        assertEquals("", plan.targetProblem());
        assertEquals(CLASSIFIED, plan.output());

        plan.forgetQuPathClasses();
        assertFalse(plan.foundNoQuPathClasses());
        assertEquals("", plan.problem());
    }

    @Test
    void replacingClassesStartsEmptyAndAsksAgainOnlyWhereAClassWasTrained() {
        var plan =
                found(
                        new TrainingClassPlan(CLASSIFIED, ALL),
                        "Myoid",
                        "Other",
                        "Round Leydig Nuclei");

        plan.setReplacing(true);

        assertEquals(Initialization.DEFINE, plan.initialization());
        assertEquals(FixedChoice.NOT_CHOSEN, plan.trainAs("Round Leydig Nuclei"));
        assertEquals(FixedChoice.BACKGROUND, plan.trainAs("Myoid"));
        // The source's training-only class is carried into the new list as a removable class.
        assertEquals("Other", name(plan.trainAs("Other")));
        assertTrue(plan.classes().get(1).isNew() && plan.classes().get(1).isTrainingOnly());
        assertEquals("Choose Train as for Round Leydig Nuclei.", plan.problem());

        plan.assign("Round Leydig Nuclei", FixedChoice.BACKGROUND);
        assertEquals(
                "Add at least one model class: choose New model class in Train as.",
                plan.problem());
        assertEquals(plan.problem(), plan.targetProblem());
        plan.assign("Round Leydig Nuclei", new NewClass("Round Leydig Nuclei"));

        assertEquals("", plan.problem());
        assertEquals(
                List.of(
                        new ClassChannel(0, "background", null),
                        new ClassChannel(1, "training_only", "Other"),
                        new ClassChannel(2, "emitted", "Round Leydig Nuclei")),
                plan.output().classChannels());
        assertTrue(plan.classes().get(2).isNew());
        assertTrue(plan.untrainedLearnedClasses().isEmpty());
    }

    @Test
    void aNewClassNoRowTrainsBlocksTrainingUntilAssignedOrRemoved() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Other", "Round Leydig Nuclei");
        plan.setReplacing(true);
        plan.assign("Round Leydig Nuclei", new NewClass("Round Leydig Nuclei"));

        plan.assign("Other", FixedChoice.BACKGROUND);

        // The carried class was not made by a row, so it stays until it is removed on purpose.
        var unused = plan.unusedNewClass().orElseThrow();
        assertEquals("Other", unused.name());
        assertEquals(
                "No QuPath class is set to Other. Choose it in Train as, or remove Other under"
                        + " Edit model classes.",
                plan.problem());
        assertTrue(plan.problem().startsWith(TrainingClassPlan.unusedClassProblem(unused)));
        assertEquals("", plan.targetProblem());

        plan.remove(unused);

        assertEquals("", plan.problem());
        assertEquals(2, plan.output().classChannels().size());
    }

    @Test
    void eachChoiceKeepsItsOwnClassesAndAssignments() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Myoid", "Round Leydig Nuclei");
        plan.assign("Myoid", new NewClass("Myoid"));
        var keptMap = plan.annotationClassMap();

        plan.setReplacing(true);
        plan.assign("Round Leydig Nuclei", new NewClass("Round Leydig Nuclei"));
        plan.assign("Myoid", FixedChoice.BACKGROUND);
        plan.remove(plan.classes().get(1));
        var replacedMap = plan.annotationClassMap();

        plan.setReplacing(false);
        assertEquals(Initialization.ADD, plan.initialization());
        assertEquals(keptMap, plan.annotationClassMap());
        assertEquals(5, plan.output().classChannels().size());

        plan.setReplacing(true);
        assertEquals(replacedMap, plan.annotationClassMap());
        assertEquals(Map.of("Round Leydig Nuclei", "Round Leydig Nuclei"), replacedMap);
        assertEquals(2, plan.output().classChannels().size());
    }

    @Test
    void aSegmentationOnlyModelOutlinesWithOneLabelUnlessItIsToldToClassify() {
        var plan = new TrainingClassPlan(STARTER, ALL);
        assertTrue(plan.outlinesOnly());
        assertFalse(plan.canCreateClass());
        assertEquals(Initialization.USE, plan.initialization());
        assertEquals(STARTER, plan.output());

        plan.showQuPathClasses(Set.of("Round Leydig Nuclei"));
        assertEquals(List.of("BACKGROUND", "Object"), choices(plan, "Round Leydig Nuclei"));
        assertTrue(plan.problem().startsWith("Set at least one QuPath class to a model class."));
        plan.assign("Round Leydig Nuclei", plan.classes().getFirst());
        assertEquals("instance_segmentation", plan.outputType());
        assertEquals("Object", plan.annotationClassMap().get("Round Leydig Nuclei"));
        plan.rename(plan.classes().getFirst(), "Nucleus");
        assertEquals(List.of("Nucleus"), plan.output().classes());

        plan.setReplacing(true);
        assertFalse(plan.outlinesOnly());
        assertEquals("Nucleus", plan.outlineLabel(), "the first choice keeps its own label");
        assertEquals(Initialization.DEFINE, plan.initialization());
        assertEquals("instance_segmentation_and_classification", plan.outputType());
        assertEquals(FixedChoice.NOT_CHOSEN, plan.trainAs("Round Leydig Nuclei"));
        plan.assign("Round Leydig Nuclei", new NewClass("Round Leydig Nuclei"));
        // One foreground class still gets an explicit background and class output.
        assertEquals(
                List.of(
                        new ClassChannel(0, "background", null),
                        new ClassChannel(1, "emitted", "Round Leydig Nuclei")),
                plan.output().classChannels());
    }

    @Test
    void aNewListCanBeNamedBeforeTheProjectIsRead() {
        var plan = new TrainingClassPlan(STARTER, ALL);
        plan.setReplacing(true);
        assertEquals("Add at least one model class.", plan.problem());

        assertThrows(IllegalArgumentException.class, () -> plan.add("Parent:Child"));
        plan.add(" Nucleus ");
        assertThrows(IllegalArgumentException.class, () -> plan.add("Nucleus"));

        // The first project read needs a target, so a class without rows is fine until then.
        assertEquals("", plan.problem());
        plan.showQuPathClasses(Set.of("Nucleus", "Debris"));
        assertEquals("Nucleus", name(plan.trainAs("Nucleus")));
        assertEquals(FixedChoice.BACKGROUND, plan.trainAs("Debris"));
        assertEquals("", plan.problem());

        // Once classes are found, a list whose only class has no annotations cannot train.
        plan.forgetQuPathClasses();
        plan.showQuPathClasses(Set.of("Debris"));
        assertTrue(plan.problem().startsWith("Set at least one QuPath class to a model class."));
        plan.assign("Debris", new NewClass("Debris"));
        assertEquals(
                "No QuPath class is set to Nucleus. Choose it in Train as, or remove Nucleus under"
                        + " Edit model classes.",
                plan.problem());
    }

    @Test
    void onlyChoicesTheBackendOffersAreAvailable() {
        var useOnly = new TrainingClassPlan(CLASSIFIED, List.of(Initialization.USE));
        assertTrue(useOnly.keepOffered());
        assertFalse(useOnly.replaceOffered());
        assertFalse(useOnly.canCreateClass());
        assertThrows(IllegalArgumentException.class, () -> useOnly.setReplacing(true));
        assertFalse(useOnly.replacing());
        assertFalse(choices(found(useOnly, "Myoid"), "Myoid").contains("new Myoid"));

        var defineOnly = new TrainingClassPlan(CLASSIFIED, List.of(Initialization.DEFINE));
        assertFalse(defineOnly.keepOffered());
        assertTrue(defineOnly.replacing());
        assertEquals(Initialization.DEFINE, defineOnly.initialization());

        var addOnly = new TrainingClassPlan(CLASSIFIED, List.of(Initialization.ADD));
        assertTrue(addOnly.keepOffered());
        assertEquals(Initialization.ADD, addOnly.initialization());
        assertEquals("Add at least one new model class.", addOnly.problem());
        // The class it needs can be named before the project is read, so the read can happen.
        assertTrue(addOnly.canCreateClass());
        addOnly.add("Myoid");
        assertEquals("", addOnly.targetProblem());
        assertEquals(5, addOnly.output().classChannels().size());

        // A segmentation-only model has nothing to extend, so Add alone also builds a new list.
        var starterAddOnly = new TrainingClassPlan(STARTER, List.of(Initialization.ADD));
        assertFalse(starterAddOnly.keepOffered());
        assertTrue(starterAddOnly.replacing());
        starterAddOnly.add("Nucleus");
        assertEquals(Initialization.ADD, starterAddOnly.initialization());
        assertEquals("", starterAddOnly.problem());

        var starterBoth =
                new TrainingClassPlan(STARTER, List.of(Initialization.ADD, Initialization.DEFINE));
        assertEquals(Initialization.DEFINE, starterBoth.initialization());
    }

    @Test
    void aProjectSwitchForgetsFoundClassesButKeepsTheModelClasses() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Myoid", "Round Leydig Nuclei");
        plan.assign("Myoid", new NewClass("Myoid"));
        plan.rename(plan.classes().get(1), "Round");

        plan.forgetQuPathClasses();

        assertEquals(List.of(), plan.quPathClasses());
        assertEquals(5, plan.classes().size());
        assertEquals("", plan.problem());
        // Presets follow the current names, so the next read matches what the list shows.
        assertEquals(
                Map.of(
                        "Round", "Round",
                        "Spindle Leydig Nuclei", "Spindle Leydig Nuclei",
                        "Other", "Other",
                        "Myoid", "Myoid"),
                plan.annotationClassMap());
    }

    @Test
    void aReadWithoutTrainingRoisKeepsEveryChoice() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Reviewed", "Round Leydig Nuclei");
        // The opposite of the defaults: the researcher's own class trains the model class, and
        // the class that only shares the model class's name is background.
        plan.assign("Reviewed", plan.classes().get(1));
        plan.assign("Round Leydig Nuclei", FixedChoice.BACKGROUND);
        var chosen = plan.annotationClassMap();

        plan.hideQuPathClasses();

        assertEquals(List.of(), plan.quPathClasses());
        assertFalse(plan.projectRead());
        assertEquals("", plan.problem());
        assertEquals(chosen, plan.annotationClassMap());

        plan.showQuPathClasses(Set.of("Reviewed", "Round Leydig Nuclei"));
        assertEquals("Round Leydig Nuclei", name(plan.trainAs("Reviewed")));
        assertEquals(FixedChoice.BACKGROUND, plan.trainAs("Round Leydig Nuclei"));
        assertEquals(chosen, plan.annotationClassMap());
    }

    @Test
    void aNewListFollowsWhatTheResearcherChoseUnderTheFirstChoice() {
        var moved = found(new TrainingClassPlan(CLASSIFIED, ALL), "Other");
        // The researcher's Other objects are Round Leydig Nuclei, not the model's Other class.
        moved.assign("Other", moved.classes().get(1));
        moved.setReplacing(true);
        // The new list has a class named Other, but training it was not the researcher's choice.
        assertEquals(FixedChoice.NOT_CHOSEN, moved.trainAs("Other"));
        assertEquals("Choose Train as for Other.", moved.problem());

        var background = found(new TrainingClassPlan(CLASSIFIED, ALL), "Other");
        background.assign("Other", FixedChoice.BACKGROUND);
        background.setReplacing(true);
        assertEquals(FixedChoice.BACKGROUND, background.trainAs("Other"));

        // The same holds when the class is not listed while the new list is opened.
        var unlisted = found(new TrainingClassPlan(CLASSIFIED, ALL), "Other");
        unlisted.assign("Other", unlisted.classes().get(1));
        unlisted.showQuPathClasses(Set.of());
        unlisted.setReplacing(true);
        unlisted.add("Tumour");
        var beforeRead = unlisted.annotationClassMap();
        assertEquals(Map.of("Tumour", "Tumour"), beforeRead);
        unlisted.showQuPathClasses(Set.of("Other"));
        assertEquals(FixedChoice.NOT_CHOSEN, unlisted.trainAs("Other"));
        assertEquals(beforeRead, unlisted.annotationClassMap());
    }

    @Test
    void aQuPathClassTrainsTheModelClassThatHasItsNameNow() {
        var plan = new TrainingClassPlan(CLASSIFIED, ALL);
        plan.rename(plan.classes().get(1), "Nucleus");
        var beforeRead = plan.annotationClassMap();
        assertEquals("Nucleus", beforeRead.get("Nucleus"));
        assertFalse(beforeRead.containsKey("Round Leydig Nuclei"));

        plan.showQuPathClasses(Set.of("Nucleus", "Round Leydig Nuclei"));

        assertEquals("Nucleus", name(plan.trainAs("Nucleus")));
        assertEquals(FixedChoice.BACKGROUND, plan.trainAs("Round Leydig Nuclei"));
        // The read counted objects with the map it was given, so finding classes cannot change it.
        assertEquals(beforeRead, plan.annotationClassMap());

        // A row that already has a choice keeps it when a class is renamed to its name.
        plan.rename(plan.classes().get(2), "Round Leydig Nuclei");
        assertEquals(FixedChoice.BACKGROUND, plan.trainAs("Round Leydig Nuclei"));
    }

    @Test
    void aRenamedRowClassOutlivesItsRow() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Ki67", "Round Leydig Nuclei");
        plan.assign("Ki67", new NewClass("Ki67"));
        var dividing = plan.classes().get(4);
        plan.rename(dividing, "Dividing nucleus");

        // Passing over another choice, as an open list does, must not lose the typed name.
        plan.assign("Ki67", FixedChoice.BACKGROUND);

        assertTrue(plan.classes().contains(dividing));
        assertEquals(
                List.of(
                        "BACKGROUND",
                        "Round Leydig Nuclei",
                        "Spindle Leydig Nuclei",
                        "Other",
                        "Dividing nucleus",
                        "new Ki67"),
                choices(plan, "Ki67"));
        assertEquals(
                "No QuPath class is set to Dividing nucleus. Choose it in Train as, or remove"
                        + " Dividing nucleus under Edit model classes.",
                plan.problem());
        plan.assign("Ki67", dividing);
        assertEquals("", plan.problem());
        assertEquals(Initialization.ADD, plan.initialization());
    }

    @Test
    void aClassIsNamedByHandOnlyWhileNoRowCanCreateIt() {
        var addOnly = new TrainingClassPlan(CLASSIFIED, List.of(Initialization.ADD));
        assertTrue(addOnly.needsClassNamedByHand(), "no row exists before the project is read");

        addOnly.showQuPathClasses(Set.of("Myoid", "Other"));
        assertFalse(addOnly.needsClassNamedByHand());
        assertEquals(
                "Add at least one new model class: choose New model class in Train as.",
                addOnly.targetProblem());

        // Every QuPath class is named like a model class, so no row offers a new one.
        addOnly.showQuPathClasses(Set.of("Other", "Round Leydig Nuclei"));
        assertTrue(addOnly.needsClassNamedByHand());
        assertEquals("Add at least one new model class.", addOnly.targetProblem());
        var extra = addOnly.add("Extra");
        assertFalse(addOnly.needsClassNamedByHand());
        assertEquals("", addOnly.targetProblem());
        addOnly.remove(extra);
        assertTrue(addOnly.needsClassNamedByHand(), "removing the class brings the need back");

        var keeping = found(new TrainingClassPlan(CLASSIFIED, ALL), "Other");
        assertFalse(keeping.needsClassNamedByHand(), "the model's own classes can be trained");
        keeping.setReplacing(true);
        assertTrue(keeping.needsClassNamedByHand(), "Other is taken by the carried class");
        assertEquals("Add at least one model class.", keeping.targetProblem());
    }

    @Test
    void theContractNamesExactlyTheClassesThePlanStartsAnew() {
        var plan = found(new TrainingClassPlan(CLASSIFIED, ALL), "Myoid", "Round Leydig Nuclei");
        assertEquals(List.of(), startedAnew(CLASSIFIED, plan));

        plan.assign("Myoid", new NewClass("Myoid"));
        assertEquals(List.of("Myoid"), startedAnew(CLASSIFIED, plan));

        plan.setReplacing(true);
        plan.assign("Round Leydig Nuclei", new NewClass("Round Leydig Nuclei"));
        assertEquals(List.of("Other", "Round Leydig Nuclei"), startedAnew(CLASSIFIED, plan));

        var starter = found(new TrainingClassPlan(STARTER, ALL), "Nucleus");
        assertEquals(List.of(), startedAnew(STARTER, starter));
        starter.setReplacing(true);
        starter.assign("Nucleus", new NewClass("Nucleus"));
        assertEquals(List.of("Nucleus"), startedAnew(STARTER, starter));

        var starterAddOnly = new TrainingClassPlan(STARTER, List.of(Initialization.ADD));
        starterAddOnly.add("Nucleus");
        assertEquals(List.of("Nucleus"), startedAnew(STARTER, starterAddOnly));
    }

    /** What the contract says needs training examples, checked against the plan's own marking. */
    private static List<String> startedAnew(ModelOutput source, TrainingClassPlan plan) {
        var names =
                TrainingClassSchema.classesWithoutLearnedWeights(
                        source, plan.output(), plan.initialization());
        assertEquals(
                plan.outlinesOnly()
                        ? List.of()
                        : plan.classes().stream()
                                .filter(ModelClass::isNew)
                                .map(ModelClass::name)
                                .toList(),
                names);
        return names;
    }

    private static TrainingClassPlan found(TrainingClassPlan plan, String... names) {
        plan.showQuPathClasses(Set.of(names));
        return plan;
    }

    private static String name(TrainAs choice) {
        return ((ModelClass) choice).name();
    }

    /** Train as choices as short text: fixed choices by constant, classes by name. */
    private static List<String> choices(TrainingClassPlan plan, String qupathClass) {
        return plan.choices(qupathClass).stream()
                .map(
                        choice ->
                                switch (choice) {
                                    case FixedChoice fixed -> fixed.name();
                                    case NewClass created -> "new " + created.name();
                                    case ModelClass modelClass -> modelClass.name();
                                })
                .toList();
    }
}

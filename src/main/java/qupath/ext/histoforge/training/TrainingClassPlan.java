package qupath.ext.histoforge.training;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ClassChannel;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.TrainingClassSchema;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;

/**
 * The trained model's classes and what each QuPath class teaches.
 *
 * <p>The researcher makes one choice: keep the starting model's classes, or build a new class list.
 * Each choice owns its class list and its Train as assignments, so switching between them loses
 * nothing. The backend's class initialization, target output and annotation map are derived from
 * the active choice.
 */
public final class TrainingClassPlan {
    private static final String SEGMENTATION_ONLY = "instance_segmentation";
    private static final String BACKGROUND_ROLE = "background";
    private static final String PREDICTED_ROLE = "emitted";
    private static final String TRAINING_ONLY_ROLE = "training_only";
    private static final String NAME_USED =
            "Use a different name; this class name is already used.";

    /** Starts a {@link #targetProblem()} that is shown beside a class's name field. */
    public static final String NAME_PROBLEM_PREFIX = "Model classes: ";

    private final ModelOutput source;
    private final Set<Initialization> offered;
    private final ClassList kept;
    private ClassList replacement;
    private boolean replacing;

    /** Whether the found classes come from a completed project read, even an empty one. */
    private boolean projectRead;

    private final TreeSet<String> qupathClasses = new TreeSet<>();

    /**
     * @param source the starting model's output
     * @param offered every class initialization the backend offers for this model
     */
    public TrainingClassPlan(ModelOutput source, Collection<Initialization> offered) {
        this.source = source;
        this.offered =
                offered.isEmpty() ? EnumSet.noneOf(Initialization.class) : EnumSet.copyOf(offered);
        kept = new ClassList();
        if (sourceHasClasses()) {
            for (var channel : source.classChannels()) {
                kept.classes.add(
                        new ModelClass(channel.role(), channel.pathClass(), channel.pathClass()));
            }
        } else {
            String label = source.classes().getFirst();
            kept.classes.add(new ModelClass(PREDICTED_ROLE, label, label));
        }
        replacing = !keepOffered();
        if (replacing) replacement = replacementList();
    }

    /** What a QuPath class can train. */
    public sealed interface TrainAs permits FixedChoice, ModelClass, NewClass {}

    public enum FixedChoice implements TrainAs {
        /** Objects of the QuPath class are taught as background. */
        BACKGROUND,
        /** The researcher still has to choose; training cannot start. */
        NOT_CHOSEN
    }

    /** Choosing this creates a model class named after the QuPath class. */
    public record NewClass(String name) implements TrainAs {}

    /** One class channel of the trained model. */
    public static final class ModelClass implements TrainAs {
        private final String role;
        private final String learnedAs;
        private String name;

        /**
         * Made by choosing New model class in a row and not renamed since; such a class exists only
         * while a row trains it.
         */
        private boolean madeByTrainAs;

        private ModelClass(String role, String name, String learnedAs) {
            this.role = role;
            this.name = name == null ? "" : name;
            this.learnedAs = learnedAs;
        }

        public String name() {
            return name;
        }

        /** The starting model's name for this class, or null without learned weights. */
        public String learnedAs() {
            return learnedAs;
        }

        /** Background is a channel without a name; every other channel is a named class. */
        public boolean isNamed() {
            return !BACKGROUND_ROLE.equals(role);
        }

        /** Whether analysis adds objects of this class to QuPath. */
        public boolean isPredicted() {
            return PREDICTED_ROLE.equals(role);
        }

        /** Learned during training, but its objects are left out of analysis results. */
        public boolean isTrainingOnly() {
            return TRAINING_ONLY_ROLE.equals(role);
        }

        /** A named class that continues from the starting model's weights. */
        public boolean isLearned() {
            return isNamed() && learnedAs != null;
        }

        /** A named class without learned weights; only these can be removed. */
        public boolean isNew() {
            return isNamed() && learnedAs == null;
        }
    }

    /** Whether the starting model has class outputs; a segmentation-only model has one label. */
    public boolean sourceHasClasses() {
        return !SEGMENTATION_ONLY.equals(source.type());
    }

    /** Whether the first choice, training the model's own classes or single label, is offered. */
    public boolean keepOffered() {
        return offered.contains(Initialization.USE)
                || sourceHasClasses() && offered.contains(Initialization.ADD);
    }

    /**
     * Whether a new class list is offered. A segmentation-only model has no class outputs to
     * extend, so Add also builds a new list for it.
     */
    public boolean replaceOffered() {
        return offered.contains(Initialization.DEFINE)
                || !sourceHasClasses() && offered.contains(Initialization.ADD);
    }

    public boolean replacing() {
        return replacing;
    }

    /**
     * @throws IllegalArgumentException when the backend does not offer that choice for this model
     */
    public void setReplacing(boolean replacing) {
        if (replacing ? !replaceOffered() : !keepOffered()) {
            throw new IllegalArgumentException("This choice is not offered for the model.");
        }
        this.replacing = replacing;
        if (replacing && replacement == null) replacement = replacementList();
    }

    /** A segmentation-only model trained as it is: outlines with one label and no classifier. */
    public boolean outlinesOnly() {
        return !sourceHasClasses() && !replacing;
    }

    /**
     * The label every object gets when a segmentation-only model is trained as it is. It belongs to
     * the first choice, so it is the same while the second choice is active.
     */
    public String outlineLabel() {
        return kept.classes.getFirst().name;
    }

    /** Whether classes can be added while the model's own classes are kept. */
    public boolean keptClassesCanGrow() {
        return sourceHasClasses() && offered.contains(Initialization.ADD);
    }

    /** Whether the active choice may gain a class that the starting model does not have. */
    public boolean canCreateClass() {
        return replacing || keptClassesCanGrow();
    }

    /** The active choice's class channels in output order, including the background channel. */
    public List<ModelClass> classes() {
        return List.copyOf(active().classes);
    }

    /**
     * Store a class name as typed; {@link #nameProblem} reports whether it can be trained. A class
     * made in a Train as row becomes a lasting class once it is renamed, so the name is not lost
     * when that row moves to another choice.
     */
    public void rename(ModelClass modelClass, String name) {
        modelClass.name = name.strip();
        modelClass.madeByTrainAs = false;
    }

    /**
     * Add a class by name, for when no Train as row can create it. A QuPath class found later under
     * that name trains it by default.
     *
     * @throws IllegalArgumentException with the reason the name cannot be used
     */
    public ModelClass add(String name) {
        String value = name.strip();
        String problem = nameSyntaxProblem(value);
        if (problem.isEmpty() && isNameUsed(value, null)) problem = NAME_USED;
        if (!problem.isEmpty()) throw new IllegalArgumentException(problem);
        var modelClass = new ModelClass(PREDICTED_ROLE, value, null);
        active().classes.add(modelClass);
        return modelClass;
    }

    /** Remove a new class; QuPath classes that trained it need a new choice. */
    public void remove(ModelClass modelClass) {
        if (!modelClass.isNew()) {
            throw new IllegalArgumentException("Only a new class can be removed.");
        }
        drop(modelClass);
    }

    private void drop(ModelClass modelClass) {
        var list = active();
        list.classes.remove(modelClass);
        // This includes a QuPath class that is not listed now: it asks again when it is listed.
        list.trainAs.replaceAll(
                (name, choice) -> choice == modelClass ? FixedChoice.NOT_CHOSEN : choice);
    }

    /**
     * Show the annotation classes found in the project. A class listed for the first time gets its
     * default choice; a class that was listed before keeps the choice it had.
     */
    public void showQuPathClasses(Set<String> names) {
        projectRead = true;
        qupathClasses.clear();
        qupathClasses.addAll(names);
        chooseDefaults(kept);
        if (replacement != null) chooseDefaults(replacement);
    }

    /** Whether the found classes come from a completed project read, even an empty one. */
    public boolean projectRead() {
        return projectRead;
    }

    /**
     * Whether the project was read and held no classified annotation or detection. QuPath cell
     * objects are never training labels, so a project with only those also counts.
     */
    public boolean foundNoQuPathClasses() {
        return projectRead && qupathClasses.isEmpty();
    }

    /**
     * List no QuPath classes, as after a read that found no training ROI. Nothing was read, so
     * every choice already made stays for the next read of the same project.
     */
    public void hideQuPathClasses() {
        projectRead = false;
        qupathClasses.clear();
    }

    /** Forget the found classes and every choice made for them, as after a project switch. */
    public void forgetQuPathClasses() {
        hideQuPathClasses();
        kept.trainAs.clear();
        if (replacement != null) replacement.trainAs.clear();
    }

    /** The found annotation classes in display order. */
    public List<String> quPathClasses() {
        return List.copyOf(qupathClasses);
    }

    public TrainAs trainAs(String qupathClass) {
        return active().trainAs.get(qupathClass);
    }

    /** Every Train as choice of one QuPath class, in display order. */
    public List<TrainAs> choices(String qupathClass) {
        var result = new ArrayList<TrainAs>();
        if (trainAs(qupathClass) == FixedChoice.NOT_CHOSEN) result.add(FixedChoice.NOT_CHOSEN);
        result.add(FixedChoice.BACKGROUND);
        active().classes.stream().filter(ModelClass::isNamed).forEach(result::add);
        if (canCreateClass() && !isNameUsed(qupathClass, null)) {
            result.add(new NewClass(qupathClass));
        }
        return result;
    }

    /**
     * Apply a Train as choice. {@link NewClass} creates the class and assigns it. A class created
     * that way, and not renamed since, is removed again when its last row moves to another choice.
     * So trying the option, for example with the arrow keys, leaves no class without annotations
     * behind.
     */
    public void assign(String qupathClass, TrainAs choice) {
        var list = active();
        var previous = list.trainAs.get(qupathClass);
        if (choice instanceof NewClass created) {
            var modelClass = new ModelClass(PREDICTED_ROLE, created.name(), null);
            modelClass.madeByTrainAs = true;
            list.classes.add(modelClass);
            list.trainAs.put(qupathClass, modelClass);
        } else {
            list.trainAs.put(qupathClass, choice);
        }
        if (previous instanceof ModelClass left && left.madeByTrainAs && !isTrained(left)) {
            drop(left);
        }
    }

    /** The backend mode implied by the choice and by whether classes were added to a kept list. */
    public Initialization initialization() {
        if (replacing) {
            return sourceHasClasses() || offered.contains(Initialization.DEFINE)
                    ? Initialization.DEFINE
                    : Initialization.ADD;
        }
        boolean added = kept.classes.stream().anyMatch(ModelClass::isNew);
        return sourceHasClasses() && (added || !offered.contains(Initialization.USE))
                ? Initialization.ADD
                : Initialization.USE;
    }

    /** The trained model's output type; classifying a segmentation-only model changes it. */
    public String outputType() {
        return !sourceHasClasses() && replacing
                ? "instance_segmentation_and_classification"
                : source.type();
    }

    /**
     * @throws IllegalArgumentException with {@link #targetProblem()} when no target can be built
     */
    public ModelOutput output() {
        requireTarget();
        return target();
    }

    /**
     * QuPath classes that teach a model class. A class left on Background, or still waiting for a
     * choice, has no entry.
     *
     * <p>A model class name that no listed QuPath class has yet is included with its default. Newly
     * discovered QuPath classes use the same defaults when the preview is projected.
     *
     * @throws IllegalArgumentException with {@link #targetProblem()} when no target can be built
     */
    public Map<String, String> annotationClassMap() {
        requireTarget();
        var list = active();
        var result = new LinkedHashMap<String, String>();
        list.trainAs.forEach(
                (qupathClass, choice) -> {
                    if (choice instanceof ModelClass modelClass) {
                        result.put(qupathClass, modelClass.name);
                    }
                });
        for (var modelClass : list.classes) {
            if (!modelClass.isNamed() || list.trainAs.containsKey(modelClass.name)) continue;
            if (defaultChoice(list, modelClass.name) instanceof ModelClass byDefault) {
                result.put(modelClass.name, byDefault.name);
            }
        }
        return Map.copyOf(result);
    }

    /** Why the trained model's classes cannot be built yet, or an empty string. */
    public String targetProblem() {
        var list = active();
        for (var modelClass : list.classes) {
            String nameProblem = nameProblem(modelClass);
            if (!nameProblem.isEmpty()) return NAME_PROBLEM_PREFIX + nameProblem;
        }
        if (needsAnotherClass()) {
            String needed =
                    list.classes.stream().anyMatch(ModelClass::isPredicted)
                            ? "Add at least one new model class"
                            : "Add at least one model class";
            return needed
                    + (trainAsCanCreateClass() ? ": choose New model class in Train as." : ".");
        }
        try {
            TrainingClassSchema.requireTransition(source, target(), initialization());
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
        return "";
    }

    /**
     * Whether the class list needs a class that no Train as row can create, so the researcher has
     * to name it by hand: before the project is read, or when every QuPath class name is already
     * the name of a model class.
     */
    public boolean needsClassNamedByHand() {
        return canCreateClass() && needsAnotherClass() && !trainAsCanCreateClass();
    }

    /** Whether the class list cannot be trained until it gains a class. */
    private boolean needsAnotherClass() {
        var list = active();
        if (list.classes.stream().noneMatch(ModelClass::isPredicted)) return true;
        // A model that only offers adding classes cannot be trained with its own classes alone.
        return initialization() == Initialization.ADD
                && list.classes.stream().noneMatch(ModelClass::isNew);
    }

    private boolean trainAsCanCreateClass() {
        return canCreateClass() && qupathClasses.stream().anyMatch(name -> !isNameUsed(name, null));
    }

    /** The first reason training cannot start yet, or an empty string. */
    public String problem() {
        String target = targetProblem();
        if (target.startsWith(NAME_PROBLEM_PREFIX)) return target;
        if (foundNoQuPathClasses()) {
            return "No classified annotations or detections were found in images with training"
                    + " ROIs. In QuPath, give the objects you want to train on a class, then"
                    + " Refresh. QuPath cell objects are not used for training.";
        }
        var unchosen = unchosenQuPathClasses();
        if (!unchosen.isEmpty()) {
            return "Choose Train as for " + sentenceList(unchosen, "and") + ".";
        }
        if (!target.isEmpty()) return target;
        if (!qupathClasses.isEmpty()) {
            if (qupathClasses.stream().noneMatch(name -> trainAs(name) instanceof ModelClass)) {
                return "Set at least one QuPath class to a model class. With every class set to "
                        + "Background, the model has nothing to learn.";
            }
            var unused = unusedNewClass();
            if (unused.isPresent()) {
                return unusedClassProblem(unused.get())
                        + "remove "
                        + unused.get().name
                        + " under Edit model classes.";
            }
        }
        return "";
    }

    /** The start of {@link #problem()} for an unused new class, up to the offer to remove it. */
    public static String unusedClassProblem(ModelClass unused) {
        return "No QuPath class is set to " + unused.name + ". Choose it in Train as, or ";
    }

    /** Why a class name cannot be trained, or an empty string. */
    public String nameProblem(ModelClass modelClass) {
        if (!modelClass.isNamed()) return "";
        if (CanonicalCodec.isBlank(modelClass.name)) {
            return modelClass.isNew()
                    ? "Enter a class name or remove this row."
                    : "Enter a class name.";
        }
        String syntax = nameSyntaxProblem(modelClass.name);
        if (!syntax.isEmpty()) return syntax;
        return isNameUsed(modelClass.name, modelClass) ? NAME_USED : "";
    }

    /**
     * The first new class that no found QuPath class trains. It would have no examples, so training
     * is blocked until it is assigned or removed. Empty while no class has been found.
     */
    public Optional<ModelClass> unusedNewClass() {
        if (qupathClasses.isEmpty()) return Optional.empty();
        return active().classes.stream()
                .filter(modelClass -> modelClass.isNew() && !isTrained(modelClass))
                .findFirst();
    }

    /**
     * Kept, predicted classes that no found QuPath class trains. Training may proceed, but such
     * objects inside training ROIs are taught as background.
     */
    public List<ModelClass> untrainedLearnedClasses() {
        if (qupathClasses.isEmpty() || replacing || !sourceHasClasses()) return List.of();
        return kept.classes.stream()
                .filter(
                        modelClass ->
                                modelClass.isPredicted()
                                        && modelClass.isLearned()
                                        && !isTrained(modelClass))
                .toList();
    }

    /** Join names as "A", "A and B" or "A, B, and C". */
    public static String sentenceList(List<String> names, String conjunction) {
        if (names.size() < 3) return String.join(" " + conjunction + " ", names);
        return String.join(", ", names.subList(0, names.size() - 1))
                + ", "
                + conjunction
                + " "
                + names.getLast();
    }

    private ClassList active() {
        return replacing ? replacement : kept;
    }

    /**
     * A new class list. It keeps the structural background channel and starts with the source's
     * training-only classes, which the researcher may rename or remove; predicted classes start
     * empty.
     */
    private ClassList replacementList() {
        var list = new ClassList();
        if (sourceHasClasses()) {
            source.classChannels().stream()
                    .filter(channel -> !PREDICTED_ROLE.equals(channel.role()))
                    .forEach(
                            channel ->
                                    list.classes.add(
                                            new ModelClass(
                                                    channel.role(), channel.pathClass(), null)));
        } else {
            list.classes.add(new ModelClass(BACKGROUND_ROLE, null, null));
        }
        chooseDefaults(list);
        return list;
    }

    private void chooseDefaults(ClassList list) {
        for (String name : qupathClasses) {
            if (!list.trainAs.containsKey(name)) list.trainAs.put(name, defaultChoice(list, name));
        }
    }

    /**
     * What a QuPath class trains until the researcher chooses for it.
     *
     * <p>Under the first choice it trains the model class of its own name, and is background when
     * there is none. A new class list follows what the class does under the first choice:
     * background stays background, and a class the researcher moved to another model class needs an
     * explicit choice. Only a class still on its default trains the new list's class of its name.
     */
    private TrainAs defaultChoice(ClassList list, String qupathClass) {
        TrainAs sameName = classNamed(list, qupathClass);
        if (list == kept) return sameName != null ? sameName : FixedChoice.BACKGROUND;
        TrainAs keptDefault = defaultChoice(kept, qupathClass);
        TrainAs keptChoice = kept.trainAs.getOrDefault(qupathClass, keptDefault);
        if (keptChoice == keptDefault && sameName != null) return sameName;
        return keptChoice == FixedChoice.BACKGROUND
                ? FixedChoice.BACKGROUND
                : FixedChoice.NOT_CHOSEN;
    }

    private static ModelClass classNamed(ClassList list, String name) {
        return list.classes.stream()
                .filter(modelClass -> modelClass.isNamed() && modelClass.name.equals(name))
                .findFirst()
                .orElse(null);
    }

    private ModelOutput target() {
        var list = active();
        if (outlinesOnly()) {
            return new ModelOutput(source.type(), List.of(list.classes.getFirst().name), List.of());
        }
        var channels = new ArrayList<ClassChannel>();
        for (var modelClass : list.classes) {
            channels.add(
                    new ClassChannel(
                            channels.size(),
                            modelClass.role,
                            modelClass.isNamed() ? modelClass.name : null));
        }
        return new ModelOutput(outputType(), List.of(), channels);
    }

    private void requireTarget() {
        String message = targetProblem();
        if (!message.isEmpty()) throw new IllegalArgumentException(message);
    }

    private List<String> unchosenQuPathClasses() {
        return qupathClasses.stream()
                .filter(name -> trainAs(name) == FixedChoice.NOT_CHOSEN)
                .toList();
    }

    private boolean isTrained(ModelClass modelClass) {
        return qupathClasses.stream().anyMatch(name -> trainAs(name) == modelClass);
    }

    private boolean isNameUsed(String name, ModelClass except) {
        return active().classes.stream()
                .anyMatch(other -> other != except && other.isNamed() && other.name.equals(name));
    }

    private static String nameSyntaxProblem(String name) {
        if (CanonicalCodec.isBlank(name)) return "Enter a class name.";
        try {
            CanonicalCodec.requirePathClassName(name, "Class name");
        } catch (IllegalArgumentException error) {
            return "Enter a valid QuPath class name (for subclasses, use Parent: Child).";
        }
        return "";
    }

    /** One choice's model classes and what each QuPath class trains under that choice. */
    private static final class ClassList {
        /** Class channels in output order, including the unnamed background channel. */
        private final List<ModelClass> classes = new ArrayList<>();

        /** QuPath class name to Background, a model class, or the request to choose one. */
        private final Map<String, TrainAs> trainAs = new LinkedHashMap<>();
    }
}

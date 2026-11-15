package qupath.ext.histoforge.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.ColorDeconvolutionStains.DefaultColorDeconvolutionStains;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.plugins.workflow.ScriptableWorkflowStep;
import qupath.lib.plugins.workflow.WorkflowStep;

/** Owns persistence-stable semantic digests for QuPath stains and workflow history. */
final class StainWorkflowSemanticDigester {

    private static final int MAX_STAIN_PERSISTENCE_STATES = 65_536;

    private StainWorkflowSemanticDigester() {}

    /** Hash the exact eventual state of QuPath's public stain persistence projection. */
    static String stainsSemanticDigest(ColorDeconvolutionStains stains) {
        String semantic = canonicalPersistedStains(stains);
        return CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(semantic));
    }

    /** Hash workflow history without QuPath's read-time image-state bookkeeping entries. */
    static String workflowSemanticDigest(ImageData<?> imageData) {
        List<WorkflowStep> steps = imageData.getHistoryWorkflow().getSteps();
        List<WorkflowStep> readPrefix = generatedReadPrefix(imageData.getImageType());
        int first = 0;
        while (!readPrefix.isEmpty() && startsWith(steps, first, readPrefix)) {
            first += readPrefix.size();
        }

        String currentStainSemantic =
                canonicalPersistedStains(imageData.getColorDeconvolutionStains());
        int last = steps.size();
        while (last > first
                && isGeneratedCurrentStainStep(steps.get(last - 1), currentStainSemantic)) {
            last--;
        }

        JsonArray semanticSteps = new JsonArray();
        for (int index = first; index < last; index++) {
            semanticSteps.add(workflowStepSemanticState(steps.get(index), index - first));
        }
        JsonObject preimage = new JsonObject();
        preimage.addProperty("schema_version", 1);
        preimage.add("steps", semanticSteps);
        return CanonicalCodec.canonicalDigest(preimage);
    }

    /**
     * Return one representative for every state linked by repeated QuPath 0.6 save/read cycles.
     *
     * <p>QuPath writes 32 decimal places and renormalizes each vector on read. Binary64 rounding
     * can produce a transient sequence or a two-cycle rather than one fixed object. Every point on
     * that deterministic orbit reaches the same eventual cycle, so its lexicographically smallest
     * saved form is persistence-invariant without discarding QuPath-representable stain precision.
     */
    private static String canonicalPersistedStains(ColorDeconvolutionStains stains) {
        if (stains == null) {
            return "";
        }
        String current = ColorDeconvolutionStains.getColorDeconvolutionStainsAsString(stains, 32);
        Map<String, Integer> firstIndex = new HashMap<>();
        List<String> orbit = new ArrayList<>();
        while (true) {
            Integer cycleStart = firstIndex.get(current);
            if (cycleStart != null) {
                return orbit.subList(cycleStart, orbit.size()).stream()
                        .min(String::compareTo)
                        .orElseThrow();
            }
            if (orbit.size() == MAX_STAIN_PERSISTENCE_STATES) {
                throw new IllegalArgumentException(
                        "QuPath stain persistence did not repeat within "
                                + MAX_STAIN_PERSISTENCE_STATES
                                + " projections");
            }
            firstIndex.put(current, orbit.size());
            orbit.add(current);
            ColorDeconvolutionStains projected =
                    ColorDeconvolutionStains.parseColorDeconvolutionStainsArg(current);
            if (projected == null) {
                throw new IllegalArgumentException("QuPath could not parse its saved stain state");
            }
            current = ColorDeconvolutionStains.getColorDeconvolutionStainsAsString(projected, 32);
        }
    }

    private static JsonObject workflowStepSemanticState(WorkflowStep step, int index) {
        if (step == null) {
            throw new IllegalArgumentException("workflow step[" + index + "] must not be null");
        }
        JsonObject state = new JsonObject();
        state.addProperty("type", step.getClass().getName());
        state.addProperty("name", step.getName());
        if (step instanceof ScriptableWorkflowStep scriptable) {
            state.addProperty("script", scriptable.getScript());
        } else {
            state.add("script", JsonNull.INSTANCE);
        }

        Map<String, ?> parameters = step.getParameterMap();
        if (parameters == null) {
            throw new IllegalArgumentException(
                    "workflow step[" + index + "] parameter map must not be null");
        }
        Map<String, Object> sortedParameters = new TreeMap<>();
        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException(
                        "workflow step[" + index + "] parameter key must not be null");
            }
            sortedParameters.put(entry.getKey(), entry.getValue());
        }
        JsonObject parameterDigests = new JsonObject();
        sortedParameters.forEach(
                (key, value) ->
                        parameterDigests.addProperty(
                                key,
                                ImageDataRevisionDigester.serializedComponentDigest(
                                        value,
                                        "workflow step[" + index + "] parameter[" + key + "]")));
        state.add("parameter_value_serialization_sha256", parameterDigests);
        return state;
    }

    private static List<WorkflowStep> generatedReadPrefix(ImageData.ImageType type) {
        if (type == ImageData.ImageType.UNSET) {
            return List.of();
        }
        List<WorkflowStep> prefix = new ArrayList<>();
        prefix.add(
                new DefaultScriptableWorkflowStep(
                        "Set image type",
                        Map.of("Image type", type),
                        "setImageType('" + type.name() + "')"));
        ColorDeconvolutionStains defaultStains = defaultStains(type);
        if (defaultStains != null) {
            prefix.add(generatedStainStep(defaultStains));
        }
        return prefix;
    }

    private static ColorDeconvolutionStains defaultStains(ImageData.ImageType type) {
        return switch (type) {
            case BRIGHTFIELD_H_E ->
                    ColorDeconvolutionStains.makeDefaultColorDeconvolutionStains(
                            DefaultColorDeconvolutionStains.H_E);
            case BRIGHTFIELD_H_DAB, BRIGHTFIELD_OTHER ->
                    ColorDeconvolutionStains.makeDefaultColorDeconvolutionStains(
                            DefaultColorDeconvolutionStains.H_DAB);
            default -> null;
        };
    }

    private static WorkflowStep generatedStainStep(ColorDeconvolutionStains stains) {
        String argument = ColorDeconvolutionStains.getColorDeconvolutionStainsAsString(stains, 32);
        return new DefaultScriptableWorkflowStep(
                "Set color deconvolution stains",
                GeneralTools.parseArgStringValues(argument),
                "setColorDeconvolutionStains('" + argument + "');");
    }

    private static boolean isGeneratedCurrentStainStep(
            WorkflowStep step, String currentStainSemantic) {
        if (step == null || step.getClass() != DefaultScriptableWorkflowStep.class) {
            return false;
        }
        String script = ((ScriptableWorkflowStep) step).getScript();
        String prefix = "setColorDeconvolutionStains('";
        String suffix = "');";
        if (!"Set color deconvolution stains".equals(step.getName())
                || script == null
                || !script.startsWith(prefix)
                || !script.endsWith(suffix)) {
            return false;
        }
        String argument = script.substring(prefix.length(), script.length() - suffix.length());
        try {
            ColorDeconvolutionStains parsed =
                    ColorDeconvolutionStains.parseColorDeconvolutionStainsArg(argument);
            return parsed != null
                    && GeneralTools.parseArgStringValues(argument).equals(step.getParameterMap())
                    && generatedStainStep(parsed)
                            .getParameterMap()
                            .keySet()
                            .equals(step.getParameterMap().keySet())
                    && canonicalPersistedStains(parsed).equals(currentStainSemantic);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean startsWith(
            List<WorkflowStep> steps, int first, List<WorkflowStep> prefix) {
        if (first + prefix.size() > steps.size()) {
            return false;
        }
        for (int index = 0; index < prefix.size(); index++) {
            if (!prefix.get(index).equals(steps.get(first + index))) {
                return false;
            }
        }
        return true;
    }
}

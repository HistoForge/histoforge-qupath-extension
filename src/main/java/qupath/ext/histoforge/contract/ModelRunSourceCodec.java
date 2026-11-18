package qupath.ext.histoforge.contract;

import static qupath.ext.histoforge.contract.StrictJson.requireExactObject;
import static qupath.ext.histoforge.contract.StrictJson.requiredString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact uploaded images assigned to the backend's accepted processing units. */
public final class ModelRunSourceCodec {
    private ModelRunSourceCodec() {}

    /** The file for one unit; accepted assignments never change when later files arrive. */
    public record Binding(int inputId, int unitId, SourceCaptureCodec.Binding source) {
        public Binding {
            CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            CanonicalCodec.requireNonnegativeInt32(unitId, "unit_id");
            Objects.requireNonNull(source, "source");
        }
    }

    /** Readback remains available after failure, including files with no completed computation. */
    public record Delivery(
            String modelRunId,
            String requestDigest,
            boolean active,
            Map<Integer, ModelExecutionPlanCodec.ModelExecutionPlan> executionPlans,
            List<Binding> sourceBindings) {
        public Delivery {
            CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id");
            CanonicalCodec.requireSha256(requestDigest, "request_digest");
            executionPlans = Map.copyOf(executionPlans);
            sourceBindings = requireOrdered(sourceBindings);
        }
    }

    public static Binding parseBinding(JsonElement value) {
        var object =
                requireExactObject(
                        value, Set.of("input_id", "unit_id", "source"), "model run source binding");
        return new Binding(
                CanonicalCodec.requireNonnegativeInt32(object.get("input_id"), "input_id"),
                CanonicalCodec.requireNonnegativeInt32(object.get("unit_id"), "unit_id"),
                SourceCaptureCodec.parseBinding(object.get("source")));
    }

    public static List<Binding> parseBindings(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("source_bindings must be an array");
        }
        List<Binding> bindings = new ArrayList<>();
        for (var item : value.getAsJsonArray()) bindings.add(parseBinding(item));
        return requireOrdered(bindings);
    }

    /** Validate the accepted plan before QuPath reads or uploads its requested pixels. */
    public static Delivery validateDelivery(
            JsonElement value,
            ModelRunRequestCodec.ModelRunRequest request,
            ExecutionLimitsCodec.ModelRunLimits limits) {
        var object =
                requireExactObject(
                        value,
                        Set.of(
                                "model_run_id",
                                "request_digest",
                                "active",
                                "execution_plans",
                                "source_bindings"),
                        "model run source delivery");
        var active = object.get("active");
        if (!active.isJsonPrimitive() || !active.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("active must be a boolean");
        }
        var planValues = object.get("execution_plans");
        if (!planValues.isJsonArray()) {
            throw new IllegalArgumentException("execution_plans must be an array");
        }
        Map<Integer, ModelExecutionPlanCodec.ModelExecutionPlan> plans = new HashMap<>();
        int previous = -1;
        for (var entry : planValues.getAsJsonArray()) {
            var item =
                    requireExactObject(entry, Set.of("input_id", "plan"), "input execution plan");
            int inputId = CanonicalCodec.requireNonnegativeInt32(item.get("input_id"), "input_id");
            if (inputId <= previous) {
                throw new IllegalArgumentException("execution_plans must use unique input order");
            }
            previous = inputId;
            plans.put(inputId, ModelExecutionPlanCodec.parse(item.get("plan")));
        }
        var delivery =
                new Delivery(
                        requiredString(object, "model_run_id"),
                        requiredString(object, "request_digest"),
                        active.getAsBoolean(),
                        plans,
                        parseBindings(object.get("source_bindings")));
        if (!delivery.modelRunId().equals(request.modelRunId())
                || !delivery.requestDigest().equals(ModelRunRequestCodec.requestDigest(request))
                || !plans.equals(ModelRunPublicationCodec.buildExpectedPlans(request, limits))) {
            throw new IllegalArgumentException(
                    "source delivery does not match the accepted request and plans");
        }
        validateBindings(delivery.sourceBindings(), request, plans);
        return delivery;
    }

    public static void validateBindings(
            List<Binding> bindings,
            ModelRunRequestCodec.ModelRunRequest request,
            Map<Integer, ModelExecutionPlanCodec.ModelExecutionPlan> plans) {
        requireOrdered(bindings);
        Map<Integer, ModelRunRequestCodec.ModelRunInput> inputs = new HashMap<>();
        request.inputs().forEach(input -> inputs.put(input.inputId(), input));
        for (var binding : bindings) {
            var input = inputs.get(binding.inputId());
            var plan = plans.get(binding.inputId());
            if (input == null || plan == null) {
                throw new IllegalArgumentException(
                        "source binding input_id is absent from the request");
            }
            if (!(input.rasterInput().sourceView() instanceof SourceCaptureCodec.View view)) {
                throw new IllegalArgumentException(
                        "sealed-file inputs cannot accept source bindings");
            }
            binding.source().requireCapture(view.capture());
            var rectangle = processingRectangle(input.rasterInput(), plan, binding.unitId());
            SourceViewGeometry.requiredCapturedSourcePixelRect(
                            view, input.rasterInput().frame().transform(), rectangle)
                    .ifPresent(
                            required ->
                                    SourceCaptureCodec.requireContained(
                                            required, binding.source().sourceRectAtLevel()));
        }
    }

    /** Model-grid rectangle to provide; finite inputs retain their normal request protocol. */
    public static SourceIdentityCodec.IntRect processingRectangle(
            RasterInputCodec.ModelRasterInput input,
            ModelExecutionPlanCodec.ModelExecutionPlan plan,
            int unitId) {
        if (unitId < 0 || unitId >= ModelExecutionPlanCodec.unitCount(plan)) {
            throw new IllegalArgumentException("source binding unit_id is absent from the plan");
        }
        if (plan instanceof ModelExecutionPlanCodec.GlobalDecodePlan) {
            throw new IllegalArgumentException(
                    "global-decode does not support incremental image delivery");
        }
        if (plan instanceof ModelExecutionPlanCodec.CoreHaloPlan core) {
            return core.windows().get(unitId).inputRect();
        }
        var raster = input.frame().transform().raster();
        return new SourceIdentityCodec.IntRect(0, 0, raster.width(), raster.height());
    }

    public static Optional<SourceCaptureCodec.Binding> unitBinding(
            List<Binding> bindings, int inputId, int unitId) {
        return bindings.stream()
                .filter(binding -> binding.inputId() == inputId && binding.unitId() == unitId)
                .map(Binding::source)
                .findFirst();
    }

    public static List<SourceCaptureCodec.UnitBinding> inputBindings(
            List<Binding> bindings, int inputId) {
        return bindings.stream()
                .filter(binding -> binding.inputId() == inputId)
                .map(
                        binding ->
                                new SourceCaptureCodec.UnitBinding(
                                        binding.unitId(), binding.source()))
                .toList();
    }

    public static JsonObject toJson(Binding binding) {
        var value = new JsonObject();
        value.addProperty("input_id", binding.inputId());
        value.addProperty("unit_id", binding.unitId());
        value.add("source", SourceCaptureCodec.toJson(binding.source()));
        return value;
    }

    public static JsonArray toJson(List<Binding> bindings) {
        var value = new JsonArray();
        bindings.forEach(binding -> value.add(toJson(binding)));
        return value;
    }

    static List<Binding> requireOrdered(List<Binding> bindings) {
        var copy = List.copyOf(bindings);
        Binding previous = null;
        for (var binding : copy) {
            if (previous != null
                    && (binding.inputId() < previous.inputId()
                            || (binding.inputId() == previous.inputId()
                                    && binding.unitId() <= previous.unitId()))) {
                throw new IllegalArgumentException(
                        "source_bindings must be unique and ordered by input and unit");
            }
            previous = binding;
        }
        return copy;
    }
}

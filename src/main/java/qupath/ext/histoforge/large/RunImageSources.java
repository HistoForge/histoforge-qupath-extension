package qupath.ext.histoforge.large;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.StrictJson;

/**
 * Image uploads retained with a model request in the existing action recovery file. The failure
 * report remains recorded after acknowledgement so an explicit retry can distinguish a failed
 * upload from failed prediction.
 */
public record RunImageSources(
        List<Upload> uploads, Optional<GatewayErrorCodec.Error> failureReport) {

    public RunImageSources {
        uploads = List.copyOf(uploads);
        Objects.requireNonNull(failureReport, "failureReport");
        Upload previous = null;
        for (var upload : uploads) {
            if (previous != null
                    && (upload.inputId() < previous.inputId()
                            || upload.inputId() == previous.inputId()
                                    && upload.unitId() <= previous.unitId())) {
                throw new IllegalArgumentException(
                        "run image uploads must be unique and ordered by input and unit");
            }
            previous = upload;
        }
    }

    /**
     * One image assigned to a planned processing unit. Accepted means accepted by any run of this
     * exact request plan, including a previous attempt. Such bytes must never be replaced; current
     * run acceptance is checked independently with the backend before delivering more images.
     */
    public record Upload(
            int inputId,
            int unitId,
            LargeActionTypes.SourceUploadRef source,
            boolean bindingAccepted) {
        public Upload {
            CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            CanonicalCodec.requireNonnegativeInt32(unitId, "unit_id");
            Objects.requireNonNull(source, "source");
            if (source.ownedUploadFile().isEmpty()) {
                throw new IllegalArgumentException(
                        "a bounded upload requires a prepared image record");
            }
            if (bindingAccepted && source.sealedArtifact().isEmpty()) {
                throw new IllegalArgumentException(
                        "an accepted image binding requires a sealed source");
            }
        }
    }

    public static RunImageSources empty() {
        return new RunImageSources(List.of(), Optional.empty());
    }

    /** Source records share the action's existing cleanup and prepared-file accounting. */
    public static Stream<LargeActionTypes.SourceUploadRef> sources(
            LargeActionTypes.LargeActionHandle handle) {
        return Stream.concat(
                handle.source().stream(),
                handle.steps().stream()
                        .flatMap(step -> step.runs().stream())
                        .flatMap(run -> run.imageSources().stream())
                        .flatMap(images -> images.uploads().stream())
                        .map(Upload::source));
    }

    static RunImageSources parse(JsonElement value) {
        var object = StrictJson.requireObject(value, "run image sources");
        if (!object.has("uploads")
                || !Set.of("uploads", "failure_report").containsAll(object.keySet())) {
            throw new IllegalArgumentException("run image sources has missing or unknown fields");
        }
        var array = object.get("uploads");
        if (!array.isJsonArray()) throw new IllegalArgumentException("uploads must be an array");
        List<Upload> uploads = new ArrayList<>();
        for (var item : array.getAsJsonArray()) {
            var upload =
                    StrictJson.requireExactObject(
                            item,
                            Set.of("input_id", "unit_id", "source", "binding_accepted"),
                            "run image upload");
            var accepted = upload.get("binding_accepted");
            if (!accepted.isJsonPrimitive() || !accepted.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("binding_accepted must be a boolean");
            }
            uploads.add(
                    new Upload(
                            CanonicalCodec.requireNonnegativeInt32(
                                    upload.get("input_id"), "input_id"),
                            CanonicalCodec.requireNonnegativeInt32(
                                    upload.get("unit_id"), "unit_id"),
                            LargeActionCodec.parseSource(upload.get("source")),
                            accepted.getAsBoolean()));
        }
        return new RunImageSources(
                uploads,
                object.has("failure_report")
                        ? Optional.of(GatewayErrorCodec.parse(object.get("failure_report")))
                        : Optional.empty());
    }

    static JsonObject toJson(RunImageSources images) {
        var value = new JsonObject();
        var uploads = new JsonArray();
        for (var upload : images.uploads()) {
            var item = new JsonObject();
            item.addProperty("input_id", upload.inputId());
            item.addProperty("unit_id", upload.unitId());
            item.add("source", LargeActionCodec.toJson(upload.source()));
            item.addProperty("binding_accepted", upload.bindingAccepted());
            uploads.add(item);
        }
        value.add("uploads", uploads);
        images.failureReport()
                .ifPresent(
                        failure -> value.add("failure_report", GatewayErrorCodec.toJson(failure)));
        return value;
    }
}

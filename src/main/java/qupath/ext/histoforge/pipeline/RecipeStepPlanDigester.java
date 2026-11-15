package qupath.ext.histoforge.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.objects.PathObject;

/** Sole canonical owner of persisted large-action parent and input-plan digests. */
public final class RecipeStepPlanDigester {

    private RecipeStepPlanDigester() {}

    /** One current source parent after upstream model and script barriers. */
    public record ParentEvidence(
            ExecutionScopeCodec.RegionKey region,
            SourceIdentityCodec.ImagePlaneIndex plane,
            String parentId,
            String pathClass,
            Geometry geometrySlide,
            Optional<ExecutionScopeCodec.OutputKey> logicalParentOutput) {
        public ParentEvidence {
            region = Objects.requireNonNull(region, "region");
            plane = Objects.requireNonNull(plane, "plane");
            parentId = CanonicalCodec.requireCanonicalUuid(parentId, "parent_id");
            pathClass = CanonicalCodec.requirePathClassName(pathClass, "path_class");
            geometrySlide = canonicalGeometry(geometrySlide);
            logicalParentOutput =
                    Objects.requireNonNull(logicalParentOutput, "logicalParentOutput");
        }

        @Override
        public Geometry geometrySlide() {
            return geometrySlide.copy();
        }
    }

    /** One retained canonical request and its deterministic step-local shard boundary. */
    public record RequestShard(int shardIndex, ModelRunRequestCodec.ModelRunRequest request) {
        public RequestShard {
            shardIndex = CanonicalCodec.requireNonnegativeInt32(shardIndex, "shard_index");
            request = Objects.requireNonNull(request, "request");
        }
    }

    /** Capture the exact parent evidence visible to a region-gated child step. */
    public static List<ParentEvidence> captureParents(
            ExecutionScopeCodec.TargetRegion target, List<PathObject> parents) {
        ExecutionScopeCodec.TargetRegion region = Objects.requireNonNull(target, "target");
        List<ParentEvidence> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (PathObject parent : Objects.requireNonNull(parents, "parents")) {
            PathObject object = Objects.requireNonNull(parent, "parent");
            if (object.getID() == null
                    || object.getROI() == null
                    || object.getPathClass() == null) {
                throw new IllegalArgumentException(
                        "source parent requires UUID, area ROI, and PathClass");
            }
            String parentId = object.getID().toString();
            if (!ids.add(parentId)) {
                throw new IllegalArgumentException("source parents contain a duplicate UUID");
            }
            SourceIdentityCodec.ImagePlaneIndex plane =
                    new SourceIdentityCodec.ImagePlaneIndex(
                            object.getROI().getZ(), object.getROI().getT());
            if (!plane.equals(region.plane())) {
                throw new IllegalArgumentException("source parent lies on another image plane");
            }
            Optional<ExecutionScopeCodec.OutputKey> producer =
                    object.hasMetadata()
                            ? ProvenanceCodec.readMetadata(object.getMetadata())
                                    .map(ProvenanceCodec.ObjectProvenance::output)
                            : Optional.empty();
            result.add(
                    new ParentEvidence(
                            region.key(),
                            plane,
                            parentId,
                            object.getPathClass().toString(),
                            NativeRoiGeometry.convert(object.getROI()),
                            producer));
        }
        result.sort(Comparator.comparing(ParentEvidence::parentId));
        return List.copyOf(result);
    }

    /** Digest the exact crop-input contract and canonical current source-parent list. */
    public static String parentPlanDigest(
            ModuleSnapshot.ModelInputSnapshot input, List<ParentEvidence> parents) {
        ModuleSnapshot.ModelInputSnapshot child = Objects.requireNonNull(input, "input");
        if (child.type() != ModuleSnapshot.ModelInputType.CROP || child.framing().isEmpty()) {
            throw new IllegalArgumentException("parent plan requires one crop input contract");
        }
        List<ParentEvidence> normalized = normalizedParents(parents);
        JsonObject preimage = new JsonObject();
        preimage.add("child_input", inputJson(child));
        JsonArray parentValues = new JsonArray();
        normalized.forEach(parent -> parentValues.add(parentJson(parent)));
        preimage.add("parents", parentValues);
        return CanonicalCodec.canonicalDigest(preimage);
    }

    /** Digest ordered logical inputs, plan family, and explicit request-shard boundaries. */
    public static String inputPlanDigest(List<RequestShard> shards) {
        List<RequestShard> normalized = normalizedShards(shards);
        JsonObject preimage = new JsonObject();
        JsonArray shardValues = new JsonArray();
        for (RequestShard shard : normalized) {
            ModelRunRequestCodec.ModelRunRequest request = shard.request();
            JsonObject value = new JsonObject();
            value.addProperty("shard_index", shard.shardIndex());
            if (request.expectedProfile().isEmpty()) {
                value.addProperty("plan_family", "finite-plan");
            } else {
                value.addProperty("plan_family", "profile");
                value.addProperty(
                        "expected_profile_digest",
                        profileDigest(request.expectedProfile().orElseThrow()));
            }
            request.haloPxOverride().ifPresent(halo -> value.addProperty("halo_px_override", halo));
            JsonArray inputs = new JsonArray();
            for (ModelRunRequestCodec.ModelRunInput input : request.inputs()) {
                JsonObject item = new JsonObject();
                item.addProperty("input_id", input.inputId());
                item.addProperty("output_generation_id", input.outputGenerationId());
                item.add("output", ExecutionScopeCodec.toJson(input.output()));
                item.addProperty("raster_input_digest", input.rasterInput().inputDigest());
                inputs.add(item);
            }
            value.add("inputs", inputs);
            shardValues.add(value);
        }
        preimage.add("shards", shardValues);
        return CanonicalCodec.canonicalDigest(preimage);
    }

    private static List<ParentEvidence> normalizedParents(List<ParentEvidence> parents) {
        List<ParentEvidence> result =
                new ArrayList<>(List.copyOf(Objects.requireNonNull(parents, "parents")));
        result.sort(Comparator.comparing(ParentEvidence::parentId));
        Set<String> ids = new HashSet<>();
        for (ParentEvidence parent : result) {
            if (!ids.add(Objects.requireNonNull(parent, "parent").parentId())) {
                throw new IllegalArgumentException("parent plan contains a duplicate UUID");
            }
        }
        return List.copyOf(result);
    }

    private static List<RequestShard> normalizedShards(List<RequestShard> shards) {
        List<RequestShard> result = List.copyOf(Objects.requireNonNull(shards, "shards"));
        Set<Integer> inputIds = new LinkedHashSet<>();
        Set<String> generationIds = new LinkedHashSet<>();
        Set<ExecutionScopeCodec.OutputKey> outputs = new LinkedHashSet<>();
        InferenceIdentityCodec.ModelStepIdentity producer = null;
        for (int index = 0; index < result.size(); index++) {
            RequestShard shard = Objects.requireNonNull(result.get(index), "request shard");
            if (shard.shardIndex() != index) {
                throw new IllegalArgumentException(
                        "input-plan shards must be contiguous from zero");
            }
            ModelRunRequestCodec.ModelRunRequest request = shard.request();
            if (producer == null) {
                producer = request.producer();
            } else if (!producer.equals(request.producer())) {
                throw new IllegalArgumentException(
                        "input-plan shards must belong to one model step");
            }
            for (ModelRunRequestCodec.ModelRunInput input : request.inputs()) {
                if (!inputIds.add(input.inputId())
                        || !generationIds.add(input.outputGenerationId())
                        || !outputs.add(input.output())) {
                    throw new IllegalArgumentException("input-plan inputs must be globally unique");
                }
            }
        }
        return result;
    }

    private static JsonObject inputJson(ModuleSnapshot.ModelInputSnapshot input) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "crop");
        value.addProperty("target_mpp", input.targetMpp());
        input.fieldProfile()
                .ifPresent(
                        profile -> value.add("field_profile", TrainingTaskCodec.toJson(profile)));
        JsonObject framing = new JsonObject();
        framing.addProperty("padding_model_px", input.framing().orElseThrow().paddingModelPx());
        value.add("framing", framing);
        return value;
    }

    private static JsonObject parentJson(ParentEvidence parent) {
        JsonObject value = new JsonObject();
        value.add("region", ExecutionScopeCodec.toJson(parent.region()));
        value.add("plane", SourceIdentityCodec.toJson(parent.plane()));
        value.addProperty("parent_id", parent.parentId());
        value.addProperty("path_class", parent.pathClass());
        value.add("geometry_slide", geometryJson(parent.geometrySlide()));
        parent.logicalParentOutput()
                .ifPresent(
                        output ->
                                value.add(
                                        "logical_parent_output",
                                        ExecutionScopeCodec.toJson(output)));
        return value;
    }

    private static JsonObject geometryJson(Geometry geometry) {
        JsonObject value = new JsonObject();
        value.addProperty("type", geometry.getGeometryType());
        value.add("coordinates", CanonicalGeometryCodec.canonicalCoordinates(geometry));
        return value;
    }

    private static Geometry canonicalGeometry(Geometry geometry) {
        return CanonicalGeometryCodec.parseGeoJson(geometryJson(Objects.requireNonNull(geometry)));
    }

    private static String profileDigest(LargeRegionProfileCodec.LargeRegionProfile profile) {
        if (profile instanceof LargeRegionProfileCodec.GlobalDecodeProfile global) {
            return global.profileDigest();
        }
        return ((LargeRegionProfileCodec.CoreHaloProfile) profile).profileDigest();
    }
}

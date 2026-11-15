package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Deterministic persistent object IDs derived from canonical HistoForge identity preimages. */
public final class ObjectIdentityCodec {

    private static final UUID HISTOFORGE_OBJECT_NAMESPACE =
            UUID.fromString("b5a8f252-9659-5f0a-866b-d127de8fad9b");

    private ObjectIdentityCodec() {}

    /** Derive the persistent object UUID for one accepted backend feature. */
    public static String candidateId(
            String outputGenerationId, int inputId, String backendFeatureId) {
        JsonArray preimage = new JsonArray();
        preimage.add(
                CanonicalCodec.requireCanonicalUuid(outputGenerationId, "output_generation_id"));
        preimage.add(CanonicalCodec.requireNonnegativeInt32(inputId, "input_id"));
        preimage.add(CanonicalCodec.requireSha256(backendFeatureId, "backend_feature_id"));
        return uuidV5(HISTOFORGE_OBJECT_NAMESPACE, CanonicalCodec.canonicalBytes(preimage))
                .toString();
    }

    /** Derive the stable UUID of the detached target annotation bound to one script invocation. */
    public static String scriptTargetId(
            String imageId, ExecutionScopeCodec.RegionKey region, String targetGeometryDigest) {
        JsonArray preimage = new JsonArray();
        preimage.add("script-target");
        preimage.add(CanonicalCodec.requireNonblank(imageId, "image_id"));
        preimage.add(ExecutionScopeCodec.toJson(region));
        preimage.add(CanonicalCodec.requireSha256(targetGeometryDigest, "target_geometry_digest"));
        return uuidV5(HISTOFORGE_OBJECT_NAMESPACE, CanonicalCodec.canonicalBytes(preimage))
                .toString();
    }

    private static UUID uuidV5(UUID namespace, byte[] name) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM does not provide SHA-1 for UUIDv5", error);
        }
        ByteBuffer namespaceBytes = ByteBuffer.allocate(16);
        namespaceBytes.putLong(namespace.getMostSignificantBits());
        namespaceBytes.putLong(namespace.getLeastSignificantBits());
        digest.update(namespaceBytes.array());
        byte[] hash = digest.digest(name);
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer result = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(result.getLong(), result.getLong());
    }
}

package qupath.ext.histoforge.cloud;

import java.io.IOException;
import java.util.Objects;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/** One immutable S3 object version used by every read from an open cloud slide. */
public record S3ObjectSnapshot(
        long contentLength, SourceIdentityCodec.SourceContentVersion contentVersion) {

    private static final String MUTABLE_NULL_VERSION_ID = "null";

    public S3ObjectSnapshot {
        if (contentLength <= 0) {
            throw new IllegalArgumentException("S3 object content length must be positive");
        }
        contentVersion = Objects.requireNonNull(contentVersion, "contentVersion");
        if (!(contentVersion instanceof SourceIdentityCodec.S3VersionId)
                && !(contentVersion instanceof SourceIdentityCodec.S3ETag)) {
            throw new IllegalArgumentException("S3 object snapshot requires an S3 content version");
        }
        if (contentVersion instanceof SourceIdentityCodec.S3VersionId version
                && MUTABLE_NULL_VERSION_ID.equals(version.value())) {
            throw new IllegalArgumentException("S3 null version is not immutable");
        }
    }

    /** Capture the current object before any metadata or pixel bytes are read. */
    public static S3ObjectSnapshot capture(S3Client s3, String bucket, String key)
            throws IOException {
        Objects.requireNonNull(s3, "s3");
        String exactBucket = requireNonblank(bucket, "S3 bucket");
        String exactKey = requireNonblank(key, "S3 key");
        try {
            HeadObjectResponse response =
                    s3.headObject(
                            HeadObjectRequest.builder().bucket(exactBucket).key(exactKey).build());
            return fromHead(response);
        } catch (RuntimeException error) {
            throw new IOException(
                    "Failed to capture S3 object version: " + exactBucket + "/" + exactKey, error);
        }
    }

    /** Build one range request pinned to the captured version or conditional ETag. */
    GetObjectRequest rangeRequest(String bucket, String key, String range) {
        var builder =
                GetObjectRequest.builder()
                        .bucket(requireNonblank(bucket, "S3 bucket"))
                        .key(requireNonblank(key, "S3 key"))
                        .range(requireNonblank(range, "S3 byte range"));
        switch (contentVersion) {
            case SourceIdentityCodec.S3VersionId version -> builder.versionId(version.value());
            case SourceIdentityCodec.S3ETag etag -> builder.ifMatch(etag.quotedValue());
            default -> throw new IllegalStateException("unsupported S3 content version");
        }
        return builder.build();
    }

    /** Require the captured immutable version to remain addressable with the same length. */
    public void requireCurrent(S3Client s3, String bucket, String key) throws IOException {
        Objects.requireNonNull(s3, "s3");
        String exactBucket = requireNonblank(bucket, "S3 bucket");
        String exactKey = requireNonblank(key, "S3 key");
        var builder = HeadObjectRequest.builder().bucket(exactBucket).key(exactKey);
        switch (contentVersion) {
            case SourceIdentityCodec.S3VersionId version -> builder.versionId(version.value());
            case SourceIdentityCodec.S3ETag etag -> builder.ifMatch(etag.quotedValue());
            default -> throw new IllegalStateException("unsupported S3 content version");
        }
        try {
            S3ObjectSnapshot current = fromHead(s3.headObject(builder.build()));
            if (current.contentLength != contentLength
                    || !contentVersion.equals(current.contentVersion)) {
                throw new IOException("captured S3 object version changed");
            }
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("captured S3 object version is no longer readable", error);
        }
    }

    private static S3ObjectSnapshot fromHead(HeadObjectResponse response) throws IOException {
        if (response == null
                || Boolean.TRUE.equals(response.deleteMarker())
                || response.contentLength() == null
                || response.contentLength() <= 0) {
            throw new IOException("S3 HEAD response does not describe a readable object");
        }
        try {
            String versionId = response.versionId();
            SourceIdentityCodec.SourceContentVersion version =
                    versionId != null
                                    && !versionId.isBlank()
                                    && !MUTABLE_NULL_VERSION_ID.equals(versionId)
                            ? new SourceIdentityCodec.S3VersionId(versionId)
                            : new SourceIdentityCodec.S3ETag(response.eTag());
            return new S3ObjectSnapshot(response.contentLength(), version);
        } catch (IllegalArgumentException error) {
            throw new IOException("S3 HEAD response lacks a stable object version", error);
        }
    }

    private static String requireNonblank(String value, String name) {
        String result = Objects.requireNonNull(value, name);
        if (result.isBlank()) {
            throw new IllegalArgumentException(name + " must be nonblank");
        }
        return result;
    }
}

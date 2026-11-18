package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.locationtech.jts.geom.Envelope;

/** Validate compressed feature files before reading individual objects for conversion. */
public final class FeatureStreamDecoder {

    private static final int RECORD_SEPARATOR = 0x1e;
    private static final int RECORD_TERMINATOR = '\n';
    private static final int BUFFER_BYTES = 64 * 1024;

    private FeatureStreamDecoder() {}

    /** Counts and coordinate bounds from complete validation; no feature geometry is retained. */
    public record ValidatedFeatureStream(
            long decodedBytes,
            long featureCount,
            long vertexCount,
            long maxRecordBytes,
            Envelope bounds,
            InferenceFeatureCodec.GeometryKind geometryKind) {
        public ValidatedFeatureStream {
            decodedBytes =
                    CanonicalCodec.requireNonnegativeSafeInteger(decodedBytes, "decoded_bytes");
            featureCount =
                    CanonicalCodec.requireNonnegativeSafeInteger(featureCount, "feature_count");
            vertexCount = CanonicalCodec.requireNonnegativeSafeInteger(vertexCount, "vertex_count");
            maxRecordBytes =
                    CanonicalCodec.requirePositiveSafeInteger(maxRecordBytes, "max_record_bytes");
            bounds = new Envelope(Objects.requireNonNull(bounds, "bounds"));
            geometryKind = Objects.requireNonNull(geometryKind, "geometryKind");
            if ((featureCount == 0) != bounds.isNull()) {
                throw new IllegalArgumentException("feature stream bounds disagree with its count");
            }
        }

        @Override
        public Envelope bounds() {
            return new Envelope(bounds);
        }

        /** Reject out-of-raster coordinates before any feature can be discarded by filtering. */
        public void requireInsideRaster(RasterInputCodec.RasterDescriptor raster) {
            Objects.requireNonNull(raster, "raster");
            if (geometryKind == InferenceFeatureCodec.GeometryKind.POINT) {
                RasterInputCodec.requirePointBoundsInsideRaster(bounds, raster, "feature stream");
            } else {
                RasterInputCodec.requireBoundsInsideRaster(bounds, raster, "feature stream");
            }
        }
    }

    /** Validate one complete file, retaining only counts and coordinate bounds. */
    public static ValidatedFeatureStream validate(
            InputStream compressed,
            FiniteInferenceCodec.ClientResultBudget budget,
            Set<String> declaredClasses,
            long expectedFeatureCount,
            long expectedVertexCount)
            throws IOException {
        return validate(
                compressed,
                budget,
                declaredClasses,
                expectedFeatureCount,
                expectedVertexCount,
                () -> false);
    }

    /**
     * Check framing, canonical records, classes, geometry, IDs and manifest counts before import.
     *
     * <p>The caller verifies the compressed file's exact length and SHA-256. ID validation retains
     * hashes and occurrence counts until the complete stream has been checked, not coordinate
     * strings, JSON records or the decoded polygon population. Bookkeeping grows with the admitted
     * record count; validation is not constant-memory.
     */
    public static ValidatedFeatureStream validate(
            InputStream compressed,
            FiniteInferenceCodec.ClientResultBudget budget,
            Set<String> declaredClasses,
            long expectedFeatureCount,
            long expectedVertexCount,
            BooleanSupplier shouldStop)
            throws IOException {
        return validate(
                compressed,
                budget,
                declaredClasses,
                expectedFeatureCount,
                expectedVertexCount,
                InferenceFeatureCodec.GeometryKind.POLYGONAL,
                shouldStop);
    }

    public static ValidatedFeatureStream validate(
            InputStream compressed,
            FiniteInferenceCodec.ClientResultBudget budget,
            Set<String> declaredClasses,
            long expectedFeatureCount,
            long expectedVertexCount,
            InferenceFeatureCodec.GeometryKind kind,
            BooleanSupplier shouldStop)
            throws IOException {
        Objects.requireNonNull(budget, "budget");
        long expectedFeatures =
                CanonicalCodec.requireNonnegativeSafeInteger(
                        expectedFeatureCount, "expected_feature_count");
        long expectedVertices =
                CanonicalCodec.requireNonnegativeSafeInteger(
                        expectedVertexCount, "expected_vertex_count");
        if (expectedFeatures > budget.maxFeatures() || expectedVertices > budget.maxVertices()) {
            throw new IllegalArgumentException("manifest feature counts exceed result budget");
        }

        var validator = new InferenceFeatureCodec.RecordValidator(declaredClasses, kind);
        long features = 0;
        long vertices = 0;
        Envelope bounds = new Envelope();
        try (var records =
                new RecordReader(
                        compressed,
                        budget.maxDecodedBytes(),
                        budget.maxRecordBytes(),
                        shouldStop)) {
            byte[] bytes;
            while ((bytes = records.next()) != null) {
                requireCount(++features, budget.maxFeatures(), "max_features");
                JsonElement parsed = CanonicalCodec.parseCanonicalJson(bytes);
                var feature = validator.validate(parsed, features - 1);
                vertices = Math.addExact(vertices, InferenceFeatureCodec.vertexCount(feature));
                requireCount(vertices, budget.maxVertices(), "max_vertices");
                bounds.expandToInclude(feature.bounds());
            }
            validator.finish(() -> requireRunning(shouldStop));
            requireCounts(features, vertices, expectedFeatures, expectedVertices);
            requireRunning(shouldStop);
            return new ValidatedFeatureStream(
                    records.decodedBytes,
                    features,
                    vertices,
                    budget.maxRecordBytes(),
                    bounds,
                    kind);
        }
    }

    /**
     * Read previously validated file bytes one feature at a time; see the overload's precondition.
     */
    public static void readValidated(
            InputStream compressed,
            ValidatedFeatureStream validated,
            Consumer<InferenceFeatureCodec.Feature> consumer)
            throws IOException {
        readValidated(compressed, validated, consumer, () -> false);
    }

    /**
     * Convert an already validated file without repeating canonicalization or whole-stream ID
     * checks.
     *
     * <p>The caller must verify that these are the exact size- and SHA-256-bound bytes used by
     * {@link #validate}; counts alone cannot establish that identity. Framing, gzip completion and
     * validated counts remain checked. Each feature belongs to the consumer; this reader does not
     * retain it. A failed or cancelled read must discard the caller's unfinished conversion.
     */
    public static void readValidated(
            InputStream compressed,
            ValidatedFeatureStream validated,
            Consumer<InferenceFeatureCodec.Feature> consumer,
            BooleanSupplier shouldStop)
            throws IOException {
        Objects.requireNonNull(validated, "validated");
        Objects.requireNonNull(consumer, "consumer");
        long features = 0;
        long vertices = 0;
        Envelope bounds = new Envelope();
        try (var records =
                new RecordReader(
                        compressed,
                        validated.decodedBytes(),
                        validated.maxRecordBytes(),
                        shouldStop)) {
            byte[] bytes;
            while ((bytes = records.next()) != null) {
                requireCount(++features, validated.featureCount(), "validated feature count");
                var value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                var feature =
                        InferenceFeatureCodec.readValidatedRecord(
                                value.getAsJsonObject(), bytes.length);
                vertices = Math.addExact(vertices, InferenceFeatureCodec.vertexCount(feature));
                requireCount(vertices, validated.vertexCount(), "validated vertex count");
                bounds.expandToInclude(feature.bounds());
                consumer.accept(feature);
            }
            requireCounts(features, vertices, validated.featureCount(), validated.vertexCount());
            if (records.decodedBytes != validated.decodedBytes()
                    || !bounds.equals(validated.bounds)) {
                throw new IllegalArgumentException(
                        "feature stream does not match its validation summary");
            }
            requireRunning(shouldStop);
        }
    }

    private static void requireCounts(
            long features, long vertices, long expectedFeatures, long expectedVertices) {
        if (features != expectedFeatures || vertices != expectedVertices) {
            throw new IllegalArgumentException(
                    "feature stream counts do not match the model result manifest");
        }
    }

    private static void requireCount(long value, long limit, String name) {
        if (value > limit) {
            throw new IllegalArgumentException("feature stream exceeds " + name);
        }
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Feature stream reading cancelled");
        }
    }

    /** The gzip and RFC 8142 framing owner shared by validation and subsequent record reading. */
    private static final class RecordReader implements AutoCloseable {
        private final InputStream input;
        private final long maxDecodedBytes;
        private final long maxRecordBytes;
        private final BooleanSupplier shouldStop;
        private final byte[] buffer = new byte[BUFFER_BYTES];
        private int position;
        private int limit;
        private long decodedBytes;

        RecordReader(
                InputStream compressed,
                long maxDecodedBytes,
                long maxRecordBytes,
                BooleanSupplier shouldStop)
                throws IOException {
            this.shouldStop = Objects.requireNonNull(shouldStop, "shouldStop");
            requireRunning(shouldStop);
            this.input =
                    new GZIPInputStream(
                            Objects.requireNonNull(compressed, "compressed"), BUFFER_BYTES);
            this.maxDecodedBytes = maxDecodedBytes;
            this.maxRecordBytes = maxRecordBytes;
        }

        byte[] next() throws IOException {
            requireRunning(shouldStop);
            int marker = readByte();
            if (marker == -1) return null;
            if (marker != RECORD_SEPARATOR) {
                throw new IllegalArgumentException(
                        "feature stream record must begin with RFC 8142 RS");
            }
            ByteArrayOutputStream record = new ByteArrayOutputStream();
            while (true) {
                if (!hasBytes()) {
                    throw new IllegalArgumentException(
                            "feature stream record is missing its LF terminator");
                }
                int end = position;
                while (end < limit
                        && buffer[end] != RECORD_TERMINATOR
                        && buffer[end] != RECORD_SEPARATOR) {
                    end++;
                }
                int length = end - position;
                int start = position;
                consume(length);
                if ((long) record.size() + length > maxRecordBytes) {
                    throw new IllegalArgumentException(
                            "feature stream record exceeds max_record_bytes");
                }
                record.write(buffer, start, length);
                if (position == limit) continue;
                int delimiter = readByte();
                if (delimiter == RECORD_TERMINATOR) break;
                throw new IllegalArgumentException(
                        "feature stream record contains an unescaped RS");
            }
            if (record.size() == 0) {
                throw new IllegalArgumentException("feature stream contains an empty record");
            }
            return record.toByteArray();
        }

        private int readByte() throws IOException {
            if (!hasBytes()) return -1;
            int value = Byte.toUnsignedInt(buffer[position]);
            consume(1);
            return value;
        }

        private boolean hasBytes() throws IOException {
            if (position < limit) return true;
            requireRunning(shouldStop);
            limit = input.read(buffer);
            position = 0;
            return limit != -1;
        }

        private void consume(int count) {
            decodedBytes = Math.addExact(decodedBytes, count);
            requireCount(decodedBytes, maxDecodedBytes, "max_decoded_bytes");
            position += count;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}

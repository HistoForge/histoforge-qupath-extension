package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.erdtman.jcs.JsonCanonicalizer;
import qupath.lib.objects.classes.PathClass;

/** Sole Java owner of duplicate-free RFC 8785/JCS JSON bytes and SHA-256 identities. */
public final class CanonicalCodec {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Pattern JSON_INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RFC3339_MILLIS =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
    private static final DateTimeFormatter RFC3339_MILLIS_FORMAT =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 3, 3, true)
                    .appendLiteral('Z')
                    .toFormatter(Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private CanonicalCodec() {}

    /** Return whether contract text is empty or contains only Unicode whitespace. */
    public static boolean isBlank(String value) {
        return value.isEmpty() || value.codePoints().allMatch(CanonicalCodec::isUnicodeWhitespace);
    }

    /** Closed Unicode White_Space property, independent of JDK character-table changes. */
    private static boolean isUnicodeWhitespace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D)
                || codePoint == 0x0020
                || codePoint == 0x0085
                || codePoint == 0x00A0
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
    }

    /** Strictly parse UTF-8 JSON and return its semantic tree after JCS validation. */
    public static JsonElement parseJson(byte[] source) {
        return parseJson(decodeUtf8(source));
    }

    /** Strictly parse JSON text and return its semantic tree after JCS validation. */
    public static JsonElement parseJson(String source) {
        canonicalBytes(source);
        return JsonParser.parseString(source);
    }

    /**
     * Parse bytes that must already be canonical, retaining the single parsed tree.
     *
     * <p>Exact re-encoding also rejects duplicate members and any permissive parser spelling:
     * neither can survive as byte-identical canonical JSON. This is stricter than {@link
     * #parseJson}, which accepts noncanonical but otherwise valid JSON.
     */
    public static JsonElement parseCanonicalJson(byte[] source) {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(decodeUtf8(source));
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("invalid canonical JSON", error);
        }
        if (!Arrays.equals(source, canonicalBytes(parsed))) {
            throw new IllegalArgumentException("JSON bytes must contain one canonical value");
        }
        return parsed;
    }

    /** Return the JSON tree represented by {@code value} under JCS number semantics. */
    public static JsonElement canonicalTree(JsonElement value) {
        return parseJson(canonicalBytes(value));
    }

    /** Return canonical UTF-8 bytes for an already parsed JSON value. */
    public static byte[] canonicalBytes(JsonElement value) {
        if (value == null) {
            throw new IllegalArgumentException("canonical JSON value must not be null");
        }
        return utf8Bytes(CanonicalJsonEncoder.encode(value));
    }

    /** Strictly parse raw UTF-8 JSON and return its canonical UTF-8 bytes. */
    public static byte[] canonicalBytes(byte[] source) {
        return canonicalBytes(decodeUtf8(source));
    }

    private static String decodeUtf8(byte[] source) {
        if (source == null) {
            throw new IllegalArgumentException("JSON source must not be null");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("JSON source is not valid UTF-8", e);
        }
    }

    /** Strictly parse JSON text and return its canonical UTF-8 bytes. */
    public static byte[] canonicalBytes(String source) {
        if (source == null) {
            throw new IllegalArgumentException("JSON source must not be null");
        }
        try {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(source);
            } catch (JsonParseException e) {
                throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
            }
            String wrapped = new JsonCanonicalizer("[" + source + "]").getEncodedString();
            String encoded = wrapped.substring(1, wrapped.length() - 1);
            requireWellFormedUnicode(encoded);
            if (!JsonParser.parseString(encoded).equals(parsed)) {
                throw new IllegalArgumentException("JSON source must contain exactly one value");
            }
            return encoded.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid RFC 8785 JSON: " + e.getMessage(), e);
        }
    }

    /** Return lowercase SHA-256 hex over one JSON value's canonical bytes. */
    public static String canonicalDigest(JsonElement value) {
        return sha256(canonicalBytes(value));
    }

    /** Encode a well-formed Java string as exact UTF-8 without replacement characters. */
    public static byte[] utf8Bytes(String value) {
        if (value == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        requireWellFormedUnicode(value);
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Return lowercase SHA-256 hex over exact bytes. */
    public static String sha256(byte[] value) {
        return sha256Incrementally(digest -> digest.update(value));
    }

    /** Hash exact byte chunks in order, without retaining them or adding separators. */
    public static String sha256Incrementally(Consumer<MessageDigest> writeBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            writeBytes.accept(digest);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Return a contract string after rejecting null, empty, and Unicode-blank values. */
    public static String requireNonblank(String value, String path) {
        if (value == null || isBlank(value)) {
            throw new IllegalArgumentException(path + " must be a nonblank string");
        }
        requireWellFormedUnicode(value);
        return value;
    }

    /** Return an exact lowercase 64-character SHA-256 value. */
    public static String requireSha256(String value, String path) {
        String digest = requireNonblank(value, path);
        if (!SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException(path + " must be lowercase 64-character SHA-256");
        }
        return digest;
    }

    /** Return a lowercase canonical UUID string without accepting alternate spellings. */
    public static String requireCanonicalUuid(String value, String path) {
        String text = requireNonblank(value, path);
        try {
            if (!UUID.fromString(text).toString().equals(text)) {
                throw new IllegalArgumentException(path + " must be a lowercase canonical UUID");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + " must be a canonical UUID", e);
        }
        return text;
    }

    /** Parse an exact UTC RFC 3339 timestamp with millisecond precision. */
    public static Instant requireRfc3339Millis(String value, String path) {
        String text = requireNonblank(value, path);
        if (!RFC3339_MILLIS.matcher(text).matches() || "0000".equals(text.substring(0, 4))) {
            throw new IllegalArgumentException(
                    path + " must be UTC RFC 3339 with exactly milliseconds");
        }
        final Instant instant;
        try {
            instant = Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    path + " must be UTC RFC 3339 with exactly milliseconds", e);
        }
        if (!RFC3339_MILLIS_FORMAT.format(instant).equals(text)) {
            throw new IllegalArgumentException(
                    path + " must be a canonical UTC millisecond timestamp");
        }
        return instant;
    }

    /** Format a millisecond-aligned instant as canonical UTC RFC 3339 text. */
    public static String rfc3339Millis(Instant value, String path) {
        if (value == null || value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(path + " must have exact millisecond precision");
        }
        String text = RFC3339_MILLIS_FORMAT.format(value);
        requireRfc3339Millis(text, path);
        return text;
    }

    /** Return an exact JSON integer representable by Java's signed {@code int}. */
    public static int requireInt32(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " must be a signed 32-bit integer");
        }
        String token = value.getAsString();
        if (!JSON_INTEGER.matcher(token).matches()) {
            throw new IllegalArgumentException(path + " must be a signed 32-bit integer");
        }
        try {
            return new BigDecimal(token).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(path + " must be a signed 32-bit integer", e);
        }
    }

    /** Return a nonnegative exact JSON integer representable by Java's signed {@code int}. */
    public static int requireNonnegativeInt32(JsonElement value, String path) {
        return requireNonnegativeInt32(requireInt32(value, path), path);
    }

    /** Validate a nonnegative primitive integer against the shared JSON scalar contract. */
    public static int requireNonnegativeInt32(int value, String path) {
        int result = value;
        if (result < 0) {
            throw new IllegalArgumentException(
                    path + " must be a nonnegative signed 32-bit integer");
        }
        return result;
    }

    /** Return an exact positive integer representable by Java's signed {@code int}. */
    public static int requirePositiveInt32(JsonElement value, String path) {
        return requirePositiveInt32(requireInt32(value, path), path);
    }

    /** Validate a positive primitive integer against the shared JSON scalar contract. */
    public static int requirePositiveInt32(int value, String path) {
        if (value <= 0) {
            throw new IllegalArgumentException(path + " must be a positive signed 32-bit integer");
        }
        return value;
    }

    /** Return an exact nonnegative integer in the RFC 8785 safe range. */
    public static long requireNonnegativeSafeInteger(JsonElement value, String path) {
        return requireNonnegativeSafeInteger(requireSafeInteger(value, path), path);
    }

    /** Validate a nonnegative primitive long against the shared JSON scalar contract. */
    public static long requireNonnegativeSafeInteger(long value, String path) {
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(path + " must be a nonnegative safe integer");
        }
        return value;
    }

    /** Return an exact signed integer in the RFC 8785 safe range. */
    public static long requireSafeInteger(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " must be a safe integer");
        }
        String token = value.getAsString();
        if (!JSON_INTEGER.matcher(token).matches()) {
            throw new IllegalArgumentException(path + " must be a safe integer");
        }
        final long result;
        try {
            result = new BigDecimal(token).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(path + " must be a safe integer", e);
        }
        return requireSafeInteger(result, path);
    }

    /** Validate a primitive long against the shared signed JSON scalar contract. */
    public static long requireSafeInteger(long value, String path) {
        if (value < -MAX_SAFE_INTEGER || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(path + " must be a safe integer");
        }
        return value;
    }

    /** Return one finite binary64 value, normalizing negative zero to positive zero. */
    public static double requireFiniteDouble(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " must be a finite number");
        }
        return requireFiniteDouble(value.getAsDouble(), path);
    }

    /** Validate one primitive binary64 value, normalizing negative zero to positive zero. */
    public static double requireFiniteDouble(double value, String path) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(path + " must be a finite number");
        }
        return value == 0.0 ? 0.0 : value;
    }

    /** Return one finite positive binary64 value. */
    public static double requirePositiveFiniteDouble(JsonElement value, String path) {
        return requirePositiveFiniteDouble(requireFiniteDouble(value, path), path);
    }

    /** Validate one primitive positive binary64 value. */
    public static double requirePositiveFiniteDouble(double value, String path) {
        double result = requireFiniteDouble(value, path);
        if (result <= 0.0) {
            throw new IllegalArgumentException(path + " must be a positive finite number");
        }
        return result;
    }

    /** Return an exact positive integer in the RFC 8785 safe range. */
    public static long requirePositiveSafeInteger(JsonElement value, String path) {
        return requirePositiveSafeInteger(requireNonnegativeSafeInteger(value, path), path);
    }

    /** Validate a positive primitive long against the shared JSON scalar contract. */
    public static long requirePositiveSafeInteger(long value, String path) {
        if (value <= 0 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(path + " must be a positive safe integer");
        }
        return value;
    }

    /** Require QuPath's canonical {@code PathClass.toString()} spelling. */
    public static String requirePathClassName(String value, String path) {
        String text = requireNonblank(value, path);
        final String canonical;
        try {
            canonical = PathClass.fromString(text).toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    path + " must be a canonical QuPath PathClass name", e);
        }
        if (!canonical.equals(text)) {
            throw new IllegalArgumentException(path + " must be a canonical QuPath PathClass name");
        }
        return text;
    }

    /** Encode a validated string scalar as a JSON object-member key. */
    public static String scalarMapKey(String value) {
        if (value == null) {
            throw new IllegalArgumentException("scalar string map key must not be null");
        }
        requireWellFormedUnicode(value);
        return value;
    }

    /** Encode a validated nonnegative integer scalar as an unsigned base-10 map key. */
    public static String scalarMapKey(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("scalar integer map key must be nonnegative");
        }
        return Integer.toString(value);
    }

    /** Encode a compound-key map as entries sorted by canonical key bytes. */
    public static JsonArray canonicalMapEntries(
            Collection<Map.Entry<JsonElement, JsonElement>> entries) {
        List<EncodedEntry> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<JsonElement, JsonElement> entry : entries) {
            byte[] keyBytes = canonicalBytes(entry.getKey());
            if (!seen.add(HexFormat.of().formatHex(keyBytes))) {
                throw new IllegalArgumentException(
                        "compound map contains a duplicate canonical key");
            }
            ordered.add(
                    new EncodedEntry(
                            keyBytes,
                            canonicalTree(entry.getKey()),
                            canonicalTree(entry.getValue())));
        }
        ordered.sort((left, right) -> Arrays.compareUnsigned(left.keyBytes(), right.keyBytes()));

        JsonArray result = new JsonArray();
        for (EncodedEntry entry : ordered) {
            JsonObject item = new JsonObject();
            item.add("key", entry.key());
            item.add("value", entry.value());
            result.add(item);
        }
        return result;
    }

    /** Encode a semantic set as values sorted by canonical element bytes. */
    public static JsonArray canonicalUnordered(Collection<JsonElement> values) {
        List<EncodedValue> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement value : values) {
            byte[] valueBytes = canonicalBytes(value);
            if (!seen.add(HexFormat.of().formatHex(valueBytes))) {
                throw new IllegalArgumentException(
                        "unordered collection contains a duplicate canonical value");
            }
            ordered.add(new EncodedValue(valueBytes, canonicalTree(value)));
        }
        ordered.sort((left, right) -> Arrays.compareUnsigned(left.bytes(), right.bytes()));

        JsonArray result = new JsonArray();
        for (EncodedValue value : ordered) {
            result.add(value.value());
        }
        return result;
    }

    private static void requireWellFormedUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("text contains an unpaired high surrogate");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("text contains an unpaired low surrogate");
            }
        }
    }

    private record EncodedEntry(byte[] keyBytes, JsonElement key, JsonElement value) {}

    private record EncodedValue(byte[] bytes, JsonElement value) {}
}

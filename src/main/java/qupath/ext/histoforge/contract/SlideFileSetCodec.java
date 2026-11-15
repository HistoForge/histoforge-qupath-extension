package qupath.ext.histoforge.contract;

import static qupath.ext.histoforge.contract.StrictJson.requireExactObject;
import static qupath.ext.histoforge.contract.StrictJson.requiredString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Portable original-slide member names and their sealed byte evidence. */
public final class SlideFileSetCodec {
    public static final int MAX_MEMBERS = 256;
    public static final int MAX_PATH_BYTES = 512;
    public static final int MAX_TOTAL_PATH_BYTES = 16 * 1024;
    public static final Comparator<String> PATH_ORDER =
            (left, right) ->
                    Arrays.compareUnsigned(
                            left.getBytes(StandardCharsets.UTF_8),
                            right.getBytes(StandardCharsets.UTF_8));

    private SlideFileSetCodec() {}

    /** Keep actual relative filenames; never sanitize by renaming a companion. */
    public static String relativePath(String path) {
        Objects.requireNonNull(path, "member.path");
        if (path.isEmpty()
                || path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES
                || !Normalizer.isNormalized(path, Normalizer.Form.NFC)
                || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0
                || path.chars().anyMatch(character -> character < 32)
                || Arrays.stream(path.split("/", -1))
                        .anyMatch(
                                part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException(
                    "slide member path must be normalized portable relative text");
        }
        // Reject malformed UTF-16 instead of silently replacing it during UTF-8 encoding.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path))
            throw new IllegalArgumentException("slide member path is not valid Unicode");
        return path;
    }

    public static void requireMemberPaths(List<String> paths) {
        if (paths.isEmpty() || paths.size() > MAX_MEMBERS)
            throw new IllegalArgumentException("slide fileset member count exceeds its bound");
        int bytes = 0;
        Set<String> names = new HashSet<>();
        for (String path : paths) {
            bytes += relativePath(path).getBytes(StandardCharsets.UTF_8).length;
            if (!names.add(path.toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException(
                        "slide fileset contains duplicate or case-aliased paths");
        }
        if (bytes > MAX_TOTAL_PATH_BYTES)
            throw new IllegalArgumentException("slide fileset path metadata exceeds its bound");
        for (String path : names) {
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                if (names.contains(path.substring(0, slash)))
                    throw new IllegalArgumentException(
                            "slide member is both a file and a directory");
            }
        }
    }

    public record Member(String path, long sizeBytes, String sha256) {
        public Member {
            path = relativePath(path);
            sizeBytes =
                    CanonicalCodec.requireNonnegativeSafeInteger(sizeBytes, "member.size_bytes");
            sha256 = CanonicalCodec.requireSha256(sha256, "member.sha256");
        }
    }

    public static List<Member> normalizeMembers(List<Member> members) {
        var result = List.copyOf(members);
        requireMemberPaths(result.stream().map(Member::path).toList());
        return result.stream().sorted(Comparator.comparing(Member::path, PATH_ORDER)).toList();
    }

    public static List<Member> parseMembers(JsonElement value) {
        if (value == null || !value.isJsonArray())
            throw new IllegalArgumentException("slide members must be an array");
        var members = new java.util.ArrayList<Member>();
        for (JsonElement item : value.getAsJsonArray()) {
            var object =
                    requireExactObject(
                            item, Set.of("path", "size_bytes", "sha256"), "slide member");
            members.add(
                    new Member(
                            requiredString(object, "path"),
                            CanonicalCodec.requireNonnegativeSafeInteger(
                                    object.get("size_bytes"), "size_bytes"),
                            requiredString(object, "sha256")));
        }
        return normalizeMembers(members);
    }

    public static JsonArray toJson(List<Member> members) {
        var result = new JsonArray();
        for (Member member : normalizeMembers(members)) {
            var value = new JsonObject();
            value.addProperty("path", member.path());
            value.addProperty("size_bytes", member.sizeBytes());
            value.addProperty("sha256", member.sha256());
            result.add(value);
        }
        return result;
    }
}

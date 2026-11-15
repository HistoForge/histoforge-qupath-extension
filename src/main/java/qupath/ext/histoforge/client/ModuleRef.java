package qupath.ext.histoforge.client;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/** The sole canonical, path-safe {@code owner/name} module-reference owner. */
public record ModuleRef(String owner, String name) {

    private static final Pattern SEGMENT =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

    public ModuleRef {
        requireSegment(owner, "owner");
        requireSegment(name, "name");
        if (owner.length() + 1 + name.length() > 96) {
            throw new IllegalArgumentException("module ref must be at most 96 characters");
        }
    }

    /** Parse only the exact canonical {@code owner/name} identity form. */
    public static Optional<ModuleRef> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("/", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            ModuleRef ref = new ModuleRef(parts[0], parts[1]);
            return ref.toString().equals(value) ? Optional.of(ref) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Validate two raw URL segments and reject traversal or any noncanonical escape. */
    public static Optional<ModuleRef> fromRouteSegments(String encodedOwner, String encodedName) {
        try {
            return Optional.of(
                    new ModuleRef(
                            decodeRouteSegment(encodedOwner), decodeRouteSegment(encodedName)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Parse the install dialog's convenience forms without adding revision semantics to module
     * identity: {@code owner/name}, {@code owner/name@revision}, or a Hugging Face URL.
     */
    public static Optional<HuggingFaceSource> parseHuggingFaceSource(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String value = input.strip();
        if (value.startsWith("huggingface.co/")) {
            value = "https://" + value;
        }
        if (value.contains("://")) {
            return parseHuggingFaceUrl(value);
        }

        String refText = value;
        Optional<String> revision = Optional.empty();
        int separator = value.indexOf('@');
        if (separator >= 0) {
            if (separator != value.lastIndexOf('@')) {
                return Optional.empty();
            }
            refText = value.substring(0, separator);
            String revisionText = value.substring(separator + 1).strip();
            if (revisionText.isEmpty()) {
                return Optional.empty();
            }
            revision = Optional.of(revisionText);
        }
        Optional<ModuleRef> ref = parse(refText);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new HuggingFaceSource(ref.get(), revision));
    }

    private static Optional<HuggingFaceSource> parseHuggingFaceUrl(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || !"huggingface.co".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null) {
            return Optional.empty();
        }
        String[] parts = uri.getRawPath().split("/", -1);
        if (parts.length < 3 || !parts[0].isEmpty()) {
            return Optional.empty();
        }
        Optional<ModuleRef> ref = fromRouteSegments(parts[1], parts[2]);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        if (parts.length == 3 || (parts.length == 4 && parts[3].isEmpty())) {
            return Optional.of(new HuggingFaceSource(ref.get(), Optional.empty()));
        }
        if (parts.length != 5
                || !("commit".equals(parts[3]) || "tree".equals(parts[3]))
                || parts[4].isBlank()
                || parts[4].contains("%")) {
            return Optional.empty();
        }
        return Optional.of(new HuggingFaceSource(ref.get(), Optional.of(parts[4].strip())));
    }

    private static void requireSegment(String value, String label) {
        if (value == null
                || value.length() < 1
                || value.length() > 96
                || !SEGMENT.matcher(value).matches()
                || ".".equals(value)
                || "..".equals(value)) {
            throw new IllegalArgumentException(
                    "module " + label + " is not a canonical path-safe segment");
        }
    }

    private static String decodeRouteSegment(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("module route segment must be nonempty");
        }
        // Every schema-1 segment character is URL-unreserved, so its one canonical route
        // spelling is the raw character itself. Any escape is noncanonical.
        if (value.indexOf('%') >= 0) {
            throw new IllegalArgumentException("module route segment is not canonically encoded");
        }
        if (".".equals(value)
                || "..".equals(value)
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("module route segment is not path-safe");
        }
        return value;
    }

    /** The validated module identity plus an optional branch, tag, or SHA for HF resolution. */
    public record HuggingFaceSource(ModuleRef moduleRef, Optional<String> revision) {
        public HuggingFaceSource {
            if (moduleRef == null || revision == null) {
                throw new IllegalArgumentException("Hugging Face source fields must not be null");
            }
        }

        @Override
        public String toString() {
            return moduleRef + revision.map(value -> "@" + value).orElse("");
        }
    }

    /** The canonical {@code owner/name} value used by current request DTOs. */
    public String repo() {
        return toString();
    }

    @Override
    public String toString() {
        return owner + "/" + name;
    }
}

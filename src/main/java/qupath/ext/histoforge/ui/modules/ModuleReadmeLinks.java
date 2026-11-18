package qupath.ext.histoforge.ui.modules;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;

/** Resolve explicit README links without allowing local files or moving repository revisions. */
final class ModuleReadmeLinks {
    private ModuleReadmeLinks() {}

    static URI destination(String value, String moduleRef, String commit) {
        try {
            URI uri = new URI(value);
            String decoded = uri.getSchemeSpecificPart();
            if (decoded.indexOf('\\') >= 0 || decoded.chars().anyMatch(Character::isISOControl)) {
                return null;
            }
            if (uri.isAbsolute()) {
                return ("https".equalsIgnoreCase(uri.getScheme())
                                        || "http".equalsIgnoreCase(uri.getScheme()))
                                && uri.getHost() != null
                                && uri.getUserInfo() == null
                        ? uri
                        : null;
            }
            if (uri.getRawAuthority() != null) return null;
            var segments = new ArrayDeque<String>();
            for (String part : uri.getPath().split("/")) {
                if (part.isEmpty() || part.equals(".")) continue;
                if (part.equals("..")) {
                    if (segments.isEmpty()) return null;
                    segments.removeLast();
                } else {
                    segments.add(part);
                }
            }
            String file = segments.isEmpty() ? "README.md" : String.join("/", segments);
            URI pinned =
                    new URI(
                            "https",
                            "huggingface.co",
                            "/" + moduleRef + "/blob/" + commit + "/" + file,
                            null,
                            null);
            // Decoding/re-encoding a query turns e.g. %26 inside a value into a separator.
            return URI.create(
                    pinned.toASCIIString()
                            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                            + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment()));
        } catch (URISyntaxException error) {
            return null;
        }
    }
}

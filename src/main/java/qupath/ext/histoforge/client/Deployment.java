package qupath.ext.histoforge.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import qupath.ext.histoforge.contract.CanonicalCodec;

/** Exact private deployment credential written by the {@code histoforge} CLI. */
public record Deployment(
        // Persisted credential schema version.
        int schemaVersion,
        // Identity of the gateway deployment that issued this credential.
        String deploymentId,
        // Canonical HTTPS gateway origin, or localhost HTTP for development.
        String gatewayUrl,
        // Exact local Modal profile used to locate and manage the deployment.
        String modalProfile,
        // Provider-resolved environment; absent only in an older v1 credential.
        String modalEnvironment,
        // Modal application name locating the deployment.
        String modalAppName,
        // Monotonic credential revision expected from authenticated capabilities.
        long authRevision,
        // Raw bearer token; this is the only persisted location that holds it.
        String token,
        // Exact UTC-millisecond time when the CLI published this credential.
        Instant updatedAt) {

    private static final int SCHEMA_VERSION = 2;
    private static final Set<String> V1_FIELDS =
            Set.of(
                    "schema_version",
                    "deployment_id",
                    "gateway_url",
                    "modal_profile",
                    "modal_app_name",
                    "auth_revision",
                    "token",
                    "updated_at");
    private static final Set<String> V2_FIELDS =
            java.util.stream.Stream.concat(
                            V1_FIELDS.stream(), java.util.stream.Stream.of("modal_environment"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    public Deployment {
        if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported deployment credential schema_version");
        }
        deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
        gatewayUrl = requireGatewayOrigin(gatewayUrl);
        modalProfile = CanonicalCodec.requireNonblank(modalProfile, "modal_profile");
        if (schemaVersion == 1) {
            if (modalEnvironment != null) {
                throw new IllegalArgumentException("v1 deployment credential has no environment");
            }
        } else {
            modalEnvironment =
                    CanonicalCodec.requireNonblank(modalEnvironment, "modal_environment");
        }
        modalAppName = CanonicalCodec.requireNonblank(modalAppName, "modal_app_name");
        authRevision = CanonicalCodec.requireNonnegativeSafeInteger(authRevision, "auth_revision");
        token = requireToken(token);
        CanonicalCodec.rfc3339Millis(updatedAt, "updated_at");
    }

    /** Construct the published v1 shape, whose environment has not yet been verified. */
    public Deployment(
            int schemaVersion,
            String deploymentId,
            String gatewayUrl,
            String modalProfile,
            String modalAppName,
            long authRevision,
            String token,
            Instant updatedAt) {
        this(
                schemaVersion,
                deploymentId,
                gatewayUrl,
                modalProfile,
                null,
                modalAppName,
                authRevision,
                token,
                updatedAt);
    }

    /** Carry a verified preflight location into the next helper command without saving it. */
    public Deployment withEnvironment(String environment) {
        return new Deployment(
                SCHEMA_VERSION,
                deploymentId,
                gatewayUrl,
                modalProfile,
                environment,
                modalAppName,
                authRevision,
                token,
                updatedAt);
    }

    /** Parse the closed snake-case persisted schema without Gson field elision. */
    public static Deployment parse(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("deployment credential must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        int schemaVersion =
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version");
        if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported deployment credential schema_version");
        }
        if (!object.keySet().equals(schemaVersion == 1 ? V1_FIELDS : V2_FIELDS)) {
            throw new IllegalArgumentException(
                    "deployment credential has missing or unknown fields");
        }
        return new Deployment(
                schemaVersion,
                requiredString(object, "deployment_id"),
                requiredString(object, "gateway_url"),
                requiredString(object, "modal_profile"),
                schemaVersion == 1 ? null : requiredString(object, "modal_environment"),
                requiredString(object, "modal_app_name"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("auth_revision"), "auth_revision"),
                requiredString(object, "token"),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "updated_at"), "updated_at"));
    }

    /** Encode this credential using the exact persisted field names. */
    public JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", schemaVersion);
        value.addProperty("deployment_id", deploymentId);
        value.addProperty("gateway_url", gatewayUrl);
        value.addProperty("modal_profile", modalProfile);
        if (schemaVersion == SCHEMA_VERSION) {
            value.addProperty("modal_environment", modalEnvironment);
        }
        value.addProperty("modal_app_name", modalAppName);
        value.addProperty("auth_revision", authRevision);
        value.addProperty("token", token);
        value.addProperty("updated_at", CanonicalCodec.rfc3339Millis(updatedAt, "updated_at"));
        return value;
    }

    private static String requireGatewayOrigin(String value) {
        String text = CanonicalCodec.requireNonblank(value, "gateway_url");
        final URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("gateway_url is invalid", error);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || host == null
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "gateway_url must be an origin without path or user-info");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        if (!validGatewayHost(normalizedHost)) {
            throw new IllegalArgumentException(
                    "gateway_url host must be a canonical IP or DNS name");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("gateway_url port must be between 1 and 65535");
        }
        if ("http".equals(normalizedScheme)) {
            if (!Set.of("localhost", "127.0.0.1", "::1").contains(normalizedHost)) {
                throw new IllegalArgumentException(
                        "HTTP gateway_url is permitted only for localhost");
            }
        } else if (!"https".equals(normalizedScheme)) {
            throw new IllegalArgumentException("gateway_url must use HTTPS");
        }
        String renderedHost =
                normalizedHost.contains(":") ? "[" + normalizedHost + "]" : normalizedHost;
        String expected = normalizedScheme + "://" + renderedHost;
        if (uri.getPort() >= 0) {
            expected += ":" + uri.getPort();
        }
        if (!text.equals(expected)) {
            throw new IllegalArgumentException("gateway_url must use canonical origin spelling");
        }
        return text;
    }

    private static boolean validGatewayHost(String host) {
        if (!host.chars().allMatch(character -> character < 0x80) || host.indexOf('%') >= 0) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(host) instanceof Inet6Address;
            } catch (UnknownHostException error) {
                return false;
            }
        }
        if (host.chars().allMatch(character -> Character.isDigit(character) || character == '.')) {
            return validIpv4(host);
        }
        if (host.isEmpty() || host.length() > 253 || host.endsWith(".")) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > 63
                    || !asciiLetterOrDigit(label.charAt(0))
                    || !asciiLetterOrDigit(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int index = 1; index < label.length() - 1; index++) {
                char character = label.charAt(index);
                if (!asciiLetterOrDigit(character) && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty()
                    || (part.length() > 1 && part.charAt(0) == '0')
                    || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiLetterOrDigit(char character) {
        return character >= 'a' && character <= 'z' || character >= '0' && character <= '9';
    }

    private static String requireToken(String value) {
        String token = CanonicalCodec.requireNonblank(value, "token");
        if (token.chars().anyMatch(character -> character <= 0x20 || character >= 0x7f)) {
            throw new IllegalArgumentException("token must be visible ASCII without whitespace");
        }
        return token;
    }

    private static String requiredString(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return item.getAsString();
    }
}

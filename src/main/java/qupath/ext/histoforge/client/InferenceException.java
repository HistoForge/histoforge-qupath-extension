package qupath.ext.histoforge.client;

import java.util.Optional;
import qupath.ext.histoforge.contract.GatewayErrorCodec;

/**
 * Reports gateway call or response-validation failures. Preserves a validated {@link
 * GatewayErrorCodec.Error} when available. {@link #statusCode()} is the HTTP status, or {@code 0}
 * when none was recorded.
 */
public class InferenceException extends Exception {

    private final int statusCode;
    private final GatewayErrorCodec.Error gatewayError;

    public InferenceException(String message) {
        this(0, message);
    }

    public InferenceException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.gatewayError = null;
    }

    /** Build an exception from a validated gateway error. */
    public InferenceException(int statusCode, GatewayErrorCodec.Error gatewayError) {
        super(gatewayError.code() + ": " + gatewayError.message());
        this.statusCode = statusCode;
        this.gatewayError = gatewayError;
    }

    public InferenceException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.gatewayError = null;
    }

    private InferenceException(String message, InferenceException rejected) {
        super(message, rejected);
        this.statusCode = rejected.statusCode;
        this.gatewayError = rejected.gatewayError;
    }

    /** Add local context for display while retaining the original gateway failure. */
    public InferenceException withDisplayMessage(String message) {
        return new InferenceException(message, this);
    }

    /** The HTTP status, or {@code 0} when none was recorded. */
    public int statusCode() {
        return statusCode;
    }

    /** The validated gateway error, when the response included one. */
    public Optional<GatewayErrorCodec.Error> gatewayError() {
        return Optional.ofNullable(gatewayError);
    }
}

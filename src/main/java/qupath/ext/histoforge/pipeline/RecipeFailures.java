package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.Objects;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;

/** Sole mapper from recipe execution exceptions to stable process-local failure categories. */
final class RecipeFailures {

    private RecipeFailures() {}

    static Categorized categorized(RunResults.FailureType type, String message, Exception cause) {
        return new Categorized(new RunResults.FailureDetail(type, message), cause);
    }

    static RunResults.FailureDetail detail(Exception failure) {
        if (failure instanceof ModelGatewayExecutor.AcceptedFiniteCallFailure accepted) {
            return accepted(accepted.originalFailure());
        }
        if (failure instanceof Categorized categorized) {
            return categorized.failure();
        }
        if (failure instanceof RecipeExecutor.FillRequiresRedoException) {
            return new RunResults.FailureDetail(
                    RunResults.FailureType.FILL_REQUIRES_REDO, message(failure));
        }
        if (failure instanceof RecipeExecutor.BlockedPrerequisiteException) {
            return new RunResults.FailureDetail(
                    RunResults.FailureType.PREREQUISITE, message(failure));
        }
        if (failure instanceof RecipeExecutor.CrossModeRequiresRedoException) {
            return new RunResults.FailureDetail(
                    RunResults.FailureType.CROSS_MODE_REQUIRES_REDO, message(failure));
        }
        if (failure instanceof LocalCapacityExceededException) {
            return new RunResults.FailureDetail(
                    RunResults.FailureType.LOCAL_CAPACITY, message(failure));
        }
        if (failure instanceof InferenceException inference) {
            return new RunResults.FailureDetail(
                    inference
                            .gatewayError()
                            .map(GatewayErrorCodec.Error::code)
                            .map(code -> gatewayType(code, false))
                            .orElseGet(
                                    () ->
                                            inference.getCause() instanceof IllegalArgumentException
                                                    ? RunResults.FailureType.RESPONSE_VALIDATION
                                                    : RunResults.FailureType.SUBMISSION),
                    message(failure));
        }
        if (failure instanceof IOException) {
            return new RunResults.FailureDetail(
                    RunResults.FailureType.SUBMISSION, message(failure));
        }
        if (failure instanceof IllegalArgumentException) {
            return new RunResults.FailureDetail(
                    RunResults.FailureType.RESPONSE_VALIDATION, message(failure));
        }
        return new RunResults.FailureDetail(RunResults.FailureType.BACKEND, message(failure));
    }

    /** Classify failure after a finite call was accepted by the backend. */
    static RunResults.FailureDetail accepted(Exception failure) {
        if (failure instanceof Categorized categorized) {
            return categorized.failure();
        }
        if (failure instanceof InferenceException inference) {
            return new RunResults.FailureDetail(
                    inference
                            .gatewayError()
                            .map(GatewayErrorCodec.Error::code)
                            .map(code -> gatewayType(code, true))
                            .orElseGet(
                                    () ->
                                            inference.getCause() instanceof IllegalArgumentException
                                                    ? RunResults.FailureType.RESPONSE_VALIDATION
                                                    : RunResults.FailureType.BACKEND),
                    message(failure));
        }
        if (failure instanceof IllegalArgumentException) {
            return new RunResults.FailureDetail(
                    RunResults.FailureType.RESPONSE_VALIDATION, message(failure));
        }
        return new RunResults.FailureDetail(RunResults.FailureType.BACKEND, message(failure));
    }

    /** Classify a local staging invariant failure after model execution has completed. */
    static RunResults.FailureDetail localStaging(Exception failure) {
        RunResults.FailureType type =
                failure instanceof LocalCapacityExceededException
                        ? RunResults.FailureType.LOCAL_CAPACITY
                        : failure instanceof IllegalArgumentException
                                ? RunResults.FailureType.RESPONSE_VALIDATION
                                : RunResults.FailureType.CORRUPT_IMAGE_STATE;
        return new RunResults.FailureDetail(type, message(failure));
    }

    /** Classify a failed mutation of the disposable working hierarchy. */
    static RunResults.FailureDetail applyFailure(RuntimeException failure) {
        if (failure instanceof LocalCapacityExceededException) {
            return new RunResults.FailureDetail(
                    RunResults.FailureType.LOCAL_CAPACITY, message(failure));
        }
        return new RunResults.FailureDetail(
                RunResults.FailureType.CORRUPT_IMAGE_STATE,
                "working hierarchy apply failed: " + message(failure));
    }

    static Exception originalCause(Exception failure) {
        if (failure instanceof Categorized categorized) {
            return categorized.originalCause();
        }
        if (failure instanceof ModelGatewayExecutor.AcceptedFiniteCallFailure accepted) {
            return accepted.originalFailure();
        }
        return failure;
    }

    static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    private static RunResults.FailureType gatewayType(
            GatewayErrorCodec.Code code, boolean accepted) {
        return switch (code) {
            case PAYLOAD_TOO_LARGE, RESULT_LIMIT_EXCEEDED -> RunResults.FailureType.RESOURCE_LIMIT;
            case UNSUPPORTED_SCHEMA,
                    UNSUPPORTED_CAPABILITY,
                    MODULE_NOT_FOUND,
                    MODULE_VERSION_UNAVAILABLE,
                    MODULE_IDENTITY_MISMATCH,
                    REGISTRY_AUTHENTICATION_FAILED,
                    REGISTRY_REPOSITORY_NOT_FOUND,
                    REGISTRY_REVISION_NOT_FOUND,
                    REGISTRY_PUBLICATION_CONFLICT ->
                    RunResults.FailureType.UNSUPPORTED_MODULE;
            case INSUFFICIENT_CONTEXT -> RunResults.FailureType.INSUFFICIENT_CONTEXT;
            case ARTIFACT_NOT_FOUND,
                    ARTIFACT_EXPIRED,
                    ARTIFACT_PRECONDITION_FAILED,
                    RANGE_NOT_SATISFIABLE ->
                    RunResults.FailureType.EXTRACTION;
            case CALL_EXPIRED, MODEL_RUN_EXPIRED -> RunResults.FailureType.TIMEOUT;
            case CALL_CANCELLED -> RunResults.FailureType.CANCELLED;
            case VALIDATION_ERROR -> RunResults.FailureType.INVALID_REQUEST;
            case AUTHENTICATION_REQUIRED,
                    RATE_LIMITED,
                    MODULE_BUSY,
                    IDEMPOTENCY_CONFLICT,
                    REGISTRY_RATE_LIMITED,
                    SERVICE_UNAVAILABLE ->
                    accepted ? RunResults.FailureType.BACKEND : RunResults.FailureType.SUBMISSION;
            default -> RunResults.FailureType.BACKEND;
        };
    }

    /** Exception retaining a precise category without hiding the original cause. */
    static final class Categorized extends Exception {

        private final RunResults.FailureDetail failure;
        private final Exception originalCause;

        private Categorized(RunResults.FailureDetail failure, Exception originalCause) {
            super(Objects.requireNonNull(originalCause, "originalCause"));
            this.failure = Objects.requireNonNull(failure, "failure");
            this.originalCause = originalCause;
        }

        RunResults.FailureDetail failure() {
            return failure;
        }

        Exception originalCause() {
            return originalCause;
        }
    }
}

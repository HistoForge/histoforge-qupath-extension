package qupath.ext.histoforge.cli;

/**
 * A CLI invocation (histoforge or uv) that didn't produce a usable result. {@link #type()} lets the
 * UI branch: show an install prompt on {@link Type#NOT_INSTALLED}, deploy only on a confirmed
 * {@link Type#GATEWAY_NOT_FOUND}, show other errors, or quietly reset on {@link Type#INTERRUPTED}.
 */
public class CliException extends Exception {

    public enum Type {
        /** The executable wasn't found (not installed / not on PATH). */
        NOT_INSTALLED,
        /** The selected Modal profile does not contain the HistoForge gateway app. */
        GATEWAY_NOT_FOUND,
        /**
         * The command ran and reported an error (non-zero exit, or a {@code status:"error"} body).
         */
        CLI_ERROR,
        /** A command expected to emit JSON exited 0 but its stdout wasn't parseable. */
        BAD_OUTPUT,
        /** The call was interrupted (e.g. the user cancelled). */
        INTERRUPTED
    }

    private final transient Type type;

    public CliException(Type type, String message) {
        super(message);
        this.type = type;
    }

    public Type type() {
        return type;
    }
}

package qupath.ext.histoforge.util;

/** Small helpers for surfacing exceptions to users. */
public final class Errors {

    private Errors() {}

    /** A human-readable message for {@code t}: its message, or its class name if it has none. */
    public static String message(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}

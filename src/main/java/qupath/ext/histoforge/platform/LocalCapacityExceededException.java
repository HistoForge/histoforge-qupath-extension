package qupath.ext.histoforge.platform;

import java.util.Objects;

/** One user-selected local capacity was exceeded. */
public final class LocalCapacityExceededException extends IllegalArgumentException {

    private final String resource;
    private final long observed;
    private final long limit;

    public LocalCapacityExceededException(String resource, long observed, long limit) {
        super(message(resource, observed, limit));
        this.resource = resource;
        this.observed = observed;
        this.limit = limit;
    }

    public String resource() {
        return resource;
    }

    public long observed() {
        return observed;
    }

    public long limit() {
        return limit;
    }

    private static String message(String resource, long observed, long limit) {
        String name = Objects.requireNonNull(resource, "resource");
        if (name.isBlank()) {
            throw new IllegalArgumentException("resource must not be blank");
        }
        if (observed < 0 || limit < 0) {
            throw new IllegalArgumentException("capacity values must not be negative");
        }
        if (observed <= limit) {
            throw new IllegalArgumentException("observed capacity must exceed limit");
        }
        return name + " exceeded: captured " + observed + ", limit " + limit;
    }
}

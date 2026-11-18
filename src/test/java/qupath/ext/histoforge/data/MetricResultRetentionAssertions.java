package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.lib.objects.PathObject;

/** Deterministic ownership checks for result projections, without relying on GC timing. */
public final class MetricResultRetentionAssertions {

    private MetricResultRetentionAssertions() {}

    /** Inspect project-owned fields and collection members reachable from publication data. */
    public static void assertOnlyResultData(Object value) {
        assertDoesNotThrow(
                () -> inspect(value, Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    private static void inspect(Object value, Set<Object> seen) throws IllegalAccessException {
        if (value == null || !seen.add(value)) {
            return;
        }
        assertFalse(
                value instanceof MetricTypes.MetricPreparation
                        || value instanceof MetricPopulationCapture
                        || value instanceof MetricObjectGeometry
                        || value instanceof MetricTypes.MetricImageSnapshot
                        || value instanceof MetricTypes.MetricInputDraft
                        || value instanceof MetricTypes.MetricRegionSnapshot
                        || value instanceof MetricTypes.MetricObjectSnapshot
                        || value instanceof MetricSnapshotBuilder.CapturedPreparation
                        || value instanceof MetricImageResolver.ResolvedImage
                        || value instanceof MetricImageResolver.ResolvedModule
                        || value instanceof ProvenanceCodec.ObjectProvenance
                        || value instanceof PathObject,
                () -> "publication data retains " + value.getClass().getName());
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                inspect(entry.getKey(), seen);
                inspect(entry.getValue(), seen);
            }
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                inspect(item, seen);
            }
        } else if (value instanceof Optional<?> optional) {
            inspect(optional.orElse(null), seen);
        } else if (value.getClass().getPackageName().startsWith("qupath.ext.histoforge")) {
            for (Field field : value.getClass().getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    inspect(field.get(value), seen);
                }
            }
        }
    }
}

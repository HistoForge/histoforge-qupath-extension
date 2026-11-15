package qupath.ext.histoforge.data;

import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.spatial.HierarchyDelta;

/** Opt-in benchmark for selected-region hierarchy normalization at supported scale. */
public final class MetricParentNormalizerBenchmark {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final UUID ANCHOR = new UUID(0x1000_0000_0000_4000L, 1L);
    private static final UUID INVALID_PARENT = new UUID(0x2000_0000_0000_4000L, 1L);

    private MetricParentNormalizerBenchmark() {}

    /** Measure normalization only; input construction and result verification are not timed. */
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: MetricParentNormalizerBenchmark <unchanged|disjoint|dense> <object-count>");
        }
        String mode = args[0];
        int objectCount = Integer.parseInt(args[1]);
        if (objectCount <= 0) {
            throw new IllegalArgumentException("object-count must be positive");
        }
        List<MetricPopulationCapture.ObjectFields> nodes =
                switch (mode) {
                    case "unchanged" -> unchanged(objectCount);
                    case "disjoint" -> disjoint(objectCount);
                    case "dense" -> dense(objectCount);
                    default -> throw new IllegalArgumentException("unknown mode: " + mode);
                };

        Optional<UUID> fallbackParent =
                "unchanged".equals(mode) ? Optional.empty() : Optional.of(ANCHOR);
        Set<UUID> administrativeParents = "unchanged".equals(mode) ? Set.of() : Set.of(ANCHOR);
        var allocationCounter = ManagementFactory.getThreadMXBean();
        long allocatedBefore = allocatedBytes(allocationCounter);
        long started = System.nanoTime();
        List<HierarchyDelta.ParentAssignment> result =
                MetricParentNormalizer.normalize(nodes, fallbackParent, administrativeParents);
        long elapsedNanos = System.nanoTime() - started;
        long allocatedAfter = allocatedBytes(allocationCounter);
        verify(mode, nodes, result);

        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema_version", 1);
        evidence.addProperty("mode", mode);
        evidence.addProperty("objects", objectCount);
        evidence.addProperty("changed_assignments", result.size());
        evidence.addProperty("elapsed_millis", elapsedNanos / 1_000_000.0);
        evidence.addProperty(
                "owning_thread_allocated_bytes",
                allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
                        ? allocatedAfter - allocatedBefore
                        : null);
        evidence.addProperty("objects_per_second", objectCount * 1_000_000_000.0 / elapsedNanos);
        evidence.addProperty("java_version", System.getProperty("java.version"));
        evidence.addProperty("os_name", System.getProperty("os.name"));
        evidence.addProperty("os_arch", System.getProperty("os.arch"));
        evidence.addProperty("maximum_heap_bytes", Runtime.getRuntime().maxMemory());
        System.out.println(
                "HISTOFORGE_METRIC_PARENT_NORMALIZER_BENCHMARK_JSON="
                        + new String(
                                CanonicalCodec.canonicalBytes(evidence), StandardCharsets.UTF_8));
    }

    private static long allocatedBytes(java.lang.management.ThreadMXBean counter) {
        if (counter instanceof com.sun.management.ThreadMXBean allocations
                && allocations.isThreadAllocatedMemorySupported()
                && allocations.isThreadAllocatedMemoryEnabled()) {
            return allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private static List<MetricPopulationCapture.ObjectFields> unchanged(int objectCount) {
        // Geometry is deliberately shared: this case isolates bookkeeping for valid root parents.
        var outline = MetricObjectGeometry.copyOf(rectangle(0, 0, 1, 1));
        List<MetricPopulationCapture.ObjectFields> nodes = new ArrayList<>(objectCount);
        for (int index = 0; index < objectCount; index++) {
            nodes.add(
                    new MetricPopulationCapture.ObjectFields(
                            objectId(index),
                            MetricTypes.MetricObjectType.DETECTION,
                            "Cell",
                            outline,
                            Optional.empty()));
        }
        return List.copyOf(nodes);
    }

    private static List<MetricPopulationCapture.ObjectFields> disjoint(int objectCount) {
        List<MetricPopulationCapture.ObjectFields> nodes = new ArrayList<>(objectCount);
        for (int index = 0; index < objectCount; index++) {
            double x = (index % 1024) * 2.0;
            double y = (index / 1024) * 2.0;
            nodes.add(node(index, rectangle(x, y, x + 1.0, y + 1.0)));
        }
        return List.copyOf(nodes);
    }

    private static List<MetricPopulationCapture.ObjectFields> dense(int objectCount) {
        List<MetricPopulationCapture.ObjectFields> nodes = new ArrayList<>(objectCount);
        for (int index = 0; index < objectCount; index++) {
            double radius = objectCount - index;
            nodes.add(node(index, rectangle(-radius, -radius, radius, radius)));
        }
        return List.copyOf(nodes);
    }

    private static MetricPopulationCapture.ObjectFields node(int index, Geometry geometry) {
        return new MetricPopulationCapture.ObjectFields(
                objectId(index),
                MetricTypes.MetricObjectType.DETECTION,
                "Cell",
                MetricObjectGeometry.copyOf(geometry),
                Optional.of(INVALID_PARENT));
    }

    private static UUID objectId(int index) {
        return new UUID(0x3000_0000_0000_4000L, Integer.toUnsignedLong(index) + 1L);
    }

    private static Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.toGeometry(new Envelope(minX, maxX, minY, maxY));
    }

    private static void verify(
            String mode,
            List<MetricPopulationCapture.ObjectFields> nodes,
            List<HierarchyDelta.ParentAssignment> result) {
        if ("unchanged".equals(mode)) {
            if (!result.isEmpty()) {
                throw new IllegalStateException("valid root parents were changed");
            }
            return;
        }
        if (result.size() != nodes.size()) {
            throw new IllegalStateException("normalization did not repair every invalid parent");
        }
        for (int index = 0; index < nodes.size(); index++) {
            Optional<UUID> expected =
                    "disjoint".equals(mode) || index == 0
                            ? Optional.of(ANCHOR)
                            : Optional.of(nodes.get(index - 1).objectId());
            HierarchyDelta.ParentAssignment change = result.get(index);
            if (!nodes.get(index).objectId().equals(change.objectId())
                    || !expected.equals(change.parentObjectId())) {
                throw new IllegalStateException("normalization selected the wrong ordered parent");
            }
        }
    }
}

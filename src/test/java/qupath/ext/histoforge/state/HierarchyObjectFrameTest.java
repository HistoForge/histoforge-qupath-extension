package qupath.ext.histoforge.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.DefaultTMAGrid;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.ROIs;

class HierarchyObjectFrameTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 10_000})
    void streamedObjectFrameHasExactlyThePreviousCanonicalBytes(int childCount) throws Exception {
        var parent = richAnnotation();
        for (int index = 0; index < childCount; index++) {
            var child = new PathDetectionObject();
            // Deliberately not UUID-sorted: persisted sibling order is part of the digest.
            child.setID(new UUID(index % 2 == 0 ? 0 : Long.MIN_VALUE, childCount - index));
            parent.addChildObject(child);
        }
        PathObject[] children = parent.getChildObjectsAsArray();
        byte[] expected = CanonicalCodec.canonicalBytes(previousObjectState(parent, true));
        byte[] expectedFrame =
                ByteBuffer.allocate(1 + Long.BYTES + expected.length)
                        .put((byte) 1)
                        .putLong(expected.length)
                        .put(expected)
                        .array();
        var recording = new RecordingDigest();
        var frameWriter =
                HierarchySemanticDigester.class.getDeclaredMethod(
                        "updateObjectFrame", MessageDigest.class, byte[].class, PathObject[].class);
        frameWriter.setAccessible(true);
        frameWriter.invoke(
                null,
                recording,
                CanonicalCodec.canonicalBytes(intrinsicState(parent, true)),
                children);

        byte[] actual = recording.digest();
        assertArrayEquals(expectedFrame, actual);
        assertEquals(
                actual.length - 1 - Long.BYTES, ByteBuffer.wrap(actual, 1, Long.BYTES).getLong());
        assertEquals(
                CanonicalCodec.sha256(expected),
                HierarchySemanticDigester.objectSemanticDigest(parent));
        assertEquals(
                CanonicalCodec.canonicalDigest(previousObjectState(parent, false)),
                HierarchySemanticDigester.objectNonClassSemanticDigest(parent));
    }

    @Test
    void fullHierarchyMatchesPreviousTreeEncodingWithAllSupportedObjectTypes() throws Exception {
        var hierarchy = new PathObjectHierarchy();
        var parent = richAnnotation();
        var roi = ROIs.createRectangleROI(2, 3, 4, 5, null);
        parent.addChildObjects(
                List.of(
                        PathObjects.createDetectionObject(roi, parent.getPathClass()),
                        PathObjects.createTileObject(roi, parent.getPathClass()),
                        PathObjects.createCellObject(
                                roi, ROIs.createEllipseROI(3, 4, 1, 2, null), null)));
        hierarchy.getRootObject().addChildObject(parent);
        hierarchy.setTMAGrid(
                DefaultTMAGrid.create(
                        List.of(PathObjects.createTMACoreObject(60, 60, 5, true)), 1));

        assertEquals(
                previousHierarchyDigest(hierarchy),
                HierarchySemanticDigester.capture(hierarchy, false).digest());
        var first = parent.getChildObjectsAsArray()[0];
        parent.removeChildObject(first);
        parent.addChildObject(first);
        assertEquals(
                previousHierarchyDigest(hierarchy),
                HierarchySemanticDigester.capture(hierarchy, false).digest());
    }

    @Test
    void traversalAcquiresEachChildArrayOnlyOnce() {
        var hierarchy = spy(new PathObjectHierarchy());
        var root = spy(hierarchy.getRootObject());
        doReturn(root).when(hierarchy).getRootObject();
        var parent = spy(richAnnotation());
        var child = spy(new PathDetectionObject());
        parent.addChildObject(child);
        root.addChildObject(parent);

        HierarchySemanticDigester.capture(hierarchy, false);

        for (PathObject object : List.of(root, parent, child)) {
            verify(object, times(1)).getChildObjectsAsArray();
        }
    }

    @Test
    void everySemanticPathStillRejectsMissingChildIdentity() {
        var hierarchy = new PathObjectHierarchy();
        var parent = richAnnotation();
        var child = spy(new PathDetectionObject());
        parent.addChildObject(child);
        hierarchy.getRootObject().addChildObject(parent);
        doReturn(null).when(child).getID();

        assertThrows(
                NullPointerException.class,
                () -> HierarchySemanticDigester.objectSemanticDigest(parent));
        assertThrows(
                NullPointerException.class,
                () -> HierarchySemanticDigester.objectNonClassSemanticDigest(parent));
        assertThrows(
                NullPointerException.class,
                () -> HierarchySemanticDigester.objectTransactionSemanticDigest(parent));
        assertThrows(
                NullPointerException.class,
                () -> HierarchySemanticDigester.capture(hierarchy, false));
    }

    private static PathObject richAnnotation() {
        var object =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(0, 0, 40, 40, null),
                        PathClass.fromString("Leydig cell: reviewed"));
        object.setName("Tissue \u03bc \ud83e\uddec \"quoted\"\n");
        object.setColor(0x123456);
        object.setLocked(true);
        object.getMetadata().put("notes", "backslash \\ and tab\t");
        object.getMetadata().put("empty", null);
        object.getMeasurementList().put("negative zero", -0.0);
        object.getMeasurementList().put("not finite", Double.NaN);
        object.getMeasurementList().put("infinity", Double.POSITIVE_INFINITY);
        return object;
    }

    // Keep the old child-array JSON construction as a test-only byte oracle. Intrinsic fields
    // still use the unchanged field capture; complete-revision goldens cover their encoding.
    private static JsonObject previousObjectState(PathObject object, boolean includeClass)
            throws Exception {
        JsonObject state = intrinsicState(object, includeClass);
        JsonArray ids = new JsonArray();
        for (PathObject child : object.getChildObjectsAsArray()) {
            ids.add(child.getID().toString());
        }
        state.add("child_ids", ids);
        assertEquals("child_ids", state.keySet().stream().sorted().findFirst().orElseThrow());
        return state;
    }

    private static JsonObject intrinsicState(PathObject object, boolean includeClass)
            throws Exception {
        var capture =
                HierarchySemanticDigester.class.getDeclaredMethod(
                        "objectState", PathObject.class, String.class, boolean.class, Map.class);
        capture.setAccessible(true);
        // Null keeps the reference path uncached, including repeated/derived PathClasses.
        return (JsonObject)
                capture.invoke(null, object, object.getID().toString(), includeClass, null);
    }

    private static String previousHierarchyDigest(PathObjectHierarchy hierarchy) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(CanonicalCodec.utf8Bytes("histoforge-image-hierarchy-v1"));
        var pending = new ArrayDeque<PathObject>();
        pending.push(hierarchy.getRootObject());
        while (!pending.isEmpty()) {
            PathObject object = pending.pop();
            previousFrame(digest, (byte) 1, previousObjectState(object, true));
            PathObject[] children = object.getChildObjectsAsArray();
            for (int index = children.length - 1; index >= 0; index--) {
                pending.push(children[index]);
            }
        }
        var grid = hierarchy.getTMAGrid();
        JsonObject gridState = new JsonObject();
        gridState.addProperty("present", grid != null);
        if (grid != null) {
            gridState.addProperty("grid_width", grid.getGridWidth());
            gridState.addProperty("grid_height", grid.getGridHeight());
            JsonArray cores = new JsonArray();
            grid.getTMACoreList().forEach(core -> cores.add(core.getID().toString()));
            gridState.add("core_ids", cores);
        }
        previousFrame(digest, (byte) 2, gridState);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void previousFrame(MessageDigest digest, byte marker, JsonObject state) {
        byte[] canonical = CanonicalCodec.canonicalBytes(state);
        digest.update(marker);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(canonical.length).array());
        digest.update(canonical);
    }

    private static final class RecordingDigest extends MessageDigest {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private RecordingDigest() {
            super("recording");
        }

        @Override
        protected void engineUpdate(byte value) {
            output.write(value);
        }

        @Override
        protected void engineUpdate(byte[] values, int offset, int length) {
            output.write(values, offset, length);
        }

        @Override
        protected byte[] engineDigest() {
            return output.toByteArray();
        }

        @Override
        protected void engineReset() {
            output.reset();
        }
    }
}

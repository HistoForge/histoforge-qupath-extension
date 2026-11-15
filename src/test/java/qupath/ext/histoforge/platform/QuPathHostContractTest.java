package qupath.ext.histoforge.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent.HierarchyEventType;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.roi.ROIs;

/** Executable evidence for the public QuPath 0.6 persistence and hierarchy boundary. */
class QuPathHostContractTest {

    private static final String IMAGE_PROPERTY = "histoforge:phase0-image-state";
    private static final String IMAGE_PROPERTY_VALUE = "schema=1;generation=fixture";
    private static final String OBJECT_PROPERTY = "histoforge:phase0-provenance";
    private static final String OBJECT_PROPERTY_VALUE = "schema=1;producer=fixture";

    @TempDir Path tempDirectory;

    @Test
    void publicProjectEntryRoundTripsStringStateObjectIdsAndParentingOffFxThread()
            throws Exception {
        ProjectImageEntry<BufferedImage> entry = createEntry("roundtrip").entry();
        UUID parentId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID childId = UUID.fromString("22222222-2222-4222-8222-222222222222");

        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            imageData.setProperty(IMAGE_PROPERTY, IMAGE_PROPERTY_VALUE);

            PathObject parent =
                    PathObjects.createAnnotationObject(
                            ROIs.createRectangleROI(1, 2, 20, 18, null),
                            PathClass.fromString("Tubule"));
            parent.setID(parentId);
            parent.getMetadata().put(OBJECT_PROPERTY, OBJECT_PROPERTY_VALUE);

            PathObject child =
                    PathObjects.createDetectionObject(
                            ROIs.createEllipseROI(5, 6, 4, 3, null),
                            PathClass.fromString("Spermatocyte"));
            child.setID(childId);
            parent.addChildObject(child);
            imageData.getHierarchy().addObject(parent);

            entry.saveImageData(imageData);
        }

        assertTrue(entry.hasImageData());
        try (ImageData<BufferedImage> reloaded = entry.readImageData()) {
            assertEquals(IMAGE_PROPERTY_VALUE, reloaded.getProperty(IMAGE_PROPERTY));

            Map<UUID, PathObject> objects = objectsById(reloaded.getHierarchy());
            PathObject parent = objects.get(parentId);
            PathObject child = objects.get(childId);
            assertNotNull(parent);
            assertNotNull(child);
            assertEquals("Tubule", parent.getPathClass().toString());
            assertEquals("Spermatocyte", child.getPathClass().toString());
            assertEquals(OBJECT_PROPERTY_VALUE, parent.getMetadata().get(OBJECT_PROPERTY));
            assertEquals(parentId, child.getParent().getID());
        }

        Map<UUID, PathObject> hierarchyOnly = objectsById(entry.readHierarchy());
        PathObject hierarchyParent = hierarchyOnly.get(parentId);
        PathObject hierarchyChild = hierarchyOnly.get(childId);
        assertNotNull(hierarchyParent);
        assertNotNull(hierarchyChild);
        assertEquals("Tubule", hierarchyParent.getPathClass().toString());
        assertEquals("Spermatocyte", hierarchyChild.getPathClass().toString());
        assertEquals(OBJECT_PROPERTY_VALUE, hierarchyParent.getMetadata().get(OBJECT_PROPERTY));
        assertEquals(parentId, hierarchyChild.getParent().getID());
    }

    @Test
    void canonicalHistoforgeProvenanceAndGenerationEvidenceSurviveQpdataReload() throws Exception {
        ProjectImageEntry<BufferedImage> entry = createEntry("histoforge-state").entry();
        UUID objectId = UUID.fromString("33333333-aaaa-4333-8333-333333333333");
        String provenanceText =
                fixture("/contracts/object-provenance-v1.json")
                        .getAsJsonArray("cases")
                        .get(0)
                        .getAsJsonObject()
                        .get("canonical")
                        .getAsString();
        ProvenanceCodec.ObjectProvenance expectedProvenance =
                ProvenanceCodec.decodeMetadataValue(provenanceText);
        String generationStoreText =
                fixture("/contracts/generation-store-v1.json").get("canonical").getAsString();
        GenerationStoreCodec.GenerationStore expectedStore =
                GenerationStoreCodec.parseGenerationStore(
                        CanonicalCodec.parseJson(generationStoreText));

        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            PathObject object =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(4, 5, 8, 7, null),
                            PathClass.fromString("Leydig cell"));
            object.setID(objectId);
            ProvenanceCodec.writeMetadata(object.getMetadata(), expectedProvenance);
            imageData.getHierarchy().addObject(object);
            ImageDataStateCodec.writeGenerationStore(imageData, expectedStore);
            entry.saveImageData(imageData);
        }

        try (ImageData<BufferedImage> reloaded = entry.readImageData()) {
            PathObject object = objectsById(reloaded.getHierarchy()).get(objectId);
            assertNotNull(object);
            assertEquals(
                    provenanceText,
                    object.getMetadata().get(HistoforgePersistenceKeys.OBJECT_PROVENANCE));
            assertEquals(
                    expectedProvenance,
                    ProvenanceCodec.readMetadata(object.getMetadata()).orElseThrow());
            assertEquals(
                    generationStoreText,
                    reloaded.getProperty(HistoforgePersistenceKeys.GENERATION_STORE));
            assertEquals(
                    expectedStore, ImageDataStateCodec.readGenerationStore(reloaded).orElseThrow());
        }
    }

    @Test
    void publicProjectEntryPropagatesSaveFailureFromUnavailableProjectRoot() throws Exception {
        EntryFixture fixture = createEntry("save-failure");

        try (ImageData<BufferedImage> imageData = fixture.entry().readImageData()) {
            Path displaced = tempDirectory.resolve("displaced-project");
            Files.move(fixture.projectDirectory(), displaced);
            Files.writeString(fixture.projectDirectory(), "blocks project directory recreation");

            assertThrows(IOException.class, () -> fixture.entry().saveImageData(imageData));
            assertFalse(fixture.entry().hasImageData());
        }
    }

    @Test
    void publicHierarchyEventsPreserveSourceHierarchyAndChangeType() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        var events = new ArrayList<qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent>();
        hierarchy.addListener(events::add);
        Object source = new Object();
        PathObject object =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 10, 10, null));

        hierarchy.addObject(object);
        assertEquals(1, events.size());
        assertSame(hierarchy, events.get(0).getSource());
        assertSame(hierarchy, events.get(0).getHierarchy());
        assertEquals(HierarchyEventType.ADDED, events.get(0).getEventType());

        events.clear();
        hierarchy.fireObjectClassificationsChangedEvent(source, java.util.List.of(object));
        assertEquals(1, events.size());
        assertSame(source, events.get(0).getSource());
        assertSame(hierarchy, events.get(0).getHierarchy());
        assertEquals(HierarchyEventType.CHANGE_CLASSIFICATION, events.get(0).getEventType());

        events.clear();
        hierarchy.fireHierarchyChangedEvent(source);
        assertEquals(1, events.size());
        assertSame(source, events.get(0).getSource());
        assertSame(hierarchy, events.get(0).getHierarchy());
        assertEquals(HierarchyEventType.OTHER_STRUCTURE_CHANGE, events.get(0).getEventType());
    }

    private EntryFixture createEntry(String name) throws Exception {
        Path projectDirectory = Files.createDirectory(tempDirectory.resolve(name + "-project"));
        Path source = tempDirectory.resolve(name + ".png");
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "PNG", source.toFile()));

        var project = Projects.createProject(projectDirectory.toFile(), BufferedImage.class);
        ProjectImageEntry<BufferedImage> entry;
        try (var server = ImageServers.buildServer(source.toUri())) {
            assertNotNull(server.getBuilder());
            entry = project.addImage(server.getBuilder());
        }
        assertNotNull(entry);
        return new EntryFixture(projectDirectory, entry);
    }

    private static com.google.gson.JsonObject fixture(String resource) throws IOException {
        try (var stream = QuPathHostContractTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing fixture " + resource);
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }

    private static Map<UUID, PathObject> objectsById(PathObjectHierarchy hierarchy) {
        return hierarchy.getAllObjects(false).stream()
                .collect(Collectors.toMap(PathObject::getID, Function.identity()));
    }

    private record EntryFixture(Path projectDirectory, ProjectImageEntry<BufferedImage> entry) {}
}

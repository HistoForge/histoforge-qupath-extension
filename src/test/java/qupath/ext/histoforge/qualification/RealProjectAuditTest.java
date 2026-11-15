package qupath.ext.histoforge.qualification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class RealProjectAuditTest {

    @TempDir Path temporary;

    @Test
    void auditsPersistedHierarchyWithoutLeakingProjectTextOrChangingBytes() throws Exception {
        Path project = createProject("patient-name-must-not-appear");
        byte[] projectBefore = Files.readAllBytes(project.resolve("project.qpproj"));
        byte[] dataBefore = Files.readAllBytes(project.resolve("data/1/data.qpdata"));

        JsonObject first = RealProjectAudit.auditProject(project);
        JsonObject second = RealProjectAudit.auditProject(project);

        assertEquals(first, second);
        assertTrue(first.get("read_only_project_tree_unchanged").getAsBoolean());
        assertTrue(first.get("declared_and_persisted_entry_ids_match").getAsBoolean());
        assertEquals(1, first.get("declared_image_entries").getAsInt());
        assertEquals(1, first.get("persisted_data_entries").getAsInt());
        assertEquals(0, first.get("image_servers_opened").getAsInt());
        assertEquals(0, first.get("pixel_bytes_read").getAsInt());
        JsonObject sourceTotals = first.getAsJsonObject("source_metadata_totals");
        assertEquals(1, sourceTotals.get("entries").getAsInt());
        assertEquals(
                1,
                sourceTotals
                        .getAsJsonObject("calibration_states")
                        .get("square-positive")
                        .getAsInt());
        assertEquals(1, sourceTotals.getAsJsonObject("dimensions_px").get("32x24").getAsInt());
        assertEquals(
                1,
                sourceTotals
                        .getAsJsonObject("positive_calibrations_mpp")
                        .get("0.25x0.25")
                        .getAsInt());
        assertEquals(2, first.getAsJsonObject("totals").get("objects").getAsInt());
        assertEquals(2, first.getAsJsonObject("totals").get("classified_objects").getAsInt());
        assertEquals(0, first.getAsJsonObject("totals").get("unclassified_objects").getAsInt());
        assertEquals(2, first.getAsJsonObject("totals").get("distinct_path_classes").getAsInt());
        assertEquals(
                1,
                first.getAsJsonObject("totals")
                        .getAsJsonObject("parent_child_classification")
                        .get("classified -> classified")
                        .getAsInt());
        assertEquals(
                1,
                first.getAsJsonObject("totals")
                        .getAsJsonObject("parent_child_classification")
                        .get("root -> classified")
                        .getAsInt());
        assertEquals(1, first.getAsJsonObject("totals").get("product_metadata_entries").getAsInt());
        assertEquals(
                1,
                first.getAsJsonObject("totals").get("distinct_product_metadata_keys").getAsInt());
        assertEquals(
                2,
                first.getAsJsonObject("totals")
                        .getAsJsonObject("classification_geometry_types")
                        .get("classified -> Polygon")
                        .getAsInt());
        assertEquals(2, first.getAsJsonObject("totals").get("geometry_components").getAsInt());
        assertEquals(0, first.getAsJsonObject("totals").get("geometry_holes").getAsInt());
        assertFalse(
                new String(CanonicalCodec.canonicalBytes(first), StandardCharsets.UTF_8)
                        .contains("patient-name-must-not-appear"));
        List<byte[]> after = snapshot(project);
        assertArrayEquals(projectBefore, after.get(0));
        assertArrayEquals(dataBefore, after.get(1));
    }

    @Test
    void refusesToWriteEvidenceInsideTheAuditedProject() throws Exception {
        Path project = createProject("private-name");
        Path output = project.resolve("audit.json");

        assertThrows(
                IllegalArgumentException.class,
                () -> RealProjectAudit.writeEvidence(project, output));
        assertFalse(Files.exists(output));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void refusesOutputWhoseExternalParentResolvesInsideProject() throws Exception {
        Path project = createProject("private-name");
        Path link = temporary.resolve("external-link");
        Files.createSymbolicLink(link, project);
        Path redirectedOutput = link.resolve("audit.json");

        assertThrows(
                IllegalArgumentException.class,
                () -> RealProjectAudit.writeEvidence(project, redirectedOutput));
        assertFalse(Files.exists(project.resolve("audit.json")));
    }

    @Test
    void emittedEvidenceBindsTheCompleteProbeClosure() throws Exception {
        Path project = createProject("private-name");
        Path output = temporary.resolve("evidence.json");

        RealProjectAudit.writeEvidence(project, output);

        JsonObject evidence =
                CanonicalCodec.parseJson(Files.readAllBytes(output)).getAsJsonObject();
        JsonObject runtime = evidence.getAsJsonObject("runtime");
        assertEquals(3, runtime.getAsJsonArray("probe_sources").size());
        Set<String> classNames = new TreeSet<>();
        for (var item : runtime.getAsJsonArray("probe_classes")) {
            classNames.add(item.getAsJsonObject().get("class_name").getAsString());
        }
        assertEquals(
                Set.of(
                        "qupath.ext.histoforge.qualification.RealProjectAudit",
                        "qupath.ext.histoforge.qualification.RealProjectAudit$FileIdentity",
                        "qupath.ext.histoforge.qualification.RealProjectAudit$ProjectSnapshot",
                        "qupath.ext.histoforge.qualification.RealProjectHierarchySummary",
                        "qupath.ext.histoforge.qualification.RealProjectHierarchySummary$Node",
                        "qupath.ext.histoforge.qualification.RealProjectSourceSummary"),
                classNames);
        String claimed = evidence.remove("evidence_digest").getAsString();
        assertEquals(CanonicalCodec.canonicalDigest(evidence), claimed);
    }

    @Test
    void classifiesCalibrationStatesFromPersistedMetadata() throws Exception {
        assertEquals("square-positive", calibrationState(projectImage(0.25, 0.25, "µm", "µm")));
        assertEquals(
                "anisotropic-positive", calibrationState(projectImage(0.25, 0.30, "µm", "µm")));
        assertEquals("nonpositive", calibrationState(projectImage(0.0, 0.25, "µm", "µm")));
        assertEquals("unsupported-unit", calibrationState(projectImage(0.25, 0.25, "nm", "nm")));
        assertEquals("missing", calibrationState(new JsonObject()));

        JsonObject missingAxis = projectImage(0.25, 0.25, "µm", "µm");
        missingAxis
                .getAsJsonObject("serverBuilder")
                .getAsJsonObject("metadata")
                .getAsJsonObject("pixelCalibration")
                .remove("pixelHeight");
        assertEquals("missing", calibrationState(missingAxis));
    }

    private Path createProject(String privateName) throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        JsonObject imageEntry = projectImage(0.25, 0.25, "µm", "µm");
        imageEntry.addProperty("entryID", 1);
        imageEntry.addProperty("imageName", privateName);
        JsonArray images = new JsonArray();
        images.add(imageEntry);
        JsonObject projectDocument = new JsonObject();
        projectDocument.add("images", images);
        Files.write(
                project.resolve("project.qpproj"), CanonicalCodec.canonicalBytes(projectDocument));
        Path data = Files.createDirectories(project.resolve("data/1"));
        Path image = temporary.resolve("source.png");
        BufferedImage buffered = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(buffered, "PNG", image.toFile());

        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        var parent =
                PathObjects.createAnnotationObject(
                        ROIs.createPolygonROI(
                                List.of(
                                        new Point2(1, 1),
                                        new Point2(20, 1),
                                        new Point2(20, 20),
                                        new Point2(1, 20)),
                                ImagePlane.getDefaultPlane()),
                        PathClass.fromString(privateName));
        var child =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(5, 5, 4, 3, ImagePlane.getDefaultPlane()),
                        PathClass.fromString(privateName + "-child"));
        child.getMetadata().put("histoforge:" + privateName, "opaque-test-value");
        child.getMeasurementList().put("Area µm^2", 12.0);
        parent.addChildObject(child);
        hierarchy.getRootObject().addChildObject(parent);

        try (var server = ImageServers.buildServer(image.toUri());
                var imageData = new ImageData<>(server, hierarchy, ImageType.BRIGHTFIELD_H_E)) {
            PathIO.writeImageData(data.resolve("data.qpdata"), imageData);
        }
        return project;
    }

    private static JsonObject projectImage(double mppX, double mppY, String unitX, String unitY) {
        JsonObject width = new JsonObject();
        width.addProperty("value", mppX);
        width.addProperty("unit", unitX);
        JsonObject height = new JsonObject();
        height.addProperty("value", mppY);
        height.addProperty("unit", unitY);
        JsonObject calibration = new JsonObject();
        calibration.add("pixelWidth", width);
        calibration.add("pixelHeight", height);
        JsonArray levels = new JsonArray();
        levels.add(new JsonObject());
        JsonObject metadata = new JsonObject();
        metadata.addProperty("width", 32);
        metadata.addProperty("height", 24);
        metadata.addProperty("sizeZ", 1);
        metadata.addProperty("sizeT", 1);
        metadata.add("levels", levels);
        metadata.add("pixelCalibration", calibration);
        JsonObject serverBuilder = new JsonObject();
        serverBuilder.add("metadata", metadata);
        JsonObject image = new JsonObject();
        image.add("serverBuilder", serverBuilder);
        return image;
    }

    private static String calibrationState(JsonObject image) throws Exception {
        return RealProjectSourceSummary.fromProjectImage(image).calibrationState();
    }

    private static List<byte[]> snapshot(Path project) throws Exception {
        return List.of(
                Files.readAllBytes(project.resolve("project.qpproj")),
                Files.readAllBytes(project.resolve("data/1/data.qpdata")));
    }
}

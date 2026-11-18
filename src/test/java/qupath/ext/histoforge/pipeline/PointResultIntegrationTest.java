package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.*;
import qupath.ext.histoforge.data.MetricSnapshotBuilder;
import qupath.ext.histoforge.data.MetricTypes;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.ext.histoforge.spatial.*;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Synthetic point results use ordinary staging, public persistence and detached metrics. */
public class PointResultIntegrationTest {
    private static final ModuleRef MODULE = new ModuleRef("histoforge", "point-result-test");
    private static final String GENERATION = "50000000-0000-4000-8000-000000000001";
    private static final String EMPTY_GENERATION = "50000000-0000-4000-8000-000000000002";
    private static final String CLASS = "Mitotic figure";
    private static final String SCORE = InferenceFeatureCodec.DETECTION_SCORE_MEASUREMENT;
    private static final SourceIdentityCodec.ImagePlaneIndex PLANE =
            new SourceIdentityCodec.ImagePlaneIndex(0, 0);
    private static final String SCRIPT =
            """
            /* @histoforge-metrics
            [{"name":"count","label":"Count","type":"scalar","decimals":0,"aggregation":"sum"},
             {"name":"density","label":"Density","type":"scalar","decimals":4,"aggregation":"mean"},
             {"name":"scores","label":"Scores","type":"distribution","decimals":8,"aggregation":"pool"}]
            */
            assert children.every { it.ROI.isPoint() && it.ROI.numPoints == 1 }
            return [count: children.size(), density: children.size() / roiAreaPx,
                    scores: children.collect { it.measurementList.get("HistoForge: Detection score") }]
            """;

    @TempDir Path directory;

    @Test
    void coincidentCentersKeepEveryUuidThroughStaging() {
        var generation =
                generate(
                        target(),
                        Optional.empty(),
                        List.of(
                                new double[] {5.5, 7.5, 0},
                                new double[] {5.5, 7.5, 0.5},
                                new double[] {5.5, 7.5, 0.5}),
                        GENERATION);
        assertEquals(3, generation.candidates().size());
        assertEquals(
                3,
                generation.candidates().stream()
                        .map(CandidateGeneration.SlideCandidate::candidateId)
                        .distinct()
                        .count());
        var hierarchy = new qupath.lib.objects.hierarchy.PathObjectHierarchy();
        hierarchy.getRootObject().addChildObject(boundary());
        var view = StagedRegionView.capture(target(), hierarchy);
        view.addGeneration(generation);
        assertEquals(3, view.objects().size());
    }

    @Test
    void exactTargetAndParentAdmissionPrecedeFloatProjection() throws Exception {
        var lower = new WKTReader().read("POLYGON ((0 0,1 0,0 1,0 0))");
        var upper = new WKTReader().read("POLYGON ((1 1,1 0,0 1,1 1))");
        double x = .600000004, y = .399999995;
        var point = new GeometryFactory().createPoint(new CoordinateXY(x, y));
        var projected = new GeometryFactory().createPoint(new CoordinateXY((float) x, (float) y));
        assertTrue(lower.covers(point));
        assertFalse(lower.covers(projected));
        assertFalse(upper.covers(point));
        assertTrue(upper.covers(projected));
        for (boolean parent : List.of(false, true)) {
            for (Geometry boundary : List.of(lower, upper)) {
                var selected = parent ? target() : target(boundary);
                var accepted =
                        generate(
                                selected,
                                parent ? Optional.of(boundary) : Optional.empty(),
                                List.of(new double[] {x, y, 0.5}),
                                GENERATION);
                assertEquals(boundary == lower ? 1 : 0, accepted.candidates().size());
                if (!accepted.candidates().isEmpty()) {
                    var nativePoint =
                            accepted.candidates().getFirst().roiSlide().getAllPoints().getFirst();
                    assertEquals((double) (float) x, nativePoint.getX());
                    assertEquals((double) (float) y, nativePoint.getY());
                }
            }
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        generate(
                                target(),
                                Optional.empty(),
                                List.of(new double[] {128, 1, 0.5}),
                                GENERATION));
    }

    @Test
    void stagedScoreSurvivesReclassificationAndPointParentsAreRejected() {
        var hierarchy = new qupath.lib.objects.hierarchy.PathObjectHierarchy();
        hierarchy.getRootObject().addChildObject(boundary());
        var generation =
                generate(
                        target(),
                        Optional.empty(),
                        List.of(new double[] {10.123456789, 20.25, 0.0}),
                        GENERATION);
        var view = StagedRegionView.capture(target(), hierarchy);
        view.addGeneration(generation);
        var candidate = generation.candidates().getFirst();
        assertEquals(
                0.0,
                view.object(candidate.candidateId()).orElseThrow().getMeasurementList().get(SCORE));
        view.applyClassChanges(
                List.of(
                        new HierarchyDelta.ClassChange(
                                UUID.fromString(candidate.candidateId()), CLASS, "Reviewed")));
        var finalized =
                view.finalizedGenerations(List.of(generation)).getFirst().candidates().getFirst();
        assertSame(candidate.roiSlide(), finalized.roiSlide());
        assertEquals(candidate.score(), finalized.score());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        view.eligibleParents(
                                MODULE,
                                new ModuleRecipe.Step(
                                        "child", "centers", List.of("Reviewed"), null)));
        assertTrue(
                view.eligibleParents(
                                MODULE,
                                new ModuleRecipe.Step("child", "centers", List.of("Other"), null))
                        .isEmpty());
    }

    @Test
    void publicSaveFreshProcessReadAndEmptyRedoPreserveThePointContract() throws Exception {
        Path source = directory.resolve("source.png");
        assertTrue(
                ImageIO.write(
                        new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB),
                        "PNG",
                        source.toFile()));
        var project =
                Projects.createProject(
                        Files.createDirectory(directory.resolve("project")).toFile(),
                        BufferedImage.class);
        var generation = savedGeneration();
        try (var server = ImageServers.buildServer(source.toUri())) {
            var entry = project.addImage(server.getBuilder());
            String revision;
            String metricDigest;
            try (var image = entry.readImageData()) {
                image.getHierarchy().getRootObject().addChildObject(boundary());
                var committer =
                        new ModelOutputCommitter(
                                image, generation.reservation().clientBuild().limits());
                var delta = committer.stage(request(generation, Set.of()));
                assertEquals(2, committer.apply(delta).addedObjects());
                assertExpected(image, generation);
                revision = imageRevision(image, generation);
                metricDigest = metrics(image, generation);
                entry.saveImageData(image);
            }
            project.syncChanges();
            Path log = directory.resolve("fresh-read.log");
            var command =
                    List.of(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Xmx512m",
                            "-Djava.awt.headless=true",
                            "-Djava.util.prefs.PreferencesFactory=qupath.ext.histoforge.platform.IsolatedPreferencesFactory",
                            "-cp",
                            childClassPath(),
                            PointResultIntegrationTest.class.getName(),
                            project.getPath().toString(),
                            revision,
                            metricDigest);
            var process =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
            boolean exited = process.waitFor(60, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            assertTrue(exited, () -> "fresh read timed out: " + readLog(log));
            assertEquals(0, process.exitValue(), () -> readLog(log));
            assertTrue(Files.readString(log).contains("POINT_SAVE_REOPEN_PASS"));
            try (var image = entry.readImageData()) {
                String before =
                        ImageDataRevisionDigester.hierarchySemanticDigest(image.getHierarchy());
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                generate(
                                        target(),
                                        Optional.empty(),
                                        List.of(new double[] {2, 2, Double.NaN}),
                                        EMPTY_GENERATION));
                assertEquals(
                        before,
                        ImageDataRevisionDigester.hierarchySemanticDigest(image.getHierarchy()));
                var empty = generate(target(), Optional.empty(), List.of(), EMPTY_GENERATION);
                var committer =
                        new ModelOutputCommitter(
                                image, generation.reservation().clientBuild().limits());
                committer.apply(committer.stage(request(empty, Set.of(generation.output()))));
                assertTrue(
                        TargetHierarchyPopulation.select(target(), image.getHierarchy()).isEmpty());
                assertTrue(
                        ImageDataStateCodec.readGenerationStore(image)
                                .orElseThrow()
                                .currentGenerationRecords()
                                .isEmpty());
                entry.saveImageData(image);
            }
            try (var image = entry.readImageData()) {
                assertTrue(
                        TargetHierarchyPopulation.select(target(), image.getHierarchy()).isEmpty());
            }
        }
    }

    /** A separate JVM cannot retain live objects or the first process's metric cache. */
    public static void main(String[] args) throws Exception {
        // The desktop host initializes server adapters before loading projects. Match that
        // public bootstrap in this standalone reader without starting JavaFX or the real app.
        ImageServers.getServerBuilderFactory();
        var project = ProjectIO.loadProject(Path.of(args[0]).toFile(), BufferedImage.class);
        var generation = savedGeneration();
        try (var image = project.getImageList().getFirst().readImageData()) {
            assertEquals(128, image.getServer().getWidth());
            assertEquals(128, image.getServer().getHeight());
            assertExpected(image, generation);
            assertEquals(args[1], imageRevision(image, generation));
            assertEquals(args[2], metrics(image, generation));
        }
        System.out.println("POINT_SAVE_REOPEN_PASS");
    }

    private static CandidateGeneration savedGeneration() {
        return generate(
                target(),
                Optional.empty(),
                List.of(
                        new double[] {10.123456789, 20.23456789, 0.0},
                        new double[] {30.5, 40.5, (double) 0.9f}),
                GENERATION);
    }

    private static String imageRevision(ImageData<?> image, CandidateGeneration generation) {
        return ImageDataRevisionDigester.capture(
                        "point-image",
                        "a".repeat(64),
                        image,
                        generation.reservation().clientBuild().limits())
                .imageDataContentDigest();
    }

    private static void assertExpected(ImageData<?> image, CandidateGeneration generation) {
        var objects = TargetHierarchyPopulation.select(target(), image.getHierarchy());
        assertEquals(2, objects.size());
        assertEquals(
                generation.candidates().stream()
                        .map(CandidateGeneration.SlideCandidate::candidateId)
                        .sorted()
                        .toList(),
                objects.stream().map(object -> object.getID().toString()).sorted().toList());
        for (var candidate : generation.candidates()) {
            var object =
                    objects.stream()
                            .filter(
                                    value ->
                                            value.getID()
                                                    .toString()
                                                    .equals(candidate.candidateId()))
                            .findFirst()
                            .orElseThrow();
            assertEquals(candidate.roiSlide().getAllPoints(), object.getROI().getAllPoints());
            assertEquals(CLASS, object.getPathClass().toString());
            assertEquals(candidate.score().orElseThrow(), object.getMeasurementList().get(SCORE));
            assertEquals(PipelineTestData.ROI_ID, object.getParent().getID().toString());
            var provenance = ProvenanceCodec.readMetadata(object.getMetadata()).orElseThrow();
            assertEquals(generation.output(), provenance.output());
            assertEquals(GENERATION, provenance.outputGenerationId());
        }
        assertEquals(
                Set.of(GENERATION),
                ImageDataStateCodec.readGenerationStore(image)
                        .orElseThrow()
                        .currentGenerationRecords()
                        .keySet());
        assertTrue(MetricSnapshotBuilder.canCaptureWithoutReload(image.getHierarchy()));
    }

    private static String metrics(ImageData<?> image, CandidateGeneration generation) {
        var identity = generation.backend().module();
        var module =
                new ModuleSnapshot(
                        "fixture",
                        identity,
                        new JsonObject(),
                        "a".repeat(64),
                        Map.of(),
                        Map.of(),
                        Optional.of(
                                new ModuleSnapshot.MetricScriptSnapshot(
                                        new ModuleSnapshot.ScriptSnapshot(
                                                "metrics.groovy",
                                                SCRIPT,
                                                CanonicalCodec.sha256(
                                                        CanonicalCodec.utf8Bytes(SCRIPT))),
                                        ScriptHeaderParser.parseMetrics(SCRIPT))));
        var prepared =
                MetricSnapshotBuilder.prepare(
                        generation.reservation().clientBuild(),
                        module,
                        List.of(target()),
                        generation.inputEvidence().rasterInput().frame().sourceRevision(),
                        image.getHierarchy());
        var evaluation = new MetricsEngine().evaluate(prepared, Map.of(), false, () -> false);
        assertTrue(evaluation.complete(), evaluation.regionFailures().toString());
        var values = evaluation.regionResults().get(target().key()).values();
        assertEquals(new MetricTypes.Scalar(2), values.get("count"));
        assertEquals(new MetricTypes.Scalar(2.0 / 10000), values.get("density"));
        assertEquals(
                List.of(0.0, (double) 0.9f),
                ((MetricTypes.Distribution) values.get("scores"))
                        .values().stream().sorted().toList());
        return prepared.snapshot().metricInput().metricInputDigest();
    }

    private static ModelOutputCommitter.StagingRequest request(
            CandidateGeneration generation, Set<ExecutionScopeCodec.OutputKey> closure) {
        return new ModelOutputCommitter.StagingRequest(
                PipelineTestData.ACTION_ID,
                true,
                generation.reservation().clientBuild(),
                PipelineTestData.DEPLOYMENT_ID,
                closure,
                List.of(generation),
                List.of(),
                Instant.parse("2026-11-16T12:00:00Z"));
    }

    private static CandidateGeneration generate(
            ExecutionScopeCodec.TargetRegion target,
            Optional<Geometry> parent,
            List<double[]> points,
            String generationId) {
        ExecutionScopeCodec.ExecutionScope scope =
                parent.isPresent()
                        ? new ExecutionScopeCodec.ParentScope(
                                target.key(), "60000000-0000-4000-8000-000000000001")
                        : new ExecutionScopeCodec.FieldScope(target.key());
        var template =
                PipelineTestData.generation(
                        MODULE, "centers", generationId, scope, target, parent, List.of());
        var input = template.inputEvidence().rasterInput();
        var budget = new FiniteInferenceCodec.ClientResultBudget(100000, 100000, 10000, 100, 100);
        var request =
                new FiniteInferenceCodec.FiniteInferenceRequest(
                        FiniteInferenceCodec.SCHEMA_VERSION,
                        template.reservation().actionId(),
                        generationId,
                        0,
                        template.output().producer(),
                        template.backend(),
                        input,
                        budget);
        var features = new ArrayList<InferenceFeatureCodec.Feature>();
        var occurrences = new java.util.HashMap<String, Integer>();
        for (double[] point : points) {
            var xy = new JsonArray();
            xy.add(point[0]);
            xy.add(point[1]);
            var preimage = new JsonArray();
            preimage.add(CLASS);
            preimage.add(xy);
            preimage.add(point[2]);
            String key =
                    new String(
                            CanonicalCodec.canonicalBytes(preimage),
                            java.nio.charset.StandardCharsets.UTF_8);
            int occurrence = occurrences.getOrDefault(key, 0);
            occurrences.put(key, occurrence + 1);
            preimage.add(occurrence);
            features.add(
                    new InferenceFeatureCodec.Feature(
                            CanonicalCodec.sha256(CanonicalCodec.canonicalBytes(preimage)),
                            new GeometryFactory().createPoint(new CoordinateXY(point[0], point[1])),
                            CLASS,
                            OptionalDouble.of(point[2])));
        }
        features.sort(Comparator.comparing(InferenceFeatureCodec.Feature::id));
        var checked =
                InferenceFeatureCodec.validate(
                        InferenceFeatureCodec.toJson(
                                new InferenceFeatureCodec.FeatureCollection(features)),
                        Set.of(CLASS),
                        InferenceFeatureCodec.GeometryKind.POINT);
        var meta =
                new FiniteInferenceCodec.BackendResultMeta(
                        FiniteInferenceCodec.SCHEMA_VERSION,
                        request.actionId(),
                        generationId,
                        "fc-fixture",
                        0,
                        request.producer(),
                        request.expectedBackend(),
                        FiniteInferenceCodec.buildBackendInputIdentity(input),
                        FiniteInferenceCodec.CoordinateSpace.RASTER_LOCAL);
        var source = (RasterInputCodec.SourceView) input.sourceView();
        var planned =
                new SourceInputPlan.PlannedModelInput(
                        template.output(),
                        template.inputEvidence().acceptance(),
                        input.frame(),
                        new SourceInputPlan.SourceViewPlan(
                                source.seriesIndex(),
                                source.resolutionLevel(),
                                source.plane(),
                                source.pixelRect(),
                                source.viewPixelsToSlide()));
        var existing =
                new CandidatePipeline.ExistingObjectSnapshot(
                        PipelineTestData.square(0, 0, 100, 100),
                        Optional.of(CLASS),
                        PLANE,
                        CandidatePipeline.ExistingObjectType.ANNOTATION,
                        false,
                        false);
        return CandidatePipeline.generateFinite(
                request,
                new FiniteInferenceCodec.InferenceResult(meta, checked),
                planned,
                (BackendExecutionCodec.FiniteCall) template.execution(),
                template.reservation(),
                List.of(existing));
    }

    private static ExecutionScopeCodec.TargetRegion target() {
        return PipelineTestData.target(new ExecutionScopeCodec.Roi(PipelineTestData.ROI_ID));
    }

    private static ExecutionScopeCodec.TargetRegion target(Geometry geometry) {
        return new ExecutionScopeCodec.TargetRegion(
                target().key(), geometry, PLANE, CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    private static PathObject boundary() {
        var object =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        object.setID(UUID.fromString(PipelineTestData.ROI_ID));
        return object;
    }

    private static String childClassPath() throws Exception {
        var entries = new LinkedHashSet<String>();
        for (ClassLoader loader = PointResultIntegrationTest.class.getClassLoader();
                loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls)
                for (var url : urls.getURLs()) entries.add(Path.of(url.toURI()).toString());
        }
        entries.addAll(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
        return String.join(File.pathSeparator, entries);
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (Exception failure) {
            return failure.toString();
        }
    }
}

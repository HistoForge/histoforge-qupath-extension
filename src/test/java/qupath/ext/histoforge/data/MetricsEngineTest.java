package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.data.MetricTypes.Distribution;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.ext.histoforge.data.MetricTypes.Missing;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.pipeline.RunResults;

/** Detached execution, exact cache, schema validation, and value-limit behavior. */
class MetricsEngineTest {

    @Test
    void completePublishedResultIsReusedOnlyForTheExactRevisionAndInput() {
        AtomicInteger calls = new AtomicInteger();
        MetricsEngine engine =
                new MetricsEngine(
                        (script, region, imageId, mppX, mppY) -> {
                            calls.incrementAndGet();
                            return Map.of("count", 1);
                        });
        MetricScriptSnapshot script =
                MetricTestData.script(
                        "return [count: 1]",
                        MetricTypesTest.scalar("count", MetricTypes.AggregationType.SUM));
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        script,
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A),
                        MetricTestData.region(MetricTestData.ROI_B));

        MetricsEngine.Evaluation first = engine.evaluate(input, Map.of(), true, () -> false);
        engine.publish(first, MetricTestData.revision('1'));
        MetricsEngine.Evaluation cached = engine.evaluate(input, Map.of(), true, () -> false);
        MetricPreparation changedRevision =
                new MetricPreparation(
                        new MetricTypes.MetricImageSnapshot(
                                input.snapshot().metricInput(), MetricTestData.revision('2')),
                        List.of());
        MetricsEngine.Evaluation missed =
                engine.evaluate(changedRevision, Map.of(), true, () -> false);

        assertEquals(4, calls.get());
        assertFalse(first.cacheHit());
        assertTrue(cached.cacheHit());
        assertFalse(missed.cacheHit());
        assertEquals(2, cached.regionResults().size());
    }

    @Test
    void lookupOnlyPathNeverExecutesAndPresenceIsOnlyACostHint() {
        AtomicInteger calls = new AtomicInteger();
        MetricsEngine engine =
                new MetricsEngine(
                        (script, region, imageId, mppX, mppY) -> {
                            calls.incrementAndGet();
                            return Map.of("count", 1);
                        });
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        MetricTestData.script(
                                "return [count: 1]",
                                MetricTypesTest.scalar("count", MetricTypes.AggregationType.SUM)),
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A));
        MetricsEngine.Evaluation computed = engine.evaluate(input, Map.of(), false, () -> false);
        engine.publish(computed, MetricTestData.revision('1'));

        assertTrue(engine.hasCachedResult(MetricTestData.IMAGE_ID, MetricTestData.MODULE_REF));
        assertEquals(1, calls.get());
        assertEquals(computed.result(), engine.exactComplete(input).orElseThrow());
        assertEquals(1, calls.get());

        MetricPreparation changedRevision =
                new MetricPreparation(
                        new MetricTypes.MetricImageSnapshot(
                                input.snapshot().metricInput(), MetricTestData.revision('2')),
                        List.of());
        assertTrue(engine.exactComplete(changedRevision).isEmpty());
        assertFalse(engine.hasCachedResult(MetricTestData.IMAGE_ID, MetricTestData.MODULE_REF));
        assertEquals(1, calls.get());
    }

    @Test
    void realGroovyUsesAFreshScriptInstanceForEachRegion() {
        MetricScriptSnapshot script =
                MetricTestData.script(
                        """
                        import groovy.transform.Field
                        @Field int calls = 0
                        calls++
                        return [count: calls]
                        """,
                        MetricTypesTest.scalar("count", MetricTypes.AggregationType.SUM));
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        script,
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A),
                        MetricTestData.region(MetricTestData.ROI_B));

        MetricsEngine.Evaluation result =
                new MetricsEngine().evaluate(input, Map.of(), false, () -> false);

        assertTrue(result.complete());
        result.regionResults()
                .values()
                .forEach(region -> assertEquals(new Scalar(1.0), region.values().get("count")));
    }

    @Test
    void validationPreservesZeroEmptyAndMissingAndFailsOnlyTheBadRegion() {
        AtomicInteger calls = new AtomicInteger();
        MetricDefinition zero = MetricTypesTest.scalar("zero", MetricTypes.AggregationType.SUM);
        MetricDefinition values = MetricTypesTest.distribution("values");
        MetricDefinition missing =
                MetricTypesTest.scalar("missing", MetricTypes.AggregationType.MEAN);
        MetricDefinition missingValues = MetricTypesTest.distribution("missing_values");
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 30),
                        MetricTestData.script("metric", zero, values, missing, missingValues),
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A),
                        MetricTestData.region(MetricTestData.ROI_B));
        MetricsEngine engine =
                new MetricsEngine(
                        (script, region, imageId, mppX, mppY) -> {
                            if (calls.getAndIncrement() != 0) {
                                return Map.of("wrong", 1);
                            }
                            Map<String, Object> result = new java.util.LinkedHashMap<>();
                            result.put("zero", 0);
                            result.put("values", List.of());
                            result.put("missing", null);
                            result.put("missing_values", null);
                            return result;
                        });

        MetricsEngine.Evaluation result = engine.evaluate(input, Map.of(), false, () -> false);

        var first = result.regionResults().get(new ExecutionScopeCodec.Roi(MetricTestData.ROI_A));
        assertEquals(new Scalar(0.0), first.values().get("zero"));
        assertEquals(new Distribution(List.of()), first.values().get("values"));
        assertInstanceOf(Missing.class, first.values().get("missing"));
        assertInstanceOf(Missing.class, first.values().get("missing_values"));
        assertEquals(1, result.regionFailures().size());
        assertEquals(
                RunResults.FailureType.METRICS,
                result.regionFailures()
                        .get(new ExecutionScopeCodec.Roi(MetricTestData.ROI_B))
                        .type());
        assertEquals(2, calls.get());
    }

    @Test
    void explicitMissingResultIsCompletePublishableAndExactlyReusable() {
        AtomicInteger calls = new AtomicInteger();
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        MetricTestData.script(
                                "metric",
                                MetricTypesTest.scalar("missing", MetricTypes.AggregationType.MEAN),
                                MetricTypesTest.distribution("missing_values")),
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A));
        MetricsEngine engine =
                new MetricsEngine(
                        (script, region, imageId, mppX, mppY) -> {
                            calls.incrementAndGet();
                            Map<String, Object> result = new java.util.LinkedHashMap<>();
                            result.put("missing", null);
                            result.put("missing_values", null);
                            return result;
                        });

        MetricsEngine.Evaluation first = engine.evaluate(input, Map.of(), true, () -> false);
        engine.publish(first, MetricTestData.revision('1'));
        MetricsEngine.Evaluation cached = engine.evaluate(input, Map.of(), true, () -> false);

        assertTrue(first.complete());
        assertFalse(first.cacheHit());
        first.regionResults()
                .values()
                .forEach(
                        region ->
                                region.values()
                                        .values()
                                        .forEach(value -> assertInstanceOf(Missing.class, value)));
        assertTrue(cached.complete());
        assertTrue(cached.cacheHit());
        assertEquals(first.result(), cached.result());
        assertEquals(1, calls.get());
    }

    @Test
    void invalidValuesAndRuntimeExceptionsRemainRegionFailures() {
        MetricDefinition scalar = MetricTypesTest.scalar("value", MetricTypes.AggregationType.MEAN);
        for (Object value :
                List.of(
                        "not a number",
                        Double.NaN,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY)) {
            assertMetricFailure(
                    scalar, (script, region, imageId, mppX, mppY) -> Map.of("value", value));
        }

        MetricDefinition distribution = MetricTypesTest.distribution("value");
        for (Object value :
                List.of(
                        "not a list",
                        List.of("not a number"),
                        java.util.Arrays.asList(1.0, null),
                        List.of(Double.NaN),
                        List.of(Double.POSITIVE_INFINITY),
                        List.of(Double.NEGATIVE_INFINITY))) {
            assertMetricFailure(
                    distribution, (script, region, imageId, mppX, mppY) -> Map.of("value", value));
        }

        assertMetricFailure(
                scalar,
                (script, region, imageId, mppX, mppY) -> {
                    throw new IllegalStateException("metric computation failed");
                });
    }

    @Test
    void prefailedRegionsAreNotExecutedAndLimitsFailClosedWithoutCaching() {
        AtomicInteger calls = new AtomicInteger();
        MetricScriptSnapshot script =
                MetricTestData.script(
                        "return [values: [1, 2]]", MetricTypesTest.distribution("values"));
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(1, 10),
                        script,
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A),
                        MetricTestData.region(MetricTestData.ROI_B));
        var blocked = new ExecutionScopeCodec.Roi(MetricTestData.ROI_A);
        MetricsEngine engine =
                new MetricsEngine(
                        (source, region, imageId, mppX, mppY) -> {
                            calls.incrementAndGet();
                            return Map.of("values", List.of(1, 2));
                        });
        MetricsEngine.Evaluation result =
                engine.evaluate(
                        input,
                        Map.of(
                                blocked,
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.METRICS,
                                        "model transaction failed")),
                        false,
                        () -> false);

        assertEquals(1, calls.get());
        assertEquals(2, result.regionFailures().size());
        assertEquals(
                RunResults.FailureType.LOCAL_CAPACITY,
                result.regionFailures()
                        .get(new ExecutionScopeCodec.Roi(MetricTestData.ROI_B))
                        .type());
        engine.publish(result, MetricTestData.revision('1'));
        assertEquals(0, engine.cacheSize());
    }

    @Test
    void compilationFailureOccursOnceBeforeRegionalIsolation() {
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        MetricsEngine engine =
                new MetricsEngine(
                        new MetricsEngine.ScriptInvoker() {
                            @Override
                            public void prepare(MetricScriptSnapshot script) {
                                preparations.incrementAndGet();
                                throw new IllegalArgumentException("invalid Groovy syntax");
                            }

                            @Override
                            public Object run(
                                    MetricScriptSnapshot script,
                                    MetricTypes.MetricRegionSnapshot region,
                                    String imageId,
                                    double mppX,
                                    double mppY) {
                                runs.incrementAndGet();
                                return Map.of("count", 1);
                            }
                        });
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        MetricTestData.script(
                                "invalid",
                                MetricTypesTest.scalar("count", MetricTypes.AggregationType.SUM)),
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A),
                        MetricTestData.region(MetricTestData.ROI_B));

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.evaluate(input, Map.of(), false, () -> false));
        assertEquals(1, preparations.get());
        assertEquals(0, runs.get());
    }

    @Test
    void imageValueLimitFailsTheCurrentAndEveryRemainingRegionWithoutExecution() {
        AtomicInteger calls = new AtomicInteger();
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 3),
                        MetricTestData.script("values", MetricTypesTest.distribution("values")),
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A),
                        MetricTestData.region(MetricTestData.ROI_B),
                        MetricTestData.region(MetricTestData.ROI_C));
        MetricsEngine engine =
                new MetricsEngine(
                        (script, region, imageId, mppX, mppY) -> {
                            calls.incrementAndGet();
                            return Map.of("values", List.of(1, 2));
                        });

        MetricsEngine.Evaluation result = engine.evaluate(input, Map.of(), false, () -> false);

        assertEquals(2, calls.get());
        assertEquals(
                Set.of(new ExecutionScopeCodec.Roi(MetricTestData.ROI_A)),
                result.regionResults().keySet());
        assertEquals(
                Set.of(
                        new ExecutionScopeCodec.Roi(MetricTestData.ROI_B),
                        new ExecutionScopeCodec.Roi(MetricTestData.ROI_C)),
                result.regionFailures().keySet());
        result.regionFailures()
                .values()
                .forEach(
                        failure ->
                                assertEquals(
                                        RunResults.FailureType.LOCAL_CAPACITY, failure.type()));
    }

    @Test
    void changedInputMissAndIncompleteResultEvictThePriorCompleteEntry() {
        AtomicInteger calls = new AtomicInteger();
        MetricScriptSnapshot good =
                MetricTestData.script(
                        "good", MetricTypesTest.scalar("count", MetricTypes.AggregationType.SUM));
        MetricScriptSnapshot bad =
                MetricTestData.script(
                        "bad", MetricTypesTest.scalar("count", MetricTypes.AggregationType.SUM));
        MetricsEngine engine =
                new MetricsEngine(
                        (script, region, imageId, mppX, mppY) -> {
                            calls.incrementAndGet();
                            return script.digest().equals(good.digest())
                                    ? Map.of("count", 1)
                                    : Map.of("wrong", 1);
                        });
        MetricPreparation original =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        good,
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A));
        MetricPreparation changed =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        bad,
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A));

        MetricsEngine.Evaluation complete = engine.evaluate(original, Map.of(), true, () -> false);
        engine.publish(complete, MetricTestData.revision('1'));
        MetricsEngine.Evaluation incomplete = engine.evaluate(changed, Map.of(), true, () -> false);
        engine.publish(incomplete, MetricTestData.revision('1'));
        MetricsEngine.Evaluation recomputed =
                engine.evaluate(original, Map.of(), true, () -> false);

        assertFalse(incomplete.complete());
        assertFalse(recomputed.cacheHit());
        assertEquals(3, calls.get());
    }

    private static void assertMetricFailure(
            MetricDefinition definition, MetricsEngine.ScriptInvoker invoker) {
        MetricPreparation input =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        MetricTestData.script("metric", definition),
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A));
        MetricsEngine engine = new MetricsEngine(invoker);

        MetricsEngine.Evaluation result = engine.evaluate(input, Map.of(), false, () -> false);
        engine.publish(result, MetricTestData.revision('1'));

        assertFalse(result.complete());
        assertTrue(result.regionResults().isEmpty());
        assertEquals(1, result.regionFailures().size());
        assertEquals(
                RunResults.FailureType.METRICS,
                result.regionFailures()
                        .get(new ExecutionScopeCodec.Roi(MetricTestData.ROI_A))
                        .type());
        assertEquals(0, engine.cacheSize());
    }
}

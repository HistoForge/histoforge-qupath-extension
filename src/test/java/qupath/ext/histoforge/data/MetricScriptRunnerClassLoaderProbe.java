package qupath.ext.histoforge.data;

import java.util.Map;
import java.util.Optional;
import qupath.ext.histoforge.data.MetricTypes.AggregationType;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricType;
import qupath.ext.histoforge.data.MetricTypes.Scalar;

/** Child-loader probe that exercises the production metric-script runner. */
public final class MetricScriptRunnerClassLoaderProbe {

    private MetricScriptRunnerClassLoaderProbe() {}

    /** Compile and execute a metric script that imports an extension-owned class. */
    public static String run() {
        String source =
                """
                import qupath.ext.histoforge.pipeline.ScriptCalibrationGate

                def calibration = ScriptCalibrationGate.requireSupported(
                    pixelWidthMicrons,
                    pixelHeightMicrons
                )
                return [value: calibration.mppX()]
                """;
        MetricDefinition definition =
                new MetricDefinition(
                        "value",
                        "Value",
                        MetricType.SCALAR,
                        Optional.empty(),
                        2,
                        AggregationType.MEAN);
        var preparation =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        MetricTestData.script(source, definition),
                        MetricTestData.revision('1'),
                        MetricTestData.region(MetricTestData.ROI_A));

        var evaluation = new MetricsEngine().evaluate(preparation, Map.of(), false, () -> false);

        if (!evaluation.complete()) {
            throw new AssertionError("metric probe failed: " + evaluation.regionFailures());
        }
        Object value = evaluation.regionResults().values().iterator().next().values().get("value");
        if (!new Scalar(0.25).equals(value)) {
            throw new AssertionError("unexpected metric probe value: " + value);
        }
        return "metric";
    }
}

package qupath.ext.histoforge.pipeline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.FeatureStreamDecoder;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes;
import qupath.ext.histoforge.large.RecoveryResultFiles;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;

/** Exact compressed feature fixtures shared by fresh dispatch and local-replay tests. */
final class FeatureStreamTestData {
    private FeatureStreamTestData() {}

    static byte[] compressed(InferenceFeatureCodec.FeatureCollection features) {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            for (var feature : InferenceFeatureCodec.toJson(features).getAsJsonArray("features")) {
                gzip.write(0x1e);
                gzip.write(CanonicalCodec.canonicalBytes(feature));
                gzip.write('\n');
            }
        } catch (IOException failure) {
            throw new AssertionError("In-memory feature encoding failed", failure);
        }
        return bytes.toByteArray();
    }

    static CompleteModelResults.RetainedFeatures retain(
            LargeActionHandleStore store,
            LargeActionTypes.LargeActionHandle handle,
            ModelRunRequestCodec.ModelRunRequest request,
            ModelSnapshot model,
            ModelRunPublicationCodec.InputResult result,
            InferenceFeatureCodec.FeatureCollection features)
            throws IOException {
        var file =
                RecoveryResultFiles.record(
                        handle, request.modelRunId(), result.featureStreamArtifact());
        var files = store.resultFiles();
        var validated =
                files.retain(
                        file,
                        new ByteArrayInputStream(compressed(features)),
                        stream ->
                                FeatureStreamDecoder.validate(
                                        stream,
                                        request.resultBudget(),
                                        Set.copyOf(model.output().emittedClasses()),
                                        result.featureCount(),
                                        result.vertexCount()));
        var input =
                request.inputs().stream()
                        .filter(value -> value.inputId() == result.inputId())
                        .findFirst()
                        .orElseThrow();
        validated.requireInsideRaster(input.rasterInput().frame().transform().raster());
        return new CompleteModelResults.RetainedFeatures(files, file, validated);
    }
}

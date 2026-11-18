package qupath.ext.histoforge.client;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import qupath.ext.histoforge.contract.FeatureStreamDecoder.ValidatedFeatureStream;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.ClientResultBudget;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;

/** Applies one run's aggregate limits whether its feature files come from disk or the gateway. */
public final class ModelResultReader {
    private ModelResultReader() {}

    public static Map<Integer, ValidatedFeatureStream> read(
            ModelRunPublicationCodec.Manifest manifest,
            ClientResultBudget budget,
            InputReader reader)
            throws IOException, InterruptedException, InferenceException {
        long transferred = 0;
        long decoded = 0;
        long features = 0;
        long vertices = 0;
        Map<Integer, ValidatedFeatureStream> completed = new LinkedHashMap<>();
        for (var input : manifest.completedInputs()) {
            if (input.featureStreamArtifact().sizeBytes()
                            > budget.maxTransferredBytes() - transferred
                    || input.featureCount() > budget.maxFeatures() - features
                    || input.vertexCount() > budget.maxVertices() - vertices) {
                throw new InferenceException(
                        "Manifest feature streams exceed the remaining result budget");
            }
            ClientResultBudget remaining =
                    new ClientResultBudget(
                            budget.maxTransferredBytes() - transferred,
                            Math.max(1, budget.maxDecodedBytes() - decoded),
                            budget.maxRecordBytes(),
                            Math.max(1, budget.maxFeatures() - features),
                            Math.max(1, budget.maxVertices() - vertices));
            ValidatedFeatureStream value = reader.read(input, remaining);
            transferred = Math.addExact(transferred, input.featureStreamArtifact().sizeBytes());
            decoded = Math.addExact(decoded, value.decodedBytes());
            features = Math.addExact(features, value.featureCount());
            vertices = Math.addExact(vertices, value.vertexCount());
            if (transferred > budget.maxTransferredBytes()
                    || decoded > budget.maxDecodedBytes()
                    || features > budget.maxFeatures()
                    || vertices > budget.maxVertices()) {
                throw new InferenceException(
                        "Aggregate feature streams exceed the accepted result budget");
            }
            completed.put(input.inputId(), value);
        }
        return Collections.unmodifiableMap(completed);
    }

    @FunctionalInterface
    public interface InputReader {
        ValidatedFeatureStream read(
                ModelRunPublicationCodec.InputResult input, ClientResultBudget remaining)
                throws IOException, InterruptedException, InferenceException;
    }
}

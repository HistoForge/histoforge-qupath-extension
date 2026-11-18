package qupath.ext.histoforge.training;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.TrainingDatasetGatewayClient;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.ExistingTrainingDataset;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetChunkRange;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetStartRequest;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetStartResult;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetUploadAccepted;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PreparedTrainingDataset;

/** Publishes one prepared immutable training archive through the negotiated chunk protocol. */
public final class TrainingDatasetUploader {

    public static final int CHUNK_SIZE_BYTES = 8 * 1024 * 1024;

    private final TrainingDatasetGatewayClient client;
    private final TrainingApiLimits limits;

    public TrainingDatasetUploader(TrainingDatasetGatewayClient client, TrainingApiLimits limits) {
        this.client = Objects.requireNonNull(client, "client");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (limits.transfer().maxChunkBytes() < CHUNK_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "backend training chunk limit is below the client schema-1 chunk size");
        }
    }

    public TrainingDatasetRecord upload(
            PreparedTrainingDataset prepared, BooleanSupplier cancelled, LongConsumer uploadedBytes)
            throws IOException, InterruptedException, InferenceException {
        PreparedTrainingDataset dataset = Objects.requireNonNull(prepared, "prepared");
        BooleanSupplier cancellation = Objects.requireNonNull(cancelled, "cancelled");
        LongConsumer progress = Objects.requireNonNull(uploadedBytes, "uploadedBytes");
        requireArchive(dataset);
        TrainingDatasetStartRequest request =
                new TrainingDatasetStartRequest(
                        dataset.manifest().datasetDigest(),
                        dataset.archiveBytesDigest(),
                        dataset.archiveBytes(),
                        CHUNK_SIZE_BYTES);
        checkCancelled(cancellation);
        TrainingDatasetStartResult started = startWithReplay(request);
        if (started instanceof ExistingTrainingDataset existing) {
            progress.accept(dataset.archiveBytes());
            return existing.dataset();
        }

        TrainingDatasetUploadAccepted accepted = (TrainingDatasetUploadAccepted) started;
        uploadChunks(dataset, request, accepted, cancellation, progress);
        checkCancelled(cancellation);
        return sealWithReplay(request, accepted);
    }

    private TrainingDatasetStartResult startWithReplay(TrainingDatasetStartRequest request)
            throws IOException, InterruptedException, InferenceException {
        try {
            return client.start(request);
        } catch (IOException first) {
            try {
                return client.start(request);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private void uploadChunks(
            PreparedTrainingDataset dataset,
            TrainingDatasetStartRequest request,
            TrainingDatasetUploadAccepted accepted,
            BooleanSupplier cancelled,
            LongConsumer progress)
            throws IOException, InterruptedException, InferenceException {
        int chunkCount =
                TrainingDatasetTransferCodec.chunkCount(
                        request.totalBytes(), request.chunkSizeBytes());
        try (FileChannel channel =
                FileChannel.open(dataset.archivePath(), StandardOpenOption.READ)) {
            for (int index = 0; index < chunkCount; index++) {
                checkCancelled(cancelled);
                TrainingDatasetChunkRange range =
                        TrainingDatasetTransferCodec.chunkRange(request, index, limits);
                byte[] body = readRange(channel, range);
                putChunkWithReplay(request, accepted, range, body);
                progress.accept(range.offset() + range.sizeBytes());
            }
        }
        requireArchive(dataset);
    }

    private void putChunkWithReplay(
            TrainingDatasetStartRequest request,
            TrainingDatasetUploadAccepted accepted,
            TrainingDatasetChunkRange range,
            byte[] body)
            throws IOException, InterruptedException, InferenceException {
        try {
            client.putChunk(request, accepted, range, body);
        } catch (IOException first) {
            try {
                client.putChunk(request, accepted, range, body);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private TrainingDatasetRecord sealWithReplay(
            TrainingDatasetStartRequest request, TrainingDatasetUploadAccepted accepted)
            throws IOException, InterruptedException, InferenceException {
        IOException firstFailure;
        try {
            return client.seal(request, accepted);
        } catch (IOException failure) {
            firstFailure = failure;
        }

        TrainingDatasetStartResult recovered;
        try {
            recovered = client.start(request);
        } catch (IOException failure) {
            failure.addSuppressed(firstFailure);
            throw failure;
        }
        if (recovered instanceof ExistingTrainingDataset existing) {
            return existing.dataset();
        }
        TrainingDatasetUploadAccepted resumed = (TrainingDatasetUploadAccepted) recovered;
        try {
            return client.seal(request, resumed);
        } catch (IOException secondFailure) {
            secondFailure.addSuppressed(firstFailure);
            try {
                TrainingDatasetStartResult finalState = client.start(request);
                if (finalState instanceof ExistingTrainingDataset existing) {
                    return existing.dataset();
                }
            } catch (IOException finalFailure) {
                finalFailure.addSuppressed(secondFailure);
                throw finalFailure;
            }
            throw secondFailure;
        }
    }

    private static byte[] readRange(FileChannel channel, TrainingDatasetChunkRange range)
            throws IOException {
        byte[] body = new byte[Math.toIntExact(range.sizeBytes())];
        ByteBuffer buffer = ByteBuffer.wrap(body);
        long position = range.offset();
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new EOFException("training archive ended inside a planned chunk");
            }
            if (read == 0) {
                continue;
            }
            position += read;
        }
        return body;
    }

    private static void requireArchive(PreparedTrainingDataset dataset) throws IOException {
        if (!Files.isRegularFile(dataset.archivePath())
                || Files.size(dataset.archivePath()) != dataset.archiveBytes()) {
            throw new IOException("prepared training archive changed before upload");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("training dataset upload cancelled");
        }
    }
}

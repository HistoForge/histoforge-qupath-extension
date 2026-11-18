package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3ChunkInputStreamTest {
    @Test
    void readsAcrossBlocksSeeksBackAndHonorsInputStreamEofRules() throws Exception {
        int block = S3ChunkInputStream.CHUNK_SIZE;
        long length = 2L * block + 17;
        var requests = new ArrayList<String>();
        var client = client(requests);
        var snapshot =
                new S3ObjectSnapshot(
                        length,
                        new SourceIdentityCodec.S3ETag("\"0123456789abcdef0123456789abcdef\""));
        try (var stream = new S3ChunkInputStream(client, "bucket", "key", snapshot)) {
            stream.seekTo(block - 2);
            var result = new byte[7];
            assertEquals(5, stream.read(result, 1, 5));
            assertArrayEquals(new byte[] {0, 0, 0, 1, 1, 1, 0}, result);
            stream.seekTo(10);
            assertEquals(0, stream.read());
            assertEquals(2, requests.size());
            stream.seekTo(length - 1);
            assertEquals(2, stream.read());
            assertEquals(-1, stream.read());
            assertEquals(0, stream.read(new byte[0], 0, 0));
            assertEquals(0, stream.available());
            stream.seekTo(length + 10);
            assertEquals(0, stream.available());
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], 0, 2));
            assertThrows(IllegalArgumentException.class, () -> stream.seekTo(-1));
            assertEquals("bytes=" + 2L * block + "-" + (length - 1), requests.getLast());
        }
    }

    @Test
    void boundedCacheEvictsLeastRecentlyUsedBlock() throws Exception {
        int block = S3ChunkInputStream.CHUNK_SIZE;
        var requests = new ArrayList<String>();
        var snapshot =
                new S3ObjectSnapshot(
                        258L * block,
                        new SourceIdentityCodec.S3ETag("\"0123456789abcdef0123456789abcdef\""));
        try (var stream = new S3ChunkInputStream(client(requests), "bucket", "key", snapshot)) {
            for (int i = 0; i < 256; i++) {
                stream.seekTo((long) i * block);
                stream.read();
            }
            stream.seekTo(0);
            stream.read();
            stream.seekTo(256L * block);
            stream.read();
            stream.seekTo(0);
            stream.read();
            assertEquals(257, requests.size());
            stream.seekTo(block);
            stream.read();
            assertEquals(258, requests.size());
        }
    }

    @Test
    void truncatedResponseFailsWithoutCachingOrAdvancing() throws Exception {
        var client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(
                                GetObjectResponse.builder().build(), new byte[2]));
        try (var stream =
                new S3ChunkInputStream(
                        client,
                        "bucket",
                        "key",
                        new S3ObjectSnapshot(
                                3,
                                new SourceIdentityCodec.S3ETag(
                                        "\"0123456789abcdef0123456789abcdef\"")))) {
            assertThrows(IOException.class, stream::read);
            assertEquals(0, stream.getPosition());
            assertThrows(IOException.class, stream::read);
            verify(client, times(2)).getObjectAsBytes(any(GetObjectRequest.class));
        }
    }

    private static S3Client client(ArrayList<String> ranges) {
        var client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(
                        call -> {
                            GetObjectRequest request = call.getArgument(0);
                            assertEquals("\"0123456789abcdef0123456789abcdef\"", request.ifMatch());
                            ranges.add(request.range());
                            var endpoints = request.range().substring(6).split("-");
                            long start = Long.parseLong(endpoints[0]),
                                    end = Long.parseLong(endpoints[1]);
                            byte[] bytes = new byte[(int) (end - start + 1)];
                            Arrays.fill(bytes, (byte) (start / S3ChunkInputStream.CHUNK_SIZE));
                            return ResponseBytes.fromByteArray(
                                    GetObjectResponse.builder().build(), bytes);
                        });
        return client;
    }
}

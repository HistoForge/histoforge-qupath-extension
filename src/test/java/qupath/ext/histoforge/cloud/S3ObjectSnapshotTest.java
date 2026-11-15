package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

class S3ObjectSnapshotTest {

    private static final String BUCKET = "research-slides";
    private static final String KEY = "study/image.ome.tif";
    private static final String ETAG = "\"0123456789abcdef\"";

    @Test
    void versionedSnapshotPinsEveryRangeAndPersistsTheVersionId() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head(4, "version-7", null));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(bytes(new byte[] {1, 2, 3, 4}));

        S3ObjectSnapshot snapshot = S3ObjectSnapshot.capture(s3, BUCKET, KEY);
        byte[] actual;
        try (var input = new S3ChunkInputStream(s3, BUCKET, KEY, snapshot)) {
            actual = input.readAllBytes();
        }

        assertArrayEquals(new byte[] {1, 2, 3, 4}, actual);
        var version =
                assertInstanceOf(SourceIdentityCodec.S3VersionId.class, snapshot.contentVersion());
        assertEquals("version-7", version.value());
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(KEY, request.getValue().key());
        assertEquals("bytes=0-3", request.getValue().range());
        assertEquals("version-7", request.getValue().versionId());
        assertEquals(null, request.getValue().ifMatch());
    }

    @Test
    void unversionedSnapshotPinsEveryRangeWithTheQuotedEtag() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head(3, null, ETAG));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(bytes(new byte[] {9, 8, 7}));

        S3ObjectSnapshot snapshot = S3ObjectSnapshot.capture(s3, BUCKET, KEY);
        try (var input = new S3ChunkInputStream(s3, BUCKET, KEY, snapshot)) {
            assertArrayEquals(new byte[] {9, 8, 7}, input.readAllBytes());
        }
        snapshot.requireCurrent(s3, BUCKET, KEY);

        var etag = assertInstanceOf(SourceIdentityCodec.S3ETag.class, snapshot.contentVersion());
        assertEquals(ETAG, etag.quotedValue());
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertEquals(ETAG, request.getValue().ifMatch());
        assertEquals(null, request.getValue().versionId());
        ArgumentCaptor<HeadObjectRequest> headRequest =
                ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3, times(2)).headObject(headRequest.capture());
        assertEquals(null, headRequest.getAllValues().get(0).ifMatch());
        assertEquals(ETAG, headRequest.getAllValues().get(1).ifMatch());
        assertEquals(null, headRequest.getAllValues().get(1).versionId());
    }

    @Test
    void mutableNullVersionFallsBackToTheQuotedEtag() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head(3, "null", ETAG));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(bytes(new byte[] {9, 8, 7}));

        S3ObjectSnapshot snapshot = S3ObjectSnapshot.capture(s3, BUCKET, KEY);
        try (var input = new S3ChunkInputStream(s3, BUCKET, KEY, snapshot)) {
            assertArrayEquals(new byte[] {9, 8, 7}, input.readAllBytes());
        }

        var etag = assertInstanceOf(SourceIdentityCodec.S3ETag.class, snapshot.contentVersion());
        assertEquals(ETAG, etag.quotedValue());
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertEquals(ETAG, request.getValue().ifMatch());
        assertEquals(null, request.getValue().versionId());
    }

    @Test
    void currentCheckAddressesTheCapturedVersionAndRejectsLengthDrift() throws Exception {
        S3Client s3 = mock(S3Client.class);
        S3ObjectSnapshot snapshot =
                new S3ObjectSnapshot(4, new SourceIdentityCodec.S3VersionId("version-7"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head(5, "version-7", null));

        IOException failure =
                assertThrows(IOException.class, () -> snapshot.requireCurrent(s3, BUCKET, KEY));
        assertTrue(failure.getMessage().contains("changed"));
        ArgumentCaptor<HeadObjectRequest> request =
                ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3).headObject(request.capture());
        assertEquals("version-7", request.getValue().versionId());
        assertEquals(null, request.getValue().ifMatch());
    }

    @Test
    void currentCheckUsesIfMatchWhenVersioningIsUnavailable() throws Exception {
        S3Client s3 = mock(S3Client.class);
        S3ObjectSnapshot snapshot = new S3ObjectSnapshot(4, new SourceIdentityCodec.S3ETag(ETAG));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head(4, null, ETAG));

        snapshot.requireCurrent(s3, BUCKET, KEY);

        ArgumentCaptor<HeadObjectRequest> request =
                ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3).headObject(request.capture());
        assertEquals(ETAG, request.getValue().ifMatch());
        assertEquals(null, request.getValue().versionId());
    }

    @Test
    void captureRejectsAnObjectWithoutStableVersionEvidence() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(
                        HeadObjectResponse.builder()
                                .contentLength(4L)
                                .eTag(null)
                                .versionId(null)
                                .build());

        assertThrows(IOException.class, () -> S3ObjectSnapshot.capture(s3, BUCKET, KEY));
    }

    @Test
    void snapshotRejectsUnsupportedContentVersions() {
        var local = new SourceIdentityCodec.LocalFile(4, 10, java.util.Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new S3ObjectSnapshot(4, local));
        var mutableNullVersion = new SourceIdentityCodec.S3VersionId("null");
        assertThrows(
                IllegalArgumentException.class, () -> new S3ObjectSnapshot(4, mutableNullVersion));
    }

    private static HeadObjectResponse head(long length, String versionId, String etag) {
        return HeadObjectResponse.builder()
                .contentLength(length)
                .versionId(versionId)
                .eTag(etag)
                .build();
    }

    private static ResponseBytes<GetObjectResponse> bytes(byte[] value) {
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), value);
    }
}

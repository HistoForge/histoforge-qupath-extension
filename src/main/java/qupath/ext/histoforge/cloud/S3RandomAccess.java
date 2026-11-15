package qupath.ext.histoforge.cloud;

import java.io.DataInputStream;
import java.io.IOException;
import loci.common.StreamHandle;
import software.amazon.awssdk.services.s3.S3Client;

/** Adapts version-pinned S3 bytes to Bio-Formats' read-only DataInput API. */
public class S3RandomAccess extends StreamHandle {
    private final S3ChunkInputStream bytes;

    public S3RandomAccess(S3Client s3, String bucket, String key, S3ObjectSnapshot snapshot)
            throws IOException {
        bytes = new S3ChunkInputStream(s3, bucket, key, snapshot);
        stream = new DataInputStream(bytes);
        length = snapshot.contentLength();
        file = S3ImageServerBuilder.SCHEME + "://" + bucket + "/" + key;
    }

    @Override
    public void seek(long position) throws IOException {
        if (position < 0) throw new IOException("Negative stream position");
        bytes.seekTo(position);
        fp = position;
    }

    @Override
    protected void resetStream() throws IOException {
        seek(0);
        stream = new DataInputStream(bytes);
        mark = 0;
    }
}

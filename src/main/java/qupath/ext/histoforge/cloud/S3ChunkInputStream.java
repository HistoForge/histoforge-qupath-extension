package qupath.ext.histoforge.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;

/** Seekable, read-only bytes from one S3 object version, cached in aligned 256 KiB blocks. */
class S3ChunkInputStream extends InputStream {
    static final int CHUNK_SIZE = 256 * 1024;
    private static final int MAX_CACHE_ENTRIES = 256;
    private final S3Client client;
    private final String bucket;
    private final String key;
    private final S3ObjectSnapshot snapshot;
    private final LinkedHashMap<Long, byte[]> blocks = new LinkedHashMap<>(16, 0.75f, true);
    private long position;

    S3ChunkInputStream(S3Client client, String bucket, String key, S3ObjectSnapshot snapshot) {
        this.client = client;
        this.bucket = bucket;
        this.key = key;
        this.snapshot = snapshot;
    }

    void seekTo(long position) {
        if (position < 0) throw new IllegalArgumentException("Negative stream position");
        this.position = position;
    }

    long getPosition() {
        return position;
    }

    @Override
    public int available() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, snapshot.contentLength() - position));
    }

    @Override
    public int read() throws IOException {
        if (position >= snapshot.contentLength()) return -1;
        int value = block()[Math.toIntExact(position % CHUNK_SIZE)] & 0xff;
        position++;
        return value;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, destination.length);
        if (length == 0) return 0;
        int requested = (int) Math.min(length, Math.max(0, snapshot.contentLength() - position));
        if (requested == 0) return -1;
        for (int copied = 0; copied < requested; ) {
            byte[] block = block();
            int withinBlock = (int) (position % CHUNK_SIZE);
            int count = Math.min(requested - copied, block.length - withinBlock);
            System.arraycopy(block, withinBlock, destination, offset + copied, count);
            copied += count;
            position += count;
        }
        return requested;
    }

    private byte[] block() throws IOException {
        long start = position - position % CHUNK_SIZE;
        byte[] cached = blocks.get(start);
        if (cached != null) return cached;
        int count = (int) Math.min(CHUNK_SIZE, snapshot.contentLength() - start);
        String range = "bytes=" + start + "-" + (start + count - 1);
        byte[] fetched;
        try {
            fetched =
                    client.getObjectAsBytes(snapshot.rangeRequest(bucket, key, range))
                            .asByteArray();
        } catch (RuntimeException failure) {
            throw new IOException(
                    "S3 range GET failed for " + bucket + "/" + key + " " + range, failure);
        }
        if (fetched.length != count)
            throw new IOException("Incomplete S3 range response for " + range);
        blocks.put(start, fetched);
        if (blocks.size() > MAX_CACHE_ENTRIES) blocks.pollFirstEntry();
        return fetched;
    }

    @Override
    public void close() {
        blocks.clear();
    }
}

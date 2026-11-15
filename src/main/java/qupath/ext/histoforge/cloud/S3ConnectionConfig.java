package qupath.ext.histoforge.cloud;

/**
 * Saved cloud location, separate from secret tokens. The sign-in region also selects the S3 client
 * region. A null keyPrefix lists the whole bucket; otherwise listing is limited to that prefix.
 * Component names are part of the saved JSON format.
 */
public record S3ConnectionConfig(
        String ssoStartUrl, String ssoRegion, String bucket, String keyPrefix) {}

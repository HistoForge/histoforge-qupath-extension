package qupath.ext.histoforge.cloud;

import software.amazon.awssdk.auth.credentials.AwsCredentials;

/** Role session and its expiry, measured in Unix seconds. */
record AwsCredentialData(AwsCredentials credentials, long expiresAtEpochSec) {}

package qupath.ext.histoforge.cloud;

/**
 * Secret session data saved for IAM Identity Center. The access token obtains AWS role credentials;
 * the refresh token and registered client credentials can renew it. Expiry is Unix time in seconds.
 * Component names are part of the saved JSON format.
 */
public record SsoTokenData(
        String accessToken,
        String refreshToken,
        long expiresAtEpochSec,
        String clientId,
        String clientSecret) {}

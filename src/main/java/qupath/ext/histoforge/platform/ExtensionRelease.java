package qupath.ext.histoforge.platform;

import java.io.IOException;
import java.util.Properties;
import java.util.Set;

/** Packaged release metadata generated from the extension build and its exact backend wheel. */
public record ExtensionRelease(
        String extensionVersion,
        String bundledBackendVersion,
        String minimumBackendVersion,
        String bundledWheel) {

    private static final ExtensionRelease CURRENT = load();

    public static ExtensionRelease current() {
        return CURRENT;
    }

    private static ExtensionRelease load() {
        try (var input =
                ExtensionRelease.class.getResourceAsStream(
                        "/qupath/ext/histoforge/build/release.properties")) {
            if (input == null)
                throw new IllegalStateException("Missing extension release metadata");
            var values = new Properties();
            values.load(input);
            if (!values.stringPropertyNames()
                    .equals(
                            Set.of(
                                    "extensionVersion",
                                    "bundledBackendVersion",
                                    "minimumBackendVersion",
                                    "bundledWheel"))) {
                throw new IllegalStateException("Invalid extension release metadata");
            }
            return new ExtensionRelease(
                    values.getProperty("extensionVersion"),
                    values.getProperty("bundledBackendVersion"),
                    values.getProperty("minimumBackendVersion"),
                    values.getProperty("bundledWheel"));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read extension release metadata", error);
        }
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

rootProject.name = "histoforge-extension"

val supportedQuPathVersions = listOf("0.6.0", "0.7.0")
val histoforgeQuPathVersion =
    providers.gradleProperty("histoforgeQuPathVersion").getOrElse(supportedQuPathVersions.first())
require(histoforgeQuPathVersion in supportedQuPathVersions) {
    "Unsupported HistoForge QuPath version '$histoforgeQuPathVersion'. " +
        "Supported versions: ${supportedQuPathVersions.joinToString(", ")}"
}
gradle.extra["histoforgeQuPathVersion"] = histoforgeQuPathVersion

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(
                files(
                    "gradle/qupath-hosts/qupath-$histoforgeQuPathVersion.versions.toml",
                ),
            )
        }
    }
}

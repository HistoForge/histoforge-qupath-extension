import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions") version "0.2.1"
    id("com.diffplug.spotless") version "8.2.0"
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u%04x".format(character.code))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

fun fileSetManifest(files: Map<String, ByteArray>): String {
    val sorted = files.entries.sortedWith { left, right ->
        Arrays.compareUnsigned(
            left.key.toByteArray(Charsets.UTF_8),
            right.key.toByteArray(Charsets.UTF_8),
        )
    }
    return buildString {
        append("{\"files\":[")
        sorted.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append("{\"path\":")
            append(jsonString(entry.key))
            append(",\"sha256\":\"")
            append(sha256(entry.value))
            append("\"}")
        }
        append("]}")
    }
}

fun fileSetDigest(files: Map<String, ByteArray>): String =
    sha256(fileSetManifest(files).toByteArray(Charsets.UTF_8))

fun isDependencyLegalResource(path: String): Boolean =
    !path.endsWith("/") && !path.endsWith(".class") &&
        Regex("(^|[-_.])(licen[cs]e|notice|copying|copyright)([-_.]|$)|^dependencies$")
            .containsMatchIn(path.substringAfterLast('/').lowercase(Locale.ROOT))

val histoforgeQuPathVersion = gradle.extra["histoforgeQuPathVersion"] as String
val catalogQuPathVersion =
    extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("qupath")
        .orElseThrow()
        .requiredVersion
if (catalogQuPathVersion != histoforgeQuPathVersion) {
    throw GradleException(
        "Selected QuPath host catalog $catalogQuPathVersion does not match " +
            histoforgeQuPathVersion,
    )
}
val dependencyLockFile =
    if (histoforgeQuPathVersion == "0.6.0") {
        layout.projectDirectory.file("gradle.lockfile")
    } else {
        layout.projectDirectory.file("gradle/dependency-locks/qupath-0.7.0.lockfile")
    }
val distributionArtifact =
    providers.gradleProperty("histoforgeDistributionArtifact").orNull?.let(::file)

val openSlideTestClassifier = run {
    val operatingSystem = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    val arm = architecture == "aarch64" || architecture == "arm64"
    when {
        operatingSystem.contains("mac") && arm -> "darwin-aarch64"
        operatingSystem.contains("mac") -> "darwin-x86_64"
        operatingSystem.contains("win") -> "win32-x86_64"
        operatingSystem.contains("linux") && arm -> "linux-aarch64"
        operatingSystem.contains("linux") -> "linux-x86_64"
        else -> throw GradleException("OpenSlide tests do not support $operatingSystem/$architecture")
    }
}

// Code formatting with Google Java Style
spotless {
    java {
        googleJavaFormat().aosp()
        toggleOffOn()  // Respect // @formatter:off and // @formatter:on
    }
}

// OME artifactory — hosts Bio-Formats formats-bsd (not mirrored on SciJava)
repositories {
    maven {
        url = uri("https://artifacts.openmicroscopy.org/artifactory/maven/")
    }
}

// Every extension compile/runtime dependency is selected from the checked-in lock.
// Formatter-internal configurations are intentionally excluded: Spotless creates
// transient, dynamically named configurations that cannot have stable lock entries.
dependencyLocking {
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
    lockFile.set(dependencyLockFile)
}

val lockedDependencyConfigurations = setOf(
    "annotationProcessor",
    "compileClasspath",
    "runtimeClasspath",
    "shadow",
    "testAnnotationProcessor",
    "testCompileClasspath",
    "testRuntimeClasspath",
)

configurations.configureEach {
    if (name in lockedDependencyConfigurations) {
        resolutionStrategy.activateDependencyLocking()
    }
}

// Identical source and dependency inputs must produce identical archive bytes,
// so artifactDigest does not change solely because a build ran at another time.
tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

qupathExtension {
    name = "histoforge-extension"
    group = "io.github.histoforge"
    version = "0.2.4"
    description = "QuPath extension for ROI-based segmentation and morphometric analysis"
    automaticModule = "qupath.ext.histoforge"
}

// Release policy, not an exact-build pin. Raise this floor for mandatory backend fixes.
val minimumBackendRelease = "0.1.5"
val generatedReleaseResources = layout.buildDirectory.dir("generated/resources/release")
val generateReleaseMetadata = tasks.register("generateReleaseMetadata") {
    val wheels = fileTree("src/main/resources/qupath/ext/histoforge/runtime") {
        include("histoforge-*.whl")
    }
    val output = generatedReleaseResources.map {
        it.file("qupath/ext/histoforge/build/release.properties")
    }
    inputs.files(wheels)
    inputs.property("extensionVersion", project.version.toString())
    inputs.property("minimumBackendRelease", minimumBackendRelease)
    outputs.file(output)
    doLast {
        val wheel = wheels.files.singleOrNull()
            ?: throw GradleException("Bundle exactly one HistoForge backend wheel")
        val backendVersion = ZipFile(wheel).use { zip ->
            val metadata = zip.entries().asSequence()
                .filter { it.name.matches(Regex("histoforge-[^/]+\\.dist-info/METADATA")) }
                .singleOrNull()
                ?: throw GradleException("Bundled wheel has no unique HistoForge METADATA")
            val headers = zip.getInputStream(metadata).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().lineSequence().takeWhile { it.isNotEmpty() }.toList()
            }
            if (headers.count { it == "Name: histoforge" } != 1) {
                throw GradleException("Bundled wheel is not the HistoForge package")
            }
            val version = headers.filter { it.startsWith("Version: ") }.singleOrNull()?.removePrefix("Version: ")
                ?: throw GradleException("Bundled wheel has no unique package version")
            val initializer = zip.getEntry("histoforge/__init__.py")
                ?: throw GradleException("Bundled wheel has no HistoForge package")
            val source = zip.getInputStream(initializer).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val declared = Regex("(?m)^__version__ = \"([^\"]+)\"$")
                .findAll(source).toList().singleOrNull()?.groupValues?.get(1)
            if (declared != version) {
                throw GradleException("Bundled gateway version does not match wheel METADATA")
            }
            version
        }
        fun releaseParts(value: String): List<java.math.BigInteger> {
            if (!value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
                throw GradleException("Bundled and minimum backend releases must use major.minor.patch: $value")
            }
            return value.split('.').map { it.toBigInteger() }
        }
        val bundled = releaseParts(backendVersion)
        val minimum = releaseParts(minimumBackendRelease)
        val relation = minimum.zip(bundled).map { (left, right) -> left.compareTo(right) }
            .firstOrNull { it != 0 } ?: 0
        if (relation > 0) throw GradleException("Minimum backend release exceeds bundled release")
        if (wheel.name != "histoforge-$backendVersion-py3-none-any.whl") {
            throw GradleException("Bundled wheel filename does not match its package version")
        }
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            "extensionVersion=${project.version}\n" +
                "bundledBackendVersion=$backendVersion\n" +
                "minimumBackendVersion=$minimumBackendRelease\n" +
                "bundledWheel=${wheel.name}\n",
            Charsets.UTF_8,
        )
    }
}

dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // JSON parsing
    shadow("com.google.code.gson:gson:2.10.1")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")

    // Reuse the host Markdown parser; bundle only the table extension QuPath does not provide.
    compileOnly(libs.commonmark.core)
    compileOnly(libs.commonmark.yaml)
    testCompileOnly(libs.commonmark.core)
    testCompileOnly(libs.commonmark.yaml)
    implementation("org.commonmark:commonmark-ext-gfm-tables:${libs.versions.commonmark.get()}") {
        isTransitive = false
    }

    // Groovy — module metric scripts run in-process (provided by QuPath at runtime)
    shadow(libs.groovy.core)

    // XLSX export for project-level metrics (lightweight, no Apache POI)
    implementation("org.dhatim:fastexcel:0.20.0")

    // Bio-Formats I/O (IRandomAccess, StreamHandle, Location) — provided by QuPath at runtime
    shadow(libs.ome.common)

    // Bio-Formats TIFF parsing (TiffParser, IFD) — provided by QuPath at runtime
    shadow(libs.formats.bsd)

    // QuPath's public lossless OME-BigTIFF writer is supplied by the host application.
    // HistoForge compiles against it but does not bundle a second copy into the extension JAR.
    compileOnly(libs.qupath.ext.bioformats)

    // QuPath's pinned OpenSlide server is supplied by the host application. HistoForge uses its
    // exact local-server type to admit unchanged originals without bundling another reader.
    compileOnly(libs.qupath.ext.openslide)

    // AWS SDK for cloud slide streaming (S3 access + SSO authentication)
    implementation(platform("software.amazon.awssdk:bom:2.31.1"))
    listOf("s3", "sso", "ssooidc").forEach { module ->
        implementation("software.amazon.awssdk:$module") {
            // QuPath owns the logging API. AWS still requests SLF4J 1.7, which must not be
            // embedded beside the host's SLF4J 2.0 API in the one extension JAR.
            exclude(group = "org.slf4j", module = "slf4j-api")
        }
    }

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.bundles.logging)
    testCompileOnly(libs.qupath.fxtras)
    // The QuPath convention adds JavaFX to the main compile path and test runtime, but not to
    // test compilation. The opt-in rendered form smoke test uses these existing host APIs.
    listOf("graphics", "controls", "fxml", "swing", "web").forEach { module ->
        testCompileOnly("org.openjfx:javafx-$module:${libs.versions.javafx.get()}")
    }
    // Public QuPath OME-TIFF reader/writer used by the opt-in Phase 0 capture benchmark.
    testImplementation(libs.qupath.ext.bioformats)
    testImplementation(libs.qupath.ext.openslide)
    testRuntimeOnly(
        "io.github.qupath:openslide:${libs.versions.openslide.get()}:$openSlideTestClassifier",
    )
    testImplementation(libs.formats.gpl)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(libs.mockito)
    testImplementation(libs.groovy.core)
    testImplementation(libs.groovy.json)

}

val generatedDependencyLicenses = layout.buildDirectory.dir("generated/resources/dependencyLicenses")
val generateDependencyLicenses = tasks.register("generateDependencyLicenses") {
    description = "Preserve original legal resources from the dependencies bundled by Shadow"
    inputs.files(configurations.runtimeClasspath)
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
    outputs.dir(generatedDependencyLicenses)

    doLast {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .associateBy { it.file.canonicalFile }
        val bundled = tasks.named<ShadowJar>("shadowJar").get().includedDependencies.files
        val ordered = bundled.map { file ->
            artifacts[file.canonicalFile]
                ?: throw GradleException("Bundled dependency has no resolved identity: ${file.name}")
        }.sortedBy { "${it.moduleVersion.id}:${it.classifier ?: ""}" }
        val output = generatedDependencyLicenses.get().asFile
        delete(output)
        val root = output.resolve("META-INF/licenses")
        root.mkdirs()
        val inventory = StringBuilder("Dependencies bundled in this JAR; host libraries are excluded.\n")
        for (artifact in ordered) {
            val module = artifact.moduleVersion.id
            val key = "${module.group}/${artifact.name}/${module.version}" +
                (artifact.classifier?.let { "/$it" } ?: "")
            inventory.append("\n$module${artifact.classifier?.let { ":$it" } ?: ""}\n")
            inventory.append("SHA-256: ${sha256(artifact.file.readBytes())}\n")
            ZipFile(artifact.file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { isDependencyLegalResource(it.name) }.sortedBy { it.name }.toList()
                if (entries.isEmpty()) inventory.append("No legal resource in the upstream JAR.\n")
                for (entry in entries) {
                    val relative = "dependencies/$key/${entry.name}"
                    val target = root.resolve(relative).toPath().normalize()
                    if (!target.startsWith(root.toPath()) || target.toFile().exists()) {
                        throw GradleException("Unsafe or duplicate dependency legal resource: $relative")
                    }
                    target.parent.toFile().mkdirs()
                    zip.getInputStream(entry).use { target.toFile().writeBytes(it.readBytes()) }
                    inventory.append("$relative\n")
                }
            }
        }
        root.resolve("dependencies.txt").writeText(inventory.toString(), Charsets.UTF_8)
    }
}

val clientBuildPolicy =
    layout.projectDirectory.file(
        "src/main/resources/qupath/ext/histoforge/build/client-execution-limits-v1.json",
    )
val generatedClientBuildResources =
    layout.buildDirectory.dir("generated/resources/clientBuildIdentity")
val generatedClientBuildManifest = generatedClientBuildResources.map {
    it.file("qupath/ext/histoforge/build/client-build-manifest-v1.json")
}

val clientBuildSourceFiles = files(
    fileTree("src/main/java") { include("**/*.java") },
    fileTree("src/main/resources") {
        include("**/*")
        exclude("qupath/ext/histoforge/build/client-execution-limits-v1.json")
    },
)
val clientDependencyLockFiles = files(dependencyLockFile)
val clientBuildConfigurationFiles = files(
    "build.gradle.kts",
    "settings.gradle.kts",
    "settings-gradle.lockfile",
    "gradle.properties",
    fileTree("gradle/qupath-hosts") { include("*.versions.toml") },
    "gradle/wrapper/gradle-wrapper.properties",
)
val clientBehaviorPolicyFiles = files(
    clientBuildPolicy,
    fileTree("src/test/resources/contracts") { include("**/*.json") },
)

val phase7QualificationProbeSources = files(
    "src/test/java/qupath/ext/histoforge/client/SourcePublicationQualification.java",
    "src/test/java/qupath/ext/histoforge/qualification/QualificationProbeIdentity.java",
    "src/test/java/qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryFixture.java",
    "src/test/java/qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryQualification.java",
)
val phase7QualificationProbeInputs = files(
    phase7QualificationProbeSources,
    clientDependencyLockFiles,
    clientBuildConfigurationFiles,
)
val generatedPhase7QualificationResources =
    layout.buildDirectory.dir("generated/resources/phase7QualificationProbe")
val generatedPhase7QualificationManifest = generatedPhase7QualificationResources.map {
    it.file("qupath/ext/histoforge/qualification/phase7-qualification-probe-v1.json")
}

val generatePhase7QualificationProbeManifest =
    tasks.register("generatePhase7QualificationProbeManifest") {
        description = "Bind the exact Phase 7 qualification harness and its test runtime"
        inputs.files(phase7QualificationProbeInputs)
        inputs.files(configurations.testRuntimeClasspath)
        inputs.property("histoforgeQuPathVersion", histoforgeQuPathVersion)
        outputs.file(generatedPhase7QualificationManifest)

        doLast {
            val exactInputs =
                phase7QualificationProbeInputs.files
                    .filter(File::isFile)
                    .associate { file ->
                        file.relativeTo(projectDir).invariantSeparatorsPath to file.readBytes()
                    }
                    .toMutableMap()
            exactInputs["selected-qupath-version.txt"] =
                "$histoforgeQuPathVersion\n".toByteArray(Charsets.UTF_8)
            val resolvedTestRuntime =
                configurations.testRuntimeClasspath.get()
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .map { artifact ->
                        val module = artifact.moduleVersion.id
                        listOf(
                            module.group,
                            artifact.name,
                            module.version,
                            artifact.classifier ?: "",
                            artifact.extension,
                            sha256(artifact.file.readBytes()),
                        ).joinToString(":")
                    }
                    .sorted()
                    .joinToString(separator = "\n", postfix = "\n")
                    .toByteArray(Charsets.UTF_8)
            exactInputs["resolved-test-runtime-artifacts.txt"] = resolvedTestRuntime

            val canonical =
                "{\"component\":\"histoforge-phase7-qualification-probes\"," +
                    "\"file_set\":${fileSetManifest(exactInputs)}," +
                    "\"file_set_digest\":\"${fileSetDigest(exactInputs)}\"," +
                    "\"schema_version\":1}"
            val output = generatedPhase7QualificationManifest.get().asFile
            output.parentFile.mkdirs()
            output.writeText(canonical + "\n", Charsets.UTF_8)
        }
    }

val generateClientBuildManifest = tasks.register("generateClientBuildManifest") {
    description = "Generate the immutable extension behavior manifest"
    inputs.files(clientBuildSourceFiles)
    inputs.files(clientDependencyLockFiles)
    inputs.files(clientBuildConfigurationFiles)
    inputs.files(clientBehaviorPolicyFiles)
    inputs.files(configurations.runtimeClasspath)
    inputs.property("histoforgeQuPathVersion", histoforgeQuPathVersion)
    outputs.file(generatedClientBuildManifest)

    doLast {
        fun exactFiles(source: FileCollection): Map<String, ByteArray> =
            source.files
                .filter(File::isFile)
                .associate { file ->
                    file.relativeTo(projectDir).invariantSeparatorsPath to file.readBytes()
                }

        val runtimeInventory =
            configurations.runtimeClasspath.get()
                .resolvedConfiguration
                .resolvedArtifacts
                .map { artifact ->
                    val module = artifact.moduleVersion.id
                    listOf(
                        module.group,
                        artifact.name,
                        module.version,
                        artifact.classifier ?: "",
                        artifact.extension,
                        sha256(artifact.file.readBytes()),
                    ).joinToString(":")
                }
                .sorted()
                .joinToString(separator = "\n", postfix = "\n")
                .toByteArray(Charsets.UTF_8)

        val sourceDigest = fileSetDigest(exactFiles(clientBuildSourceFiles))
        val dependencyDigest = fileSetDigest(exactFiles(clientDependencyLockFiles))
        val environmentDigest =
            fileSetDigest(mapOf("resolved-runtime-artifacts.txt" to runtimeInventory))
        val configurationInputs =
            exactFiles(clientBuildConfigurationFiles).toMutableMap().apply {
                put(
                    "selected-qupath-version.txt",
                    "$histoforgeQuPathVersion\n".toByteArray(Charsets.UTF_8),
                )
            }
        val configurationDigest = fileSetDigest(configurationInputs)
        val policyDigest = fileSetDigest(exactFiles(clientBehaviorPolicyFiles))
        val canonical =
            "{\"behavior_policy_digest\":\"$policyDigest\"," +
                "\"build_configuration_digest\":\"$configurationDigest\"," +
                "\"component\":\"histoforge-qupath-extension\"," +
                "\"dependency_lock_digest\":\"$dependencyDigest\"," +
                "\"resolved_environment_digest\":\"$environmentDigest\"," +
                "\"schema_version\":1," +
                "\"source_digest\":\"$sourceDigest\"}"
        val output = generatedClientBuildManifest.get().asFile
        output.parentFile.mkdirs()
        output.writeText(canonical + "\n", Charsets.UTF_8)
    }
}

sourceSets.main.configure {
    resources.srcDir(generatedClientBuildResources)
    resources.srcDir(generatedReleaseResources)
    resources.srcDir(generatedDependencyLicenses)
}
sourceSets.test.configure {
    resources.srcDir(generatedPhase7QualificationResources)
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(generateClientBuildManifest, generateReleaseMetadata, generateDependencyLicenses)
}
tasks.named<ProcessResources>("processTestResources") {
    dependsOn(generatePhase7QualificationProbeManifest)
}
tasks.named<Jar>("sourcesJar") {
    dependsOn(generateClientBuildManifest, generateReleaseMetadata, generateDependencyLicenses)
}

tasks.named<ShadowJar>("shadowJar") {
    // This upstream transitive inventory is stale. Its original copy remains namespaced below;
    // dependencies.txt records the exact artifacts included in this distribution instead.
    exclude("META-INF/DEPENDENCIES")
}

val verifyDependencyLicenses = tasks.register("verifyDependencyLicenses") {
    group = "verification"
    description = "Check that the distribution preserves every bundled dependency legal resource"
    val distribution = tasks.named<Jar>("shadowJar")
    val archive = distributionArtifact?.let { artifact -> providers.provider { artifact } }
        ?: distribution.flatMap { it.archiveFile }.map { it.asFile }
    if (distributionArtifact == null) dependsOn(distribution)
    inputs.files(configurations.runtimeClasspath)
    inputs.file(archive)
    doLast {
        JarFile(archive.get()).use { jar ->
            val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
                .associateBy { it.file.canonicalFile }
            for (file in tasks.named<ShadowJar>("shadowJar").get().includedDependencies.files) {
                val artifact = artifacts.getValue(file.canonicalFile)
                val module = artifact.moduleVersion.id
                val key = "${module.group}/${artifact.name}/${module.version}" +
                    (artifact.classifier?.let { "/$it" } ?: "")
                ZipFile(file).use { source ->
                    for (entry in source.entries().asSequence().filter { isDependencyLegalResource(it.name) }) {
                        val path = "META-INF/licenses/dependencies/$key/${entry.name}"
                        val actual = jar.getJarEntry(path)
                            ?: throw GradleException("Missing dependency legal resource: $path")
                        val expectedBytes = source.getInputStream(entry).use { it.readBytes() }
                        if (!jar.getInputStream(actual).use { it.readBytes() }.contentEquals(expectedBytes)) {
                            throw GradleException("Dependency legal resource changed: $path")
                        }
                    }
                }
            }
            val supplements = fileTree("src/main/resources/META-INF/licenses").files
            for (file in supplements.filter(File::isFile)) {
                val path = file.relativeTo(projectDir.resolve("src/main/resources")).invariantSeparatorsPath
                val entry = jar.getJarEntry(path) ?: throw GradleException("Missing legal supplement: $path")
                if (!jar.getInputStream(entry).use { it.readBytes() }.contentEquals(file.readBytes())) {
                    throw GradleException("Legal supplement changed: $path")
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyDependencyLicenses) }
tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = project.version.toString()
}

val verifyDistributionArtifact =
    distributionArtifact?.let { artifact ->
        tasks.register("verifyDistributionArtifact") {
            group = "verification"
            description = "Verify the canonical QuPath 0.6 distribution used by a host test lane"
            inputs.file(artifact)

            doLast {
                if (!artifact.isFile) {
                    throw GradleException("Distribution artifact is not a file: $artifact")
                }
                JarFile(artifact).use { jar ->
                    val target = jar.manifest?.mainAttributes?.getValue("QuPath-Version")
                    if (target != "0.6.0") {
                        throw GradleException(
                            "Distribution artifact must be the canonical QuPath 0.6.0 build; " +
                                "found QuPath-Version=$target",
                        )
                    }
                    if (jar.getJarEntry("qupath/ext/histoforge/HistoforgeExtension.class") == null) {
                        throw GradleException(
                            "Distribution artifact does not contain HistoforgeExtension.class",
                        )
                    }
                    if (jar.getJarEntry("org/slf4j/Logger.class") != null) {
                        throw GradleException(
                            "Distribution artifact must use QuPath's logging API, not embed SLF4J",
                        )
                    }
                }
            }
        }
    }

tasks.withType<Test>().configureEach {
    // Unit tests must never read or write the owner's native QuPath preferences.
    systemProperty("java.util.prefs.PreferencesFactory", "qupath.ext.histoforge.platform.IsolatedPreferencesFactory")
    systemProperty("histoforge.test.qupath.version", histoforgeQuPathVersion)
    if (histoforgeQuPathVersion == "0.7.0") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
    if (distributionArtifact != null && verifyDistributionArtifact != null) {
        dependsOn(verifyDistributionArtifact)
        classpath =
            files(
                sourceSets.test.get().output,
                distributionArtifact,
                configurations.testRuntimeClasspath,
            )
        systemProperty(
            "histoforge.test.distribution.artifact",
            distributionArtifact.absolutePath,
        )
    }
}

// Opt-in Phase 0 evidence harness. It is not part of the ordinary test gate because realistic
// hierarchy sizes require a workstation-scale heap and intentionally take longer than unit tests.
tasks.register<JavaExec>("phase0ImageDataBenchmark") {
    group = "verification"
    description = "Benchmark semantic ImageData capture and public QuPath save/reload"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "qupath.ext.histoforge.state.ImageDataRevisionBenchmark"
    maxHeapSize = "12g"
    jvmArgs("-Djava.awt.headless=true")
    args(
        providers.gradleProperty("histoforgeBenchmarkParents").getOrElse("1000"),
        providers.gradleProperty("histoforgeBenchmarkChildrenPerParent").getOrElse("100"),
        providers.gradleProperty("histoforgeBenchmarkChildVertices").getOrElse("12"),
        providers.gradleProperty("histoforgeBenchmarkMeasurementsPerObject").getOrElse("1"),
    )
}


// Opt-in Phase 0 evidence harness. It creates a large deterministic lossless source through
// QuPath's public OME writer, reopens it through QuPath, then times the exact PNG and one-level
// tiled ZLIB OME-BigTIFF capture paths selected by the target architecture.
tasks.register<JavaExec>("phase0SourceCaptureBenchmark") {
    group = "verification"
    description = "Benchmark public QuPath PNG and OME-BigTIFF source capture"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "qupath.ext.histoforge.source.SourceCaptureBenchmark"
    maxHeapSize = "12g"
    jvmArgs("-Djava.awt.headless=true")
    args(
        providers.gradleProperty("histoforgeCaptureOutput").getOrElse("build/phase0-source-capture"),
        providers.gradleProperty("histoforgeCaptureOriginalWidth").getOrElse("53248"),
        providers.gradleProperty("histoforgeCaptureOriginalHeight").getOrElse("26624"),
        providers.gradleProperty("histoforgeCaptureSmallSize").getOrElse("4096"),
        providers.gradleProperty("histoforgeCaptureLargeSize").getOrElse("10000"),
    )
}

// Opt-in real-WSI benchmark. It converts selected native-level rectangles through the same
// public QuPath OME-BigTIFF writer and exact RGB verification used by production source capture.
tasks.register<JavaExec>("phase0RealWsiCaptureBenchmark") {
    group = "verification"
    description = "Benchmark WSI-scale decode and canonical OME-BigTIFF capture"
    dependsOn(tasks.testClasses)
    // Supplying the exact QuPath application bundle makes the opt-in benchmark use the same
    // reader plugins and native libraries as the supported desktop host. Without it, the task
    // uses only the readers on the ordinary test runtime classpath.
    val hostApp = providers.gradleProperty("histoforgeRealWsiHostApp")
    classpath = if (hostApp.isPresent) {
        files(
            fileTree(file(hostApp.get()).resolve("Contents/app")) { include("*.jar") },
            sourceSets.test.get().runtimeClasspath,
        )
    } else {
        sourceSets.test.get().runtimeClasspath
    }
    mainClass = "qupath.ext.histoforge.spatial.RealWsiCaptureBenchmark"
    maxHeapSize = "12g"
    jvmArgs("-Djava.awt.headless=true")
    args(
        providers.gradleProperty("histoforgeRealWsiInput").getOrElse(""),
        providers.gradleProperty("histoforgeRealWsiOutput")
            .getOrElse("build/phase0-real-wsi-capture"),
        providers.gradleProperty("histoforgeRealWsiLevel").getOrElse("0"),
        providers.gradleProperty("histoforgeRealWsiCases")
            .getOrElse("large-10000,half-slide,full-slide"),
    )
}

// Opt-in Phase 0 host probe. It creates a transparent real QuPath GUI and a disposable project,
// then exercises viewer-still-active and user-navigated-away commit cuts through public APIs.
// It is deliberately separate from the headless unit-test gate and does not wire the target
// Phase 3 lifecycle into the current execution path.
tasks.register<JavaExec>("phase0QuPathViewerLifecycle") {
    group = "verification"
    description = "Verify live QuPath navigation, save ordering, and viewer server ownership"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "qupath.ext.histoforge.platform.QuPathViewerLifecycleProbe"
    jvmArgs(
        "-Djava.awt.headless=false",
        "-Dqupath.startup.script=block",
    )
    args(
        providers.gradleProperty("histoforgeViewerEvidence")
            .getOrElse("build/phase0-qupath-viewer-lifecycle.json"),
    )
}

// Opt-in Phase 7 evidence harness. It reads persisted QuPath hierarchies and selected privacy-safe
// server metadata without opening image servers or pixels, and fails if any project byte changes.
tasks.register<JavaExec>("phase7RealProjectAudit") {
    group = "verification"
    description = "Audit a real QuPath project's hierarchy and calibration metadata read-only"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "qupath.ext.histoforge.qualification.RealProjectAudit"
    maxHeapSize = "4g"
    jvmArgs("-Djava.awt.headless=true")
    args(
        providers.gradleProperty("histoforgePhase7Project").getOrElse(""),
        providers.gradleProperty("histoforgePhase7Evidence")
            .getOrElse("build/phase7-real-project-audit.json"),
    )
}

// Opt-in Phase 7 private-deployment qualification. It drives the production Java transport and
// source-publication owner, but writes only privacy-safe evidence and always releases the source.
tasks.register<JavaExec>("phase7JavaSourcePublication") {
    group = "verification"
    description = "Publish, seal, verify, and release one real WSI through the Java client"
    val distributionJar = tasks.named<Jar>("shadowJar")
    dependsOn(distributionJar, tasks.testClasses)
    classpath = files(
        distributionJar.flatMap { it.archiveFile },
        sourceSets.test.get().output,
        sourceSets.test.get().runtimeClasspath,
    )
    mainClass = "qupath.ext.histoforge.client.SourcePublicationQualification"
    maxHeapSize = "4g"
    jvmArgs("-Djava.awt.headless=true")
    args(
        providers.gradleProperty("histoforgePhase7Credential").getOrElse(""),
        providers.gradleProperty("histoforgePhase7Source").getOrElse(""),
        providers.gradleProperty("histoforgePhase7SourceStart").getOrElse(""),
        providers.gradleProperty("histoforgePhase7SourceSha256").getOrElse(""),
        providers.gradleProperty("histoforgePhase7SourceEvidence")
            .getOrElse("build/phase7-java-source-publication.json"),
    )
}

// Opt-in Phase 7 private-deployment qualification. The packaged extension JAR is placed before
// exploded test/runtime outputs so scientific build capture sees the exact artifact under test.
// Interruption modes durably record an accepted backend run and then halt the JVM without cleanup;
// separate invocations must explicitly resume or abandon that retained project state.
tasks.register<JavaExec>("phase7PrivateGatewayRecovery") {
    group = "verification"
    description = "Qualify abrupt durable-action recovery and abandonment on real pixels"
    val distributionJar = tasks.named<Jar>("shadowJar")
    dependsOn(distributionJar, tasks.testClasses)
    classpath = files(
        distributionJar.flatMap { it.archiveFile },
        sourceSets.test.get().output,
        sourceSets.test.get().runtimeClasspath,
    )
    mainClass = "qupath.ext.histoforge.ui.project.PrivateGatewayRecoveryQualification"
    maxHeapSize = "8g"
    jvmArgs(
        "-Djava.awt.headless=false",
        "-Dqupath.startup.script=block",
    )
    val mode = providers.gradleProperty("histoforgePhase7RecoveryMode").getOrElse("")
    val interruptionMode = mode.startsWith("interrupt-")
    isIgnoreExitValue = interruptionMode
    args(
        mode,
        providers.gradleProperty("histoforgePhase7Credential").getOrElse(""),
        providers.gradleProperty("histoforgePhase7Source").getOrElse(""),
        providers.gradleProperty("histoforgePhase7SourceSha256").getOrElse(""),
        providers.gradleProperty("histoforgePhase7RecoveryWorkspace").getOrElse(""),
    )
    if (interruptionMode) {
        doLast {
            val exit = executionResult.get().exitValue
            if (exit != 86) {
                throw GradleException("abrupt qualification exited $exit instead of 86")
            }
        }
    }
}

// Opt-in networked macOS bootstrap check; writes uv only beneath a new evidence directory.
tasks.register<JavaExec>("uvBootstrapSmoke") {
    group = "verification"
    description = "Verify the pinned private uv installer without changing shared uv or shell state"
    dependsOn(tasks.testClasses)
    if (verifyDistributionArtifact != null) dependsOn(verifyDistributionArtifact)
    classpath = if (distributionArtifact == null) {
        sourceSets.test.get().runtimeClasspath
    } else {
        files(distributionArtifact, sourceSets.test.get().output, sourceSets.test.get().runtimeClasspath)
    }
    mainClass = "qupath.ext.histoforge.cli.UvBootstrapSmoke"
    maxHeapSize = "256m"
    args(providers.gradleProperty("histoforgeUvEvidence").getOrElse(""))
}

// Opt-in desktop form check. Loads real FXML with fixture catalog data and performs no cloud IO.
tasks.register<JavaExec>("trainingFormSmoke") {
    group = "verification"
    description = "Verify the rendered Train form and Cloud/Local settings"
    dependsOn(tasks.testClasses)
    if (verifyDistributionArtifact != null) dependsOn(verifyDistributionArtifact)
    classpath = if (distributionArtifact == null) {
        sourceSets.test.get().runtimeClasspath
    } else {
        files(sourceSets.test.get().output, distributionArtifact, configurations.testRuntimeClasspath)
    }
    if (distributionArtifact != null) {
        systemProperty("histoforge.test.distribution.artifact", distributionArtifact.absolutePath)
    }
    systemProperty("histoforge.test.qupath.version", histoforgeQuPathVersion)
    mainClass = "qupath.ext.histoforge.ui.training.TrainingFormSmoke"
    jvmArgs("-Djava.awt.headless=false")
    args(layout.buildDirectory.dir("training-form-smoke").get().asFile.absolutePath)
}

// Opt-in scope controls check using disposable projects; no cloud mutation.
tasks.register<JavaExec>("trainingCaptureSmoke") {
    group = "verification"
    description = "Verify empty and populated Train refresh transitions on native JavaFX"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "qupath.ext.histoforge.ui.training.TrainingCaptureSmoke"
    jvmArgs("-Djava.awt.headless=false")
}

// Opt-in scope controls check using disposable projects; no cloud mutation.
tasks.register<JavaExec>("annotationScopeSmoke") {
    group = "verification"
    description = "Verify native annotation scope controls and project isolation"
    dependsOn(tasks.testClasses)
    if (verifyDistributionArtifact != null) dependsOn(verifyDistributionArtifact)
    classpath = if (distributionArtifact == null) {
        sourceSets.test.get().runtimeClasspath
    } else {
        files(sourceSets.test.get().output, distributionArtifact, configurations.testRuntimeClasspath)
    }
    mainClass = "qupath.ext.histoforge.ui.project.AnnotationScopeSmoke"
    jvmArgs("-Djava.awt.headless=false")
    args(layout.buildDirectory.dir("annotation-scope-smoke").get().asFile.absolutePath)
}

// Opt-in Project-table error check; synthetic results, no image or cloud access.
tasks.register<JavaExec>("projectRunDetailsSmoke") {
    group = "verification"
    description = "Verify complete, copyable Project-table errors and recycled cells"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "qupath.ext.histoforge.ui.project.ProjectRunDetailsSmoke"
    jvmArgs("-Djava.awt.headless=false")
    args(layout.buildDirectory.dir("project-run-details-smoke").get().asFile.absolutePath)
}

// Opt-in native Project refresh tests; controlled workers, no image or cloud mutation.
tasks.register<JavaExec>("projectRefreshSmoke") {
    group = "verification"
    description = "Verify summary-first refresh, coalescing and lifecycle cancellation on native JavaFX"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "qupath.ext.histoforge.ui.project.ProjectRefreshSmoke"
    jvmArgs("-Djava.awt.headless=false")
}


// Opt-in populated UI evidence; all gateway responses come from contract fixtures.
tasks.register<JavaExec>("trainingSurfacesSmoke") {
    group = "verification"
    description = "Render training dataset states, Jobs, and report snapshots"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "qupath.ext.histoforge.ui.training.TrainingSurfacesSmoke"
    jvmArgs("-Djava.awt.headless=false")
    args(layout.buildDirectory.dir("training-surfaces-smoke").get().asFile.absolutePath)
}

// Opt-in document rendering and explicit-link probe; all metadata is synthetic.
tasks.register<JavaExec>("moduleCardsSmoke") {
    group = "verification"
    description = "Verify saved module cards, explicit links and no automatic resource requests"
    dependsOn(tasks.testClasses)
    if (verifyDistributionArtifact != null) dependsOn(verifyDistributionArtifact)
    classpath = if (distributionArtifact == null) {
        sourceSets.test.get().runtimeClasspath
    } else {
        files(distributionArtifact, sourceSets.test.get().output, sourceSets.test.get().runtimeClasspath)
    }
    mainClass = "qupath.ext.histoforge.ui.modules.ModuleCardsSmoke"
    jvmArgs("-Djava.awt.headless=false")
    args(layout.buildDirectory.dir("module-cards-smoke-$histoforgeQuPathVersion").get().asFile.absolutePath)
}

// Opt-in native timer and UI check; controlled HTTP and catalog responses only.
tasks.register<JavaExec>("catalogJobsRefreshSmoke") {
    group = "verification"
    description = "Verify native catalog/Jobs loading, idle polling and in-flight refresh races"
    dependsOn(tasks.testClasses)
    if (verifyDistributionArtifact != null) dependsOn(verifyDistributionArtifact)
    classpath = if (distributionArtifact == null) {
        sourceSets.test.get().runtimeClasspath
    } else {
        files(sourceSets.test.get().output, distributionArtifact, configurations.testRuntimeClasspath)
    }
    if (distributionArtifact != null) {
        systemProperty("histoforge.test.distribution.artifact", distributionArtifact.absolutePath)
    }
    systemProperty("histoforge.test.qupath.version", histoforgeQuPathVersion)
    maxHeapSize = "512m"
    mainClass = "qupath.ext.histoforge.ui.jobs.CatalogJobsRefreshSmoke"
    jvmArgs("-Djava.awt.headless=false")
    args(layout.buildDirectory.dir("catalog-jobs-refresh-smoke").get().asFile.absolutePath)
}

// Explicit transfer qualification only. No model run, deletion, deployment, or native UI control.
tasks.register<JavaExec>("phase8JavaDatasetTransfer") {
    group = "verification"
    description = "Measure the packaged Java training-dataset uploader against one gateway"
    val distributionJar = tasks.named<Jar>("shadowJar")
    dependsOn(distributionJar, tasks.testClasses)
    classpath = files(
        distributionJar.flatMap { it.archiveFile },
        sourceSets.test.get().output,
        sourceSets.test.get().runtimeClasspath,
    )
    mainClass = "qupath.ext.histoforge.client.TrainingDatasetTransferQualification"
    maxHeapSize = "1g"
    jvmArgs("-Djava.awt.headless=true")
    args(
        providers.gradleProperty("histoforgePhase8Credential").getOrElse(""),
        providers.gradleProperty("histoforgePhase8Archive").getOrElse(""),
        providers.gradleProperty("histoforgePhase8ArchiveSha256").getOrElse(""),
        providers.gradleProperty("histoforgePhase8TransferEvidence")
            .getOrElse("build/phase8-java-dataset-transfer.json"),
    )
}

// The distribution JAR is `shadowJar`'s `-all.jar`. The qupath-conventions
// plugin supplies its manifest (Automatic-Module-Name, QuPath-Version) and the
// `shadow` configuration above keeps QuPath's own jars out of it.
tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

// Native component qualification uses synthetic images and mocked external services only.
tasks.register<JavaExec>("componentUiSmoke") {
    group = "verification"
    description = "Verify cloud, ROI and project UI component behavior"
    dependsOn(tasks.testClasses)
    if (verifyDistributionArtifact != null) dependsOn(verifyDistributionArtifact)
    classpath = if (distributionArtifact == null) {
        sourceSets.test.get().runtimeClasspath
    } else {
        files(sourceSets.test.get().output, distributionArtifact, configurations.testRuntimeClasspath)
    }
    if (distributionArtifact != null) {
        systemProperty("histoforge.test.distribution.artifact", distributionArtifact.absolutePath)
    }
    systemProperty("histoforge.test.qupath.version", histoforgeQuPathVersion)
    systemProperty("java.util.prefs.PreferencesFactory", "qupath.ext.histoforge.platform.IsolatedPreferencesFactory")
    mainClass = "qupath.ext.histoforge.ui.project.ComponentUiSmoke"
    maxHeapSize = "512m"
    jvmArgs("-Djava.awt.headless=false")
    args(layout.buildDirectory.dir("component-ui-smoke-$histoforgeQuPathVersion").get().asFile.absolutePath)
}

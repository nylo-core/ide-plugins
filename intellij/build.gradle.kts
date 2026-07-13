import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())

    // AS-drift guard: an updated Android Studio bundles Flutter/Dart IDE plugins compiled with a newer
    // Kotlin than this plugin's pinned compiler — their `.kotlin_module` metadata (e.g. 2.4.0) is ahead
    // of our compiler (2.2.0), so a local build against the installed AS fails compileKotlin /
    // compileTestKotlin with "Module was compiled with an incompatible version of Kotlin". We only read
    // those plugins' APIs, so relax the metadata-version gate to let the newer local plugins be read.
    // No effect on CI, which resolves the pinned marketplace Flutter build (older, compatible metadata).
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Machine-local overrides (local.properties, gitignored, or -P): point the build at an installed IDE
// and build-matched Flutter/Dart plugins so runIde/compile use compatible artifacts with no downloads.
// When absent, the build resolves everything from gradle properties as usual (the CI path).
val localProperty = { key: String ->
    providers.fileContents(layout.projectDirectory.file("local.properties")).asText.map { content ->
        content.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.trim().orEmpty()
    }.filter { it.isNotEmpty() }.orElse(providers.gradleProperty(key))
}

val localIdePath = localProperty("localIdePath")
val localFlutterPlugin = localProperty("localFlutterPlugin")
val localDartPlugin = localProperty("localDartPlugin")
val localLsp4ijPlugin = localProperty("localLsp4ijPlugin")

// The local plugin paths point at live installs that the running IDE mutates: flutter-intellij
// downloads its embedded jxbrowser (Chromium) into its own plugin directory, and the resulting
// jxbrowser/user-data contains unix sockets Gradle cannot fingerprint ("Cannot snapshot ...:
// not a regular file"). Depend on a static mirror (runtime state excluded) instead of the live
// directory; the mirror refreshes only when the installed plugin's files actually change.
val mirrorLocalPlugin = { sourcePath: String ->
    val source = File(sourcePath)
    if (!source.isDirectory) throw GradleException("Local plugin directory not found: $sourcePath")
    val mirrorRoot = layout.projectDirectory.dir(".gradle/local-plugin-mirrors").asFile
    val mirror = mirrorRoot.resolve(source.name)
    val manifestFile = mirrorRoot.resolve("${source.name}.manifest")
    val manifest = source.walkTopDown()
        .onEnter { it.name != "jxbrowser" }
        .filter { it.isFile }
        .map { "${it.relativeTo(source).path}:${it.length()}:${it.lastModified()}" }
        .sorted()
        .joinToString("\n")
    if (!mirror.isDirectory || manifestFile.takeIf(File::exists)?.readText() != manifest) {
        logger.lifecycle("Mirroring local plugin '${source.name}' into $mirror")
        mirror.deleteRecursively()
        copy {
            from(source) {
                exclude("jxbrowser", "jxbrowser/**")
            }
            into(mirror)
        }
        manifestFile.writeText(manifest)
    }
    mirror.absolutePath
}

dependencies {
    intellijPlatform {
        if (localIdePath.isPresent) {
            local(localIdePath.get())
        } else {
            create(
                providers.gradleProperty("platformType").get(),
                providers.gradleProperty("platformVersion").get(),
            )
        }

        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins").map { it.split(',').map(String::trim).filter(String::isNotEmpty) },
        )

        // Flutter + Dart IDE plugins. Locally, use the installed build-matched plugins (e.g. AS 261's
        // flutter-intellij v93 / Dart) so the runIde sandbox can open Flutter projects; CI uses the pinned
        // marketplace build (io.flutter via platformPlugins, matched to the create() platform version).
        if (localFlutterPlugin.isPresent && localDartPlugin.isPresent) {
            if (localLsp4ijPlugin.isPresent) localPlugin(mirrorLocalPlugin(localLsp4ijPlugin.get())) // Dart depends on lsp4ij
            localPlugin(mirrorLocalPlugin(localDartPlugin.get()))
            localPlugin(mirrorLocalPlugin(localFlutterPlugin.get()))
        } else {
            plugins(
                providers.gradleProperty("platformPlugins").map { it.split(',').map(String::trim).filter(String::isNotEmpty) },
            )
        }

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.junit)
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    // Skip the searchable-options index — it spins up a headless IDE and isn't useful for this plugin,
    // so disabling it makes runIde / buildPlugin start noticeably faster.
    buildSearchableOptions = false

    // No .form files and we don't rely on runtime @NotNull assertions, so bytecode instrumentation adds
    // nothing here. Disabling it also avoids the "instrumentIdeaExtensions doesn't support the nested
    // 'skip' element" failure from the instrumentation Ant task against the local build-261 IDE, which
    // otherwise blocks `test`/`runIde`.
    instrumentCode = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

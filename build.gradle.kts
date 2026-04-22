import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val platformVersion = providers.gradleProperty("platformVersion").get()

        // RubyMine installer distribution — IPG 2.x resolves from JetBrains CDN.
        // The Maven multi-OS archive (useInstaller=false) is not published for RM.
        rubymine(platformVersion)

        // Bundled plugins in RubyMine 2024.3 (verified via printBundledPlugins)
        bundledPlugin("org.jetbrains.plugins.ruby")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.modules.json")

        // Ruby test framework for RubyMine plugin development
        // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-types.html#TestFrameworkType
        testFramework(TestFrameworkType.Bundled)

        pluginVerifier()
        zipSigner()
    }

    // Coroutines are bundled with the platform — compile-only to avoid packaging conflicts
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    // Coroutines needed in tests (not on platform classpath during test execution)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        ides {
            providers.gradleProperty("pluginVerifierIdeVersions").get()
                .split(",")
                .map { it.trim() }
                .forEach { ide(it) }
        }
    }

    // Disable bytecode instrumentation — not needed for scaffold validation
    // and avoids OOM in memory-constrained CI environments.
    instrumentCode = false
}

// ── Webview build ──────────────────────────────────────────────────────────────
// The JCEF chat panel loads its UI from META-INF/rubyn-webview/index.html.
// Vite produces a single-file bundle in webview-source/dist/ that we copy into
// the processResources output so it ends up inside the plugin JAR/zip.

val webviewDir = layout.projectDirectory.dir("webview-source")
val webviewDist = webviewDir.dir("dist")

val webviewNpmInstall by tasks.registering(Exec::class) {
    description = "Install webview npm dependencies"
    group = "webview"
    workingDir = webviewDir.asFile
    inputs.file(webviewDir.file("package.json"))
    inputs.file(webviewDir.file("package-lock.json")).optional()
    outputs.dir(webviewDir.dir("node_modules"))
    commandLine("npm", "install", "--no-audit", "--no-fund")
}

val webviewBuild by tasks.registering(Exec::class) {
    description = "Build the webview UI bundle with Vite"
    group = "webview"
    dependsOn(webviewNpmInstall)
    workingDir = webviewDir.asFile
    inputs.dir(webviewDir.dir("src"))
    inputs.file(webviewDir.file("index.html"))
    inputs.file(webviewDir.file("vite.config.ts"))
    inputs.file(webviewDir.file("tsconfig.json"))
    outputs.dir(webviewDist)
    commandLine("npm", "run", "build")
}

val copyWebview by tasks.registering(Copy::class) {
    description = "Copy webview dist into plugin resources"
    group = "webview"
    dependsOn(webviewBuild)
    from(webviewDist)
    into(layout.buildDirectory.dir("resources/main/META-INF/rubyn-webview"))
}

tasks {
    processResources {
        dependsOn(copyWebview)
    }

    wrapper {
        gradleVersion = "8.11.1"
    }

    test {
        useJUnit()
    }
}

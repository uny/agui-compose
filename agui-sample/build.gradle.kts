plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * The sample application. Not published, so no `maven-publish`, no `dokka`, and no
 * `abiValidation`: there is no consumer to keep an ABI stable for, and a klib dump checked in for
 * an application would be a file to regenerate every time the sample's UI changed.
 *
 * `kotlin-multiplatform` with a single `jvm()` rather than `kotlin("jvm")`. What the sample proves
 * is that the library's five-target promise is usable, and the day this gains an Android or iOS
 * entry point that has to be a source-set edge rather than a rewrite: the application itself lives
 * in `commonMain` and only the window belongs to a platform. Starting on one target is the
 * deliberate part -- see the README section this module is named from.
 */
kotlin {
    // As in every other module. The sample has no published API, but `explicitApi()` is what keeps
    // its declarations honest about which of them the entry points actually need.
    explicitApi()

    // The repository's floor, for the reason `agui-agent` records: upstream's `kotlin-core-jvm` is
    // class-file 65.
    jvmToolchain(21)

    jvm()

    sourceSets {
        commonMain.dependencies {
            // The top of the stack a host would take. `agui-material3` re-exposes `agui-compose`
            // as `api`, and `agui-agent` brings `agui-core` and the upstream client -- including
            // `HttpAgent`, which this sample constructs. Both are `implementation`: nothing here
            // is a library surface.
            implementation(projects.aguiMaterial3)
            implementation(projects.aguiAgent)
            // The Markdown renderer, fitted through `LocalAguiTextRenderer`. An agent's prose is
            // Markdown in practice and the library deliberately does not assume it, so the sample
            // is where that decision gets made the way an application makes it.
            implementation(projects.aguiMarkdown)

            // Named even though they arrive transitively: the Compose compiler plugin is applied
            // to this module and refuses to run without the runtime on the compile class path.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // The sample is small enough that its UI is worth asserting rather than eyeballing:
            // what a window would show is a composition, and a composition is testable.
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            // As in every module that draws in a test: Compose's JVM harness draws through Skiko,
            // whose native library ships with the desktop artifact rather than with `ui-test`.
            implementation(compose.desktop.currentOs)
        }
        jvmMain.dependencies {
            // `Window` and `application` are the desktop artifact's, not `compose.ui`'s.
            implementation(compose.desktop.currentOs)
        }
    }
}

/**
 * So that `./gradlew :agui-sample:run` opens the window.
 *
 * Only `mainClass`. Packaging would need a distribution name, a version and an icon per platform,
 * and nothing asks for an installer: this is a sample, not a product.
 */
compose.desktop {
    application {
        mainClass = "dev.ynagai.agui.sample.SampleMain"
    }
}

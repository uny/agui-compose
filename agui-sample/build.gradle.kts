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

            // Not used by a line of this module, and required for it to run at all.
            //
            // Two dependencies bring kotlinx-datetime and they disagree across a binary break.
            // Upstream's `kotlin-core`/`kotlin-client` 0.4.1 are compiled against 0.6.2, whose
            // `kotlinx.datetime.Clock` is a class; Compose Material 3 1.9.0 brings 0.7.1, where
            // `Clock` and `Instant` have moved to `kotlin.time` and the old classes are gone.
            // Gradle resolves the conflict to 0.7.1, which compiles and then dies on the first
            // event upstream timestamps:
            //
            //   java.lang.NoClassDefFoundError: kotlinx/datetime/Clock$System
            //
            // `0.8.0-0.6.x-compat` is the artifact kotlinx-datetime publishes for exactly this:
            // 0.8.0 with the 0.6.x binary surface kept, so both sides find what they were compiled
            // against.
            //
            // It wins because Gradle resolves a conflict to the highest version and this one sorts
            // above 0.7.1 -- not because it is declared here rather than arriving transitively.
            // `dependencyInsight --configuration jvmRuntimeClasspath` says so in as many words:
            // "By conflict resolution: between versions 0.8.0-0.6.x-compat, 0.7.1 and 0.6.2". The
            // consequence is worth writing down: the day something on this graph brings a version
            // that sorts higher still, this stops winning, and it stops winning at run time rather
            // than at compile time. A `strictly` would hold it, and is deliberately not used --
            // this line is the one the README tells a reader to write, and a version range in a
            // README is a worse thing to copy than a coordinate. The check that catches it is the
            // live one the README describes, not this module's tests.
            //
            // **This is not the sample's problem to own.** Any application taking `agui-material3`
            // and `agui-agent` together hits it, which is what a sample is for -- finding the thing
            // no module's own test classpath could. The README says so where a reader will meet it.
            implementation(libs.kotlinx.datetime)

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

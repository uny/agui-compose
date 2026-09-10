import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    explicitApi()

    // Matches `agui-core`: upstream's `kotlin-core-jvm` is class-file 65, so 21 is the lowest
    // floor this repository can promise, and pinning it keeps that floor out of the hands of
    // whichever JDK happened to run Gradle.
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    // Called explicitly because this module adds `dependsOn` edges of its own below, and Kotlin
    // silently drops the default template for any project that does. Without this line there is no
    // `appleMain` or `appleTest` -- which costs nothing today, since nothing here is
    // platform-specific, and would cost the first `expect`/`actual` in this module a debugging
    // session over a source set that used to exist everywhere else.
    applyDefaultHierarchyTemplate()

    android {
        namespace = "dev.ynagai.agui.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Wired for the same reason `agui-core` wires it -- a target whose `.aar` is published
        // and whose tests never run is a target the suite is silent about -- but stated plainly:
        // **today it runs nothing.** Every test in this module needs a composition on screen and
        // therefore lives in `composeUiTest`, which Android does not depend on, and `commonTest`
        // itself is empty. So `testAndroidHostTest` reports NO-SOURCE and the default renderers
        // ship in the `.aar` with nothing exercising them.
        //
        // Kept rather than deleted so that the first test which needs no composition runs here
        // without anyone having to rediscover this block. Closing the real gap needs Robolectric
        // or an instrumentation harness, and this module carries neither.
        withHostTest {}
    }

    jvm()

    // Four targets, not the five `agui-model` and `agui-core` publish.
    //
    // `iosX64` is absent because Compose Multiplatform does not publish it: at 1.12.0 the
    // `org.jetbrains.compose.foundation:foundation`, `:ui` and `:runtime` modules carry
    // `ios_arm64` and `ios_simulator_arm64` variants and no `ios_x64` one -- measured from their
    // Gradle module metadata, not inferred. So no module that draws anything can reach an Intel
    // simulator, whatever this file asks for.
    //
    // The lower layers keep the fifth target rather than being trimmed to match. They depend on
    // none of this: a consumer on an Intel simulator can still fold an event stream with
    // `agui-core` and render it with something other than Compose. Losing that to make one number
    // uniform across the repository would be a real cost paid for a cosmetic gain.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        /**
         * Tests that need a composition on screen.
         *
         * Compiled for the JVM and both iOS targets, and not Android: its host test task has no
         * composition to draw into without an instrumentation or Robolectric harness, and this
         * module carries neither.
         *
         * Two of those three actually *run*. `iosArm64` is a device target, so Kotlin builds and
         * links the test binary but creates no task that executes it; what the edge buys there is
         * a compile against the device klib, which catches an API that exists in the simulator's
         * and not in it. Assertions execute on `jvm` and `iosSimulatorArm64`. The default renderers therefore ship in the `.aar` with nothing drawing them on
         * that target. That is a real gap rather than a justified exclusion, and it is written down
         * here so it is not mistaken for one.
         *
         * A source set rather than a runtime skip, because a test that returns early still reports
         * as passing. What the build says instead is that these tests do not run on Android at all.
         */
        val composeUiTest = create("composeUiTest") {
            dependsOn(commonTest.get())
            dependencies { implementation(libs.compose.ui.test) }
        }

        jvmTest.get().dependsOn(composeUiTest)
        iosArm64Test.get().dependsOn(composeUiTest)
        iosSimulatorArm64Test.get().dependsOn(composeUiTest)

        commonMain.dependencies {
            api(projects.aguiModel)

            // `api`, not `implementation`: the published surface is built out of these. A slot in
            // `AguiComponents` is a `@Composable` function taking a `Modifier`, `AguiTextRenderer`
            // is a composition local, and `AguiTranscript` takes a `LazyListState` so a caller can
            // drive the scroll. Every one of those types appears in `api/`'s dumps. Scoped
            // `implementation` they would reach a consumer's runtime classpath but not its compile
            // classpath, and writing a renderer against the published artifact would not compile.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Compose's JVM test harness draws through Skiko, and the native library ships with
            // the desktop artifact rather than with `ui-test`. Without it every UI test fails in
            // `SkikoComposeUiTest`'s initialiser, before any composition happens. The other targets
            // carry their own renderer, so this is scoped to the JVM.
            implementation(compose.desktop.currentOs)
        }
    }
}

/**
 * Maven Central. Identical in shape to `agui-core`'s block, and for the same reasons: an unsigned
 * artifact is rejected by the portal, so signing is unconditional rather than dependent on a key
 * being present; the javadoc jar is a real one because the KDoc here carries the reasoning a
 * consumer cannot re-derive from the signatures; and the sources jar is registered by the
 * publishing plugin rather than by `withSourcesJar(publish = true)`, which would leave one empty
 * jar per target writing to the same path.
 */
mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )
    publishToMavenCentral()
    signAllPublications()
}

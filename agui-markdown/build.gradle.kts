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

    // Matches every other module in this repository: upstream's `kotlin-core-jvm` is class-file 65,
    // so 21 is the lowest floor the repository can promise.
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    // Explicit for the same reason as in `agui-compose`: this module adds `dependsOn` edges below,
    // and Kotlin silently drops the default hierarchy template for any project that does.
    applyDefaultHierarchyTemplate()

    android {
        namespace = "dev.ynagai.agui.markdown"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Running the style tests and none of the drawing ones. `commonTest` reaches here, so the
        // pure factories in `MarkdownAguiStyle.kt` are covered on Android; everything that needs a
        // composition lives in `composeUiTest`, which Android does not depend on, so the renderer
        // itself is not. That is a narrower hole than the other two modules have rather than no
        // hole -- closing it needs Robolectric or an instrumentation harness for the repository as
        // a whole, and `docs/decisions/0003` records that argument once so it is not had again per
        // build file.
        withHostTest {}
    }

    jvm()

    // Four targets, matching `agui-compose`, and for its reason rather than this module's:
    // Compose Multiplatform 1.12.0 publishes no `ios_x64` variant of `foundation`, `ui` or
    // `runtime`. The Markdown renderer happens to publish the same four (plus `js`, `wasmJs` and
    // `macosArm64`, which this repository does not take), so it adds no constraint of its own.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        /**
         * Tests that need a composition on screen.
         *
         * As in `agui-material3`: the renderer draws, so its tests live here. `commonTest` is not
         * empty, though -- the two style factories are pure, and testing them there rather than
         * here is what gets them onto Android. Of the three targets compiled, two run --
         * `iosArm64` is a device target, so Kotlin links the test binary without creating a task
         * that runs it.
         */
        val composeUiTest = create("composeUiTest") {
            dependsOn(commonTest.get())
            dependencies { implementation(libs.compose.ui.test) }
        }

        jvmTest.get().dependsOn(composeUiTest)
        iosArm64Test.get().dependsOn(composeUiTest)
        iosSimulatorArm64Test.get().dependsOn(composeUiTest)

        commonMain.dependencies {
            // `api`: what this module publishes is an `AguiTextRenderer`, and a consumer who could
            // not name that type could not provide it to `LocalAguiTextRenderer`.
            api(projects.aguiCompose)

            // `api` as well, and here it is not a convenience: `MarkdownColors`, `MarkdownTypography`
            // and `MarkdownFlavourDescriptor` are constructor parameters of the renderer this module
            // publishes. Scoped `implementation` they would reach a consumer's runtime classpath but
            // not its compile classpath, and the renderer could not be constructed at all.
            api(libs.markdown.renderer)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // As in the other drawing modules: Compose's JVM test harness draws through Skiko,
            // whose native library ships with the desktop artifact rather than with `ui-test`.
            implementation(compose.desktop.currentOs)
        }
    }
}

/**
 * Maven Central. Identical in shape to the other modules' blocks, and for the same reasons: an
 * unsigned artifact is rejected by the portal, so signing is unconditional rather than dependent on
 * a key being present; the javadoc jar is a real one because the KDoc here carries the reasoning a
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

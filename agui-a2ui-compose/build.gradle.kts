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
        namespace = "dev.ynagai.agui.a2ui.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Wired as `agui-compose` wires it, with a smaller caveat: the composition tests live in
        // `composeUiTest`, which Android does not run, so what runs here is `commonTest` alone --
        // the one catalog-id check. See that module's build file.
        withHostTest {}
    }

    jvm()

    // Four targets, for the reason `agui-compose` gives and for `agui-a2ui`'s as well: neither
    // Compose Multiplatform nor `a2ui-compose` publishes `iosX64`.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // Tests that need a composition on screen: compiled for the JVM and both iOS targets, not
        // Android, as in `agui-compose`, and for the reasons given there.
        val composeUiTest = create("composeUiTest") {
            dependsOn(commonTest.get())
            dependencies { implementation(libs.compose.ui.test) }
        }

        jvmTest.get().dependsOn(composeUiTest)
        iosArm64Test.get().dependsOn(composeUiTest)
        iosSimulatorArm64Test.get().dependsOn(composeUiTest)

        commonMain.dependencies {
            // `api`, all four: `A2uiHost` holds an `A2uiRenderer`, `withA2ui` takes a
            // `ComponentRegistry` and returns `AguiComponents`, and the slots it fills are
            // `@Composable` lambdas. Every one of those is in the published signatures.
            api(projects.aguiA2ui)
            api(projects.aguiCompose)
            api(libs.a2ui.compose)
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

/** Maven Central. Identical in shape to `agui-compose`'s block, and for the same reasons. */
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

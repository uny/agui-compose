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
        namespace = "dev.ynagai.agui.material3"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Wired, and -- as in `agui-compose` -- today it runs nothing. Every test in this module
        // draws, so every test lives in `composeUiTest`, which Android does not depend on, and
        // `commonTest` is empty. `testAndroidHostTest` therefore reports NO-SOURCE and these
        // renderers ship in the `.aar` with nothing exercising them.
        //
        // This is the second module with the same hole. Closing it needs Robolectric or an
        // instrumentation harness for the repository as a whole rather than a decision taken twice
        // in two build files; see `docs/decisions/0003`.
        withHostTest {}
    }

    jvm()

    // Four targets, matching `agui-compose`. `iosX64` is absent because Compose Multiplatform
    // 1.12.0 publishes no `ios_x64` variant of `foundation`, `ui` or `runtime`.
    //
    // Not because of Material 3: `org.jetbrains.compose.material3:material3:1.9.0` does publish
    // `ios_x64` (it rides its own version line, based on CMP 1.9.1). The constraint arrives through
    // `agui-compose`, and adding the target here would fail to resolve foundation rather than
    // gaining a simulator.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        /**
         * Tests that need a composition on screen.
         *
         * The set of targets, and the reason Android is not among them, are `agui-compose`'s and
         * are explained there. Every renderer in this module draws, so everything here lives in
         * this source set and `commonTest` stays empty.
         *
         * Of the three targets compiled, two run: `iosArm64` is a device target, so Kotlin links
         * the test binary without creating a task that executes it. Assertions execute on `jvm`
         * and `iosSimulatorArm64`.
         */
        val composeUiTest = create("composeUiTest") {
            dependsOn(commonTest.get())
            dependencies { implementation(libs.compose.ui.test) }
        }

        jvmTest.get().dependsOn(composeUiTest)
        iosArm64Test.get().dependsOn(composeUiTest)
        iosSimulatorArm64Test.get().dependsOn(composeUiTest)

        commonMain.dependencies {
            // `api`: this module's whole published surface is `agui-compose`'s types with Material
            // 3 values in them. `Material3AguiComponents` returns an `AguiComponents`, and a
            // consumer who could not name that type could not `copy` a slot of it.
            api(projects.aguiCompose)

            // `api` too, and not because a Material 3 type appears in a signature -- none does.
            // It is `api` because a consumer cannot *use* what this module draws without it: every
            // renderer here reads `MaterialTheme`, so a `MaterialTheme { }` has to sit above the
            // transcript, and a consumer who got these renderers without a way to name it would
            // have a table that draws unthemed defaults.
            api(libs.compose.material3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // As in `agui-compose`: Compose's JVM test harness draws through Skiko, whose native
            // library ships with the desktop artifact rather than with `ui-test`.
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

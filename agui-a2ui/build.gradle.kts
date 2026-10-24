import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    explicitApi()

    // Matches `agui-agent`: upstream's `kotlin-core-jvm` is class-file 65, so 21 is the lowest
    // floor this repository can promise.
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }

    android {
        namespace = "dev.ynagai.agui.a2ui"
        // The lower of the two floors, as of `a2ui-core` 0.2.0: this module draws nothing, and
        // every AAR it depends on now says 24 or less. See the catalog for what the value promises.
        compileSdk = libs.versions.android.coreCompileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Runs `commonTest` on the JVM against the Android variant, for the reason `agui-agent`
        // gives: a published target the suite is silent about is a target nothing has checked.
        withHostTest {}
    }

    jvm()

    // Four targets, not the five `agui-agent` publishes, and not for the reason the Compose
    // modules give. This module draws nothing; what it lacks is `a2ui-core`, which publishes
    // `ios_arm64` and `ios_simulator_arm64` and no `ios_x64` -- measured from its module metadata.
    // A protocol translation that cannot resolve its target on a platform cannot promise it.
    iosArm64()
    iosSimulatorArm64()

    js { browser() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("upstream") {
                withJvm()
                withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
                withIos()
            }
        }
    }

    sourceSets {
        // SPIKE (#29): the three files that name upstream's types, compiled for the four native
        // targets only. The web targets get the rest of the module.
        named("upstreamMain").dependencies {
            api(libs.agui.core)
            api(libs.agui.tools)
        }
        named("upstreamTest").dependencies { implementation(projects.aguiAgent) }

        commonMain.dependencies {
            // `api`: the entry points take `agui-model`'s parts and `UiTranscript`, and hand back
            // `a2ui-core`'s messages. A consumer holds both to call anything here.
            api(projects.aguiModel)
            api(libs.a2ui.core)

        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // The recorded upstream traffic the trace-driven tests fold. JVM-only because the
            // fixtures are resource files, and only the JVM test task has a classpath to read
            // them off; what runs on the other targets is `commonTest`.
            implementation(projects.aguiCore)
            implementation(projects.aguiReplay)
        }
    }
}

/**
 * Maven Central. Identical in shape to `agui-agent`'s block, and for the same reasons.
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


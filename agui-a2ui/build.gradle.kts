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
    abiValidation()

    android {
        namespace = "dev.ynagai.agui.a2ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
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

    sourceSets {
        commonMain.dependencies {
            // `api`: the entry points take `agui-model`'s parts and `UiTranscript`, and hand back
            // `a2ui-core`'s messages. A consumer holds both to call anything here.
            api(projects.aguiModel)
            api(libs.a2ui.core)

            // `api`: `RenderA2UiTool` is an `AbstractToolExecutor`, and `catalogContext` builds
            // upstream's `Context`. Both types are in the signature, so the artifacts that carry
            // them are declared where the signature is -- the rule `agui-agent` follows.
            api(libs.agui.core)
            api(libs.agui.tools)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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

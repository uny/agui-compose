import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    android {
        namespace = "dev.ynagai.agui.model"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Runs `commonTest` on the JVM against the Android variant. Without it the warning the
        // build prints is literal: not one test would ever run on the target whose `.aar` is
        // published, and the suite would be green on four targets and silent on the fifth.
        withHostTest {}
    }

    jvm()

    // The five targets the upstream SDK publishes, and no more. `iosX64` is in the list because
    // upstream ships it and dropping it would make this library unusable from an Intel simulator
    // that upstream still supports. macOS/Native and the two web backends are absent for the
    // reason recorded in `docs/decisions/0001-riding-on-the-upstream-kotlin-sdk.md`.
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            // `api`: a part's payload -- tool-call arguments, an A2UI surface message, a state
            // snapshot -- is a `JsonElement` on this module's own signatures, so a consumer
            // cannot read one without the type.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

/**
 * Maven Central.
 *
 * `signAllPublications` rather than a conditional: an unsigned artifact is rejected by the
 * portal's validation, so making it depend on a key being present would turn a missing secret into
 * a failure at the end of a release run instead of the start of one. A `-SNAPSHOT` version is
 * exempt; a release version is not, including for `publishToMavenLocal`.
 *
 * The javadoc jar is a real one. Central requires the artifact either way, and the KDoc in this
 * library carries the reasoning behind its own rules, which is the part a consumer cannot
 * re-derive from the signatures.
 *
 * The sources jar is registered here rather than by `withSourcesJar(publish = true)` in the
 * `kotlin` block: the KMP helper registers one empty jar per target, each writing to the same
 * `-sources.jar` path the publication reads, and Gradle then refuses the build for an implicit
 * dependency it cannot order. Asking the publishing plugin for it leaves one producer.
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

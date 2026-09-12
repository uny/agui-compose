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

    // The published JVM floor, pinned rather than inherited. Upstream's
    // `kotlin-core-jvm` is class-file 65, so 21 is the lowest this can be; without a
    // toolchain the class-file version is whichever JDK ran Gradle, which makes the
    // floor promised in the README an accident of the publisher's machine.
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    android {
        namespace = "dev.ynagai.agui.agent"
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
            // `api`: the entry point takes upstream's `AbstractAgent` and hands back
            // `agui-model`'s `UiTranscript` -- reached through `agui-core`, which exposes it as
            // `api` -- so a consumer holds both to call it at all.
            api(projects.aguiCore)
            api(libs.agui.client)

            // Not named by a line of this module, and required for anything built on it to run.
            //
            // `kotlin-client` and `kotlin-tools` 0.4.1 are compiled against kotlinx-datetime 0.6.2,
            // where `kotlinx.datetime.Clock` is a class -- `AbstractAgent.Companion`,
            // `AgUiAgent`, `ToolExecutionManager` and four more call it. In 0.7 `Clock` and
            // `Instant` moved to `kotlin.time` and those classes are gone, so an application that
            // resolves to 0.7 compiles and then dies on the first event upstream timestamps:
            //
            //   java.lang.NoClassDefFoundError: kotlinx/datetime/Clock$System
            //
            // That is not hypothetical. Compose Material 3 1.9.0 brings 0.7.1, so *any* consumer
            // taking `agui-material3` and this module together hit it until this line existed --
            // and nothing in either module's own test classpath could have caught it, because the
            // two never meet until something depends on both. `agui-sample` is what found it.
            //
            // `0.8.0-0.6.x-compat` is the artifact kotlinx-datetime publishes for exactly this
            // case: 0.8.0 with the 0.6.x binary surface kept, so both sides find what they were
            // compiled against. `implementation` rather than `api` -- nothing in this module's
            // compile surface names a datetime type, and it is the *runtime* classpath that
            // crashed -- which is enough: an `implementation` dependency rides in this module's
            // published `runtimeElements`, and that is the classpath a consumer resolves.
            //
            // It wins because Gradle resolves a conflict to the highest version and this one sorts
            // above 0.7.1, not because of where it is declared. So it stops winning the day
            // something on a consumer's graph brings a version that sorts higher still -- at run
            // time, not at build time. `strictly` would hold it and is deliberately not used: this
            // library does not get to decide that a consumer may never take a newer datetime. The
            // whole argument is in `docs/decisions/0007`.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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

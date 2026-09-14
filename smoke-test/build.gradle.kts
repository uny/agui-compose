/*
 * A consumer, not a subproject.
 *
 * This is a separate Gradle build with its own `settings.gradle.kts`, and it depends on
 * `dev.ynagai.agui` by *coordinate* rather than by project. That is the whole point: a project
 * dependency resolves through the producer's own configurations and so never reads the `.module`
 * metadata, the POM, or the per-target coordinates a real consumer resolves. The failure this
 * guards against is the one that does not show up until someone else tries to use the release --
 * publish succeeds, and the consumer cannot resolve.
 *
 * It declares every target the library publishes, and depends on every module from the source
 * set that matches the module's own target list. Three modules (`agui-model`, `agui-core`,
 * `agui-agent`) publish the five targets the upstream SDK does, `iosX64` among them; the other
 * six publish four, because they ride on Compose Multiplatform or on `a2ui-compose`, and neither
 * publishes `iosX64`. One `commonMain` naming all nine would not resolve on `iosX64`, and
 * dropping `iosX64` from this build would leave a published variant of three modules uncovered.
 * So the three are `commonMain` dependencies and the six belong to `noIosX64Main`, an
 * intermediate source set that only the four targets they publish depend on. The first draft of
 * this build put `agui-a2ui` in the first group, and this gate is what said otherwise: its
 * `.module` file lists no `iosX64` variant, because `a2ui-core`'s does not.
 *
 * No Compose compiler plugin. The sources name Compose types on published signatures and call
 * nothing composable, and a compilation that carries no `@Composable` does not need the plugin.
 * Applying it would put a second reason for a failure on this path -- the plugin refusing to run
 * without the runtime on the compile class path -- that is not the property under test.
 */
import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    // From the producer's own version catalog, read across the build boundary in
    // `settings.gradle.kts`. A consumer pinned to a different Kotlin than the library was built
    // with is not a consumer this gate should be testing.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

/**
 * The version under test, passed in by the release workflow as `-PaguiVersion`.
 *
 * With no property it falls back to the producer's own `VERSION_NAME`, read across the build
 * boundary rather than copied. A literal default here would keep naming `0.1.0-SNAPSHOT` after the
 * producer moved on, and because `mavenLocal()` still holds that older snapshot the by-hand run
 * would go green against artifacts that are not the ones just published.
 */
val aguiVersion: String =
    (findProperty("aguiVersion") as String?)?.takeIf { it.isNotBlank() }
        ?: file("../gradle.properties")
            .takeIf { it.isFile }
            ?.let { properties -> Properties().apply { properties.inputStream().use(::load) } }
            ?.getProperty("VERSION_NAME")
        ?: error(
            "No -PaguiVersion, and no VERSION_NAME in ../gradle.properties. This build reads the " +
                "producer's version from the repository it sits in; run it from there, or pass " +
                "-PaguiVersion=<version>.",
        )

kotlin {
    // The repository's floor, for the reason `agui-agent` records: upstream's `kotlin-core-jvm`
    // is class-file 65.
    jvmToolchain(21)

    // The default template plus one group. `noIosX64` is "every target but `iosX64`" -- the four
    // that six of the nine modules publish -- and it gives this build a `noIosX64Main` source set
    // for those modules' dependencies. A group in the template rather than hand-written
    // `dependsOn` edges, because KGP refuses to apply the default template alongside explicit
    // edges, and without the template there would be no `iosMain` and no `commonMain`-to-target
    // wiring to start from.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("noIosX64") {
                withAndroidTarget()
                withJvm()
                withIosArm64()
                withIosSimulatorArm64()
            }
        }
    }

    android {
        namespace = "dev.ynagai.agui.smoketest"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation("dev.ynagai.agui:agui-model:$aguiVersion")
            implementation("dev.ynagai.agui:agui-core:$aguiVersion")
            implementation("dev.ynagai.agui:agui-agent:$aguiVersion")
        }

        // The six modules that publish no `iosX64`, on the four targets they do publish.
        getByName("noIosX64Main").dependencies {
            implementation("dev.ynagai.agui:agui-a2ui:$aguiVersion")
            implementation("dev.ynagai.agui:agui-compose:$aguiVersion")
            implementation("dev.ynagai.agui:agui-material3:$aguiVersion")
            implementation("dev.ynagai.agui:agui-markdown:$aguiVersion")
            implementation("dev.ynagai.agui:agui-a2ui-compose:$aguiVersion")
            implementation("dev.ynagai.agui:agui-a2ui-material3:$aguiVersion")
        }
    }
}

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
 * set that matches the module's own target list. There are four lists:
 *
 * - `agui-model`: seven -- the upstream SDK's five, plus `js` and `wasmJs`. `commonMain`.
 * - `agui-core`, `agui-agent`: the upstream SDK's five, `iosX64` among them. `protocolMain`.
 * - `agui-compose`, `agui-material3`, `agui-markdown`: six -- no `iosX64`, because Compose
 *   Multiplatform publishes none, and the two web backends. `drawingMain`.
 * - `agui-a2ui`, `agui-a2ui-compose`, `agui-a2ui-material3`: four -- neither `iosX64` nor the web,
 *   because they reach the upstream SDK and `a2ui-compose` both. `a2uiMain`, under `drawingMain`.
 *
 * One `commonMain` naming all nine would resolve on none of `iosX64`, `js` or `wasmJs`, and
 * dropping a target from this build would leave a published variant uncovered. The first draft of
 * this build put `agui-a2ui` with the five-target modules, and this gate is what said otherwise:
 * its `.module` file lists no `iosX64` variant, because `a2ui-core`'s does not.
 *
 * No Compose compiler plugin. The sources name Compose types on published signatures and call
 * nothing composable, and a compilation that carries no `@Composable` does not need the plugin.
 * Applying it would put a second reason for a failure on this path -- the plugin refusing to run
 * without the runtime on the compile class path -- that is not the property under test.
 */
import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

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

// Read by `floor/`, the second consumer in this build, so the two resolve the same publish from
// one place rather than each parsing the producer's properties file.
extra["aguiVersion"] = aguiVersion

kotlin {
    // The repository's floor, for the reason `agui-agent` records: upstream's `kotlin-core-jvm`
    // is class-file 65.
    jvmToolchain(21)

    // The default template plus three groups, one per target list in the header. `protocol` and
    // `drawing` overlap -- the JVM, Android and two iOS targets are in both -- and `a2ui` is their
    // intersection, nested under `drawing` because what it touches names Compose types. Groups in
    // the template rather than hand-written `dependsOn` edges, because KGP refuses to apply the default template alongside explicit
    // edges, and without the template there would be no `iosMain` and no `commonMain`-to-target
    // wiring to start from.
    //
    // Android by platform type, not `withAndroidTarget()`: that matcher covers the old
    // `com.android.library` target only, and against the `com.android.kotlin.multiplatform.library`
    // target this build uses it matches nothing -- `androidMain` then depends on `commonMain`
    // alone, `compileAndroidMain` compiles `Smoke.kt` and nothing else, and every other module's
    // `-android` variant is never resolved, with nothing in the output to say so. Measured on
    // 2026-09-15: with `withAndroidTarget()` the edge is absent; with the predicate below the group
    // edges are present and every file compiles for Android.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("protocol") {
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
                withJvm()
                withIosArm64()
                withIosSimulatorArm64()
                withIosX64()
            }
            group("drawing") {
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
                withJvm()
                withIosArm64()
                withIosSimulatorArm64()
                withJs()
                withWasmJs()
                group("a2ui") {
                    withCompilations { it.platformType == KotlinPlatformType.androidJvm }
                    withJvm()
                    withIosArm64()
                    withIosSimulatorArm64()
                }
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

    js { browser() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation("dev.ynagai.agui:agui-model:$aguiVersion")
        }
        getByName("protocolMain").dependencies {
            implementation("dev.ynagai.agui:agui-core:$aguiVersion")
            implementation("dev.ynagai.agui:agui-agent:$aguiVersion")
        }
        getByName("drawingMain").dependencies {
            implementation("dev.ynagai.agui:agui-compose:$aguiVersion")
            implementation("dev.ynagai.agui:agui-material3:$aguiVersion")
            implementation("dev.ynagai.agui:agui-markdown:$aguiVersion")
        }
        getByName("a2uiMain").dependencies {
            implementation("dev.ynagai.agui:agui-a2ui:$aguiVersion")
            implementation("dev.ynagai.agui:agui-a2ui-compose:$aguiVersion")
            implementation("dev.ynagai.agui:agui-a2ui-material3:$aguiVersion")
        }
    }
}

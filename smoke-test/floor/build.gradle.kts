/*
 * The `compileSdk` floor, gated rather than asserted.
 *
 * `docs/decisions/0014` promises that `agui-model`, `agui-core`, `agui-agent` and `agui-a2ui` can
 * be taken by an Android consumer compiling against API 24, because none of them draws and every
 * AAR they depend on asks for 24 or less. The root project cannot check that: it compiles its
 * Android target at the drawing floor (37), so it resolves those four at 37 and says nothing about
 * the promise.
 *
 * This is an Android consumer at 24 depending on exactly those four. What enforces the floor is
 * AGP's own `checkAarMetadata`, which reads each dependency's published
 * `aar-metadata.properties` and fails naming the module and the version it wants -- the same
 * failure a consumer gets, from the same file, at the same API level. So a dependency bump that
 * raises one of the four above 24, or a module reading the wrong catalog key, fails on the tag
 * rather than in someone else's project.
 *
 * The same Android plugin and no Compose, matching the root project: these four carry no Compose,
 * and a consumer at 24 could not take one that did.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.ynagai.agui.smoketest.floor"

        // The promise under test, read from the same catalog key the four modules publish with
        // rather than written as a literal -- a literal would keep passing after the key moved.
        compileSdk = libs.versions.android.coreCompileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        androidMain.dependencies {
            // The version the root project resolved; see its `extra["aguiVersion"]`.
            val aguiVersion = rootProject.extra["aguiVersion"] as String
            implementation("dev.ynagai.agui:agui-model:$aguiVersion")
            implementation("dev.ynagai.agui:agui-core:$aguiVersion")
            implementation("dev.ynagai.agui:agui-agent:$aguiVersion")
            implementation("dev.ynagai.agui:agui-a2ui:$aguiVersion")
        }
    }
}

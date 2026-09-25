rootProject.name = "agui-consumer-smoke-test"

// The `compileSdk` floor gate. A second consumer rather than a second source set, because the
// property under test is a *lower* `compileSdk` than the root project compiles at and one Android
// library cannot be built at two.
include(":floor")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // The producer's catalog, read across the build boundary rather than copied. This build pins
    // the same Kotlin and AGP the library is built with, and the same `compileSdk`/`minSdk`;
    // hand-copied, those drift, and the drift surfaces on a tag -- the one run where a failure
    // costs a version number.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }

    repositories {
        // First, and the point of the whole build: the artifacts under test are resolved from a
        // repository, exactly as a consumer resolves them, rather than by a project dependency
        // that never exercises the published metadata.
        //
        // `exclusiveContent`, which is doing two things at once.
        //
        // It restricts `mavenLocal()` to the group under test: unfiltered it would also shadow
        // every third-party dependency from `~/.m2`, so a stale or hand-installed
        // Kotlin/AndroidX/Compose jar on a developer's machine would silently win over Maven
        // Central and the gate would pass against a dependency set no consumer ever resolves.
        //
        // And it restricts the group under test to `mavenLocal()`: `dev.ynagai.agui` is not
        // looked up anywhere else. Without that, once a version is actually on Central the
        // fallback would answer for anything the local publish failed to write -- the gate would
        // validate the *last* release instead of the one being cut.
        //
        // `includeGroup` is an exact match, so `dev.ynagai.a2ui` -- the renderer `agui-a2ui`
        // depends on, already on Central -- is still resolved from there, as a consumer would.
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("dev.ynagai.agui") }
        }
        google {
            mavenContent {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

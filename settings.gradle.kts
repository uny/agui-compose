// `agui` rather than `agui-compose`: a root project may not share its name with one of its
// subprojects, and `agui-compose` is the name the UI module will take.
rootProject.name = "agui"

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
    repositories {
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

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":agui-model")
include(":agui-core")
include(":agui-compose")
include(":agui-material3")
include(":agui-markdown")

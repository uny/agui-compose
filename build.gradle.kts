plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.maven.publish) apply false
    // Applied here as well as in each module: the root is where the nine published modules' HTML
    // is aggregated into one site with cross-module links, which `docs.yml` publishes to GitHub Pages.
    alias(libs.plugins.dokka)
}

allprojects {
    group = "dev.ynagai.agui"
    version = findProperty("VERSION_NAME")?.toString() ?: "0.1.0-SNAPSHOT"
}

// The published modules only -- `agui-sample` is an app, and `agui-replay` the server it and the
// trace-driven tests replay traffic from; neither is an API anyone depends on.
dependencies {
    dokka(projects.aguiModel)
    dokka(projects.aguiCore)
    dokka(projects.aguiAgent)
    dokka(projects.aguiA2ui)
    dokka(projects.aguiA2uiCompose)
    dokka(projects.aguiA2uiMaterial3)
    dokka(projects.aguiCompose)
    dokka(projects.aguiMaterial3)
    dokka(projects.aguiMarkdown)
}

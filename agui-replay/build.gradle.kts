plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

/*
 * A server that replays recorded AG-UI traffic over SSE. Not published, and JVM-only: it exists
 * so the sample and the trace-driven tests have an A2UI-speaking server that needs no Python, no
 * model and no key -- the traces are upstream's own, recorded off its LangGraph agents through its
 * a2ui middleware (see `src/main/resources/traces/README.md`).
 *
 * `kotlin("jvm")` rather than a single-target multiplatform build, unlike `agui-sample`: nothing
 * here will ever want a second target, and a KMP build for a Ktor server would be ceremony.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("dev.ynagai.agui.replay.ReplayMain")
}

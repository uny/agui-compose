package dev.ynagai.agui.agent

import com.agui.client.agent.HttpAgent
import com.agui.client.agent.HttpAgentConfig
import kotlin.test.Test

/**
 * Compiles, or does not: that is the assertion.
 *
 * On the JVM and Android, `kotlin-client` exposes Ktor at runtime only, and `HttpAgent`'s single
 * constructor names `io.ktor.client.HttpClient` in its signature. Whether a consumer holding
 * `agui-agent` alone can construct the upstream transport the README shows is therefore a question
 * about the compile classpath, and this file is where the answer is measured rather than assumed.
 * It lives in `commonTest` because that is the only source set the Android host test compiles:
 * `withHostTest {}` takes `commonTest` and nothing from `jvmTest`, so a `jvmTest` here would
 * measure the JVM and leave Android -- the other platform with the runtime-only shape -- to
 * inference. On iOS the same code compiles for a different reason (upstream exposes Ktor as `api`
 * there), which is fine; the measurement is the two platforms where it could fail.
 *
 * Measured answer: it compiles, with no Ktor artifact on either compile classpath (checked with
 * `:agui-agent:dependencies --configuration jvmCompileClasspath`). The constructor's `HttpClient?`
 * parameter is left to its default, and the compiler does not need the class to do that. A
 * consumer that *passes* a client is naming the type itself, and declares `ktor-client-core` for
 * the same reason it would in any project.
 */
class HttpAgentReachabilityTest {

    @Test
    fun the_upstream_transport_is_constructible_from_this_module_alone() {
        HttpAgent(HttpAgentConfig(url = "http://127.0.0.1:1")).dispose()
    }
}

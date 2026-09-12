package dev.ynagai.agui.replay

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * An AG-UI server whose every answer is a recording.
 *
 * One route per trace: `POST /<name>` takes a `RunAgentInput`, reads its `threadId` and `runId`,
 * and streams the trace's next run for that thread as `text/event-stream`, one `data:` line per
 * event, [delayMillis] apart so that a client draws it as a stream rather than as a blink. The
 * request body is otherwise ignored -- what the user typed cannot change what was recorded --
 * and `GET /` lists the routes.
 *
 * Runs are counted per thread, so a client that keeps its `threadId` across turns walks through a
 * multi-run recording, and one that starts a new thread starts the recording over.
 */
@OptIn(ExperimentalAtomicApi::class)
public class ReplayServer(
    public val traces: List<ReplayTrace> = ReplayTrace.RESOURCES.map(ReplayTrace::resource),
    public val port: Int = DEFAULT_PORT,
    public val delayMillis: Long = DEFAULT_DELAY_MILLIS,
) {
    private val byName = traces.associateBy { it.name }
    private val turns = mutableMapOf<String, AtomicInt>()

    /** Builds the server. Call `start(wait = ...)` on what comes back. */
    public fun build(): EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
        install(SSE)
        routing {
            get("/") {
                call.respondText(byName.keys.joinToString("\n") { "POST /$it" } + "\n")
            }
            for (trace in traces) {
                // `sse` rather than a hand-written response: it sets the content type, keeps the
                // connection open and flushes each event, which is what a client's SSE parser
                // needs to see the events as they are sent rather than when the response ends.
                sse("/${trace.name}") { play(trace) }
            }
            post("/{name}") {
                // Ktor's `sse` answers GET and POST alike, so this is reached only for a name no
                // trace has; a clear 404 beats CIO's default empty one.
                call.respondText("No trace named `${call.parameters["name"]}`\n", status = HttpStatusCode.NotFound)
            }
        }
    }

    private suspend fun ServerSSESession.play(trace: ReplayTrace) {
        val input = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val threadId = input.string("threadId") ?: "thread"
        val runId = input.string("runId") ?: "run"
        val turn = synchronized(turns) { turns.getOrPut("${trace.name}/$threadId") { AtomicInt(-1) } }
            .incrementAndFetch()
        for (event in trace.run(turn, threadId, runId)) {
            send(ServerSentEvent(data = event.toString()))
            if (delayMillis > 0) delay(delayMillis)
        }
    }

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    public companion object {
        public const val DEFAULT_PORT: Int = 8000
        public const val DEFAULT_DELAY_MILLIS: Long = 40

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** `./gradlew :agui-replay:run [--args="<port> [<delay-ms>]"]`. */
public object ReplayMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: ReplayServer.DEFAULT_PORT
        val delay = args.getOrNull(1)?.toLongOrNull() ?: ReplayServer.DEFAULT_DELAY_MILLIS
        val server = ReplayServer(port = port, delayMillis = delay)
        println("Replaying ${server.traces.size} recorded traces on http://localhost:$port/ :")
        for (trace in server.traces) println("  http://localhost:$port/${trace.name}  (${trace.runs.size} run(s))")
        server.build().start(wait = true)
    }
}


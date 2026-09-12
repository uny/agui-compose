package dev.ynagai.agui.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One recorded conversation: the events a client received, split into the runs they belong to.
 *
 * A run is everything from one `RUN_STARTED` to the next. A recording of a two-turn conversation
 * holds two runs, and the server plays the Nth run for the Nth request a thread makes -- so a
 * client that sends a second message gets the second recorded answer, not the first one again.
 *
 * @property events every event, in wire order, as recorded.
 */
public class ReplayTrace(public val name: String, public val events: List<JsonObject>) {
    /** The events grouped by run. Anything before the first `RUN_STARTED` belongs to the first run. */
    public val runs: List<List<JsonObject>> = buildList {
        var current = mutableListOf<JsonObject>()
        for (event in events) {
            if (event.type == "RUN_STARTED" && current.isNotEmpty()) {
                add(current)
                current = mutableListOf()
            }
            current += event
        }
        if (current.isNotEmpty()) add(current)
    }

    /**
     * The run to play for the [index]th request on a thread, with its ids rewritten to the
     * client's. A thread that asks for more runs than were recorded gets the last one again --
     * a conversation that goes on past the recording has nothing truer to say.
     */
    public fun run(index: Int, threadId: String, runId: String): List<JsonObject> =
        runs[index.coerceIn(0, runs.lastIndex)].map { event ->
            when (event.type) {
                "RUN_STARTED", "RUN_FINISHED", "RUN_ERROR" -> JsonObject(
                    event + mapOf("threadId" to JsonPrimitive(threadId), "runId" to JsonPrimitive(runId)),
                )
                else -> event
            }
        }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a recording: a JSON array of event objects. */
        public fun parse(name: String, text: String): ReplayTrace =
            ReplayTrace(name, (json.parseToJsonElement(text) as JsonArray).map { it.jsonObject })

        /**
         * A recording shipped in this module's resources, by its file name without `.json`.
         *
         * @throws IllegalArgumentException when there is no such recording.
         */
        public fun resource(name: String): ReplayTrace {
            val stream = ReplayTrace::class.java.getResourceAsStream("/traces/$name.json")
                ?: throw IllegalArgumentException("No recorded trace named `$name`; see ${RESOURCES.joinToString()}")
            return parse(name, stream.bufferedReader().use { it.readText() })
        }

        /** Every recording this module ships. Listed by hand: a jar has no directory listing. */
        public val RESOURCES: List<String> = listOf(
            "advanced-hotel-comparison",
            "dynamic-team-roster",
            "dynamic-product-comparison",
            "fixed-flight-search",
            "fixed-multiple-surfaces",
        )
    }
}

private val JsonObject.type: String? get() = this["type"]?.jsonPrimitive?.content

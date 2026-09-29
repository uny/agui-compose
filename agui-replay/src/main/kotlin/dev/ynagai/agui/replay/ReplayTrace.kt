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
        var started = false
        for (event in events) {
            if (event.type == "RUN_STARTED") {
                // Only a run already under way is closed by the next `RUN_STARTED`: a prelude
                // before the first one belongs to the first run, as the summary says.
                if (started) {
                    add(current)
                    current = mutableListOf()
                }
                started = true
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
        runs.getOrNull(index.coerceAtMost(runs.lastIndex)).orEmpty().map { event ->
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

        /**
         * Every `*.json` in [directory], each named by its file name without `.json`, sorted by name.
         *
         * For recordings that belong to an application rather than to this module -- a client's
         * own catalog, with its own data -- kept beside that application's code instead of here.
         *
         * A name becomes a route path as it stands, so it is held to letters, digits, `.`, `_` and `-`,
         * starting with a letter or a digit: `{id}` would be a route parameter answering every path,
         * and `50%` a malformed escape that stops the server from starting.
         *
         * @throws IllegalArgumentException when [directory] is not a directory, or cannot be listed,
         *   or holds a recording whose name is not a plain path segment, or that does not parse.
         */
        public fun directory(directory: java.io.File): List<ReplayTrace> {
            require(directory.isDirectory) { "Not a directory: ${directory.absoluteFile}" }
            // `listFiles` answers null, not empty, for a directory it cannot read: an error, not a
            // directory without recordings.
            val files = requireNotNull(directory.listFiles { file -> file.isFile && file.extension == "json" }) {
                "Cannot list ${directory.absoluteFile}"
            }
            return files
                .sortedBy { it.name }
                .map { file ->
                    val name = file.nameWithoutExtension
                    require(name.matches(PLAIN_NAME)) { "Not a plain trace name: `$name`, from $file" }
                    try {
                        parse(name, file.readText())
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Not a recording: $file", e)
                    }
                }
        }

        private val PLAIN_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

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

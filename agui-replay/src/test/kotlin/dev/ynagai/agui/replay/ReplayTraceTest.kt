package dev.ynagai.agui.replay

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayTraceTest {
    @Test
    fun `every shipped trace parses and splits into its runs`() {
        val runs = ReplayTrace.RESOURCES.associateWith { ReplayTrace.resource(it).runs.size }
        assertEquals(
            mapOf(
                "advanced-hotel-comparison" to 1,
                "dynamic-team-roster" to 1,
                "dynamic-product-comparison" to 1,
                "fixed-flight-search" to 1,
                "fixed-multiple-surfaces" to 2,
            ),
            runs,
        )
    }

    @Test
    fun `a run is played with the client's ids and the recording's everything else`() {
        val trace = ReplayTrace.resource("fixed-multiple-surfaces")
        val second = trace.run(1, threadId = "T", runId = "R")
        assertEquals("RUN_STARTED", second.first()["type"]!!.jsonPrimitive.content)
        assertEquals("T", second.first()["threadId"]!!.jsonPrimitive.content)
        assertEquals("R", second.last()["runId"]!!.jsonPrimitive.content)
        // The recording's own ids, elsewhere: the client sees what the browser saw.
        assertEquals("id-1", second.first()["input"]!!.let { (it as kotlinx.serialization.json.JsonObject)["threadId"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a thread that outlives the recording hears the last run again`() {
        val trace = ReplayTrace.resource("fixed-flight-search")
        assertEquals(trace.run(0, "t", "r"), trace.run(7, "t", "r"))
    }

    @Test
    fun `a prelude before the first RUN_STARTED belongs to the first run`() {
        val trace = ReplayTrace.parse(
            "p",
            """[{"type":"CUSTOM"},{"type":"RUN_STARTED"},{"type":"RUN_FINISHED"},{"type":"RUN_STARTED"},{"type":"RUN_FINISHED"}]""",
        )
        assertEquals(listOf(3, 2), trace.runs.map { it.size })
        assertEquals("CUSTOM", trace.run(0, "t", "r").first()["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty recording plays as nothing rather than throwing`() {
        assertEquals(emptyList(), ReplayTrace.parse("e", "[]").run(0, "t", "r"))
    }
}

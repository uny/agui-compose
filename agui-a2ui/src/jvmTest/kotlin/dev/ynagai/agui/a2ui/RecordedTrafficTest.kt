package dev.ynagai.agui.a2ui

import com.agui.core.types.AgUiJson
import com.agui.core.types.BaseEvent
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.surface.MessageProcessor
import dev.ynagai.a2ui.core.surface.RendererState
import dev.ynagai.agui.core.UiTranscriptReducer
import dev.ynagai.agui.model.UiTranscript
import dev.ynagai.agui.replay.ReplayTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The whole path, on upstream's recorded traffic: events → transcript → payloads → surfaces →
 * `a2ui-core` state. What these prove is that the v0.9 → v1.0 rewrite is mechanical *for what
 * upstream actually sends*, which the evolution guide asserts and no unit test can.
 */
class RecordedTrafficTest {
    /** Folds every event of [name] and applies every step to a renderer state, in order. */
    private fun play(name: String, until: String? = null): Played {
        val trace = ReplayTrace.resource(name)
        val warnings = mutableListOf<String>()
        val reducer = UiTranscriptReducer(onWarning = warnings::add)
        var surfaces = A2uiSurfaces.Empty
        var state = RendererState()
        var transcript = UiTranscript()
        var steps = 0
        for (event in trace.events) {
            if (event["type"].toString() == "\"$until\"") break
            transcript = reducer.accept(AgUiJson.decodeFromJsonElement(BaseEvent.serializer(), event))
            val step = surfaces.accept(transcript.a2uiPayloads())
            if (!step.isEmpty) steps++
            state = MessageProcessor.applyAll(state, step.deletes).state
            for (batch in step.batches) {
                state = try {
                    MessageProcessor.applyAll(state, batch.messages).state
                } catch (e: Exception) {
                    fail("${batch.carrier} rejected by a2ui-core: ${e.message}")
                }
            }
            surfaces = step.next
            assertEquals(state.surfaces.keys, surfaces.surfaceIds, "the reconciler's idea of what is live")
        }
        return Played(transcript, surfaces, state, steps, warnings)
    }

    private class Played(
        val transcript: UiTranscript,
        val surfaces: A2uiSurfaces,
        val state: RendererState,
        val steps: Int,
        val warnings: List<String>,
    )

    @Test
    fun `a streamed render call through the middleware ends as one renderable surface`() {
        val played = play("advanced-hotel-comparison")
        val surface = played.state.surface("hotel-comparison") ?: fail("no surface")
        assertTrue(surface.isRenderable, "root resolves")
        assertEquals("https://a2ui.org/demos/dojo/dynamic_catalog.json", surface.catalogId)
        assertEquals(setOf("hotel-comparison"), played.surfaces.surfaceIds)

        // The run's closing `MESSAGES_SNAPSHOT` is the server's history, which never held the
        // middleware's synthetic activity -- so once it lands the activity is gone from the
        // transcript and the outer tool's result, which the snapshot does hold, owns the surface.
        // One surface either way; what changes is which carrier draws it.
        val slots = played.surfaces.accept(played.transcript.a2uiPayloads()).slots
        val owners = slots.filterValues { it is A2uiSlot.Surfaces }.keys
        assertEquals(1, owners.size, "$slots")
        assertIs<A2uiCarrier.ToolResult>(owners.single())

        // The progressive snapshots redraw the surface a handful of times, not once per event.
        assertTrue(played.steps in 3..12, "steps: ${played.steps}")
        assertEquals(emptyList(), played.warnings)
    }

    @Test
    fun `while the run streams the activity owns the surface and the tool carriers yield`() {
        val played = play("advanced-hotel-comparison", until = "MESSAGES_SNAPSHOT")
        val slots = played.surfaces.accept(played.transcript.a2uiPayloads()).slots
        val owners = slots.filterValues { it is A2uiSlot.Surfaces }.keys
        assertEquals(1, owners.size, "$slots")
        assertIs<A2uiCarrier.Activity>(owners.single())
        assertEquals(2, slots.values.count { it is A2uiSlot.Shadowed }, "$slots")
        assertTrue(played.state.surface("hotel-comparison")!!.isRenderable)
    }

    @Test
    fun `an a2ui_operations tool result with one activity ends as one surface`() {
        val played = play("fixed-flight-search")
        val surface = played.state.surface("flight-search-results") ?: fail("no surface")
        assertTrue(surface.isRenderable)
        assertEquals(setOf("flight-search-results"), played.surfaces.surfaceIds)
        assertEquals(emptyList(), played.warnings)
    }

    @Test
    fun `two runs each carrying a surface leave both live`() {
        val played = play("fixed-multiple-surfaces")
        assertEquals(setOf("flight-search-results", "hotel-search-results"), played.surfaces.surfaceIds)
        assertTrue(played.state.surfaces.values.all { it.isRenderable })
        assertEquals(emptyList(), played.warnings)
    }

    @Test
    fun `the data model rides the rewrite intact`() {
        val played = play("dynamic-team-roster")
        val surface = played.state.surface("team-roster") ?: fail("no surface")
        val items = surface.dataModel["items"]
        assertTrue(items.toString().contains("Alice Chen"), "$items")
    }

    @Test
    fun `an ACTIVITY_DELTA that patches the operations in is read like a snapshot`() {
        // Not in any recording -- the middleware only ever snapshots -- but the protocol has
        // the event and the reducer applies it, so a payload has to come out of the patched
        // content the same way it comes out of a replaced one.
        val reducer = UiTranscriptReducer(onWarning = { fail(it) })
        val snapshot = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"ACTIVITY_SNAPSHOT","messageId":"a","activityType":"a2ui-surface","content":{"status":"building"},"replace":true}""",
        )
        val delta = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"ACTIVITY_DELTA","messageId":"a","activityType":"a2ui-surface","patch":[
                {"op":"remove","path":"/status"},
                {"op":"add","path":"/a2ui_operations","value":[
                    {"version":"v0.9","createSurface":{"surfaceId":"d","catalogId":"${AguiA2ui.UPSTREAM_BASIC_CATALOG_ID}"}},
                    {"version":"v0.9","updateComponents":{"surfaceId":"d","components":[{"id":"root","component":"Text","text":"patched"}]}}
                ]}
            ]}""",
        )
        reducer.accept(AgUiJson.decodeFromJsonElement(BaseEvent.serializer(), snapshot))
        val building = reducer.accept(AgUiJson.decodeFromJsonElement(BaseEvent.serializer(), snapshot)).a2uiPayloads().single()
        assertIs<A2uiPayload.Building>(building.payload)

        val patched = reducer.accept(AgUiJson.decodeFromJsonElement(BaseEvent.serializer(), delta)).a2uiPayloads().single()
        assertEquals(A2uiCarrier.Activity("a"), patched.carrier)
        val surfaces = assertIs<A2uiPayload.Surfaces>(patched.payload)
        assertEquals(listOf("d"), surfaces.surfaceIds)
        val state = MessageProcessor.applyAll(RendererState(), surfaces.messages).state
        assertTrue(state.surface("d")!!.isRenderable)
    }

    @Test
    fun `the recorded tool result translates to v1_0 messages that a2ui-core accepts on their own`() {
        // The check the evolution guide invites: take the operations upstream actually produced,
        // rewrite them, and let a2ui-core say whether they are a surface.
        val trace = ReplayTrace.resource("advanced-hotel-comparison")
        val result = trace.events.first { it["type"].toString() == "\"TOOL_CALL_RESULT\"" }
        val content = result["content"]!!.toString().removeSurrounding("\"").replace("\\\"", "\"")
        val operations = kotlinx.serialization.json.Json.parseToJsonElement(content)
            .let { it as kotlinx.serialization.json.JsonObject }[AguiA2ui.OPERATIONS_KEY]
            as kotlinx.serialization.json.JsonArray
        val messages: List<AgentToRendererMessage> = A2uiTranslation.Default.operations(operations)
        assertIs<CreateSurfaceMessage>(messages.first())
        val state = MessageProcessor.applyAll(RendererState(), messages).state
        assertTrue(state.surface("hotel-comparison")!!.isRenderable)
    }
}

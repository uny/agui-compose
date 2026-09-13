package dev.ynagai.agui.a2ui

import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.protocol.DeleteSurfaceMessage
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiRole
import dev.ynagai.agui.model.UiTranscript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class A2uiSurfacesTest {
    private fun operations(surfaceId: String, text: String): String =
        """{"a2ui_operations":[
            {"version":"v0.9","createSurface":{"surfaceId":"$surfaceId","catalogId":"c"}},
            {"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","components":[{"id":"root","component":"Text","text":"$text"}]}}
        ]}"""

    private fun activity(messageId: String, content: String) = UiMessage(
        id = messageId,
        role = UiRole.ACTIVITY,
        parts = listOf(
            ActivityPart(
                id = "activity:$messageId",
                messageId = messageId,
                activityType = AguiA2ui.ACTIVITY_TYPE,
                content = Json.parseToJsonElement(content),
            ),
        ),
    )

    private fun toolCall(
        toolCallId: String,
        name: String = "search",
        arguments: String = "{}",
        status: ToolCallStatus = ToolCallStatus.COMPLETE,
        result: String? = null,
    ) = UiMessage(
        id = "m:$toolCallId",
        role = UiRole.ASSISTANT,
        parts = listOf(
            ToolCallPart(
                id = "tool:$toolCallId",
                toolCallId = toolCallId,
                name = name,
                arguments = arguments,
                parsedArguments = runCatching { Json.parseToJsonElement(arguments) }.getOrNull(),
                status = status,
                result = result,
            ),
        ),
    )

    private fun transcript(vararg messages: UiMessage) = UiTranscript(messages = messages.toList())

    @Test
    fun `a cumulative snapshot that repeats createSurface deletes before it replays`() {
        val first = A2uiSurfaces.Empty.accept(transcript(activity("a", operations("s", "one"))).a2uiPayloads())
        assertEquals(emptyList(), first.deletes)
        assertEquals(1, first.batches.size)
        assertIs<CreateSurfaceMessage>(first.batches.single().messages.first())
        assertEquals(setOf("s"), first.next.surfaceIds)

        val second = first.next.accept(transcript(activity("a", operations("s", "two"))).a2uiPayloads())
        assertEquals(listOf(DeleteSurfaceMessage("s")), second.deletes)
        assertEquals(1, second.batches.size)
        assertEquals(setOf("s"), second.next.surfaceIds)

        val same = second.next.accept(transcript(activity("a", operations("s", "two"))).a2uiPayloads())
        assertTrue(same.isEmpty, "an unchanged transcript is no work")
    }

    @Test
    fun `the lifecycle before the paint is a pending slot and not a surface`() {
        val step = A2uiSurfaces.Empty.accept(transcript(activity("a", """{"status":"building","progressTokens":20}""")).a2uiPayloads())
        assertTrue(step.isEmpty)
        val slot = assertIs<A2uiSlot.Pending>(step.slots.getValue(A2uiCarrier.Activity("a")))
        assertEquals(A2uiPayload.Building(progressTokens = 20), slot.payload)
    }

    @Test
    fun `an activity outranks the tool result that carries the same surface whichever came first`() {
        val ops = operations("s", "x")
        val carried = transcript(toolCall("t", result = ops), activity("a", ops)).a2uiPayloads()
        val step = A2uiSurfaces.Empty.accept(carried)
        assertEquals(A2uiSlot.Surfaces(listOf("s")), step.slots[A2uiCarrier.Activity("a")])
        assertEquals(A2uiSlot.Shadowed(A2uiCarrier.Activity("a")), step.slots[A2uiCarrier.ToolResult("t")])
        assertEquals(listOf(A2uiCarrier.Activity("a")), step.batches.map { it.carrier })
    }

    @Test
    fun `a surface that changes hands is deleted through its old owner and recreated by the new`() {
        val ops = operations("s", "x")
        val byTool = A2uiSurfaces.Empty.accept(transcript(toolCall("t", result = ops)).a2uiPayloads())
        assertEquals(listOf(A2uiCarrier.ToolResult("t")), byTool.batches.map { it.carrier })

        val byActivity = byTool.next.accept(transcript(toolCall("t", result = ops), activity("a", ops)).a2uiPayloads())
        assertEquals(listOf(DeleteSurfaceMessage("s")), byActivity.deletes)
        assertEquals(listOf(A2uiCarrier.Activity("a")), byActivity.batches.map { it.carrier })
        assertEquals(setOf("s"), byActivity.next.surfaceIds)

        // And back, when the activity goes away -- the shape of a MESSAGES_SNAPSHOT at run end.
        val back = byActivity.next.accept(transcript(toolCall("t", result = ops)).a2uiPayloads())
        assertEquals(listOf(DeleteSurfaceMessage("s")), back.deletes)
        assertEquals(listOf(A2uiCarrier.ToolResult("t")), back.batches.map { it.carrier })
    }

    @Test
    fun `a render call streams as building and paints when its arguments parse`() {
        val streaming = transcript(
            toolCall("r", name = AguiA2ui.RENDER_TOOL_NAME, arguments = """{"surfaceId":"s","comp""", status = ToolCallStatus.STREAMING_ARGUMENTS),
        )
        val first = A2uiSurfaces.Empty.accept(streaming.a2uiPayloads())
        assertEquals(A2uiSlot.Pending(A2uiPayload.Building()), first.slots[A2uiCarrier.ToolArguments("r")])

        val done = transcript(
            toolCall(
                "r",
                name = AguiA2ui.RENDER_TOOL_NAME,
                arguments = """{"surfaceId":"s","components":[{"id":"root","component":"Text","text":"hi"}]}""",
                status = ToolCallStatus.AWAITING_RESULT,
            ),
        )
        val second = first.next.accept(done.a2uiPayloads())
        assertEquals(A2uiSlot.Surfaces(listOf("s")), second.slots[A2uiCarrier.ToolArguments("r")])
        val created = assertIs<CreateSurfaceMessage>(second.batches.single().messages.single())
        assertEquals(AguiA2ui.V10_BASIC_CATALOG_ID, created.catalogId)
    }

    @Test
    fun `a rejected batch is not retried until its payload changes`() {
        val ops = operations("s", "x")
        val step = A2uiSurfaces.Empty.accept(transcript(activity("a", ops)).a2uiPayloads())
        val refused = step.next.rejected(step.batches.single())
        assertEquals(emptySet(), refused.surfaceIds)

        val same = refused.accept(transcript(activity("a", ops)).a2uiPayloads())
        assertTrue(same.isEmpty, "the same payload would throw the same way")

        val changed = refused.accept(transcript(activity("a", operations("s", "y"))).a2uiPayloads())
        assertEquals(emptyList(), changed.deletes, "nothing was live to delete")
        assertEquals(1, changed.batches.size)
    }

    @Test
    fun `an update before its create is pending as malformed rather than a batch that would throw`() {
        val content = """{"a2ui_operations":[{"version":"v0.9","updateComponents":{"surfaceId":"s","components":[]}}]}"""
        val step = A2uiSurfaces.Empty.accept(transcript(activity("a", content)).a2uiPayloads())
        assertTrue(step.isEmpty)
        val slot = assertIs<A2uiSlot.Pending>(step.slots.getValue(A2uiCarrier.Activity("a")))
        assertIs<A2uiPayload.Malformed>(slot.payload)
    }

    @Test
    fun `a later carrier that only updates a surface does not take it from the one that created it`() {
        val created = A2uiSurfaces.Empty.accept(transcript(toolCall("t1", result = operations("s", "x"))).a2uiPayloads())
        val update = """{"a2ui_operations":[{"version":"v0.9","updateDataModel":{"surfaceId":"s","path":"/","value":{}}}]}"""
        val step = created.next.accept(
            transcript(toolCall("t1", result = operations("s", "x")), toolCall("t2", result = update)).a2uiPayloads(),
        )
        assertTrue(step.isEmpty, "the surface on screen stays: $step")
        assertEquals(A2uiSlot.Surfaces(listOf("s")), step.slots[A2uiCarrier.ToolResult("t1")])
        assertEquals(A2uiSlot.Shadowed(A2uiCarrier.ToolResult("t1")), step.slots[A2uiCarrier.ToolResult("t2")])
        assertEquals(setOf("s"), step.next.surfaceIds)
    }

    @Test
    fun `a payload that deletes what it created earlier takes the surface down without a batch that would throw`() {
        val created = A2uiSurfaces.Empty.accept(transcript(activity("a", operations("s", "x"))).a2uiPayloads())
        val delete = """{"a2ui_operations":[{"version":"v0.9","deleteSurface":{"surfaceId":"s"}}]}"""
        val step = created.next.accept(transcript(activity("a", delete)).a2uiPayloads())
        assertEquals(listOf(DeleteSurfaceMessage("s")), step.deletes)
        assertEquals(emptyList(), step.batches, "the delete already happened; replaying it would throw")
        assertEquals(A2uiSlot.Surfaces(emptyList()), step.slots[A2uiCarrier.Activity("a")])
        assertEquals(emptySet(), step.next.surfaceIds)
    }

    @Test
    fun `a delete before its create within one payload is dropped from the batch`() {
        val content = """{"a2ui_operations":[
            {"version":"v0.9","deleteSurface":{"surfaceId":"s"}},
            {"version":"v0.9","createSurface":{"surfaceId":"s","catalogId":"c"}}
        ]}"""
        val step = A2uiSurfaces.Empty.accept(transcript(activity("a", content)).a2uiPayloads())
        val batch = step.batches.single()
        assertIs<CreateSurfaceMessage>(batch.messages.single())
        assertEquals(setOf("s"), step.next.surfaceIds)
    }

    @Test
    fun `an empty operations array is a surface slot that draws nothing rather than a pending one`() {
        val step = A2uiSurfaces.Empty.accept(transcript(activity("a", """{"a2ui_operations":[]}""")).a2uiPayloads())
        assertTrue(step.isEmpty)
        assertEquals(A2uiSlot.Surfaces(emptyList()), step.slots[A2uiCarrier.Activity("a")])
    }

    @Test
    fun `a tool result encoded twice is still read`() {
        val once = operations("s", "x")
        val twice = kotlinx.serialization.json.JsonPrimitive(once).toString()
        val payload = A2uiPayload.ofResult(
            (toolCall("t", result = twice).parts.single() as ToolCallPart),
        )
        assertIs<A2uiPayload.Surfaces>(payload)
    }

    @Test
    fun `an activity of another type is nobody's business here`() {
        val other = UiMessage(
            id = "o",
            role = UiRole.ACTIVITY,
            parts = listOf(ActivityPart(id = "activity:o", messageId = "o", activityType = "progress", content = JsonObject(emptyMap()))),
        )
        assertEquals(emptyList(), transcript(other).a2uiPayloads())
    }
}

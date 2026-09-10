package dev.ynagai.agui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiMessageTest {

    @Test
    fun text_is_the_text_parts_only_and_keeps_their_order() {
        val message = UiMessage(
            id = "a1",
            role = UiRole.ASSISTANT,
            parts = listOf(
                ReasoningPart(id = "r", text = "deliberating", messageId = "r1"),
                TextPart(id = "t1", text = "Let me look. ", messageId = "m1"),
                ToolCallPart(id = "c", toolCallId = "c1", name = "search"),
                TextPart(id = "t2", text = "Found three.", messageId = "m2"),
            ),
        )

        assertEquals("Let me look. Found three.", message.text)
    }

    @Test
    fun a_message_is_streaming_while_any_one_of_its_parts_is() {
        val settled = UiMessage(
            id = "a1",
            role = UiRole.ASSISTANT,
            parts = listOf(
                TextPart(id = "t1", text = "done", messageId = "m1", streaming = false),
                ToolCallPart(
                    id = "c",
                    toolCallId = "c1",
                    name = "search",
                    status = ToolCallStatus.AWAITING_RESULT,
                ),
            ),
        )
        assertFalse(settled.isStreaming)

        // A tool still assembling its arguments keeps the message live even though the text stopped.
        assertTrue(
            settled.copy(
                parts = settled.parts.dropLast(1) +
                    ToolCallPart(id = "c", toolCallId = "c1", name = "search"),
            ).isStreaming,
        )
    }

    @Test
    fun a_message_with_no_text_parts_has_empty_text() {
        val activity = UiMessage(
            id = "act-1",
            role = UiRole.ACTIVITY,
            parts = listOf(
                ActivityPart(
                    id = "a",
                    messageId = "act-1",
                    activityType = "web_search",
                    content = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
            ),
        )

        assertEquals("", activity.text)
        assertFalse(activity.isStreaming)
    }
}

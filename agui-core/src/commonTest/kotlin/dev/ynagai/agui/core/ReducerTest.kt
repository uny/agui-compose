package dev.ynagai.agui.core

import com.agui.core.types.BaseEvent
import com.agui.core.types.ReasoningEndEvent
import com.agui.core.types.ReasoningMessageContentEvent
import com.agui.core.types.ReasoningStartEvent
import com.agui.core.types.Role
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.StepFinishedEvent
import com.agui.core.types.StepStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallResultEvent
import com.agui.core.types.ToolCallStartEvent
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiRole
import dev.ynagai.agui.model.UiTranscript
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReducerTest {

    /**
     * The property this whole library exists for.
     *
     * The same run through the upstream reducer yields an `AssistantMessage` with one `content`
     * string and a `toolCalls` list, plus reasoning in a channel of its own -- from which nobody
     * can tell that the tool call happened between the two sentences.
     */
    @Test
    fun preserves_the_order_of_reasoning_text_and_tool_calls() {
        val transcript = reduce(
            RunStartedEvent(threadId = "t", runId = "r"),
            ReasoningStartEvent(messageId = "think-1"),
            ReasoningMessageContentEvent(messageId = "think-1", delta = "weighing it up"),
            ReasoningEndEvent(messageId = "think-1"),
            TextMessageStartEvent(messageId = "m1"),
            TextMessageContentEvent(messageId = "m1", delta = "Let me look."),
            TextMessageEndEvent(messageId = "m1"),
            ToolCallStartEvent(toolCallId = "c1", toolCallName = "search", parentMessageId = "m1"),
            ToolCallArgsEvent(toolCallId = "c1", delta = """{"q":"kmp"}"""),
            ToolCallEndEvent(toolCallId = "c1"),
            ToolCallResultEvent(messageId = "tm1", toolCallId = "c1", content = "3 hits"),
            TextMessageStartEvent(messageId = "m2"),
            TextMessageContentEvent(messageId = "m2", delta = "Found three."),
            TextMessageEndEvent(messageId = "m2"),
            RunFinishedEvent(threadId = "t", runId = "r"),
        )

        val message = transcript.messages.single()
        assertEquals(UiRole.ASSISTANT, message.role)
        assertContentEquals(
            listOf("reasoning:think-1", "text:m1", "tool:c1", "text:m2"),
            message.parts.map { it.id },
        )
        assertEquals("Let me look.Found three.", message.text)
    }

    @Test
    fun tracks_streaming_state_per_part() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageStartEvent(messageId = "m1"))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "Hel"))

        assertTrue(reducer.transcript.messages.single().isStreaming)
        assertEquals("Hel", reducer.transcript.messages.single().text)

        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "lo"))
        reducer.accept(TextMessageEndEvent(messageId = "m1"))

        val part = reducer.transcript.messages.single().parts.single() as TextPart
        assertEquals("Hello", part.text)
        assertEquals(false, part.streaming)
        assertEquals("m1", part.messageId)
    }

    @Test
    fun walks_a_tool_call_through_its_states_and_parses_the_arguments_once_they_are_whole() {
        val reducer = UiTranscriptReducer()
        reducer.accept(ToolCallStartEvent(toolCallId = "c1", toolCallName = "search"))
        reducer.accept(ToolCallArgsEvent(toolCallId = "c1", delta = """{"q":"""))

        val streaming = reducer.transcript.tool("c1")
        assertEquals(ToolCallStatus.STREAMING_ARGUMENTS, streaming.status)
        // Not valid JSON yet -- half an object. A renderer can still show the fragment.
        assertNull(streaming.parsedArguments)
        assertEquals("""{"q":""", streaming.arguments)

        reducer.accept(ToolCallArgsEvent(toolCallId = "c1", delta = """"kmp"}"""))
        reducer.accept(ToolCallEndEvent(toolCallId = "c1"))

        val awaiting = reducer.transcript.tool("c1")
        assertEquals(ToolCallStatus.AWAITING_RESULT, awaiting.status)
        assertEquals("""{"q":"kmp"}""", awaiting.arguments)
        assertEquals("kmp", awaiting.parsedArguments!!.stringAt("q"))

        reducer.accept(ToolCallResultEvent(messageId = "tm", toolCallId = "c1", content = "3 hits"))

        val complete = reducer.transcript.tool("c1")
        assertEquals(ToolCallStatus.COMPLETE, complete.status)
        assertEquals("3 hits", complete.result)
    }

    @Test
    fun a_user_message_ends_the_assistants_turn() {
        val transcript = reduce(
            TextMessageStartEvent(messageId = "a1"),
            TextMessageContentEvent(messageId = "a1", delta = "Hi"),
            TextMessageEndEvent(messageId = "a1"),
            TextMessageStartEvent(messageId = "u1", role = Role.USER),
            TextMessageContentEvent(messageId = "u1", delta = "Hello"),
            TextMessageEndEvent(messageId = "u1"),
            TextMessageStartEvent(messageId = "a2"),
            TextMessageContentEvent(messageId = "a2", delta = "Again"),
            TextMessageEndEvent(messageId = "a2"),
        )

        assertContentEquals(
            listOf(UiRole.ASSISTANT, UiRole.USER, UiRole.ASSISTANT),
            transcript.messages.map { it.role },
        )
        assertContentEquals(listOf("Hi", "Hello", "Again"), transcript.messages.map { it.text })
    }

    @Test
    fun a_tool_call_joins_the_message_its_parent_id_names_even_after_that_turn_closed() {
        val transcript = reduce(
            RunStartedEvent(threadId = "t", runId = "r"),
            TextMessageStartEvent(messageId = "m1"),
            TextMessageContentEvent(messageId = "m1", delta = "one moment"),
            TextMessageEndEvent(messageId = "m1"),
            RunFinishedEvent(threadId = "t", runId = "r"),
            // A second run, whose call still belongs to the first run's message.
            RunStartedEvent(threadId = "t", runId = "r2"),
            ToolCallStartEvent(toolCallId = "c1", toolCallName = "search", parentMessageId = "m1"),
        )

        val message = transcript.messages.single()
        assertContentEquals(listOf("text:m1", "tool:c1"), message.parts.map { it.id })
    }

    @Test
    fun a_run_that_ends_mid_stream_stops_every_caret_it_left_blinking() {
        val transcript = reduce(
            RunStartedEvent(threadId = "t", runId = "r"),
            TextMessageStartEvent(messageId = "m1"),
            TextMessageContentEvent(messageId = "m1", delta = "half a th"),
            RunErrorEvent(message = "upstream closed the socket", code = "EPIPE"),
        )

        assertEquals(false, transcript.messages.single().isStreaming)
        assertEquals(RunState.Failed("upstream closed the socket", "EPIPE"), transcript.run)
    }

    @Test
    fun tracks_the_run_and_its_open_steps() {
        val reducer = UiTranscriptReducer()
        assertEquals(RunState.Idle, reducer.transcript.run)

        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        assertEquals(RunState.Running("t", "r"), reducer.transcript.run)

        reducer.accept(StepStartedEvent(stepName = "retrieve"))
        reducer.accept(StepStartedEvent(stepName = "rank"))
        assertContentEquals(listOf("retrieve", "rank"), reducer.transcript.steps)

        reducer.accept(StepFinishedEvent(stepName = "retrieve"))
        assertContentEquals(listOf("rank"), reducer.transcript.steps)

        reducer.accept(RunFinishedEvent(threadId = "t", runId = "r"))
        assertEquals(RunState.Finished("t", "r"), reducer.transcript.run)
    }

    /**
     * The claim [MessageBuilder] caching makes, checked rather than asserted in a comment: a
     * message nobody touched comes back as the same instance, so a Compose renderer does not
     * redraw the transcript once per event.
     */
    @Test
    fun leaves_untouched_messages_as_the_same_instance() {
        val reducer = UiTranscriptReducer()
        reducer.accept(TextMessageStartEvent(messageId = "u1", role = Role.USER))
        reducer.accept(TextMessageContentEvent(messageId = "u1", delta = "Hello"))
        reducer.accept(TextMessageEndEvent(messageId = "u1"))
        val user = reducer.transcript.messages.single()

        reducer.accept(TextMessageStartEvent(messageId = "a1"))
        reducer.accept(TextMessageContentEvent(messageId = "a1", delta = "Hi"))

        val after = reducer.transcript
        assertEquals(2, after.messages.size)
        assertSame(user, after.messages[0])
    }

    private fun UiTranscript.tool(id: String): ToolCallPart =
        messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single { it.toolCallId == id }
}

internal fun reduce(vararg events: BaseEvent): UiTranscript {
    val reducer = UiTranscriptReducer()
    events.forEach(reducer::accept)
    return reducer.transcript
}

private fun JsonElement.stringAt(key: String): String = jsonObject.getValue(key).jsonPrimitive.content

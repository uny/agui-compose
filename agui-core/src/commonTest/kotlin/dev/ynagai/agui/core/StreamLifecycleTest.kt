package dev.ynagai.agui.core

import com.agui.core.types.ActivitySnapshotEvent
import com.agui.core.types.ReasoningEncryptedValueEvent
import com.agui.core.types.ReasoningEndEvent
import com.agui.core.types.ReasoningMessageContentEvent
import com.agui.core.types.ReasoningMessageEndEvent
import com.agui.core.types.ReasoningMessageStartEvent
import com.agui.core.types.ReasoningStartEvent
import com.agui.core.types.Role
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.StepStartedEvent
import com.agui.core.types.TextMessageChunkEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.ThinkingEndEvent
import com.agui.core.types.ThinkingStartEvent
import com.agui.core.types.ThinkingTextMessageContentEvent
import com.agui.core.types.ThinkingTextMessageStartEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallChunkEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallStartEvent
import dev.ynagai.agui.model.ReasoningPart
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ways a stream can be left open, and what closes it.
 *
 * Every test here failed before the fix it names. They are grouped because they share one
 * question -- "who settles this part, and when?" -- which the reducer answers in four places:
 * `endText`, `endToolCall`, `endReasoningStream` and `finalizeTurn`.
 */
class StreamLifecycleTest {

    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun reducer(warnings: MutableList<String>) = UiTranscriptReducer { warnings += it }

    // ---- finalizeTurn reaches parts that detachTurn orphaned ---------------------------------

    @Test
    fun a_run_ending_under_an_activity_still_stops_the_caret_beneath_it() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageStartEvent(messageId = "m1"))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "Search"))
        // Detaches the turn: the activity is not an interruption, so the text keeps streaming...
        reducer.accept(ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":0}""")))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "ing"))
        assertTrue(
            reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<TextPart>().single().streaming,
            "the caret went out while the words were still arriving",
        )

        // ...but the run ending has to settle it, even though the turn is no longer the open one.
        reducer.accept(RunFinishedEvent(threadId = "t", runId = "r"))
        val text = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<TextPart>().single()
        assertEquals("Searching", text.text)
        assertFalse(text.streaming, "a caret left blinking under text that will never grow")
    }

    @Test
    fun a_run_error_fails_a_tool_call_orphaned_by_a_user_message() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ToolCallStartEvent(toolCallId = "c1", toolCallName = "search"))
        reducer.accept(ToolCallArgsEvent(toolCallId = "c1", delta = """{"q":"""))
        // A user message mid-stream detaches the assistant turn holding the unfinished call.
        reducer.accept(TextMessageStartEvent(messageId = "u1", role = Role.USER))
        reducer.accept(RunErrorEvent(message = "connection lost"))

        val call = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.FAILED, call.status)
        assertEquals("connection lost", call.error)
    }

    // ---- chunk streams end implicitly, and something has to do it ---------------------------

    @Test
    fun a_tool_call_delivered_entirely_by_chunks_completes_rather_than_failing() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ToolCallChunkEvent(toolCallId = "c1", toolCallName = "search", delta = """{"q":"""))
        reducer.accept(ToolCallChunkEvent(toolCallId = "c1", delta = """"kt"}"""))
        reducer.accept(RunFinishedEvent(threadId = "t", runId = "r"))

        val call = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single()
        assertEquals(
            ToolCallStatus.AWAITING_RESULT,
            call.status,
            "a frontend tool the client must still execute was reported as failed",
        )
        assertNull(call.error)
        assertEquals(obj("""{"q":"kt"}"""), call.parsedArguments)
    }

    @Test
    fun a_chunk_for_a_new_id_closes_the_stream_the_previous_one_left_open() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageChunkEvent(messageId = "m1", delta = "hello"))
        reducer.accept(TextMessageChunkEvent(messageId = "m2", delta = "world"))

        val parts = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<TextPart>()
        assertEquals(listOf("hello", "world"), parts.map { it.text })
        assertFalse(parts[0].streaming, "two carets in one bubble: m1 was finished by m2 arriving")
        assertTrue(parts[1].streaming)
    }

    @Test
    fun a_chunk_with_no_id_does_not_reopen_a_stream_that_already_ended() {
        val warnings = mutableListOf<String>()
        val reducer = reducer(warnings)
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageStartEvent(messageId = "u1", role = Role.USER))
        reducer.accept(TextMessageContentEvent(messageId = "u1", delta = "Hello"))
        reducer.accept(TextMessageEndEvent(messageId = "u1"))
        reducer.accept(TextMessageChunkEvent(messageId = null, role = Role.ASSISTANT, delta = "Hi there"))

        assertEquals(listOf(UiRole.USER), reducer.transcript.messages.map { it.role })
        assertEquals(
            "Hello",
            reducer.transcript.messages.single().text,
            "the assistant's prose was appended to the user's own message",
        )
        assertTrue(warnings.any { it.startsWith("TEXT_MESSAGE_CHUNK with no messageId") }, warnings.toString())
    }

    @Test
    fun a_tool_chunk_with_no_id_does_not_reopen_a_call_that_already_ended() {
        val warnings = mutableListOf<String>()
        val reducer = reducer(warnings)
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ToolCallStartEvent(toolCallId = "c1", toolCallName = "search"))
        reducer.accept(ToolCallArgsEvent(toolCallId = "c1", delta = """{"q":"kt"}"""))
        reducer.accept(ToolCallEndEvent(toolCallId = "c1"))
        reducer.accept(ToolCallChunkEvent(toolCallId = null, delta = """{"q":"""))

        val call = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single()
        assertEquals("""{"q":"kt"}""", call.arguments, "a settled call had fresh argument text appended")
        assertEquals(obj("""{"q":"kt"}"""), call.parsedArguments)
        assertTrue(warnings.any { it.startsWith("TOOL_CALL_CHUNK with no toolCallId") }, warnings.toString())
    }

    // ---- REASONING_END ends the stream, not one part ----------------------------------------

    @Test
    fun reasoning_end_settles_the_messages_the_stream_carried() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ReasoningStartEvent(messageId = "r-stream"))
        reducer.accept(ReasoningMessageStartEvent(messageId = "rm1"))
        reducer.accept(ReasoningMessageContentEvent(messageId = "rm1", delta = "weighing it up"))
        reducer.accept(ReasoningMessageEndEvent(messageId = "rm1"))
        reducer.accept(ReasoningEndEvent(messageId = "r-stream"))

        val parts = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>()
        // The stream id never carried text, so it never became a part of its own.
        assertEquals(listOf("reasoning:rm1"), parts.map { it.id })
        assertEquals("weighing it up", parts.single().text)
        assertFalse(parts.single().streaming, "the caret outlived the deliberation it belonged to")
    }

    @Test
    fun reasoning_content_under_the_streams_own_id_still_opens_a_part() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ReasoningStartEvent(messageId = "r1"))
        reducer.accept(ReasoningMessageContentEvent(messageId = "r1", delta = "hmm"))
        reducer.accept(ReasoningEndEvent(messageId = "r1"))

        val part = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>().single()
        assertEquals("hmm", part.text)
        assertFalse(part.streaming)
    }

    @Test
    fun a_second_thinking_block_in_one_run_is_its_own_part_and_streams() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ThinkingStartEvent(title = "First"))
        reducer.accept(ThinkingTextMessageStartEvent())
        reducer.accept(ThinkingTextMessageContentEvent(delta = "aaa"))
        reducer.accept(ThinkingEndEvent())
        reducer.accept(ThinkingStartEvent(title = "Second"))
        reducer.accept(ThinkingTextMessageStartEvent())
        reducer.accept(ThinkingTextMessageContentEvent(delta = "bbb"))

        val parts = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>()
        assertEquals(listOf("aaa", "bbb"), parts.map { it.text }, "two blocks folded into one part")
        assertEquals(listOf("First", "Second"), parts.map { it.title })
        assertFalse(parts[0].streaming)
        assertTrue(parts[1].streaming, "the agent was still deliberating into a part declared finished")
    }

    // ---- REASONING_ENCRYPTED_VALUE is addressed, not guessed ---------------------------------

    @Test
    fun an_encrypted_value_lands_on_the_message_its_entity_id_names_after_the_stream_ended() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ReasoningStartEvent(messageId = "rm1"))
        reducer.accept(ReasoningMessageContentEvent(messageId = "rm1", delta = "think"))
        reducer.accept(ReasoningEndEvent(messageId = "rm1"))
        // Its ordinary position on the wire: after the stream it belongs to has closed.
        reducer.accept(
            ReasoningEncryptedValueEvent(subtype = "message", entityId = "rm1", encryptedValue = "blob"),
        )

        val part = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>().single()
        assertEquals(listOf("blob"), part.encryptedValues.map { it.value })
    }

    @Test
    fun an_encrypted_value_for_a_tool_call_lands_on_that_call() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ToolCallStartEvent(toolCallId = "c1", toolCallName = "search"))
        reducer.accept(ToolCallEndEvent(toolCallId = "c1"))
        reducer.accept(
            ReasoningEncryptedValueEvent(subtype = "tool-call", entityId = "c1", encryptedValue = "blob"),
        )

        val call = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single()
        assertEquals("blob", call.encryptedValue)
    }

    @Test
    fun an_encrypted_value_naming_nothing_warns_rather_than_landing_on_the_wrong_part() {
        val warnings = mutableListOf<String>()
        val reducer = reducer(warnings)
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ReasoningStartEvent(messageId = "rm1"))
        reducer.accept(ReasoningMessageContentEvent(messageId = "rm1", delta = "think"))
        reducer.accept(
            ReasoningEncryptedValueEvent(subtype = "message", entityId = "other", encryptedValue = "blob"),
        )

        val part = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>().single()
        assertContentEquals(emptyList(), part.encryptedValues)
        assertTrue(warnings.any { it.contains("unknown message other") }, warnings.toString())
    }

    // ---- a run is a scope --------------------------------------------------------------------

    @Test
    fun a_new_run_does_not_inherit_the_previous_ones_open_steps() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r1"))
        reducer.accept(StepStartedEvent(stepName = "retrieve"))
        reducer.accept(RunErrorEvent(message = "boom"))
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r2"))

        assertContentEquals(emptyList(), reducer.transcript.steps)
    }

    @Test
    fun a_producer_that_reuses_message_ids_per_run_gets_a_new_message() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r1"))
        reducer.accept(TextMessageStartEvent(messageId = "msg_1"))
        reducer.accept(TextMessageContentEvent(messageId = "msg_1", delta = "one"))
        reducer.accept(TextMessageEndEvent(messageId = "msg_1"))
        reducer.accept(RunFinishedEvent(threadId = "t", runId = "r1"))
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r2"))
        reducer.accept(TextMessageStartEvent(messageId = "msg_1"))
        reducer.accept(TextMessageContentEvent(messageId = "msg_1", delta = "two"))

        assertEquals(listOf("one", "two"), reducer.transcript.messages.map { it.text })
        assertTrue(
            reducer.transcript.messages.last().isStreaming,
            "the second run streamed into the first run's settled message",
        )
    }

    // ---- roles the model has, and the one it does not ---------------------------------------

    @Test
    fun a_streamed_activity_role_becomes_an_activity_message_not_an_assistant_one() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageStartEvent(messageId = "a1", role = Role.ACTIVITY))
        reducer.accept(TextMessageContentEvent(messageId = "a1", delta = "searching"))

        assertEquals(listOf(UiRole.ACTIVITY), reducer.transcript.messages.map { it.role })
    }

    @Test
    fun a_tool_role_text_message_is_skipped_rather_than_rendered_as_the_agent() {
        val warnings = mutableListOf<String>()
        val reducer = reducer(warnings)
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageStartEvent(messageId = "t1", role = Role.TOOL))
        reducer.accept(TextMessageContentEvent(messageId = "t1", delta = "3 hits"))

        assertContentEquals(emptyList(), reducer.transcript.messages.map { it.role })
        assertTrue(warnings.any { it.contains("has no place in the model") }, warnings.toString())
    }
}

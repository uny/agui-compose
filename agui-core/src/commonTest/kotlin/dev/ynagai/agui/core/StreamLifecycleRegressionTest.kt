package dev.ynagai.agui.core

import com.agui.core.types.AssistantMessage
import com.agui.core.types.FunctionCall
import com.agui.core.types.MessagesSnapshotEvent
import com.agui.core.types.ReasoningEndEvent
import com.agui.core.types.ReasoningMessageContentEvent
import com.agui.core.types.ReasoningMessageEndEvent
import com.agui.core.types.ReasoningMessageStartEvent
import com.agui.core.types.ReasoningStartEvent
import com.agui.core.types.Role
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageChunkEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.ThinkingEndEvent
import com.agui.core.types.ThinkingStartEvent
import com.agui.core.types.ThinkingTextMessageContentEvent
import com.agui.core.types.ToolCall
import com.agui.core.types.ToolCallChunkEvent
import com.agui.core.types.ToolCallResultEvent
import com.agui.core.types.ToolCallStartEvent
import dev.ynagai.agui.model.ReasoningPart
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiPart
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The second pass: defects the fixes for the first pass introduced, plus what that pass missed.
 *
 * Every test here failed on the commit that fixed the original eight. Kept as their own class
 * because "the repair broke a neighbouring case" is a distinct thing to regress against.
 */
class StreamLifecycleRegressionTest {

    // ---- an implicit end must not undo a result that already landed --------------------------

    @Test
    fun a_chunked_call_whose_result_arrived_stays_complete_when_the_run_ends() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ToolCallChunkEvent(toolCallId = "c1", toolCallName = "search", delta = "{}"))
        reducer.accept(ToolCallResultEvent(messageId = "tm1", toolCallId = "c1", content = "3 hits"))
        reducer.accept(RunFinishedEvent(threadId = "t", runId = "r"))

        val call = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.COMPLETE, call.status, "the run boundary undid a landed result")
        assertEquals("3 hits", call.result)
    }

    @Test
    fun a_failed_run_reports_a_half_delivered_chunked_call_as_failed() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ToolCallChunkEvent(toolCallId = "c1", toolCallName = "search", delta = """{"q":"""))
        reducer.accept(RunErrorEvent(message = "socket closed"))

        val call = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single()
        assertEquals(
            ToolCallStatus.FAILED,
            call.status,
            "truncated arguments were closed as if they were whole, hiding the failure",
        )
        assertEquals("socket closed", call.error)
    }

    // ---- a declined stream stays declined ----------------------------------------------------

    @Test
    fun a_skipped_tool_role_chunk_stream_does_not_reappear_as_assistant_text() {
        val warnings = mutableListOf<String>()
        val reducer = UiTranscriptReducer { warnings += it }
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageChunkEvent(messageId = "tm1", role = Role.TOOL, delta = "tool "))
        reducer.accept(TextMessageChunkEvent(messageId = null, delta = "output"))

        assertContentEquals(emptyList(), reducer.transcript.messages.map { it.role })
        assertTrue(warnings.any { it.contains("has no place in the model") }, warnings.toString())
    }

    // ---- a snapshot discards the streams it replaced -----------------------------------------

    @Test
    fun a_snapshot_does_not_leave_an_implicit_stream_to_resurrect() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageChunkEvent(messageId = "m1", role = Role.USER, delta = "before"))
        reducer.accept(MessagesSnapshotEvent(messages = emptyList()))
        reducer.accept(TextMessageChunkEvent(messageId = null, delta = "after"))

        assertContentEquals(
            emptyList(),
            reducer.transcript.messages.map { it.id },
            "a message the snapshot discarded came back under a different role",
        )
    }

    // ---- REASONING_MESSAGE_END settles the message it names ----------------------------------

    @Test
    fun a_reasoning_message_ends_without_waiting_for_the_phase() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ReasoningStartEvent(messageId = "phase"))
        reducer.accept(ReasoningMessageStartEvent(messageId = "rm1"))
        reducer.accept(ReasoningMessageContentEvent(messageId = "rm1", delta = "done"))
        reducer.accept(ReasoningMessageEndEvent(messageId = "rm1"))

        val part = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>().single()
        assertFalse(part.streaming, "the message was finished but still claimed to be arriving")
        assertFalse(reducer.transcript.messages.single().isStreaming)

        // The phase is still open, so a second message under it still opens and streams.
        reducer.accept(ReasoningMessageStartEvent(messageId = "rm2"))
        reducer.accept(ReasoningMessageContentEvent(messageId = "rm2", delta = "more"))
        val parts = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>()
        assertEquals(listOf("done", "more"), parts.map { it.text })
        assertTrue(parts.last().streaming)

        reducer.accept(ReasoningEndEvent(messageId = "phase"))
        assertTrue(
            reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>().none { it.streaming },
        )
    }

    // ---- a restored assistant message owns its id even with no prose -------------------------

    @Test
    fun a_call_finds_a_snapshot_assistant_message_that_carried_no_text() {
        val reducer = UiTranscriptReducer()
        reducer.accept(
            MessagesSnapshotEvent(
                messages = listOf(
                    AssistantMessage(
                        id = "a1",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(id = "c1", function = FunctionCall(name = "search", arguments = "{}")),
                        ),
                    ),
                ),
            ),
        )
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ToolCallStartEvent(toolCallId = "c2", toolCallName = "lookup", parentMessageId = "a1"))

        assertEquals(1, reducer.transcript.messages.size, "the call opened a bubble of its own")
        assertContentEquals(
            listOf("tool:c1", "tool:c2"),
            reducer.transcript.messages.single().parts.map { it.id },
        )
    }

    // ---- two thoughts either side of an answer are two parts ---------------------------------

    @Test
    fun thinking_blocks_either_side_of_an_answer_keep_their_places() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ThinkingStartEvent(title = "First"))
        reducer.accept(ThinkingTextMessageContentEvent(delta = "before"))
        reducer.accept(ThinkingEndEvent())
        reducer.accept(TextMessageStartEvent(messageId = "m1"))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "answer"))
        reducer.accept(TextMessageEndEvent(messageId = "m1"))
        reducer.accept(ThinkingStartEvent(title = "Second"))
        reducer.accept(ThinkingTextMessageContentEvent(delta = "after"))
        reducer.accept(ThinkingEndEvent())

        val shape = reducer.transcript.messages.single().parts.map { part: UiPart ->
            when (part) {
                is ReasoningPart -> "reasoning:${part.title}:${part.text}"
                is TextPart -> "text:${part.text}"
                else -> "other"
            }
        }
        assertContentEquals(
            listOf("reasoning:First:before", "text:answer", "reasoning:Second:after"),
            shape,
            "the second thought was folded into the first, behind the answer between them",
        )
        assertTrue(
            reducer.transcript.messages.single().parts.filterIsInstance<ReasoningPart>().none { it.streaming },
        )
    }

    @Test
    fun a_thinking_block_left_open_when_the_run_ends_still_settles() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(ThinkingStartEvent(title = "Planning"))
        reducer.accept(ThinkingTextMessageContentEvent(delta = "hmm"))
        reducer.accept(RunFinishedEvent(threadId = "t", runId = "r"))

        val part = reducer.transcript.messages.flatMap { it.parts }.filterIsInstance<ReasoningPart>().single()
        assertEquals("hmm", part.text)
        assertFalse(part.streaming)
        assertNull(reducer.transcript.messages.single().parts.filterIsInstance<ToolCallPart>().firstOrNull())
    }
}

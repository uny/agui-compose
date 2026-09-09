package dev.ynagai.agui.core

import com.agui.core.types.ActivityMessage
import com.agui.core.types.AssistantMessage
import com.agui.core.types.BinaryInputContent
import com.agui.core.types.FunctionCall
import com.agui.core.types.MessagesSnapshotEvent
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.StateDeltaEvent
import com.agui.core.types.StateSnapshotEvent
import com.agui.core.types.SystemMessage
import com.agui.core.types.TextInputContent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.ThinkingEndEvent
import com.agui.core.types.ThinkingStartEvent
import com.agui.core.types.ThinkingTextMessageContentEvent
import com.agui.core.types.ThinkingTextMessageStartEvent
import com.agui.core.types.ToolCall
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallResultEvent
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolMessage
import com.agui.core.types.UserMessage
import dev.ynagai.agui.model.FilePart
import dev.ynagai.agui.model.ReasoningPart
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiRole
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateAndSnapshotTest {

    @Test
    fun keeps_shared_state_by_snapshot_and_by_patch() {
        val transcript = reduce(
            StateSnapshotEvent(snapshot = obj("""{"cart":{"items":0}}""")),
            StateDeltaEvent(delta = arr("""[{"op":"replace","path":"/cart/items","value":2}]""")),
        )

        assertEquals(obj("""{"cart":{"items":2}}"""), transcript.sharedState)
    }

    @Test
    fun a_state_patch_that_does_not_apply_warns_and_leaves_the_state_alone() {
        val warnings = mutableListOf<String>()
        val reducer = UiTranscriptReducer(warnings::add)

        reducer.accept(StateSnapshotEvent(snapshot = obj("""{"cart":{"items":0}}""")))
        reducer.accept(StateDeltaEvent(delta = arr("""[{"op":"test","path":"/cart/items","value":9}]""")))

        assertEquals(1, warnings.size)
        assertEquals(obj("""{"cart":{"items":0}}"""), reducer.transcript.sharedState)
    }

    @Test
    fun rebuilds_the_transcript_from_a_messages_snapshot_and_reattaches_tool_results() {
        val transcript = reduce(
            MessagesSnapshotEvent(
                messages = listOf(
                    SystemMessage(id = "s1", content = "be brief"),
                    UserMessage(id = "u1", content = "search kmp"),
                    AssistantMessage(
                        id = "a1",
                        content = "Looking.",
                        toolCalls = listOf(
                            ToolCall(id = "c1", function = FunctionCall("search", """{"q":"kmp"}""")),
                        ),
                    ),
                    ToolMessage(id = "t1", content = "3 hits", toolCallId = "c1"),
                    ActivityMessage(id = "act-1", activityType = "web_search", activityContent = obj("""{"found":3}""")),
                ),
            ),
        )

        assertContentEquals(
            listOf(UiRole.SYSTEM, UiRole.USER, UiRole.ASSISTANT, UiRole.ACTIVITY),
            transcript.messages.map { it.role },
        )

        val assistant = transcript.messages[2]
        assertContentEquals(listOf("text:a1", "tool:c1"), assistant.parts.map { it.id })

        val call = assistant.parts[1] as ToolCallPart
        assertEquals(ToolCallStatus.COMPLETE, call.status)
        assertEquals("3 hits", call.result)
    }

    @Test
    fun a_failed_tool_result_in_a_snapshot_lands_as_a_failure() {
        val transcript = reduce(
            MessagesSnapshotEvent(
                messages = listOf(
                    AssistantMessage(
                        id = "a1",
                        toolCalls = listOf(ToolCall(id = "c1", function = FunctionCall("search", "{}"))),
                    ),
                    ToolMessage(id = "t1", content = "", toolCallId = "c1", error = "timed out"),
                ),
            ),
        )

        val call = transcript.messages.single().parts.single() as ToolCallPart
        assertEquals(ToolCallStatus.FAILED, call.status)
        assertEquals("timed out", call.error)
    }

    @Test
    fun a_snapshot_replaces_what_was_there_rather_than_adding_to_it() {
        val reducer = UiTranscriptReducer()
        reducer.accept(TextMessageStartEvent(messageId = "m1"))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "streamed"))

        reducer.accept(MessagesSnapshotEvent(messages = listOf(UserMessage(id = "u1", content = "restored"))))

        assertContentEquals(listOf("restored"), reducer.transcript.messages.map { it.text })
    }

    /**
     * A restored snapshot can still be amended: the rebuilt parts go back into the indexes, so a
     * result arriving after the snapshot finds the call it names.
     */
    @Test
    fun a_result_arriving_after_a_snapshot_still_finds_its_call() {
        val reducer = UiTranscriptReducer()
        reducer.accept(
            MessagesSnapshotEvent(
                messages = listOf(
                    AssistantMessage(
                        id = "a1",
                        toolCalls = listOf(ToolCall(id = "c1", function = FunctionCall("search", "{}"))),
                    ),
                ),
            ),
        )
        reducer.accept(ToolCallResultEvent(messageId = "t1", toolCallId = "c1", content = "3 hits"))

        val call = reducer.transcript.messages.single().parts.single() as ToolCallPart
        assertEquals(ToolCallStatus.COMPLETE, call.status)
        assertEquals("3 hits", call.result)
    }

    @Test
    fun a_multimodal_user_message_becomes_text_and_file_parts_in_order() {
        val transcript = reduce(
            MessagesSnapshotEvent(
                messages = listOf(
                    UserMessage.multimodal(
                        id = "u1",
                        parts = listOf(
                            TextInputContent("what is this?"),
                            BinaryInputContent(mimeType = "image/png", url = "https://example.test/a.png"),
                        ),
                    ),
                ),
            ),
        )

        val parts = transcript.messages.single().parts
        assertEquals("what is this?", (parts[0] as TextPart).text)
        assertEquals("image/png", (parts[1] as FilePart).mimeType)
    }

    /**
     * The deprecated `THINKING_*` spelling lands on the same [ReasoningPart] as `REASONING_*`, so a
     * consumer renders one thing regardless of which spelling its server emits.
     */
    @Test
    fun folds_the_deprecated_thinking_events_onto_a_reasoning_part() {
        @Suppress("DEPRECATION")
        val transcript = reduce(
            ThinkingStartEvent(title = "Planning"),
            ThinkingTextMessageStartEvent(),
            ThinkingTextMessageContentEvent(delta = "first, search"),
            ThinkingEndEvent(),
        )

        val part = transcript.messages.single().parts.single() as ReasoningPart
        assertEquals("Planning", part.title)
        assertEquals("first, search", part.text)
        assertTrue(!part.streaming)
    }
}

/**
 * The deprecated `THINKING_*` events across a run boundary.
 *
 * They carry no message id of their own, so the reducer synthesises one. A synthetic id that
 * outlived its run would make a second run's deliberation append to the first run's part, in a
 * message that had already settled.
 */
class ThinkingAcrossRunsTest {

    @Test
    fun a_second_runs_thinking_does_not_append_to_the_first_runs() {
        @Suppress("DEPRECATION")
        val transcript = reduce(
            RunStartedEvent(threadId = "t", runId = "r1"),
            ThinkingStartEvent(title = "Planning"),
            ThinkingTextMessageContentEvent(delta = "first, search"),
            ThinkingEndEvent(),
            RunFinishedEvent(threadId = "t", runId = "r1"),
            RunStartedEvent(threadId = "t", runId = "r2"),
            ThinkingStartEvent(title = "Replanning"),
            ThinkingTextMessageContentEvent(delta = "now, summarise"),
            ThinkingEndEvent(),
            RunFinishedEvent(threadId = "t", runId = "r2"),
        )

        val reasoning = transcript.messages.map { it.parts.single() as ReasoningPart }
        assertContentEquals(listOf("first, search", "now, summarise"), reasoning.map { it.text })
        assertContentEquals(listOf("Planning", "Replanning"), reasoning.map { it.title })
    }
}

/**
 * A run that dies while a tool call is still assembling its arguments.
 *
 * Left alone, the call would sit in [ToolCallStatus.STREAMING_ARGUMENTS] for ever and the message
 * would report itself as streaming long after the socket closed.
 */
class InterruptedToolCallTest {

    @Test
    fun a_run_error_fails_a_tool_call_that_never_finished_its_arguments() {
        val transcript = reduce(
            RunStartedEvent(threadId = "t", runId = "r"),
            ToolCallStartEvent(toolCallId = "c1", toolCallName = "search"),
            ToolCallArgsEvent(toolCallId = "c1", delta = """{"q":"""),
            RunErrorEvent(message = "upstream closed the socket"),
        )

        val call = transcript.messages.single().parts.single() as ToolCallPart
        assertEquals(ToolCallStatus.FAILED, call.status)
        assertEquals("upstream closed the socket", call.error)
        assertEquals(false, transcript.messages.single().isStreaming)
    }
}

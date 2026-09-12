package dev.ynagai.agui.core

import com.agui.core.types.AssistantMessage
import com.agui.core.types.BinaryInputContent
import com.agui.core.types.MessagesSnapshotEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextInputContent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.UserMessage
import dev.ynagai.agui.model.FilePart
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.UiRole
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [UiTranscriptReducer.appendUserMessage] -- the one entry point that is not an event.
 *
 * What is measured here is that a local turn draws the same way a turn restored from a
 * `MESSAGES_SNAPSHOT` does, and that appending one costs the transcript nothing it already had:
 * the alternative shape, synthesising a snapshot per send, would rebuild every earlier message
 * lossily, and `keeps_what_is_already_on_screen` is the test that would fail if this were ever
 * implemented that way.
 */
class LocalUserMessageTest {

    @Test
    fun draws_a_plain_message_as_one_text_part() {
        val reducer = UiTranscriptReducer()

        val transcript = reducer.appendUserMessage(UserMessage(id = "u1", content = "Hello"))

        val message = transcript.messages.single()
        assertEquals(UiRole.USER, message.role)
        assertEquals("u1", message.id)
        val part = message.parts.single() as TextPart
        assertEquals("Hello", part.text)
        assertEquals("text:u1", part.id)
        assertFalse(part.streaming)
    }

    @Test
    fun draws_multimodal_content_in_the_order_it_was_given() {
        val reducer = UiTranscriptReducer()

        val transcript = reducer.appendUserMessage(
            UserMessage(
                id = "u1",
                content = "ignored when contentParts is present",
                contentParts = listOf(
                    TextInputContent(text = "what is this?"),
                    BinaryInputContent(mimeType = "image/png", url = "https://example.test/a.png"),
                ),
            ),
        )

        val parts = transcript.messages.single().parts
        assertEquals("what is this?", (parts[0] as TextPart).text)
        assertEquals("image/png", (parts[1] as FilePart).mimeType)
        assertContentEquals(listOf("text:u1#0", "file:u1#1"), parts.map { it.id })
    }

    /**
     * The point of appending rather than replacing: the answer already on screen keeps the ordering
     * a rebuild from a snapshot would lose, and its parts are not even rebuilt.
     */
    @Test
    fun keeps_what_is_already_on_screen() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r1"))
        reducer.accept(TextMessageStartEvent(messageId = "a1"))
        reducer.accept(TextMessageContentEvent(messageId = "a1", delta = "Hi"))
        val before = reducer.accept(RunFinishedEvent(threadId = "t", runId = "r1")).messages.single()

        val after = reducer.appendUserMessage(UserMessage(id = "u1", content = "Hello"))

        assertEquals(before, after.messages.first())
        assertContentEquals(listOf(UiRole.ASSISTANT, UiRole.USER), after.messages.map { it.role })
    }

    /**
     * Appending detaches the assistant's turn without finishing it: the words still arriving keep
     * arriving, and the next ones open a bubble of their own on the far side of the user's line.
     */
    @Test
    fun a_message_sent_mid_stream_does_not_stop_the_text_still_streaming() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r1"))
        reducer.accept(TextMessageStartEvent(messageId = "a1"))
        reducer.accept(TextMessageContentEvent(messageId = "a1", delta = "still talking"))

        reducer.appendUserMessage(UserMessage(id = "u1", content = "interrupting"))
        reducer.accept(TextMessageContentEvent(messageId = "a1", delta = " here"))
        reducer.accept(TextMessageStartEvent(messageId = "a2"))
        val transcript = reducer.accept(TextMessageContentEvent(messageId = "a2", delta = "and again"))

        assertContentEquals(
            listOf(UiRole.ASSISTANT, UiRole.USER, UiRole.ASSISTANT),
            transcript.messages.map { it.role },
        )
        assertContentEquals(
            listOf("still talking here", "interrupting", "and again"),
            transcript.messages.map { it.text },
        )
        assertTrue((transcript.messages[0].parts.single() as TextPart).streaming)
    }

    /** The run ending settles the part the append detached, the same as any other orphan. */
    @Test
    fun the_run_ending_settles_the_turn_the_append_detached() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r1"))
        reducer.accept(TextMessageStartEvent(messageId = "a1"))
        reducer.accept(TextMessageContentEvent(messageId = "a1", delta = "half a sentence"))
        reducer.appendUserMessage(UserMessage(id = "u1", content = "interrupting"))

        val transcript = reducer.accept(RunFinishedEvent(threadId = "t", runId = "r1"))

        assertFalse((transcript.messages[0].parts.single() as TextPart).streaming)
    }

    /** Local ids go through the same mint as ids off the wire, so a reused one is disambiguated. */
    @Test
    fun a_reused_id_becomes_a_second_message_rather_than_a_corrupted_first() {
        val reducer = UiTranscriptReducer()

        reducer.appendUserMessage(UserMessage(id = "u1", content = "first"))
        val transcript = reducer.appendUserMessage(UserMessage(id = "u1", content = "second"))

        assertContentEquals(listOf("u1", "u1#2"), transcript.messages.map { it.id })
        assertContentEquals(listOf("first", "second"), transcript.messages.map { it.text })
    }

    /**
     * A server that answers with its own copy of the thread replaces the local turn rather than
     * being appended beside it -- the snapshot's whole job.
     *
     * The server's copy says something the local one does not, because a snapshot that merely
     * kept the local message would be indistinguishable from one that replaced it with an
     * identical copy. A server that normalises a turn -- trims it, redacts it, resolves an
     * uploaded file's id -- is the case that would otherwise go on drawing the client's own
     * pre-normalisation text forever.
     */
    @Test
    fun a_later_snapshot_replaces_the_appended_message_rather_than_doubling_it() {
        val reducer = UiTranscriptReducer()
        reducer.appendUserMessage(UserMessage(id = "u1", content = "Hello "))

        val transcript = reducer.accept(
            MessagesSnapshotEvent(
                messages = listOf(
                    UserMessage(id = "u1", content = "Hello"),
                    AssistantMessage(id = "a1", content = "Hi"),
                ),
            ),
        )

        assertContentEquals(listOf("u1", "a1"), transcript.messages.map { it.id })
        assertContentEquals(listOf("Hello", "Hi"), transcript.messages.map { it.text })
    }
}

package dev.ynagai.agui.core

import com.agui.core.types.ActivitySnapshotEvent
import com.agui.core.types.Role
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `UiMessage.id` is what a renderer keys on, and Compose's `LazyColumn` throws
 * `IllegalArgumentException: Key "..." was already used` on a duplicate rather than degrading.
 * The wire promises no uniqueness, so this reducer has to.
 */
class MessageIdUniquenessTest {

    /**
     * A producer that numbers its message ids from one on every run.
     *
     * `startNewRun` already anticipates this and answers it with a second message rather than by
     * appending to the settled first -- which is what used to mint two messages carrying `m1`.
     */
    @Test
    fun aRenumberedRunGetsItsOwnMessageId() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t1", runId = "r1"))
        reducer.accept(TextMessageStartEvent(messageId = "m1", role = Role.ASSISTANT))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "first"))
        reducer.accept(TextMessageEndEvent(messageId = "m1"))
        reducer.accept(RunFinishedEvent(threadId = "t1", runId = "r1"))

        reducer.accept(RunStartedEvent(threadId = "t1", runId = "r2"))
        reducer.accept(TextMessageStartEvent(messageId = "m1", role = Role.ASSISTANT))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "second"))

        val messages = reducer.transcript.messages
        assertEquals(listOf("m1", "m1#2"), messages.map { it.id })
        // The disambiguation is the *later* message's; the first run keeps the wire's id and text.
        assertEquals(listOf("first", "second"), messages.map { it.text })
    }

    /** An `ACTIVITY_SNAPSHOT` naming a `messageId` a user text message already took. */
    @Test
    fun anActivitySnapshotCannotCollideWithAnExistingMessage() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t1", runId = "r1"))
        reducer.accept(TextMessageStartEvent(messageId = "x", role = Role.USER))
        reducer.accept(TextMessageEndEvent(messageId = "x"))
        reducer.accept(
            ActivitySnapshotEvent(
                messageId = "x",
                activityType = "a2ui-surface",
                content = JsonPrimitive("payload"),
            ),
        )

        assertEquals(listOf("x", "x#2"), reducer.transcript.messages.map { it.id })
    }

    /** An id that never collides is passed through untouched. */
    @Test
    fun anIdThatDoesNotCollideIsUntouched() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t1", runId = "r1"))
        reducer.accept(TextMessageStartEvent(messageId = "u1", role = Role.USER))
        reducer.accept(TextMessageEndEvent(messageId = "u1"))
        reducer.accept(TextMessageStartEvent(messageId = "a1", role = Role.ASSISTANT))

        assertEquals(listOf("u1", "a1"), reducer.transcript.messages.map { it.id })
    }
}

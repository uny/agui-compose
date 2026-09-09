package dev.ynagai.agui.core

import com.agui.core.types.BaseEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import dev.ynagai.agui.model.RunState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FlowsTest {

    private val run: Flow<BaseEvent> = flowOf(
        RunStartedEvent(threadId = "t", runId = "r"),
        TextMessageStartEvent(messageId = "m1"),
        TextMessageContentEvent(messageId = "m1", delta = "Hel"),
        TextMessageContentEvent(messageId = "m1", delta = "lo"),
        TextMessageEndEvent(messageId = "m1"),
        RunFinishedEvent(threadId = "t", runId = "r"),
    )

    @Test
    fun emits_one_transcript_per_event_so_a_renderer_sees_every_frame() = runTest {
        val frames = run.foldToTranscript().toList()

        assertEquals(6, frames.size)
        assertContentEquals(
            listOf("", "", "Hel", "Hello", "Hello", "Hello"),
            frames.map { it.messages.firstOrNull()?.text.orEmpty() },
        )
        assertEquals(RunState.Finished("t", "r"), frames.last().run)
    }

    /**
     * A fresh reducer per collection. Otherwise a retry, or a second subscriber, would append a
     * replay of the run to the conversation it already produced.
     */
    @Test
    fun replays_from_empty_on_every_collection() = runTest {
        val transcripts = run.foldToTranscript()

        assertEquals(1, transcripts.toList().last().messages.size)
        // The second collection sees the same conversation, not that one appended to itself.
        assertEquals(1, transcripts.toList().last().messages.size)
    }
}

package dev.ynagai.agui.core

import com.agui.core.types.Interrupt
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunFinishedInterruptOutcome
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallResultEvent
import com.agui.core.types.ToolCallStartEvent
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiInterrupt
import dev.ynagai.agui.model.UiTranscript
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A run that ends with an interrupt outcome: what it asked for lands on the run, and the call it
 * named is told it is waiting.
 */
class InterruptTest {

    @Test
    fun the_interrupts_land_on_the_finished_run_whole() {
        val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
        val metadata = buildJsonObject { put("tool", JsonPrimitive("transfer")) }
        val transcript = reduce(
            RunStartedEvent(threadId = "t", runId = "r"),
            RunFinishedEvent(
                threadId = "t",
                runId = "r",
                outcome = RunFinishedInterruptOutcome(
                    interrupts = listOf(
                        Interrupt(
                            id = "i1",
                            reason = "tool_call",
                            message = "Approve?",
                            toolCallId = "c1",
                            responseSchema = schema,
                            expiresAt = "2026-09-13T09:00:00Z",
                            metadata = metadata,
                        ),
                        Interrupt(id = "i2", reason = "choose"),
                    ),
                ),
            ),
        )

        val run = assertIs<RunState.Finished>(transcript.run)
        assertEquals(true, run.interrupted)
        assertEquals(
            listOf(
                UiInterrupt("i1", "tool_call", "Approve?", "c1", schema, "2026-09-13T09:00:00Z", metadata),
                UiInterrupt("i2", "choose"),
            ),
            run.interrupts,
        )
    }

    @Test
    fun a_run_that_was_done_asked_nothing() {
        val run = assertIs<RunState.Finished>(reduce(*finished()).run)
        assertEquals(false, run.interrupted)
        assertEquals(emptyList(), run.interrupts)
    }

    @Test
    fun the_call_an_interrupt_names_waits_for_approval() {
        val transcript = reduce(*call("c1"), *ask("c1"))
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, transcript.toolCall("c1").status)
    }

    @Test
    fun an_interrupt_naming_no_call_or_an_unknown_one_marks_nothing() {
        val transcript = reduce(*call("c1"), *ask(null, "c9"))
        assertEquals(ToolCallStatus.AWAITING_RESULT, transcript.toolCall("c1").status)
        assertEquals(2, (transcript.run as RunState.Finished).interrupts.size)
    }

    /** A call that already has its result is not waiting on anyone, whatever the producer says. */
    @Test
    fun a_call_that_already_has_its_result_is_not_held() {
        val transcript = reduce(
            *call("c1"),
            ToolCallResultEvent(messageId = "m", toolCallId = "c1", content = "ok"),
            *ask("c1"),
        )
        assertEquals(ToolCallStatus.COMPLETE, transcript.toolCall("c1").status)
    }

    /**
     * The next run starting means the question was answered or abandoned. An approved call's
     * result arrives in that run; a declined one may never hear anything again, so a status that
     * still said "waiting on the client" would be a lie for ever.
     */
    @Test
    fun the_next_run_releases_the_call_and_its_result_lands() {
        val reducer = UiTranscriptReducer()
        (call("c1") + ask("c1")).forEach { reducer.accept(it) }
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, reducer.transcript.toolCall("c1").status)

        reducer.accept(RunStartedEvent(threadId = "t", runId = "r2"))
        assertEquals(ToolCallStatus.AWAITING_RESULT, reducer.transcript.toolCall("c1").status)
        assertEquals(RunState.Running("t", "r2"), reducer.transcript.run)

        reducer.accept(ToolCallResultEvent(messageId = "m", toolCallId = "c1", content = "sent"))
        assertEquals(ToolCallStatus.COMPLETE, reducer.transcript.toolCall("c1").status)
        assertEquals("sent", reducer.transcript.toolCall("c1").result)
    }

    /**
     * A resume whose run failed before it started -- a transport failure is a lone `RUN_ERROR`
     * -- leaves the transcript's run [RunState.Failed] with the questions still open. The next
     * run to start still releases the call: what was held is remembered here, not read back off
     * a run state that has since been replaced.
     */
    @Test
    fun a_failed_run_between_the_ask_and_the_next_start_still_releases_the_call() {
        val reducer = UiTranscriptReducer()
        (call("c1") + ask("c1")).forEach { reducer.accept(it) }
        reducer.accept(RunErrorEvent(message = "upstream is down", code = "BOOM"))
        assertIs<RunState.Failed>(reducer.transcript.run)
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, reducer.transcript.toolCall("c1").status)

        reducer.accept(RunStartedEvent(threadId = "t", runId = "r2"))
        assertEquals(ToolCallStatus.AWAITING_RESULT, reducer.transcript.toolCall("c1").status)
    }

    private fun UiTranscript.toolCall(id: String): ToolCallPart =
        messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single { it.toolCallId == id }

    private fun call(id: String): Array<com.agui.core.types.BaseEvent> = arrayOf(
        RunStartedEvent(threadId = "t", runId = "r"),
        ToolCallStartEvent(toolCallId = id, toolCallName = "transfer"),
        ToolCallArgsEvent(toolCallId = id, delta = "{}"),
        ToolCallEndEvent(toolCallId = id),
    )

    private fun ask(vararg toolCallIds: String?): Array<com.agui.core.types.BaseEvent> = arrayOf(
        RunFinishedEvent(
            threadId = "t",
            runId = "r",
            outcome = RunFinishedInterruptOutcome(
                interrupts = toolCallIds.mapIndexed { i, id -> Interrupt(id = "i$i", reason = "approve", toolCallId = id) },
            ),
        ),
    )

    private fun finished(): Array<com.agui.core.types.BaseEvent> = arrayOf(
        RunStartedEvent(threadId = "t", runId = "r"),
        RunFinishedEvent(threadId = "t", runId = "r"),
    )
}

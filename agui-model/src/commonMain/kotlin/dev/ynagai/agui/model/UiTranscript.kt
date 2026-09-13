package dev.ynagai.agui.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Everything a renderer needs to draw one conversation, at one instant.
 *
 * Immutable and replaced wholesale on every change rather than mutated. A Compose renderer
 * recomposes on identity, and the parts that did not change keep their instances, so replacing the
 * transcript costs a diff of the list rather than a redraw of it.
 *
 * @property sharedState the agent's `STATE_SNAPSHOT` with every `STATE_DELTA` applied. Shared with
 *   the agent in both directions, which is why it is here and not inside a message.
 * @property steps the `STEP_STARTED` / `STEP_FINISHED` names still open, oldest first.
 */
public data class UiTranscript(
    public val messages: List<UiMessage> = emptyList(),
    public val run: RunState = RunState.Idle,
    public val sharedState: JsonElement = JsonObject(emptyMap()),
    public val steps: List<String> = emptyList(),
)

/** Where the current run has got to. */
public sealed interface RunState {
    /** No run has started yet. A run that ends stays [Finished] or [Failed]; it never returns here. */
    public data object Idle : RunState

    /** A run is in flight. */
    public data class Running(
        public val threadId: String,
        public val runId: String,
    ) : RunState

    /**
     * The run finished.
     *
     * This is the *run's* verdict, not the turn's. A client that executes tools answers a finished
     * run with another run carrying the results, so a transcript can read [Finished] while a tool
     * is still executing and again between that run and the next; a UI gating on "the agent is
     * done" gates on the call that started the exchange returning, not on this.
     *
     * @property result whatever the agent returned with `RUN_FINISHED`, which the protocol leaves
     *   untyped.
     * @property interrupts what the run stopped to ask for, when it stopped to ask rather than
     *   because it was done. Empty for a run that was done. Every one of these has to be answered
     *   -- or abandoned -- by the next run on the thread before that run starts; the protocol has
     *   no other way past an interrupt, and `agui-agent`'s `AgentSession.resume` is the call that
     *   answers them.
     */
    public data class Finished(
        public val threadId: String,
        public val runId: String,
        public val result: JsonElement? = null,
        public val interrupts: List<UiInterrupt> = emptyList(),
    ) : RunState {
        /** Whether the run stopped to wait for the client rather than because it was done. */
        public val interrupted: Boolean get() = interrupts.isNotEmpty()
    }

    /** The run failed. `RUN_ERROR` carries no thread or run id, so neither does this. */
    public data class Failed(
        public val message: String,
        public val code: String? = null,
    ) : RunState
}

/**
 * What the thread is waiting on: the interrupts of a [RunState.Finished] that stopped to ask, and
 * nothing for any other state. A renderer that draws the questions asks this rather than casting.
 */
public val RunState.pendingInterrupts: List<UiInterrupt>
    get() = (this as? RunState.Finished)?.interrupts.orEmpty()

/**
 * One thing a run stopped to ask for: an approval, a choice, a credential.
 *
 * The shape is the protocol's `Interrupt`, kept whole because every field is something a renderer
 * may need: [message] is the prompt to put in front of whoever answers, [responseSchema] describes
 * the answer the agent wants -- carried opaquely, so a renderer can build a form for it -- and
 * [toolCallId] names the call an approval concerns, when it concerns one. It is optional in the
 * protocol and absent from some producers, so an interrupt is *not* a property of a tool call:
 * it belongs to the run, and a tool call the run named is told it is waiting
 * ([ToolCallStatus.AWAITING_APPROVAL]) rather than handed the interrupt itself.
 *
 * @property id unique within the run; the answer names it.
 * @property reason an open string the producer chose -- `tool_call` for an approval, from the
 *   producers that raise one. Nothing here reads anything into it; `agui-material3` draws it as
 *   the prompt of last resort.
 * @property expiresAt when the producer will stop accepting an answer, in a format the protocol
 *   deliberately leaves to the producer -- conventionally ISO 8601. Carried, not judged: nothing
 *   here decides an interrupt has expired, so a client that wants to stop offering one after its
 *   time reads this itself.
 * @property metadata whatever else the producer attached. AWS Strands puts the tool's name and
 *   input here for an approval raised without a native tool use.
 */
public data class UiInterrupt(
    public val id: String,
    public val reason: String,
    public val message: String? = null,
    public val toolCallId: String? = null,
    public val responseSchema: JsonElement? = null,
    public val expiresAt: String? = null,
    public val metadata: JsonElement? = null,
)

/**
 * The client's answer to one [UiInterrupt], as the next run carries it.
 *
 * @property interruptId the [UiInterrupt.id] this answers.
 * @property status whether the interrupt was answered or abandoned.
 * @property payload the answer the agent asked for, any JSON, when [status] is
 *   [UiResumeStatus.RESOLVED]. What an abandoned interrupt means for the agent's work is the
 *   producer's business, so an entry abandoning one carries nothing.
 */
public data class UiResumeEntry(
    public val interruptId: String,
    public val status: UiResumeStatus,
    public val payload: JsonElement? = null,
) {
    public companion object {
        /** An entry answering [interrupt] with [payload]. */
        public fun resolved(interrupt: UiInterrupt, payload: JsonElement? = null): UiResumeEntry =
            UiResumeEntry(interrupt.id, UiResumeStatus.RESOLVED, payload)

        /** An entry abandoning [interrupt]. */
        public fun cancelled(interrupt: UiInterrupt): UiResumeEntry =
            UiResumeEntry(interrupt.id, UiResumeStatus.CANCELLED)
    }
}

/** How a [UiResumeEntry] disposes of its interrupt. */
public enum class UiResumeStatus {
    /** Answered; the entry's payload is the answer. */
    RESOLVED,

    /** Abandoned, explicitly. Omitting an interrupt from a resume is not this. */
    CANCELLED,
}

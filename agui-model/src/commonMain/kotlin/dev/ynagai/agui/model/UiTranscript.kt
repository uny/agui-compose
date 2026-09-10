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
     * @property result whatever the agent returned with `RUN_FINISHED`, which the protocol leaves
     *   untyped.
     * @property interrupted set when the run stopped to wait for the client -- a human approving a
     *   tool call, say -- rather than because it was done.
     */
    public data class Finished(
        public val threadId: String,
        public val runId: String,
        public val result: JsonElement? = null,
        public val interrupted: Boolean = false,
    ) : RunState

    /** The run failed. `RUN_ERROR` carries no thread or run id, so neither does this. */
    public data class Failed(
        public val message: String,
        public val code: String? = null,
    ) : RunState
}

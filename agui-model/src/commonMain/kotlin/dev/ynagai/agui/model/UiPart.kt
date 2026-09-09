package dev.ynagai.agui.model

import kotlinx.serialization.json.JsonElement

/**
 * One renderable piece of a message, in the position the agent emitted it.
 *
 * The protocol streams text, reasoning and tool calls as three independent event families, each
 * with its own message identifier and no field relating one to another. Their *arrival order* is
 * the only record that the agent reasoned, then spoke, then called a tool -- so that order is what
 * this model preserves, and [UiMessage.parts] is the list it is preserved in.
 *
 * A part carries its own streaming state rather than the message carrying one state for all of it.
 * A tool call can still be running while the text after it is complete, and a renderer that had
 * only a message-level flag would have to choose which of the two to believe.
 */
public sealed interface UiPart {
    /**
     * Stable across the whole life of the part, including while it streams.
     *
     * Renderers key list items on this, so it must not change when the part's content grows. It is
     * unique within a [UiMessage] but not across a transcript: two messages may each hold a part
     * whose id is the message id they belong to.
     */
    public val id: String
}

/**
 * Assistant or user prose.
 *
 * @property messageId the protocol message id this run of text was streamed under. Kept because a
 *   single [UiMessage] can hold several text runs -- an agent that speaks, calls a tool, then
 *   speaks again emits two text messages with two ids -- and a renderer or a resend needs to know
 *   which is which.
 * @property streaming `true` between `TEXT_MESSAGE_START` and `TEXT_MESSAGE_END`. A renderer shows
 *   a caret while it is set.
 */
public data class TextPart(
    override val id: String,
    public val text: String,
    public val messageId: String,
    public val streaming: Boolean = false,
) : UiPart

/**
 * The agent's chain of thought.
 *
 * Separate from [TextPart] rather than a flag on it: the two are usually styled differently, are
 * often collapsed by default, and a renderer that treated reasoning as text would put an agent's
 * private deliberation in the same visual place as its answer.
 *
 * @property title the value `REASONING_START` carried, when it carried one.
 * @property encryptedValues opaque blobs from `REASONING_ENCRYPTED_VALUE`. They are not renderable
 *   and are kept only so that a client which must echo them back to the agent still can.
 */
public data class ReasoningPart(
    override val id: String,
    public val text: String,
    public val messageId: String,
    public val title: String? = null,
    public val streaming: Boolean = false,
    public val encryptedValues: List<EncryptedReasoningValue> = emptyList(),
) : UiPart

/** An opaque `REASONING_ENCRYPTED_VALUE` payload, kept verbatim for echoing back to the agent. */
public data class EncryptedReasoningValue(
    public val subtype: String,
    public val entityId: String,
    public val value: String,
)

/**
 * A tool the agent asked for, and how far it has got.
 *
 * @property arguments the JSON text assembled from `TOOL_CALL_ARGS`. A string rather than a parsed
 *   [JsonElement] because it is *not* valid JSON until the call ends -- it arrives one fragment at
 *   a time, and a renderer that wants to show arguments as they stream has to work with the
 *   fragment. [ToolCallPart.parsedArguments] is the parsed form, and is null until it parses.
 * @property result the tool's output, once a `TOOL_CALL_RESULT` names this call. Its shape is the
 *   tool's own business, so it is left as the string the protocol carries.
 */
public data class ToolCallPart(
    override val id: String,
    public val toolCallId: String,
    public val name: String,
    public val arguments: String = "",
    public val parsedArguments: JsonElement? = null,
    public val status: ToolCallStatus = ToolCallStatus.STREAMING_ARGUMENTS,
    public val result: String? = null,
    public val error: String? = null,
    public val parentMessageId: String? = null,
) : UiPart

/**
 * How far a [ToolCallPart] has got.
 *
 * [AWAITING_RESULT] is the state a *frontend* tool sits in while the client executes it, and is
 * also the state a backend tool sits in while the agent runs it. The protocol does not distinguish
 * the two -- both are "the call is complete and no result has arrived" -- so neither does this.
 */
public enum class ToolCallStatus {
    /** Arguments are still arriving; `TOOL_CALL_END` has not been seen. */
    STREAMING_ARGUMENTS,

    /** Arguments are complete. No `TOOL_CALL_RESULT` has named this call yet. */
    AWAITING_RESULT,

    /** A result arrived. */
    COMPLETE,

    /** The client reported the call as failed. The protocol has no failure event of its own. */
    FAILED,
}

/**
 * Structured content that is not text and not a tool call: an `ACTIVITY_SNAPSHOT` stream.
 *
 * Modelled on [activityType] rather than as a purpose-built A2UI part, because that is the shape
 * the protocol has. `a2ui-surface` is one value of the field; a client that understands another
 * value can render it through the same part without this module learning about it. Interpreting an
 * A2UI payload is the `agui-a2ui` module's job.
 *
 * @property content the current content, with every `ACTIVITY_DELTA` patch already applied.
 */
public data class ActivityPart(
    override val id: String,
    public val messageId: String,
    public val activityType: String,
    public val content: JsonElement,
) : UiPart

/**
 * A binary attachment on a user message -- an image, a document.
 *
 * Exactly one of [url] and [data] is the payload in practice, but the protocol permits either
 * alongside a bare [remoteId], so all three are optional here and a renderer resolves whichever it
 * finds. Constructing one with none of the three is what the protocol forbids.
 */
public data class FilePart(
    override val id: String,
    public val mimeType: String,
    public val remoteId: String? = null,
    public val url: String? = null,
    public val data: String? = null,
    public val filename: String? = null,
) : UiPart

package dev.ynagai.agui.model

/**
 * One turn in the transcript, as an ordered list of [UiPart]s.
 *
 * A message is not the same unit as a protocol message. An assistant that reasons, speaks, calls a
 * tool and speaks again emits four message ids across three event families; this model folds that
 * turn into one [UiMessage] whose parts are in the order the agent produced them, and each part
 * keeps the protocol id it came from. That is the whole reason this type exists: the transcript
 * shape the upstream SDK maintains -- an assistant message with a single `content` string and a
 * separate `toolCalls` list, and reasoning in a sibling channel keyed by its own id -- cannot say
 * whether the tool call came before or after the sentence.
 *
 * There is no `TOOL` role. A tool's output belongs to the call that asked for it, so a
 * `TOOL_CALL_RESULT` lands on the [ToolCallPart] rather than opening a message of its own.
 */
public data class UiMessage(
    /**
     * Identity within one transcript, and the key a list renderer is meant to use.
     *
     * **Unique across [UiTranscript.messages]** -- Compose's `LazyColumn` throws outright on a
     * duplicate key, so this is a guarantee a renderer is entitled to lean on rather than a
     * coincidence. The producer is what upholds it: `agui-core` disambiguates on sight, because
     * the wire promises nothing of the sort and a run that renumbers its message ids from one is
     * an ordinary thing for an agent to do.
     *
     * Usually the protocol message id this turn opened under, and equal to it whenever nothing
     * forced a rename. It is *not* the field to correlate against the wire: a turn folds several
     * protocol messages together, and each part keeps its own protocol id
     * ([TextPart.messageId] and its siblings) for exactly that.
     */
    public val id: String,
    public val role: UiRole,
    public val parts: List<UiPart> = emptyList(),
    public val name: String? = null,
) {
    /** The concatenated text of every [TextPart], for a renderer that wants the message as prose. */
    public val text: String
        get() = parts.filterIsInstance<TextPart>().joinToString(separator = "") { it.text }

    /** `true` while any part is still arriving. */
    public val isStreaming: Boolean
        get() = parts.any {
            when (it) {
                is TextPart -> it.streaming
                is ReasoningPart -> it.streaming
                is ToolCallPart -> it.status == ToolCallStatus.STREAMING_ARGUMENTS
                is ActivityPart, is FilePart -> false
            }
        }
}

/**
 * Who produced a message.
 *
 * [ACTIVITY] is a role in the protocol's own message model, not an invention here: an
 * `ACTIVITY_SNAPSHOT` stream is addressed by a message id of its own and does not belong to the
 * assistant's turn.
 */
public enum class UiRole {
    USER,
    ASSISTANT,
    SYSTEM,
    DEVELOPER,
    ACTIVITY,
}

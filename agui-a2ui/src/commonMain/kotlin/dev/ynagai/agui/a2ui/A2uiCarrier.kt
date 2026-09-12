package dev.ynagai.agui.a2ui

import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.UiTranscript

/**
 * Where in a transcript an [A2uiPayload] came from.
 *
 * Keyed by the *protocol's* ids -- the activity's `messageId`, the call's `toolCallId` -- and not
 * by `UiPart.id`. A `MESSAGES_SNAPSHOT` at the end of a run rebuilds every part, and a key that
 * changed with the rebuild would tear down and redraw every surface the moment the run finished.
 *
 * The order of the variants is their precedence. When one surface arrives in several carriers --
 * the ordinary case with upstream's middleware, which paints the activity progressively *and*
 * leaves the `render_a2ui` arguments and the outer tool's result in the transcript -- the
 * highest wins and the others draw nothing. The activity is the middleware's own cumulative,
 * validated form, so it outranks the result, which outranks the arguments the model streamed.
 */
public sealed interface A2uiCarrier {
    /** Lower draws first; higher takes a surface from lower. */
    public val precedence: Int

    /** The streamed arguments of a render call. */
    public data class ToolArguments(public val toolCallId: String) : A2uiCarrier {
        override val precedence: Int get() = 1
    }

    /** A tool result holding `a2ui_operations`. */
    public data class ToolResult(public val toolCallId: String) : A2uiCarrier {
        override val precedence: Int get() = 2
    }

    /** An `a2ui-surface` activity. */
    public data class Activity(public val messageId: String) : A2uiCarrier {
        override val precedence: Int get() = 3
    }
}

/** One payload and the carrier it arrived in. */
public data class A2uiCarried(public val carrier: A2uiCarrier, public val payload: A2uiPayload)

/**
 * Every A2UI payload in this transcript, in transcript order.
 *
 * A render call contributes up to two: its arguments and, separately, its result -- the two say
 * different things at different times, and the result of an *outer* tool commonly carries the
 * surface an inner call streamed. Parts that carry no A2UI contribute nothing; a part that
 * claims to and does not contributes [A2uiPayload.Malformed].
 */
public fun UiTranscript.a2uiPayloads(
    translation: A2uiTranslation = A2uiTranslation.Default,
    toolNames: Set<String> = setOf(AguiA2ui.RENDER_TOOL_NAME),
): List<A2uiCarried> = buildList {
    for (message in messages) {
        for (part in message.parts) {
            when (part) {
                is ActivityPart -> A2uiPayload.of(part, translation)?.let {
                    add(A2uiCarried(A2uiCarrier.Activity(part.messageId), it))
                }
                is ToolCallPart -> {
                    A2uiPayload.ofArguments(part, translation, toolNames)?.let {
                        add(A2uiCarried(A2uiCarrier.ToolArguments(part.toolCallId), it))
                    }
                    A2uiPayload.ofResult(part, translation)?.let {
                        add(A2uiCarried(A2uiCarrier.ToolResult(part.toolCallId), it))
                    }
                }
                else -> Unit
            }
        }
    }
}

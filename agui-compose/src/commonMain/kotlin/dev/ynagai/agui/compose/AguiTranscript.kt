package dev.ynagai.agui.compose

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.FilePart
import dev.ynagai.agui.model.ReasoningPart
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiPart
import dev.ynagai.agui.model.UiTranscript

/**
 * Draws a whole [UiTranscript].
 *
 * Messages only. [UiTranscript.run], [UiTranscript.steps] and [UiTranscript.sharedState] are not
 * drawn and are not silently dropped either -- they are the transcript's *status*, and where a
 * status belongs on screen (a banner, a toolbar, an error sheet, nowhere) is an application's
 * layout decision that this composable would be guessing at. They stay on the transcript the caller
 * already holds.
 *
 * A [LazyColumn] rather than a scrolling [Column][androidx.compose.foundation.layout.Column]: a
 * transcript grows without bound and every part off screen would otherwise stay composed. Items are
 * keyed on [UiMessage.id], so a message whose parts grew keeps its state -- scroll position, an
 * expanded disclosure in a replaced slot -- while text streams into it.
 *
 * @param state hoisted so the caller can drive it. Following a streaming response means scrolling
 *   as text arrives, and only the application knows whether to keep doing that after the reader has
 *   scrolled up.
 */
@Composable
public fun AguiTranscript(
    transcript: UiTranscript,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    LazyColumn(modifier = modifier, state = state) {
        items(
            count = transcript.messages.size,
            key = { index -> transcript.messages[index].id },
        ) { index ->
            AguiMessage(transcript.messages[index])
        }
    }
}

/**
 * Draws one message: the frame from [AguiComponents.message], with its parts inside.
 *
 * Public because a transcript is not the only surface a message appears on -- a preview, a pinned
 * answer, a message being composed -- and none of those want a [LazyColumn] around one item.
 *
 * [modifier] reaches the frame, not the parts, which is the Compose convention: it is this
 * composable's root that a caller means to pad or size. A part is modified by replacing its slot.
 *
 * Each part is wrapped in [key] on [UiPart.id]. The parts of a streaming message are appended to
 * rather than reordered, so positional identity would usually agree -- but `UiPart.id` exists to be
 * keyed on, and a replaced slot holding state of its own (an expanded disclosure, a scrolled code
 * block) is what breaks first when it is not.
 */
@Composable
public fun AguiMessage(message: UiMessage, modifier: Modifier = Modifier) {
    LocalAguiComponents.current.message(message, modifier) {
        message.parts.forEach { part -> key(part.id) { AguiPart(part) } }
    }
}

/**
 * Draws one part through the matching slot in [AguiComponents].
 *
 * The `when` is exhaustive over a sealed hierarchy and has no `else`. When `agui-model` grows a part
 * kind this stops compiling, which is the point: a new kind of content that silently drew nothing
 * would look identical to an empty message.
 */
@Composable
public fun AguiPart(part: UiPart, modifier: Modifier = Modifier) {
    val components = LocalAguiComponents.current
    when (part) {
        is TextPart -> components.text(part, modifier)
        is ReasoningPart -> components.reasoning(part, modifier)
        is ToolCallPart -> components.toolCall(part, modifier)
        is ActivityPart -> components.activity(part, modifier)
        is FilePart -> components.file(part, modifier)
    }
}

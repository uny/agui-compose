package dev.ynagai.agui.compose

import androidx.compose.foundation.layout.Column
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
import dev.ynagai.agui.model.UiInterrupt
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiPart
import dev.ynagai.agui.model.UiResumeEntry
import dev.ynagai.agui.model.UiTranscript

/**
 * Draws a whole [UiTranscript].
 *
 * Messages only. [UiTranscript.run], [UiTranscript.steps] and [UiTranscript.sharedState] are not
 * drawn and are not silently dropped either -- they are the transcript's *status*, and where a
 * status belongs on screen (a banner, a toolbar, an error sheet, nowhere) is an application's
 * layout decision that this composable would be guessing at. They stay on the transcript the caller
 * already holds. The one piece of status that has a drawing of its own is what a run stopped to
 * ask for, and [AguiInterrupts] draws that where the application puts it.
 *
 * A [LazyColumn] rather than a scrolling [Column][androidx.compose.foundation.layout.Column]: a
 * transcript grows without bound and every *message* off screen would otherwise stay composed.
 * Items are keyed on [UiMessage.id], so a message whose parts grew keeps its state -- scroll
 * position, an expanded disclosure in a replaced slot -- while text streams into it.
 *
 * The virtualisation stops at the message boundary, which matters for the shape this model
 * produces: [AguiMessage] composes every part of one message eagerly, and `agui-core` folds a
 * whole assistant turn -- reasoning, each run of prose, every tool call -- into a single
 * [UiMessage]. A turn entirely off screen still costs nothing. But the item is all-or-nothing: the
 * moment any one part of a turn scrolls into view, every part of that turn composes and measures,
 * however far the rest of it extends past the viewport. Splitting a turn across items would key on
 * [UiPart.id] and give up the message frame, which is the wrong trade for a transcript of ordinary
 * turns; an application that expects unbounded single turns wants its own list rather than this
 * one.
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

/**
 * Draws what the run stopped to ask for, each through [AguiComponents.interrupt].
 *
 * Separate from [AguiTranscript] because an interrupt is not a message: it is the thread's
 * status, and where a question waiting on the reader belongs -- under the transcript, above the
 * composer, in a sheet -- is the application's layout decision. Pass
 * [dev.ynagai.agui.model.pendingInterrupts] of the transcript's run, or whatever the application
 * holds them in once the transcript has stopped: a run that *fails* after asking drops them from
 * [dev.ynagai.agui.model.RunState], and `agui-agent`'s session keeps them.
 *
 * Nothing when [interrupts] is empty, and nothing is measured either -- an empty [Column] takes
 * no space -- so this can sit in a layout unconditionally.
 *
 * @param onResume what the reader answered, one entry per interrupt answered. Answering is the
 *   caller's job from here: `agui-agent`'s `AgentSession.resume` takes every answer at once, since
 *   the protocol lets no run start until every interrupt has one.
 */
@Composable
public fun AguiInterrupts(
    interrupts: List<UiInterrupt>,
    onResume: (UiResumeEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val components = LocalAguiComponents.current
    Column(modifier) {
        interrupts.forEach { interrupt ->
            key(interrupt.id) { components.interrupt(interrupt, onResume, Modifier) }
        }
    }
}

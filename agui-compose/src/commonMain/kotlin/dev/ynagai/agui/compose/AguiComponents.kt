package dev.ynagai.agui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.FilePart
import dev.ynagai.agui.model.ReasoningPart
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiPart

/**
 * One drawing function per kind of [UiPart], plus the frame a whole [UiMessage] sits in.
 *
 * A table of slots rather than a registry keyed by type, because the set of part kinds is a sealed
 * hierarchy in `agui-model` and cannot grow at runtime. That makes an exhaustive `when` possible in
 * [AguiPart], and it makes this type's field list the honest statement of what a renderer must
 * cover: adding a part kind upstream breaks this file at compile time instead of falling through to
 * a placeholder at run time.
 *
 * Overriding is by `copy`, so an application replaces the one slot it cares about and inherits the
 * rest:
 *
 * ```
 * val components = remember { AguiComponents().copy(toolCall = { part, modifier -> ... }) }
 *
 * CompositionLocalProvider(LocalAguiComponents provides components) { AguiTranscript(transcript) }
 * ```
 *
 * The `remember` is not decoration. [LocalAguiComponents] is static, so a table this composition
 * does not consider equal to the last one recomposes everything under it with skipping disabled --
 * and a slot lambda that captures anything makes exactly that on every recomposition of whatever
 * holds the provider. Built in the `provides`, a capturing override redraws every visible part on
 * every frame of a streaming response; hoisted into a `remember`, none of them redraw.
 *
 * The defaults draw structure and nothing else -- no colour, no spacing, no shape. That is not an
 * unfinished state: this module sits below any design system, and a default that guessed at
 * padding would be a design decision made in the one place a consumer cannot see it. `agui-material3`
 * is where those decisions get made.
 */
@Immutable
public data class AguiComponents(
    /** Assistant or user prose. Draws through [LocalAguiTextRenderer]. */
    public val text: @Composable (part: TextPart, modifier: Modifier) -> Unit = { part, modifier ->
        LocalAguiTextRenderer.current.Render(part.text, part.streaming, modifier)
    },

    /**
     * The agent's chain of thought.
     *
     * Drawn, and drawn identically to [text], because hiding it by default would make an empty
     * composition the thing a consumer sees first and leave them looking for the bug. Collapsing
     * reasoning behind a disclosure is the usual treatment and it is a design-system decision, so
     * it belongs one layer up rather than here.
     */
    public val reasoning: @Composable (part: ReasoningPart, modifier: Modifier) -> Unit =
        { part, modifier ->
            LocalAguiTextRenderer.current.Render(part.text, part.streaming, modifier)
        },

    /**
     * A tool call.
     *
     * The default shows the tool's name and how far the call has got, and deliberately not its
     * arguments: they arrive as JSON fragments that are not parseable until the call ends, and a
     * default that dumped the fragment would put a half-written object on screen. An application
     * that wants to show arguments streaming has [ToolCallPart.arguments] and replaces this slot.
     */
    public val toolCall: @Composable (part: ToolCallPart, modifier: Modifier) -> Unit =
        { part, modifier ->
            BasicText(text = "${part.name} (${part.status})", modifier = modifier)
        },

    /**
     * An `ACTIVITY_SNAPSHOT` stream.
     *
     * The default draws the activity type and not the payload. `agui-model` keeps the content as an
     * opaque `JsonElement` on purpose -- interpreting `a2ui-surface`, or any other value of the
     * field, is a job for a module that understands that dialect -- so the honest default here
     * names what arrived and leaves the rendering to whoever can do it. `agui-a2ui` replaces this
     * slot.
     */
    public val activity: @Composable (part: ActivityPart, modifier: Modifier) -> Unit =
        { part, modifier ->
            BasicText(text = part.activityType, modifier = modifier)
        },

    /**
     * An attachment.
     *
     * The default draws the file's name, or its MIME type when the protocol carried no name.
     * Decoding a base64 image or fetching a URL needs an image loader, and this module does not
     * choose one for its consumers.
     */
    public val file: @Composable (part: FilePart, modifier: Modifier) -> Unit = { part, modifier ->
        BasicText(text = part.filename ?: part.mimeType, modifier = modifier)
    },

    /**
     * The frame one message's parts are laid out in.
     *
     * Takes the message and the content that draws its parts, so an override can add a role label,
     * an avatar or an alignment without reimplementing the part dispatch inside it.
     *
     * The `modifier` is [AguiMessage]'s own and belongs on whatever this slot makes its root, which
     * is what makes `AguiMessage(message, Modifier.padding(8.dp))` mean what a Compose caller
     * expects. An override that drops it silently breaks that caller.
     */
    public val message:
        @Composable (
            message: UiMessage,
            modifier: Modifier,
            content: @Composable () -> Unit,
        ) -> Unit =
        { _, modifier, content -> Column(modifier) { content() } },
)

/**
 * The slot table the renderers in this module draw through.
 *
 * `static` for the same reason [LocalAguiTextRenderer] is: replacing the table restyles the whole
 * transcript at once, so tracking per-part reads would buy nothing and would cost one invalidation
 * per part on the change it is meant to make cheap.
 *
 * The cost of that choice lands on the caller, so it is stated rather than left to be discovered:
 * every value this local has not seen before is a full restyle, and an [AguiComponents] built
 * inside the `provides` is a value it has not seen before whenever one of its slots captures.
 * `remember` the table.
 */
public val LocalAguiComponents: ProvidableCompositionLocal<AguiComponents> =
    staticCompositionLocalOf { AguiComponents() }

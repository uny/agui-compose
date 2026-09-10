package dev.ynagai.agui.material3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.ynagai.agui.compose.AguiComponents
import dev.ynagai.agui.compose.AguiTextRenderer
import dev.ynagai.agui.compose.LocalAguiComponents
import dev.ynagai.agui.compose.LocalAguiTextRenderer

/**
 * The slot table `agui-compose` draws through, filled in with Material 3.
 *
 * `agui-compose`'s defaults draw structure and nothing else -- no colour, no spacing, no shape --
 * because that module sits below any design system. This is the table that makes the same
 * transcript look like an application.
 *
 * It is a table rather than a set of composables, so overriding is the mechanism that already
 * exists: `copy` one slot and inherit the rest.
 *
 * ```
 * val components = remember {
 *     Material3AguiComponents().copy(toolCall = { part, modifier -> MyToolCallCard(part, modifier) })
 * }
 * ```
 *
 * The same instance every call. [LocalAguiComponents] is static, so providing a value the
 * composition has not seen before restyles everything under it with skipping disabled -- and the
 * usual way to do that by accident is to build the table inside the `provides`. Returning a
 * singleton removes that hazard from *this* call; it does not remove it from a `copy` whose slot
 * lambda captures, which is still a new value on every recomposition and still wants a `remember`.
 *
 * **[AguiComponents.text] is not overridden.** The default already draws through
 * [LocalAguiTextRenderer], and [ProvideMaterial3Agui] puts [Material3AguiTextRenderer] there, so
 * prose picks up Material 3's type and colour without this table naming `Text` at all. That is what
 * keeps the Markdown seam open: an application that provides its own renderer keeps every slot
 * here, because none of them draw prose themselves.
 *
 * @see ProvideMaterial3Agui
 */
public fun Material3AguiComponents(): AguiComponents = Material3Components

private val Material3Components = AguiComponents(
    reasoning = { part, modifier -> Material3Reasoning(part, modifier) },
    toolCall = { part, modifier -> Material3ToolCall(part, modifier) },
    activity = { part, modifier -> Material3Activity(part, modifier) },
    file = { part, modifier -> Material3File(part, modifier) },
    message = { message, modifier, content -> Material3MessageFrame(message, modifier, content) },
)

/**
 * Provides both of this module's defaults to everything drawn inside.
 *
 * Two composition locals, not one, and that is the point of the function existing. Providing
 * [Material3AguiComponents] alone leaves [LocalAguiTextRenderer] at `PlainAguiTextRenderer`, which
 * draws through `BasicText` and therefore has no ambient text style -- so a transcript would have
 * Material 3 tool calls and bubbles around prose that ignored the theme's type scale and content
 * colour entirely. The two defaults are one decision and this is the call that makes it.
 *
 * ```
 * MaterialTheme {
 *     ProvideMaterial3Agui {
 *         AguiTranscript(transcript)
 *     }
 * }
 * ```
 *
 * A `MaterialTheme` above it is the caller's job rather than this function's. Wrapping one here
 * would mean a nested theme inside every application that already has its own -- and quietly
 * discarding that application's colour scheme, which is the one thing a design-system layer must
 * not do.
 *
 * @param textRenderer the seam a Markdown implementation is fitted through. Pass one and the slots
 *   in this module keep working: prose draws through whatever is provided here, framed by Material
 *   3 either way. `remember` it -- the local is static, so a renderer rebuilt on every
 *   recomposition re-renders the whole transcript on every frame of a streaming response.
 * @param components the slot table, for a caller who has `copy`-ed a slot of it.
 */
@Composable
public fun ProvideMaterial3Agui(
    textRenderer: AguiTextRenderer = Material3AguiTextRenderer,
    components: AguiComponents = Material3AguiComponents(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAguiTextRenderer provides textRenderer,
        LocalAguiComponents provides components,
        content = content,
    )
}

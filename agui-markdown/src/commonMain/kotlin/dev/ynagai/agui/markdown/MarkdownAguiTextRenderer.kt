package dev.ynagai.agui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import dev.ynagai.agui.compose.AguiTextRenderer
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

/**
 * Draws agent prose as Markdown.
 *
 * This is the whole of what this module publishes, and it is fitted through the seam
 * [AguiTextRenderer] exists for:
 *
 * ```
 * val renderer = remember { MarkdownAguiTextRenderer() }
 * CompositionLocalProvider(LocalAguiTextRenderer provides renderer) {
 *     AguiTranscript(transcript)
 * }
 * ```
 *
 * `remember` is not decoration. [LocalAguiTextRenderer][dev.ynagai.agui.compose.LocalAguiTextRenderer]
 * is a static composition local with no equality beyond identity, so a renderer constructed inside
 * the `provides` re-provides itself on every recomposition and re-parses every visible run of a
 * streaming response on every frame.
 *
 * **The default styling is black on nothing, and that is deliberate.** This module depends on
 * `agui-compose` and a parser, not on a design system, so there is no ambient text style or content
 * colour for it to read -- the same position, and the same consequence, as
 * [PlainAguiTextRenderer][dev.ynagai.agui.compose.PlainAguiTextRenderer]. Dropped into
 * `ProvideMaterial3Agui` unchanged it will draw black text in a dark theme and ignore the type
 * scale, which is a real bug and not a subtle one. Under Material 3, construct it with the ambient
 * values instead, which costs two lambdas and no extra dependency:
 *
 * ```
 * val renderer = remember {
 *     MarkdownAguiTextRenderer(
 *         colors = { markdownAguiColors(text = LocalContentColor.current) },
 *         typography = { markdownAguiTypography(base = LocalTextStyle.current) },
 *     )
 * }
 * ```
 *
 * @param colors read in composition rather than passed as a value, so that a caller reading
 *   `MaterialTheme` gets a renderer that follows a theme change without being reconstructed --
 *   which is what lets the whole thing sit behind a single `remember` with no keys.
 * @param typography read in composition for the same reason. Note that reasoning is dimmed by the
 *   slot around this renderer through `LocalContentColor`, and a `MarkdownColors` carries an
 *   explicit colour -- so under Material 3 the lambda above is also what keeps dimming working.
 * @param flavour the Markdown dialect. GitHub Flavored Markdown by default, which is what an agent
 *   writing tables and fenced code is almost always writing.
 */
@Stable
public class MarkdownAguiTextRenderer(
    private val colors: @Composable () -> MarkdownColors = { markdownAguiColors() },
    private val typography: @Composable () -> MarkdownTypography = { markdownAguiTypography() },
    private val flavour: MarkdownFlavourDescriptor = GFMFlavourDescriptor(),
) : AguiTextRenderer {
    @Composable
    override fun Render(text: String, streaming: Boolean, modifier: Modifier) {
        val colors = colors()
        val typography = typography()

        if (streaming) {
            Streaming(text = text, colors = colors, typography = typography, modifier = modifier)
        } else {
            Markdown(
                markdownState = rememberMarkdownState(
                    content = text,
                    flavour = flavour,
                    // Parse on the frame that first composes this rather than a frame later. The
                    // async default renders an empty box until the parse lands, and this branch is
                    // reached by a finished message -- including the moment a streaming one
                    // finishes -- so the blank would read as the answer disappearing and coming
                    // back. A chat-sized document is not a parse worth deferring.
                    immediate = true,
                ),
                colors = colors,
                typography = typography,
                modifier = modifier,
            )
        }
    }

    /**
     * The incremental path, taken while text is still arriving.
     *
     * [AguiTextRenderer.Render] is handed the whole run rather than the latest delta, and the
     * parser's streaming state is append-only, so the delta is recovered here by comparing what
     * has been fed in against what has now arrived. That is worth doing rather than re-parsing the
     * accumulated string on every token: the streaming parser keeps the settled prefix of the
     * document and re-parses only the tail, which turns a quadratic cost over the length of a
     * response into a linear one.
     *
     * It also stops the document from twitching. A re-parse of a partial response re-derives the
     * whole tree, so a fence that is not closed yet is a paragraph on one frame and a code block on
     * the next; the streaming parser holds the unsettled tail separately and only promotes it once
     * the syntax that would change its meaning cannot still arrive.
     */
    @Composable
    private fun Streaming(
        text: String,
        colors: MarkdownColors,
        typography: MarkdownTypography,
        modifier: Modifier,
    ) {
        // Bumped when `text` stops being an extension of what has been fed in -- a regenerated
        // message, or a renderer reused across runs. An append-only parser cannot retract, so the
        // only way back is a new one.
        var generation by remember { mutableIntStateOf(0) }

        // The parser and everything drawn from it sit inside the same `key`, so that starting over
        // replaces both at once. Keeping the renderer outside it and swapping only the parser
        // crashes: `Markdown` collects the snapshot into remembered state that holds its previous
        // value until the new parser emits, while reading the text to slice through the parser it
        // was just handed -- so for one frame it indexes the old document's nodes into an empty
        // string.
        key(generation) {
            val state = rememberStreamingMarkdownState(flavour = flavour)

            // Every write to the parser happens here rather than in the composition body. A parser
            // fed from composition is a parser fed once per recomposition, and its state updates
            // schedule the next one -- which is a recomposition loop that pins the frame thread,
            // not a slow renderer.
            LaunchedEffect(state, text) {
                val fed = state.content.toString()
                when {
                    text == fed -> Unit
                    text.startsWith(fed) -> state.append(text.substring(fed.length))
                    else -> generation++
                }
            }

            Markdown(
                streamingMarkdownState = state,
                colors = colors,
                typography = typography,
                modifier = modifier,
            )
        }
    }
}

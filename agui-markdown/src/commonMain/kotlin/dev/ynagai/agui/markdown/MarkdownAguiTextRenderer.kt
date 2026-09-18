package dev.ynagai.agui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * the `provides` re-provides itself on every recomposition, and the streaming document a renderer
 * keeps per run is thrown away and rebuilt with it -- which re-parses every visible run from the
 * top on every frame.
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
 * **This makes agent text clickable, which is a change of posture and not only of appearance.**
 * [PlainAguiTextRenderer][dev.ynagai.agui.compose.PlainAguiTextRenderer] draws a URL as the
 * characters it is; this draws it as a link, and `TEXT_MESSAGE_CONTENT` is written by a model that
 * may be relaying whatever it was told to relay. A link is emitted as a `LinkAnnotation.Url`
 * carrying the agent's own string, with no scheme filtering, so a tap reaches the ambient
 * `LocalUriHandler` -- and under GFM, bare autolinked URLs are tap targets too, without any
 * `[](...)` syntax. An application with its own deep-link scheme should assume the transcript can
 * ask to open one, and provide a `LocalUriHandler` that decides what it will act on. Images are
 * not fetched: an `![alt](src)` is drawn as its alt text, so nothing here reaches the network on
 * its own.
 *
 * @param colors read in composition rather than passed as a value, so that a caller reading
 *   `MaterialTheme` gets a renderer that follows a theme change without being reconstructed --
 *   which is what lets the whole thing sit behind a single `remember` with no keys.
 * @param typography read in composition for the same reason. Note that reasoning is dimmed by the
 *   slot around this renderer through `LocalContentColor`, and a [MarkdownColors] carries an
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

        // Parsed in composition, on the frame the text arrives, rather than in an effect a frame
        // later. The finished branch is reached by a finished message -- including the moment a
        // streaming one finishes -- and a deferred parse would draw an empty box there, which
        // reads as the answer disappearing and coming back. A chat-sized document is not a parse
        // worth deferring; the streaming branch is what bounds the cost for the one that is not
        // chat-sized yet.
        val segments = if (streaming) {
            val document = remember(flavour) { StreamingMarkdownDocument(flavour) }
            document.update(text)
        } else {
            listOf(remember(text, flavour) { flavour.parse(text) })
        }

        // A segment with nothing but blank lines in it (a run that opens with them, or a tail that
        // is only the blank line after a settle point) draws nothing, and so gets no gap either.
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            segments.filter { it.hasBlocks }.forEach { segment ->
                MarkdownBlocks(segment = segment, colors = colors, typography = typography)
            }
        }
    }
}

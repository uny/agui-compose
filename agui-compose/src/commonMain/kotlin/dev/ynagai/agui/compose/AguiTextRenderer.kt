package dev.ynagai.agui.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * How a run of agent prose is drawn.
 *
 * This is the seam a Markdown renderer is fitted through, and the reason this module has no
 * Markdown dependency of its own. An agent's text is Markdown in practice but not by the
 * protocol's account of it -- `TEXT_MESSAGE_CONTENT` carries a string and says nothing about its
 * syntax -- so which flavour to parse, and whether to parse at all, is the application's decision
 * rather than this library's. A slot keeps that decision where it belongs and keeps a parser,
 * its grammar and its transitive dependencies out of every consumer that does not want one.
 *
 * One renderer serves both prose parts. [TextPart][dev.ynagai.agui.model.TextPart] and
 * [ReasoningPart][dev.ynagai.agui.model.ReasoningPart] differ in how they are framed -- reasoning
 * is usually dimmer and collapsed -- but not in how their text is marked up, so replacing this
 * upgrades both without either default slot in [AguiComponents] being reimplemented.
 *
 * @see LocalAguiTextRenderer
 */
@Stable
public fun interface AguiTextRenderer {
    /**
     * @param text the whole run as it currently stands, not the latest delta. The reducer
     *   accumulates `TEXT_MESSAGE_CONTENT` before it reaches a renderer, so an implementation is
     *   free to re-parse this string on every recomposition and correct output does not depend on
     *   it doing anything cleverer. An incremental parser is a cost optimisation here, not a
     *   correctness requirement.
     * @param streaming `true` while more text is still arriving. A Markdown implementation wants
     *   this: a fence or a link that is half-typed is not an error yet, and an implementation that
     *   cannot tell the two apart has to choose between flickering syntax and swallowing it.
     */
    @Composable
    public fun Render(text: String, streaming: Boolean, modifier: Modifier)
}

/**
 * The renderer every default prose slot draws through.
 *
 * `static` rather than a regular composition local: swapping the renderer restyles the whole
 * transcript, so there is nothing to gain from tracking reads of it, and a static local keeps a
 * long transcript from invalidating one composition per part when it does change.
 *
 * The default is [PlainAguiTextRenderer] -- literal text, no parsing. A transcript therefore draws
 * correctly out of the box and shows Markdown syntax as the characters it is, which is honest about
 * what this module does rather than silently degrading.
 */
public val LocalAguiTextRenderer: ProvidableCompositionLocal<AguiTextRenderer> =
    staticCompositionLocalOf { PlainAguiTextRenderer }

/**
 * Draws the text exactly as it arrived.
 *
 * [BasicText] rather than a Material `Text`: this module is deliberately below any design system,
 * and the styled counterpart belongs in `agui-material3`. Foundation has no ambient text style, so
 * this draws at `TextStyle.Default` and cannot be restyled from above -- an application that wants
 * its own type provides its own [AguiTextRenderer], which is the same seam Markdown arrives
 * through.
 */
public object PlainAguiTextRenderer : AguiTextRenderer {
    @Composable
    override fun Render(text: String, streaming: Boolean, modifier: Modifier) {
        BasicText(text = text, modifier = modifier)
    }
}

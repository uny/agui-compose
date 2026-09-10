package dev.ynagai.agui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownAlertColors
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAlertColors

/**
 * What `TextStyle.Default` draws at.
 *
 * Compose resolves an unspecified font size to 14sp at paint time, which is the size
 * [PlainAguiTextRenderer][dev.ynagai.agui.compose.PlainAguiTextRenderer] ends up drawing at. The
 * heading scale below has to multiply a real number, so the same value is named here rather than
 * leaving headings unspecified and therefore all the same size as body text.
 */
private val DefaultFontSize: TextUnit = 14.sp

/**
 * Markdown colours derived from one text colour.
 *
 * The parser's palette is five colours plus the GFM alert set, and four of the five are backgrounds
 * and rules that only have to read against the surface behind them. Deriving them from [text] as
 * low-alpha tints means the whole set follows the one colour a caller actually knows -- pass
 * `LocalContentColor.current` and code blocks stay legible in a dark theme without a second
 * argument.
 *
 * [alert] is the sixth, and it is the one that cannot be tinted: GFM's `> [!WARNING]` blocks are
 * drawn in named accent colours, and the parser's own default is its light-theme set regardless of
 * what it was handed for [text]. So the default here picks the set by the luminance of [text] --
 * light text means a dark surface behind it -- rather than leaving a dark-theme transcript with
 * light-theme alerts. A caller who knows better passes their own.
 *
 * The threshold is 0.15 rather than the midpoint, because relative luminance is not perceptual
 * lightness and the two populations are nowhere near it. Measured: Material 3's light-theme
 * `onSurface` is 0.011 and its `onSurfaceVariant` 0.062, while its dark-theme `onSurface` is 0.760,
 * `onSurfaceVariant` 0.566 and `outline` 0.281. Everything a light theme uses for text sits below
 * 0.07; everything a dark theme uses sits above 0.28. A midpoint cut would put dimmed light-on-dark
 * text -- `outline`, or a hand-picked grey like `0xFF9E9E9E` at 0.32 -- on the light side and give
 * a near-black surface light-theme alerts.
 *
 * The default is black, for the reason
 * [PlainAguiTextRenderer][dev.ynagai.agui.compose.PlainAguiTextRenderer] draws black: this module
 * sits below any design system and has no ambient text colour to read. See
 * [MarkdownAguiTextRenderer] for what that means when it is used underneath Material 3.
 */
public fun markdownAguiColors(
    text: Color = Color.Black,
    codeBackground: Color = text.copy(alpha = 0.08f),
    inlineCodeBackground: Color = text.copy(alpha = 0.08f),
    dividerColor: Color = text.copy(alpha = 0.24f),
    tableBackground: Color = text.copy(alpha = 0.04f),
    alert: MarkdownAlertColors = markdownAlertColors(darkTheme = text.luminance() > 0.15f),
): MarkdownColors = DefaultMarkdownColors(
    text = text,
    codeBackground = codeBackground,
    inlineCodeBackground = inlineCodeBackground,
    dividerColor = dividerColor,
    tableBackground = tableBackground,
    alert = alert,
)

/**
 * Markdown typography derived from one text style.
 *
 * The parser wants seventeen styles. All of them are this one with something changed: headings are
 * bold and scaled, code is monospace, a quote is italic, and the rest are [base] unaltered. The
 * heading scale is HTML's own (2, 1.5, 1.17, 1, 0.83, 0.75) rather than a type ramp of this
 * library's invention -- a Markdown document is written against that scale, and a caller who wants
 * a designed one has a `MarkdownTypography` of their own to pass.
 *
 * Pass `LocalTextStyle.current` from Material 3 and the whole transcript follows the host's type.
 */
public fun markdownAguiTypography(
    base: TextStyle = TextStyle.Default,
): MarkdownTypography {
    val size = base.fontSize.takeOrElse { DefaultFontSize }

    // An absolute line height is scaled with the font size rather than inherited. A `TextStyle`
    // carrying one in `sp` carries a number measured for its own size, and Material 3's do: the
    // `bodySmall` that `agui-material3` provides around the prose slot is 12sp of text in a 16sp
    // line. Copying that 16sp onto a 24sp `h1` puts the glyphs in a box smaller than they are, and
    // the heading collides with the lines around it.
    //
    // Only `sp`, though. An `em` line height is a multiple of the font size being scaled here, so
    // it follows the heading on its own -- scaling it too would apply the same factor twice and
    // give `h1` roughly double the leading it asked for. Unspecified likewise stays unspecified,
    // which is what `TextStyle.Default` wants: Compose then derives a line height from the font.
    val lineHeight = base.lineHeight
    fun heading(scale: Float) = base.copy(
        fontSize = size * scale,
        lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight,
        fontWeight = FontWeight.Bold,
    )
    val code = base.copy(fontFamily = FontFamily.Monospace)
    return DefaultMarkdownTypography(
        h1 = heading(2f),
        h2 = heading(1.5f),
        h3 = heading(1.17f),
        h4 = heading(1f),
        h5 = heading(0.83f),
        h6 = heading(0.75f),
        text = base,
        code = code,
        inlineCode = code,
        quote = base.copy(fontStyle = FontStyle.Italic),
        paragraph = base,
        ordered = base,
        bullet = base,
        list = base,
        textLink = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)),
        table = base,
    )
}

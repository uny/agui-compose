package dev.ynagai.agui.markdown

import androidx.compose.runtime.Immutable
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
 * The colours [MarkdownAguiTextRenderer] draws with.
 *
 * Five colours plus the GFM alert set. [text] is the one a caller actually knows; the other four
 * are backgrounds and rules that only have to read against the surface behind the transcript,
 * which is why [markdownAguiColors] derives them from [text] rather than asking for each.
 */
@Immutable
public class MarkdownColors(
    public val text: Color,
    public val codeBackground: Color,
    public val inlineCodeBackground: Color,
    public val dividerColor: Color,
    public val tableBackground: Color,
    public val alert: MarkdownAlertColors,
) {
    override fun equals(other: Any?): Boolean = other is MarkdownColors &&
        text == other.text &&
        codeBackground == other.codeBackground &&
        inlineCodeBackground == other.inlineCodeBackground &&
        dividerColor == other.dividerColor &&
        tableBackground == other.tableBackground &&
        alert == other.alert

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + codeBackground.hashCode()
        result = 31 * result + inlineCodeBackground.hashCode()
        result = 31 * result + dividerColor.hashCode()
        result = 31 * result + tableBackground.hashCode()
        result = 31 * result + alert.hashCode()
        return result
    }
}

/**
 * The accent colour of each GFM alert kind -- `> [!NOTE]` through `> [!CAUTION]`.
 *
 * Drawn as the alert's border and title. These are the one part of the palette that cannot be a
 * tint of the text colour, because an alert's whole meaning is the named hue it carries.
 */
@Immutable
public class MarkdownAlertColors(
    public val note: Color,
    public val tip: Color,
    public val important: Color,
    public val warning: Color,
    public val caution: Color,
) {
    override fun equals(other: Any?): Boolean = other is MarkdownAlertColors &&
        note == other.note &&
        tip == other.tip &&
        important == other.important &&
        warning == other.warning &&
        caution == other.caution

    override fun hashCode(): Int {
        var result = note.hashCode()
        result = 31 * result + tip.hashCode()
        result = 31 * result + important.hashCode()
        result = 31 * result + warning.hashCode()
        result = 31 * result + caution.hashCode()
        return result
    }
}

/**
 * Alert accents in the hues GitHub draws them in, one set for a light surface and one for a dark.
 *
 * Blue for a note, green for a tip, purple for important, amber for a warning, red for caution --
 * the association a reader already has from the page an agent may well have taken the alert
 * from. The values are close to GitHub's default themes rather than copied from them; what
 * matters is the hue, and a caller who wants an exact palette passes their own.
 */
public fun markdownAlertColors(darkTheme: Boolean): MarkdownAlertColors = if (darkTheme) {
    MarkdownAlertColors(
        note = Color(0xFF4493F8),
        tip = Color(0xFF3FB950),
        important = Color(0xFFAB7DF8),
        warning = Color(0xFFD29922),
        caution = Color(0xFFF85149),
    )
} else {
    MarkdownAlertColors(
        note = Color(0xFF0969DA),
        tip = Color(0xFF1A7F37),
        important = Color(0xFF8250DF),
        warning = Color(0xFF9A6700),
        caution = Color(0xFFD1242F),
    )
}

/**
 * The text styles [MarkdownAguiTextRenderer] draws with.
 *
 * Every block element that carries prose has a style here. [textLink] is a [TextLinkStyles] rather
 * than a [TextStyle] because a link is a span inside a paragraph, not a block of its own, and it
 * is the one style that has states (hovered, pressed) to describe.
 */
@Immutable
public class MarkdownTypography(
    public val h1: TextStyle,
    public val h2: TextStyle,
    public val h3: TextStyle,
    public val h4: TextStyle,
    public val h5: TextStyle,
    public val h6: TextStyle,
    public val text: TextStyle,
    public val code: TextStyle,
    public val inlineCode: TextStyle,
    public val quote: TextStyle,
    public val textLink: TextLinkStyles,
    public val table: TextStyle,
) {
    override fun equals(other: Any?): Boolean = other is MarkdownTypography &&
        h1 == other.h1 &&
        h2 == other.h2 &&
        h3 == other.h3 &&
        h4 == other.h4 &&
        h5 == other.h5 &&
        h6 == other.h6 &&
        text == other.text &&
        code == other.code &&
        inlineCode == other.inlineCode &&
        quote == other.quote &&
        textLink == other.textLink &&
        table == other.table

    override fun hashCode(): Int {
        var result = h1.hashCode()
        result = 31 * result + h2.hashCode()
        result = 31 * result + h3.hashCode()
        result = 31 * result + h4.hashCode()
        result = 31 * result + h5.hashCode()
        result = 31 * result + h6.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + code.hashCode()
        result = 31 * result + inlineCode.hashCode()
        result = 31 * result + quote.hashCode()
        result = 31 * result + textLink.hashCode()
        result = 31 * result + table.hashCode()
        return result
    }
}

/**
 * Markdown colours derived from one text colour.
 *
 * The palette is five colours plus the GFM alert set, and four of the five are backgrounds and
 * rules that only have to read against the surface behind them. Deriving them from [text] as
 * low-alpha tints means the whole set follows the one colour a caller actually knows -- pass
 * `LocalContentColor.current` and code blocks stay legible in a dark theme without a second
 * argument.
 *
 * [alert] is the sixth, and it is the one that cannot be tinted: GFM's `> [!WARNING]` blocks are
 * drawn in named accent colours. So the default here picks the set by the luminance of [text] --
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
): MarkdownColors = MarkdownColors(
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
 * Twelve styles, and all of them are this one with something changed: headings are bold and
 * scaled, code is monospace, a quote is italic, and the rest are [base] unaltered. The heading
 * scale is HTML's own (2, 1.5, 1.17, 1, 0.83, 0.75) rather than a type ramp of this library's
 * invention -- a Markdown document is written against that scale, and a caller who wants a
 * designed one has a [MarkdownTypography] of their own to pass.
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
    return MarkdownTypography(
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
        textLink = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)),
        table = base,
    )
}

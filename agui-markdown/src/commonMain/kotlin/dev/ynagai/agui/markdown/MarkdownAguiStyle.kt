package dev.ynagai.agui.markdown

import androidx.compose.ui.graphics.Color
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
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography

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
 * The parser's palette is five colours, four of which are backgrounds and rules that only have to
 * read against the surface behind them. Deriving them from [text] as low-alpha tints means the
 * whole set follows the one colour a caller actually knows -- pass `LocalContentColor.current` and
 * code blocks stay legible in a dark theme without a second argument.
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
): MarkdownColors = DefaultMarkdownColors(
    text = text,
    codeBackground = codeBackground,
    inlineCodeBackground = inlineCodeBackground,
    dividerColor = dividerColor,
    tableBackground = tableBackground,
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
    fun heading(scale: Float) = base.copy(fontSize = size * scale, fontWeight = FontWeight.Bold)
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

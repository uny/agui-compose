package dev.ynagai.agui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.markdownAlertColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The two style factories, asserted on their output rather than through a composition.
 *
 * They are pure, so they belong here rather than in `composeUiTest` -- which is also the only
 * reason this module's Android target runs a test at all: `androidHostTest` depends on
 * `commonTest`, and every drawing test lives in a source set it does not take.
 *
 * What is asserted is what a caller can see: the numbers a `MarkdownTypography` and a
 * `MarkdownColors` actually carry. The renderer tests exercise these functions through the default
 * lambdas but assert nothing about their values, so every constant in them was free to change.
 */
class MarkdownAguiStyleTest {

    /**
     * The caller's font size is the one that gets scaled.
     *
     * Not the module's fallback: the whole point of `markdownAguiTypography(base = ...)` under
     * Material 3 is that the host's type reaches the transcript, and a heading computed from
     * `DefaultFontSize` instead of `base` would discard it silently while still looking plausible.
     */
    @Test
    fun headingsScaleTheCallersFontSize() {
        val typography = markdownAguiTypography(base = TextStyle(fontSize = 20.sp))

        assertEquals(40.sp, typography.h1.fontSize)
        assertEquals(30.sp, typography.h2.fontSize)
        assertEquals(20.sp, typography.h4.fontSize)
        assertEquals(FontWeight.Bold, typography.h1.fontWeight)
    }

    /**
     * A heading twice the size gets twice the line box.
     *
     * A `TextStyle` carrying a line height carries one measured for its own size -- Material 3's
     * `bodySmall`, which `agui-material3` provides around the prose slot, is 12sp of text in a 16sp
     * line. Inheriting that 16sp onto a 24sp `h1` puts the glyphs in a box smaller than they are,
     * and the heading collides with the lines around it.
     */
    @Test
    fun headingsScaleTheLineHeightWithTheFontSize() {
        val typography = markdownAguiTypography(
            base = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        )

        assertEquals(24.sp, typography.h1.fontSize)
        assertEquals(32.sp, typography.h1.lineHeight)
        assertTrue(typography.h1.lineHeight.value >= typography.h1.fontSize.value)
    }

    /**
     * A relative line height is left alone, because it scales itself.
     *
     * `em` is a multiple of the font size, and the font size is what `heading` is scaling -- so
     * `1.5.em` on a doubled `h1` already resolves to a doubled line box. Scaling the number too
     * applies the factor twice and gives the heading roughly double the leading it asked for, which
     * is the opposite of the `sp` bug above and just as visible.
     */
    @Test
    fun aRelativeLineHeightIsNotScaledTwice() {
        val typography = markdownAguiTypography(
            base = TextStyle(fontSize = 16.sp, lineHeight = 1.5.em),
        )

        assertEquals(1.5.em, typography.h1.lineHeight)
        assertEquals(32.sp, typography.h1.fontSize)
    }

    /** An unspecified line height stays unspecified, so Compose derives one from the font. */
    @Test
    fun anUnspecifiedLineHeightIsNotInvented() {
        val typography = markdownAguiTypography()

        assertEquals(TextStyle.Default.lineHeight, typography.h1.lineHeight)
        assertEquals(28.sp, typography.h1.fontSize)
    }

    /** Code is the base style in a monospace face, and both code styles agree. */
    @Test
    fun codeIsMonospace() {
        val typography = markdownAguiTypography(base = TextStyle(fontSize = 20.sp))

        assertEquals(FontFamily.Monospace, typography.code.fontFamily)
        assertEquals(FontFamily.Monospace, typography.inlineCode.fontFamily)
        assertEquals(20.sp, typography.code.fontSize)
    }

    /** Every derived colour is the caller's text colour, tinted -- not a palette of this module's. */
    @Test
    fun coloursAreTintsOfTheTextColour() {
        val colors = markdownAguiColors(text = Color.White)

        assertEquals(Color.White, colors.text)
        assertEquals(Color.White.copy(alpha = 0.24f), colors.dividerColor)
        assertEquals(Color.White.copy(alpha = 0.08f), colors.codeBackground)
        assertEquals(Color.White.copy(alpha = 0.04f), colors.tableBackground)
    }

    /**
     * GFM alert accents follow the surface the text colour implies.
     *
     * These are the one part of the palette that cannot be a tint of [markdownAguiColors]'s `text`,
     * so the parser's own default -- its light-theme set, whatever it was handed -- would otherwise
     * leave a dark transcript with light-theme alerts.
     */
    @Test
    fun alertColoursFollowTheImpliedTheme() {
        val onDark = markdownAguiColors(text = Color.White).alert
        val onLight = markdownAguiColors(text = Color.Black).alert

        assertEquals(markdownAlertColors(darkTheme = true).warning, onDark.warning)
        assertEquals(markdownAlertColors(darkTheme = false).warning, onLight.warning)
        assertNotEquals(onLight.warning, onDark.warning)
    }

    /**
     * Dimmed light-on-dark text still reads as a dark surface.
     *
     * Black and white are the easy ends. The threshold has to sit below what a dark theme uses for
     * *secondary* text, which is where the real values are: Material 3's dark `outline` has a
     * relative luminance of ~0.28 and a hand-picked dim grey is around 0.32 -- both far below the
     * midpoint, and both light-on-dark. A threshold that split the difference between black and
     * white would hand a near-black surface the light-theme alert palette.
     */
    @Test
    fun dimmedTextOnADarkSurfaceStillGetsDarkAlerts() {
        val m3DarkOutline = markdownAguiColors(text = Color(0xFF938F99)).alert
        val dimGrey = markdownAguiColors(text = Color(0xFF9E9E9E)).alert
        val m3LightOnSurfaceVariant = markdownAguiColors(text = Color(0xFF49454F)).alert

        assertEquals(markdownAlertColors(darkTheme = true).warning, m3DarkOutline.warning)
        assertEquals(markdownAlertColors(darkTheme = true).warning, dimGrey.warning)
        assertEquals(markdownAlertColors(darkTheme = false).warning, m3LightOnSurfaceVariant.warning)
    }
}

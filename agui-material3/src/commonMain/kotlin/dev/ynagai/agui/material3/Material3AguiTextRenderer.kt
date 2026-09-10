package dev.ynagai.agui.material3

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ynagai.agui.compose.AguiTextRenderer

/**
 * Draws prose with Material 3's `Text`.
 *
 * The difference from
 * [PlainAguiTextRenderer][dev.ynagai.agui.compose.PlainAguiTextRenderer] is entirely that this one
 * can be styled from above. `BasicText` has no ambient text style, so the default renderer draws at
 * `TextStyle.Default` -- 14sp, black, whatever the theme says -- and a host that set a type scale
 * or a content colour would watch the transcript ignore both. This reads [LocalTextStyle] and the
 * ambient content colour, which is what makes the slots in [Material3AguiComponents] able to dim
 * reasoning, or a host able to enlarge the whole transcript, without replacing the renderer.
 *
 * **Still not a Markdown renderer.** The `streaming` flag is accepted and ignored, and `**bold**`
 * draws as five literal characters and a word. That is the same position `agui-compose` takes and
 * for the same reason -- `TEXT_MESSAGE_CONTENT` carries a string and says nothing about its syntax,
 * so which flavour to parse is the application's decision -- and this module does not overturn it
 * merely by being one layer higher. What it does is leave the seam intact: an application that
 * wants Markdown provides its own [AguiTextRenderer] and keeps every slot in this module, because
 * the prose slots here draw *through* [LocalAguiTextRenderer][dev.ynagai.agui.compose.LocalAguiTextRenderer]
 * rather than calling `Text` themselves.
 */
public object Material3AguiTextRenderer : AguiTextRenderer {
    @Composable
    override fun Render(text: String, streaming: Boolean, modifier: Modifier) {
        Text(text = text, modifier = modifier)
    }
}

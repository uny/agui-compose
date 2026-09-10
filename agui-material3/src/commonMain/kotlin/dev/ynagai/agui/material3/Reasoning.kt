package dev.ynagai.agui.material3

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.ynagai.agui.compose.LocalAguiTextRenderer
import dev.ynagai.agui.model.ReasoningPart

/**
 * The agent's chain of thought, behind a disclosure.
 *
 * `agui-compose` draws reasoning expanded and identical to prose, and says in as many words that
 * collapsing it is a design-system decision belonging one layer up. This is that layer, so this is
 * where the decision gets made -- and made the way the treatment is conventionally done rather than
 * the way that is simplest to implement.
 *
 * **It follows the stream, then gets out of the way.** While the reasoning deltas are still
 * arriving the text is open, because watching an agent think is the entire value of showing
 * reasoning at all; when the run ends it collapses, because the finished answer is what the
 * reader came for and a wall of superseded thinking above it is noise. The moment the reader
 * touches the header that automatic behaviour stops for that part and their choice stands, which is
 * what the nullable state below means: `null` is "follow the stream", and a click replaces it with
 * an answer that no longer changes on its own.
 *
 * The state is `remember`, not `rememberSaveable`, so a long transcript scrolled far enough for
 * this part to leave the `LazyColumn`'s composition forgets an expansion the reader chose. That is
 * a real limitation rather than an oversight: `rememberSaveable` would add
 * `compose-runtime-saveable` to a module whose whole argument is that it adds only Material 3, to
 * buy back a case -- reader expands finished reasoning, scrolls past it, scrolls back -- that
 * `agui-compose` does not have a way to be asked about. An application that needs it hoists the
 * state and replaces this slot.
 */
@Composable
internal fun Material3Reasoning(part: ReasoningPart, modifier: Modifier) {
    // `null` means nobody has chosen, so the stream decides. Any non-null value is the reader's.
    var chosen: Boolean? by remember { mutableStateOf(null) }
    val expanded = chosen ?: part.streaming

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AguiSpacing.betweenParts),
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    onClickLabel = if (expanded) AguiStrings.HIDE else AguiStrings.SHOW,
                    role = Role.Button,
                ) { chosen = !expanded }
                .padding(vertical = AguiSpacing.betweenParts),
            horizontalArrangement = Arrangement.spacedBy(AguiSpacing.insideRow),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // `title` is the protocol's own label for the run of reasoning when it sent one --
                // "Analysing the schema" rather than the generic word.
                text = part.title ?: AguiStrings.REASONING,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Capped for the same reason a tool call's name is: the agent chose this string
                // and its length, and a `Row` measures its unweighted children in order against
                // whatever width is left. Uncapped, a long title takes the whole row and the label
                // beside it measures at zero -- leaving exactly the handle-less disclosure this
                // treatment exists to avoid.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = if (expanded) AguiStrings.HIDE else AguiStrings.SHOW,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (expanded) {
            // Dimmed from outside the renderer rather than by passing it a colour. The renderer is
            // the Markdown seam and may be somebody else's; styling reasoning through the ambient
            // content colour and text style means a Markdown implementation fitted here inherits
            // the treatment instead of having to reimplement it.
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                    LocalAguiTextRenderer.current.Render(part.text, part.streaming, Modifier)
                }
            }
        }
    }
}

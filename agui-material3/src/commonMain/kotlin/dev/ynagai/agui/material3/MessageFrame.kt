package dev.ynagai.agui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiRole

/**
 * The frame one message's parts sit in, chosen by role.
 *
 * Four treatments for five roles:
 *
 * - **User** -- a tonal bubble, aligned to the end. The one role whose messages a reader scans for
 *   when scrolling back, and the one whose text is always short enough for a bubble to be the right
 *   shape.
 * - **Assistant** -- no container at all, full width. An answer is the page rather than a card on
 *   it, and a bubble around a long response with code and tool calls in it wastes the width the
 *   response needs. This is also why the default is not symmetric: the two roles are not doing the
 *   same thing.
 * - **System and developer** -- dimmed and small, with a label. Usually not shown to a reader; when
 *   they are, what matters is that they are visibly not part of the conversation.
 * - **Activity** -- like the assistant's, because the part inside already draws its own container.
 *
 * [modifier] lands on the root of whichever treatment is chosen, which is what `agui-compose`'s
 * `AguiMessage` promises its callers and the reason each branch below starts from it rather than
 * from `Modifier`.
 */
@Composable
internal fun Material3MessageFrame(
    message: UiMessage,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    when (message.role) {
        UiRole.USER -> Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = AguiSpacing.betweenMessages),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                // `fill = false` is what makes this a bubble rather than a full-width band: the
                // surface takes the width its text needs and is capped at the row's, so a short
                // message stays short and a long one wraps instead of overflowing.
                modifier = Modifier.weight(1f, fill = false),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Parts(Modifier.padding(AguiSpacing.insideContainer), content)
            }
        }

        UiRole.ASSISTANT, UiRole.ACTIVITY -> Parts(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = AguiSpacing.betweenMessages),
            content = content,
        )

        UiRole.SYSTEM, UiRole.DEVELOPER -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = AguiSpacing.betweenMessages),
            verticalArrangement = Arrangement.spacedBy(AguiSpacing.betweenParts),
        ) {
            Text(
                text = if (message.role == UiRole.SYSTEM) {
                    AguiStrings.SYSTEM
                } else {
                    AguiStrings.DEVELOPER
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Dimmed through the ambient content colour and text style rather than by passing
            // either down: the parts are drawn by slots this frame does not know the identity of,
            // including a text renderer the application may have replaced.
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                    Parts(Modifier, content)
                }
            }
        }
    }
}

/** The parts of one message, stacked with this module's spacing between them. */
@Composable
private fun Parts(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AguiSpacing.betweenParts),
    ) {
        content()
    }
}

package dev.ynagai.agui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus

/**
 * A tool call: its name, how far it has got, and nothing else.
 *
 * The arguments stay off screen, which is `agui-compose`'s decision kept rather than revisited --
 * they arrive as JSON fragments that are not parseable until `TOOL_CALL_END`, so a default that
 * showed them would put a half-written object in front of a reader. What Material 3 adds is that
 * the state is legible without reading an enum name: a spinner while the call is in flight, and
 * the error colour when the client reported it failed.
 *
 * A [Surface] rather than a `Card`. Both would draw a container; the card's elevation implies the
 * tool call is a thing to attend to and act on, and it is neither -- it is a footnote in the middle
 * of an answer. A tonal surface separates it from the prose above and below without lifting it off
 * the page.
 *
 * The result is not drawn either, and that is a gap rather than a decision:
 * [ToolCallPart.result] and [ToolCallPart.error] both carry text this could show. Showing them well
 * means deciding how much of an arbitrarily long tool result belongs inline, which is an
 * application's decision about its own tools, and showing them badly means an unbounded string in
 * the middle of a transcript. The status word is what this module is willing to claim.
 */
@Composable
internal fun Material3ToolCall(part: ToolCallPart, modifier: Modifier) {
    val failed = part.status == ToolCallStatus.FAILED
    val running = part.status == ToolCallStatus.STREAMING_ARGUMENTS ||
        part.status == ToolCallStatus.AWAITING_RESULT

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (failed) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(AguiSpacing.insideContainer),
            horizontalArrangement = Arrangement.spacedBy(AguiSpacing.insideRow),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                // Sized down to the type beside it rather than left at the default 48dp, which
                // would make the spinner the largest thing in a row meant to read as one line.
                // `strokeWidth` scales with it or the ring closes into a disc.
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = part.name,
                style = MaterialTheme.typography.labelLarge,
                // A tool name is a symbol from someone else's namespace and has no length limit.
                // One line, ellipsised, so a long one cannot push the status off the row.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = AguiStrings.toolCallStatus(part.status),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

package dev.ynagai.agui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.ynagai.agui.model.FilePart

/**
 * An attachment, as a name and a type.
 *
 * Not as a picture, even when the MIME type says it is one. Drawing the image means decoding
 * [FilePart.data] from base64 or fetching [FilePart.url], and fetching means an image loader --
 * Coil, Kamel, something -- which is a dependency with a network stack, a disk cache and an opinion
 * about lifecycles. Putting one in a Material 3 module would mean every consumer who wanted styled
 * text got an HTTP client with it. That is exactly the trade `agui-compose` refuses over Markdown
 * and it does not become a better trade one layer up.
 *
 * So this draws the affordance an application replaces: the file is visibly present and named, and
 * the slot to `copy` is the one that would show it.
 */
@Composable
internal fun Material3File(part: FilePart, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(AguiSpacing.insideContainer),
            verticalArrangement = Arrangement.spacedBy(AguiSpacing.betweenParts),
        ) {
            Text(
                // The protocol does not require a filename. Falling back to the MIME type keeps
                // the row from being empty, and the second line is then a duplicate -- which reads
                // better than a blank where a name should be.
                text = part.filename ?: part.mimeType,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = part.mimeType,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

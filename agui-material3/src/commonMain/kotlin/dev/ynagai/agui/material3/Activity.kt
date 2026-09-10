package dev.ynagai.agui.material3

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ynagai.agui.model.ActivityPart

/**
 * An `ACTIVITY_SNAPSHOT` stream, named and not interpreted.
 *
 * This slot draws less than every other one here, and deliberately. [ActivityPart.content] is an
 * opaque `JsonElement` because the protocol's `activityType` is an open field: `a2ui-surface` is
 * one value of it, and a payload under that value is a whole generative-UI document that needs a
 * renderer which understands the dialect. Material 3 is a design system, not a second protocol, so
 * what it can honestly contribute is a container that says something arrived and what kind of thing
 * it claimed to be.
 *
 * `agui-a2ui` is the module that replaces this. Until it exists, an application whose agent emits
 * activities sees the type name -- which is the honest failure mode, and visibly different from the
 * empty composition that a slot drawing nothing would produce.
 */
@Composable
internal fun Material3Activity(part: ActivityPart, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = part.activityType,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(AguiSpacing.insideContainer),
        )
    }
}

package dev.ynagai.agui.a2ui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiPlaceholder
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.NoPlaceholder
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.a2ui.material3.Material3Components
import dev.ynagai.agui.a2ui.A2uiPayload
import dev.ynagai.agui.a2ui.AguiA2ui
import dev.ynagai.agui.a2ui.compose.A2uiHost
import dev.ynagai.agui.a2ui.compose.A2uiPendingRenderer
import dev.ynagai.agui.a2ui.compose.withA2ui
import dev.ynagai.agui.compose.AguiComponents

/**
 * The state before, or instead of, a surface, in Material 3: a spinner and a label while the
 * agent generates, and an error container when it gave up or sent something unreadable.
 *
 * The same shape as `agui-material3`'s tool-call row, deliberately: a surface being built is a
 * tool at work, and the two read as one kind of thing when they sit in one transcript.
 */
public object Material3A2uiPending : A2uiPendingRenderer {
    @Composable
    override fun Render(payload: A2uiPayload, modifier: Modifier) {
        val failed = payload is A2uiPayload.Failed || payload is A2uiPayload.Malformed
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!failed) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Column {
                    Text(
                        text = when (payload) {
                            is A2uiPayload.Building -> "Building UI"
                            is A2uiPayload.Retrying -> payload.maxAttempts?.let { max ->
                                "Retrying (${payload.attempt ?: "?"}/$max)"
                            } ?: "Retrying"
                            is A2uiPayload.Failed -> "UI generation failed"
                            is A2uiPayload.Malformed -> "UI could not be read"
                            is A2uiPayload.Surfaces -> ""
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    val detail = when (payload) {
                        is A2uiPayload.Failed -> payload.error
                        is A2uiPayload.Malformed -> payload.reason
                        else -> null
                    }
                    if (detail != null) Text(text = detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * [withA2ui] with Material 3 filled in: the basic catalog's renderers and [Material3A2uiPending].
 *
 * @param registry the component renderers. Defaults to the basic catalog; a host with its own
 *   catalog passes `Material3Components.Basic.with(...)`, keeping the basic ones underneath.
 */
public fun AguiComponents.withMaterial3A2ui(
    host: A2uiHost,
    registry: ComponentRegistry = Material3Components.Basic,
    onMessage: (RendererToAgentMessage) -> Unit = {},
    placeholder: A2uiPlaceholder = NoPlaceholder,
    toolNames: Set<String> = setOf(AguiA2ui.RENDER_TOOL_NAME),
): AguiComponents = withA2ui(
    host = host,
    registry = registry,
    onMessage = onMessage,
    pending = Material3A2uiPending,
    placeholder = placeholder,
    toolNames = toolNames,
)

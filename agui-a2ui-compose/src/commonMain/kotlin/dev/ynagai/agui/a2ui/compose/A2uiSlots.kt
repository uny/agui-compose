package dev.ynagai.agui.a2ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.compose.A2uiPlaceholder
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.NoPlaceholder
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.agui.a2ui.A2uiCarrier
import dev.ynagai.agui.a2ui.A2uiPayload
import dev.ynagai.agui.a2ui.A2uiSlot
import dev.ynagai.agui.a2ui.AguiA2ui
import dev.ynagai.agui.compose.AguiComponents
import dev.ynagai.agui.model.ToolCallPart

/**
 * What to draw for a payload that is not a surface: the lifecycle before the paint, a failure,
 * or a payload the client could not read.
 */
public fun interface A2uiPendingRenderer {
    @Composable
    public fun Render(payload: A2uiPayload, modifier: Modifier)
}

/**
 * The default: the state's name, and nothing else. Structure, not design -- what `AguiComponents`'
 * own defaults draw, and for the same reason. A design system puts a progress bar here.
 */
public val BasicPending: A2uiPendingRenderer = A2uiPendingRenderer { payload, modifier ->
    BasicText(
        text = when (payload) {
            is A2uiPayload.Building -> "building"
            is A2uiPayload.Retrying -> "retrying"
            is A2uiPayload.Failed -> "failed: ${payload.error}"
            is A2uiPayload.Malformed -> "malformed: ${payload.reason}"
            is A2uiPayload.Surfaces -> "" // Never pending; the type admits it, so it is spelled.
        },
        modifier = modifier,
    )
}

/**
 * This slot table with the `activity` and `toolCall` slots drawing A2UI through [host].
 *
 * An activity slot that finds its carrier in [host] draws its surfaces, or its pending state;
 * one that does not -- an activity of some other type -- falls through to the slot it replaced,
 * so a table that already drew activities keeps drawing the ones this module does not read.
 *
 * A tool-call slot is the same with two carriers to look up, the arguments and the result, in
 * that order of preference when both hold surfaces... which they do not: the reconciler gives
 * a surface to one carrier, so at most one of the two is [A2uiSlot.Surfaces]. A render call
 * whose surface an activity owns is [A2uiSlot.Shadowed] and draws *nothing* -- not the tool call
 * either, because "render_a2ui (awaiting result)" under a surface that is on screen would be a
 * status line for the thing above it. Any other tool call whose result an activity shadows keeps
 * its ordinary rendering: its result is still a result, and the surface is elsewhere.
 *
 * `remember` the table this returns, as [AguiComponents] says: the slots capture [host].
 *
 * @param registry what draws each component type. Must cover the catalogs the host's renderer
 *   holds, or the uncovered types draw as the registry's placeholder.
 * @param onMessage where a surface's actions go. Nothing is sent anywhere by this module;
 *   `ActionMessage.toForwardedProps` and `toUserText` in `agui-a2ui` are the two shapes upstream
 *   reads them in.
 * @param pending what to draw for a payload that is not yet, or not at all, a surface.
 * @param placeholder what `a2ui-compose` draws for a component type the registry lacks.
 * @param toolNames the tools whose calls draw nothing when shadowed. The host's own set.
 */
public fun AguiComponents.withA2ui(
    host: A2uiHost,
    registry: ComponentRegistry,
    onMessage: (RendererToAgentMessage) -> Unit = {},
    pending: A2uiPendingRenderer = BasicPending,
    placeholder: A2uiPlaceholder = NoPlaceholder,
    toolNames: Set<String> = setOf(AguiA2ui.RENDER_TOOL_NAME),
): AguiComponents {
    val previousActivity = activity
    val previousToolCall = toolCall
    return copy(
        activity = { part, modifier ->
            when (val slot = host.slot(A2uiCarrier.Activity(part.messageId))) {
                null -> previousActivity(part, modifier)
                else -> A2uiSlotContent(host, A2uiCarrier.Activity(part.messageId), slot, registry, onMessage, pending, placeholder, modifier)
            }
        },
        toolCall = { part, modifier ->
            val arguments = A2uiCarrier.ToolArguments(part.toolCallId)
            val result = A2uiCarrier.ToolResult(part.toolCallId)
            val argumentsSlot = host.slot(arguments)
            val resultSlot = host.slot(result)
            when {
                resultSlot is A2uiSlot.Surfaces ->
                    A2uiSlotContent(host, result, resultSlot, registry, onMessage, pending, placeholder, modifier)
                argumentsSlot is A2uiSlot.Surfaces ->
                    A2uiSlotContent(host, arguments, argumentsSlot, registry, onMessage, pending, placeholder, modifier)
                argumentsSlot is A2uiSlot.Pending ->
                    A2uiSlotContent(host, arguments, argumentsSlot, registry, onMessage, pending, placeholder, modifier)
                argumentsSlot is A2uiSlot.Shadowed && part.name in toolNames -> Unit
                resultSlot is A2uiSlot.Pending && part.isDone ->
                    A2uiSlotContent(host, result, resultSlot, registry, onMessage, pending, placeholder, modifier)
                else -> previousToolCall(part, modifier)
            }
        },
    )
}

/** A result that is malformed is worth showing only once the call is over and it is final. */
private val ToolCallPart.isDone: Boolean get() = result != null

@Composable
private fun A2uiSlotContent(
    host: A2uiHost,
    carrier: A2uiCarrier,
    slot: A2uiSlot,
    registry: ComponentRegistry,
    onMessage: (RendererToAgentMessage) -> Unit,
    pending: A2uiPendingRenderer,
    placeholder: A2uiPlaceholder,
    modifier: Modifier,
) {
    host.rejected[carrier]?.let { reason ->
        pending.Render(A2uiPayload.Malformed(reason, kotlinx.serialization.json.JsonNull), modifier)
        return
    }
    when (slot) {
        is A2uiSlot.Surfaces -> Column(modifier) {
            for (surfaceId in slot.surfaceIds) {
                key(surfaceId) {
                    A2uiSurface(
                        renderer = host.renderer,
                        surfaceId = surfaceId,
                        registry = registry,
                        placeholder = placeholder,
                        onMessage = onMessage,
                    )
                }
            }
        }
        is A2uiSlot.Pending -> pending.Render(slot.payload, modifier)
        is A2uiSlot.Shadowed -> Unit
    }
}

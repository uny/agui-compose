package dev.ynagai.agui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ynagai.agui.model.UiInterrupt
import dev.ynagai.agui.model.UiResumeEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A question the run stopped to ask, with two answers: yes and no.
 *
 * This is the one surface in the module that *is* a thing to attend to and act on -- the tool call
 * is a footnote, the interrupt is the reason nothing else is happening -- so it is drawn in the
 * primary container, which is the loudest ground Material 3 offers without an elevation the rest
 * of the transcript would sit oddly beside.
 *
 * **What the buttons send.** The protocol carries [UiInterrupt.responseSchema] so a consumer can
 * build a form for the answer, and this does not build one: a form generated from an arbitrary
 * JSON Schema is a library of its own, and the schema that is actually on the wire -- AWS
 * Strands' tool approval, and nothing else at the time of writing -- is `{ approved: boolean }`.
 * So the affirmative resolves with `{"approved": true}` when the schema names an `approved`
 * property and with no payload otherwise, which is what a producer that asked for a bare
 * go-ahead reads as one; the negative *abandons* the interrupt rather than resolving it with
 * `false`, because abandoning is the answer every producer has to accept, and a producer that
 * distinguishes the two has been told the honest thing, that the reader declined to answer
 * (Strands answers its own approval hook `{"approved": false}` for it, and the call gets a
 * denial for its result; a generic Strands interrupt gets the `cancelled` envelope). A question
 * whose schema wants more than a boolean gets these same two buttons, and an application with
 * such a producer replaces this slot with the form its schema deserves.
 *
 * The prompt is [UiInterrupt.message] when the producer wrote one; the tool's name from
 * [UiInterrupt.metadata] when it did not but named a tool the Strands way; and the bare
 * [UiInterrupt.reason] as a last resort, because a card with no words on it is worse than one
 * with a producer's identifier on it.
 */
@Composable
internal fun Material3Interrupt(
    interrupt: UiInterrupt,
    onResume: (UiResumeEntry) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(AguiSpacing.insideContainer),
            verticalArrangement = Arrangement.spacedBy(AguiSpacing.insideRow),
        ) {
            Text(
                text = interrupt.prompt(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AguiSpacing.insideRow, Alignment.End),
            ) {
                OutlinedButton(onClick = { onResume(UiResumeEntry.cancelled(interrupt)) }) {
                    Text(AguiStrings.DECLINE)
                }
                Button(onClick = { onResume(UiResumeEntry.resolved(interrupt, interrupt.approval())) }) {
                    Text(AguiStrings.APPROVE)
                }
            }
        }
    }
}

private fun UiInterrupt.prompt(): String =
    message
        ?: metadata?.jsonObjectOrNull()?.get("tool_name")?.stringOrNull()?.let { AguiStrings.approveCall(it) }
        ?: reason

/** The affirmative answer [responseSchema] asks for, or nothing when it asks for no field this knows. */
private fun UiInterrupt.approval(): JsonElement? {
    val properties = responseSchema?.jsonObjectOrNull()?.get("properties")?.jsonObjectOrNull() ?: return null
    if ("approved" !in properties) return null
    return buildJsonObject { put("approved", JsonPrimitive(true)) }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

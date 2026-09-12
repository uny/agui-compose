package dev.ynagai.agui.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.agui.a2ui.A2uiBatch
import dev.ynagai.agui.a2ui.A2uiCarrier
import dev.ynagai.agui.a2ui.A2uiSlot
import dev.ynagai.agui.a2ui.A2uiSurfaces
import dev.ynagai.agui.a2ui.A2uiTranslation
import dev.ynagai.agui.a2ui.AguiA2ui
import dev.ynagai.agui.a2ui.a2uiPayloads
import dev.ynagai.agui.model.UiTranscript

/**
 * The A2UI surfaces of one transcript, kept in an [A2uiRenderer] and drawn where the transcript's
 * parts are.
 *
 * Holds the [A2uiSurfaces] bookkeeping and applies each step to [renderer] as it comes. The slot
 * table [slots] is what the drawing side reads: [withA2ui] looks up the carrier a part maps to,
 * and draws the surfaces, the lifecycle, or nothing, as the reconciler decided.
 *
 * A batch the renderer throws on -- a data-model path that is not a JSON Pointer, say -- is
 * reported through [onWarning], taken back from the bookkeeping, and shown in its slot as the
 * message the renderer gave. The other carriers' surfaces stand.
 *
 * One host per transcript, and one renderer per host: [A2uiSurfaces] assumes it is the only thing
 * creating surfaces in the renderer it plans for, and a second host sharing the renderer would
 * delete the first one's surfaces as strangers.
 *
 * @param renderer where the surfaces live. Its catalogs decide which catalog ids resolve.
 * @param translation how upstream's envelopes become v1.0; the default remaps the basic catalog.
 * @param toolNames the tools whose arguments are surfaces.
 * @param onWarning where a rejected batch is reported. Nothing is thrown at a composition.
 */
@Stable
public class A2uiHost(
    public val renderer: A2uiRenderer,
    private val translation: A2uiTranslation = A2uiTranslation.Default,
    private val toolNames: Set<String> = setOf(AguiA2ui.RENDER_TOOL_NAME),
    private val onWarning: (String) -> Unit = {},
) {
    private var surfaces: A2uiSurfaces = A2uiSurfaces.Empty

    /** What to draw for each carrier, as of the last [accept]. Reading it subscribes to changes. */
    public var slots: Map<A2uiCarrier, A2uiSlot> by mutableStateOf(emptyMap())
        private set

    /** The batches the renderer refused, by carrier, with what it said. */
    public var rejected: Map<A2uiCarrier, String> by mutableStateOf(emptyMap())
        private set

    /** The slot for [carrier], or null when the transcript holds no such carrier. */
    public fun slot(carrier: A2uiCarrier): A2uiSlot? = slots[carrier]

    /**
     * Brings [renderer] up to [transcript]. Idempotent: the same transcript twice is no work.
     *
     * Call from the thread that owns the renderer's snapshot state -- in Compose, the main one.
     */
    public fun accept(transcript: UiTranscript) {
        val step = surfaces.accept(transcript.a2uiPayloads(translation, toolNames))
        var next = step.next
        val failures = rejected.toMutableMap()
        if (step.deletes.isNotEmpty()) renderer.applyAll(step.deletes)
        for (batch in step.batches) {
            failures -= batch.carrier
            val failure = apply(batch)
            if (failure != null) {
                next = next.rejected(batch)
                failures[batch.carrier] = failure
                onWarning("A2UI ${batch.carrier}: $failure")
            }
        }
        // A carrier that left the transcript takes its failure with it.
        failures.keys.retainAll(step.slots.keys)
        surfaces = next
        rejected = failures
        slots = step.slots
    }

    /** Applies [batch] atomically; the renderer's message when it refused, or null. */
    private fun apply(batch: A2uiBatch): String? = try {
        renderer.applyAll(batch.messages)
        null
    } catch (e: RuntimeException) {
        // `A2uiStateException` for an order violation, `A2uiFormatException` for a pointer that
        // is not one; both are `RuntimeException`s, and a third the renderer may grow is caught
        // for the same reason -- a composition is not the place an agent's payload gets to throw.
        e.message ?: e::class.simpleName ?: "rejected"
    }
}

/**
 * An [A2uiHost] for [renderer], kept up to date with [transcript].
 *
 * The host is remembered against the renderer, so a new transcript value is a new [A2uiHost.accept]
 * and not a new host. The update runs in a [LaunchedEffect], one frame after the transcript
 * changed: the renderer's state is snapshot state, and writing it during composition is what the
 * effect exists to avoid.
 */
@Composable
public fun rememberA2uiHost(
    transcript: UiTranscript,
    renderer: A2uiRenderer,
    translation: A2uiTranslation = A2uiTranslation.Default,
    toolNames: Set<String> = setOf(AguiA2ui.RENDER_TOOL_NAME),
    onWarning: (String) -> Unit = {},
): A2uiHost {
    val host = remember(renderer, translation, toolNames) { A2uiHost(renderer, translation, toolNames, onWarning) }
    LaunchedEffect(host, transcript) { host.accept(transcript) }
    return host
}

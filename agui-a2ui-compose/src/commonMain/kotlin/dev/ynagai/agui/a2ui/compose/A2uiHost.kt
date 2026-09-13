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
 * reported through [onWarning], recorded as refused so it is not retried until its payload
 * changes, and shown in its slot as the message the renderer gave. The other carriers' surfaces
 * stand.
 *
 * One host per transcript, and one renderer per host: [A2uiSurfaces] assumes it is the only thing
 * creating surfaces in the renderer it plans for, and a second host sharing the renderer would
 * delete the first one's surfaces as strangers. Which is why the three settings are `var`s and
 * not construction-time: a host rebuilt for a new [translation] would start with empty books
 * against a renderer that still holds every surface, and its first step would try to create
 * what exists. Change the setting instead and [accept] again; the reconciler sees the payloads
 * that changed and redraws those.
 *
 * @param renderer where the surfaces live. Its catalogs decide which catalog ids resolve.
 * @param translation how upstream's envelopes become v1.0; the default remaps the basic catalog.
 * @param toolNames the tools whose arguments are surfaces.
 * @param onWarning where a rejected batch is reported. Nothing is thrown at a composition.
 */
@Stable
public class A2uiHost(
    public val renderer: A2uiRenderer,
    public var translation: A2uiTranslation = A2uiTranslation.Default,
    public var toolNames: Set<String> = setOf(AguiA2ui.RENDER_TOOL_NAME),
    public var onWarning: (String) -> Unit = {},
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
        // A failure stands only while the carrier still claims surfaces with the same payload. A
        // carrier that left the transcript takes its failure with it, and so does one whose
        // payload moved on to something that is not a batch -- the middleware's `retrying` after
        // a paint the renderer refused, or a surface another carrier took over -- since the slot
        // now has its own thing to say and the old error would be said over it.
        failures.keys.retainAll { step.slots[it] is A2uiSlot.Surfaces }
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
 * The host is remembered against the renderer alone, so a new transcript value -- or a new
 * [translation], [toolNames] or [onWarning], which a caller commonly builds inline -- is a new
 * [A2uiHost.accept] on the same host and not a new host with empty books against a renderer that
 * still holds every surface. The update runs in a [LaunchedEffect], one frame after the
 * transcript changed: the renderer's state is snapshot state, and writing it during composition
 * is what the effect exists to avoid.
 */
@Composable
public fun rememberA2uiHost(
    transcript: UiTranscript,
    renderer: A2uiRenderer,
    translation: A2uiTranslation = A2uiTranslation.Default,
    toolNames: Set<String> = setOf(AguiA2ui.RENDER_TOOL_NAME),
    onWarning: (String) -> Unit = {},
): A2uiHost {
    val host = remember(renderer) { A2uiHost(renderer, translation, toolNames, onWarning) }
    host.translation = translation
    host.toolNames = toolNames
    host.onWarning = onWarning
    LaunchedEffect(host, transcript, translation, toolNames) { host.accept(transcript) }
    return host
}

package dev.ynagai.agui.a2ui

import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.protocol.DeleteSurfaceMessage
import kotlinx.serialization.json.JsonNull

/**
 * What a renderer should draw for one carrier, decided by [A2uiSurfaces].
 */
public sealed interface A2uiSlot {
    /** Draw these surfaces; this carrier owns them. */
    public data class Surfaces(public val surfaceIds: List<String>) : A2uiSlot

    /** Nothing to draw yet, or nothing drawable: show the state instead. Never [A2uiPayload.Surfaces]. */
    public data class Pending(public val payload: A2uiPayload) : A2uiSlot

    /**
     * Every surface this carrier holds is owned by [by]. Draw nothing: the surface is on screen
     * once, where [by] is, and a second copy under the tool call would be the same surface twice.
     */
    public data class Shadowed(public val by: A2uiCarrier) : A2uiSlot
}

/** The messages to apply for one carrier, as one atomic batch. */
public data class A2uiBatch(
    public val carrier: A2uiCarrier,
    public val messages: List<AgentToRendererMessage>,
)

/**
 * The surfaces a transcript amounts to, and how to get a renderer from the last transcript to
 * this one.
 *
 * `a2ui-core` refuses to create a surface that exists, and upstream's middleware re-sends
 * `createSurface` in every cumulative snapshot -- so a renderer fed the transcript's payloads as
 * they came would throw on the second snapshot. This type sits between: it remembers what each
 * carrier last had applied, and when a carrier's payload changes it deletes the surfaces that
 * carrier created and replays the new payload whole. Delete-then-replay rather than a diff,
 * because a snapshot is one document -- the specification's normative reading of `replace` -- and
 * the cost is a redraw of one surface, which is what a new snapshot means anyway.
 *
 * Immutable: [accept] returns the [Step] to the next value, and the caller that applied the
 * step's messages keeps [Step.next]. A caller whose renderer rejected a batch keeps [rejected]
 * instead, so this value never claims a surface the renderer does not hold.
 *
 * The caller applies [Step.deletes] first and then each [Step.batches] entry as one atomic
 * `applyAll`. `A2uiRenderer.applyAll` leaves its state untouched when a batch throws, which is
 * why the batches are per carrier rather than one list: a payload one carrier got wrong costs
 * that carrier's surfaces and nobody else's.
 */
public class A2uiSurfaces private constructor(
    /** What each carrier had applied, keyed by carrier. */
    private val applied: Map<A2uiCarrier, List<AgentToRendererMessage>>,
    /** The surfaces the renderer holds, keyed by id, with the carrier that created each. */
    private val live: Map<String, A2uiCarrier>,
) {
    /** The surfaces the renderer holds after the last step was applied. */
    public val surfaceIds: Set<String> get() = live.keys

    /** How to get from this value to [next]. */
    public class Step internal constructor(
        public val next: A2uiSurfaces,
        /** Apply first. Every surface here is one the renderer holds. */
        public val deletes: List<DeleteSurfaceMessage>,
        /** Then each of these, atomically and in order. */
        public val batches: List<A2uiBatch>,
        /** What to draw for every carrier in the transcript, in transcript order. */
        public val slots: Map<A2uiCarrier, A2uiSlot>,
    ) {
        /** True when the renderer has nothing to do -- the usual case for a text-only event. */
        public val isEmpty: Boolean get() = deletes.isEmpty() && batches.isEmpty()
    }

    /**
     * The step from this value to the one that reflects [carried] -- the transcript's payloads,
     * in transcript order, as [a2uiPayloads] lists them.
     */
    public fun accept(carried: List<A2uiCarried>): Step {
        // One owner per surface: the carrier of highest precedence, and among equals the latest
        // in the transcript. Recomputed from scratch on every step so that the order the carriers
        // arrived in does not decide who owns what -- with the middleware, the activity's first
        // paint and the outer tool's result land in either order.
        val owner = mutableMapOf<String, A2uiCarrier>()
        for ((carrier, payload) in carried) {
            if (payload !is A2uiPayload.Surfaces) continue
            for (surfaceId in payload.surfaceIds) {
                val current = owner[surfaceId]
                if (current == null || carrier.precedence >= current.precedence) owner[surfaceId] = carrier
            }
        }

        // What each carrier would apply: its messages, minus those for surfaces it does not own.
        val slots = LinkedHashMap<A2uiCarrier, A2uiSlot>()
        val wanted = LinkedHashMap<A2uiCarrier, List<AgentToRendererMessage>>()
        for ((carrier, payload) in carried) {
            if (payload !is A2uiPayload.Surfaces) {
                slots[carrier] = A2uiSlot.Pending(payload)
                continue
            }
            val owned = payload.surfaceIds.filter { owner[it] == carrier }
            if (owned.isEmpty()) {
                val by = payload.surfaceIds.firstNotNullOfOrNull { owner[it] }
                slots[carrier] = if (by != null) A2uiSlot.Shadowed(by) else A2uiSlot.Pending(payload)
                continue
            }
            val messages = payload.messages.filter { it.surfaceIdOrNull() in owned }
            val outOfOrder = outOfOrder(messages)
            if (outOfOrder != null) {
                slots[carrier] = A2uiSlot.Pending(
                    A2uiPayload.Malformed("surface `$outOfOrder` is updated before it is created", JsonNull),
                )
                continue
            }
            wanted[carrier] = messages
            slots[carrier] = A2uiSlot.Surfaces(owned)
        }

        // A carrier whose messages changed -- or that is gone -- gives up what it created. A
        // surface that changed hands is deleted through its old owner and recreated by the new.
        val deletes = LinkedHashSet<String>()
        val batches = mutableListOf<A2uiBatch>()
        val nextApplied = LinkedHashMap<A2uiCarrier, List<AgentToRendererMessage>>()
        val nextLive = LinkedHashMap(live)
        for ((carrier, before) in applied) {
            if (wanted[carrier] == before) continue
            for ((surfaceId, creator) in live) if (creator == carrier) deletes += surfaceId
        }
        for ((carrier, messages) in wanted) {
            val before = applied[carrier]
            if (before == messages) {
                nextApplied[carrier] = before
                continue
            }
            // Live and not scheduled for deletion means another carrier still holds it -- a
            // change of owner from one that did not itself change. Take it down first.
            for (message in messages) if (message is CreateSurfaceMessage) {
                if (message.surfaceId in nextLive) deletes += message.surfaceId
            }
            batches += A2uiBatch(carrier, messages)
            nextApplied[carrier] = messages
        }
        for (surfaceId in deletes) nextLive -= surfaceId
        for (batch in batches) {
            for (message in batch.messages) {
                when (message) {
                    is CreateSurfaceMessage -> nextLive[message.surfaceId] = batch.carrier
                    is DeleteSurfaceMessage -> nextLive -= message.surfaceId
                    else -> Unit
                }
            }
        }
        return Step(
            next = A2uiSurfaces(nextApplied, nextLive),
            deletes = deletes.map { DeleteSurfaceMessage(it) },
            batches = batches,
            slots = slots,
        )
    }

    /**
     * This value with [batch] taken back: the renderer threw on it and, since `applyAll` leaves
     * its state alone when it throws, holds none of the batch's surfaces. Call on [Step.next].
     */
    public fun rejected(batch: A2uiBatch): A2uiSurfaces = A2uiSurfaces(
        applied = applied - batch.carrier,
        live = live.filterValues { it != batch.carrier },
    )

    /** The first surface a message updates before any message creates it, or null. */
    private fun outOfOrder(messages: List<AgentToRendererMessage>): String? {
        val created = mutableSetOf<String>()
        for (message in messages) {
            val surfaceId = message.surfaceIdOrNull() ?: continue
            when (message) {
                is CreateSurfaceMessage -> created += surfaceId
                is DeleteSurfaceMessage -> created -= surfaceId
                else -> if (surfaceId !in created) return surfaceId
            }
        }
        return null
    }

    public companion object {
        /** No carrier applied, no surface live: where a renderer starts. */
        public val Empty: A2uiSurfaces = A2uiSurfaces(emptyMap(), emptyMap())
    }
}

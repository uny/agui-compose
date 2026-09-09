package dev.ynagai.agui.core

import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiPart
import dev.ynagai.agui.model.UiRole

/**
 * One [UiMessage] under construction.
 *
 * Mutable, and caches the immutable message it last produced. The cache is what lets
 * [UiTranscriptReducer] hand a renderer a fresh [dev.ynagai.agui.model.UiTranscript] on every
 * event without every message in it becoming a new instance: a Compose renderer recomposes on
 * identity, so an untouched message must come back as the same object or a thousand-event run
 * redraws the whole transcript a thousand times.
 */
internal class MessageBuilder(
    val id: String,
    val role: UiRole,
    var name: String? = null,
) {
    val parts: MutableList<UiPart> = mutableListOf()

    private var cached: UiMessage? = null

    fun invalidate() {
        cached = null
    }

    /** Replaces the part at [index], keeping its position. */
    fun replace(index: Int, part: UiPart) {
        parts[index] = part
        invalidate()
    }

    /** Appends [part] and returns the index it landed at. */
    fun append(part: UiPart): Int {
        parts += part
        invalidate()
        return parts.lastIndex
    }

    fun build(): UiMessage =
        cached ?: UiMessage(id = id, role = role, parts = parts.toList(), name = name)
            .also { cached = it }
}

/** Where a part lives: which message, and where in that message's list. */
internal data class PartRef(val message: MessageBuilder, val index: Int)

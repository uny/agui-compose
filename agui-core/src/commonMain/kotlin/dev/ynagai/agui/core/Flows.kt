package dev.ynagai.agui.core

import com.agui.core.types.BaseEvent
import dev.ynagai.agui.model.UiTranscript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Folds an AG-UI event stream into a stream of transcripts, one per event.
 *
 * A fresh [UiTranscriptReducer] per collection, so collecting twice replays from empty rather than
 * continuing someone else's conversation. Cold, like the flow it wraps.
 *
 * Emitting on every event rather than on a timer: the events *are* the frames -- one
 * `TEXT_MESSAGE_CONTENT` is one visible character or word -- and a renderer that wants fewer can
 * ask for fewer with [kotlinx.coroutines.flow.conflate] or a sample, which it cannot do if this
 * has already dropped them.
 *
 * @param onWarning see [UiTranscriptReducer].
 */
public fun Flow<BaseEvent>.foldToTranscript(
    onWarning: (String) -> Unit = {},
): Flow<UiTranscript> = flow {
    val reducer = UiTranscriptReducer(onWarning)
    collect { event -> emit(reducer.accept(event)) }
}

package dev.ynagai.agui.agent

import com.agui.client.agent.AbstractAgent
import com.agui.client.agent.RunAgentParameters
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import dev.ynagai.agui.core.UiTranscriptReducer
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.UiTranscript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One agent, one thread, one transcript.
 *
 * Runs an upstream [AbstractAgent] and folds every event it produces into a [UiTranscript] that
 * outlives the run. `agui-core`'s `foldToTranscript` starts a fresh reducer per collection, which
 * is right for one run and wrong for a conversation: a second run folded that way would begin from
 * an empty transcript and the first answer would vanish from the screen. This holds a single
 * [UiTranscriptReducer] for as long as the session lives and feeds every run into it, so the
 * transcript grows the way the thread does. Upstream's `threadId` is fixed per agent, so a session
 * is a thread.
 *
 * Events are taken from [AbstractAgent.runAgentObservable] rather than `runAgent`: the latter
 * collects on an internal `Dispatchers.Default` scope and reports failure only to a logger, so a
 * caller can neither observe the events nor learn that the run failed. The observable rethrows
 * after upstream's own `HttpAgent` has already turned transport failures into `RUN_ERROR`, and it
 * still runs upstream's chunk transform and event verifier first -- so a stream that violates the
 * protocol's state machine surfaces as a throw, and lands here as [RunState.Failed].
 *
 * The reducer is not thread-safe and asks to be fed from one coroutine. [run] holds a mutex for
 * the length of a run, so two callers who race to start one take turns; the second waits for the
 * first to end rather than interleaving its events into the same transcript.
 *
 * This does not dispose the agent. [AbstractAgent.dispose] cancels its scope and closes an HTTP
 * client it created itself, and the agent may be shared with something outside this session; the
 * owner that constructed it closes it.
 *
 * @param agent the upstream agent to run. Its `threadId` names the thread this transcript is of.
 * @param onWarning see [UiTranscriptReducer]; this class adds one case of its own, a `RUN_ERROR`
 *   that arrived after the run had already ended and was therefore not folded.
 */
public class AgentSession(
    public val agent: AbstractAgent,
    private val onWarning: (String) -> Unit = {},
) {
    private val reducer = UiTranscriptReducer(onWarning)
    private val runs = Mutex()

    private val mutableTranscript = MutableStateFlow(reducer.transcript)

    /**
     * The transcript as of the last event, one value per event.
     *
     * A `StateFlow` conflates: a collector that is slower than the stream sees the latest frame
     * rather than every one. That is the right default for a renderer, which draws whatever is
     * current; it is the wrong tool for counting events, which is what the agent's own subscriber
     * hooks are for.
     */
    public val transcript: StateFlow<UiTranscript> = mutableTranscript.asStateFlow()

    /**
     * Runs the agent once and folds its events into [transcript], suspending until the run ends.
     *
     * Returns the state the run ended in, which is also what [transcript] now reports: a
     * [RunState.Finished] when the agent said so, a [RunState.Failed] when it sent `RUN_ERROR` or
     * when the stream threw -- a protocol violation caught by upstream's verifier, say. A throw is
     * folded into the transcript as a `RUN_ERROR` with code [CLIENT_ERROR_CODE] and is **not**
     * rethrown: the transcript is the report, and a UI that launched this from a button does not
     * want an unhandled exception for a run that failed in an ordinary way. Cancellation is the
     * exception -- it is recorded the same way, with [CANCELLED_CODE], and then propagates, because
     * the caller asked for it and a cancelled coroutine has to stay cancelled. A stream that ends
     * without `RUN_FINISHED` or `RUN_ERROR` -- a connection the server closed cleanly mid-run --
     * is a failure too, recorded under [CLIENT_ERROR_CODE]: upstream's verifier checks each event
     * against the last but has no opinion about the end of the stream, so this is where that check
     * lives.
     *
     * Recording a cancellation at all is deliberate. Without it the transcript keeps the caret
     * blinking under text that will never grow and a tool call waiting on arguments that will
     * never arrive, until the next run happens to settle them. The reducer has no state for
     * "stopped on request", so the run is marked failed and the code says why. The message is a
     * fixed one rather than the exception's: a `Job.cancel()` with no cause carries the coroutine
     * class name as its message, which is not something to draw.
     *
     * Once the agent has ended the run itself -- `RUN_FINISHED` or `RUN_ERROR` seen -- nothing
     * that happens afterwards rewrites that verdict: a verifier throw on a trailing event, a
     * cancellation while upstream's transport waits for the server to close the connection, or the
     * `RUN_ERROR` upstream's `HttpAgent` sends when that wait ends in a transport failure, all
     * leave the agent's own message and code in place. A cancellation that arrives while this
     * call is still waiting for an earlier run to release the mutex records nothing, because
     * nothing of this run had started.
     *
     * Only the coroutine that called this can stop the run. [AbstractAgent.abortRun] cancels a job
     * that upstream's `runAgent` starts, and `runAgentObservable` starts none, so through this
     * class it is inert.
     *
     * @param parameters the run's id, tools, context and forwarded properties, each defaulted by
     *   the agent when absent. The messages sent are the agent's own -- what it was constructed
     *   with, plus what earlier runs through it produced.
     */
    public suspend fun run(parameters: RunAgentParameters? = null): RunState = runs.withLock {
        // Per run, not per call: upstream's verifier lets one stream carry RUN_STARTED again after
        // RUN_FINISHED, and the reducer follows it, so the flag has to follow it too.
        var ended = false
        try {
            agent.runAgentObservable(parameters).collect { event ->
                when (event) {
                    is RunStartedEvent -> ended = false
                    is RunFinishedEvent -> ended = true
                    // The verifier permits RUN_ERROR after RUN_FINISHED, and upstream's `HttpAgent`
                    // sends one when the connection fails *after* the server has said the run is
                    // done. A run that finished did not then fail; the answer stands, and the
                    // dropped event goes where the reducer's own skips go rather than nowhere.
                    is RunErrorEvent -> if (ended) {
                        onWarning("RUN_ERROR after the run ended, not folded: ${event.code} ${event.message}")
                        return@collect
                    } else {
                        ended = true
                    }
                    else -> Unit
                }
                mutableTranscript.value = reducer.accept(event)
            }
            if (!ended) fail(message = "Stream ended before RUN_FINISHED", code = CLIENT_ERROR_CODE)
        } catch (e: CancellationException) {
            if (!ended) fail(message = "Run cancelled", code = CANCELLED_CODE)
            throw e
        } catch (e: Exception) {
            if (!ended) fail(message = e.message ?: e::class.simpleName ?: "Run failed", code = CLIENT_ERROR_CODE)
        }
        transcript.value.run
    }

    private fun fail(message: String, code: String) {
        mutableTranscript.value = reducer.accept(RunErrorEvent(message = message, code = code))
    }

    public companion object {
        /** The `RUN_ERROR` code a run is given when its event stream threw on the client. */
        public const val CLIENT_ERROR_CODE: String = "CLIENT_ERROR"

        /** The `RUN_ERROR` code a run is given when the coroutine running it was cancelled. */
        public const val CANCELLED_CODE: String = "CANCELLED"
    }
}

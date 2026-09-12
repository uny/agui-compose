package dev.ynagai.agui.agent

import com.agui.client.agent.AbstractAgent
import com.agui.client.agent.RunAgentParameters
import com.agui.core.types.BaseEvent
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.UserMessage
import dev.ynagai.agui.core.UiTranscriptReducer
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.UiTranscript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

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
 * The reducer is not thread-safe and asks to be fed from one coroutine. [run] and [send] hold a
 * mutex for the length of a run, so two callers who race to start one take turns; the second waits
 * for the first to end rather than interleaving its events into the same transcript.
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
     * The transcript as of the last event, one value per event -- plus one per [send], which puts
     * the message on screen before the run that carries it has produced anything.
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
        foldRun { agent.runAgentObservable(parameters) }
    }

    /**
     * Appends [message] to the transcript, sends it with the thread's history, and runs the agent.
     *
     * This is how a client says something. [run] cannot: [RunAgentParameters] carries a run id,
     * tools, context and forwarded properties and no messages, and `AbstractAgent.setMessages` is
     * protected -- so through that path the only messages a server ever sees are the ones the agent
     * was constructed with plus the ones its own runs produced. The public way in is the
     * `RunAgentInput` overload of `runAgentObservable`, which the agent then adopts as its own
     * state (`messages` and `state` are assigned from the input), so the turn sent here is history
     * for every run after it.
     *
     * The history sent is the agent's own, which is the agent's own accounting of the thread and
     * not this transcript: upstream folds each run's answer back into `messages`, in the lossy
     * shape its state layer keeps, and that is the shape the protocol asks a client to send back.
     * The transcript is for the screen and keeps the ordering that shape loses.
     *
     * The message reaches the transcript before the run starts, under the session's own lock, so
     * the sender sees their line before the answer to it and no other run can slip events between
     * the two. It is the lock, not the clock: a send that arrives while an earlier run is still
     * streaming waits for that run, and nothing of this turn is on screen until it does.
     *
     * A run that fails leaves the message there -- what was said was said, and a UI that offers a
     * retry needs it on screen to retry from. **Retry through [run], not through a second [send].**
     * The turn is already the agent's history by then: `runAgentObservable` adopts the input's
     * messages before the run that fails, so [run] asks the same question again, while a second
     * [send] would append it a second time and the server would be asked twice.
     *
     * The input is built here rather than by `AbstractAgent.prepareRunAgentInput`, which is the one
     * thing this path cannot reuse -- it takes `RunAgentParameters`, which is what carries no
     * messages. Field for field it is defaulted the way that method defaults them, but it is not
     * that method: an agent that *overrides* it to add something of its own gets that on [run] and
     * not here.
     *
     * @param message the turn to send. Supply the id when the client has one to supply; it is the
     *   id the message goes on the wire with, and the one it is drawn under unless the transcript
     *   already holds that id, in which case the drawn one is suffixed and the wire's is not (see
     *   [UiTranscriptReducer.appendUserMessage]). A later `MESSAGES_SNAPSHOT` does not reconcile
     *   against it either -- a snapshot replaces the transcript whole, the server's copy of this
     *   turn included.
     * @param parameters the run's id, tools, context and forwarded properties. Defaulted here the
     *   way `AbstractAgent` defaults them: a generated run id, no tools, no context, and empty
     *   forwarded properties.
     */
    public suspend fun send(message: UserMessage, parameters: RunAgentParameters? = null): RunState =
        runs.withLock {
            mutableTranscript.value = reducer.appendUserMessage(message)
            val input = RunAgentInput(
                threadId = agent.threadId,
                runId = parameters?.runId ?: Uuid.random().toString(),
                state = agent.state,
                messages = agent.messages + message,
                tools = parameters?.tools ?: emptyList(),
                context = parameters?.context ?: emptyList(),
                forwardedProps = parameters?.forwardedProps ?: JsonObject(emptyMap()),
            )
            foldRun { agent.runAgentObservable(input) }
        }

    /**
     * Sends one line of text as a user turn, under a generated message id.
     *
     * The id is this library's rather than the caller's, which is the trade: a chat box that has
     * nothing to say about ids does not have to invent a scheme, and a client that does -- one
     * matching messages against a store of its own -- passes a [UserMessage] to [send] instead.
     */
    public suspend fun send(text: String, parameters: RunAgentParameters? = null): RunState =
        send(UserMessage(id = Uuid.random().toString(), content = text), parameters)

    /**
     * Folds one run's events into the transcript and reports the state it ended in.
     *
     * Called with the session's lock already held, by [run] and by [send]. `Mutex` is not
     * reentrant, so this must not take it.
     *
     * [events] is a factory rather than a `Flow`, because building the stream is itself part of
     * the run: `runAgentObservable` adopts the input's messages and state and calls the agent's
     * own `run` before it returns anything, so an agent that fails to start throws from the call
     * that builds the flow rather than from collecting it. Taking the built flow as a parameter
     * would evaluate that at the call site, outside the `try` below, and the throw would escape
     * to a caller this class promises never to throw at.
     */
    private suspend fun foldRun(events: () -> Flow<BaseEvent>): RunState {
        // Per run, not per call: upstream's verifier lets one stream carry RUN_STARTED again after
        // RUN_FINISHED, and the reducer follows it, so the flag has to follow it too.
        var ended = false
        try {
            events().collect { event ->
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
        return transcript.value.run
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

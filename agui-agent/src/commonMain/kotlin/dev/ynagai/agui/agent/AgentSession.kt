package dev.ynagai.agui.agent

import com.agui.client.agent.AbstractAgent
import com.agui.client.agent.RunAgentParameters
import com.agui.core.types.BaseEvent
import com.agui.core.types.AssistantMessage
import com.agui.core.types.Context
import com.agui.core.types.Message
import com.agui.core.types.ResumeEntry
import com.agui.core.types.ResumeStatus
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.Tool
import com.agui.core.types.ToolCallResultEvent
import com.agui.core.types.ToolMessage
import com.agui.core.types.UserMessage
import com.agui.tools.ToolRegistry
import dev.ynagai.agui.core.UiTranscriptReducer
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.UiInterrupt
import dev.ynagai.agui.model.UiResumeEntry
import dev.ynagai.agui.model.UiResumeStatus
import dev.ynagai.agui.model.UiTranscript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
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
 * ### Frontend tools
 *
 * A session given a [ToolRegistry] executes the tools in it on the client. Every run declares the
 * registry's tools to the server, after whatever the caller's `RunAgentParameters.tools` names;
 * when the agent calls one, a [ToolRunner] -- inserted between the agent's stream and the
 * reducer -- assembles the call from its events, runs the executor, and hands the `ToolMessage`
 * back here. The result is folded into the transcript as a `TOOL_CALL_RESULT`, so the call that
 * was drawn `AWAITING_RESULT` is drawn `COMPLETE`, and once the run has finished it is **sent back
 * in a run of its own**: the same `tools`, `context` and `forwardedProps`, a new run id, and the
 * thread's history with the tool's answer placed directly after the assistant message that made
 * the call -- where a model backend requires it, even when the agent said more after calling.
 * That run may call another tool, which is answered the same way, until a run ends without
 * calling one. [run] and [send] suspend for the whole exchange and return the state the *last*
 * run ended in.
 *
 * Neither upstream's `ClientToolResponseHandler` nor its `ToolExecutionManager` is used. The
 * handler answers a tool by starting a second run *inside* itself and collecting it there, which
 * is a run this session never sees: its events would not reach the transcript, and it would not
 * take the lock the runs below take turns on. The manager is what [ToolRunner] replaces, for the
 * reasons its note gives. Here the run that carries an answer is started by the same code that
 * started the one it answers.
 *
 * A tool the agent calls that the registry does not hold is left alone, so a backend tool's
 * events fold exactly as they do with no registry at all.
 *
 * A run that stops to *ask* -- `RUN_FINISHED` with an interrupt outcome, an approval or a choice
 * only a human can give -- is not answered by a tool result, and not by the next [run] or [send]
 * either. The protocol lets no run start on the thread until every interrupt of the one that
 * stopped has been answered or abandoned in the input's `resume` list, and this class holds that
 * line: [resume] is the one call that starts a run while the thread is interrupted, and [run] and
 * [send] throw rather than start one the server would refuse -- or worse, one it would accept and
 * proceed past the question with. The interrupts to answer are on the transcript
 * ([RunState.Finished.interrupts]) and, until a run has ended cleanly since, on
 * [pendingInterrupts]. A tool result folded while the thread is interrupted is kept the way a
 * failed run's is, and goes out with the [resume].
 *
 * A result whose run *failed* -- a `RUN_ERROR` from the agent, or a stream that threw or was
 * cancelled while the tool was executing -- is not sent then, because there is no finished run to
 * answer. It is kept, and goes out with the next [run] or [send] on this session, ahead of the
 * turn that call adds. The alternative was dropping it, which would leave the agent's history
 * holding a call with no result, and that is a history most model backends refuse to continue.
 * A tool cancelled mid-execution has a result too: upstream's `AbstractToolExecutor` catches the
 * cancellation as it would any exception and hands back its own failure report (`Tool execution
 * failed: …`), which is kept and sent for the same reason -- the history then holds an answer to
 * the call, even if the answer is that it was stopped. A tool cancelled before it ran at all is
 * answered by the runner with the fact that it did not run, for the same reason.
 *
 * The reducer is fed only from the collecting coroutine. The runner executes tools on jobs of its
 * own, so each result goes on a channel and the collector folds it -- before the next event, or
 * after the stream ends -- rather than the job folding it from wherever it happens to be running.
 *
 * @param agent the upstream agent to run. Its `threadId` names the thread this transcript is of.
 * @param tools the tools this client executes, or null for a session that executes none. Declared
 *   on every run through this session, and answered as described above.
 * @param onWarning see [UiTranscriptReducer]; this class adds one case of its own, a `RUN_ERROR`
 *   that arrived after the run had already ended and was therefore not folded. Last, so a call
 *   that passes it as a trailing lambda still can.
 */
public class AgentSession(
    public val agent: AbstractAgent,
    public val tools: ToolRegistry? = null,
    private val onWarning: (String) -> Unit = {},
) {
    private val reducer = UiTranscriptReducer(onWarning)
    private val runs = Mutex()

    /** Where the runner's jobs leave results; drained on the collecting coroutine only. */
    private val results = Channel<ToolMessage>(Channel.UNLIMITED)

    /** Results folded into the transcript that no run has yet carried to the server. */
    private val unsent = mutableListOf<ToolMessage>()

    /**
     * What the last run to end cleanly stopped to ask for; empty when it did not. A run that
     * *failed* leaves it alone -- upstream's reference client keeps its pending list across a
     * `RUN_ERROR` for the same reason -- so a resume whose run failed is still owed.
     */
    private val pending = MutableStateFlow<List<UiInterrupt>>(emptyList())

    /**
     * The answers the last [resume] put on the wire, kept until a run ends cleanly. A resume whose
     * run failed was not consumed, so the retry -- [run], as for any failed run -- carries them
     * again, exactly as it carries a tool result the failed run did not deliver.
     */
    private var unconsumed: List<ResumeEntry> = emptyList()

    private val runner: ToolRunner? = tools?.let { ToolRunner(it, results) }

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
     * The interrupts the thread is waiting on: those of the last run that ended cleanly, when it
     * stopped to ask. Empty on a thread that is not waiting; while it is not empty, [run] and
     * [send] throw unless a failed [resume] left its answers owed, in which case they retry it.
     *
     * The same list [RunState.Finished.interrupts] carries, held here because the transcript stops
     * carrying it the moment a run *fails* -- [RunState.Failed] names no interrupts -- while the
     * thread is still waiting on them. A `StateFlow` for the same reason [transcript] is: a UI
     * that gates its composer on this, rather than on the transcript's run, keeps it closed
     * through a failed [resume] and can still draw the questions to retry from.
     */
    public val pendingInterrupts: StateFlow<List<UiInterrupt>> = pending.asStateFlow()

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
     * With a [tools] registry, a run that calls one of its tools is answered by another run, and
     * this returns the state the last of those ended in; see the class note. A tool result an
     * earlier run failed to carry goes out with this one, placed after the call it answers.
     *
     * A thread that is *interrupted* -- the last run stopped to ask, and [pendingInterrupts] is
     * not empty -- may not run this way, and this throws before anything is sent: the protocol
     * lets no run start past an unanswered interrupt, so the run would be refused by the server
     * or, worse, accepted and continued past the question. [resume] is the call that continues
     * such a thread. The one exception is a retry: a [resume] whose run *failed* left its answers
     * owed, and this carries them again.
     *
     * @param parameters the run's id, tools, context and forwarded properties. The id is generated
     *   here when absent rather than by the agent, because a tool executed during the run is told
     *   which run called it; the rest are defaulted the way the agent defaults them, with the
     *   registry's tools declared after the caller's. The messages sent are the agent's own --
     *   what it was constructed with, plus what earlier runs through it produced.
     * @throws IllegalStateException when the thread is interrupted and no failed [resume] is owed.
     */
    public suspend fun run(parameters: RunAgentParameters? = null): RunState = runs.withLock {
        val resume = covering(unconsumed)
        val prepared = prepare(parameters)
        val ended = if (unsent.isEmpty() && resume == null) {
            foldRun(prepared.runId) { agent.runAgentObservable(prepared.parameters()) }
        } else {
            val messages = agent.messages.answered(takeUnsent())
            foldRun(prepared.runId) { agent.runAgentObservable(input(prepared, messages, resume)) }
        }
        answerTools(ended, prepared)
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
     * With a [tools] registry, a run that calls one of its tools is answered by another run, and
     * this returns the state the last of those ended in; see the class note. A tool result an
     * earlier run failed to carry goes out with this one, ahead of [message].
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
     * A thread that is interrupted may not be sent to, as it may not be [run]: this throws before
     * the message reaches the transcript, so a line the thread cannot carry is neither drawn nor
     * failed. Answer the interrupts with [resume] first. As with [run], a failed [resume]'s
     * answers are carried again rather than refused.
     *
     * @param parameters the run's id, tools, context and forwarded properties. Defaulted here the
     *   way `AbstractAgent` defaults them: a generated run id, no tools, no context, and empty
     *   forwarded properties. The registry's tools, when there is one, are declared after the
     *   caller's.
     * @throws IllegalStateException when the thread is interrupted and no failed [resume] is owed.
     */
    public suspend fun send(message: UserMessage, parameters: RunAgentParameters? = null): RunState =
        runs.withLock {
            // Checked before the message is on screen: a line the thread cannot carry yet is a
            // programming error to report, not a turn to draw and then fail.
            val resume = covering(unconsumed)
            mutableTranscript.value = reducer.appendUserMessage(message)
            val prepared = prepare(parameters)
            val messages = agent.messages.answered(takeUnsent()) + message
            val ended = foldRun(prepared.runId) { agent.runAgentObservable(input(prepared, messages, resume)) }
            answerTools(ended, prepared)
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
     * Answers what the last run stopped to ask for, and runs the agent.
     *
     * This is how a client continues an interrupted thread. [entries] must cover every interrupt
     * in [pendingInterrupts] -- each answered, or explicitly abandoned -- and name nothing else;
     * the protocol rejects a resuming input that leaves one uncovered rather than letting a run
     * proceed past a question nobody answered, and this throws before the run starts for the
     * same reason. Omitting an interrupt is not abandoning it: abandon with
     * [UiResumeEntry.cancelled].
     *
     * Whether an interrupt has *expired* is not judged here. The protocol leaves the format of
     * `expiresAt` to the producer and the judgment to the consumer; a producer that will not take
     * a late answer says so by failing the run, which this returns as a [RunState.Failed].
     *
     * The history sent is the agent's own, as [run] sends it, with any tool result an earlier run
     * failed to carry placed after its call: a client that executed a tool while the thread was
     * interrupted has a result to deliver, and this is the first run that can deliver it. A run
     * that fails leaves the thread interrupted and the answers owed. Either retry works: [run]
     * carries the answers the failed run carried, and a second [resume] carries whatever it is
     * given -- the questions are still open, so answering them again is not answering them
     * twice, and a reader who clicks *Approve* a second time is doing the natural thing.
     *
     * With a [tools] registry, a run that calls one of its tools is answered by another run, and
     * this returns the state the last of those ended in; see the class note.
     *
     * @param entries one answer per pending interrupt.
     * @param parameters as for [run].
     * @throws IllegalStateException when the thread is not interrupted, or when [entries] leaves a
     *   pending interrupt uncovered -- the thread's state is what the entries fail to match.
     * @throws IllegalArgumentException when [entries] names an interrupt that is not pending, or
     *   names one twice.
     */
    public suspend fun resume(entries: List<UiResumeEntry>, parameters: RunAgentParameters? = null): RunState =
        runs.withLock {
            check(pending.value.isNotEmpty()) { "Nothing to resume: the thread is not interrupted." }
            val resume = covering(entries.map { it.toWire() })!!
            val prepared = prepare(parameters)
            val messages = agent.messages.answered(takeUnsent())
            val ended = foldRun(prepared.runId) { agent.runAgentObservable(input(prepared, messages, resume)) }
            answerTools(ended, prepared)
        }

    /**
     * [entries], once they are known to answer exactly what is [pending]; null when nothing is
     * pending and there is nothing to answer.
     *
     * The rule is the protocol's, and it cuts both ways: a run may not start while an interrupt
     * is unanswered, and an entry may not answer an interrupt the thread is not waiting on.
     */
    private fun covering(entries: List<ResumeEntry>): List<ResumeEntry>? {
        val pending = pending.value
        if (pending.isEmpty()) {
            require(entries.isEmpty()) {
                "Resume entries ${entries.map { it.interruptId }} answer nothing: the thread is not interrupted."
            }
            return null
        }
        val ids = entries.map { it.interruptId }
        require(ids.size == ids.toSet().size) { "Resume entries name an interrupt twice: $ids" }
        val waiting = pending.map { it.id }.toSet()
        val unknown = ids.filterNot { it in waiting }
        require(unknown.isEmpty()) { "Resume entries name interrupts the thread is not waiting on: $unknown" }
        val uncovered = waiting - ids.toSet()
        check(uncovered.isEmpty()) {
            "Thread has ${uncovered.size} pending interrupt(s) not addressed by resume: ${uncovered.joinToString()}"
        }
        return entries
    }

    private fun UiResumeEntry.toWire(): ResumeEntry = ResumeEntry(
        interruptId = interruptId,
        status = when (status) {
            UiResumeStatus.RESOLVED -> ResumeStatus.RESOLVED
            UiResumeStatus.CANCELLED -> ResumeStatus.CANCELLED
        },
        payload = payload,
    )

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
    private suspend fun foldRun(runId: String, events: () -> Flow<BaseEvent>): RunState {
        // Per run, not per call: upstream's verifier lets one stream carry RUN_STARTED again after
        // RUN_FINISHED, and the reducer follows it, so the flag has to follow it too.
        var ended = false
        try {
            val stream = events().let { runner?.execute(it, agent.threadId, runId) ?: it }
            stream.collect { event ->
                // A result the runner produced since the last event goes in first. The runner
                // emits TOOL_CALL_END downstream before it starts the job, and `emit` returns
                // only after this lambda has, so no result can land ahead of its own call.
                foldResults()
                when (event) {
                    is RunStartedEvent -> ended = false
                    is RunFinishedEvent -> {
                        ended = true
                        // The run ended cleanly, so whatever this run carried was consumed.
                        // Cleared here, on the event, so a stream upstream's verifier stops right
                        // after it still leaves this right; what the thread waits on now is read
                        // off the transcript once the event has folded, below.
                        unconsumed = emptyList()
                    }
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
                if (event is RunFinishedEvent) {
                    pending.value = (transcript.value.run as? RunState.Finished)?.interrupts.orEmpty()
                }
            }
            if (!ended) fail(message = "Stream ended before RUN_FINISHED", code = CLIENT_ERROR_CODE)
        } catch (e: CancellationException) {
            if (!ended) fail(message = "Run cancelled", code = CANCELLED_CODE)
            throw e
        } catch (e: Exception) {
            if (!ended) fail(message = e.message ?: e::class.simpleName ?: "Run failed", code = CLIENT_ERROR_CODE)
        } finally {
            // The runner joins its jobs before the stream completes, so on the ordinary path
            // everything is here by now. On a throw or a cancellation the jobs are cancelled with
            // the stream's scope, and what they handed back -- a result, the executor's report
            // that the tool was stopped, or the runner's that it never started -- is folded and
            // kept.
            foldResults()
        }
        return transcript.value.run
    }

    private fun fail(message: String, code: String) {
        mutableTranscript.value = reducer.accept(RunErrorEvent(message = message, code = code))
    }

    /**
     * Folds every result the runner has handed back since the last call, and keeps each to send.
     *
     * Non-suspending on purpose: it is called from a `finally` that may be running cancelled, and
     * `tryReceive` neither suspends nor throws for that.
     */
    private fun foldResults() {
        while (true) {
            val message = results.tryReceive().getOrNull() ?: break
            unsent += message
            mutableTranscript.value = reducer.accept(
                ToolCallResultEvent(messageId = message.id, toolCallId = message.toolCallId, content = message.content),
            )
        }
    }

    /**
     * Sends the results of [ended] back, and the results of that run, until a run calls no tool.
     *
     * Only a run that finished, and finished *done*, is answered: a failed one has no answer to
     * continue, and an interrupted one is waiting on something a tool result is not -- the
     * protocol says a run that stopped for a frontend tool finishes as success, so an interrupt
     * outcome is a question for a human, and the next run has to carry the answer to it. What is
     * left unsent when this returns is carried by the next [run], [send] or [resume].
     */
    private suspend fun answerTools(ended: RunState, parameters: PreparedRun): RunState {
        var current = ended
        while (current is RunState.Finished && !current.interrupted && unsent.isNotEmpty()) {
            val next = parameters.copy(runId = Uuid.random().toString())
            val messages = agent.messages.answered(takeUnsent())
            current = foldRun(next.runId) { agent.runAgentObservable(input(next, messages)) }
        }
        return current
    }

    /**
     * The results to put on the wire, cleared here because `runAgentObservable` adopts the input's
     * messages as the agent's before it runs -- once sent, they are the agent's history, and a run
     * that then fails is retried through [run] the way a failed [send] is.
     */
    private fun takeUnsent(): List<ToolMessage> = unsent.toList().also { unsent.clear() }

    /**
     * This history with each of [results] placed directly after the assistant message holding
     * the call it answers -- after any answer already there -- or at the end when no message
     * holds it.
     *
     * Appending would do when the call is the last thing the agent said, and not otherwise: an
     * agent that says something after calling a tool, or one whose `TOOL_CALL_START` names no
     * `parentMessageId` in a thread with earlier turns -- upstream then attaches the call to the
     * *last* assistant message, wherever it is -- leaves something between the call and its
     * answer, and the model backends that require the two adjacent refuse the history.
     */
    private fun List<Message>.answered(results: List<ToolMessage>): List<Message> {
        if (results.isEmpty()) return this
        val messages = toMutableList()
        for (result in results) {
            val call = messages.indexOfLast { message ->
                message is AssistantMessage && message.toolCalls?.any { it.id == result.toolCallId } == true
            }
            if (call < 0) {
                messages += result
                continue
            }
            var at = call + 1
            while (at < messages.size && messages[at] is ToolMessage) at++
            messages.add(at, result)
        }
        return messages
    }

    /**
     * [parameters] with a run id it may have lacked and the registry's tools added after its own,
     * every field defaulted the way `AbstractAgent.prepareRunAgentInput` defaults it. Neither tool
     * list is deduplicated: two tools of one name is the caller's to sort out, and a server that
     * receives both will say which it took.
     */
    private fun prepare(parameters: RunAgentParameters?): PreparedRun = PreparedRun(
        runId = parameters?.runId ?: Uuid.random().toString(),
        tools = (parameters?.tools ?: emptyList()) + (tools?.getAllTools() ?: emptyList()),
        context = parameters?.context ?: emptyList(),
        forwardedProps = parameters?.forwardedProps ?: JsonObject(emptyMap()),
        given = parameters,
    )

    /**
     * The input `prepareRunAgentInput` would build from [parameters], carrying [messages] instead
     * of the agent's own, and [resume] -- which that method cannot carry at all: upstream's
     * `RunAgentParameters` has no field for it, and the input it builds leaves `resume` null.
     * Recorded as owed the moment it is on the wire, and cleared when a run ends cleanly.
     */
    private fun input(parameters: PreparedRun, messages: List<Message>, resume: List<ResumeEntry>? = null): RunAgentInput {
        if (resume != null) unconsumed = resume
        return RunAgentInput(
            threadId = agent.threadId,
            runId = parameters.runId,
            state = agent.state,
            messages = messages,
            tools = parameters.tools,
            context = parameters.context,
            forwardedProps = parameters.forwardedProps,
            resume = resume,
        )
    }

    /**
     * `RunAgentParameters` with nothing left null. Upstream's carries every field nullable and
     * fills the gaps in `prepareRunAgentInput`; the runs here need the run id before the agent is
     * asked, and a follow-up run needs the rest to send again, so the gaps are filled once.
     *
     * [parameters] is for the one path that still goes through `prepareRunAgentInput` -- a [run]
     * with nothing of its own to carry -- and hands that method what the caller gave it, nulls
     * included, so an agent that overrides it to fill a null still gets to. Only the run id is
     * always set, and the tools only when a registry adds to them.
     */
    private data class PreparedRun(
        val runId: String,
        val tools: List<Tool>,
        val context: List<Context>,
        val forwardedProps: JsonElement,
        val given: RunAgentParameters?,
    ) {
        fun parameters(): RunAgentParameters = RunAgentParameters(
            runId = runId,
            tools = if (tools.isEmpty()) given?.tools else tools,
            context = given?.context,
            forwardedProps = given?.forwardedProps,
        )
    }

    public companion object {
        /** The `RUN_ERROR` code a run is given when its event stream threw on the client. */
        public const val CLIENT_ERROR_CODE: String = "CLIENT_ERROR"

        /** The `RUN_ERROR` code a run is given when the coroutine running it was cancelled. */
        public const val CANCELLED_CODE: String = "CANCELLED"
    }
}

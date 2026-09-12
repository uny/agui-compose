package dev.ynagai.agui.agent

import com.agui.core.types.BaseEvent
import com.agui.core.types.FunctionCall
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.ToolCall
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolMessage
import com.agui.tools.ToolExecutionContext
import com.agui.tools.ToolExecutionResult
import com.agui.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Executes a [ToolRegistry]'s tools as one run's stream calls them, and leaves each answer on
 * [results].
 *
 * A stage between the agent's stream and the reducer: every event passes through unchanged, and
 * a `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` sequence naming a tool the registry
 * holds is assembled into a [ToolCall] and executed on a job of its own, under the scope of the
 * collect. A call naming a tool the registry does not hold is left alone. `RUN_FINISHED` and
 * `RUN_ERROR` go downstream first and then wait for every job started so far, so the stream
 * completes with every answer delivered.
 *
 * This is the part of upstream's `ToolExecutionManager` the session needs, written here because
 * three things about the manager are measured wrong for a run that calls more than one tool
 * (`docs/decisions/0008`): it joins its jobs by iterating the map each job removes itself from,
 * and the `ConcurrentModificationException` that raises cancels every job still running; it names
 * each answer `msg_<epoch millis>`, so two answers in one millisecond share an id; and a job
 * cancelled before its body ran leaves the call with no answer at all. Here the jobs are joined
 * from a snapshot, ids are random, and every call whose job was started is answered -- a job
 * that never ran answers with the fact that it did not. A call whose `TOOL_CALL_END` never came
 * has no job and no answer, as before: it is a call the agent never finished making.
 *
 * Every answer is a `ToolMessage` whose content is the executor's result the way the manager
 * formats it: the JSON result when there is one, else the message, else `true` / `false`. A
 * registry that throws answers with `Error: Tool execution failed - …`, as the manager did, and a
 * tool cancelled mid-execution answers with whatever its executor caught the cancellation as.
 *
 * Answers are put on the channel with `trySend`, never `send`: the last of them are written from
 * a `finally` that may be running cancelled, and the channel is unlimited, so nothing suspends
 * and nothing is lost.
 */
internal class ToolRunner(
    private val registry: ToolRegistry,
    private val results: SendChannel<ToolMessage>,
) {
    fun execute(events: Flow<BaseEvent>, threadId: String, runId: String): Flow<BaseEvent> = flow {
        /** Per call started, completed by the job's body as its first act. */
        val ran = mutableMapOf<String, CompletableDeferred<Unit>>()
        try {
            coroutineScope {
                val open = mutableMapOf<String, Pending>()
                val jobs = mutableListOf<Job>()
                events.collect { event ->
                    emit(event)
                    when (event) {
                        is ToolCallStartEvent -> if (registry.getToolExecutor(event.toolCallName) != null) {
                            open[event.toolCallId] = Pending(event.toolCallName)
                        }

                        is ToolCallArgsEvent -> open[event.toolCallId]?.arguments?.append(event.delta)

                        is ToolCallEndEvent -> open.remove(event.toolCallId)?.let { pending ->
                            val call = ToolCall(
                                id = event.toolCallId,
                                function = FunctionCall(name = pending.name, arguments = pending.arguments.toString()),
                            )
                            val body = CompletableDeferred<Unit>().also { ran[call.id] = it }
                            jobs += launch {
                                body.complete(Unit)
                                val content = run(call, threadId, runId)
                                results.trySend(ToolMessage(id = Uuid.random().toString(), content = content, toolCallId = call.id))
                            }
                        }

                        // Downstream already has the event; the reducer draws the run ended while
                        // the tools it called are still running, which is the documented shape.
                        is RunFinishedEvent, is RunErrorEvent -> {
                            jobs.toList().forEach { it.join() }
                            jobs.clear()
                        }

                        else -> Unit
                    }
                }
            }
        } finally {
            // `coroutineScope` has returned, so every job is done: one whose body ran has answered,
            // a cancelled one included. What is left is a job cancelled before it ran.
            for ((id, body) in ran) {
                if (!body.isCompleted) {
                    results.trySend(ToolMessage(id = Uuid.random().toString(), content = NOT_EXECUTED, toolCallId = id))
                }
            }
        }
    }

    /** Runs [call] and reports the answer as text; never throws. */
    private suspend fun run(call: ToolCall, threadId: String, runId: String): String = try {
        registry.executeTool(ToolExecutionContext(toolCall = call, threadId = threadId, runId = runId)).text()
    } catch (e: Exception) {
        "Error: Tool execution failed - ${e.message}"
    }

    private fun ToolExecutionResult.text(): String =
        result?.toString()?.takeIf { it.isNotEmpty() }
            ?: message?.takeIf { it.isNotEmpty() }
            ?: if (success) "true" else "false"

    private class Pending(val name: String) {
        val arguments = StringBuilder()
    }

    internal companion object {
        /** What a call answers with when its run ended before the tool started. */
        internal const val NOT_EXECUTED: String = "Error: Tool execution failed - the run ended before the tool started"
    }
}

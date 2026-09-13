package dev.ynagai.agui.agent

import com.agui.client.agent.RunAgentParameters
import com.agui.core.types.AssistantMessage
import com.agui.core.types.BaseEvent
import com.agui.core.types.Interrupt
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunFinishedInterruptOutcome
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.Tool
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolMessage
import com.agui.core.types.UserMessage
import com.agui.tools.AbstractToolExecutor
import com.agui.tools.ToolExecutionContext
import com.agui.tools.ToolExecutionResult
import com.agui.tools.toolRegistry
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiResumeEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AgentSession] with a `ToolRegistry`: a tool the agent calls is executed here, and its result
 * goes back in a run of its own.
 *
 * The agent is scripted on what it is *sent*: a run whose input ends in a `ToolMessage` is the
 * follow-up, and answers with text; any other run calls the tool. That is the shape a server has
 * -- it sees the result only in the next request -- and it is what makes `agent.inputs` the
 * measurement that matters: the second input is the wire, and everything about the follow-up is
 * asserted against it.
 */
class AgentSessionToolsTest {

    @Test
    fun executes_the_tool_and_sends_the_result_in_a_run_of_its_own() = runTest {
        val agent = ScriptedAgent(callThenAnswer())
        val session = AgentSession(agent, tools = toolRegistry(Echo()))

        val ended = session.run(RunAgentParameters(runId = "r1"))

        assertEquals(2, agent.inputs.size, "one run to call the tool, one to answer it")
        val followUp = agent.inputs[1]
        assertIs<RunState.Finished>(ended)
        assertEquals(followUp.runId, ended.runId, "the state returned is the follow-up's")

        val result = assertIs<ToolMessage>(followUp.messages.last())
        assertEquals("c1", result.toolCallId)
        assertEquals("""{"echoed":"hi"}""", result.content)
        val call = assertIs<AssistantMessage>(followUp.messages[followUp.messages.size - 2])
        assertEquals("c1", call.toolCalls?.single()?.id, "the call precedes its result in the history sent")

        val part = session.transcript.value.messages.first().parts.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.COMPLETE, part.status)
        assertEquals("""{"echoed":"hi"}""", part.result)
    }

    /**
     * The tool the server called it with has to be the tool it is offered again, or the follow-up
     * run cannot call it a second time. Everything else the caller set rides along too.
     */
    @Test
    fun the_follow_up_run_carries_the_same_tools_context_and_props() = runTest {
        val agent = ScriptedAgent(callThenAnswer())
        val session = AgentSession(agent, tools = toolRegistry(Echo()))
        val callers = Tool(name = "search", description = "look it up", parameters = JsonObject(emptyMap()))
        val props = buildJsonObject { put("tenant", JsonPrimitive("acme")) }

        session.send("hello", RunAgentParameters(runId = "r1", tools = listOf(callers), forwardedProps = props))

        val (first, second) = agent.inputs
        assertContentEquals(listOf("search", "echo"), first.tools.map { it.name }, "caller's first, registry's after")
        assertContentEquals(first.tools, second.tools)
        assertEquals(first.context, second.context)
        assertEquals(props, second.forwardedProps)
        assertTrue(second.runId != first.runId && second.runId.isNotBlank())
    }

    @Test
    fun a_tool_the_registry_does_not_hold_is_left_to_the_server() = runTest {
        val agent = ScriptedAgent(callThenAnswer(toolName = "weather"))
        val session = AgentSession(agent, tools = toolRegistry(Echo()))

        val ended = session.run(RunAgentParameters(runId = "r1"))

        assertEquals(1, agent.inputs.size, "nothing to answer, so nothing sent")
        assertEquals(RunState.Finished("t", "r1"), ended)
        val part = session.transcript.value.messages.single().parts.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.AWAITING_RESULT, part.status)
        assertNull(part.result)
    }

    /** One follow-up may call a tool again. It is answered the same way, until one does not. */
    @Test
    fun a_follow_up_that_calls_a_tool_is_answered_too() = runTest {
        var calls = 0
        val agent = ScriptedAgent({ input ->
            val answered = input.messages.count { it is ToolMessage }
            if (answered < 2) {
                calls++
                call(input.runId, "c$calls", "echo", """{"text":"$calls"}""")
            } else {
                flow { answer(input.runId, "a", "done").forEach { emit(it) } }
            }
        })
        val session = AgentSession(agent, tools = toolRegistry(Echo()))

        session.run(RunAgentParameters(runId = "r1"))

        assertEquals(3, agent.inputs.size)
        assertEquals(listOf("c1", "c2"), agent.inputs[2].messages.filterIsInstance<ToolMessage>().map { it.toolCallId })
        val parts = session.transcript.value.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>()
        assertEquals(listOf(ToolCallStatus.COMPLETE, ToolCallStatus.COMPLETE), parts.map { it.status })
    }

    /**
     * A run that stopped to *ask* is not answered by a tool result. The protocol says a run that
     * stopped for a frontend tool finishes as success, so an interrupt outcome is a question only
     * the next run's `resume` can answer -- and the result the client has meanwhile is kept, and
     * goes out with that resume, placed after its call.
     */
    @Test
    fun an_interrupted_run_is_not_answered_by_the_tool_that_ran_here() = runTest {
        val interrupt = RunFinishedInterruptOutcome(interrupts = listOf(Interrupt(id = "i1", reason = "approve")))
        val agent = ScriptedAgent(callThenAnswer(outcome = interrupt))
        val session = AgentSession(agent, tools = toolRegistry(Echo()))

        val ended = session.run(RunAgentParameters(runId = "r1"))

        assertEquals(1, agent.inputs.size)
        assertIs<RunState.Finished>(ended)
        assertEquals(true, ended.interrupted)
        assertEquals(listOf("i1"), session.pendingInterrupts.value.map { it.id })

        session.resume(listOf(UiResumeEntry.cancelled(ended.interrupts.single())))

        assertEquals(2, agent.inputs.size)
        assertEquals(listOf("i1"), agent.inputs[1].resume?.map { it.interruptId })
        assertEquals(listOf("c1"), agent.inputs[1].messages.filterIsInstance<ToolMessage>().map { it.toolCallId })
        assertEquals(emptyList(), session.pendingInterrupts.value)
    }

    /**
     * There is no finished run to answer, so the result waits -- and goes out with the next turn,
     * ahead of it, rather than being dropped and leaving a call with no result in the history.
     */
    @Test
    fun a_result_of_a_failed_run_goes_with_the_next_send() = runTest {
        val agent = ScriptedAgent({ input ->
            if (input.messages.none { it is ToolMessage }) {
                flow {
                    emit(RunStartedEvent(threadId = THREAD, runId = input.runId))
                    toolCall("c1", "echo", """{"text":"hi"}""").forEach { emit(it) }
                    emit(RunErrorEvent(message = "boom", code = "E"))
                }
            } else {
                flow { answer(input.runId, "a", "done").forEach { emit(it) } }
            }
        })
        val session = AgentSession(agent, tools = toolRegistry(Echo()))

        val failed = session.run(RunAgentParameters(runId = "r1"))
        assertEquals(RunState.Failed("boom", "E"), failed)
        assertEquals(1, agent.inputs.size, "a failed run is not followed up")
        val part = session.transcript.value.messages.first().parts.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.COMPLETE, part.status, "the result is on screen even so")

        session.send(UserMessage(id = "u1", content = "again"))

        val next = agent.inputs[1].messages.takeLast(2)
        assertEquals("c1", assertIs<ToolMessage>(next[0]).toolCallId)
        assertEquals("u1", next[1].id)
        assertEquals(2, agent.inputs.size, "the answer called no tool, so that was the end")
    }

    /** The registry turns a throwing executor into a result that says so; the agent hears about it. */
    @Test
    fun a_tool_that_throws_answers_with_the_failure() = runTest {
        val agent = ScriptedAgent(callThenAnswer(toolName = "broken"))
        val session = AgentSession(agent, tools = toolRegistry(Broken()))

        session.run(RunAgentParameters(runId = "r1"))

        assertEquals(2, agent.inputs.size)
        val result = assertIs<ToolMessage>(agent.inputs[1].messages.last())
        assertTrue("no can do" in result.content, result.content)
    }

    /** Through [AgentSession.run] as much as through [AgentSession.send]: the registry's tools are on the wire. */
    @Test
    fun run_declares_the_registry_tools_after_the_callers() = runTest {
        val agent = ScriptedAgent(callThenAnswer())
        val session = AgentSession(agent, tools = toolRegistry(Echo()))
        val callers = Tool(name = "search", description = "look it up", parameters = JsonObject(emptyMap()))

        session.run(RunAgentParameters(runId = "r1", tools = listOf(callers)))
        session.run(RunAgentParameters(runId = "r2"))

        assertEquals(listOf("search", "echo"), agent.inputs[0].tools.map { it.name })
        assertEquals(listOf("echo"), agent.inputs.last().tools.map { it.name })
    }

    /** The other way a kept result goes out: [AgentSession.run], which adds no turn of its own. */
    @Test
    fun a_result_of_a_failed_run_goes_with_the_next_run() = runTest {
        val agent = ScriptedAgent({ input ->
            if (input.messages.none { it is ToolMessage }) {
                flow {
                    emit(RunStartedEvent(threadId = THREAD, runId = input.runId))
                    toolCall("c1", "echo", """{"text":"hi"}""").forEach { emit(it) }
                    emit(RunErrorEvent(message = "boom", code = "E"))
                }
            } else {
                flow { answer(input.runId, "a", "done").forEach { emit(it) } }
            }
        })
        val session = AgentSession(agent, tools = toolRegistry(Echo()))
        session.run(RunAgentParameters(runId = "r1"))

        val ended = session.run(RunAgentParameters(runId = "r2"))

        assertIs<RunState.Finished>(ended)
        assertEquals(2, agent.inputs.size)
        assertEquals("c1", assertIs<ToolMessage>(agent.inputs[1].messages.last()).toolCallId)
    }

    /**
     * A stream that dies right after `TOOL_CALL_END` cancels the job before its body runs, so no
     * executor is there to report the cancellation. The runner answers for it, and the history
     * the next turn carries still holds an answer to every call.
     */
    @Test
    fun a_tool_whose_run_died_before_it_started_is_answered_as_not_run() = runTest {
        val agent = ScriptedAgent({ input ->
            if (input.messages.none { it is ToolMessage }) {
                flow {
                    emit(RunStartedEvent(threadId = THREAD, runId = input.runId))
                    toolCall("c1", "echo", """{"text":"hi"}""").forEach { emit(it) }
                    throw IllegalStateException("net")
                }
            } else {
                flow { answer(input.runId, "a", "done").forEach { emit(it) } }
            }
        })
        val session = AgentSession(agent, tools = toolRegistry(Echo()))

        val failed = session.run(RunAgentParameters(runId = "r1"))
        assertIs<RunState.Failed>(failed)
        val part = session.transcript.value.messages.first().parts.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.COMPLETE, part.status)
        assertEquals(ToolRunner.NOT_EXECUTED, part.result)

        session.send(UserMessage(id = "u1", content = "again"))

        val next = agent.inputs[1].messages.takeLast(2)
        assertEquals("c1", assertIs<ToolMessage>(next[0]).toolCallId)
        assertEquals("u1", next[1].id)
    }

    /**
     * Two tools in one run, the second still running when `RUN_FINISHED` arrives. Both are waited
     * for and both answer with their own result under their own id -- which is what upstream's
     * manager got wrong, twice over (see the [ToolRunner] note).
     */
    @Test
    fun two_tools_in_one_run_are_both_answered_with_distinct_ids() = runTest {
        val agent = ScriptedAgent({ input ->
            if (input.messages.none { it is ToolMessage }) {
                flow {
                    emit(RunStartedEvent(threadId = THREAD, runId = input.runId))
                    toolCall("c1", "echo", """{"text":"1"}""").forEach { emit(it) }
                    toolCall("c2", "slow", "{}").forEach { emit(it) }
                    emit(RunFinishedEvent(threadId = THREAD, runId = input.runId))
                }
            } else {
                flow { answer(input.runId, "a", "done").forEach { emit(it) } }
            }
        })
        val session = AgentSession(agent, tools = toolRegistry(Echo(), Slow()))

        session.run(RunAgentParameters(runId = "r1"))

        val results = agent.inputs[1].messages.filterIsInstance<ToolMessage>()
        assertEquals(mapOf("c1" to """{"echoed":"1"}""", "c2" to "slept"), results.associate { it.toolCallId to it.content })
        assertEquals(2, results.map { it.id }.toSet().size, "ids: ${results.map { it.id }}")
    }

    /**
     * The answer goes where a model backend requires it, directly after the message that made the
     * call, not at the end of whatever the agent said afterwards.
     */
    @Test
    fun a_result_is_placed_after_the_message_that_called_for_it() = runTest {
        val agent = ScriptedAgent({ input ->
            if (input.messages.none { it is ToolMessage }) {
                flow {
                    emit(RunStartedEvent(threadId = THREAD, runId = input.runId))
                    emit(TextMessageStartEvent(messageId = "a1"))
                    emit(TextMessageContentEvent(messageId = "a1", delta = "calling"))
                    emit(TextMessageEndEvent(messageId = "a1"))
                    toolCall("c1", "echo", """{"text":"hi"}""").forEach { emit(it) }
                    emit(TextMessageStartEvent(messageId = "a2"))
                    emit(TextMessageContentEvent(messageId = "a2", delta = "meanwhile"))
                    emit(TextMessageEndEvent(messageId = "a2"))
                    emit(RunFinishedEvent(threadId = THREAD, runId = input.runId))
                }
            } else {
                flow { answer(input.runId, "a", "done").forEach { emit(it) } }
            }
        })
        val session = AgentSession(agent, tools = toolRegistry(Echo()))

        session.run(RunAgentParameters(runId = "r1"))

        val messages = agent.inputs[1].messages
        val call = messages.indexOfFirst { (it as? AssistantMessage)?.toolCalls?.any { c -> c.id == "c1" } == true }
        assertEquals("c1", assertIs<ToolMessage>(messages[call + 1]).toolCallId)
        assertEquals("a2", messages[call + 2].id)
    }

    @Test
    fun without_a_registry_nothing_changes() = runTest {
        val agent = ScriptedAgent(callThenAnswer())
        val session = AgentSession(agent)

        session.run(RunAgentParameters(runId = "r1"))

        assertEquals(1, agent.inputs.size)
        assertContentEquals(emptyList(), agent.inputs.single().tools)
    }

    // ---- Fixtures ----------------------------------------------------------------------------

    private class Echo : AbstractToolExecutor(
        Tool(name = "echo", description = "says it back", parameters = JsonObject(emptyMap())),
    ) {
        override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult {
            val text = (context.toolCall.function.arguments.let { kotlinx.serialization.json.Json.parseToJsonElement(it) } as JsonObject)["text"]
            return ToolExecutionResult.success(buildJsonObject { put("echoed", text ?: JsonPrimitive("")) })
        }
    }

    private class Slow : AbstractToolExecutor(
        Tool(name = "slow", description = "takes its time", parameters = JsonObject(emptyMap())),
    ) {
        override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult {
            delay(1_000)
            return ToolExecutionResult.success(message = "slept")
        }
    }

    private class Broken : AbstractToolExecutor(
        Tool(name = "broken", description = "never works", parameters = JsonObject(emptyMap())),
    ) {
        override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult =
            throw IllegalStateException("no can do")
    }

    private fun toolCall(id: String, name: String, arguments: String): List<BaseEvent> = listOf(
        ToolCallStartEvent(toolCallId = id, toolCallName = name),
        ToolCallArgsEvent(toolCallId = id, delta = arguments),
        ToolCallEndEvent(toolCallId = id),
    )

    private fun call(runId: String, id: String, name: String, arguments: String, outcome: RunFinishedInterruptOutcome? = null) =
        flow {
            emit(RunStartedEvent(threadId = THREAD, runId = runId))
            toolCall(id, name, arguments).forEach { emit(it) }
            emit(RunFinishedEvent(threadId = THREAD, runId = runId, outcome = outcome))
        }

    /** Calls [toolName] on a run that carries no tool result yet; answers with text on one that does. */
    private fun callThenAnswer(
        toolName: String = "echo",
        outcome: RunFinishedInterruptOutcome? = null,
    ): (RunAgentInput) -> Flow<BaseEvent> = { input ->
        if (input.messages.none { it is ToolMessage }) {
            call(input.runId, "c1", toolName, """{"text":"hi"}""", outcome)
        } else {
            flow { answer(input.runId, "a-${input.runId}", "done").forEach { emit(it) } }
        }
    }
}

/**
 * Cancelled while a tool is still executing.
 *
 * Measured rather than assumed, because upstream's `AbstractToolExecutor.execute` catches
 * `Exception` and on the JVM a `CancellationException` is one: the executor does not let the
 * cancellation through, it reports it as a failed execution -- `Tool execution failed: … was
 * cancelled` -- and hands that back like any result. The session keeps it like any result. That
 * is the right outcome for the history, which then holds a call *and* an answer rather than a
 * call the server cannot continue from; what the answer says is the executor's wording, and the
 * transcript draws it as a result.
 */
class AgentSessionToolsCancellationTest {

    @Test
    fun a_tool_cancelled_mid_execution_answers_with_the_managers_failure_report() = runTest {
        val agent = ScriptedAgent({ input ->
            if (input.messages.none { it is ToolMessage }) {
                flow {
                    emit(RunStartedEvent(threadId = THREAD, runId = input.runId))
                    emit(ToolCallStartEvent(toolCallId = "c1", toolCallName = "slow"))
                    emit(ToolCallArgsEvent(toolCallId = "c1", delta = "{}"))
                    emit(ToolCallEndEvent(toolCallId = "c1"))
                    emit(RunFinishedEvent(threadId = THREAD, runId = input.runId))
                }
            } else {
                flow { answer(input.runId, "a", "done").forEach { emit(it) } }
            }
        })
        val session = AgentSession(agent, tools = toolRegistry(Slow()))

        val job = launch { session.run(RunAgentParameters(runId = "r1")) }
        yield()
        yield()
        job.cancel()
        job.join()

        // RUN_FINISHED was seen before the cancellation, which arrived while the runner was
        // joining the tool's job; the agent's verdict stands, as it does for any late cancellation.
        assertEquals(RunState.Finished("t", "r1"), session.transcript.value.run)
        assertEquals(1, agent.inputs.size, "no follow-up: the cancelled coroutine starts nothing")
        val part = session.transcript.value.messages.first().parts.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.COMPLETE, part.status)
        assertTrue(part.result.orEmpty().startsWith("Tool execution failed"), "got ${part.result}")

        session.send(UserMessage(id = "u1", content = "again"))

        val next = agent.inputs[1].messages.takeLast(2)
        assertEquals("c1", assertIs<ToolMessage>(next[0]).toolCallId)
        assertEquals("u1", next[1].id)
    }

    private class Slow : AbstractToolExecutor(Tool(name = "slow", description = "", parameters = JsonObject(emptyMap()))) {
        override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult = awaitCancellation()
    }
}

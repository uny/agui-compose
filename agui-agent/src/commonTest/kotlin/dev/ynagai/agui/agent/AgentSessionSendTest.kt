package dev.ynagai.agui.agent

import com.agui.client.agent.AgentConfig
import com.agui.client.agent.RunAgentParameters
import com.agui.core.types.AssistantMessage
import com.agui.core.types.Context
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.Tool
import com.agui.core.types.UserMessage
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.UiRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [AgentSession.send] -- saying something, which [AgentSession.run] cannot do.
 *
 * `RunAgentParameters` carries no messages and `AbstractAgent.setMessages` is protected, so the
 * measurement that matters most here is `carries_the_message_to_the_agent_with_the_threads_history`:
 * it is the one that would fail if the send path were ever moved back onto the parameters overload.
 */
class AgentSessionSendTest {

    @Test
    fun carries_the_message_to_the_agent_with_the_threads_history() = runTest {
        val agent = ScriptedAgent(
            config = AgentConfig(threadId = "t", initialMessages = listOf(UserMessage(id = "u0", content = "earlier"))),
        )
        val session = AgentSession(agent)

        session.send(UserMessage(id = "u1", content = "hello"), RunAgentParameters(runId = "r1"))

        val sent = agent.inputs.single()
        assertContentEquals(listOf("u0", "u1"), sent.messages.map { it.id })
        assertEquals("hello", (sent.messages.last() as UserMessage).content)
        assertEquals("t", sent.threadId)
        assertEquals("r1", sent.runId)
    }

    /**
     * The turn sent becomes the agent's own history, because the `RunAgentInput` overload of
     * `runAgentObservable` adopts the input's messages as the agent's state. Without that, run two
     * would ask the server to answer a question it was never told about.
     */
    @Test
    fun the_agent_keeps_the_sent_turn_as_history_for_the_next_run() = runTest {
        val agent = ScriptedAgent()
        val session = AgentSession(agent)

        session.send(UserMessage(id = "u1", content = "hello"), RunAgentParameters(runId = "r1"))
        session.send(UserMessage(id = "u2", content = "again"), RunAgentParameters(runId = "r2"))

        assertContentEquals(listOf("u1", "a-r1", "u2"), agent.inputs[1].messages.map { it.id })
        assertEquals("ok", (agent.inputs[1].messages[1] as AssistantMessage).content)
    }

    @Test
    fun the_message_is_on_screen_before_the_answer_starts() = runTest {
        val release = CompletableDeferred<Unit>()
        val agent = ScriptedAgent({ input ->
            flow {
                release.await()
                answer(input.runId, "a-${input.runId}", "ok").forEach { emit(it) }
            }
        })
        val session = AgentSession(agent)

        val sending = launch { session.send(UserMessage(id = "u1", content = "hello")) }
        testScheduler.runCurrent()

        val message = session.transcript.value.messages.single()
        assertEquals(UiRole.USER, message.role)
        assertEquals("hello", (message.parts.single() as TextPart).text)
        assertEquals(RunState.Idle, session.transcript.value.run)

        release.complete(Unit)
        sending.join()
        assertContentEquals(
            listOf(UiRole.USER, UiRole.ASSISTANT),
            session.transcript.value.messages.map { it.role },
        )
    }

    /**
     * A UI offering a retry needs the line it would retry still on screen -- and the retry itself
     * is [AgentSession.run], not a second [AgentSession.send], because the failed turn is already
     * the agent's history: `runAgentObservable` adopts the input's messages before the run that
     * fails. Both halves are measured here; without the second, a retry would ask the server the
     * question before last while the transcript showed the new one.
     */
    @Test
    fun a_failed_run_leaves_the_message_on_screen_and_in_the_history_to_retry_from() = runTest {
        var fail = true
        val agent = ScriptedAgent({ input ->
            flow {
                emit(RunStartedEvent(threadId = input.threadId, runId = input.runId))
                if (fail) {
                    emit(RunErrorEvent(message = "upstream is down", code = "BOOM"))
                } else {
                    answer(input.runId, "a-${input.runId}", "ok").drop(1).forEach { emit(it) }
                }
            }
        })
        val session = AgentSession(agent)

        val ended = session.send(UserMessage(id = "u1", content = "hello"))

        assertEquals(RunState.Failed("upstream is down", "BOOM"), ended)
        assertEquals("hello", (session.transcript.value.messages.single().parts.single() as TextPart).text)

        fail = false
        session.run(RunAgentParameters(runId = "r2"))

        // The retry asks the question that failed, once -- not the one before it, and not twice.
        assertContentEquals(listOf("u1"), agent.inputs[1].messages.map { it.id })
        assertContentEquals(
            listOf(UiRole.USER, UiRole.ASSISTANT),
            session.transcript.value.messages.map { it.role },
        )
    }

    /**
     * The same guarantee [AgentSession.run] gives, on a path where breaking it would be worse: the
     * line is already on screen, so a throw escaping here would leave a message no run state ever
     * accounts for. `runAgentObservable` calls the agent's own `run` before it returns a flow, so
     * an agent that fails to start fails while the stream is being built.
     */
    @Test
    fun a_throw_while_the_stream_is_being_built_leaves_the_message_and_a_failed_run() = runTest {
        val agent = ScriptedAgent({ error("offline") })
        val session = AgentSession(agent)

        val ended = session.send("hello")

        assertEquals(RunState.Failed("offline", AgentSession.CLIENT_ERROR_CODE), ended)
        assertEquals("hello", (session.transcript.value.messages.single().parts.single() as TextPart).text)
    }

    @Test
    fun sending_text_mints_an_id_per_message() = runTest {
        val agent = ScriptedAgent()
        val session = AgentSession(agent)

        session.send("one")
        session.send("two")

        val ids = session.transcript.value.messages.filter { it.role == UiRole.USER }.map { it.id }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertTrue(ids.all { it.isNotBlank() })
        assertContentEquals(ids, agent.inputs.map { it.messages.last().id })
    }

    /**
     * The defaults `AbstractAgent.prepareRunAgentInput` would have applied, applied here instead:
     * building the input by hand is what the send path costs, and a turn sent through it must not
     * reach the server in a different shape from one sent through [AgentSession.run].
     *
     * The state is asserted against the literal the agent was built with, not against
     * `agent.state`: `runAgentObservable` assigns `this.state = input.state` before it returns, so
     * comparing the two after the run passes for whatever was sent, empty object included.
     */
    @Test
    fun defaults_the_run_the_way_the_agent_would_have() = runTest {
        val state = buildJsonObject { put("cursor", JsonPrimitive("42")) }
        val agent = ScriptedAgent(config = AgentConfig(threadId = THREAD, initialState = state))
        val session = AgentSession(agent)

        session.send("hello")

        val sent = agent.inputs.single()
        assertTrue(sent.runId.isNotBlank())
        assertContentEquals(emptyList(), sent.tools)
        assertContentEquals(emptyList(), sent.context)
        assertEquals(JsonObject(emptyMap()), sent.forwardedProps)
        assertEquals(state, sent.state)
    }

    /**
     * Every field `RunAgentParameters` carries, carried. `tools` is the one that would be missed
     * most quietly -- a frontend tool the server is never told about is an answer that declines to
     * use it, not an error.
     */
    @Test
    fun passes_the_runs_parameters_through() = runTest {
        val agent = ScriptedAgent()
        val session = AgentSession(agent)
        val context = listOf(Context(description = "locale", value = "ja-JP"))
        val tools = listOf(Tool(name = "search", description = "look it up", parameters = JsonObject(emptyMap())))
        val forwardedProps = buildJsonObject { put("tenant", JsonPrimitive("acme")) }

        session.send(
            "hello",
            RunAgentParameters(runId = "r1", tools = tools, context = context, forwardedProps = forwardedProps),
        )

        val sent = agent.inputs.single()
        assertContentEquals(context, sent.context)
        assertContentEquals(tools, sent.tools)
        assertEquals(forwardedProps, sent.forwardedProps)
    }

    /**
     * The append happens under the same lock the run holds, so a line sent while the agent is still
     * answering does not land in the middle of that answer.
     */
    @Test
    fun a_send_waits_for_the_run_already_in_flight() = runTest {
        val release = CompletableDeferred<Unit>()
        val agent = ScriptedAgent({ input ->
            flow {
                emit(RunStartedEvent(threadId = input.threadId, runId = input.runId))
                if (input.runId == "r1") release.await()
                emit(TextMessageStartEvent(messageId = "a-${input.runId}"))
                emit(TextMessageContentEvent(messageId = "a-${input.runId}", delta = input.runId))
                emit(TextMessageEndEvent(messageId = "a-${input.runId}"))
                emit(RunFinishedEvent(threadId = input.threadId, runId = input.runId))
            }
        })
        val session = AgentSession(agent)

        val first = launch { session.run(RunAgentParameters(runId = "r1")) }
        val second = launch { session.send(UserMessage(id = "u1", content = "hello"), RunAgentParameters(runId = "r2")) }
        testScheduler.runCurrent()
        // The first run is in flight and nothing of the second turn is on screen, message included.
        assertEquals(RunState.Running("t", "r1"), session.transcript.value.run)
        assertContentEquals(emptyList(), session.transcript.value.messages.map { it.role })

        release.complete(Unit)
        first.join()
        second.join()

        assertContentEquals(
            listOf(UiRole.ASSISTANT, UiRole.USER, UiRole.ASSISTANT),
            session.transcript.value.messages.map { it.role },
        )
    }
}

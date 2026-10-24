package dev.ynagai.agui.a2ui

import com.agui.client.agent.AbstractAgent
import com.agui.client.agent.AgentConfig
import com.agui.core.types.BaseEvent
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolMessage
import com.agui.tools.toolRegistry
import dev.ynagai.agui.agent.AgentSession
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The ADK-shaped agent: `render_a2ui` called at the top level, and the answer waited on.
 *
 * Scripted rather than recorded, because upstream's recordings are all of the LangGraph shape,
 * where the call is closed on the server. This is the other model, the one the executor is for.
 */
class RenderA2UiToolTest {
    private val arguments = """{"surfaceId":"s","components":[{"id":"root","component":"Text","text":"hi"}]}"""

    /** Run 1 calls the tool; run 2 -- the one that carries the result -- says thanks. */
    private class RenderingAgent : AbstractAgent(AgentConfig(threadId = "t")) {
        val inputs = mutableListOf<RunAgentInput>()

        override fun run(input: RunAgentInput): Flow<BaseEvent> {
            inputs += input
            return flow {
                emit(RunStartedEvent(threadId = "t", runId = input.runId))
                if (input.messages.lastOrNull() !is ToolMessage) {
                    emit(ToolCallStartEvent(toolCallId = "r", toolCallName = AguiA2ui.RENDER_TOOL_NAME))
                    emit(ToolCallArgsEvent(toolCallId = "r", delta = """{"surfaceId":"s","components":[{"id":"root","component":"Text","text":"hi"}]}"""))
                    emit(ToolCallEndEvent(toolCallId = "r"))
                } else {
                    emit(TextMessageStartEvent(messageId = "m2"))
                    emit(TextMessageContentEvent(messageId = "m2", delta = "rendered, thanks"))
                    emit(TextMessageEndEvent(messageId = "m2"))
                }
                emit(RunFinishedEvent(threadId = "t", runId = input.runId))
            }
        }
    }

    @Test
    fun `a registered executor answers the call in the next run and the arguments draw`() = runTest {
        val agent = RenderingAgent()
        val session = AgentSession(agent, tools = toolRegistry(RenderA2UiTool()))

        val ended = session.send("draw something")
        assertIs<RunState.Finished>(ended)
        assertEquals(2, agent.inputs.size, "the call was answered by a run of its own")

        // The tool is declared on every run, and the second run carries the answer.
        assertTrue(agent.inputs.all { input -> input.tools.any { it.name == AguiA2ui.RENDER_TOOL_NAME } })
        val answer = assertIs<ToolMessage>(agent.inputs[1].messages.last())
        assertEquals("r", answer.toolCallId)
        assertTrue(answer.content.contains("\"status\":\"rendered\""), answer.content)

        // In the transcript: the call is complete, and its arguments are a surface.
        val transcript = session.transcript.value
        val call = transcript.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.COMPLETE, call.status)
        val carried = transcript.a2uiPayloads().single()
        assertEquals(A2uiCarrier.ToolArguments("r"), carried.carrier)
        val surfaces = assertIs<A2uiPayload.Surfaces>(carried.payload)
        assertEquals(listOf("s"), surfaces.surfaceIds)
    }

    @Test
    fun `a call with no components is answered with a failure the agent can read`() = runTest {
        val agent = object : AbstractAgent(AgentConfig(threadId = "t")) {
            val inputs = mutableListOf<RunAgentInput>()
            override fun run(input: RunAgentInput): Flow<BaseEvent> {
                inputs += input
                return flow {
                    emit(RunStartedEvent(threadId = "t", runId = input.runId))
                    if (input.messages.lastOrNull() !is ToolMessage) {
                        emit(ToolCallStartEvent(toolCallId = "r", toolCallName = AguiA2ui.RENDER_TOOL_NAME))
                        emit(ToolCallArgsEvent(toolCallId = "r", delta = """{"surfaceId":"s"}"""))
                        emit(ToolCallEndEvent(toolCallId = "r"))
                    }
                    emit(RunFinishedEvent(threadId = "t", runId = input.runId))
                }
            }
        }
        val session = AgentSession(agent, tools = toolRegistry(RenderA2UiTool()))
        session.send("draw nothing")
        val answer = assertIs<ToolMessage>(agent.inputs[1].messages.last())
        assertTrue(answer.content.contains("components"), answer.content)
        // Upstream reports an executor's failure as an ordinary result whose content says so --
        // the protocol has no failure event -- so the call is COMPLETE, and what its arguments
        // amount to is a payload the slot shows as unreadable rather than a surface.
        val call = session.transcript.value.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single()
        assertEquals(ToolCallStatus.COMPLETE, call.status)
        assertIs<A2uiPayload.Malformed>(session.transcript.value.a2uiPayloads().single().payload)
    }
}

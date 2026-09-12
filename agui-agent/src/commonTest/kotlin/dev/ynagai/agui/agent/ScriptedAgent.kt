package dev.ynagai.agui.agent

import com.agui.client.agent.AbstractAgent
import com.agui.client.agent.AgentConfig
import com.agui.core.types.BaseEvent
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An [AbstractAgent] whose `run` is a script rather than a socket.
 *
 * What it is handed on each run is kept, because what upstream sends as history is part of what is
 * under test -- and, since `AbstractAgent.setMessages` is protected, the only way to see what a
 * caller managed to put there.
 */
internal class ScriptedAgent(
    private val script: (RunAgentInput) -> Flow<BaseEvent> = { input ->
        flow { answer(input.runId, "a-${input.runId}", "ok").forEach { emit(it) } }
    },
    config: AgentConfig = AgentConfig(threadId = THREAD),
) : AbstractAgent(config) {
    val inputs = mutableListOf<RunAgentInput>()

    override fun run(input: RunAgentInput): Flow<BaseEvent> {
        inputs += input
        return script(input)
    }
}

/** The thread every agent in these tests is of. */
internal const val THREAD: String = "t"

/** One whole run: started, one text message assembled from [deltas], finished. */
internal fun answer(runId: String, messageId: String, vararg deltas: String): List<BaseEvent> = buildList {
    add(RunStartedEvent(threadId = THREAD, runId = runId))
    add(TextMessageStartEvent(messageId = messageId))
    deltas.forEach { add(TextMessageContentEvent(messageId = messageId, delta = it)) }
    add(TextMessageEndEvent(messageId = messageId))
    add(RunFinishedEvent(threadId = THREAD, runId = runId))
}

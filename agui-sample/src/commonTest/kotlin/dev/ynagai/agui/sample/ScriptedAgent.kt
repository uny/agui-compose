package dev.ynagai.agui.sample

import com.agui.client.agent.AbstractAgent
import com.agui.client.agent.AgentConfig
import com.agui.core.types.BaseEvent
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * An [AbstractAgent] whose run is a script rather than a socket, keeping what it was handed.
 *
 * `dispose()` is `final` upstream and cannot be observed by overriding it, so what is recorded is
 * its effect: the agent's own scope is cancelled, and that is the thing a leaked agent would still
 * have open.
 */
internal class ScriptedAgent(
    url: String,
    private val failing: Boolean = false,
) : AbstractAgent(AgentConfig(threadId = THREAD)) {
    val inputs: MutableList<RunAgentInput> = mutableListOf()
    val url: String = url

    val disposed: Boolean get() = !agentScope.isActive

    override fun run(input: RunAgentInput): Flow<BaseEvent> {
        inputs += input
        return flow {
            emit(RunStartedEvent(threadId = THREAD, runId = input.runId))
            if (failing) {
                emit(RunErrorEvent(message = "no", code = "NOPE"))
                return@flow
            }
            val messageId = "a-${input.runId}"
            emit(TextMessageStartEvent(messageId = messageId))
            emit(TextMessageContentEvent(messageId = messageId, delta = "ok"))
            emit(TextMessageEndEvent(messageId = messageId))
            emit(RunFinishedEvent(threadId = THREAD, runId = input.runId))
        }
    }
}

/** The thread every agent in these tests is of. */
internal const val THREAD: String = "t"

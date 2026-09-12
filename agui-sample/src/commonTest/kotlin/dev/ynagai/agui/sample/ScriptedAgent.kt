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
 * `dispose()` is observed by its effect rather than by overriding it: the agent's own scope is
 * cancelled, which is the thing a leaked agent would still have open, and it is the same signal
 * whatever an override does on top. `HttpAgent` overrides it to close the Ktor client it built --
 * that is the part a double has nothing to stand in for.
 *
 * The answer is Markdown, not because the sample cares what it says but because it is the only way
 * a test can tell that [MarkdownAguiTextRenderer][dev.ynagai.agui.markdown.MarkdownAguiTextRenderer]
 * is the renderer actually fitted: under the Material 3 default the same delta would draw as its
 * own source.
 */
internal class ScriptedAgent(
    val url: String,
    private val failing: Boolean = false,
) : AbstractAgent(AgentConfig(threadId = THREAD)) {
    val inputs: MutableList<RunAgentInput> = mutableListOf()

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
            emit(TextMessageContentEvent(messageId = messageId, delta = ANSWER))
            emit(TextMessageEndEvent(messageId = messageId))
            emit(RunFinishedEvent(threadId = THREAD, runId = input.runId))
        }
    }
}

/** The thread every agent in these tests is of. */
internal const val THREAD: String = "t"

/** What a scripted run answers with, on the wire. Markdown; see the class note. */
internal const val ANSWER: String = "**ok**"

/** The same answer once a Markdown renderer has drawn it. */
internal const val ANSWER_DRAWN: String = "ok"

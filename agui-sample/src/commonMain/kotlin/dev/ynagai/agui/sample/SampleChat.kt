package dev.ynagai.agui.sample

import com.agui.client.agent.AbstractAgent
import com.agui.client.agent.HttpAgent
import com.agui.client.agent.HttpAgentConfig
import dev.ynagai.agui.agent.AgentSession
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.UiTranscript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One connection to one AG-UI endpoint, and the transcript of talking to it.
 *
 * This is the seam the sample's UI is written against, and it is a plain class rather than Compose
 * state on purpose: the interesting behaviour -- what happens to the agent when the endpoint
 * changes, what a failed run leaves on screen -- is then testable without a composition and
 * without a socket.
 *
 * The thing this class owns that [AgentSession] deliberately does not is the *agent's lifetime*.
 * `AgentSession` says so in as many words: an agent may be shared with something outside the
 * session, so the owner that constructed it closes it. Here the sample is that owner -- it builds
 * one `HttpAgent` per endpoint -- so [connect] and [close] are where `dispose()` has to happen.
 * Leaving it out would leak an HTTP client per typed URL.
 *
 * @param agents how an endpoint URL becomes an agent. Defaulted to the upstream transport, and
 *   substituted in tests by an agent that emits a scripted stream -- which is the whole reason
 *   this is a parameter. An application that needs headers, an auth token or a timeout passes the
 *   other `HttpAgentConfig` fields here.
 */
public class SampleChat(
    private val agents: (url: String) -> AbstractAgent = { HttpAgent(HttpAgentConfig(url = it)) },
) {
    private val mutableConnection = MutableStateFlow<Connection?>(null)

    /**
     * The current connection, or `null` before the first [connect] and after [close].
     *
     * A `StateFlow` of a nullable rather than two fields, so that the UI reads one value and a
     * reconnect cannot be observed half-applied: the old agent is disposed and the new session
     * published in one assignment.
     */
    public val connection: StateFlow<Connection?> = mutableConnection.asStateFlow()

    /**
     * Points this chat at [url], disposing whatever it was pointed at before.
     *
     * A new agent means a new `threadId`, and therefore a new transcript: the thread is upstream's
     * unit and the old one belonged to the old endpoint. Nothing is validated beyond emptiness --
     * a URL that does not resolve is a run that fails, and a failed run is something this sample
     * is meant to show rather than something it should prevent.
     */
    public fun connect(url: String) {
        val trimmed = url.trim()
        require(trimmed.isNotEmpty()) { "An endpoint URL is needed to connect" }
        val agent = agents(trimmed)
        mutableConnection.value.let { previous ->
            mutableConnection.value = Connection(url = trimmed, agent = agent, session = AgentSession(agent))
            previous?.agent?.dispose()
        }
    }

    /**
     * Sends one line as a user turn and suspends until the run it starts has ended.
     *
     * Returns `null` when there is nothing to send it to, rather than throwing: "no endpoint yet"
     * is a state the window is in until someone types one, not a programming error.
     *
     * Runs are not gated here. [AgentSession] holds a mutex for the length of a run, so a second
     * send while one is streaming waits its turn and is then sent with the first run's answer
     * already in the history -- which is the library's documented behaviour and the thing a sample
     * should exercise rather than hide behind a disabled button.
     */
    public suspend fun send(text: String): RunState? = mutableConnection.value?.session?.send(text)

    /**
     * Disposes the agent and forgets the connection.
     *
     * Called when the window closes. Idempotent: the second call has nothing to dispose.
     */
    public fun close() {
        mutableConnection.value?.agent?.dispose()
        mutableConnection.value = null
    }

    /**
     * One endpoint, the agent talking to it, and the transcript that has accumulated.
     *
     * The agent is here rather than private because it carries the [AbstractAgent.threadId] the
     * transcript is *of*, which the UI shows -- and because [SampleChat] needs it to dispose.
     */
    public class Connection(
        public val url: String,
        public val agent: AbstractAgent,
        public val session: AgentSession,
    ) {
        /** The transcript of this thread, growing with every run. */
        public val transcript: StateFlow<UiTranscript> get() = session.transcript
    }
}

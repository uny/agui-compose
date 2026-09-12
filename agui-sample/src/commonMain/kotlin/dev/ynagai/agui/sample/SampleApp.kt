package dev.ynagai.agui.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ynagai.agui.compose.AguiTranscript
import dev.ynagai.agui.markdown.MarkdownAguiTextRenderer
import dev.ynagai.agui.markdown.markdownAguiColors
import dev.ynagai.agui.markdown.markdownAguiTypography
import dev.ynagai.agui.material3.ProvideMaterial3Agui
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.UiTranscript
import kotlinx.coroutines.launch

/**
 * The whole sample: an endpoint to point at, a transcript, and a line to type into.
 *
 * This is the README's own snippets assembled once, in the order an application would assemble
 * them -- `MaterialTheme` / `Surface` / `ProvideMaterial3Agui` / `AguiTranscript`, with a Markdown
 * renderer fitted through the text-renderer parameter -- around an [AgentSession][dev.ynagai.agui.agent.AgentSession]
 * driven by [SampleChat]. Nothing is stubbed: what draws here is what the published modules draw,
 * fed by whatever AG-UI server the endpoint field names.
 *
 * The endpoint starts empty. There is no default server to talk to and inventing one would tie
 * this sample to somebody's uptime; the README says which one to run locally.
 */
@Composable
public fun SampleApp(chat: SampleChat, modifier: Modifier = Modifier) {
    // The window owns the agent's lifetime because it owns the `SampleChat` -- closing it here is
    // what `AgentSession` says the constructing owner has to do, and the entry point is where a
    // desktop window's disposal is observable.
    DisposableEffect(chat) { onDispose { chat.close() } }

    val connection by chat.connection.collectAsState()
    val transcript by (connection?.transcript?.collectAsState() ?: remember { mutableStateOf(UiTranscript()) })
    val scope = rememberCoroutineScope()

    var endpoint by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }

    // `remember` with no keys, as the README insists. The local it is provided through is static,
    // so a renderer rebuilt on recomposition would re-render every visible message on every frame
    // of a streaming answer. The lambdas are `@Composable`, so the theme is still followed.
    val textRenderer = remember {
        MarkdownAguiTextRenderer(
            colors = { markdownAguiColors(text = LocalContentColor.current) },
            typography = { markdownAguiTypography(base = LocalTextStyle.current) },
        )
    }

    val listState = rememberLazyListState()

    MaterialTheme {
        // Not decoration: `MaterialTheme` leaves `LocalContentColor` at Material 3's default black,
        // and a `Surface` is what derives it from the background it paints. Without one this
        // transcript draws black prose on a dark ground in dark mode.
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EndpointBar(
                    endpoint = endpoint,
                    onEndpointChange = { endpoint = it },
                    onConnect = { chat.connect(endpoint) },
                    connectedTo = connection?.url,
                )

                Text(
                    text = statusLine(connection?.agent?.threadId, transcript.run),
                    style = MaterialTheme.typography.labelMedium,
                )

                ProvideMaterial3Agui(textRenderer = textRenderer) {
                    AguiTranscript(
                        transcript = transcript,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        state = listState,
                    )
                }

                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    enabled = connection != null,
                    onSend = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            draft = ""
                            // Launched rather than awaited, and the field is not disabled while it
                            // runs: `send` suspends for the length of the run, and a second send
                            // meanwhile queues behind the session's own mutex. That queueing is
                            // library behaviour worth having on screen.
                            scope.launch { chat.send(text) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EndpointBar(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    onConnect: () -> Unit,
    connectedTo: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("AG-UI endpoint") },
            placeholder = { Text("http://localhost:8000/") },
        )
        Button(
            onClick = onConnect,
            // The only validation there is. A URL that does not resolve is a run that fails, and
            // showing a failed run is part of what this sample is for.
            enabled = endpoint.isNotBlank() && endpoint.trim() != connectedTo,
        ) {
            Text(if (connectedTo == null) "Connect" else "Reconnect")
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            label = { Text("Message") },
        )
        Button(onClick = onSend, enabled = enabled && draft.isNotBlank()) {
            Text("Send")
        }
    }
}

/**
 * The transcript's *status*, which `AguiTranscript` deliberately does not draw.
 *
 * Internal rather than private so the tests can read it: this is the one place the sample turns a
 * [RunState] into words, and a `Failed` whose message never reached the screen would defeat the
 * point of the library recording it.
 */
internal fun statusLine(threadId: String?, run: RunState): String {
    val thread = threadId?.let { "thread $it" } ?: "not connected"
    val state = when (run) {
        is RunState.Idle -> "idle"
        is RunState.Running -> "running ${run.runId}"
        is RunState.Finished -> if (run.interrupted) "interrupted" else "finished"
        is RunState.Failed -> "failed: ${run.message}" + (run.code?.let { " ($it)" } ?: "")
    }
    return "$thread — $state"
}

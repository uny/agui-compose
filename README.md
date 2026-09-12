# agui-compose

A Compose Multiplatform client for the [AG-UI protocol](https://github.com/ag-ui-protocol/ag-ui).

**This library rides on the upstream community Kotlin SDK
(`com.ag-ui.community:kotlin-core` / `kotlin-client`). It is not a second implementation of the
protocol.** The event types are upstream's, and so are the transport and the SSE parser. What is
added here is the layer upstream has no opinion about: a render model that keeps the order an agent
produced things in, and the Compose UI that draws it. The one function this library does replace,
and why, is in
[docs/decisions/0001](docs/decisions/0001-riding-on-the-upstream-kotlin-sdk.md); the UI layer's own
three — no design system, no Markdown parser, one fewer target — are in
[docs/decisions/0002](docs/decisions/0002-what-the-ui-layer-does-not-depend-on.md), and what the
Material 3 layer decides on your behalf is in
[docs/decisions/0003](docs/decisions/0003-what-material-3-decides-for-you.md). Fitting a Markdown
parser under all of it, without a design system to style it from, is
[docs/decisions/0004](docs/decisions/0004-parsing-markdown-without-a-design-system.md). Where the
upstream transport meets all of that — and what it decides about Ktor on your behalf — is
[docs/decisions/0005](docs/decisions/0005-running-the-upstream-agent.md). Why saying something needs
a second entry point, and why upstream's own API leaves no other way in, is
[docs/decisions/0006](docs/decisions/0006-saying-something.md).

Chat is the first surface, not the boundary. AG-UI's 33 events cover streaming text, reasoning,
tool calls, human-in-the-loop approval, shared state, generative UI surfaces, run lifecycle,
multimodal input and steering — this library is aimed at all of it.

> **Status: pre-release.** Nothing is published yet and the API is not stable.

## Modules

| Module | What it is | Published |
| --- | --- | --- |
| `agui-model` | The render model: `UiMessage` as an ordered list of parts, each with its own streaming state. No UI framework, no protocol types. | not yet |
| `agui-core` | Folds an AG-UI event stream into that model, including the `ACTIVITY_*` events the upstream reducer does not handle. | not yet |
| `agui-compose` | Draws a `UiTranscript`. Compose runtime and foundation only — no design system, no Markdown parser, one overridable slot per part kind. | not yet |
| `agui-material3` | Fills every one of those slots with Material 3: bubbles, a reasoning disclosure, tool-call and attachment surfaces. The first layer that is meant to be looked at. | not yet |
| `agui-markdown` | Draws prose as GitHub Flavored Markdown through the text renderer slot, parsing incrementally while a run is still arriving. Depends on `agui-compose` and a parser; no design system. | not yet |
| `agui-agent` | Runs an upstream `AbstractAgent` and keeps its transcript: one render model per thread, fed by every run, observable as a `StateFlow`. Brings the upstream client — and the Ktor engine it chose per platform. | not yet |
| `agui-sample` | A desktop window that talks to a real AG-UI server: the whole stack above, assembled the way an application would. See [The sample](#the-sample). | never |

`agui-a2ui` (the [A2UI](https://github.com/uny/a2ui-compose) bridge, as an optional dependency) and
the `agui-provider-*` adapters come next.

## Targets

`agui-model`, `agui-core` and `agui-agent`: `androidTarget`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `iosX64` —
the five the upstream SDK publishes.

`agui-compose`, and every module that draws: the same set **minus `iosX64`**. Compose Multiplatform
1.12.0 does not publish an `ios_x64` variant of `foundation`, `ui` or `runtime`, so no Compose
module can reach an Intel simulator regardless of how it is configured. The lower two modules keep
the fifth target rather than being trimmed to match — folding an event stream needs no Compose, and
a consumer on an Intel simulator can still do it and render the result with something else.

**There is no browser target and no Kotlin/Native macOS target,** because upstream publishes
neither and its `STATE_DELTA` dependency cannot reach them either. Compose Multiplatform on the
desktop — macOS included — is the `jvm` target and is supported. The full reasoning, and what would
change the answer, is in [docs/decisions/0001](docs/decisions/0001-riding-on-the-upstream-kotlin-sdk.md).

## Why the render model is not the SDK's transcript

Upstream's `AgentState` holds an assistant message as one `content` string plus a separate
`toolCalls` list, and puts reasoning in a channel of its own. An agent that speaks, calls a tool,
and speaks again produces a state from which the order of those three is not recoverable — and the
order is what a chat UI draws.

`agui-model` keeps it:

```kotlin
UiMessage(
    id = "a1",
    role = UiRole.ASSISTANT,
    parts = listOf(
        ReasoningPart(text = "weighing it up", streaming = false, /* … */),
        TextPart(text = "Let me look.", /* … */),
        ToolCallPart(name = "search", status = ToolCallStatus.COMPLETE, result = "3 hits", /* … */),
        TextPart(text = "Found three.", /* … */),
    ),
)
```

Each part carries its own streaming state, because a tool call can still be running under text that
has already finished.

## Usage

```kotlin
import dev.ynagai.agui.core.foldToTranscript

agent.run(input)                    // Flow<BaseEvent>, from the upstream SDK
    .foldToTranscript()             // Flow<UiTranscript>
    .collect { transcript -> render(transcript) }
```

One transcript per event, so a renderer sees every frame. A renderer that wants fewer can
`conflate()` — which it could not do if this had already dropped them.

That is one run. A conversation is many, and `foldToTranscript` starts from empty on every
collection — so for a thread, hold an `AgentSession` around the upstream agent instead:

```kotlin
import com.agui.client.agent.HttpAgent
import com.agui.client.agent.HttpAgentConfig
import dev.ynagai.agui.agent.AgentSession

val session = AgentSession(HttpAgent(HttpAgentConfig(url = "https://…/agui")))

session.transcript                  // StateFlow<UiTranscript>, grows with every run
session.send("What changed today?") // the user's turn, then the run it starts
// or, with no new turn to send -- a retry, a resumed interrupt:
session.run()                       // runs from the history the agent already holds
```

`send` is how a client says something, and it is not a convenience over `run`: upstream's
`RunAgentParameters` carries no messages and `AbstractAgent.setMessages` is protected, so a run
started from parameters alone can only ever send the history the agent already holds. `send` puts
the turn on screen before the first event of the run arrives — it waits for a run already in
flight, so "before the answer" is a promise about the answer to *this* turn, not about the clock —
sends it with that history, and leaves it on screen if the run fails. Pass a `UserMessage` instead
of a `String` to supply the id yourself, or to send files alongside the text.

Retry a failed turn with `run`, not with a second `send`. The turn is already the agent's history
by the time the run fails, so `run` asks it again; `send` would append it a second time, and the
server would be asked twice.

The transcript is the report: a run that fails — `RUN_ERROR` from the agent, or a stream that
broke the protocol and was rejected by upstream's verifier — ends in `RunState.Failed` rather than
an exception thrown into whatever coroutine a button launched it from. Cancelling the coroutine is
recorded the same way, then propagates. Runs on one session take turns. The session does not own
the agent's lifetime — `dispose()` it yourself, once — and it does not choose the HTTP engine:
upstream fixes that per platform (CIO on the JVM, `ktor-client-android` on Android, Darwin on iOS),
and the only way to substitute one is the `HttpClient` parameter on `HttpAgent`.

### Drawing it

```kotlin
import dev.ynagai.agui.compose.AguiTranscript

AguiTranscript(transcript)
```

`agui-compose` sits below any design system: the defaults draw structure and no colour, spacing or
shape. Each kind of part goes through a slot in `AguiComponents`, replaced one at a time by `copy`:

```kotlin
val components = remember {
    AguiComponents().copy(
        toolCall = { part, modifier -> MyToolCallCard(part, modifier) },
    )
}

CompositionLocalProvider(LocalAguiComponents provides components) { AguiTranscript(transcript) }
```

**`remember` the table, rather than building it in the `provides`.** Both locals here are
`staticCompositionLocalOf`, which does not track reads: a value it does not consider equal to the
last one recomposes the whole subtree under it, skipping disabled. A slot lambda that captures
anything — a click handler, a theme value, view-model state — makes a fresh unequal table on every
recomposition of the composable holding the provider, so every visible part redraws on every frame
of a streaming response. Measured on a three-message transcript: a capturing override re-runs all
three part slots per recomposition, a `remember`ed one re-runs none.

**Markdown is not a dependency of this library.** `TEXT_MESSAGE_CONTENT` carries a string and the
protocol says nothing about its syntax, so which flavour to parse — and whether to parse at all —
is the application's call. The default renderer draws the text literally; a richer one is fitted
through `LocalAguiTextRenderer`, and serves both prose parts at once:

```kotlin
val renderer = remember {
    AguiTextRenderer { text, streaming, modifier ->
        Markdown(text, modifier)   // any renderer you like
    }
}

CompositionLocalProvider(LocalAguiTextRenderer provides renderer) { AguiTranscript(transcript) }
```

The renderer is handed the whole run as it currently stands rather than the latest delta, so
re-parsing on every recomposition is correct; an incremental parser is an optimisation, not a
requirement. The `streaming` flag is there so a parser can tell a run still arriving from a finished
one.

`agui-markdown` is that renderer, written once:

```kotlin
val renderer = remember { MarkdownAguiTextRenderer() }
```

It parses incrementally while `streaming` is true — the settled part of the document stays parsed
and only the tail is re-read — and parses the finished text complete when the run ends. **Its
defaults draw black text at Compose's default size,** because the module sits below any design
system and has nothing ambient to read; under Material 3, hand it the ambient values:

```kotlin
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle

val renderer = remember {
    MarkdownAguiTextRenderer(
        colors = { markdownAguiColors(text = LocalContentColor.current) },
        typography = { markdownAguiTypography(base = LocalTextStyle.current) },
    )
}
```

Both locals come from `material3`, which `agui-markdown` does not depend on — you already have it if
you are under `ProvideMaterial3Agui`, and you need to declare it if you are not.

Those are `@Composable` lambdas rather than values so that one `remember` with no keys still follows
a theme change — reconstructing the renderer to pick one up would re-parse every visible run on
every frame. The reasoning, and why this is a separate module from `agui-material3`, is in
[docs/decisions/0004](docs/decisions/0004-parsing-markdown-without-a-design-system.md).

### Making it look like something

`agui-material3` is that slot table, filled in:

```kotlin
import dev.ynagai.agui.material3.ProvideMaterial3Agui

MaterialTheme {
    Surface {
        ProvideMaterial3Agui {
            AguiTranscript(transcript)
        }
    }
}
```

User messages become end-aligned tonal bubbles, assistant answers stay full width, reasoning
collapses into a disclosure when its stream ends, and tool calls and attachments get surfaces of
their own. `MaterialTheme` stays yours — this provides no theme of its own, so your colour scheme
and type scale are what it draws with.

The `Surface` is not decoration. `MaterialTheme` sets a colour scheme but does not provide
`LocalContentColor`, which is left at Material 3's own default of black; a `Surface` is what
derives it from the background it paints. Without one, a dark theme draws this transcript's prose
black on a dark ground. Any `Scaffold` or `Surface` you already have counts — but the two-composable
version of this snippet is a trap in dark mode, so it is written with three.

It provides **two** composition locals, and that is why the function exists: the slot table, and a
text renderer that draws through Material 3's `Text` instead of `BasicText`. Providing only the
first would leave prose ignoring your theme entirely.

Overriding is the same `copy` as above, and fitting a Markdown renderer is the same
`LocalAguiTextRenderer` — the prose slots here draw *through* it rather than calling `Text`
themselves, so a renderer you fit keeps every Material 3 frame around it:

```kotlin
ProvideMaterial3Agui(
    textRenderer = remember {
        MarkdownAguiTextRenderer(
            colors = { markdownAguiColors(text = LocalContentColor.current) },
            typography = { markdownAguiTypography(base = LocalTextStyle.current) },
        )
    },
    components = remember { Material3AguiComponents().copy(file = ::MyAttachmentTile) },
) {
    AguiTranscript(transcript)
}
```

What it deliberately does not do: parse Markdown (that is `agui-markdown`), load images for
attachments, or render an `ACTIVITY_SNAPSHOT` payload (that is `agui-a2ui`). Each of those is a
dependency with an opinion, and each is one `copy` away for an application that wants it. The
reasoning is in [docs/decisions/0003](docs/decisions/0003-what-material-3-decides-for-you.md).

## The sample

`agui-sample` is one desktop window: an endpoint to point at, a transcript, and a line to type
into. It is the whole stack above assembled once — `AgentSession` over upstream's `HttpAgent`,
drawn by `AguiTranscript` under `ProvideMaterial3Agui`, with `agui-markdown` fitted through the
text-renderer slot — and nothing in it is stubbed.

```
./gradlew :agui-sample:run
```

**The endpoint field starts empty, and the sample ships with no server.** There is no hosted
endpoint to point it at that would not tie this repository to somebody else's uptime, so what it
talks to is a server you run. The smallest one is upstream's own, and it needs no API key:

```
git clone https://github.com/ag-ui-protocol/ag-ui
cd ag-ui/integrations/server-starter/python/examples
uv run dev
```

That listens on `http://localhost:8000/` (`PORT` moves it) and answers every turn with a fixed
`Hello world!`. The reply is canned; everything under it is not — a real HTTP request, a real SSE
stream of AG-UI events, upstream's parser and verifier, and this library's reducer and renderers.
It is what the sample was verified against.

For an agent that actually thinks, any of the other
[integrations](https://github.com/ag-ui-protocol/ag-ui/tree/main/integrations) serves the same
protocol on the same shape of endpoint; those are LLM-backed and want a provider key of their own.
The sample does not care which — it is a URL.

**One dependency clash an application has to resolve itself.** Upstream's `kotlin-core` and
`kotlin-client` 0.4.1 are compiled against kotlinx-datetime 0.6.2; Compose Material 3 1.9.0 brings
0.7.1, where `Clock` and `Instant` moved to `kotlin.time` and the old classes are gone. Gradle picks
0.7.1, everything compiles, and the first event upstream timestamps dies with
`NoClassDefFoundError: kotlinx/datetime/Clock$System`. Any application taking `agui-material3` and
`agui-agent` together will meet this. The fix is one line, and it is what `agui-sample` does:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")
```

That artifact is 0.8.0 with the 0.6.x binary surface kept, published for exactly this, so both
sides find what they were compiled against.

What the sample deliberately does not do yet: run frontend tools, render an `ACTIVITY_SNAPSHOT`,
or build for Android or iOS. It is one target and one screen, and the module is laid out so that
the second target is a source set rather than a rewrite.

## Building

```
./gradlew build
```

Requires **JDK 21**. That floor is inherited, not chosen: upstream's `kotlin-core-jvm` is Java 21
bytecode, so a JDK 17 build cannot load the types this library is built on.

The Apple targets need macOS. On Linux they are skipped rather than failed, so a green Linux build
says less than it appears to -- which is why CI runs on macOS.

## Licence

Apache 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

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
[docs/decisions/0006](docs/decisions/0006-saying-something.md). And what a sample application found
that no module's own tests could — a dependency neither of two modules names, on which they
disagree — is
[docs/decisions/0007](docs/decisions/0007-pinning-a-dependency-neither-module-names.md). How a
tool the agent calls gets executed here and answered, and why upstream's own handler for that is
not used, is [docs/decisions/0008](docs/decisions/0008-executing-a-tool-on-the-client.md). What a
run that stopped to ask a human looks like, and why a tool result is not an answer to it, is
[docs/decisions/0010](docs/decisions/0010-answering-what-a-run-stopped-to-ask.md). What a run has
to carry for an agent to draw at all is
[docs/decisions/0011](docs/decisions/0011-asking-an-agent-to-draw.md). How a version gets from a
tag to Maven Central, and what stands between the two, is
[docs/decisions/0012](docs/decisions/0012-releasing-to-maven-central.md).

Chat is the first surface, not the boundary. AG-UI's 33 events cover streaming text, reasoning,
tool calls, human-in-the-loop approval, shared state, generative UI surfaces, run lifecycle,
multimodal input and steering — this library is aimed at all of it.

> **Status: `0.1.0` is on Maven Central, and the API is not stable.** A `0.x` line: every public
> signature is covered by a checked-in ABI dump, so a change to one is a visible diff rather than
> a surprise, but nothing is promised across versions yet.

## Installation

```kotlin
dependencies {
    implementation("dev.ynagai.agui:agui-material3:0.1.0")
    implementation("dev.ynagai.agui:agui-agent:0.1.0")
}
```

That is the usual pair: `agui-material3` brings `agui-compose`, `agui-model` and the slots, and
`agui-agent` brings `agui-core` and the upstream client. Add `agui-markdown` for prose as
Markdown, and `agui-a2ui-material3` -- which brings `agui-a2ui-compose` and `agui-a2ui` -- when
the agent draws. Each lower module is published on its own for a host that wants less: `agui-core`
to fold events with no Compose at all, `agui-compose` to draw with a design system other than
Material 3. Coordinates are `dev.ynagai.agui:<module>:0.1.0`; the [Modules](#modules) table says
what each carries, and [Targets](#targets) which platforms.

### What a consumer has to be on

Three floors, and they are not the same for every module. Each is what the published artifacts
declare, read from them rather than from this file's intentions.

| Floor | `agui-model` / `agui-core` / `agui-agent` | `agui-a2ui` | Every module that draws | How it fails |
| --- | --- | --- | --- | --- |
| `compileSdk` | **24** | **37** | **37** | AGP's `checkAarMetadata` names the module and the version it wants. `compileSdk` is what you compile against; `targetSdk` and `minSdk` need not move. |
| Compose Multiplatform | none | none | **1.12.0** | Silently. Gradle takes the highest version, so a lower one you declare is raised without a message; holding it with `strictly` wins, and the mismatch surfaces at compile or run time instead of at resolution. |
| Kotlin | **2.4** line | **2.4** line | **2.4** line | A 2.3 compiler reads the JVM and Android artifacts (metadata one minor version ahead is readable) and refuses the iOS klibs (`incompatible ABI version '2.4.0'`). |

The `compileSdk` split is the point of publishing the lower modules on their own: the three carry
no Compose, and the AARs they depend on ask for nothing, so they sit at `minSdk`. `agui-a2ui`
draws nothing either but rides on `a2ui-core`, whose AAR asks for 37. The split is the next
release's: `0.1.0` was published from one shared value, and its AARs declare 37 for all nine
modules.

**The Kotlin floor is the one that bites**: the Kotlin version is project-wide, so a project held
on 2.3 by anything at all cannot move for one library — and if it targets iOS, it cannot take these
artifacts. KSP is not that anything: it has no 2.4-numbered release, but its 2.3 line runs on a
Kotlin 2.4 project (measured: KSP 2.3.12 with Moshi's codegen on Kotlin 2.4.10, JVM, and the plugin
applied to a KMP project with iOS targets), so Room, Dagger or Moshi alone do not hold a consumer
back. That floor is inherited from two dependencies rather than chosen, and it is coming down: the
plan, and what it waits on, is [#20](https://github.com/uny/agui-compose/issues/20). Until then the
`0.x` line tracks the newest Kotlin.

## Modules

| Module | What it is | Version |
| --- | --- | --- |
| `agui-model` | The render model: `UiMessage` as an ordered list of parts, each with its own streaming state. No UI framework, no protocol types. | `0.1.0` |
| `agui-core` | Folds an AG-UI event stream into that model, including the `ACTIVITY_*` events the upstream reducer does not handle. | `0.1.0` |
| `agui-compose` | Draws a `UiTranscript`. Compose runtime and foundation only — no design system, no Markdown parser, one overridable slot per part kind. | `0.1.0` |
| `agui-material3` | Fills every one of those slots with Material 3: bubbles, a reasoning disclosure, tool-call and attachment surfaces, an approval card. The first layer that is meant to be looked at. | `0.1.0` |
| `agui-markdown` | Draws prose as GitHub Flavored Markdown through the text renderer slot, parsing incrementally while a run is still arriving. Depends on `agui-compose` and a parser; no design system. | `0.1.0` |
| `agui-agent` | Runs an upstream `AbstractAgent` and keeps its transcript: one render model per thread, fed by every run, observable as a `StateFlow`. Brings the upstream client — and the Ktor engine it chose per platform. | `0.1.0` |
| `agui-a2ui` | Reads A2UI out of a transcript — from an `a2ui-surface` activity, a streamed `render_a2ui` call, or a tool result carrying `a2ui_operations` — and turns upstream's v0.9 envelopes into the v1.0 messages [a2ui-compose](https://github.com/uny/a2ui-compose) parses. Decides which carrier draws a surface that arrived in several. No Compose. | `0.1.0` |
| `agui-a2ui-compose` | Keeps an `A2uiRenderer` up to date with a transcript and fills the `activity` and `toolCall` slots with its surfaces. No design system. | `0.1.0` |
| `agui-a2ui-material3` | The basic catalog's Material 3 renderers and a Material 3 "building UI" state, so a Material 3 transcript draws A2UI with one call. | `0.1.0` |
| `agui-replay` | A Ktor server that replays upstream's recorded A2UI traffic over SSE — the sample's server, and the trace-driven tests' fixtures. JVM only. See [Replaying upstream](#replaying-upstream). | never |
| `agui-sample` | A desktop window that talks to a real AG-UI server: the whole stack above, assembled the way an application would. See [The sample](#the-sample). | never |

The `agui-provider-*` adapters come next.

## Targets

`agui-model`, `agui-core` and `agui-agent`: `androidTarget`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `iosX64` —
the five the upstream SDK publishes. Their Android variant compiles against API 24, not the 37 the
rest of the repository needs; see [what a consumer has to be on](#what-a-consumer-has-to-be-on).

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
// or, with no new turn to send -- a retry:
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

### Executing a tool here

A frontend tool is one the agent calls and the client runs. Hand the session a `ToolRegistry` from
upstream's `kotlin-tools` — it arrives with `agui-agent` — and the tools in it are declared on
every run, executed when called, and answered:

```kotlin
import com.agui.tools.AbstractToolExecutor
import com.agui.tools.ToolExecutionContext
import com.agui.tools.ToolExecutionResult
import com.agui.tools.toolRegistry

class ChangeBackground : AbstractToolExecutor(Tool(name = "change_background", …)) {
    override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult {
        paint(context.toolCall.function.arguments)   // whatever the tool does
        return ToolExecutionResult.success(buildJsonObject { put("changed", true) })
    }
}

val session = AgentSession(agent, tools = toolRegistry(ChangeBackground()))
session.send("tool")   // returns when the agent has been told the result and has answered it
```

The protocol has one channel for a tool's result: the next run's input. So a run that called a
tool is answered by a run of its own — the same tools, context and forwarded properties, the
thread's history with the result placed after its call — and `send` or `run` suspends until a run
ends without calling one. The call is drawn `AWAITING_RESULT` while the tool executes and `COMPLETE` once it has
a result, in the transcript, before the answering run starts. A tool the registry does not hold is
left alone: a backend tool's events fold exactly as they do with no registry, and a run that stops
to *ask* -- an interrupt outcome -- is not answered by a tool result at all; see the next section.

`RunState.Finished` is the run's verdict, not the turn's — a transcript reads it while a tool is
still executing and again between a run and the run that answers it. Gate "the agent is done" on
the suspend call returning. A result whose run failed, or was cancelled, is kept and sent ahead of
the next turn rather than dropped; the reasoning, and why upstream's own `ClientToolResponseHandler`
is not what sends it, is in
[docs/decisions/0008](docs/decisions/0008-executing-a-tool-on-the-client.md).

### Answering what a run stopped to ask

A run that needs something only a human can give -- an approval, a choice -- ends with
`RUN_FINISHED` whose outcome is an interrupt, and the protocol lets no run start on the thread until
every interrupt of that run has been answered or abandoned in the next input's `resume` list. The
transcript carries the questions on the run, and the session answers them:

```kotlin
val ended = session.send("Transfer 100 to Alice") as RunState.Finished
ended.interrupts                // what the run stopped to ask: prompt, schema, the call it concerns
session.resume(
    ended.interrupts.map { UiResumeEntry.resolved(it, buildJsonObject { put("approved", JsonPrimitive(true)) }) },
)                               // one entry per interrupt; the run that carries them, then its answer
```

`resume` throws before the run starts if the entries leave an interrupt uncovered, name one the
thread is not waiting on, or name one twice -- the reference client's rules, and omitting an
interrupt is not abandoning it (`UiResumeEntry.cancelled` is). `send` and `run` throw while the
thread is interrupted, for the same reason: a run the server would refuse is not a run to start. A
resume whose run *fails* is still owed -- the thread keeps waiting, `session.pendingInterrupts`
(a `StateFlow`) still names the questions after the transcript's `RunState.Failed` has stopped
naming them, and either `run()`, which carries the answers the failed run carried, or a second
`resume` retries it. Whether an interrupt has *expired* is not
judged here: `expiresAt` is carried for you to read, and a producer that will not take a late
answer fails the run. The reasoning, and why a tool result is not an answer to an interrupt, is in
[docs/decisions/0010](docs/decisions/0010-answering-what-a-run-stopped-to-ask.md).

A tool call the interrupt names is drawn `AWAITING_APPROVAL` until the next run starts. The
question itself is drawn by `AguiInterrupts(interrupts, onResume)`, placed wherever the
application wants it -- it is the thread's status, not a message, so `AguiTranscript` does not
draw it -- and `agui-material3` fills the slot with a card whose *Approve* resolves with
`{"approved": true}` when the schema names that property and *Decline* abandons.

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

### Drawing A2UI

[A2UI](https://a2ui.org/) is how an agent describes a user interface as JSON for the client to draw
with its own widgets. Over AG-UI it arrives in three carriers, and the same surface commonly
arrives in all three during one run: an `ACTIVITY_SNAPSHOT` with `activityType: "a2ui-surface"`
that upstream's `a2ui-middleware` synthesises and repaints progressively; the streamed arguments
of a `render_a2ui` tool call; and a tool result whose content holds `a2ui_operations`. `agui-a2ui`
reads all three off the transcript, decides which one draws a surface that came in several
(activity over result over arguments, so nothing is drawn twice), and hands
[a2ui-compose](https://github.com/uny/a2ui-compose) the messages — deleting a surface before a
cumulative snapshot recreates it, since a2ui-core refuses to create one that exists.

```kotlin
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiRendererConfig
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.agui.a2ui.compose.rememberA2uiHost
import dev.ynagai.agui.a2ui.material3.withMaterial3A2ui

val renderer = remember { A2uiRenderer(A2uiRendererConfig.Default.withCatalogs(listOf(BasicCatalog.definition))) }
val host = rememberA2uiHost(transcript, renderer)
val components = remember(host) { Material3AguiComponents().withMaterial3A2ui(host) }

ProvideMaterial3Agui(components = components) { AguiTranscript(transcript) }
```

That draws the basic catalog. A catalog is the renderer's trust boundary — the agent may name only
what the client holds — so an agent that generates its own components needs their
`CatalogDefinition` in the renderer and their `ComponentRenderer`s in the registry
(`Material3Components.Basic.with(...)`); the sample's `DojoCatalog` is one of those, for upstream's
dojo. An action a user takes on a surface reaches `onMessage` as a2ui-core's `ActionMessage`;
`toForwardedProps()` and `toUserText()` are the two shapes upstream reads it in, and sending it is
yours.

**Versions.** Upstream emits A2UI **v0.9** envelopes; a2ui-compose implements **v1.0** and nothing
older. For the four messages upstream sends the wire difference is mechanical — the version
string, a dropped `theme`, a renamed `attachDataModel`, a `value` that became required — and
`agui-a2ui` rewrites the JSON before a2ui-core parses it, checked against upstream's recorded
traffic rather than against the evolution guide's word. One thing is not mechanical: upstream's
basic catalog id is `…/v0_9/basic_catalog.json`, which is not even the v0.9 specification's, and
`A2uiTranslation` maps upstream's spellings -- that URL, the v0.9 specification's, and a bare
`basic` -- to the v1.0 basic catalog and passes any other id through. The reasoning is in
[docs/decisions/0009](docs/decisions/0009-drawing-a2ui-from-three-carriers.md).

**Asking for a surface.** None of the above arrives unless the run asks. Upstream's agents inject
their `generate_a2ui` tool only when the run's `forwardedProps` carries `injectA2UITool`, and put
the client's components in front of the model only when its `context` carries the component
schema — both things `@ag-ui/a2ui-middleware` adds to every request when it sits in front of the
agent, which nothing does here. `A2uiRequest` builds both, plus the render tool's usage guide the
middleware sends beside the schema, for the catalog the renderer holds; send it on every run:

```kotlin
import com.agui.client.agent.RunAgentParameters
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.agui.a2ui.A2uiRequest

val request = A2uiRequest(BasicCatalog.definition)
val parameters = RunAgentParameters(context = request.context(), forwardedProps = request.forwardedProps())
session.send("Show me the hotels", parameters)
```

Without it, upstream's adapters never inject their tool: the agent answers in text and the stream
says nothing about why. An agent that calls `render_a2ui` itself, through a `RenderA2UiTool` the
client registered, needs no flag to draw. The reasoning, and what
was and was not measured against a live server, is in
[docs/decisions/0011](docs/decisions/0011-asking-an-agent-to-draw.md).

`RenderA2UiTool` is the `render_a2ui` tool as a frontend tool, for an agent that calls it directly
and waits on the answer. It is not registered by anything here, on purpose: upstream's LangGraph
adapter calls `render_a2ui` from a subagent and closes it on the server, and a client that answered
that call would be answering a question it was not asked. Register it for the agents that ask.

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

The sample also executes one frontend tool, `change_background`, and paints whatever gradient it
is sent. To see that go round, upstream's all-features starter is the server, and it needs no key
either — but it does need a full (not sparse) checkout, because its lockfile points at the Python
SDK by path; a shallow clone is fine:

```
cd ag-ui/integrations/server-starter-all-features/python/examples
uv run dev
```

Point the sample at `http://localhost:8000/agentic_chat` and type `tool`: the server calls the
tool, the window changes colour, the result goes back in a second run, and the server answers
`background changed ✓`. Type anything else for a plain reply.

For an agent that actually thinks, any of the other
[integrations](https://github.com/ag-ui-protocol/ag-ui/tree/main/integrations) serves the same
protocol on the same shape of endpoint; those are LLM-backed and want a provider key of their own.
The sample does not care which — it is a URL.

To see a run stop and ask, the server has to be one that raises an approval, and none of upstream's
examples turns that on: [`docs/live/approval`](docs/live/approval/README.md) is a Strands server
with one gated `transfer` tool, and wants a key for the model behind it. Point the sample at it and
type `Transfer 100 to Alice`: the call is drawn as awaiting approval, the card asks, and Approve
runs the tool in the next run. The same server is what `agui-agent`'s `LiveApprovalTest` measures
when `AGUI_LIVE_APPROVAL_URL` names it; without the variable the test is skipped, and CI skips it.

To see an agent draw because the client asked, the server has to be one that supplies nothing of
its own, and upstream's A2UI examples all stamp a catalog id and hand the sub-agent a guide from the
server side: [`docs/live/a2ui`](docs/live/a2ui/README.md) is a Strands server with a plain agent and
no A2UI config, so the `A2uiRequest` the sample sends is the only reason a surface arrives. Point
the sample at it and type `Compare three hotels in Kyoto`; the README beside it says what should
come back, and what each other outcome means.

**One dependency clash you do not have to resolve.** Upstream's `kotlin-client` and `kotlin-tools`
0.4.1 are compiled against kotlinx-datetime 0.6.2; Compose Material 3 1.9.0 brings 0.7.1, where
`Clock` and `Instant` moved to `kotlin.time` and the old classes are gone. Taking `agui-material3`
and `agui-agent` together used to compile and then die on the first event upstream timestamps, with
`NoClassDefFoundError: kotlinx/datetime/Clock$System`. `agui-agent` now declares the coordinate that
carries both binary surfaces, so this is handled — it is written down in
[docs/decisions/0007](docs/decisions/0007-pinning-a-dependency-neither-module-names.md) because it
is the kind of thing that comes back, and because it is what the sample found on its first run.

### Replaying upstream

A2UI needs an agent that generates UI, and every upstream agent that does is LLM-backed and wants
a key — and, as of this writing, a Python runtime beside it. Rather than ask for either, this
repository ships the traffic those agents produced: five event streams recorded by upstream's own
end-to-end suite off its LangGraph agents, through its `a2ui-middleware`, converted to JSON with
no other change (`agui-replay/src/main/resources/traces/`). `agui-replay` serves them the way an
AG-UI server would, one route per recording:

```
./gradlew :agui-replay:run
```

Point the sample at `http://localhost:8000/advanced-hotel-comparison` and type anything: the
server streams the recording — the `render_a2ui` arguments as they were generated, the
middleware's `building` activity, the cumulative surface snapshots, the outer tool's result — and
the window draws three hotel cards from the dojo's catalog, which the sample registers because
a catalog is the renderer's trust boundary and the agent cannot add to it. `GET /` lists the
routes; `fixed-multiple-surfaces` answers two turns. What you typed does not reach anything: the
request's thread and run ids are honoured and the rest of it is not, because a recording cannot
answer a question it was not asked.

The same recordings are what `agui-a2ui`'s trace-driven tests fold, and what the sample's
end-to-end test plays over a real socket into a real window. So "verified against a real server"
here means verified against what a real server said, byte for byte, minus the model that said it.

What the sample deliberately does not do yet: build for Android or iOS. It is one target and one
screen, and the module is laid out so that the second target is a source set rather than a
rewrite.

## Building

```
./gradlew build
```

Requires **JDK 21**. That floor is inherited, not chosen: upstream's `kotlin-core-jvm` is Java 21
bytecode, so a JDK 17 build cannot load the types this library is built on.

The Apple targets need macOS. On Linux they are skipped rather than failed, so a green Linux build
says less than it appears to -- which is why CI runs on macOS.

### Releasing

A `v*` tag is a release: `.github/workflows/cd.yml` reads the version from it, publishes every
module locally and signs it, resolves that publish from a separate consumer build
([`smoke-test/`](smoke-test/README.md)) on every target, and uploads to Maven Central without
releasing -- the last step is a button in the portal. The job runs under a `release`
environment, so once that environment exists with a required reviewer the run waits on a human
before it touches a secret; until then the line gates nothing. `release-dry-run.yml` rehearses
the same path from the Actions tab with a throwaway key and no upload; run it before the first
tag, and after any change to a module's targets or publishing block.

## Licence

Apache 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

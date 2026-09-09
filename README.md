# agui-compose

A Compose Multiplatform client for the [AG-UI protocol](https://github.com/ag-ui-protocol/ag-ui).

**This library rides on the upstream community Kotlin SDK
(`com.ag-ui.community:kotlin-core` / `kotlin-client`). It is not a second implementation of the
protocol.** The event types are upstream's, and so are the transport and the SSE parser. What is
added here is the layer upstream has no opinion about: a render model that keeps the order an agent
produced things in, and the Compose UI that draws it. The one function this library does replace,
and why, is in
[docs/decisions/0001](docs/decisions/0001-riding-on-the-upstream-kotlin-sdk.md).

Chat is the first surface, not the boundary. AG-UI's 33 events cover streaming text, reasoning,
tool calls, human-in-the-loop approval, shared state, generative UI surfaces, run lifecycle,
multimodal input and steering — this library is aimed at all of it.

> **Status: pre-release.** Nothing is published yet and the API is not stable.

## Modules

| Module | What it is | Published |
| --- | --- | --- |
| `agui-model` | The render model: `UiMessage` as an ordered list of parts, each with its own streaming state. No UI framework, no protocol types. | not yet |
| `agui-core` | Folds an AG-UI event stream into that model, including the `ACTIVITY_*` events the upstream reducer does not handle. | not yet |

`agui-compose` (the Compose renderer), `agui-material3`, `agui-a2ui` (the
[A2UI](https://github.com/uny/a2ui-compose) bridge, as an optional dependency) and the
`agui-provider-*` adapters come next.

## Targets

`androidTarget`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `iosX64` — the five the upstream SDK
publishes.

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

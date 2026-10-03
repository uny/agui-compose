# 16. Leaving the WebView bridge to the host

Date: 2026-10-03

## Status

Accepted. Settles the bridge question that [0015](0015-drawing-in-a-browser-without-the-protocol-layer.md)
left open on [#29](https://github.com/uny/agui-compose/issues/29). The rest of #29 stays open.

## Context

Decision 15 made the drawing modules publish `js` and `wasmJs` so a WebView inside a native
application can draw a transcript the native side folded. It left the bridge between the two
undefined, in both directions:

- **Native to WebView: the transcript.** `AgentSession.transcript` is a `StateFlow<UiTranscript>`
  and `pendingInterrupts` a `StateFlow<List<UiInterrupt>>`. None of `agui-model`'s types is
  `@Serializable`. The payloads inside them -- parsed tool arguments, activity content, shared
  state -- are `JsonElement` and already serialize; the message, part and run-state hierarchy does
  not. A tool call's raw `arguments` is a `String`, because it holds the JSON while it is still
  streaming in and incomplete.
- **WebView to native: what the user does.** A chat screen needs `send(String)`, `resume(List<UiResumeEntry>)`
  and `run()` on `AgentSession`. `send` and `run` take plain values, the optional
  `RunAgentParameters` aside, which stays on the native side. `resume` takes `UiResumeEntry`, a
  model type: an interrupt id, a `UiResumeStatus` and an optional `JsonElement` payload.

#29 put it as two options:

1. **Serializers in `agui-model`.** Every public type gains a serializer, so `UiTranscript`,
   `UiInterrupt` and `UiResumeEntry` cross a bridge as JSON with no hand-written mapping. The
   host still carries the bytes and dispatches the calls.
2. **No change here.** Each host maps the model to its own bridge format.

Measured on `3cb4df0`: nothing in this repository hosts a WebView. `agui-sample` runs natively, and
the consumer smoke test compiles the web variants without bridging anything. No consumer outside it
has asked.

## Decision

**The bridge is the host's.** `agui-model` gains no serializers, and this library defines no bridge
format, message envelope or JavaScript interface.

A host writes the mapping once, in its own shared Kotlin code, since the native side and the
WebView can both depend on `agui-model`. It chooses what crosses -- the whole transcript on each
change, or a diff of it -- and how the WebView's calls come back to `AgentSession`.

Option 1 is not rejected on its merits. It is deferred for three reasons:

- **A serializer is a wire format.** Adding one makes the JSON shape of every model type this
  library's to keep stable, on top of the Kotlin ABI it already keeps. A field added to a part for
  drawing's sake would become a compatibility question for every stored or in-flight bridge
  payload.
- **The shape is unknown without a host.** Whether a bridge sends the whole transcript or a
  diff, whether streaming text crosses per chunk, and how a host versions its envelope are all
  answered by the first real host, not here. A format written first would be fitted to a guess.
- **Option 2 does not preclude it.** Serializers can be added later without breaking a host that
  mapped by hand. The reverse -- removing serializers a host depends on -- is a breaking change.

## Consequences

- **`agui-model`'s public surface and ABI dumps do not change.**
- **A WebView host writes a mapping and a call dispatcher.** The mapping covers `UiTranscript`,
  `UiMessage`, the `UiPart` subtypes, `RunState` and `UiInterrupt` one way, and `UiResumeEntry` the
  other, with the enums and values nested in them. The `JsonElement` fields pass through as they
  are. What comes back is input from web content, so the dispatcher checks it before it reaches
  `AgentSession`: a `resume` entry answers what a run stopped to ask, an approval included. A host
  that draws arguments while they stream sends `ToolCallPart.arguments`, not only
  `parsedArguments`.
- **Tool execution stays native.** `AgentSession` runs tools from upstream's `ToolRegistry`, and
  `agui-agent` has no browser target; a tool whose effect is in the WebView is the host's to
  forward.
- **The README says the bridge is the host's**, next to the browser targets.

## What would change the answer

- **A second host sharing a bridge format.** Two hosts writing the same mapping is the point at
  which the format belongs here, as serializers in `agui-model` or as a separate bridge module that
  keeps the wire format off `agui-model`'s own surface.
- **A first host that finds the mapping costly.** If a real host shows the hand mapping is where its
  bugs are, that is evidence for option 1 regardless of the number of hosts.
- **A browser with no native host behind it.** Then nothing crosses a bridge: the render model runs
  in the browser, as decision 15 and #29 describe, and this decision no longer applies.

# 1. Riding on the upstream Kotlin SDK, and the two places we do not

Date: 2026-09-10

## Status

Accepted.

## Context

AG-UI has a community Kotlin SDK in the protocol's own repository, under
`sdks/community/kotlin/library`. It publishes three artifacts to Maven Central:

| Artifact | What it holds |
| --- | --- |
| `com.ag-ui.community:kotlin-core` | The protocol types: 33 events, the message model, the JSON setup |
| `com.ag-ui.community:kotlin-client` | `HttpAgent`, the SSE parser, `EventVerifier`, `StateManager`, `defaultApplyEvents` |
| `com.ag-ui.community:kotlin-tools` | Frontend tool registry and execution |

The Maven group is `com.ag-ui.community`. `com.agui` is only the package prefix, which reads like
a coordinate and is not one.

This library is a Compose Multiplatform client for the same protocol. Re-declaring the protocol
would fork a wire format that already has one Kotlin definition, and split a small ecosystem for no
gain. So the starting position was: depend on the upstream SDK, add the rendering layer it has no
opinion about.

Two things measured before committing changed the shape of that.

## The target set

The published artifacts carry five targets, and this was read off the Gradle module metadata rather
than the build script:

```
androidJvm, jvm, iosArm64, iosSimulatorArm64, iosX64
```

There is no `macosArm64`, no `js` and no `wasmJs` — and `kotlin-core` has exactly the same five, so
depending on the types alone buys no targets back. The sibling library `a2ui-compose` publishes
seven. Three ways out were considered:

1. **Take the five.** Android, iOS, and desktop through the JVM target — which is how Compose
   Multiplatform desktop is built anyway, on macOS as much as on Windows and Linux. What is lost is
   the browser (`js`, `wasmJs`) and Kotlin/Native macOS.
2. **Re-declare the protocol types** and keep seven targets. Roughly 1,100 lines of event and
   message definitions, maintained against a spec that is still moving, in exchange for two
   backends.
3. **Vendor the upstream sources** and build them with more targets. This is dead on a fact rather
   than on judgement: `kotlin-client` applies `STATE_DELTA` through
   `io.github.reidsync:kotlin-json-patch`, which publishes Android, JVM, JS and the three iOS
   targets — **no `macosArm64` and no `wasmJs`**. The vendoring route cannot reach the two backends
   it exists to reach.

**Decision: (1).** The five targets upstream publishes, and `iosX64` is among them because upstream
still supports it and dropping it would break an Intel simulator that upstream does not. The
browser gap is real and is written down here rather than papered over; it closes when upstream
publishes web targets, or when a consumer's need makes (2) worth its price.

Two smaller measurements that came with it:

- **Kotlin 2.4.10 consumes upstream's 2.1.20 klibs.** Verified with a throwaway project that
  depends on `kotlin-core` and `kotlin-client` 0.4.1 and compiles `jvm`, `iosArm64` and
  `iosSimulatorArm64` against `AbstractAgent`, `AgentState`, `defaultApplyEvents` and
  `ActivitySnapshotEvent`. This was not assumed from Kotlin's compatibility policy.
- **The JVM floor is inherited: Java 21.** Upstream compiles its JVM and Android targets at
  `JVM_21`, and `kotlin-core-jvm-0.4.1.jar` is class-file version 65. A consumer on JDK 17 cannot
  load it, so neither this library nor its CI can run there. Like the target list, this is a
  consequence of riding on upstream rather than a choice made here.
- **Upstream declares coroutines and `kotlinx.serialization` as `implementation`,** so they do not
  arrive transitively. A consumer that never declares them fails to compile against upstream's own
  signatures. This library declares both.

Version 0.4.1 is pinned. It is both the newest release and the newest commit on the library's path
(`9770f32`, 2026-06-02), so pinning the release leaves nothing on the branch behind.

## The reducer

Upstream's `defaultApplyEvents` folds an event stream into an `AgentState`:

```kotlin
data class AgentState(
    val messages: List<Message>?,
    val thinking: ThinkingTelemetryState?,
    val state: State?,
    val rawEvents: List<RawEvent>?,
    val customEvents: List<CustomEvent>?,
    val reasoning: ReasoningTelemetryState?,
)

data class AssistantMessage(
    val id: String,
    val content: String?,
    val name: String?,
    val toolCalls: List<ToolCall>?,
)
```

That is a transcript, and a correct one. It is not a render model, and the difference is not a
matter of taste:

- An assistant message holds **one `content` string and a separate `toolCalls` list**. An agent
  that says "let me look", calls a tool, and then says "found three" produces a message from which
  nobody can tell whether the call came before, between or after the sentences.
- **Reasoning is a sibling channel**, keyed by its own message ids, with no field relating a
  reasoning stream to the assistant message it belongs to.
- `AgentState` is emitted **partially** — `emit(AgentState(reasoning = ...))` with every other
  field null — so it is a patch stream, not a sequence of snapshots.

Ordering is exactly the property a chat renderer needs and the only place it survives is the event
stream itself.

**Decision: `agui-core` folds `Flow<BaseEvent>` directly into `UiTranscript`, and does not consume
`AgentState`.**

This is a substitution for one function, not a reimplementation of the SDK. The event types are
upstream's; so are the transport, the SSE parser and the event verifier that produce them, which
arrive with the agent layer. `agui-core` itself does not depend on `kotlin-client` at all — folding
events into a render model opens no socket, and depending on the client module would put Ktor on
the classpath of a module that makes no request. It does depend on `kotlin-json-patch` directly, at
the version `kotlin-client` uses, so a graph holding both resolves to one copy.

One consequence is worth stating plainly: **`MESSAGES_SNAPSHOT` cannot restore ordering.** A
snapshot carries the lossy shape, so a restored assistant message renders its text before its tool
calls. A streamed turn does not lose the order and a restored one does. That asymmetry belongs to
the wire format, not to this model.

## `ACTIVITY_SNAPSHOT` and `ACTIVITY_DELTA`

`kotlin-core` defines both events. `defaultApplyEvents` has no branch for either — the events pass
through the upstream reducer without effect.

`agui-core` handles them, against the specification's normative text
(`docs/spec/draft/events/activity.mdx` in `ag-ui-protocol/ag-ui`) rather than against a guess:
`replace` absent or true replaces content *and* `activityType`; an explicit `replace: false` leaves
an existing activity exactly as it stands and is not a merge; a delta may retype the activity it
amends; and the two tolerated failures — a delta naming an activity that does not exist, and a
well-formed patch that does not apply — warn and skip without failing the run. Each of those has a
test that quotes the sentence it comes from.

They are modelled as a general `ActivityPart` carrying `activityType` and `content`, not as an A2UI
part. `a2ui-surface` is one value of an open field, and the protocol says a consumer must tolerate
types it does not recognise. Interpreting an A2UI payload belongs to the separate `agui-a2ui`
module.

Note for later: A2UI reaches a client by two routes, not one. The ACTIVITY events are one; the
other is a `render_a2ui` frontend tool call, which is what upstream's own example uses — its
`RenderA2UiToolExecutor` exists so that "the middleware no longer has to synthesize
`ACTIVITY_SNAPSHOT` + a fake `TOOL_CALL_RESULT`". A complete client handles both.

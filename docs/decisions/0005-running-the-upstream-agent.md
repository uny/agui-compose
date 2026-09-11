# 5. Running the upstream agent

Date: 2026-09-11

## Status

Accepted.

## Context

[Decision 1](0001-riding-on-the-upstream-kotlin-sdk.md) took the upstream SDK's types and left its
transport for later: `agui-core` depends on `kotlin-core` only, because folding events into a render
model opens no socket and `kotlin-client` would put Ktor on the classpath of a module that makes no
request. This record is the "later". Something has to run `HttpAgent`, and hand what comes out of it
to the reducer.

Two questions decide the shape of that module, and both were answered by reading the published
Gradle module metadata for `kotlin-client` 0.4.1 rather than its build script -- the lesson of
decision 4, where a dependency assumed to arrive transitively did not.

### What `kotlin-client` publishes

The five targets `kotlin-core` publishes, and no others: `androidJvm`, `jvm`, `iosArm64`,
`iosSimulatorArm64`, `iosX64`. Nothing here changes the target set of decision 1.

Its dependencies are where the shape comes from. Upstream chooses the Ktor engine **per platform,
in the library**: CIO on the JVM, `ktor-client-android` on Android, Darwin on iOS. A consumer does
not pick one, and the only substitution point is the `HttpClient` parameter on `HttpAgent` -- whose
platform factories show what such a client has to have installed (`ContentNegotiation` with
upstream's `AgUiJson`, `SSE`, `HttpTimeout`).

How those dependencies are exposed differs by platform, and this is the part that decides what the
module declares:

| Platform | Ktor, coroutines, serialization, kermit, datetime |
| --- | --- |
| `jvm`, `androidJvm` | runtime only (`implementation`); the compile classpath holds `kotlin-core`, `kotlin-tools` and the stdlib |
| iOS (all three) | `api` |

So on the JVM and Android, a module whose public signatures name any `io.ktor` type has to declare
`ktor-client-core` itself, or its consumers fail to compile against its own API. `kotlin-tools`
arrives as `api` everywhere.

Whether that bites a consumer constructing `HttpAgent` -- whose only constructor takes an
`HttpClient?` -- was measured rather than inferred: a test in this module's `commonTest` (the one
source set the Android host test compiles; a `jvmTest` would measure the JVM alone) constructs one
with the parameter defaulted, and compiles on both with no Ktor artifact on the compile classpath.
Passing a client is a different matter, and a consumer doing so
is naming the type and declares `ktor-client-core` as it would anywhere.

One oddity, recorded rather than acted on: the common metadata variant lists `ktor-client-darwin`,
which has no common variant to resolve to. Gradle tolerates it -- `compileCommonMainKotlinMetadata`
for this module succeeds -- so it is a watch item, not a defect to work around.

### What the reducer needs

`agui-core`'s `foldToTranscript` starts a fresh `UiTranscriptReducer` on every collection. That is
correct for one run -- a retry must not append a replay to the conversation it already produced --
and wrong for a conversation, which is many runs: a second run folded that way begins from an empty
transcript and the first answer disappears from the screen. Something has to hold one reducer for
the lifetime of a thread and feed every run into it.

Upstream's `threadId` is fixed per `AbstractAgent`, so "a thread" and "an agent" are the same
lifetime, and the reducer asks to be fed from one coroutine.

### How events come out of upstream

`AbstractAgent` has two entry points. `runAgent()` collects on an internal `Dispatchers.Default`
scope and reports failure to a logger, so a caller sees neither the events nor the failure.
`runAgentObservable()` returns the `Flow<BaseEvent>` after upstream's chunk transform and event
verifier, and rethrows what the stream throws -- by which point `HttpAgent` has already converted
transport failures into `RUN_ERROR` events, so what still throws is a protocol violation the
verifier rejected, or a client-side fault.

`runAgentObservable` also keeps upstream's own `AgentState` current per event, and that is what
`prepareRunAgentInput` sends as history with the next run. Whether text deltas accumulate correctly
under that per-event shape was measured, not read: a two-run test checks that the first run's
assembled answer arrives in the second run's input.

## Decision

**A module `agui-agent`, on the same five targets as `agui-core`, holding one `AgentSession`.**

- **Its public API names `AbstractAgent`, `RunAgentParameters`, `UiTranscript` and `RunState`, and
  no Ktor type.** That is what lets it declare `kotlin-client` and nothing else: the engine is
  upstream's choice, the substitution point is upstream's constructor, and the module neither
  repeats nor hides either. A consumer that wants a different engine builds an `HttpClient` and
  hands it to `HttpAgent`, exactly as it would without this library.
- **One reducer per session, for the session's lifetime.** `AgentSession.run` folds a run's events
  into it and exposes the result as a `StateFlow<UiTranscript>`. `foldToTranscript` stays as it is
  for the one-run case.
- **Events come from `runAgentObservable`**, and a run that throws is folded into the transcript as
  a `RUN_ERROR` with code `CLIENT_ERROR` and not rethrown. The transcript is the report; a UI that
  launched the run from a button does not want an unhandled exception for a run that failed in an
  ordinary way. Cancellation is recorded the same way, under `CANCELLED`, and then propagates. So
  is a stream that ends without `RUN_FINISHED` or `RUN_ERROR`: upstream's verifier checks each
  event against the last and has no opinion about the end of the stream, so a connection the
  server closed cleanly mid-run would otherwise leave the transcript running forever. None of
  this overwrites a verdict the agent already gave -- once `RUN_FINISHED` or `RUN_ERROR` has been
  seen, a later throw or cancellation leaves the agent's own message and code in place.
- **Runs on one session take turns.** A mutex is held for the length of a run, because the reducer
  is not thread-safe and two interleaved runs in one transcript is not a state anyone asked for.
- **The session does not dispose the agent.** `dispose()` cancels the agent's scope and closes a
  client it created itself; the agent may be shared, so its owner closes it.
- `jvmToolchain(21)`, `explicitApi`, ABI validation, `withHostTest`, and Maven Central publishing
  are copied from `agui-core`, module-level `gradle.properties` included.

## Consequences

- A cancelled run is a `RunState.Failed`. The model has no state for "stopped on request", and
  leaving the run `Running` -- a caret blinking under text that will never grow -- was the worse
  of the two. The code distinguishes it. A `Cancelled` state in `agui-model` is the honest fix if a
  UI turns out to need one; that is an ABI change and is not made here.
- **A user message typed locally has no way into the transcript.** `UiTranscriptReducer` builds a
  `UserMessage` only from `MESSAGES_SNAPSHOT`, and a snapshot replaces the whole transcript with
  the lossy shape decision 1 describes. So a chat surface cannot yet show what the user just sent
  before the agent answers. This is a boundary question -- either `agui-core` gains a public append
  entry, or the UI prepends it -- and it is deferred, not forgotten. The first sample application
  will force it.
- Frontend tools are not wired. `kotlin-tools` arrives with `kotlin-client`, and upstream's
  `ToolExecutionManager` sits between the observable and a consumer; `AgentSession` takes the
  observable directly, so a tool call streams into the transcript and nothing executes it. The
  `render_a2ui` route decision 1 noted lives here, and comes with `agui-a2ui`.
- `StateFlow` conflates. A renderer that wants every frame -- for a test, say -- counts events
  through upstream's `AgentSubscriber`, not through the transcript.
- The `.module` metadata that decided the dependency declaration is a statement about 0.4.1. A
  future release that exposes Ktor as `api` on the JVM changes nothing here; one that makes the
  engine consumer-selectable would let this module drop a sentence from its KDoc and nothing else.

## What would change the answer

- Upstream exposing an engine choice to consumers: the module would stop describing the fixed
  engine and start forwarding the choice.
- A second module needing a thread-lifetime reducer -- `agui-a2ui` handling a `render_a2ui` call
  that spans runs, say -- would move the "one reducer per thread" mechanism down into `agui-core`
  and leave `agui-agent` with only the upstream binding.

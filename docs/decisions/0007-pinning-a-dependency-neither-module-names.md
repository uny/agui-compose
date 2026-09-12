# 7. Pinning a dependency neither module names

Date: 2026-09-12

## Status

Accepted.

## Context

`agui-sample` is the first thing in this repository to depend on `agui-material3` and `agui-agent`
at once. It compiled, launched, connected to a server, sent a turn, and died:

```
java.lang.NoClassDefFoundError: kotlinx/datetime/Clock$System
```

Neither module names a datetime type. The clash is entirely between what they each bring.

### What the two sides were compiled against

Upstream's `kotlin-client` and `kotlin-tools` 0.4.1 are built against kotlinx-datetime **0.6.2**,
where `kotlinx.datetime.Clock` is a class. Seven of their classes call it, read off the artifacts
rather than the source:

| Artifact | Classes referencing `kotlinx/datetime/Clock` |
| --- | --- |
| `kotlin-client` | `AgUiAgent`, `AbstractAgent$Companion`, `ClientToolResponseHandler` |
| `kotlin-tools` | `ToolExecutionManager`, `DefaultToolRegistry`, `CircuitBreaker`, `ToolErrorHandler` |
| `kotlin-core` | none |

`kotlin-core` is clean, which is why `agui-core` and everything built on it alone is unaffected.
The exposure arrives with the transport, and therefore with `agui-agent`.

Compose Material 3 1.9.0 brings kotlinx-datetime **0.7.1**. In 0.7, `Clock` and `Instant` moved to
`kotlin.time`; `kotlinx/datetime/Clock.class` and `kotlinx/datetime/Instant.class` are not in the
0.7.1 jar at all. Gradle resolves the conflict the way it resolves every conflict -- highest version
wins -- and picks 0.7.1.

### Why nothing caught it

Every module's tests pass, on every target, and always did. `agui-material3` has no reason to put
the upstream client on its test classpath and `agui-agent` has no reason to put Material 3 on its
own; the two artifacts do not meet until something depends on both, and until `agui-sample` nothing
did. The compile step cannot catch it either -- no source file names the missing class, so the
first symptom is a `NoClassDefFoundError` at run time, from a build that succeeded.

This is the failure mode a sample application exists to find, and it found it on the first run.

## Decision

**`agui-agent` declares `org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat`,** and no other
module declares kotlinx-datetime at all.

`0.8.0-0.6.x-compat` is published by kotlinx-datetime for exactly this situation: 0.8.0 with the
0.6.x binary surface kept alongside the new one, so code compiled against either finds what it was
compiled against. It is on Maven Central, and it sorts above 0.7.1, so ordinary conflict resolution
selects it.

`implementation`, not `api`. Nothing in this module's compile surface names a datetime type, and
the classpath that crashed was the runtime one -- an `implementation` dependency rides in the
published `runtimeElements` variant, which is the classpath a consumer resolves. Declaring it `api`
would put a library on consumers' compile classpath that none of them has a reason to name.

**In `agui-agent` rather than in `agui-sample`**, which is where it started. A README note telling
applications to add a line puts the same discovery on everyone who ever takes these two modules
together, and they discover it the way this repository did: at run time, after a green build. The
declaration rides in published metadata instead, and a consumer never learns there was anything to
learn. Nothing is published yet, which is what makes this cheap to decide now: a coordinate added
before the first release is a build file, and one added after is a migration note.

**No `strictly`.** It would hold the version against anything, including a consumer who has a
legitimate reason to take a newer kotlinx-datetime. A library does not get to make that call for an
application. The cost of not using it is written down under Consequences.

## Consequences

- An application taking `agui-material3` and `agui-agent` together works, with nothing to configure.
- This wins by version order, not by where it is declared. The day something on a consumer's graph
  brings a kotlinx-datetime that sorts above `0.8.0-0.6.x-compat`, the pin stops winning -- and it
  stops winning at run time, from a build that still succeeds. That is the same failure as before,
  with a later trigger -- but it is caught here, which is the reason this record is filed against a
  sample rather than a note. `:agui-sample:jvmTest` fails 4 of its 10 on
  `NoClassDefFoundError: kotlinx/datetime/Clock$System` the moment the pin stops winning, because
  its runs go through `runAgentObservable` and upstream timestamps them. What is *not* caught is a
  bump that happens on a **consumer's** graph, which nothing in this repository resolves; that is
  what running `agui-sample` against a real server is for.
- `ToolExecutionManager` is one of the seven classes on the 0.6.2 side, so the frontend-tools work
  is downstream of this record rather than a second encounter with it.
- One line to remove when it stops being needed, and a clear signal for when: see below.

## What would change the answer

- **Upstream moves to kotlinx-datetime 0.7 or newer.** Then both sides agree, the compat artifact
  is no longer bridging anything, and this declaration should be deleted rather than bumped. This
  is the expected end of the record.
- **The compat line stops being published.** It is a maintenance branch, not a permanent release
  series; if it stops receiving the fixes this repository needs, the choice becomes pinning
  upstream's own floor (0.6.2, which loses to Material 3 without `strictly`) or `strictly` after
  all, and the argument above would have to be re-made.
- **A consumer reports the pin fighting them.** Being overridable is the point of not using
  `strictly`, so a consumer overriding it deliberately is this working. A consumer who cannot
  override it, or who is broken by it, is a reason to re-open.

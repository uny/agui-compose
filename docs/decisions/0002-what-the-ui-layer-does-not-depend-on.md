# 2. What the UI layer does not depend on: a design system, a Markdown parser, and an Intel simulator

Date: 2026-09-10

## Status

Accepted.

## Context

`agui-compose` is the first module in this repository that draws anything. Three questions had to
be answered before it could be written, and two of them were expected to be trade-offs. Only one
turned out to be.

### The target set is not a choice

[Decision 1](0001-riding-on-the-upstream-kotlin-sdk.md) fixed the published target set at the five
the upstream SDK ships: `androidTarget`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `iosX64`. That
decision was made for modules that hold types and fold events. It does not survive contact with
Compose.

Compose Multiplatform 1.12.0 does not publish an `ios_x64` variant. Measured from the Gradle module
metadata rather than inferred:

| Artifact | Apple variants published |
| --- | --- |
| `org.jetbrains.compose.foundation:foundation:1.12.0` | `ios_arm64`, `ios_simulator_arm64`, `macos_arm64` |
| `org.jetbrains.compose.ui:ui:1.12.0` | `ios_arm64`, `ios_simulator_arm64`, `macos_arm64` |
| `org.jetbrains.compose.runtime:runtime:1.12.0` | `ios_arm64`, `ios_simulator_arm64`, and the other Native families |
| `org.jetbrains.compose.material3:material3:1.9.0` | `ios_arm64`, `ios_simulator_arm64`, `ios_x64`, `macos_arm64`, `macos_x64` |

material3 ships on its own version line, independent of the Compose Multiplatform release, and
still carries `ios_x64`. That looks like an escape until it is followed: material3 is built on
`foundation` and `ui`, so its Intel variant cannot resolve alongside the 1.12.0 ones this
repository uses.

One configuration would reach an Intel simulator: pinning Compose Multiplatform back to a release
that still published `ios_x64`. It is rejected rather than absent. The published target set is not
worth freezing the UI layer's whole toolchain -- Kotlin version, Compose compiler, every API added
since -- for a simulator architecture Apple has already moved off, and the pin would have to hold
for as long as the target does.

The sibling `a2ui-compose` is independent confirmation: it publishes seven targets and `iosX64` is
not among them.

### Markdown looked like a trade-off, and was one for about an hour

An agent's text is Markdown in practice. `mikepenz/multiplatform-markdown-renderer` was the
candidate, and it appeared to force a choice:

| Version | `iosX64` | Streaming API | Kotlin |
| --- | --- | --- | --- |
| 0.40.2 | yes | no | 2.3.20 |
| 0.41.0 | no | no | — |
| 0.45.0 | no | `StreamingMarkdownState` | 2.4.10 |

`iosX64` was dropped at 0.41.0 — silently, not in the release notes — and the streaming API arrived
at 0.42.0. The two are mutually exclusive in that library, and pinning 0.40.2 to keep the fifth
target meant a dead version line built on an older Kotlin.

The CMP finding above dissolves that: a Compose module has four targets whatever it depends on, and
0.45.0's published set is a superset of those four. The library's core is also
design-system-free — its classes reference `foundation`, `ui`, `runtime` and `animation` and
nothing from Material, with the Material 3 bindings in a separate `-m3` artifact — so it would
have fitted the module split cleanly.

The remaining question was therefore not *which* Markdown library but *whether `agui-compose`
should have one at all*.

## Decision

**The target sets are allowed to differ.** `agui-model` and `agui-core` keep all five targets;
`agui-compose` and every module that draws take four. Trimming the lower two to match would remove
a capability — folding an event stream on an Intel simulator and rendering it with something other
than Compose — to make one number uniform across a table in the README.

**`agui-compose` sits below any design system.** The defaults draw structure and no colour, spacing
or shape. Every part kind goes through a slot in `AguiComponents`, replaced one at a time by `copy`.
`agui-material3` is where visual decisions get made.

**`agui-compose` takes no Markdown dependency.** `AguiTextRenderer`, reached through
`LocalAguiTextRenderer`, is the seam; the default draws text literally. One renderer serves both
`TextPart` and `ReasoningPart`, so an application fits it once. It is handed the accumulated run
rather than the latest delta — the reducer accumulates before a renderer sees anything — so
re-parsing per recomposition is correct and an incremental parser is a cost optimisation rather
than a correctness requirement. The `streaming` flag is passed through so a parser can hold back
half-typed syntax.

## Consequences

A consumer who wants Markdown adds the dependency and four lines. That is the cost, and it is paid
by the consumers who want it rather than by all of them: `TEXT_MESSAGE_CONTENT` carries a string and
the protocol says nothing about its syntax, so a parser baked in here would be this library
asserting a fact about the wire that the specification does not contain.

The slot is not free of risk. A library that ships no default beyond plain text will be judged on
what an application looks like before it fits one, and "renders Markdown out of the box" is a real
thing to give up. The judgement is that a skeleton which cannot be overridden is worse than one
that starts plain, and that this direction is the reversible one: a default can be added later,
while a baked-in parser cannot be removed without breaking consumers.

`agui-compose` has no test coverage on Android. Every test in the module needs a composition on
screen, Compose's UI test harness needs Robolectric or an instrumentation host to provide one there,
and this module carries neither. The tests run on `jvm` and `iosSimulatorArm64`. This is a gap
rather than a justified exclusion, and it is recorded here and in the build file rather than
papered over.

## What would change the answer

- **Compose Multiplatform publishing `ios_x64` again.** Then the five-target set is available to
  the UI modules and the asymmetry should be removed rather than kept for its own sake.
- **An Intel simulator becoming load-bearing for a real consumer.** The rejected pin above is the
  option that comes back, and it would be worth its cost only if someone were actually blocked.
- **The protocol gaining a content-type on text events.** If `TEXT_MESSAGE_CONTENT` ever says what
  its string is, a default that honours it stops being an assertion and becomes an implementation.

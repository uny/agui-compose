# 3. What Material 3 decides for you: the whole table, and still no parser

Date: 2026-09-10

## Status

Accepted.

## Context

[Decision 2](0002-what-the-ui-layer-does-not-depend-on.md) put `agui-compose` below any design
system: its slots draw structure and no colour, spacing or shape, and it names `agui-material3` as
the place visual decisions get made. This is that module, and writing it forced two questions the
previous record deferred and one it had already answered in a way worth re-testing one layer up.

### Replacing the table, or only theming it

Two shapes were available. `agui-material3` could provide a theme -- values a consumer applies to
the defaults they already have -- or it could provide a filled-in `AguiComponents` that replaces
them.

Theming alone is not reachable. `agui-compose`'s defaults draw through `BasicText`, and foundation
has no ambient text style: there is no value a theme could set that would change what those
defaults render. The choice was never between two designs, only between replacing the table and
adding an API that could not work.

The second half of that is easy to miss. Providing the slot
table alone leaves `LocalAguiTextRenderer` at `PlainAguiTextRenderer` -- so an application gets
Material 3 tool calls, bubbles and disclosures wrapped around prose that ignores the theme's type
scale and content colour entirely, which looks like a bug in the design system rather than a
missing second line. The two locals are one decision.

### Markdown, one layer up

The handoff into this module left it open whether a Markdown renderer belongs here or in a module
of its own. The argument in Decision 2 was that `TEXT_MESSAGE_CONTENT` carries a string and the
protocol says nothing about its syntax, so a parser baked in asserts a fact about the wire that the
specification does not contain. Being one layer higher does not change that. What being one layer
higher *does* change is who pays: a consumer who wants Material 3 text styling would get a Markdown
grammar, its transitive dependencies and its parse cost along with it.

The sibling `a2ui-compose` shows the other road. Its `a2ui-material3` hand-rolls a Markdown subset
in `markdownText`, and it is a defensible module there because the A2UI specification's own
implementation guide asks for Markdown and names the fallback. AG-UI asks for nothing of the kind.

### The Android gap, for the second time

`agui-compose` publishes an `.aar` that no test has ever exercised: every test needs a composition
on screen, Compose's UI test harness needs Robolectric or an instrumentation host to provide one on
Android, and the module carries neither. Decision 2 recorded that as a real gap.

This module reproduces it exactly. That is the point at which it stops being one module's footnote.

## Decision

**`agui-material3` replaces the slot table, and `ProvideMaterial3Agui` provides both locals.**
`Material3AguiComponents()` returns a filled-in `AguiComponents`; overriding remains `copy` of one
slot. The convenience composable exists so that the second local is not something a consumer has to
discover.

**The prose slots do not call `Text`.** `AguiComponents.text` is left at `agui-compose`'s default,
which draws through `LocalAguiTextRenderer`, and `reasoning` routes through the same renderer with
Material 3's dimming applied *around* the call via `LocalContentColor` and `ProvideTextStyle`. A
consumer who fits their own renderer therefore keeps every frame in this module, and inherits the
reasoning treatment for free.

**`MaterialTheme` stays the caller's.** `ProvideMaterial3Agui` does not wrap one. A design-system
layer that nested a theme would discard the colour scheme of every application that already had
its own.

**No Markdown here.** It goes in `agui-markdown`, an optional dependency, on the same principle
`agui-a2ui` follows.

**The Android test gap is repository-wide and is not closed in this module.** It is recorded here
once rather than argued again in a third build file.

## Consequences

A consumer gets a styled transcript from two composables and a `MaterialTheme`. The seam Decision 2
built survives the styling: Markdown arrives by providing a renderer, not by replacing slots.

`Material3AguiComponents()` returns a singleton, so the usual hazard of building a table inside a
`provides` -- both slot locals are `staticCompositionLocalOf`, and an unequal value recomposes the
whole transcript with skipping disabled -- does not apply to the call itself. It still applies to a
`copy` whose override captures, and the KDoc says so.

This module owns exactly one piece of mutable state: the reasoning disclosure. It follows the
stream while reasoning arrives and collapses when it ends, until the reader touches it, after which
their choice stands. The state is `remember` rather than `rememberSaveable`, so an expansion is
forgotten if the part scrolls out of the `LazyColumn`'s composition.

That limitation is not paid for by a dependency this module declined to take. `runtime-saveable`
is already on this module's compile classpath -- `agui-compose` exposes `foundation` as `api`,
`foundation` depends on `ui-text`, and `ui-text` depends on `runtime-saveable` -- so
`rememberSaveable` compiles here on every target with no artifact added to any consumer's graph.
The open question is behavioural rather than about size: whether a disclosure the reader opened
should survive the scroll, and process death with it. Nobody has answered it, so the module ships
the smaller behaviour.

Every user-visible word is English and not localised, collected in one internal `AguiStrings` so
the cost is visible rather than spread across five files. There is no multiplatform resource
mechanism a library can use without imposing its own choice of one on consumers.

Two published artifacts now ship Android `.aar`s that no test has run against. The suite is green
on `jvm` and `iosSimulatorArm64` and silent on the target most consumers will use.

## What would change the answer

- **A Robolectric or instrumentation harness in this repository.** Then the Android gap closes for
  both modules at once, which is the only way it is worth closing.
- **`agui-markdown` proving that consumers always want it.** If nobody ever takes `agui-material3`
  without also taking the parser, the module boundary is costing two dependencies to express one
  choice, and merging it here becomes the smaller thing.
- **The protocol gaining a content-type on text events.** The same trigger Decision 2 names: a
  default parser stops being an assertion once the wire says what its string is.

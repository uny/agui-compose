# 17. Drawing A2UI in a browser

Date: 2026-10-24

## Status

Accepted. Amends [0015](0015-drawing-in-a-browser-without-the-protocol-layer.md) for `agui-a2ui`,
`agui-a2ui-compose` and `agui-a2ui-material3`, which that decision kept on their targets. Takes a
second step on [#29](https://github.com/uny/agui-compose/issues/29) without closing it.

## Context

Decision 15 gave the drawing modules `js` and `wasmJs`, so that a WebView inside a native
application can draw a transcript the native side folded. Decision 16 left the bridge between the
two to the host. The A2UI modules stayed native, because `agui-a2ui` names upstream's types and
upstream publishes no web variant. The two A2UI drawing modules depend on `agui-a2ui`, so they
stayed native as well.

Read file by file, that applies to only part of `agui-a2ui`:

- **Three of its nine source files name upstream's types**: `A2uiRequest` and `catalogContext`
  build upstream's `Context`, and `RenderA2UiTool` is an `AbstractToolExecutor`. These are the
  request side of decision 11 and the tool of decision 8. Both run where the agent runs.
- **The other six name none.** These are the carriers, the payloads, the v0.9-to-v1.0
  translation, the surfaces and the action mapping, which is what turns a transcript into A2UI
  messages. `agui-a2ui-compose` and `agui-a2ui-material3` are built on these six and reference
  none of the three.
- **Every other dependency publishes both web backends.** This was read from the `.module` files
  on Central: `a2ui-core`, `a2ui-compose` and `a2ui-material3` 0.3.0 all carry `js` and `wasmJs`
  variants.

So the WebView of decision 15 can draw A2UI with everything except the three declarations, and
the three belong to the native side of decision 16's split.

Upstream has not moved. `kotlin-core`, `kotlin-client` and `kotlin-tools` are 0.4.1 on Central,
and publish decision 1's five targets. ag-ui-protocol/ag-ui#3017, a draft, adds `wasmJs` and no
`js`. The measurement is recorded on #29.

Three shapes were considered:

1. **A native-only source set inside `agui-a2ui`.** The three declarations compile for Android,
   the JVM and iOS. The rest of the module also compiles for the web.
2. **A separate module for the three declarations.** Every module would have the same API on
   every target. Existing consumers would have to add the new artifact to keep their code
   compiling, and the split could not be undone later without the same cost.
3. **Nothing until a WebView host exists.** No host exists in this repository yet.

## Decision

**`agui-a2ui`, `agui-a2ui-compose` and `agui-a2ui-material3` publish `js` and `wasmJs`, in shape
(1).** `agui-a2ui` goes from four targets to six, and each A2UI drawing module from four to six,
matching `agui-compose`.

**In `agui-a2ui`, `A2uiRequest`, `catalogContext` and `RenderA2UiTool` live in an `upstream`
source set**: a group in the default hierarchy template over Android, the JVM and the two iOS
targets. `upstreamMain` declares `kotlin-core` and `kotlin-tools`; `commonMain` no longer does.
Their two tests move to `upstreamTest` with them.

Shape (2) costs every existing consumer a dependency change, to give the web an API it has no use
for. Shape (3) waits for a consumer that decision 15 already planned for. The drawing path also
has a check now that it did not have before: the A2UI Compose tests run in a browser.

## Consequences

- **`agui-a2ui`'s API differs by target, and it is the only module where it does.** Decision 15
  said of its four modules that the published signatures were the same on the web targets as on
  the others. That still holds for those four. It does not hold here, on purpose. Shared code
  whose targets are all native still sees the three declarations. Shared code that includes a web
  target does not see them, and does not need to: a WebView does not send runs.
- **`js` stays partial for longer than `wasmJs`.** If upstream publishes `wasmJs`, which is what
  #3017 proposes, the `upstream` group gains `wasmJs` and the module's API is the same on it. `js`
  converges only when upstream adds `js` to `core` and `tools`. Nothing proposes that yet. Dropping
  `js` here instead is not an option: the A2UI drawing modules publish `js` like `agui-compose`
  does, and they cannot resolve without `agui-a2ui`'s `js` variant.
- **Adding a target is one-way.** Removing `js` or `wasmJs` later breaks every consumer that took
  it, the same as removing any published variant.
- **The Android ABI is not checked.** The klib dump covers iOS and the web, and marks the three
  declarations `[ios]`. The JVM dump is unchanged. Nothing dumps Android's API. The first version
  of this change selected Android with `withAndroidTarget()`, which does not match the
  `com.android.kotlin.multiplatform.library` target, so the AAR was built without the three
  declarations. The only build output that showed it was `testAndroidHostTest` running 22 tests
  instead of 34. The group now selects Android by platform type, as `smoke-test` already did, and
  the smoke test's `a2uiMain` resolves the three declarations against the published Android
  variant. With `withAndroidTarget()` put back, that check fails, as `smoke-test/README.md`
  records.
- **The tests run on every target the code reaches.** `agui-a2ui`'s 22 common tests run on `js`
  and `wasmJs`. The 12 that need upstream run on Android, the JVM and iOS. The A2UI Compose tests
  run under Kotlin/Wasm. Kotlin/JS gets a compile, for the reason decision 15 gives.
- **The consumer smoke test still has four shared source sets, and one of them changed meaning.**
  The A2UI modules move to `drawingMain`. `a2uiMain` keeps the four native targets, adds no
  dependency, and touches the three declarations. That makes it the check that a consumer whose
  shared code has no web target still sees them. The release workflows' task lists are unchanged,
  because they already compile both web backends and all four metadata source sets.
- **Each A2UI ABI dump's target list changed.** In `agui-a2ui`'s klib dump, the three declarations
  also moved under `// Targets: [ios]`. Nothing else changed.

## What would change the answer

- **Upstream publishing web variants.** With `wasmJs` on `kotlin-core` and `kotlin-tools`, the
  `upstream` group gains `wasmJs`. With `js` too, the group can go and the three files can move
  back to `commonMain`. `agui-core` and `agui-agent` need more than that, as decision 15 and #29
  record.
- **A browser with no native host behind it.** Then the browser sends its own runs, and needs the
  three declarations as well as the protocol modules. That is the decision that re-declares the
  protocol types, which decision 15 already names.

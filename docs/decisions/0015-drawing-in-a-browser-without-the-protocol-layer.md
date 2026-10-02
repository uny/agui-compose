# 15. Drawing in a browser without the protocol layer

Date: 2026-09-29

## Status

Accepted. Amends the target sets recorded in [0001](0001-riding-on-the-upstream-kotlin-sdk.md) and
[0002](0002-what-the-ui-layer-does-not-depend-on.md) for four modules, and takes the first step on
[#29](https://github.com/uny/agui-compose/issues/29) without closing it.

## Context

The use case is one chat screen that runs natively and inside a WebView, the latter compiled to
Kotlin/JS or Kotlin/Wasm. Today the WebView is always hosted by a native application. A browser with
no native host behind it is a later possibility, not a present requirement.

#29 recorded what stops each module from publishing a browser target, split by backend. One of its
three routes, replacing the JSON Patch dependency, was described as the only one that reaches
`wasmJs`. Read against the build rather than the issue's summary, it is a necessary condition and
not a sufficient one:

- **`agui-core` gains no target from it.** It declares `api(libs.agui.core)`, upstream's
  `kotlin-core`, which publishes the five targets of decision 1 and no browser variant. Its ABI
  dump names `com.agui.core.types.BaseEvent` on its own signatures.
- **`agui-agent` does not lose the dependency.** Upstream's `kotlin-client` declares
  `kotlin-json-patch` itself, for its own `StateManager` and `defaultApplyEvents`, so removing the
  direct dependency from `agui-core` leaves the artifact in every graph that runs an agent.
- **Upstream types are on three modules' public surfaces**, not one: `agui-core`'s events,
  `agui-agent`'s `AbstractAgent`, `RunAgentParameters` and `ToolRegistry`, and `agui-a2ui`'s
  `Context` and `AbstractToolExecutor`.

Two shortcuts past that were considered and do not work. Vendoring upstream's sources under their
own package names puts two copies of the same classes on any JVM or Android graph that also reaches
upstream through `agui-agent` -- D8 refuses the duplicate and the JVM silently loads whichever comes
first. Keeping upstream on the five native targets and a vendored copy on a web-only source set
does not compile: `commonMain` is compiled against `commonMain`'s dependencies, and the reducer lives
there. The form that does not collide is re-declaring the protocol types in this library's own
package, which is option (2) that decision 1 rejected, now with a web consumer to weigh against it.

What #29 left open was the question that decides between the routes: whether a browser needs the
render model or only the drawing. For a WebView inside a native application it needs only the
drawing. The native side can run the transport and the reducer it runs already, and hand the
WebView what they produce.

And the drawing does not depend on upstream at all. `agui-compose` depends on `agui-model` and
Compose; `agui-material3` adds Compose Material 3; `agui-markdown` adds intellij-markdown.
`agui-model`'s one dependency is kotlinx-serialization. Every one of those publishes `js` and
`wasmJs`: intellij-markdown 0.7.14 carries `js` and `wasm` variants in its module metadata, and
Compose Multiplatform 1.12.0 and material3 1.9.0 resolve and link for both backends in this build.
#29's table said the Compose modules inherit the protocol layer's blockers. That holds for the three
A2UI modules, which depend on `agui-a2ui`, and not for these.

## Decision

**`agui-model`, `agui-compose`, `agui-material3` and `agui-markdown` publish `js` and `wasmJs`.**
`agui-model` goes from five targets to seven, and each drawing module from four to six.

**`agui-core`, `agui-agent`, `agui-a2ui` and the two A2UI drawing modules keep their targets.**
Decision 1's five, or four where Compose removes `iosX64`, for the reasons above.

**The JSON Patch dependency is not replaced yet.** It unblocks nothing on its own. `agui-core` stays
native-only while it names upstream's types, and `kotlin-client` keeps the artifact in the agent
graph regardless. A hand-written RFC 6902 implementation is new surface to get wrong -- `move`
into a descendant, `~0`/`~1` escapes, the `-` index, atomicity across operations -- and would
be written for no consumer. It becomes the first step of re-declaring the protocol types if that
decision is taken, and is recorded on #29 as such.

## Consequences

- **The drawing tests run in a browser.** `composeUiTest` is wired to `wasmJs` as well as `jvm` and
  the iOS targets, so every Compose test in the three modules executes under Kotlin/Wasm. It is not
  wired to `js`: Compose's test harness cannot boot Skiko on Kotlin/JS, which `a2ui-compose`
  measured and works around the same way. `js` compiles the renderers and runs `commonTest`, which
  in `agui-markdown` is the parsing and the style factories.
- **The Gradle heap is 6G.** Linking a Kotlin/Wasm executable with Compose and Material 3 in it ran
  out of memory at 4G, as `a2ui-compose` found before.
- **CI runs the web backends on Linux, in their own jobs.** The macOS job keeps the Apple targets and
  the ABI check; it excludes the browser tests and bundles, which need no Apple toolchain.
- **The release still runs everything on one runner.** `cd.yml` and `release-dry-run.yml` verify with
  the full `build checkKotlinAbi` on macOS, web backends included -- as `a2ui-compose`'s release
  does on the same runner at the same heap.
- **The consumer smoke test has four shared source sets, one per target list**, and compiles `js`
  and `wasmJs` against the published variants. See `smoke-test/README.md`.
- **Each affected ABI dump's target list changed**, and nothing else in them did: the published
  signatures are the same on the web targets as on the others.
- **`kotlin-js-store/` is checked in**: the yarn lock files for the npm packages the JS and Wasm
  test runners resolve.
- **A WebView host still has a bridge to build.** `agui-model`'s types are not `@Serializable`, so
  handing a `UiTranscript` from the native side to the WebView needs either serializers here or a
  mapping in the host. The other direction, sending a message or answering an interrupt from the
  WebView, has no shape in this library yet. Both are open on #29.

## What would change the answer

- **A browser with no native host behind it.** Then the render model has to run in the browser,
  and the protocol types have to be re-declared in this library's package -- superseding decision
  1's choice of option (1), with a new decision saying so. Replacing JSON Patch comes first, with a
  conformance corpus and a differential test against the current library before the dependency
  goes. `agui-agent` would need a browser transport as well.
- **Upstream publishing web targets.** With web variants of `kotlin-core`, `agui-core` needs only
  the JSON Patch replacement. The signal is a `js` or `wasmJs` target in upstream's
  `sdks/community/kotlin/library/*/build.gradle.kts`, and then in a release on Central.

*Corrected 2026-10-02:* this entry named ag-ui-protocol/ag-ui#2787 as the surface to watch. That PR
merged on 2026-09-30 and aligns the Kotlin SDK with the AG-UI 1.0 schema; it touches sources and
tests only, and adds no target. Upstream's `kotlin-core` still publishes decision 1's five, so
nothing above changes.

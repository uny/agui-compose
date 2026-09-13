# 9. Drawing A2UI from three carriers

Date: 2026-09-13

## Status

Accepted.

## Context

A2UI is the generative-UI protocol AG-UI's own examples draw with: an agent describes a surface as
JSON -- `createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface` -- and the client
renders it with widgets it holds. `docs/decisions/0001` left interpreting it to a module named
`agui-a2ui` and noted two routes in. Measuring upstream (`ag-ui-protocol/ag-ui` at `7479336`,
2026-09-12) found three, a version gap, and one correction to 0001.

**Where A2UI comes from upstream.** The client-side middleware 0001 could not locate is
`middlewares/a2ui-middleware` (TypeScript, `@ag-ui/a2ui-middleware` 0.0.10). It wraps an agent and
does three things to the stream: it watches `TOOL_CALL_ARGS` for a tool named `render_a2ui` and
emits cumulative `ACTIVITY_SNAPSHOT` events with `activityType: "a2ui-surface"` and `replace: true`
as the arguments close -- preceded by `{"status": "building"}` under the *same* `messageId`, so the
paint replaces the skeleton in place; it parses every `TOOL_CALL_RESULT` for
`{"a2ui_operations": [...]}` and emits an activity for that too; and at run end it synthesises a
`TOOL_CALL_RESULT` of `{"status": "rendered"}` for a `render_a2ui` call the server left open.
Without the middleware -- a Kotlin client talking straight to a Python server -- none of that
happens, and what is on the wire is the streamed `render_a2ui` arguments and the outer tool's
result. So a surface has **three carriers**, and in upstream's own recordings the same surface
arrives in all three during one run: the activity, the `render_a2ui` arguments, and the
`generate_a2ui` result.

**The version gap.** Every upstream emitter puts `"version": "v0.9"` on its envelopes;
`a2ui-compose` (`dev.ynagai.a2ui` 0.1.0) implements v1.0 and its parser refuses any other version.
The evolution guide lists the wire differences for these four messages as: the version string,
`createSurface.theme` removed, `attachDataModel` renamed `sendDataModel`, `updateDataModel.value`
required with `null` meaning delete; the flat component format, `{"path": ...}` bindings and
`children: {componentId, path}` templates are unchanged. The `render_a2ui` arguments themselves
carry no version and no catalog at all -- `surfaceId`, `components`, `data` -- so that carrier
needs no rewrite; it is the shape of a v1.0 `createSurface` with inline components.

**The catalog id.** Upstream's toolkit names the basic catalog
`https://a2ui.org/specification/v0_9/basic_catalog.json`. The v0.9 specification names it
`.../v0_9/catalogs/basic/catalog.json`; `a2ui-compose` registers `.../v1_0/catalogs/basic/catalog.json`.
A v1.0 renderer resolves a component against the surface's catalog and, on a miss, draws the root
alone -- so the id has to be mapped whatever the version.

**The correction.** 0001 says `RenderA2UiToolExecutor` is in upstream's `kotlin-tools`. It is not:
it is in the example application `chatapp-shared`, wrapping `com.contextable:a2ui-4k` 0.9.3 -- a
v0.9 Kotlin renderer -- and `kotlin-tools` carries nothing A2UI-specific.

**The sample's server.** No upstream server that speaks A2UI runs without a model, a key and a
Python runtime; `server-starter-all-features`, which the sample used, has no A2UI at all. The two
LangGraph examples that do use custom catalogs (`HotelCard`, `ProductCard`, `TeamMemberCard`,
`FlightCard`) registered on the dojo's React frontend, which a client holding only the basic
catalog cannot draw. Upstream's dojo end-to-end suite, however, ships **recordings** of what those
agents streamed through the middleware to a browser -- `apps/dojo/e2e/tests/langgraphPythonTests/
a2ui*.event-trace.ts`, MIT -- and they contain every carrier.

## Decision

**Four modules, in the layering the rest of the repository uses, plus one server.**

- `agui-a2ui` (no Compose; `agui-model`, `a2ui-core`, upstream `kotlin-core` and `kotlin-tools`):
  the JSON-to-JSON rewrite from v0.9 to v1.0, applied *before* `a2ui-core` parses so that its
  parser stays the one place v1.0 is enforced; the three carriers as `A2uiCarrier`, keyed by the
  protocol's ids -- `messageId`, `toolCallId` -- and never by `UiPart.id`, because a
  `MESSAGES_SNAPSHOT` rebuilds the parts; `A2uiPayload`, which is a surface *or* a lifecycle state
  (`building`, `retrying`, `failed`) *or* something unreadable, since the middleware sends the
  first two under the same activity the surface later replaces; and `A2uiSurfaces`, the
  reconciler.
- `agui-a2ui-compose`: `A2uiHost`, which keeps an `A2uiRenderer` in step with a transcript, and
  `withA2ui`, which fills the `activity` and `toolCall` slots. No design system.
- `agui-a2ui-material3`: the basic catalog's Material 3 renderers and a Material 3 pending state.
- `agui-replay` (JVM, unpublished): the five recordings as resources, and a Ktor CIO server that
  plays one per route over SSE, rewriting only the thread and run ids to the client's.

**Ownership, not deduplication.** One surface, three carriers: the reconciler gives each surface
to one owner -- among the carriers that *create* it, activity over tool result over streamed
arguments, and among equals the later in the transcript -- and recomputes that on every step, so
the order the carriers arrived in decides nothing. A carrier that only updates or deletes a
surface never owns it: it has nothing to draw in the creator's place, and taking the surface from
the creator would take it off the screen for nobody. The activity is the middleware's own cumulative, validated form, which is why it
outranks the result it was built from. A carrier whose every surface is owned elsewhere is
`Shadowed` and draws nothing; a `render_a2ui` call shadowed by its activity draws *not even the
tool call*, since "render_a2ui (awaiting result)" under a surface already on screen would be a
status line for the thing above it. Any other tool call keeps its ordinary rendering.

**Delete before replay.** `a2ui-core` throws on `createSurface` for a surface that exists, and the
middleware repeats `createSurface` in every cumulative snapshot. The reconciler remembers what
each carrier last applied and, when that changes, deletes the surfaces the carrier created and
replays the new payload whole -- a snapshot is one document, which is the specification's own
reading of `replace`, and the cost is a redraw of one surface, which is what a new snapshot means.
Per-carrier batches, applied atomically, so a payload one carrier got wrong costs that carrier's
surfaces and nobody else's; `A2uiRenderer.applyAll` leaves its state untouched when it throws,
which is what makes that atomicity real.

**The catalog is mapped, not chosen.** `A2uiTranslation.catalogIds` turns upstream's spellings
of "basic" -- two URLs and the bare word -- into the v1.0 basic catalog and passes every other id through: a custom catalog's id
is the host's, and the host that registered it is the one that can draw it. The client also sends
its catalog as the `Context` entry the middleware reads the id from (`catalogContext`), so a
middleware in front of the agent stamps a catalog the client holds on the surfaces it streams.

**`RenderA2UiTool` exists and registers nothing.** Two upstream models of the tool coexist: the ADK
adapter has the main model call `render_a2ui` at the top level and wait on the client's answer;
the LangGraph adapter calls it from a subagent inside `generate_a2ui`, closes it on the server, and
never puts a result on the wire. A client that registered the executor against the second would
answer a call it was not asked to run. So the executor is in `agui-a2ui`, and the decision to
register it is made per agent by whoever builds the `ToolRegistry`.

**The sample draws the dojo's catalog against the recordings.** `DojoCatalog` is the dynamic
catalog's four components as a v1.0 `CatalogDefinition` -- `Row` declared in it, because v1.0 has
no fallback to the other catalogs a renderer holds -- with three card renderers. The sample's
end-to-end test starts `agui-replay` on a free port, connects `HttpAgent` to it, sends a turn, and
asserts the three hotels are on screen once. That is what "verified against a real server" means
here: what a real server said, over a real socket, minus the model that said it.

## Consequences

- **`agui-a2ui` is four targets, not five.** `a2ui-core` publishes no `iosX64`, so nothing
  depending on it can. The lower layers keep the fifth target as before.
- **A surface changes hands at run end, and redraws.** The run's closing `MESSAGES_SNAPSHOT` is the
  server's history, which never held the middleware's synthetic activity; the reducer replaces the
  transcript with it, the activity carrier disappears, and the outer tool's result -- which the
  snapshot does hold -- becomes the owner. One surface throughout; one delete-and-recreate when
  the run ends. A reducer that kept activities across a snapshot would avoid it, and would be
  reading the protocol's `MESSAGES_SNAPSHOT` as something other than a replacement, which 0001
  decided against.
- **`render_a2ui` stays `AWAITING_RESULT` in a direct connection.** With no middleware and no
  registered executor, the inner call never gets a result. The transcript reports that truthfully,
  and `withA2ui` draws the surface in its place rather than the status.
- **Progressive rendering is at the middleware's granularity, not the token's.** A streamed
  `render_a2ui` call draws as `building` until its arguments parse, then whole. Upstream's
  middleware extracts closed components and data items from a partial JSON string; this module
  does not, and the recorded activities carry the middleware's progressive snapshots anyway.
- **The recordings are upstream's, verbatim, and can go stale.** They are pinned to the commit
  noted in `agui-replay/src/main/resources/traces/README.md`. Re-recording is re-running the
  converter against a newer checkout; nothing in this repository depends on their ids or their
  wording except the sample test's three hotel names.
- **0001's note on `RenderA2UiToolExecutor` is corrected in place.**

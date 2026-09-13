# Recorded upstream traffic

Event streams recorded from `ag-ui-protocol/ag-ui`'s dojo end-to-end suite, at commit
`747933694b05676203da7d5bb8d8e50432e59b75` (2026-09-12), converted from
`apps/dojo/e2e/tests/langgraphPythonTests/*.event-trace.ts` to JSON arrays with no other change.
Ids are the suite's own normalised ones (`id-1`, `id-2`, ...); the replay server rewrites the
thread and run ids to the requesting client's and leaves everything else alone.

Each is what a browser received from the dojo's Node server: the LangGraph Python agent's stream,
with `@ag-ui/a2ui-middleware` in front of it. So they carry the `a2ui-surface` activities the
middleware synthesises *and* the raw `render_a2ui` argument stream and `a2ui_operations` results
the agent produced, which is what makes them useful -- one recording exercises every carrier.

| File | Journey | Catalog |
|:--|:--|:--|
| `advanced-hotel-comparison.json` | `a2uiAdvanced` / hotel comparison | dojo dynamic |
| `dynamic-team-roster.json` | `a2uiDynamicSchema` / team roster | dojo dynamic |
| `dynamic-product-comparison.json` | `a2uiDynamicSchema` / product comparison | dojo dynamic |
| `fixed-flight-search.json` | `a2uiFixedSchema` / flight search | dojo fixed |
| `fixed-multiple-surfaces.json` | `a2uiFixedSchema` / two surfaces over two runs | dojo fixed |

Upstream is MIT-licensed; its notice is reproduced in `LICENSE-ag-ui` beside this file.

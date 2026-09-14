# The drawing server

What the sample is pointed at to see whether an agent draws when the client asks: AWS Strands with
a plain agent and **no A2UI config on the server**. Injection of `generate_a2ui`, the catalog id
its surfaces bind to and the component list its sub-agent reads all come from the run the client
sends -- the `A2UI Component Schema` context entry and `forwardedProps.injectA2UITool` that
`A2uiRequest` produces (see [0011](../../decisions/0011-asking-an-agent-to-draw.md)); the adapter
lifts the entry into run state and the sub-agent's prompt as `## Available Components`. None of
upstream's examples works this way round: the dojo's `a2ui_dynamic_schema` agent also injects only
on the client's flag, but stamps its own catalog id and hands the sub-agent a hand-written
composition guide from the server side, hence this file. One thing here is not plain: the agent's
system prompt is upstream's, verbatim, and it names `generate_a2ui`; a run with a plain prompt has
not been measured. It needs a key for a model.

```
cd docs/live/a2ui
MODEL_PROVIDER=openai OPENAI_API_KEY=… uv run python server.py
```

Export the key on its own line first if the terminal is shared with anything that reads it.
`MODEL_PROVIDER` is `openai` (`gpt-5.4`, over Chat Completions), `anthropic` (`claude-sonnet-4-6`)
or `gemini` (`gemini-2.5-flash`); `MODEL_ID` overrides the model, `PORT` the port.

Then, from the repository root:

```
./gradlew :agui-sample:run
```

Point the sample at `http://127.0.0.1:8000/` and type `Compare three hotels in Kyoto`. The sample
sends `A2uiRequest(DojoCatalog.definition)` with every run, so what should come back is a
`generate_a2ui` tool call whose result carries `a2ui_operations`, with the sub-agent's `render_a2ui`
arguments streamed before it -- two of the three carriers of
[0009](../../decisions/0009-drawing-a2ui-from-three-carriers.md); there is no middleware here to
emit the activity -- and the transcript should draw a row of `HotelCard`s under the call. A plain
text answer listing hotels means either that the model was offered the tool and did not take it, or
that the tool was never injected because the run carried no `injectA2UITool`: the request body in
the trace tells the two apart. No tool call and no text means look at the server log.

Two switches for telling failures apart:

- `AGUI_TRACE=path` appends every request body and every byte of every response to that file, so
  the run can be read after the window is closed and attached to a pull request. It holds the whole
  conversation and any provider error text verbatim; read it before attaching it.
- `A2UI_SERVER_GUIDE=1` gives the server the A2UI config upstream's `a2ui_dynamic_schema` has: the
  dojo catalog id and upstream's hand-written composition guide, which the sub-agent then reads
  alongside the component list the client sent. Injection still waits on the client's flag. A model
  that draws with this and not without it is a model that needs more than the catalog; a model
  that draws with neither is not drawing.

What it was measured against, on 2026-09-14: `ag_ui_strands` 0.4.0 at upstream `7479336`,
`strands-agents` 1.55.1, `gpt-5.4`, without `A2UI_SERVER_GUIDE`. The surface arrived, in both
carriers, and the sample drew it; the recording is attached to
[#15](https://github.com/uny/agui-compose/pull/15). The Consequences of 0011 carry the result.

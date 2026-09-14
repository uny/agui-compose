# The drawing server

What the sample is pointed at to see whether an agent draws when the client asks: AWS Strands with
a plain agent and **no A2UI wiring on the server**. Injection of `generate_a2ui`, the catalog it
draws against and the guide its sub-agent reads all come from the run the client sends -- the
`A2UI catalog` context entry and `forwardedProps.injectA2UITool` that `A2uiRequest` produces
(see [0011](../../decisions/0011-asking-an-agent-to-draw.md)). None of upstream's examples works
this way round: the dojo's `a2ui_dynamic_schema` agent opts in on the server and hands the
sub-agent its own composition guide, hence this file. It needs a key for a model.

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
emit the activity -- and the transcript should draw a row of `HotelCard`s under the call. A plain text answer listing hotels means the model was offered the
tool and did not take it; no tool call and no text means look at the server log.

Two switches for telling failures apart:

- `AGUI_TRACE=path` appends every request body and every byte of every response to that file, so
  the run can be read after the window is closed and attached to a pull request.
- `A2UI_SERVER_GUIDE=1` makes the server opt in the way upstream's `a2ui_dynamic_schema` does,
  with upstream's hand-written composition guide instead of the catalog the client sends. A model
  that draws with this and not without it is a model that needs more than the catalog; a model
  that draws with neither is not drawing.

What it was measured against: see the pull request that added this file, and the Consequences of
0011.

# The approval server

What `agui-agent`'s `LiveApprovalTest` talks to, and what the sample was pointed at for the
screenshots in [#11](https://github.com/uny/agui-compose/pull/11): AWS Strands with one
server-executed tool behind `ToolBehavior(interrupt_on_call=True)`. It needs a key for a model.

```
cd docs/live/approval
MODEL_PROVIDER=gemini GOOGLE_API_KEY=… uv run python server.py
```

Then, from the repository root:

```
AGUI_LIVE_APPROVAL_URL=http://127.0.0.1:8000/ ./gradlew :agui-agent:jvmTest --tests '*LiveApprovalTest'
```

Without the variable the two tests are reported as skipped, which is what CI does. The model is
not what is measured -- the assertions are about the interrupt's shape and the tool's fate -- but
it has to call the tool when told to; `gemini-2.5-flash`, `gpt-5.4` and `claude-sonnet-4-6` are the
three the server knows how to construct.

What it was measured against, on 2026-09-13: `ag_ui_strands` 0.4.0 at upstream `7479336`,
`gemini-2.5-flash`. Raw traffic for one approved and one declined transfer is attached to the pull
request.

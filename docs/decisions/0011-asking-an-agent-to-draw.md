# 11. Asking an agent to draw

Date: 2026-09-14

## Status

Accepted. Extends decision 9, which read A2UI off the response and left the request to the caller.

## Context

Decision 9 made `agui-a2ui` a reader: three carriers on the response side, one translation, one
`Context` entry (`catalogContext`) for the caller to send so a streamed surface binds to a catalog
the client holds. The sample sent that entry. Against upstream's recordings that was the whole
story, because a recording does not care what was asked.

Against a live server it was not. Pointed at upstream's `aws-strands` `a2ui_dynamic_schema` agent
(`ag-ui-protocol/ag-ui` at `7479336`), the sample got text and no surface, with nothing in the
stream to say why. The request had 111 input tokens: the agent's model was never offered a tool to
draw with. Reading the adapter, `plan_a2ui_injection` in `a2ui_tool.py` is explicit -- "no
`injectA2UITool`, no injection", mirroring the LangGraph adapter -- and the flag it reads is a key
in `forwardedProps`. The ADK, CrewAI, Mastra and Microsoft adapters read the same key. None of
this is in the AG-UI specification, and none of it is in the response-side contract decision 9 read.

Where the flag comes from, upstream, is `@ag-ui/a2ui-middleware`, running in the TypeScript
runtime in front of the agent. For a run it is configured to inject on, `injectToolAndFlag` sets
`forwardedProps.injectA2UITool` to its configured value (`true`, or a custom render tool's name),
adds the `render_a2ui` tool to `tools`, and `injectSchemaContext` and `injectToolGuidelines` add two
`Context` entries: the component schema under the description `catalogContext` already uses, and a
usage guide for the render tool under `A2UI render tool usage guide — how to call <name> with valid
arguments.`. The dojo's Strands agents get all four (`agents.ts`, `STRANDS_A2UI_INJECT_AGENTS`,
with `DOJO_A2UI_MIDDLEWARE_CONFIG`). On the Python side the toolkit's `split_a2ui_schema_context`
lifts the schema entry into `state["ag-ui"]["a2ui_schema"]` and every other entry into
`state["ag-ui"]["context"]`, and `build_context_prompt` puts both in front of the generating
sub-agent -- so the guide text reaches the model behind the dojo, guidelines included.

A Kotlin client has no TypeScript runtime in front of its agent. What the middleware adds to the
request, it has to add itself, or the agent behaves as if nobody asked it to draw.

One thing measured on the way is not this decision's to fix. With the flag and the schema entry
sent, Strands injected `generate_a2ui` and its Gemini-backed sub-agent produced
`"components": [{}]` until recovery gave up (`a2ui_recovery_exhausted`) -- a tool-argument problem
between that model and that adapter, server-side, and the same with or without the schema entry.
Whether an OpenAI or Anthropic model behind the same adapter draws with what this client now sends
is unmeasured; the keys were not available to this repository.

## Decision

**`agui-a2ui` gets the request side: `A2uiRequest`.** For a `CatalogDefinition` it builds the
`forwardedProps` and the `Context` list a run has to carry, mirroring the middleware's
`injectToolAndFlag`, `injectSchemaContext` and `injectToolGuidelines`:

- `forwardedProps.injectA2UITool`: a JSON `true`, or the render tool's name when it is not
  `render_a2ui` -- the two values the middleware forwards from its configuration. Never `false`:
  the adapters treat an explicit `false` as overriding a server-side opt-in, and absence as
  deferring to it.
- The schema entry, built by `catalogContext` as before. Its value stays the full v1.0 catalog
  document: what the machine readers want -- `catalogId` and `components` at the top level -- is
  there, and the rest is prose for the model.
- The usage guide, verbatim from the middleware's `RENDER_A2UI_TOOL_GUIDELINES`, under the
  middleware's description, on by default. It is prose the model reads and the dojo's model reads
  it, so a client that wants the dojo's output quality sends it; a caller that has its own
  instructions turns it off.

Both builders take what the caller already carries and add to it, replacing an entry of the same
description or a value under the same key rather than adding a second -- the middleware's
behaviour, and what keeps a caller who also sends `a2uiAction` or its own design guidelines from
losing them.

**It does not declare the `render_a2ui` tool.** The middleware adds it to `tools` because it also
answers the call. Here, declaring a tool is what registering an executor does, and
`RenderA2UiTool`'s KDoc says when to register it and when not; a declaration without an executor
would recreate the `AWAITING_RESULT` case decision 9 describes. Strands drops the declaration by
name and calls `generate_a2ui` instead, so for the adapters that inject, nothing is lost by not
sending it.

**The strings live in `AguiA2ui`**, beside the response-side ones: `INJECT_TOOL_KEY`, and
`guidelinesContextDescription(toolName)`. Same reasoning as decision 9 -- they are upstream's
agreement, not a specification's, and the day one changes the change here is one line.

**The sample sends it on every run**, in place of the bare `catalogContext` it sent before.

## Consequences

- **The module is no longer a reader only.** `A2uiRequest` is the one place `agui-a2ui` says what
  to send. It depends on `agui-core`'s `Context` and on kotlinx-serialization's `JsonObject`, both
  already in the module's API, and not on `RunAgentParameters`: that type is `kotlin-client`'s,
  which this module does not depend on, so the caller builds the parameters from the two pieces.
- **The wire shape is tested; the effect is not.** `A2uiRequestTest` pins what the adapters match
  on -- the descriptions byte for byte, the flag as a JSON boolean, the schema value's top-level
  keys, the replace-not-append behaviour. Whether an agent then draws is a property of the model
  behind it, and the one model measured could not. The one-shot check, for whoever has a key: the
  dojo's `a2ui_dynamic_schema` Strands agent with an OpenAI or Anthropic model, and the sample.
- **A client behind upstream's middleware sends these twice.** The middleware replaces its own
  entries and its own key and adds nothing, which is the behaviour this code copies from it, so
  the duplication is harmless. A client behind a middleware configured *not* to inject would now
  turn injection on from the client; that is the middleware's `??` fallback working as designed,
  and a client that wants the server's choice does not send `A2uiRequest`.
- **The guide names v0.9.** It is upstream's text, for upstream's agents, which emit v0.9 and
  which `A2uiTranslation` rewrites. It is not a statement about what this client renders.

package dev.ynagai.agui.a2ui

import com.agui.core.types.Context
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a run has to carry for an agent to draw A2UI at all: the request side of the convention
 * whose response side the rest of this module reads.
 *
 * Upstream's agents do not offer to draw. Their adapters -- Strands, LangGraph, ADK, CrewAI,
 * Mastra -- inject their `generate_a2ui` tool only when the run's `forwardedProps` carries
 * `injectA2UITool`, and put the client's components in front of the model only when the run's
 * `context` carries the schema entry [catalogContext] builds. Neither is in the AG-UI
 * specification; both are what `@ag-ui/a2ui-middleware` adds to every request when it runs in
 * front of the agent, and a client that talks to the agent without that middleware in front --
 * which is every client this repository is for -- has to add them itself. Without them the
 * agent answers in text, with nothing in the stream to say why.
 *
 * Three things, mirrored from the middleware's `injectToolAndFlag` and `injectSchemaContext`:
 *
 * - `forwardedProps.injectA2UITool`: `true`, or the render tool's name when it is not the default,
 *   which is the value the middleware forwards from its own configuration. Never `false`: an
 *   explicit `false` overrides an agent's server-side opt-in, and absence does not.
 * - The schema `Context` entry, from [catalogContext]. The agents read the catalog id out of it
 *   and hand the rest to the model.
 * - The render tool's usage guide, a second `Context` entry the middleware sends alongside the
 *   schema. The Python adapters put every context entry into the generating sub-agent's prompt,
 *   so an agent behind upstream's dojo sees this text, and an agent behind this client should see
 *   the same. Off with [guidelines] `false`.
 *
 * What it does not do is declare the `render_a2ui` tool. The middleware adds it to `tools`
 * because it answers the call itself; here, declaring a tool is what registering an executor
 * does, and [RenderA2UiTool]'s KDoc says when to. Strands drops the declaration by name and
 * calls `generate_a2ui` instead, so an agent that never asks the client to answer loses nothing.
 *
 * ```kotlin
 * val request = A2uiRequest(catalog)
 * RunAgentParameters(context = request.context(), forwardedProps = request.forwardedProps())
 * ```
 *
 * Send it on every run. Both methods take what the caller already carries and add to it,
 * replacing an entry of the same description or key rather than adding a second.
 *
 * @param catalog the catalog this client draws -- the one its renderer holds.
 * @param renderToolName the render tool's name, for an agent configured with a custom one.
 * @param guidelines whether to send the usage guide.
 */
public class A2uiRequest(
    private val catalog: CatalogDefinition,
    private val renderToolName: String = AguiA2ui.RENDER_TOOL_NAME,
    private val guidelines: Boolean = true,
) {
    init {
        // An empty name would go out as `injectA2UITool: ""`, which the adapters read as an explicit
        // off -- the one value this class promises never to send.
        require(renderToolName.isNotEmpty()) { "renderToolName must not be empty" }
    }

    /** [existing] with the schema entry, and the guidelines entry when on, replacing any of the same description. */
    public fun context(existing: List<Context> = emptyList()): List<Context> {
        val guide = AguiA2ui.guidelinesContextDescription(renderToolName)
        val added = buildList {
            add(catalogContext(catalog))
            if (guidelines) add(Context(description = guide, value = renderToolGuidelines(renderToolName)))
        }
        val replaced = added.mapTo(HashSet()) { it.description }
        return existing.filterNot { it.description in replaced } + added
    }

    /** [existing] with `injectA2UITool` set, replacing any value already under that key. */
    public fun forwardedProps(existing: JsonObject = JsonObject(emptyMap())): JsonObject {
        val flag = if (renderToolName == AguiA2ui.RENDER_TOOL_NAME) JsonPrimitive(true) else JsonPrimitive(renderToolName)
        return JsonObject(existing + (AguiA2ui.INJECT_TOOL_KEY to flag))
    }
}

/**
 * The middleware's `RENDER_A2UI_TOOL_GUIDELINES`, verbatim. Prose the model reads, so a drift
 * from upstream's wording costs output quality rather than a contract; kept verbatim so the
 * model behind this client is told what the model behind the dojo is told.
 */
internal fun renderToolGuidelines(toolName: String): String = """
## How to call $toolName

You MUST provide ALL required arguments when calling $toolName:

- **surfaceId** (string, required): Unique ID for the surface (e.g. "sales-dashboard").
- **components** (array, REQUIRED): A2UI v0.9 flat component array. NEVER omit this.
- **data** (object, optional): Initial data model for path-bound component values.

Note: the catalog id is set by the host, not by you. Do not include a catalogId argument.

### Component format (v0.9 flat)

Components are a flat array — children are referenced by ID, not nested:
- Every component has `id` (unique) and `component` (type name from the available catalog).
- The root component MUST have `id: "root"`.
- Properties go directly on the component object.
- Use `children: ["id1", "id2"]` for multiple children, `child: "id"` for a single child.

### Minimal example

```json
{
  "surfaceId": "my-dashboard",
  "components": [
    { "id": "root", "component": "Column", "children": ["title", "row1"] },
    { "id": "title", "component": "Title", "text": "Overview" },
    { "id": "row1", "component": "Row", "children": ["m1", "m2"], "gap": 16 },
    { "id": "m1", "component": "Metric", "label": "Users", "value": "1,200" },
    { "id": "m2", "component": "Metric", "label": "Revenue", "value": "${'$'}50K" }
  ]
}
```

### Key rules

1. NEVER call $toolName without the `components` array — the UI will be empty.
2. Root must be a layout component (Column, Row, Card) — not Text or Button.
3. Component IDs must be unique. A component must NOT reference itself as child.
4. Only use component names from the Available Components schema in context.
5. For data binding use `{ "path": "/key" }` (absolute) or `{ "path": "key" }` (relative inside templates).
6. For repeating content: `children: { componentId: "card-id", path: "/items" }` repeats per array item.
7. Button actions: `"action": { "event": { "name": "action_name", "context": { ... } } }` — event must be an object.
8. No placeholder images — only use real URLs or Icon components.
""".trimIndent()

package dev.ynagai.agui.a2ui

import com.agui.core.types.Context
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition

/**
 * The `Context` entry that tells an agent which catalog this client draws.
 *
 * Two readers, upstream, and they want different things from it. The middleware reads the
 * `catalogId` and stamps it on the `createSurface` it emits for a streamed render call, so the
 * surface resolves against a catalog the client holds rather than against `basic`. The LangGraph
 * adapter lifts the whole value into the graph's state, where a prompt can put the component
 * schemas in front of the model. The value is the catalog document itself, serialised as v1.0,
 * because that is the one form both readers can take: `catalogId` and `components` are at the
 * top level of it, which is all either looks for.
 *
 * Send it on every run, in `RunAgentParameters.context`. The middleware replaces it with a
 * server-side schema when one is configured, and passes it through when not. On its own it
 * tells an agent what the client draws, not that the agent may draw; [A2uiRequest] sends both.
 */
public fun catalogContext(catalog: CatalogDefinition): Context = Context(
    description = AguiA2ui.SCHEMA_CONTEXT_DESCRIPTION,
    value = A2uiJson.strict.encodeToString(CatalogDefinition.serializer(), catalog),
)

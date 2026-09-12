package dev.ynagai.agui.a2ui

import dev.ynagai.a2ui.core.A2ui
import dev.ynagai.a2ui.core.protocol.A2uiFormatException
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns what upstream puts on the wire into the v1.0 messages `a2ui-core` parses.
 *
 * Upstream's toolkit and middleware emit A2UI **v0.9** envelopes; `a2ui-core` implements v1.0 and
 * refuses any other `version` outright. Between the two, for the four messages upstream emits, the
 * wire difference is mechanical -- read off the specification's own evolution guide, and checked
 * here against recorded traffic:
 *
 * - `version` is `"v0.9"` and must read `"v1.0"`;
 * - `createSurface.theme` is gone, and `createSurface.attachDataModel` became `sendDataModel`;
 * - `updateDataModel.value` became required, with `null` meaning delete -- so an omitted value
 *   is sent as `null`, which is the nearest v1.0 has to what v0.9 let an agent leave out;
 * - the flat component format, `{"path": ...}` bindings and `children: {componentId, path}`
 *   templates are the same shape in both.
 *
 * What is *not* mechanical is the catalog id. Upstream names `v0_9/basic_catalog.json` -- a string
 * that is not even the v0.9 specification's id for that catalog -- and `a2ui-compose` registers the
 * v1.0 one, so a surface that named the upstream id would resolve to no catalog and draw its root
 * alone. [catalogIds] is where that mapping lives, and [upstreamCatalogIds] is the default: the two
 * upstream spellings of "basic" become the v1.0 basic catalog and everything else passes through,
 * because a custom catalog's id is the host's own and this module has no business renaming it.
 *
 * The rewrite is JSON to JSON and happens before parsing, on purpose: `a2ui-core`'s parser is the
 * one place the v1.0 rules are enforced, and a translation that built its messages by hand would
 * be a second, weaker parser.
 *
 * @param catalogIds what a `catalogId` on the wire becomes. Applied to `createSurface` only; a
 *   v0.9 component carries no catalog of its own.
 * @param defaultCatalogId the catalog a [rendered] surface is bound to. A `render_a2ui` call names
 *   no catalog -- the host chooses -- so this is that choice.
 */
public class A2uiTranslation(
    public val catalogIds: (String) -> String = ::upstreamCatalogIds,
    public val defaultCatalogId: String = AguiA2ui.V10_BASIC_CATALOG_ID,
) {
    /**
     * One envelope -- v0.9 or already v1.0 -- as the message it names.
     *
     * @throws A2uiFormatException when it is neither: no `version`, an unknown one, or a body
     *   `a2ui-core` cannot read. `A2uiFormatException` extends `SerializationException`, and so
     *   does what the JSON parser throws, so a caller catching the latter catches both.
     */
    public fun message(envelope: JsonObject): AgentToRendererMessage {
        val rewritten = when (envelope["version"]?.asString()) {
            AguiA2ui.UPSTREAM_VERSION -> upgrade(envelope)
            A2ui.PROTOCOL_VERSION -> envelope
            null -> throw A2uiFormatException("A2UI envelope: `version` is required.")
            else -> envelope // `a2ui-core` says which versions it takes; let it say so.
        }
        return A2uiJson.lenient.decodeFromJsonElement(AgentToRendererMessage.serializer(), rewritten)
    }

    /**
     * An `a2ui_operations` array as messages, in order.
     *
     * @throws A2uiFormatException on the first element that is not an envelope, with the messages
     *   before it lost -- an operations array is one document, and half of one is not a surface.
     */
    public fun operations(operations: JsonArray): List<AgentToRendererMessage> =
        operations.mapIndexed { index, element ->
            val envelope = element as? JsonObject
                ?: throw A2uiFormatException("a2ui_operations[$index]: not an object.")
            try {
                message(envelope)
            } catch (e: SerializationException) {
                throw A2uiFormatException("a2ui_operations[$index]: ${e.message}")
            }
        }

    /**
     * A completed `render_a2ui` call's arguments as the one v1.0 message they amount to.
     *
     * v1.0 lets `createSurface` carry the opening components and data model inline, which is
     * exactly the shape of the call: `surfaceId`, `components`, an optional `data`. The catalog is
     * [defaultCatalogId] -- the call has none, and a `catalogId` an older adapter streamed anyway
     * is honoured through [catalogIds] because that is what upstream's middleware does with it.
     *
     * @throws A2uiFormatException when `surfaceId` or `components` is missing or mis-typed.
     */
    public fun rendered(arguments: JsonObject): AgentToRendererMessage {
        val surfaceId = arguments["surfaceId"]?.asString()
            ?: throw A2uiFormatException("render_a2ui: `surfaceId` is required.")
        val components = arguments["components"] as? JsonArray
            ?: throw A2uiFormatException("render_a2ui: `components` is required.")
        val catalogId = arguments["catalogId"]?.asString()
            ?.takeIf { it.isNotEmpty() && it != "basic" }
            ?.let(catalogIds)
            ?: defaultCatalogId
        val envelope = buildJsonObject {
            put("version", JsonPrimitive(A2ui.PROTOCOL_VERSION))
            put(
                "createSurface",
                buildJsonObject {
                    put("surfaceId", JsonPrimitive(surfaceId))
                    put("catalogId", JsonPrimitive(catalogId))
                    put("components", components)
                    (arguments["data"] as? JsonObject)?.let { put("dataModel", it) }
                },
            )
        }
        return A2uiJson.lenient.decodeFromJsonElement(AgentToRendererMessage.serializer(), envelope)
    }

    /** [envelope], a v0.9 one, rewritten as v1.0. */
    private fun upgrade(envelope: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in envelope) {
            when (key) {
                "version" -> put(key, JsonPrimitive(A2ui.PROTOCOL_VERSION))
                "createSurface" -> put(key, upgradeCreateSurface(value))
                "updateDataModel" -> put(key, upgradeUpdateDataModel(value))
                else -> put(key, value)
            }
        }
    }

    private fun upgradeCreateSurface(body: JsonElement): JsonElement {
        if (body !is JsonObject) return body
        return buildJsonObject {
            for ((key, value) in body) {
                when (key) {
                    "theme" -> Unit
                    "attachDataModel" -> put("sendDataModel", value)
                    "catalogId" -> put(key, value.asString()?.let { JsonPrimitive(catalogIds(it)) } ?: value)
                    else -> put(key, value)
                }
            }
        }
    }

    private fun upgradeUpdateDataModel(body: JsonElement): JsonElement {
        if (body !is JsonObject || "value" in body) return body
        return JsonObject(body + ("value" to JsonNull))
    }

    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    public companion object {
        /** The translation with every default: upstream's basic catalog becomes v1.0's. */
        public val Default: A2uiTranslation = A2uiTranslation()

        /**
         * Upstream's two spellings of the basic catalog become the v1.0 basic catalog's id; any
         * other id is returned as it came.
         */
        public fun upstreamCatalogIds(catalogId: String): String = when (catalogId) {
            AguiA2ui.UPSTREAM_BASIC_CATALOG_ID, AguiA2ui.V09_BASIC_CATALOG_ID -> AguiA2ui.V10_BASIC_CATALOG_ID
            else -> catalogId
        }
    }
}

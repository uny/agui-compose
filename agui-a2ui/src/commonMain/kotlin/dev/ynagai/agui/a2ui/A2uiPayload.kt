package dev.ynagai.agui.a2ui

import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.protocol.DeleteSurfaceMessage
import dev.ynagai.a2ui.core.protocol.UpdateComponentsMessage
import dev.ynagai.a2ui.core.protocol.UpdateDataModelMessage
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What one part of a transcript says about an A2UI surface.
 *
 * A surface reaches a client in three carriers, and the same surface commonly arrives in all
 * three during one run: an `ACTIVITY_SNAPSHOT` upstream's middleware synthesises, the streamed
 * arguments of a `render_a2ui` call, and a tool result whose content holds `a2ui_operations`. The
 * activity also carries the lifecycle *before* the surface exists -- the middleware sends
 * `{"status": "building"}` under the same `activityType` and `messageId` the surface will later
 * replace in place -- so a payload is not always a surface, and "not a surface" is not an error.
 */
public sealed interface A2uiPayload {
    /** A surface is being generated; nothing to draw yet but a placeholder. */
    public data class Building(
        /** The middleware's running estimate of the tokens streamed so far, when it sends one. */
        public val progressTokens: Int? = null,
    ) : A2uiPayload

    /** The previous attempt was rejected and the agent is generating again. */
    public data class Retrying(
        public val attempt: Int? = null,
        public val maxAttempts: Int? = null,
        /** What the middleware found wrong with the last attempt, verbatim. */
        public val errors: JsonElement? = null,
    ) : A2uiPayload

    /** Generation was given up on. The conversation goes on; this slot shows why. */
    public data class Failed(
        public val error: String,
        public val attempts: JsonElement? = null,
        public val maxAttempts: Int? = null,
    ) : A2uiPayload

    /** A surface, or several: the v1.0 messages that build it, in order. */
    public data class Surfaces(public val messages: List<AgentToRendererMessage>) : A2uiPayload {
        /** Every surface the messages name, in first-mention order. */
        public val surfaceIds: List<String> = messages.mapNotNull { it.surfaceIdOrNull() }.distinct()
    }

    /**
     * The carrier claimed to hold A2UI and did not.
     *
     * Kept rather than dropped, for the reason `agui-model` keeps a failed tool call: a renderer
     * that shows "malformed" is telling the truth, and one that shows nothing is hiding a bug in
     * whichever side produced the payload.
     */
    public data class Malformed(public val reason: String, public val content: JsonElement) : A2uiPayload

    public companion object {
        /**
         * The payload an `a2ui-surface` activity carries, or null when [part] is some other kind of
         * activity.
         *
         * Three content shapes are recognised, in this order: an `a2ui_operations` array; a
         * lifecycle object keyed by `status`; and a bare envelope with a `version`, which the A2A
         * bridge sends and the middleware does not. Anything else is [Malformed].
         */
        public fun of(part: ActivityPart, translation: A2uiTranslation = A2uiTranslation.Default): A2uiPayload? {
            if (part.activityType != AguiA2ui.ACTIVITY_TYPE) return null
            val content = part.content as? JsonObject
                ?: return Malformed("activity content is not an object", part.content)
            content[AguiA2ui.OPERATIONS_KEY]?.let { return operations(it, translation) }
            content["status"]?.let { return lifecycle(it, content) }
            if ("version" in content) return envelope(content, translation)
            return Malformed("no `${AguiA2ui.OPERATIONS_KEY}`, `status` or `version`", content)
        }

        /**
         * The payload a `render_a2ui` call's arguments carry: [Building] while they stream,
         * [Surfaces] once they have parsed. Null when [part] is not a render call, and also when
         * the call has been reported failed -- there is nothing to draw for a call that did not
         * happen, and the tool-call slot already says so.
         */
        public fun ofArguments(
            part: ToolCallPart,
            translation: A2uiTranslation = A2uiTranslation.Default,
            toolNames: Set<String> = setOf(AguiA2ui.RENDER_TOOL_NAME),
        ): A2uiPayload? {
            if (part.name !in toolNames) return null
            return when (part.status) {
                ToolCallStatus.STREAMING_ARGUMENTS -> Building()
                ToolCallStatus.FAILED -> null
                ToolCallStatus.AWAITING_RESULT, ToolCallStatus.COMPLETE -> {
                    val arguments = part.parsedArguments as? JsonObject
                        ?: return Malformed(
                            "render_a2ui arguments did not parse",
                            JsonPrimitive(part.arguments),
                        )
                    try {
                        Surfaces(listOf(translation.rendered(arguments)))
                    } catch (e: SerializationException) {
                        Malformed(e.message ?: "render_a2ui arguments rejected", arguments)
                    }
                }
            }
        }

        /**
         * The payload a tool result carries, or null when the result is absent or is not A2UI.
         *
         * Any tool's result qualifies, not only `render_a2ui`'s: upstream's LangGraph adapter
         * returns `a2ui_operations` from a `generate_a2ui` tool, and a fixed-schema agent returns
         * them from `search_flights`. What makes a result A2UI is its content, not the tool's
         * name. A result that is JSON-encoded twice -- a string holding the object -- is read
         * through one extra decode, because that is a shape upstream's own parser accepts.
         */
        public fun ofResult(part: ToolCallPart, translation: A2uiTranslation = A2uiTranslation.Default): A2uiPayload? {
            val text = part.result ?: return null
            val content = parseJson(text) ?: return null
            val operations = (content as? JsonObject)?.get(AguiA2ui.OPERATIONS_KEY) ?: return null
            return operations(operations, translation)
        }

        private fun operations(element: JsonElement, translation: A2uiTranslation): A2uiPayload {
            val array = element as? JsonArray
                ?: return Malformed("`${AguiA2ui.OPERATIONS_KEY}` is not an array", element)
            return try {
                Surfaces(translation.operations(array))
            } catch (e: SerializationException) {
                Malformed(e.message ?: "operations rejected", element)
            }
        }

        private fun envelope(content: JsonObject, translation: A2uiTranslation): A2uiPayload = try {
            Surfaces(listOf(translation.message(content)))
        } catch (e: SerializationException) {
            Malformed(e.message ?: "envelope rejected", content)
        }

        private fun lifecycle(status: JsonElement, content: JsonObject): A2uiPayload =
            when ((status as? JsonPrimitive)?.contentOrNull) {
                "building" -> Building(progressTokens = content.int("progressTokens"))
                "retrying" -> Retrying(
                    attempt = content.int("attempt"),
                    maxAttempts = content.int("maxAttempts"),
                    errors = content["errors"],
                )
                "failed" -> Failed(
                    error = content.string("error") ?: "A2UI generation failed",
                    attempts = content["attempts"],
                    maxAttempts = content.int("maxAttempts"),
                )
                else -> Malformed("unknown lifecycle status `$status`", content)
            }

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun parseJson(text: String): JsonElement? {
            val first = try {
                json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                return null
            }
            // Encoded twice: a JSON string whose content is the object. One more decode, and no
            // further -- upstream stops there too.
            if (first is JsonPrimitive && first.isString) {
                return try {
                    json.parseToJsonElement(first.content)
                } catch (_: SerializationException) {
                    null
                }
            }
            return first
        }
    }
}

/** The surface a message addresses, or null for the two function-call messages, which have none. */
public fun AgentToRendererMessage.surfaceIdOrNull(): String? = when (this) {
    is CreateSurfaceMessage -> surfaceId
    is UpdateComponentsMessage -> surfaceId
    is UpdateDataModelMessage -> surfaceId
    is DeleteSurfaceMessage -> surfaceId
    else -> null
}

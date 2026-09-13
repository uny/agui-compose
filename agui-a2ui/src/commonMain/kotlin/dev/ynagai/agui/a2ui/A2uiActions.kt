package dev.ynagai.agui.a2ui

import dev.ynagai.a2ui.core.protocol.ActionMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * An action the user took on a surface, in the shape upstream's middleware reads it from
 * `RunAgentInput.forwardedProps`: `{"a2uiAction": {"userAction": {...}}}`.
 *
 * The middleware lifts it into a synthetic assistant tool call named `log_a2ui_event` plus its
 * result, so the model sees the action as something that happened in the conversation. The five
 * fields are the A2UI `action` message's own, which is why this is a rename and not a mapping.
 * Merge it into whatever else the run forwards; this module does not own `forwardedProps`.
 */
public fun ActionMessage.toForwardedProps(): JsonObject = buildJsonObject {
    put(
        "a2uiAction",
        buildJsonObject {
            put(
                "userAction",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("surfaceId", JsonPrimitive(surfaceId))
                    put("sourceComponentId", JsonPrimitive(sourceComponentId))
                    put("context", context)
                    put("timestamp", JsonPrimitive(timestamp))
                },
            )
        },
    )
}

/**
 * The same action as a line of user text, for a stack that does not honour `forwardedProps`.
 *
 * Upstream's own Kotlin example moved to this form after finding that the ADK adapter, seeing the
 * middleware's synthetic tool pair as a call it never made, dropped the whole batch and the user
 * message with it. The model reads the action as prose, which is enough for it to know what to
 * draw next. The format is the example's, character for character, so a backend that learned to
 * read one reads the other.
 */
public fun ActionMessage.toUserText(): String = buildString {
    append("[A2UI Action] name=")
    append(name)
    append(", surfaceId=")
    append(surfaceId)
    append(", sourceComponentId=")
    append(sourceComponentId)
    append(", context=")
    append(context.toString())
}

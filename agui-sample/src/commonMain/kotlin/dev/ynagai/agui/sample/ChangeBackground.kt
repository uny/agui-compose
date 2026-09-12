package dev.ynagai.agui.sample

import com.agui.core.types.Tool
import com.agui.tools.AbstractToolExecutor
import com.agui.tools.ToolExecutionContext
import com.agui.tools.ToolExecutionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The one frontend tool the sample executes: the agent asks for a background, the window paints
 * it.
 *
 * The name and the argument are upstream's. `server-starter-all-features`' `/agentic_chat` answers
 * a turn that reads `tool` by calling `change_background` with a CSS gradient string, and answers
 * the tool's result with `background changed ✓` -- so this is the tool that lets the sample show
 * a frontend tool going round the whole loop against a server that needs no key. The argument is
 * CSS because that is what the server sends; what the window makes of it is [backgroundColors].
 *
 * The executor writes the request through [onChange] and returns a result that says it did. The
 * result is what the agent is told, and a tool that changes something in the UI has nothing to
 * hand back but the fact that it happened.
 */
internal class ChangeBackground(
    private val onChange: (background: String) -> Unit,
) : AbstractToolExecutor(
    Tool(
        name = "change_background",
        description = "Change the background of the chat window.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("background") {
                    put("type", "string")
                    put("description", "The background to paint: a colour or a CSS gradient.")
                }
            }
            put("required", buildJsonArray { add("background") })
        },
    ),
) {
    override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult {
        val arguments = Json.parseToJsonElement(context.toolCall.function.arguments).jsonObject
        // `contentOrNull`: a JSON `null` is not a background either.
        val background = arguments["background"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.failure("`background` is required")
        onChange(background)
        return ToolExecutionResult.success(buildJsonObject { put("changed", true) }, "Background changed")
    }
}

/**
 * The colours in a CSS background, as `0xAARRGGBB`, in the order written.
 *
 * Not a CSS parser. The sample paints a gradient of whatever `#rrggbb` (or `#rgb`) colours the
 * string names and ignores the rest -- the angle, the stops, a colour written as a name -- because
 * the point is that a string the agent sent reached the window, not fidelity to a stylesheet. A
 * string naming no colour paints nothing, and the list says so by being empty.
 */
internal fun backgroundColors(background: String): List<Long> =
    HEX_COLOR.findAll(background).map { match ->
        val hex = match.groupValues[1].let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        0xFF000000L or hex.toLong(16)
    }.toList()

private val HEX_COLOR = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b")

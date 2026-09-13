package dev.ynagai.agui.a2ui

import com.agui.core.types.Tool
import com.agui.tools.AbstractToolExecutor
import com.agui.tools.ToolExecutionContext
import com.agui.tools.ToolExecutionResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The `render_a2ui` tool as a frontend tool: declared to the agent, and answered by the client.
 *
 * Executing it draws nothing. The drawing is done from the transcript -- the call's arguments
 * are an [A2uiCarrier.ToolArguments] carrier the moment `TOOL_CALL_END` lands, whether or not
 * anything executes them -- so what this executor adds is the *answer*: a `TOOL_CALL_RESULT`
 * saying `{"status": "rendered"}`, which is the result upstream's middleware synthesises for the
 * same call, and which an agent that called the tool at the top level is waiting on before it
 * goes on. Registering it also declares the tool, which is how an agent learns it may call it.
 *
 * **Register it only for an agent that calls `render_a2ui` directly.** Upstream's LangGraph
 * adapter calls it from a subagent inside an outer `generate_a2ui` tool, closes it on the server,
 * and never sends a result on the wire; a client that answered that inner call would send a
 * result for a call the server never asked it to run. `AgentSession` executes what its registry
 * holds, so the decision is made by what is put in the registry -- which is why nothing here
 * registers itself.
 *
 * @param name the tool's name, for a middleware configured with a custom one.
 * @param translation what the arguments are checked with. The check is that they would draw:
 *   arguments that name no surface or carry no components are answered with a failure, the
 *   same way upstream's validator rejects them, so the agent hears that its call was empty.
 */
public class RenderA2UiTool(
    name: String = AguiA2ui.RENDER_TOOL_NAME,
    private val translation: A2uiTranslation = A2uiTranslation.Default,
) : AbstractToolExecutor(
    Tool(
        name = name,
        description = "Render a dynamic A2UI surface with structured parameters. " +
            "Follow the A2UI render tool usage guide provided in context.",
        parameters = PARAMETERS,
    ),
) {
    override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult {
        val arguments = try {
            Json.parseToJsonElement(context.toolCall.function.arguments) as? JsonObject
                ?: return ToolExecutionResult.failure("Arguments are not an object")
        } catch (e: SerializationException) {
            return ToolExecutionResult.failure("Invalid JSON arguments: ${e.message}")
        }
        return try {
            translation.rendered(arguments)
            ToolExecutionResult.success(buildJsonObject { put("status", "rendered") }, "A2UI surface rendered")
        } catch (e: SerializationException) {
            ToolExecutionResult.failure(e.message ?: "render_a2ui failed")
        }
    }

    private companion object {
        /**
         * Upstream's schema for the tool, verbatim. `catalogId` is deliberately absent: the
         * catalog is the host's choice, and a parameter would let the model name one the client
         * never registered.
         */
        val PARAMETERS: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("surfaceId") {
                    put("type", "string")
                    put("description", "Unique surface identifier.")
                }
                putJsonObject("components") {
                    put("type", "array")
                    put(
                        "description",
                        "A2UI component array (flat format). The root component must have id \"root\".",
                    )
                    putJsonObject("items") { put("type", "object") }
                }
                putJsonObject("data") {
                    put("type", "object")
                    put(
                        "description",
                        "Initial data model for the surface. Written to the root path. " +
                            "Use for pre-filling form values or providing data for components " +
                            "bound to data model paths.",
                    )
                }
            }
            put("required", buildJsonArray { add("surfaceId"); add("components") })
        }
    }
}

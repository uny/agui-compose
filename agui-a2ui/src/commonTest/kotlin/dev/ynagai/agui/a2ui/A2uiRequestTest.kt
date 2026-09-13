package dev.ynagai.agui.a2ui

import com.agui.core.types.Context
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The request side is a wire contract read by upstream's adapters, so these pin the strings and
 * the JSON types those adapters match on: the descriptions byte for byte, the flag as a JSON
 * boolean rather than the string `"true"`, and the schema value as an object with `catalogId` and
 * `components` at the top level.
 */
class A2uiRequestTest {
    private val catalog: CatalogDefinition = A2uiJson.strict.decodeFromString(
        CatalogDefinition.serializer(),
        """
        {
          "catalogId": "https://example.com/catalog.json",
          "protocolVersion": "1.0",
          "components": {
            "Card": { "type": "object", "properties": { "component": { "const": "Card" } } }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `forwardedProps carries injectA2UITool as a JSON true`() {
        val props = A2uiRequest(catalog).forwardedProps()
        assertEquals(JsonPrimitive(true), props["injectA2UITool"])
        assertTrue(props.getValue("injectA2UITool").jsonPrimitive.boolean)
    }

    @Test
    fun `a custom render tool name is the flag's value`() {
        val props = A2uiRequest(catalog, renderToolName = "draw").forwardedProps()
        assertEquals(JsonPrimitive("draw"), props["injectA2UITool"])
    }

    @Test
    fun `forwardedProps keeps what the caller carried and replaces the flag`() {
        val existing = buildJsonObject {
            put("injectA2UITool", false)
            put("a2uiAction", buildJsonObject { put("userAction", buildJsonObject { put("name", "book") }) })
        }
        val props = A2uiRequest(catalog).forwardedProps(existing)
        assertEquals(JsonPrimitive(true), props["injectA2UITool"])
        assertEquals(existing["a2uiAction"], props["a2uiAction"])
        assertEquals(2, props.size)
    }

    @Test
    fun `the schema entry's description is the middleware's byte for byte`() {
        val schema = A2uiRequest(catalog).context().first()
        assertEquals(
            "A2UI Component Schema \u2014 available components for generating UI surfaces. " +
                "Use these component names and properties when creating A2UI operations.",
            schema.description,
        )
    }

    @Test
    fun `the schema entry's value is an object with catalogId and components on top`() {
        val schema = A2uiRequest(catalog).context().first()
        val value = Json.parseToJsonElement(schema.value).jsonObject
        assertEquals("https://example.com/catalog.json", value.getValue("catalogId").jsonPrimitive.content)
        assertEquals(setOf("Card"), value.getValue("components").jsonObject.keys)
    }

    @Test
    fun `the guidelines entry names the render tool in its description and its text`() {
        val guide = A2uiRequest(catalog, renderToolName = "draw").context().last()
        assertEquals("A2UI render tool usage guide \u2014 how to call draw with valid arguments.", guide.description)
        assertTrue(guide.value.startsWith("## How to call draw\n"))
        assertTrue(guide.value.endsWith("only use real URLs or Icon components."))
        assertTrue("\"value\": \"\$50K\"" in guide.value)
        assertTrue("render_a2ui" !in guide.value)
    }

    @Test
    fun `guidelines can be left out`() {
        val context = A2uiRequest(catalog, guidelines = false).context()
        assertEquals(1, context.size)
        assertEquals(AguiA2ui.SCHEMA_CONTEXT_DESCRIPTION, context.single().description)
    }

    @Test
    fun `context keeps the caller's entries and replaces the ones it owns`() {
        val existing = listOf(
            Context(description = "Design guidelines", value = "Be brief."),
            Context(description = AguiA2ui.SCHEMA_CONTEXT_DESCRIPTION, value = "{}"),
            Context(description = AguiA2ui.guidelinesContextDescription("render_a2ui"), value = "stale"),
        )
        val context = A2uiRequest(catalog).context(existing)
        assertEquals(3, context.size)
        assertEquals(existing.first(), context.first())
        assertTrue(context.none { it.value == "{}" || it.value == "stale" })
    }

    @Test
    fun `the schema entry is what catalogContext builds`() {
        assertEquals(catalogContext(catalog), A2uiRequest(catalog).context().first())
    }

    @Test
    fun `an empty render tool name is refused rather than sent as an opt-out`() {
        assertFailsWith<IllegalArgumentException> { A2uiRequest(catalog, renderToolName = "") }
    }
}

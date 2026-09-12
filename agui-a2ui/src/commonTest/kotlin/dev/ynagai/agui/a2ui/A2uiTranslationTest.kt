package dev.ynagai.agui.a2ui

import dev.ynagai.a2ui.core.protocol.A2uiFormatException
import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.protocol.UpdateDataModelMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class A2uiTranslationTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a v0_9 createSurface becomes v1_0 with the catalog remapped and the theme dropped`() {
        val message = A2uiTranslation.Default.message(
            json(
                """{"version":"v0.9","createSurface":{"surfaceId":"s","catalogId":"${AguiA2ui.UPSTREAM_BASIC_CATALOG_ID}",
                   "theme":{"primaryColor":"#000"},"attachDataModel":true}}""",
            ),
        )
        val created = assertIs<CreateSurfaceMessage>(message)
        assertEquals("s", created.surfaceId)
        assertEquals(AguiA2ui.V10_BASIC_CATALOG_ID, created.catalogId)
        assertEquals(true, created.sendDataModel)
    }

    @Test
    fun `a custom catalog id passes through unchanged`() {
        val created = A2uiTranslation.Default.message(
            json("""{"version":"v0.9","createSurface":{"surfaceId":"s","catalogId":"https://example.com/c.json"}}"""),
        ) as CreateSurfaceMessage
        assertEquals("https://example.com/c.json", created.catalogId)
    }

    @Test
    fun `an updateDataModel without a value gets null, which v1_0 reads as delete`() {
        val message = A2uiTranslation.Default.message(
            json("""{"version":"v0.9","updateDataModel":{"surfaceId":"s","path":"/x"}}"""),
        )
        assertEquals(JsonNull, assertIs<UpdateDataModelMessage>(message).value)
    }

    @Test
    fun `a v1_0 envelope is taken as it is`() {
        val message = A2uiTranslation.Default.message(
            json("""{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"${AguiA2ui.UPSTREAM_BASIC_CATALOG_ID}"}}"""),
        )
        // No remap on a v1.0 envelope: an agent that speaks v1.0 named the catalog it meant.
        assertEquals(AguiA2ui.UPSTREAM_BASIC_CATALOG_ID, (message as CreateSurfaceMessage).catalogId)
    }

    @Test
    fun `an envelope without a version is refused`() {
        assertFailsWith<A2uiFormatException> {
            A2uiTranslation.Default.message(json("""{"createSurface":{"surfaceId":"s"}}"""))
        }
    }

    @Test
    fun `an unknown version is refused by a2ui-core, not here`() {
        assertFailsWith<A2uiFormatException> {
            A2uiTranslation.Default.message(json("""{"version":"v0.8","createSurface":{"surfaceId":"s"}}"""))
        }
    }

    @Test
    fun `render arguments become one createSurface bound to the default catalog`() {
        val translation = A2uiTranslation(defaultCatalogId = "https://example.com/mine.json")
        val created = translation.rendered(
            json("""{"surfaceId":"s","components":[{"id":"root","component":"Text","text":"hi"}],"data":{"k":1}}"""),
        ) as CreateSurfaceMessage
        assertEquals("https://example.com/mine.json", created.catalogId)
        assertEquals(1, created.components?.size)
        assertEquals("root", created.components?.single()?.id)
        assertEquals("hi", created.components?.single()?.properties?.get("text")?.jsonPrimitive?.content)
        assertEquals(1, created.dataModel?.get("k")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `render arguments naming the basic catalog by its short name take the default instead`() {
        val created = A2uiTranslation.Default.rendered(
            json("""{"surfaceId":"s","catalogId":"basic","components":[]}"""),
        ) as CreateSurfaceMessage
        assertEquals(AguiA2ui.V10_BASIC_CATALOG_ID, created.catalogId)
        assertNull(created.dataModel)
    }

    @Test
    fun `render arguments without components are refused`() {
        assertFailsWith<A2uiFormatException> {
            A2uiTranslation.Default.rendered(json("""{"surfaceId":"s"}"""))
        }
    }
}

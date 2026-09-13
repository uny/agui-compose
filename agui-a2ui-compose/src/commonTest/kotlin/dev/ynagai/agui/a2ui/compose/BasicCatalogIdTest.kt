package dev.ynagai.agui.a2ui.compose

import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.agui.a2ui.AguiA2ui
import kotlin.test.Test
import kotlin.test.assertEquals

class BasicCatalogIdTest {
    @Test
    fun `the id agui-a2ui remaps the basic catalog to is the one a2ui-compose registers`() {
        // `agui-a2ui` spells the id out because it cannot see `BasicCatalog`; this is where the
        // two meet, and where a bump of `a2ui-compose` that moved the catalog would show.
        assertEquals(BasicCatalog.id, AguiA2ui.V10_BASIC_CATALOG_ID)
    }
}

package dev.ynagai.agui.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiComponentScope
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.rememberAction
import dev.ynagai.a2ui.compose.rememberNumber
import dev.ynagai.a2ui.compose.rememberString
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.material3.Material3Components

/**
 * The catalog upstream's dojo registers for its dynamic A2UI agents, written down here in the
 * v1.0 form `a2ui-core` validates against.
 *
 * Upstream's `a2ui_dynamic_schema` and `a2ui_advanced` agents draw with four components: the
 * basic catalog's `Row`, and three cards the dojo's React frontend defines and registers under
 * `https://a2ui.org/demos/dojo/dynamic_catalog.json`. A catalog is the renderer's trust boundary
 * -- what the agent may ask for is what the client holds -- so a client that wants to draw what
 * those agents send holds this document and these renderers, and nothing an agent sends can add
 * to them.
 *
 * `Row` is declared here rather than borrowed: v1.0 resolves a component against the surface's
 * catalog and errors out on a miss, with no fallback to the other catalogs a renderer holds. Its
 * definition is the basic catalog's, with the `$ref`s the schema registry already resolves.
 */
internal object DojoCatalog {
    const val ID: String = "https://a2ui.org/demos/dojo/dynamic_catalog.json"

    private const val COMMON: String = "https://a2ui.org/specification/v1_0/common_types.json#/\$defs"

    val definition: CatalogDefinition by lazy {
        A2uiJson.strict.decodeFromString(
            CatalogDefinition.serializer(),
            """
            {
              "catalogId": "$ID",
              "protocolVersion": "1.0",
              "title": "AG-UI dojo dynamic catalog",
              "components": {
                "Row": {
                  "type": "object",
                  "properties": {
                    "component": { "const": "Row" },
                    "children": { "${'$'}ref": "$COMMON/ChildList" },
                    "justify": { "type": "string", "enum": ["center", "end", "spaceAround", "spaceBetween", "spaceEvenly", "start", "stretch"] },
                    "align": { "type": "string", "enum": ["start", "center", "end", "stretch"] },
                    "weight": { "type": "number" }
                  },
                  "required": ["component", "children"]
                },
                "HotelCard": {
                  "type": "object",
                  "properties": {
                    "component": { "const": "HotelCard" },
                    "name": { "${'$'}ref": "$COMMON/DynamicString" },
                    "location": { "${'$'}ref": "$COMMON/DynamicString" },
                    "rating": { "${'$'}ref": "$COMMON/DynamicNumber" },
                    "pricePerNight": { "${'$'}ref": "$COMMON/DynamicString" },
                    "amenities": { "${'$'}ref": "$COMMON/DynamicString" },
                    "action": { "${'$'}ref": "$COMMON/Action" },
                    "weight": { "type": "number" }
                  },
                  "required": ["component", "name"]
                },
                "ProductCard": {
                  "type": "object",
                  "properties": {
                    "component": { "const": "ProductCard" },
                    "name": { "${'$'}ref": "$COMMON/DynamicString" },
                    "price": { "${'$'}ref": "$COMMON/DynamicString" },
                    "rating": { "${'$'}ref": "$COMMON/DynamicNumber" },
                    "description": { "${'$'}ref": "$COMMON/DynamicString" },
                    "badge": { "${'$'}ref": "$COMMON/DynamicString" },
                    "action": { "${'$'}ref": "$COMMON/Action" },
                    "weight": { "type": "number" }
                  },
                  "required": ["component", "name"]
                },
                "TeamMemberCard": {
                  "type": "object",
                  "properties": {
                    "component": { "const": "TeamMemberCard" },
                    "name": { "${'$'}ref": "$COMMON/DynamicString" },
                    "role": { "${'$'}ref": "$COMMON/DynamicString" },
                    "department": { "${'$'}ref": "$COMMON/DynamicString" },
                    "email": { "${'$'}ref": "$COMMON/DynamicString" },
                    "avatarUrl": { "${'$'}ref": "$COMMON/DynamicString" },
                    "action": { "${'$'}ref": "$COMMON/Action" },
                    "weight": { "type": "number" }
                  },
                  "required": ["component", "name"]
                }
              }
            }
            """.trimIndent(),
        )
    }

    /**
     * The basic catalog's Material 3 renderers with the three cards on top. `Row` draws through
     * the basic `RowRenderer`, which reads the same properties this catalog declares for it.
     */
    val registry: ComponentRegistry = Material3Components.Basic.with(
        mapOf(
            "HotelCard" to ComponentRenderer { scope, modifier ->
                DojoCard(
                    scope = scope,
                    modifier = modifier,
                    title = scope.rememberString("name"),
                    subtitle = scope.rememberString("location"),
                    lines = listOfNotNull(
                        scope.rememberNumber("rating")?.let { "Rating ${stars(it)}" },
                        scope.rememberString("pricePerNight"),
                        scope.rememberString("amenities"),
                    ),
                    button = "Book",
                )
            },
            "ProductCard" to ComponentRenderer { scope, modifier ->
                DojoCard(
                    scope = scope,
                    modifier = modifier,
                    title = scope.rememberString("name"),
                    subtitle = scope.rememberString("badge"),
                    lines = listOfNotNull(
                        scope.rememberString("price"),
                        scope.rememberNumber("rating")?.let { "Rating ${stars(it)}" },
                        scope.rememberString("description"),
                    ),
                    button = "Select",
                )
            },
            "TeamMemberCard" to ComponentRenderer { scope, modifier ->
                DojoCard(
                    scope = scope,
                    modifier = modifier,
                    title = scope.rememberString("name"),
                    subtitle = scope.rememberString("role"),
                    lines = listOfNotNull(
                        scope.rememberString("department"),
                        scope.rememberString("email"),
                    ),
                    button = "Contact",
                )
            },
        ),
    )

    private fun stars(rating: Double): String {
        val full = rating.toInt().coerceIn(0, 5)
        return "★".repeat(full) + "☆".repeat(5 - full) + " (${rating})"
    }
}

/**
 * One card: a title, an optional subtitle, some lines, and a button that dispatches the
 * component's `action` -- through the scope, so the surface's `onMessage` hears it as an A2UI
 * `action` message with the component's context resolved against the data model.
 */
@Composable
private fun DojoCard(
    scope: A2uiComponentScope,
    modifier: Modifier,
    title: String?,
    subtitle: String?,
    lines: List<String>,
    button: String,
) {
    val action = scope.rememberAction("action")
    OutlinedCard(modifier = modifier.widthIn(min = 160.dp, max = 260.dp).padding(4.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title ?: "", style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(text = subtitle, style = MaterialTheme.typography.labelMedium)
            for (line in lines) Text(text = line, style = MaterialTheme.typography.bodySmall)
            if (action != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { scope.dispatch(action) }) { Text(button) }
                }
            }
        }
    }
}

package dev.ynagai.agui.material3

import androidx.compose.ui.unit.dp

/**
 * The spacing this module draws with.
 *
 * Internal and unconfigurable on purpose. Material 3 has a spacing scale but Compose Multiplatform
 * does not expose it as theme values the way it exposes colour and type, so a "configurable"
 * spacing here would mean this module inventing a token system that no other Material library
 * reads. An application that wants different metrics replaces the slot, which is one `copy` away
 * and is the seam that already exists.
 *
 * These are the values, stated once so a reviewer can see them together rather than finding them
 * spread across five files.
 */
internal object AguiSpacing {
    /** Between one message and the next. Halved, because each message contributes its own. */
    val betweenMessages = 4.dp

    /** Between the parts inside one message. */
    val betweenParts = 4.dp

    /** Inside a bubble, a tool-call surface, an attachment row. */
    val insideContainer = 12.dp

    /** Between a container's own children -- a label and the text beside it. */
    val insideRow = 8.dp
}

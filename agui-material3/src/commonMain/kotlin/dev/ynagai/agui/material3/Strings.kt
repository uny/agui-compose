package dev.ynagai.agui.material3

import dev.ynagai.agui.model.ToolCallStatus

/**
 * Every word this module puts on screen.
 *
 * **English, and not localised.** There is no multiplatform resource mechanism a library can use
 * without dragging its own choice of one into every consumer -- Compose's `stringResource` needs
 * the `components.resources` plugin applied to the *consuming* project -- so a library that
 * pretended to localise here would be shipping a mechanism, not a translation.
 *
 * Collected in one file rather than inlined at each call site so that the cost is visible: this is
 * the complete list of what an application in another language has to replace, and replacing it is
 * a `copy` of the slot that draws it.
 */
internal object AguiStrings {
    const val REASONING = "Reasoning"
    const val SHOW = "Show"
    const val HIDE = "Hide"
    const val SYSTEM = "System"
    const val DEVELOPER = "Developer"
    const val FAILED = "failed"

    fun toolCallStatus(status: ToolCallStatus): String = when (status) {
        // The two in-flight states are drawn as one word. They differ in whether the arguments
        // have finished arriving, which is a fact about the protocol rather than about the call:
        // to a reader the tool is running either way, and the progress indicator beside this says
        // so already.
        ToolCallStatus.STREAMING_ARGUMENTS -> "running"
        ToolCallStatus.AWAITING_RESULT -> "running"
        ToolCallStatus.COMPLETE -> "done"
        ToolCallStatus.FAILED -> FAILED
    }
}

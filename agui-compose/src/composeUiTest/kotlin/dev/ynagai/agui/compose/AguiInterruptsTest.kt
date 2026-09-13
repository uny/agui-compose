package dev.ynagai.agui.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.UiInterrupt
import dev.ynagai.agui.model.pendingInterrupts
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AguiInterruptsTest {

    @Test
    fun theDefaultDrawsThePromptOrTheReason() = runComposeUiTest {
        setContent {
            AguiInterrupts(
                listOf(
                    UiInterrupt(id = "i1", reason = "tool_call", message = "May I?"),
                    UiInterrupt(id = "i2", reason = "choose_slot"),
                ),
                onResume = {},
            )
        }
        onNodeWithText("May I?").assertIsDisplayed()
        onNodeWithText("choose_slot").assertIsDisplayed()
    }

    @Test
    fun theSlotIsReplaceable() = runComposeUiTest {
        val components = AguiComponents().copy(
            interrupt = { interrupt, _, modifier -> BasicText("ask:${interrupt.id}", modifier) },
        )
        setContent {
            CompositionLocalProvider(LocalAguiComponents provides components) {
                AguiInterrupts(listOf(UiInterrupt(id = "i1", reason = "r")), onResume = {})
            }
        }
        onNodeWithText("ask:i1").assertIsDisplayed()
    }

    @Test
    fun nothingPendingTakesNoSpace() = runComposeUiTest {
        setContent {
            AguiInterrupts(RunState.Running("t", "r").pendingInterrupts, onResume = {}, modifier = Modifier.testTag("none"))
        }
        assertEquals(0.dp, onNodeWithTag("none").getBoundsInRoot().height)
    }
}

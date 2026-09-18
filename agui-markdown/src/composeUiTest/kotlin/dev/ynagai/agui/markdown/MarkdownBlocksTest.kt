package dev.ynagai.agui.markdown

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/**
 * The block elements an agent writes, each asserted as the text it puts on screen.
 *
 * These are one level up from [InlineBuilderTest]: what is pinned is that each block *kind* is
 * recognised and drawn -- a heading is not shown with its `#`, a fence's content is one text
 * node without its fence lines, a table's cells are the cells and not the pipes. What they are
 * drawn *as* (size, bar, background) is not asserted; that is what the typography and colour
 * parameters are for, and a test of them would be a screenshot.
 */
@OptIn(ExperimentalTestApi::class)
class MarkdownBlocksTest {
    private fun rendered(text: String, assertions: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent { MarkdownAguiTextRenderer().Render(text = text, streaming = false, modifier = Modifier) }
        assertions()
    }

    @Test
    fun headingsDropTheirMarkers() = rendered("# Title\n\nSetext\n---\n\n### Third *one*") {
        onNodeWithText("Title").assertIsDisplayed()
        onNodeWithText("Setext").assertIsDisplayed()
        onNodeWithText("Third one").assertIsDisplayed()
        onNodeWithText("#", substring = true).assertDoesNotExist()
    }

    @Test
    fun listsDrawTheirItemsAndCountFromTheFirstNumber() = rendered("- one\n- two\n\n3. third\n3. fourth\n\n1) a\n1) b") {
        onNodeWithText("one").assertIsDisplayed()
        onNodeWithText("two").assertIsDisplayed()
        onNodeWithText("3.").assertIsDisplayed()
        onNodeWithText("4.").assertIsDisplayed()
        onNodeWithText("fourth").assertIsDisplayed()
        onNodeWithText("1)").assertIsDisplayed()
        onNodeWithText("2)").assertIsDisplayed()
    }

    @Test
    fun aNestedListIsDrawnInsideItsItem() = rendered("- outer\n  - inner\n- next") {
        onNodeWithText("outer").assertIsDisplayed()
        onNodeWithText("inner").assertIsDisplayed()
        onNodeWithText("next").assertIsDisplayed()
    }

    @Test
    fun aFenceIsOneBlockOfItsContent() = rendered("```kotlin\nval a = 1\n\nval b = 2\n```") {
        onNodeWithText("val a = 1\n\nval b = 2").assertIsDisplayed()
        onNodeWithText("```", substring = true).assertDoesNotExist()
        onNodeWithText("kotlin", substring = true).assertDoesNotExist()
    }

    @Test
    fun aFenceInsideAListItemKeepsItsContent() = rendered("- item\n\n  ```\n  code here\n  ```") {
        onNodeWithText("item").assertIsDisplayed()
        onNodeWithText("code here").assertIsDisplayed()
    }

    @Test
    fun anIndentedBlockLosesItsIndent() = rendered("Text\n\n    indented code") {
        onNodeWithText("indented code").assertIsDisplayed()
    }

    @Test
    fun aQuoteDrawsItsProse() = rendered("> quoted\n> more") {
        onNodeWithText("quoted more").assertIsDisplayed()
    }

    @Test
    fun anAlertDrawsItsTitleAndBody() = rendered("> [!WARNING]\n> careful") {
        onNodeWithText("Warning").assertIsDisplayed()
        onNodeWithText("careful").assertIsDisplayed()
        onNodeWithText("[!WARNING]", substring = true).assertDoesNotExist()
    }

    @Test
    fun aTableDrawsItsCells() = rendered("| a | b |\n|:--|--:|\n| 1 | **2** |") {
        onNodeWithText("a").assertIsDisplayed()
        onNodeWithText("b").assertIsDisplayed()
        onNodeWithText("1").assertIsDisplayed()
        onNodeWithText("2").assertIsDisplayed()
        onNodeWithText("|", substring = true).assertDoesNotExist()
        onNodeWithText("--", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRuleDrawsNoText() = rendered("above\n\n---\n\nbelow") {
        onNodeWithText("above").assertIsDisplayed()
        onNodeWithText("below").assertIsDisplayed()
        onNodeWithText("---", substring = true).assertDoesNotExist()
    }

    @Test
    fun aTaskListDrawsItsBoxesInFrontOfItsItems() = rendered("- [ ] todo\n- [x] done") {
        onNodeWithText("• [ ]").assertIsDisplayed()
        onNodeWithText("• [x]").assertIsDisplayed()
        onNodeWithText("todo").assertIsDisplayed()
        onNodeWithText("done").assertIsDisplayed()
    }

    @Test
    fun anIndentedBlockKeepsItsBlankLinesAndLosesItsTab() = rendered("    a\n\n\tb") {
        onNodeWithText("a\n\nb").assertIsDisplayed()
    }

    @Test
    fun aShortTableRowKeepsItsColumns() = rendered("| a | b |\n|---|---|\n| 1 |") {
        onNodeWithText("1").assertIsDisplayed()
        onNodeWithText("b").assertIsDisplayed()
    }
}

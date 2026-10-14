package me.d3sox.manida

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

internal fun ComposeTestRule.openLibraryPage(label: String) {
    val destination = (hasText(label) or hasContentDescription(label)) and hasClickAction()
    if (onAllNodes(destination).fetchSemanticsNodes().isEmpty()) {
        onNodeWithText("More").performClick()
        onNodeWithTag("library-more-pages").performScrollToNode(hasText(label))
        onNode(destination).performClick()
    } else onNode(destination).performClick()
}

internal fun ComposeTestRule.openFileAction(label: String) {
    if (onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
        onNodeWithText("File actions").performClick()
    }
    onNodeWithText(label).performScrollTo().performClick()
}

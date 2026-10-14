package io.github.d3sox.musicplayer.ui

import androidx.compose.ui.test.*

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openLibraryPage(label: String) {
    val destination = (hasText(label) or hasContentDescription(label)) and hasClickAction()
    if (onAllNodes(destination).fetchSemanticsNodes().isEmpty()) {
        onNodeWithText("More").performClick()
        onNodeWithTag("library-more-pages").performScrollToNode(hasText(label))
        onNode(destination).performClick()
    } else onNode(destination).performClick()
}

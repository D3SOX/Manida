package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.d3sox.musicplayer.history.ListeningStatistics
import io.github.d3sox.musicplayer.library.LibrarySort
import io.github.d3sox.musicplayer.library.Track
import io.github.d3sox.musicplayer.library.recentAt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun alphabetIndicator(text: String): String = text.trim().firstOrNull()?.uppercaseChar()?.let {
    if (it.isLetter()) it.toString() else "#"
} ?: "#"

internal expect fun formatScrollDate(epochMillis: Long): String

internal fun Track.scrollLabel(sort: LibrarySort, statistics: Map<String, ListeningStatistics>?): String = when (sort) {
    LibrarySort.TITLE -> alphabetIndicator(metadata.title)
    LibrarySort.ARTIST -> alphabetIndicator(metadata.artist)
    LibrarySort.ALBUM -> alphabetIndicator(metadata.album)
    LibrarySort.RECENT -> formatScrollDate(recentAt)
    LibrarySort.RATING -> "$rating ★"
    LibrarySort.PLAY_COUNT -> countLabel(statistics?.get(id)?.playCount ?: 0, "play")
    LibrarySort.LAST_PLAYED -> statistics?.get(id)?.lastPlayedAt?.let(::formatScrollDate) ?: "Never played"
}

@Composable
internal fun BoxScope.LibraryScrollbar(state: LazyListState, count: Int, description: String,
    headerItems: Int = 0, label: (Int) -> String) {
    FastScrollbar(count, (state.firstVisibleItemIndex - headerItems).coerceAtLeast(0),
        state.layoutInfo.visibleItemsInfo.size, state.canScrollForward || state.canScrollBackward,
        description, { state.scrollToItem(it + headerItems) }, label)
}

@Composable
internal fun BoxScope.LibraryScrollbar(state: LazyGridState, count: Int, description: String, label: (Int) -> String) {
    FastScrollbar(count, state.firstVisibleItemIndex, state.layoutInfo.visibleItemsInfo.size,
        state.canScrollForward || state.canScrollBackward, description, { state.scrollToItem(it) }, label)
}

@Composable
private fun BoxScope.FastScrollbar(count: Int, first: Int, visible: Int, scrollable: Boolean, description: String,
    scrollTo: suspend (Int) -> Unit, label: (Int) -> String) {
    if (count < 2 || !scrollable) return
    val scope = rememberCoroutineScope()
    val scroll by rememberUpdatedState(scrollTo)
    val describe by rememberUpdatedState(label)
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var target by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val thumb = with(LocalDensity.current) { 48.dp.toPx() }
    val travel = (height - thumb).coerceAtLeast(1f)
    val fraction = if (dragging) ((cursor - thumb / 2) / travel).coerceIn(0f, 1f)
        else (first.toFloat() / (count - visible.coerceAtLeast(1)).coerceAtLeast(1)).coerceIn(0f, 1f)
    fun move(y: Float) {
        cursor = y
        val index = (((y - thumb / 2) / travel).coerceIn(0f, 1f) * (count - 1)).roundToInt()
        if (index != target || !dragging) {
            target = index
            scrollJob?.cancel()
            scrollJob = scope.launch { scroll(index) }
        }
    }
    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(16.dp).onSizeChanged { height = it.height }
        .semantics {
            contentDescription = description
            stateDescription = describe(first.coerceIn(0, count - 1))
            progressBarRangeInfo = ProgressBarRangeInfo(first.toFloat(), 0f..(count - 1).toFloat())
            setProgress { value -> scope.launch { scroll(value.roundToInt().coerceIn(0, count - 1)) }; true }
        }.pointerInput(count) { detectTapGestures { move(it.y) } }.pointerInput(count) {
            detectVerticalDragGestures(onDragStart = { move(it.y); dragging = true },
                onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                onVerticalDrag = { change, _ -> change.consume(); move(change.position.y) })
        }) {
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight(.98f)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .12f), RoundedCornerShape(1.dp)))
        Box(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (fraction * travel).roundToInt()) }
            .width(if (dragging) 8.dp else 5.dp).height(48.dp)
            .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f), RoundedCornerShape(4.dp)))
    }
    if (dragging) Surface(Modifier.align(Alignment.TopEnd).padding(end = 24.dp)
        .offset { IntOffset(0, (fraction * travel).roundToInt()) }, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(describe(target.coerceIn(0, count - 1)), Modifier.testTag("fast-scroll-label").padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleMedium)
    }
}

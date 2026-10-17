package me.d3sox.nevumi.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import sh.calvin.reorderable.DragGestureDetector

/** Keeps upcoming songs below their section heading, before reordering sees the gesture. */
internal class QueueDragBoundary(private val rowTop: () -> Float, private val minimumTop: () -> Float) : DragGestureDetector {
    var atBoundary = false
        private set

    override suspend fun PointerInputScope.detect(onDragStart: (Offset) -> Unit, onDragEnd: () -> Unit,
        onDragCancel: () -> Unit, onDrag: (PointerInputChange, Offset) -> Unit) {
        var pointerTop = 0f
        var appliedTop = 0f
        detectDragGestures(onDragStart = {
            atBoundary = false
            pointerTop = rowTop()
            appliedTop = pointerTop
            onDragStart(it)
        }, onDragEnd = onDragEnd, onDragCancel = { atBoundary = false; onDragCancel() }, onDrag = { change, amount ->
            pointerTop += amount.y
            val minimum = minimumTop()
            atBoundary = pointerTop <= minimum
            val top = pointerTop.coerceAtLeast(minimum)
            onDrag(change, Offset(amount.x, top - appliedTop))
            appliedTop = top
        })
    }
}

package io.github.voreulch.honoradvanced.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange

/**
 * Drag-gesture detector that fires onDragStart on the initial DOWN (no
 * touch-slop wait), matching KernelSU's inspectDragGestures semantics.
 * Standard detectDragGestures waits for touch slop, so a plain TAP never
 * triggers it — which broke FloatingBottomBar's tap-to-switch behaviour.
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (PointerInputChange) -> Unit = { },
    onDragEnd: (PointerInputChange) -> Unit = { },
    onDragCancel: () -> Unit = {},
    onDrag: (PointerInputChange, Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down)
        var drag: PointerInputChange? = null
        do {
            val event = awaitPointerEvent()
            drag = event.changes.firstOrNull()
            if (drag != null) {
                val change = drag.positionChange()
                if (change != Offset.Zero) onDrag(drag, change)
            }
        } while (drag != null && drag.pressed)
        if (drag != null) onDragEnd(drag) else onDragCancel()
    }
}

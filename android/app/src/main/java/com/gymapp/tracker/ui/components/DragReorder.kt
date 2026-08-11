package com.gymapp.tracker.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A vertical list of items the user can reorder by dragging a handle.
 *
 * Built for a handful of dashboard cards, not a long list — it lays out every
 * item eagerly (no `LazyColumn`) so drag math stays simple: each item reports
 * its own pixel height via [Modifier.onGloballyPositioned], and crossing half
 * of a neighbour's height swaps the two in [order].
 *
 * The drag target is [DragHandle] specifically, so ordinary taps and clicks
 * inside [itemContent] (buttons, exercise rows, …) are completely unaffected —
 * dragging only starts from the handle icon itself.
 */
@Composable
fun <T> DraggableSectionList(
    items: List<T>,
    key: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, dragHandle: @Composable () -> Unit) -> Unit,
) {
    val itemHeights = remember { mutableStateMapOf<String, Float>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Local copy so a swap is visible immediately; committed via onReorder.
    var order by remember(items.map(key)) { mutableStateOf(items) }

    Column(modifier = modifier) {
        order.forEach { item ->
            val itemKey = key(item)
            val isDragging = itemKey == draggingKey

            Column(
                Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .alpha(if (isDragging) 0.94f else 1f)
                    .onGloballyPositioned { coordinates ->
                        itemHeights[itemKey] = coordinates.size.height.toFloat()
                    },
            ) {
                itemContent(item) {
                    DragHandle(
                        onDragStart = {
                            draggingKey = itemKey
                            dragOffset = 0f
                        },
                        onDrag = { delta ->
                            dragOffset += delta
                            val draggedIndex = order.indexOf(item)
                            val draggedHeight = itemHeights[itemKey] ?: return@DragHandle

                            if (dragOffset > 0) {
                                val nextIndex = draggedIndex + 1
                                val nextItem = order.getOrNull(nextIndex) ?: return@DragHandle
                                val nextHeight = itemHeights[key(nextItem)] ?: return@DragHandle
                                if (dragOffset > nextHeight / 2) {
                                    order = order.toMutableList().apply {
                                        removeAt(draggedIndex)
                                        add(nextIndex, item)
                                    }
                                    dragOffset -= nextHeight
                                }
                            } else if (dragOffset < 0) {
                                val prevIndex = draggedIndex - 1
                                val prevItem = order.getOrNull(prevIndex) ?: return@DragHandle
                                val prevHeight = itemHeights[key(prevItem)] ?: return@DragHandle
                                if (-dragOffset > prevHeight / 2) {
                                    order = order.toMutableList().apply {
                                        removeAt(draggedIndex)
                                        add(prevIndex, item)
                                    }
                                    dragOffset += prevHeight
                                }
                            }
                            // draggedHeight participates only via the swap threshold
                            // above; referencing it keeps the height map populated
                            // even for a single-item list.
                            draggedHeight
                        },
                        onDragEnd = {
                            draggingKey = null
                            dragOffset = 0f
                            onReorder(order)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DragHandle(
    onDragStart: () -> Unit,
    onDrag: (deltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Icon(
        Icons.Default.DragHandle,
        contentDescription = "Ziehen zum Verschieben",
        tint = LocalContentColor.current.copy(alpha = 0.45f),
        modifier = Modifier
            .size(22.dp)
            .padding(2.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    },
                )
            },
    )
}

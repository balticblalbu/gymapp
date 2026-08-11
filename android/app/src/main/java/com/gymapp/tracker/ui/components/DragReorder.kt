package com.gymapp.tracker.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex

/**
 * A vertical list of items the user can reorder by pressing and holding
 * anywhere on an item, then dragging it up or down.
 *
 * Built for a handful of cards, not a long list — it lays out every item
 * eagerly (no `LazyColumn`) so drag math stays simple: each item reports its
 * own pixel height via [Modifier.onGloballyPositioned], and crossing half of
 * a neighbour's height swaps the two in [order].
 *
 * A long-press gesture ([detectDragGesturesAfterLongPress]) is used instead
 * of a plain drag so ordinary taps, scrolls and clicks inside [itemContent]
 * (buttons, text fields, exercise rows, …) stay completely unaffected — a
 * quick tap never starts a drag, only a press held past the long-press
 * timeout does.
 */
@Composable
fun <T> DraggableSectionList(
    items: List<T>,
    key: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T) -> Unit,
) {
    val itemHeights = remember { mutableStateMapOf<String, Float>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
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
                    }
                    .pointerInput(itemKey) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggingKey = itemKey
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                draggingKey = null
                                dragOffset = 0f
                                onReorder(order)
                            },
                            onDragCancel = {
                                draggingKey = null
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                val draggedIndex = order.indexOf(item)

                                if (dragOffset > 0) {
                                    val nextIndex = draggedIndex + 1
                                    val nextItem = order.getOrNull(nextIndex)
                                    val nextHeight = nextItem?.let { itemHeights[key(it)] }
                                    if (nextItem != null && nextHeight != null && dragOffset > nextHeight / 2) {
                                        order = order.toMutableList().apply {
                                            removeAt(draggedIndex)
                                            add(nextIndex, item)
                                        }
                                        dragOffset -= nextHeight
                                    }
                                } else if (dragOffset < 0) {
                                    val prevIndex = draggedIndex - 1
                                    val prevItem = order.getOrNull(prevIndex)
                                    val prevHeight = prevItem?.let { itemHeights[key(it)] }
                                    if (prevItem != null && prevHeight != null && -dragOffset > prevHeight / 2) {
                                        order = order.toMutableList().apply {
                                            removeAt(draggedIndex)
                                            add(prevIndex, item)
                                        }
                                        dragOffset += prevHeight
                                    }
                                }
                            },
                        )
                    },
            ) {
                itemContent(item)
            }
        }
    }
}

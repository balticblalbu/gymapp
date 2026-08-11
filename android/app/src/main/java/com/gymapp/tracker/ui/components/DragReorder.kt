package com.gymapp.tracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as keyedComposable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A vertical list of items the user can reorder by pressing and holding
 * anywhere on an item — not a dedicated handle — then dragging it up or down.
 *
 * Built for a handful of cards, not a long list — it lays out every item
 * eagerly (no `LazyColumn`) so drag math stays simple: each item reports its
 * own pixel height via [Modifier.onGloballyPositioned], and crossing half of
 * a neighbour's height swaps the two in [order].
 *
 * The long-press detector runs on [PointerEventPass.Initial], i.e. it sees
 * every touch before any child (a button, a clickable row, a text field)
 * gets a chance to claim it. A quick tap is never intercepted — the
 * detector only starts consuming events once the long-press timeout has
 * actually elapsed — so ordinary clicks inside [itemContent] keep working
 * everywhere on the card, and holding anywhere on the card starts a drag.
 * On release the card springs from wherever it was let go back to its
 * settled position instead of snapping there instantly.
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
    // Actively being finger-dragged right now (drives the raw, 1:1 offset).
    var interactingKey by remember { mutableStateOf<String?>(null) }
    // Dragging OR still springing back into place — drives scale/shadow.
    var elevatedKey by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Local copy so a swap is visible immediately; committed via onReorder.
    var order by remember(items.map(key)) { mutableStateOf(items) }

    Column(modifier = modifier) {
        order.forEach { item ->
            val itemKey = key(item)
            keyedComposable(itemKey) {
                val isDragging = itemKey == interactingKey
                val isElevated = itemKey == elevatedKey
                val scale by animateFloatAsState(if (isElevated) 0.94f else 1f, label = "dragScale")
                val elevation by animateDpAsState(if (isElevated) 14.dp else 0.dp, label = "dragElevation")
                var rawOffset by remember { mutableFloatStateOf(0f) }
                val springOffset = remember { Animatable(0f) }

                Column(
                    Modifier
                        .zIndex(if (isElevated) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) rawOffset else springOffset.value
                            scaleX = scale
                            scaleY = scale
                            shadowElevation = elevation.toPx()
                            shape = RoundedCornerShape(20.dp)
                            clip = false
                        }
                        .onGloballyPositioned { coordinates ->
                            itemHeights[itemKey] = coordinates.size.height.toFloat()
                        }
                        .pointerInput(itemKey) {
                            detectLongPressDragGestures(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    rawOffset = 0f
                                    interactingKey = itemKey
                                    elevatedKey = itemKey
                                },
                                onDrag = { delta ->
                                    rawOffset += delta.y
                                    val draggedIndex = order.indexOf(item)

                                    if (rawOffset > 0) {
                                        val nextIndex = draggedIndex + 1
                                        val nextItem = order.getOrNull(nextIndex)
                                        val nextHeight = nextItem?.let { itemHeights[key(it)] }
                                        if (nextItem != null && nextHeight != null && rawOffset > nextHeight / 2) {
                                            order = order.toMutableList().apply {
                                                removeAt(draggedIndex)
                                                add(nextIndex, item)
                                            }
                                            rawOffset -= nextHeight
                                        }
                                    } else if (rawOffset < 0) {
                                        val prevIndex = draggedIndex - 1
                                        val prevItem = order.getOrNull(prevIndex)
                                        val prevHeight = prevItem?.let { itemHeights[key(it)] }
                                        if (prevItem != null && prevHeight != null && -rawOffset > prevHeight / 2) {
                                            order = order.toMutableList().apply {
                                                removeAt(draggedIndex)
                                                add(prevIndex, item)
                                            }
                                            rawOffset += prevHeight
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val settleFrom = rawOffset
                                    interactingKey = null
                                    onReorder(order)
                                    scope.launch {
                                        springOffset.snapTo(settleFrom)
                                        springOffset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                        if (elevatedKey == itemKey) elevatedKey = null
                                    }
                                },
                                onDragCancel = {
                                    val settleFrom = rawOffset
                                    interactingKey = null
                                    scope.launch {
                                        springOffset.snapTo(settleFrom)
                                        springOffset.animateTo(0f, tween(150))
                                        if (elevatedKey == itemKey) elevatedKey = null
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
}

/**
 * Long-press-to-drag that wins over child click/tap handlers regardless of
 * where on the item the press lands.
 *
 * Runs on [PointerEventPass.Initial] so it observes every touch before
 * children see it. While the press is younger than the long-press timeout
 * nothing is consumed, so a plain tap reaches its child (a button, a
 * clickable row) completely unaffected. Once the timeout elapses without
 * the pointer lifting or moving past touch slop, this becomes the winning
 * gesture: it starts consuming events, which cancels whatever press state
 * a child gesture (e.g. `clickable`) had built up, and drives the drag.
 *
 * The callbacks are plain (non-suspend) on purpose: `awaitPointerEventScope`
 * is a restricted-suspension scope, so only its own await-functions may be
 * suspended on from inside here — any animation the caller wants to run off
 * the end of a drag has to be launched in its own coroutine scope instead.
 */
private suspend fun PointerInputScope.detectLongPressDragGestures(
    onDragStart: () -> Unit,
    onDrag: (dragAmount: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val touchSlop = viewConfiguration.touchSlop

        val releasedOrMovedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!change.pressed) return@withTimeoutOrNull true
                if ((change.position - down.position).getDistance() > touchSlop) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") true
        }

        // Timing out (result == null) means the pointer was still down and
        // roughly stationary for the whole timeout — that's the long press.
        if (releasedOrMovedEarly == null) {
            onDragStart()
            var lastPosition = down.position
            var endedNormally = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) {
                    endedNormally = true
                    break
                }
                val delta = change.position - lastPosition
                lastPosition = change.position
                onDrag(delta)
            }
            if (endedNormally) onDragEnd() else onDragCancel()
        }
    }
}

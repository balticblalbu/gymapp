package com.gymapp.tracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects a scrolling list to the [DraggableSectionList] inside it, so a card
 * held against an edge pulls the list along instead of running off it.
 *
 * The list's real bounds have to be measured rather than assumed: the visible
 * area is neither the full screen nor the full content — status bar, bottom
 * navigation and insets all cut into it, and a card judged against the wrong
 * bounds gets clipped away before the auto-scroll ever starts.
 *
 * Apply [dragAutoScrollBounds] to the list and hand the same instance to
 * [DraggableSectionList].
 */
@Stable
class DragAutoScroller internal constructor(internal val state: ScrollableState) {
    internal var topInRoot by mutableFloatStateOf(0f)
    internal var height by mutableFloatStateOf(0f)
}

@Composable
fun rememberDragAutoScroller(state: ScrollableState): DragAutoScroller =
    remember(state) { DragAutoScroller(state) }

/** Measures the list's visible area for [DragAutoScroller]. */
fun Modifier.dragAutoScrollBounds(scroller: DragAutoScroller): Modifier =
    onGloballyPositioned { coordinates ->
        scroller.topInRoot = coordinates.positionInRoot().y
        scroller.height = coordinates.size.height.toFloat()
    }

/**
 * A vertical list of cards the user reorders by pressing and holding anywhere
 * on a card — no dedicated handle — and dragging it up or down.
 *
 * Three details matter for the drag to feel solid under the finger:
 *
 * 1. **The touch target never moves.** The gesture lives on an outer box that
 *    stays put; only an inner box is visually translated. If the pointer input
 *    sat inside the moving layer, every translation would shift the reported
 *    finger position too, and the card would oscillate instead of tracking the
 *    finger.
 * 2. **The list is not reordered mid-drag.** [order] changes only on release.
 *    While dragging, the dragged card follows the finger 1:1 and its
 *    neighbours slide aside to open a gap. Reordering during the gesture would
 *    move the dragged card's node in the layout and tear down the very
 *    gesture that is driving it.
 * 3. **The card stays inside the list.** Its travel is clamped to the visible
 *    area, so it can never be pushed under a neighbouring bar and vanish.
 *
 * The long-press detector runs on [PointerEventPass.Initial], so it sees each
 * touch before any child does — but consumes nothing until the long-press
 * timeout has elapsed. A quick tap therefore still reaches buttons and
 * clickable rows inside [itemContent] unchanged.
 *
 * Pass a [scroller] to let a card dragged against the top or bottom edge pull
 * the page along with it; without one, a card only moves as far as the screen
 * currently shows.
 */
@Composable
fun <T> DraggableSectionList(
    items: List<T>,
    key: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    scroller: DragAutoScroller? = null,
    itemContent: @Composable (item: T) -> Unit,
) {
    var order by remember(items.map(key)) { mutableStateOf(items) }
    val heights = remember { mutableStateMapOf<String, Float>() }
    val topsInRoot = remember { mutableStateMapOf<String, Float>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val cardShape = RoundedCornerShape(20.dp)

    val density = LocalDensity.current
    val edgeZone = with(density) { 96.dp.toPx() }
    val maxScrollStep = with(density) { 11.dp.toPx() }

    // Keeps the dragged card fully inside the list's visible area, however far
    // the finger travels — at the ends of the list there is nothing left to
    // scroll, and an unclamped card would simply slide out of sight.
    fun clampToViewport(offset: Float, itemKey: String): Float {
        val bounds = scroller ?: return offset
        if (bounds.height <= 0f) return offset
        val top = topsInRoot[itemKey] ?: return offset
        val lowest = bounds.topInRoot - top
        val highest = bounds.topInRoot + bounds.height - (heights[itemKey] ?: 0f) - top
        return if (lowest <= highest) offset.coerceIn(lowest, highest) else offset
    }

    // Auto-scroll while a card is held against an edge. Whatever the list
    // actually scrolls is added back onto the drag offset, so the card stays
    // pinned under the finger and keeps advancing through the list.
    LaunchedEffect(draggingKey, scroller) {
        val bounds = scroller ?: return@LaunchedEffect
        if (draggingKey == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val dragged = draggingKey ?: break
            val top = topsInRoot[dragged] ?: break
            val centre = top + (heights[dragged] ?: 0f) / 2f + dragOffset
            val viewTop = bounds.topInRoot
            val viewBottom = viewTop + bounds.height
            val step = when {
                centre < viewTop + edgeZone -> -((viewTop + edgeZone - centre) / edgeZone) * maxScrollStep
                centre > viewBottom - edgeZone -> ((centre - (viewBottom - edgeZone)) / edgeZone) * maxScrollStep
                else -> 0f
            }
            if (step != 0f) {
                dragOffset = clampToViewport(
                    dragOffset + bounds.state.scrollBy(step.coerceIn(-maxScrollStep, maxScrollStep)),
                    dragged,
                )
            }
        }
    }

    val heightList = order.map { heights[key(it)] ?: 0f }
    val fromIndex = draggingKey?.let { dragged -> order.indexOfFirst { key(it) == dragged } }
        ?.takeIf { it >= 0 }
    val toIndex = fromIndex?.let { targetIndexFor(it, dragOffset, heightList) }

    Column(modifier) {
        order.forEachIndexed { index, item ->
            val itemKey = key(item)
            key(itemKey) {
                val isDragging = itemKey == draggingKey

                // Where this card sits while a drag is in progress: the dragged
                // one follows the finger, the ones it has passed step aside by
                // exactly its height to open the gap it will drop into.
                val gap = when {
                    fromIndex == null || toIndex == null -> 0f
                    index == fromIndex -> 0f
                    index in (fromIndex + 1)..toIndex -> -heightList[fromIndex]
                    index in toIndex..(fromIndex - 1) -> heightList[fromIndex]
                    else -> 0f
                }

                val shift = remember { Animatable(0f) }
                LaunchedEffect(gap, draggingKey) {
                    // Snapping (not animating) back to zero once the drag ends
                    // avoids a double-move: the commit already changed this
                    // card's real position by the same amount.
                    if (draggingKey == null) shift.snapTo(0f) else shift.animateTo(gap, tween(160))
                }

                val scale by animateFloatAsState(if (isDragging) 0.96f else 1f, label = "dragScale")
                val lift by animateDpAsState(if (isDragging) 12.dp else 0.dp, label = "dragLift")

                Box(
                    Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .onGloballyPositioned {
                            heights[itemKey] = it.size.height.toFloat()
                            topsInRoot[itemKey] = it.positionInRoot().y
                        }
                        .pointerInput(itemKey) {
                            detectLongPressDrag(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragOffset = 0f
                                    draggingKey = itemKey
                                },
                                onDrag = { deltaY ->
                                    dragOffset = clampToViewport(dragOffset + deltaY, itemKey)
                                },
                                onDragEnd = {
                                    val from = order.indexOfFirst { key(it) == itemKey }
                                    val to = targetIndexFor(
                                        from,
                                        dragOffset,
                                        order.map { heights[key(it)] ?: 0f },
                                    )
                                    draggingKey = null
                                    dragOffset = 0f
                                    if (from >= 0 && to != from) {
                                        order = order.toMutableList().apply { add(to, removeAt(from)) }
                                        onReorder(order)
                                    }
                                },
                                onDragCancel = {
                                    draggingKey = null
                                    dragOffset = 0f
                                },
                            )
                        },
                ) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffset else shift.value
                                scaleX = scale
                                scaleY = scale
                            }
                            // Accent cards are translucent by design, so a lifted
                            // card would show whatever it floats over. An opaque
                            // backdrop while dragging keeps it readable.
                            .then(
                                if (lift > 0.dp) {
                                    Modifier
                                        .shadow(lift, cardShape, clip = false)
                                        .background(MaterialTheme.colorScheme.background, cardShape)
                                } else {
                                    Modifier
                                }
                            ),
                    ) {
                        itemContent(item)
                    }
                }
            }
        }
    }
}

/**
 * Which slot a card dragged [offset] pixels from [from] would land in.
 *
 * Walks outward one neighbour at a time, taking each slot only once the drag
 * has covered half of that neighbour — so a card settles where it visually
 * overlaps the most, and neighbours of different heights each need their own
 * fair share of travel.
 */
private fun targetIndexFor(from: Int, offset: Float, heights: List<Float>): Int {
    if (from !in heights.indices) return from
    var target = from
    var covered = 0f
    if (offset > 0f) {
        var i = from + 1
        while (i < heights.size && offset > covered + heights[i] / 2f) {
            covered += heights[i]
            target = i
            i++
        }
    } else if (offset < 0f) {
        var i = from - 1
        while (i >= 0 && -offset > covered + heights[i] / 2f) {
            covered += heights[i]
            target = i
            i--
        }
    }
    return target
}

/**
 * Long-press-to-drag that wins over child click handlers wherever the press
 * lands, without stealing ordinary taps.
 *
 * Observes on [PointerEventPass.Initial] but consumes nothing until the
 * long-press timeout has passed with the pointer still down and inside touch
 * slop. Only then does it start consuming, which cancels any press state a
 * child (`clickable`, a button) had built up, and drives the drag from there.
 *
 * Callbacks are deliberately non-suspending: `awaitPointerEventScope` is a
 * restricted suspension scope, so anything the caller wants to await has to
 * run in its own coroutine scope instead.
 */
private suspend fun PointerInputScope.detectLongPressDrag(
    onDragStart: () -> Unit,
    onDrag: (deltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val touchSlop = viewConfiguration.touchSlop

        val settledEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!change.pressed) return@withTimeoutOrNull true
                if ((change.position - down.position).getDistance() > touchSlop) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") true
        }

        // A timeout (null) means the finger stayed down and roughly still for
        // the full duration — that is the long press.
        if (settledEarly == null) {
            onDragStart()
            var lastY = down.position.y
            var lifted = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) {
                    lifted = true
                    break
                }
                onDrag(change.position.y - lastY)
                lastY = change.position.y
            }
            if (lifted) onDragEnd() else onDragCancel()
        }
    }
}

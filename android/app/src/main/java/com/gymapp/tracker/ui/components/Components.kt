package com.gymapp.tracker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gymapp.tracker.core.Fmt
import com.gymapp.tracker.ui.theme.AppTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Rounded surface card – the basic building block of every screen. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (accent) Brush.linearGradient(
                    listOf(colors.primary.copy(alpha = 0.16f), colors.primary.copy(alpha = 0.04f)),
                ) else Brush.linearGradient(listOf(colors.surface, colors.surface)),
            )
            .border(
                1.dp,
                if (accent) colors.primary.copy(alpha = 0.28f) else colors.outline,
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Compact number tile used in the dashboard grid. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Coloured percentage badge: green up, red down, neutral when unknown. */
@Composable
fun TrendPill(value: Double?, modifier: Modifier = Modifier, filled: Boolean = false) {
    val colors = AppTheme.colors
    val positive = (value ?: 0.0) >= 0
    val tint = when {
        value == null -> MaterialTheme.colorScheme.onSurfaceVariant
        positive -> colors.positive
        else -> colors.negative
    }
    val background = when {
        filled && value != null -> MaterialTheme.colorScheme.primary
        else -> tint.copy(alpha = 0.12f)
    }
    val foreground = if (filled && value != null) MaterialTheme.colorScheme.onPrimary else tint

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(Fmt.percent(value), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = foreground)
    }
}

/** Muscle group row: name, proportional bar, percentage. */
@Composable
fun MuscleGroupRow(name: String, fraction: Float, changePercent: Double?) {
    val colors = AppTheme.colors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "muscle-bar")
    val negative = (changePercent ?: 0.0) < 0

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(84.dp), maxLines = 1)
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (negative) Brush.horizontalGradient(listOf(colors.negative.copy(alpha = 0.7f), colors.negative))
                        else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), MaterialTheme.colorScheme.primary)),
                    ),
            )
        }
        Text(
            Fmt.percent(changePercent),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            color = when {
                changePercent == null -> MaterialTheme.colorScheme.onSurfaceVariant
                changePercent < 0 -> colors.negative
                else -> colors.positive
            },
            modifier = Modifier.width(62.dp).padding(start = 8.dp),
        )
    }
}

data class ChartPoint(val label: String, val value: Double, val detail: String = "")

/**
 * Interactive line chart drawn on a Canvas.
 *
 * Deliberately not a third party library: the requirements are simple (one
 * series, tap to inspect) and a hand drawn chart keeps full control over
 * theming and adds no dependency that could break on the next Compose update.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 150.dp,
    valueFormatter: (Double) -> String = { Fmt.number(it, 1) },
) {
    if (points.size < 2) {
        EmptyChartHint(modifier, height)
        return
    }

    val colors = AppTheme.colors
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val lineColor = colors.chartLine
    val gridColor = colors.chartGrid
    val fillTop = colors.chartFillTop
    val fillBottom = colors.chartFillBottom

    val minValue = points.minOf { it.value }
    val maxValue = points.maxOf { it.value }
    val span = (maxValue - minValue).takeIf { it > 0.0001 } ?: (maxValue.takeIf { it > 0 } ?: 1.0)
    val low = if (maxValue == minValue) minValue - span * 0.5 else minValue - span * 0.12
    val high = if (maxValue == minValue) maxValue + span * 0.5 else maxValue + span * 0.12

    Column(modifier) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        val step = size.width / (points.size - 1).coerceAtLeast(1)
                        val index = (offset.x / step).roundToInt().coerceIn(0, points.lastIndex)
                        selected = if (selected == index) null else index
                    }
                },
        ) {
            val width = size.width
            val chartHeight = size.height
            val stepX = width / (points.size - 1)
            fun yFor(value: Double): Float =
                (chartHeight - ((value - low) / (high - low)).toFloat() * chartHeight).coerceIn(0f, chartHeight)

            // Horizontal grid
            for (i in 0..3) {
                val y = chartHeight * i / 3f
                drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
            }

            val linePath = Path()
            val fillPath = Path()
            points.forEachIndexed { index, point ->
                val x = stepX * index
                val y = yFor(point.value)
                if (index == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, chartHeight)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(width, chartHeight)
            fillPath.close()

            drawPath(fillPath, Brush.verticalGradient(listOf(fillTop, fillBottom)))
            drawPath(linePath, lineColor, style = Stroke(width = 6f))

            // Last point is always highlighted, the selected one gets a marker.
            val lastX = stepX * points.lastIndex
            drawCircle(lineColor, radius = 9f, center = Offset(lastX, yFor(points.last().value)))

            selected?.let { index ->
                val x = stepX * index
                val y = yFor(points[index].value)
                drawLine(lineColor.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, chartHeight), strokeWidth = 2f)
                drawCircle(Color.White, radius = 12f, center = Offset(x, y))
                drawCircle(lineColor, radius = 8f, center = Offset(x, y))
            }
        }

        Spacer(Modifier.height(6.dp))
        val info = selected?.let { points[it] }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                info?.label ?: points.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                info?.let { "${valueFormatter(it.value)}${if (it.detail.isNotBlank()) " · ${it.detail}" else ""}" }
                    ?: points.last().label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (info != null) FontWeight.Bold else FontWeight.Normal,
                color = if (info != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected == null && points.size > 1) {
            Text(
                "Tippe auf den Verlauf für Details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Bar chart for weekly volume; the last (current) bar is accented. */
@Composable
fun BarChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 90.dp,
    valueFormatter: (Double) -> String = { Fmt.volume(it) },
) {
    if (points.isEmpty()) {
        EmptyChartHint(modifier, height)
        return
    }
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val maxValue = points.maxOf { it.value }.takeIf { it > 0 } ?: 1.0
    val muted = AppTheme.colors.barMuted

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(height),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            points.forEachIndexed { index, point ->
                val fraction = (point.value / maxValue).toFloat().coerceIn(0.04f, 1f)
                val isLast = index == points.lastIndex
                val isSelected = selected == index
                val color by animateColorAsState(
                    when {
                        isSelected || isLast -> MaterialTheme.colorScheme.primary
                        else -> muted
                    },
                    label = "bar-color",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(color)
                        .clickable { selected = if (isSelected) null else index },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val info = selected?.let { points[it] }
        Text(
            info?.let { "${it.label}: ${valueFormatter(it.value)}${if (it.detail.isNotBlank()) " · ${it.detail}" else ""}" }
                ?: points.joinToString(" · ") { it.label }.take(60),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyChartHint(modifier: Modifier, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Noch nicht genug Daten",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Horizontal period selector (7 T / 30 T / 3 M / …). */
@Composable
fun PeriodSelector(
    periods: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        periods.forEach { (key, label) ->
            val active = key == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Non-blocking banner for offline mode and errors. */
@Composable
fun StatusBanner(text: String, isError: Boolean = false, onAction: (() -> Unit)? = null, actionLabel: String? = null) {
    val background = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    else MaterialTheme.colorScheme.surfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onAction != null && actionLabel != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}

/** Small badge marking values that came from the AI pipeline. */
@Composable
fun AiBadge(confidence: Double?) {
    val label = confidence?.let { "KI ${(it * 100).roundToInt()} %" } ?: "KI"
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** Rounds to a sensible step so ±0.05 kg never shows up in the UI. */
fun Double.roundSensible(): Double = (this * 100).roundToInt() / 100.0

fun Double?.orZero(): Double = this ?: 0.0

fun safeFraction(value: Double, max: Double): Float =
    if (max <= 0) 0f else (abs(value) / max).toFloat().coerceIn(0f, 1f)

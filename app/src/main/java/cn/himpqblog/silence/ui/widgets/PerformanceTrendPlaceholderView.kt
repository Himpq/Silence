package cn.himpqblog.silence.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import cn.himpqblog.silence.R
import cn.himpqblog.silence.perf.PerformanceTrendPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class PerformanceTrendPlaceholderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface OnViewportChangedListener {
        fun onViewportChanged(startTimestampMs: Long, endTimestampMs: Long, isZoomed: Boolean)
    }

    private data class AxisTick(
        val elapsedMs: Long,
        val ratio: Float,
        val timestampMs: Long
    )

    private data class SeriesPoint(
        val timestampMs: Long,
        val xRatio: Float,
        val y: Float,
        val powerSign: Int,
        val gapBefore: Boolean
    )

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_card_soft)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider_color)
        strokeWidth = density
    }
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_primary)
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chargingPowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5BC980.toInt()
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dischargingPowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB35C.toInt()
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 12f * scaledDensity
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_hint)
        textSize = 10.5f * scaledDensity
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 13f * scaledDensity
    }
    private val legendBatteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_primary)
        textSize = 11f * scaledDensity
    }
    private val legendPowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.perf_bar_warm)
        textSize = 11f * scaledDensity
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        strokeWidth = density
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_panel)
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 10.5f * scaledDensity
    }
    private val resetButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_panel)
    }
    private val resetButtonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_hint)
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val resetButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 14f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())

    private var points: List<PerformanceTrendPoint> = emptyList()
    private var chartTitle: String = context.getString(R.string.performance_records_chart_placeholder)
    private var viewportStartRatio = 0f
    private var viewportSpanRatio = 1f
    private var lastTouchX = 0f
    private var isDragging = false
    private var highlightedPointIndex = -1
    private var resetButtonRect = RectF()
    private var powerMax = 0.5f
    private var gapThresholdMs = 15_000L

    var onViewportChangedListener: OnViewportChangedListener? = null

    fun setSampleSeed(seed: Long) {
        chartTitle = context.getString(R.string.performance_records_chart_placeholder)
        points = emptyList()
        viewportStartRatio = 0f
        viewportSpanRatio = 1f
        highlightedPointIndex = -1
        powerMax = 0.5f
        gapThresholdMs = 15_000L
        invalidate()
    }

    // 图表每次拿到新数据后会重置缩放窗口，时间轴始终保持整张原图的真实跨度。
    fun renderTrend(title: String, trendPoints: List<PerformanceTrendPoint>) {
        chartTitle = title
        points = trendPoints.sortedBy { it.timestampEpochMs }
        viewportStartRatio = 0f
        viewportSpanRatio = 1f
        highlightedPointIndex = if (points.isNotEmpty()) points.lastIndex else -1
        powerMax = max(0.5f, points.maxOfOrNull { abs(it.powerW) } ?: 0.5f)
        gapThresholdMs = estimateGapThresholdMs(points)
        dispatchViewportChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val panelRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(panelRect, 18f * density, 18f * density, panelPaint)

        val chartLeft = 50f * density
        val chartRight = width - 50f * density
        val chartTop = 32f * density
        val chartBottom = height - 70f * density
        val chartWidth = max(1f, chartRight - chartLeft)
        val chartHeight = max(1f, chartBottom - chartTop)

        titlePaint.textAlign = Paint.Align.LEFT
        legendBatteryPaint.textAlign = Paint.Align.RIGHT
        legendPowerPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(chartTitle, chartLeft, 18f * density, titlePaint)
        canvas.drawText("电量", width - 84f * density, 18f * density, legendBatteryPaint)
        canvas.drawText("功率", width - 18f * density, 18f * density, legendPowerPaint)

        if (points.isEmpty()) {
            canvas.drawText(
                context.getString(R.string.home_performance_monitor_empty),
                chartLeft,
                chartTop + chartHeight / 2f,
                emptyPaint
            )
            return
        }

        val viewportStartTime = resolveViewportStartTimestamp()
        val viewportEndTime = resolveViewportEndTimestamp()
        val visiblePoints = resolveVisiblePoints()
        drawHorizontalGrid(canvas, chartLeft, chartRight, chartTop, chartBottom)
        drawLeftBatteryAxis(canvas, chartLeft, chartTop, chartBottom)
        drawRightPowerAxis(canvas, chartRight, chartTop, chartBottom, powerMax)
        drawBottomTimeAxis(canvas, chartLeft, chartRight, chartTop, chartBottom, chartWidth, viewportStartTime, viewportEndTime)
        drawCurves(canvas, chartLeft, chartTop, chartWidth, chartHeight, visiblePoints, viewportStartTime, viewportEndTime)
        drawHighlight(canvas, chartLeft, chartTop, chartWidth, chartHeight, visiblePoints, viewportStartTime, viewportEndTime)
        drawResetButton(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) {
            return super.onTouchEvent(event)
        }
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isTouchInsideResetButton(event.x, event.y)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && event.pointerCount == 1) {
                    val deltaX = event.x - lastTouchX
                    if (abs(deltaX) > 2f * density) {
                        panViewport(deltaX)
                        isDragging = true
                        lastTouchX = event.x
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isTouchInsideResetButton(event.x, event.y)) {
                    resetViewport()
                } else if (!scaleGestureDetector.isInProgress && !isDragging) {
                    highlightNearestPoint(event.x)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDragging = false
            }
        }
        return true
    }

    private fun drawHorizontalGrid(
        canvas: Canvas,
        chartLeft: Float,
        chartRight: Float,
        chartTop: Float,
        chartBottom: Float
    ) {
        repeat(5) { index ->
            val ratio = index / 4f
            val y = chartTop + (chartBottom - chartTop) * ratio
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }
    }

    private fun drawLeftBatteryAxis(
        canvas: Canvas,
        chartLeft: Float,
        chartTop: Float,
        chartBottom: Float
    ) {
        axisPaint.textAlign = Paint.Align.RIGHT
        repeat(5) { index ->
            val percent = 100 - index * 25
            val ratio = index / 4f
            val y = chartTop + (chartBottom - chartTop) * ratio + 4f * density
            canvas.drawText("$percent%", chartLeft - 6f * density, y, axisPaint)
        }
    }

    private fun drawRightPowerAxis(
        canvas: Canvas,
        chartRight: Float,
        chartTop: Float,
        chartBottom: Float,
        maxPower: Float
    ) {
        axisPaint.textAlign = Paint.Align.LEFT
        val labels = listOf(maxPower, maxPower / 2f, 0f)
        labels.forEachIndexed { index, value ->
            val ratio = index / 2f
            val y = chartTop + (chartBottom - chartTop) * ratio + 4f * density
            canvas.drawText(String.format(Locale.US, "%.1fW", value), chartRight + 6f * density, y, axisPaint)
        }
    }

    private fun drawBottomTimeAxis(
        canvas: Canvas,
        chartLeft: Float,
        chartRight: Float,
        chartTop: Float,
        chartBottom: Float,
        chartWidth: Float,
        viewportStartTime: Long,
        viewportEndTime: Long
    ) {
        val ticks = buildTicks(viewportStartTime, viewportEndTime)
        val maxLabelCount = max(3, (chartWidth / (92f * density)).toInt())
        val labelEvery = max(1, ceil((ticks.size - 1).toFloat() / max(1, maxLabelCount - 1).toFloat()).toInt())

        ticks.forEachIndexed { index, tick ->
            val x = chartLeft + (chartRight - chartLeft) * tick.ratio
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
            if (index == 0 || index == ticks.lastIndex || index % labelEvery == 0) {
                axisPaint.textAlign = when (index) {
                    0 -> Paint.Align.LEFT
                    ticks.lastIndex -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                val baseY = chartBottom + 18f * density
                canvas.drawText(formatDurationLabel(tick.elapsedMs), x, baseY, axisPaint)
                canvas.drawText("(${timeFormatter.format(Date(tick.timestampMs))})", x, baseY + 13f * density, axisPaint)
            }
        }
    }

    // 超过 1 个采样间隔没记录才断线；充放电切换只换颜色，不切断连续线段。
    private fun drawCurves(
        canvas: Canvas,
        chartLeft: Float,
        chartTop: Float,
        chartWidth: Float,
        chartHeight: Float,
        visiblePoints: List<PerformanceTrendPoint>,
        viewportStartTime: Long,
        viewportEndTime: Long
    ) {
        if (visiblePoints.isEmpty()) {
            return
        }
        val batterySeries = buildSeries(visiblePoints, viewportStartTime, viewportEndTime) { point ->
            val batteryRatio = (point.batteryLevelPercent?.coerceIn(0, 100)?.toFloat() ?: 0f) / 100f
            chartTop + chartHeight * (1f - batteryRatio)
        }
        val powerSeries = buildSeries(visiblePoints, viewportStartTime, viewportEndTime) { point ->
            val powerRatio = (abs(point.powerW) / powerMax).coerceIn(0f, 1f)
            chartTop + chartHeight * (1f - powerRatio)
        }

        splitSeriesBySamplingGap(batterySeries).forEach { segment ->
            val path = buildSmoothPath(segment)
            if (!path.isEmpty) {
                canvas.drawPath(path, batteryPaint)
            }
        }
        drawPowerSeries(canvas, powerSeries)
    }

    private fun drawHighlight(
        canvas: Canvas,
        chartLeft: Float,
        chartTop: Float,
        chartWidth: Float,
        chartHeight: Float,
        visiblePoints: List<PerformanceTrendPoint>,
        viewportStartTime: Long,
        viewportEndTime: Long
    ) {
        if (highlightedPointIndex !in visiblePoints.indices) {
            return
        }
        val point = visiblePoints[highlightedPointIndex]
        val x = chartLeft + chartWidth * resolveRatioX(point.timestampEpochMs, viewportStartTime, viewportEndTime)
        val batteryRatio = (point.batteryLevelPercent?.coerceIn(0, 100)?.toFloat() ?: 0f) / 100f
        val y = chartTop + chartHeight * (1f - batteryRatio)
        canvas.drawLine(x, chartTop, x, chartTop + chartHeight, indicatorPaint)
        canvas.drawCircle(x, y, 3f * density, indicatorPaint)

        val tooltipLines = listOf(
            dateTimeFormatter.format(Date(point.timestampEpochMs)),
            "电量 ${point.batteryLevelPercent ?: 0}%",
            "功率 ${String.format(Locale.US, "%.2fW", abs(point.powerW))}"
        )
        val tooltipWidth = tooltipLines.maxOf { tooltipTextPaint.measureText(it) } + 16f * density
        val tooltipHeight = tooltipLines.size * 13f * density + 10f * density
        val tooltipLeft = min(max(chartLeft, x - tooltipWidth / 2f), chartLeft + chartWidth - tooltipWidth)
        val tooltipTop = max(8f * density, chartTop + 8f * density)
        val tooltipRect = RectF(tooltipLeft, tooltipTop, tooltipLeft + tooltipWidth, tooltipTop + tooltipHeight)
        canvas.drawRoundRect(tooltipRect, 10f * density, 10f * density, tooltipPaint)
        tooltipTextPaint.textAlign = Paint.Align.LEFT
        tooltipLines.forEachIndexed { index, line ->
            canvas.drawText(line, tooltipLeft + 8f * density, tooltipTop + 14f * density + index * 13f * density, tooltipTextPaint)
        }
    }

    private fun drawResetButton(canvas: Canvas) {
        val radius = 18f * density
        val centerX = 22f * density + radius
        val centerY = height - 22f * density - radius
        resetButtonRect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawOval(resetButtonRect, resetButtonPaint)
        canvas.drawOval(resetButtonRect, resetButtonStrokePaint)
        val baseline = centerY - (resetButtonTextPaint.descent() + resetButtonTextPaint.ascent()) / 2f
        canvas.drawText("↺", centerX, baseline, resetButtonTextPaint)
    }

    private fun resolveVisiblePoints(): List<PerformanceTrendPoint> {
        if (points.size <= 1) {
            return points
        }
        val viewportStartTime = resolveViewportStartTimestamp()
        val viewportEndTime = resolveViewportEndTimestamp()
        val visible = points.filterIndexed { index, point ->
            val inRange = point.timestampEpochMs in viewportStartTime..viewportEndTime
            val keepPreviousEdge = index > 0 &&
                point.timestampEpochMs >= viewportStartTime &&
                points[index - 1].timestampEpochMs < viewportStartTime &&
                point.timestampEpochMs - points[index - 1].timestampEpochMs <= gapThresholdMs
            val keepNextEdge = index < points.lastIndex &&
                point.timestampEpochMs <= viewportEndTime &&
                points[index + 1].timestampEpochMs > viewportEndTime &&
                points[index + 1].timestampEpochMs - point.timestampEpochMs <= gapThresholdMs
            inRange || keepPreviousEdge || keepNextEdge
        }
        if (highlightedPointIndex >= visible.size) {
            highlightedPointIndex = visible.lastIndex
        }
        return visible
    }

    private fun buildSeries(
        visiblePoints: List<PerformanceTrendPoint>,
        viewportStartTime: Long,
        viewportEndTime: Long,
        yResolver: (PerformanceTrendPoint) -> Float
    ): List<SeriesPoint> {
        if (visiblePoints.isEmpty()) {
            return emptyList()
        }
        val raw = visiblePoints.map { point -> point to yResolver(point) }
        return raw.mapIndexed { index, entry ->
            val point = entry.first
            val prev = raw.getOrNull(index - 1)?.second ?: entry.second
            val curr = entry.second
            val next = raw.getOrNull(index + 1)?.second ?: entry.second
            val smoothed = (prev + curr * 2f + next) / 4f
            SeriesPoint(
                timestampMs = point.timestampEpochMs,
                xRatio = resolveRatioX(point.timestampEpochMs, viewportStartTime, viewportEndTime),
                y = smoothed,
                powerSign = resolvePowerSign(point.powerW),
                gapBefore = index > 0 && point.timestampEpochMs - visiblePoints[index - 1].timestampEpochMs > gapThresholdMs
            )
        }
    }

    private fun buildSmoothPath(series: List<SeriesPoint>): Path {
        val path = Path()
        if (series.isEmpty()) {
            return path
        }
        val first = series.first()
        path.moveTo(resolveChartX(first.xRatio), first.y)
        for (index in 1 until series.size) {
            val previous = series[index - 1]
            val current = series[index]
            val controlX = (resolveChartX(previous.xRatio) + resolveChartX(current.xRatio)) / 2f
            path.quadTo(controlX, previous.y, resolveChartX(current.xRatio), current.y)
        }
        return path
    }

    private fun drawPowerSeries(canvas: Canvas, series: List<SeriesPoint>) {
        splitSeriesBySamplingGap(series).forEach { continuousSegment ->
            val chargingPath = Path()
            val dischargingPath = Path()
            var chargingStarted = false
            var dischargingStarted = false
            continuousSegment.forEachIndexed { index, point ->
                val x = resolveChartX(point.xRatio)
                if (point.powerSign >= 0) {
                    if (!chargingStarted) {
                        chargingPath.moveTo(x, point.y)
                        chargingStarted = true
                    } else {
                        val previous = continuousSegment[index - 1]
                        val controlX = (resolveChartX(previous.xRatio) + x) / 2f
                        chargingPath.quadTo(controlX, previous.y, x, point.y)
                    }
                } else {
                    if (!dischargingStarted) {
                        dischargingPath.moveTo(x, point.y)
                        dischargingStarted = true
                    } else {
                        val previous = continuousSegment[index - 1]
                        val controlX = (resolveChartX(previous.xRatio) + x) / 2f
                        dischargingPath.quadTo(controlX, previous.y, x, point.y)
                    }
                }
            }
            if (!chargingPath.isEmpty) {
                canvas.drawPath(chargingPath, chargingPowerPaint)
            }
            if (!dischargingPath.isEmpty) {
                canvas.drawPath(dischargingPath, dischargingPowerPaint)
            }
        }
    }

    private fun splitSeriesBySamplingGap(series: List<SeriesPoint>): List<List<SeriesPoint>> {
        if (series.isEmpty()) {
            return emptyList()
        }
        val result = ArrayList<List<SeriesPoint>>()
        var startIndex = 0
        for (index in 1 until series.size) {
            if (series[index].gapBefore) {
                result += series.subList(startIndex, index).toList()
                startIndex = index
            }
        }
        result += series.subList(startIndex, series.size).toList()
        return result
    }

    private fun resolvePowerSign(powerW: Float): Int {
        return when {
            powerW < 0f -> -1
            else -> 1
        }
    }

    // 超过 1 个采样间隔没记录才算断开，这里用全局中位采样间隔作为基准。
    private fun estimateGapThresholdMs(allPoints: List<PerformanceTrendPoint>): Long {
        val deltas = allPoints.zipWithNext()
            .map { (previous, next) -> next.timestampEpochMs - previous.timestampEpochMs }
            .filter { it > 0L }
            .sorted()
        val medianDelta = deltas.getOrNull(deltas.size / 2) ?: 15_000L
        return max(1_000L, medianDelta)
    }

    private fun resolveRatioX(timestampMs: Long, viewportStartTime: Long, viewportEndTime: Long): Float {
        val start = viewportStartTime
        val end = viewportEndTime
        val span = max(1L, end - start)
        return ((timestampMs - start).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    private fun resolveChartX(ratio: Float): Float {
        val chartLeft = 50f * density
        val chartRight = width - 50f * density
        return chartLeft + (chartRight - chartLeft) * ratio
    }

    private fun buildTicks(viewportStartTime: Long, viewportEndTime: Long): List<AxisTick> {
        val start = viewportStartTime
        val totalDurationMs = max(1L, viewportEndTime - viewportStartTime)
        val stepMs = resolveTickStepMs(totalDurationMs)
        val ticks = ArrayList<AxisTick>()
        var elapsed = 0L
        while (elapsed < totalDurationMs) {
            ticks += AxisTick(
                elapsedMs = elapsed,
                ratio = elapsed.toFloat() / totalDurationMs.toFloat(),
                timestampMs = start + elapsed
            )
            elapsed += stepMs
        }
        ticks += AxisTick(
            elapsedMs = totalDurationMs,
            ratio = 1f,
            timestampMs = viewportEndTime
        )
        return ticks
    }

    private fun resolveTickStepMs(totalDurationMs: Long): Long {
        val candidates = longArrayOf(
            10_000L,
            30_000L,
            60_000L,
            5 * 60_000L,
            10 * 60_000L,
            15 * 60_000L,
            30 * 60_000L,
            60 * 60_000L,
            2 * 60 * 60_000L,
            3 * 60 * 60_000L,
            4 * 60 * 60_000L,
            6 * 60 * 60_000L,
            12 * 60 * 60_000L,
            24 * 60 * 60_000L
        )
        val targetSegments = when {
            totalDurationMs >= 24 * 60 * 60_000L -> 24
            totalDurationMs >= 6 * 60 * 60_000L -> 12
            totalDurationMs >= 60 * 60_000L -> 6
            totalDurationMs >= 10 * 60_000L -> 5
            else -> 4
        }
        val targetStep = max(10_000L, totalDurationMs / targetSegments)
        return candidates.minByOrNull { candidate -> abs(candidate - targetStep) } ?: 60_000L
    }

    private fun formatDurationLabel(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 && seconds > 0 -> "${minutes}m${seconds}s"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    private fun panViewport(deltaX: Float) {
        if (viewportSpanRatio >= 0.999f) {
            return
        }
        val chartWidth = max(1f, width - 100f * density)
        val deltaRatio = (-deltaX / chartWidth) * viewportSpanRatio
        viewportStartRatio = (viewportStartRatio + deltaRatio).coerceIn(0f, 1f - viewportSpanRatio)
        dispatchViewportChanged()
        invalidate()
    }

    private fun highlightNearestPoint(touchX: Float) {
        val visiblePoints = resolveVisiblePoints()
        if (visiblePoints.isEmpty()) {
            return
        }
        val viewportStartTime = resolveViewportStartTimestamp()
        val viewportEndTime = resolveViewportEndTimestamp()
        val chartLeft = 50f * density
        val chartRight = width - 50f * density
        val ratio = ((touchX - chartLeft) / max(1f, chartRight - chartLeft)).coerceIn(0f, 1f)
        var nearestIndex = 0
        var nearestDistance = Float.MAX_VALUE
        visiblePoints.forEachIndexed { index, point ->
            val distance = abs(resolveRatioX(point.timestampEpochMs, viewportStartTime, viewportEndTime) - ratio)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = index
            }
        }
        highlightedPointIndex = nearestIndex
        invalidate()
    }

    private fun isTouchInsideResetButton(x: Float, y: Float): Boolean {
        return resetButtonRect.contains(x, y)
    }

    private fun resetViewport() {
        viewportStartRatio = 0f
        viewportSpanRatio = 1f
        dispatchViewportChanged()
        invalidate()
    }

    private fun dispatchViewportChanged() {
        if (points.isEmpty()) {
            return
        }
        onViewportChangedListener?.onViewportChanged(
            startTimestampMs = resolveViewportStartTimestamp(),
            endTimestampMs = resolveViewportEndTimestamp(),
            isZoomed = viewportSpanRatio < 0.999f
        )
    }

    private fun resolveViewportStartTimestamp(): Long {
        val startTime = points.firstOrNull()?.timestampEpochMs ?: 0L
        val totalDurationMs = max(1L, (points.lastOrNull()?.timestampEpochMs ?: startTime) - startTime)
        return startTime + (totalDurationMs * viewportStartRatio).toLong()
    }

    private fun resolveViewportEndTimestamp(): Long {
        val startTime = points.firstOrNull()?.timestampEpochMs ?: 0L
        val totalDurationMs = max(1L, (points.lastOrNull()?.timestampEpochMs ?: startTime) - startTime)
        return startTime + (totalDurationMs * (viewportStartRatio + viewportSpanRatio).coerceAtMost(1f)).toLong()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (points.size <= 1) {
                return false
            }
            val previousSpan = viewportSpanRatio
            viewportSpanRatio = (viewportSpanRatio / detector.scaleFactor).coerceIn(0.05f, 1f)
            val focusRatio = (detector.focusX / max(1f, width.toFloat())).coerceIn(0f, 1f)
            val absoluteFocus = viewportStartRatio + previousSpan * focusRatio
            viewportStartRatio = (absoluteFocus - viewportSpanRatio * focusRatio).coerceIn(0f, 1f - viewportSpanRatio)
            dispatchViewportChanged()
            invalidate()
            return true
        }
    }
}

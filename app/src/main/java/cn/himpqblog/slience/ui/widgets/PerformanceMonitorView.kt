package cn.himpqblog.slience.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import cn.himpqblog.slience.R
import cn.himpqblog.slience.perf.PerformanceMode
import kotlin.math.max

class PerformanceMonitorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_VISIBLE_CORES = 8
        private const val COLUMN_COUNT = 4
    }

    data class CoreHistory(
        val coreIndex: Int,
        val currentMhz: Int,
        val minMhz: Int,
        val maxMhz: Int,
        val historyMhz: List<Int>
    )

    data class Sample(
        val currentMode: PerformanceMode,
        val cores: List<CoreHistory>
    )

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_card_soft)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 10f * scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 12.5f * scaledDensity
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_hint)
        textSize = 8.8f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 10f * scaledDensity
        textAlign = Paint.Align.RIGHT
    }
    private val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_primary)
        textSize = 13f * scaledDensity
        isFakeBoldText = true
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider_color)
        strokeWidth = density
    }

    private val hotColor = ContextCompat.getColor(context, R.color.perf_bar_hot)
    private val warmColor = ContextCompat.getColor(context, R.color.perf_bar_warm)
    private val coolColor = ContextCompat.getColor(context, R.color.perf_bar_cool)
    private val coldColor = ContextCompat.getColor(context, R.color.perf_bar_cold)

    private var sample: Sample? = null

    // 性能页的小卡片图统一通过这一个入口喂数据，保持测量和绘制时序一致。
    fun render(sample: Sample?) {
        this.sample = sample
        requestLayout()
        invalidate()
    }

    // 卡片高度按宽度反推，确保 4 列布局下每张核心卡片比例稳定。
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (measuredWidth <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val horizontalPadding = 20f * density
        val horizontalGap = 6f * density
        val verticalGap = 10f * density
        val headerHeight = 36f * density
        val bottomPadding = 14f * density
        val visibleCount = (sample?.cores?.take(MAX_VISIBLE_CORES)?.size ?: MAX_VISIBLE_CORES).coerceAtLeast(1)
        val rows = ((visibleCount + COLUMN_COUNT - 1) / COLUMN_COUNT).coerceAtLeast(1)
        val availableWidth = (measuredWidth - horizontalPadding).coerceAtLeast(0f)
        val cardWidth = if (COLUMN_COUNT > 0) {
            (availableWidth - horizontalGap * (COLUMN_COUNT - 1)) / COLUMN_COUNT.toFloat()
        } else {
            availableWidth
        }.coerceAtLeast(56f * density)
        val cardHeight = cardWidth * 1.24f
        val desiredHeight = (
            headerHeight +
                rows * cardHeight +
                (rows - 1) * verticalGap +
                bottomPadding
            ).toInt()
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    // 每次重绘都按“标题 + 4x2 核心卡片”结构重新布局，避免横竖屏比例失真。
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = sample
        if (current == null || current.cores.isEmpty()) {
            drawEmptyState(canvas)
            return
        }

        val headerTop = 18f * density
        canvas.drawText(
            context.getString(R.string.home_performance_monitor_title),
            16f * density,
            headerTop,
            labelPaint
        )
        canvas.drawText(
            context.getString(current.currentMode.labelRes),
            width - 16f * density,
            headerTop,
            modePaint.apply { textAlign = Paint.Align.RIGHT }
        )

        val contentTop = 30f * density
        val horizontalGap = 6f * density
        val verticalGap = 10f * density
        val rows = ((current.cores.size + COLUMN_COUNT - 1) / COLUMN_COUNT).coerceAtLeast(1)
        val availableWidth = width - 20f * density
        val cardWidth = (availableWidth - horizontalGap * (COLUMN_COUNT - 1)) / COLUMN_COUNT
        val cardHeight = (height - contentTop - 16f * density - verticalGap * (rows - 1)) / rows

        current.cores.take(MAX_VISIBLE_CORES).forEachIndexed { index, core ->
            val column = index % COLUMN_COUNT
            val row = index / COLUMN_COUNT
            val left = 10f * density + column * (cardWidth + horizontalGap)
            val top = contentTop + row * (cardHeight + verticalGap)
            drawCoreCard(
                canvas = canvas,
                core = core,
                rect = RectF(left, top, left + cardWidth, top + cardHeight)
            )
        }
    }

    // 没有频率数据时只画轻量空态，避免把旧缓存误展示成当前状态。
    private fun drawEmptyState(canvas: Canvas) {
        canvas.drawText(
            context.getString(R.string.home_performance_monitor_empty),
            16f * density,
            height / 2f,
            labelPaint
        )
    }

    // 单核心卡片里同时负责标题、占用率、五段频率柱和当前频率范围文本。
    private fun drawCoreCard(canvas: Canvas, core: CoreHistory, rect: RectF) {
        canvas.drawRoundRect(rect, 18f * density, 18f * density, cardPaint)
        val titleBaseline = rect.top + 18f * density
        canvas.drawText("C${core.coreIndex}", rect.left + 10f * density, titleBaseline, labelPaint)

        val percent = resolveUsagePercent(core)
        canvas.drawText(
            "$percent%",
            rect.right - 10f * density,
            titleBaseline,
            percentPaint
        )

        val chartHorizontalPadding = 8f * density
        val chartTop = rect.top + 28f * density
        val chartAreaHeight = ((rect.width() - chartHorizontalPadding * 2f) * 0.98f)
            .coerceAtMost(rect.height() * 0.39f)
        val chartLeft = rect.left + chartHorizontalPadding
        val chartRight = rect.right - chartHorizontalPadding
        val chartBottom = chartTop + chartAreaHeight
        val history = core.historyMhz.takeLast(5).let {
            if (it.size >= 5) it else List(5 - it.size) { core.minMhz } + it
        }
        val normalizedMax = max(core.maxMhz, history.maxOrNull() ?: core.currentMhz).coerceAtLeast(1)
        val chartWidth = chartRight - chartLeft
        val spacing = chartWidth / 9f
        val barWidth = spacing
        history.forEachIndexed { index, value ->
            val normalized = value.toFloat() / normalizedMax.toFloat()
            val barHeight = max(chartAreaHeight * normalized, 6f * density)
            val left = chartLeft + index * spacing * 2f
            val top = chartBottom - barHeight
            barPaint.color = resolveBarColor(core, value)
            canvas.drawRoundRect(
                RectF(left, top, left + barWidth, chartBottom),
                6f * density,
                6f * density,
                barPaint
            )
        }
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, gridPaint)

        val valueCenterX = rect.centerX()
        val valueBaseline = chartBottom + 24f * density
        canvas.drawText("${core.currentMhz}MHz", valueCenterX, valueBaseline, valuePaint)
        canvas.drawText(
            "${core.minMhz}-${core.maxMhz}MHz",
            valueCenterX,
            valueBaseline + 13f * density,
            rangePaint
        )
    }

    // 这里的“占用率”是基于频率区间的相对值，不是系统真实 CPU usage。
    private fun resolveUsagePercent(core: CoreHistory): Int {
        val range = (core.maxMhz - core.minMhz).coerceAtLeast(1)
        val relative = (core.currentMhz - core.minMhz).coerceAtLeast(0)
        return ((relative * 100f) / range.toFloat()).toInt().coerceIn(0, 100)
    }

    // 柱状颜色跟着频率高低走，越接近最大频率越偏暖色。
    private fun resolveBarColor(core: CoreHistory, value: Int): Int {
        val range = (core.maxMhz - core.minMhz).coerceAtLeast(1)
        val normalized = ((value - core.minMhz).coerceAtLeast(0)).toFloat() / range.toFloat()
        return when {
            normalized >= 0.78f -> hotColor
            normalized >= 0.52f -> warmColor
            normalized >= 0.24f -> coolColor
            else -> blendColor(coldColor, coolColor, normalized / 0.24f)
        }
    }

    // 冷暖色过渡统一走线性混色，保证不同核心的低频颜色也有细微层次。
    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val red = Color.red(from) + ((Color.red(to) - Color.red(from)) * t).toInt()
        val green = Color.green(from) + ((Color.green(to) - Color.green(from)) * t).toInt()
        val blue = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.rgb(red, green, blue)
    }
}

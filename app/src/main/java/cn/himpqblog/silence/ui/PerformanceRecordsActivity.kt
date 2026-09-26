package cn.himpqblog.silence.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import cn.himpqblog.silence.R
import cn.himpqblog.silence.databinding.ActivityPerformanceRecordsBinding
import cn.himpqblog.silence.perf.PerformanceLogEntryType
import cn.himpqblog.silence.perf.PerformanceLogFileSummary
import cn.himpqblog.silence.perf.PerformanceLogRecord
import cn.himpqblog.silence.perf.PerformanceLogStore
import cn.himpqblog.silence.ui.widgets.PerformanceTrendPlaceholderView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class PerformanceRecordsActivity : AppCompatActivity() {

    private data class VisiblePackageSummary(
        val packageName: String,
        val durationMs: Long,
        val averagePowerW: Float
    )

    private lateinit var binding: ActivityPerformanceRecordsBinding
    private val adapter = PerformanceRecordFileAdapter()
    private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private var currentRecords: List<PerformanceLogRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPerformanceRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = ContextCompat.getColor(this, R.color.surface_panel)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.surface_bg)

        binding.topToolbar.setNavigationOnClickListener { finish() }
        binding.recordFilesList.layoutManager = LinearLayoutManager(this)
        binding.recordFilesList.adapter = adapter
        adapter.onItemClick = { summary ->
            renderTrend(summary)
        }
        adapter.onItemLongClick = { summary ->
            showRecordOptions(summary)
        }
        binding.performanceTrendPlaceholder.onViewportChangedListener =
            object : PerformanceTrendPlaceholderView.OnViewportChangedListener {
                override fun onViewportChanged(startTimestampMs: Long, endTimestampMs: Long, isZoomed: Boolean) {
                    updateVisibleRangeSummary(startTimestampMs, endTimestampMs)
                }
            }

        applyWindowInsets()
        loadFiles()
    }

    // 记录页是独立 Activity，这里单独处理状态栏 inset，避免标题顶进系统栏。
    private fun applyWindowInsets() {
        val toolbarBaseHeight = resources.getDimensionPixelSize(R.dimen.top_bar_height)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topToolbar.updatePadding(top = systemBars.top)
            binding.topToolbar.updateLayoutParams {
                height = toolbarBaseHeight + systemBars.top
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    // 列表页默认展示最新记录文件，同时给顶部图表和可视范围摘要喂入首个可用文件的数据。
    private fun loadFiles() {
        val summaries = PerformanceLogStore.listLogFiles(this)
            .ifEmpty { PerformanceRecordFileAdapter.placeholderItems() }
        adapter.submitList(summaries)
        binding.recordSummary.text = getString(R.string.performance_records_summary, summaries.size)
        summaries.firstOrNull()?.let(::renderTrend)
            ?: run {
                currentRecords = emptyList()
                binding.performanceTrendPlaceholder.setSampleSeed(System.currentTimeMillis())
                renderVisibleRangeItems(emptyList())
            }
    }

    // 图表展示读的是“已落盘文件 + 当天内存缓冲”的可见记录集合。
    private fun renderTrend(summary: PerformanceLogFileSummary) {
        val records = PerformanceLogStore.readVisibleRecords(this, summary.fileName)
        currentRecords = records
        val points = PerformanceLogStore.buildPowerTrend(records)
        val title = if (points.isEmpty()) {
            getString(R.string.performance_records_chart_placeholder)
        } else {
            "${summary.dateLabel} · ${points.size} records"
        }
        binding.performanceTrendPlaceholder.renderTrend(title, points)
        if (points.isEmpty()) {
            renderVisibleRangeItems(emptyList())
        }
    }

    // 当前浏览范围摘要按前台包名聚合，展示可视时间段里的占用时长和平均功率。
    private fun updateVisibleRangeSummary(startTimestampMs: Long, endTimestampMs: Long) {
        val sampleRecords = currentRecords
            .filter { it.entryType == PerformanceLogEntryType.SAMPLE }
            .filter { it.recordedAtEpochMs != null }
            .sortedBy { it.recordedAtEpochMs }
        if (sampleRecords.isEmpty()) {
            renderVisibleRangeItems(emptyList())
            return
        }
        val intervalEstimateMs = estimateSampleInterval(sampleRecords)
        data class MutableSummary(var durationMs: Long = 0L, var powerWeightedSum: Double = 0.0)
        val buckets = LinkedHashMap<String, MutableSummary>()
        sampleRecords.forEachIndexed { index, record ->
            val packageName = record.foregroundPackageName?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val currentTimestamp = record.recordedAtEpochMs ?: return@forEachIndexed
            val nextTimestamp = sampleRecords.getOrNull(index + 1)?.recordedAtEpochMs ?: (currentTimestamp + intervalEstimateMs)
            val segmentStart = max(currentTimestamp, startTimestampMs)
            val segmentEnd = minOf(nextTimestamp, endTimestampMs)
            if (segmentEnd <= segmentStart) {
                return@forEachIndexed
            }
            val durationMs = segmentEnd - segmentStart
            val bucket = buckets.getOrPut(packageName) { MutableSummary() }
            bucket.durationMs += durationMs
            bucket.powerWeightedSum += abs(record.powerW ?: 0f).toDouble() * durationMs.toDouble()
        }
        val summaries = buckets.map { (packageName, bucket) ->
            VisiblePackageSummary(
                packageName = packageName,
                durationMs = bucket.durationMs,
                averagePowerW = if (bucket.durationMs > 0L) {
                    (bucket.powerWeightedSum / bucket.durationMs.toDouble()).toFloat()
                } else {
                    0f
                }
            )
        }.sortedByDescending { it.durationMs }
        renderVisibleRangeItems(summaries)
    }

    private fun renderVisibleRangeItems(items: List<VisiblePackageSummary>) {
        binding.visibleRangeContainer.removeAllViews()
        if (items.isEmpty()) {
            binding.visibleRangeContainer.addView(
                createVisibleRangeItemView(
                    getString(R.string.performance_records_visible_range_empty),
                    true
                )
            )
            return
        }
        items.forEach { item ->
            val text = getString(
                R.string.performance_records_visible_range_item,
                item.packageName,
                formatDuration(item.durationMs),
                String.format(Locale.US, "%.2fW", item.averagePowerW)
            )
            binding.visibleRangeContainer.addView(createVisibleRangeItemView(text, false))
        }
    }

    private fun createVisibleRangeItemView(text: String, isEmpty: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(
                ContextCompat.getColor(
                    this@PerformanceRecordsActivity,
                    if (isEmpty) R.color.text_secondary else R.color.text_primary
                )
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isEmpty) 13f else 12.5f)
            setLineSpacing(0f, 1.1f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(this@PerformanceRecordsActivity, R.color.surface_card_soft))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(10)
            }
        }
    }

    private fun estimateSampleInterval(records: List<PerformanceLogRecord>): Long {
        val deltas = records.zipWithNext()
            .mapNotNull { (previous, next) ->
                val start = previous.recordedAtEpochMs
                val end = next.recordedAtEpochMs
                if (start != null && end != null && end > start) end - start else null
            }
        return deltas.sorted().getOrNull(deltas.size / 2) ?: 15_000L
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours}h${minutes}m"
            minutes > 0L -> "${minutes}m${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // 长按记录项弹出的信息窗，用于快速查看统计信息和进入删除确认。
    private fun showRecordOptions(summary: PerformanceLogFileSummary) {
        val message = buildString {
            append(getString(R.string.performance_record_option_start_time))
            append(": ")
            append(summary.firstRecordedAt?.let { timeFormatter.format(Date(it)) } ?: "--")
            append('\n')
            append(getString(R.string.performance_record_option_updated_time))
            append(": ")
            append(timeFormatter.format(Date(summary.lastModifiedAt)))
            append('\n')
            append(getString(R.string.performance_record_option_count))
            append(": ")
            append(summary.recordCount)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(summary.fileName)
            .setMessage(message)
            .setPositiveButton(R.string.performance_record_delete) { _, _ ->
                confirmDelete(summary)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // 删除前二次确认，避免测试日志被误删后无法追溯。
    private fun confirmDelete(summary: PerformanceLogFileSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.performance_record_delete_confirm_title)
            .setMessage(getString(R.string.performance_record_delete_confirm_message, summary.fileName))
            .setPositiveButton(R.string.performance_record_delete) { _, _ ->
                val success = PerformanceLogStore.deleteLogFile(this, summary.fileName)
                Toast.makeText(
                    this,
                    if (success) R.string.performance_record_delete_success else R.string.performance_record_delete_failed,
                    Toast.LENGTH_SHORT
                ).show()
                loadFiles()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

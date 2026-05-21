package cn.himpqblog.slience.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.himpqblog.slience.R
import cn.himpqblog.slience.databinding.ItemPerformanceRecordFileBinding
import cn.himpqblog.slience.perf.PerformanceLogFileSummary
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PerformanceRecordFileAdapter :
    ListAdapter<PerformanceLogFileSummary, PerformanceRecordFileAdapter.ViewHolder>(DiffCallback) {

    var onItemClick: ((PerformanceLogFileSummary) -> Unit)? = null
    var onItemLongClick: ((PerformanceLogFileSummary) -> Unit)? = null

    companion object {
        fun placeholderItems(): List<PerformanceLogFileSummary> {
            val now = System.currentTimeMillis()
            return listOf(
                PerformanceLogFileSummary("silence_perf_2026-05-20.csv", "2026-05-20", 128, now - 3_600_000L, now, 18_432),
                PerformanceLogFileSummary("silence_perf_2026-05-19.csv", "2026-05-19", 96, now - 90_000_000L, now - 86_400_000L, 13_104),
                PerformanceLogFileSummary("silence_perf_2026-05-18.csv", "2026-05-18", 72, now - 176_400_000L, now - 172_800_000L, 9_876)
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPerformanceRecordFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick, onItemLongClick)
    }

    class ViewHolder(
        private val binding: ItemPerformanceRecordFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private val sizeFormatter = DecimalFormat("0.0")

        fun bind(
            item: PerformanceLogFileSummary,
            onItemClick: ((PerformanceLogFileSummary) -> Unit)?,
            onItemLongClick: ((PerformanceLogFileSummary) -> Unit)?
        ) {
            // 列表项只承担摘要展示，真正的图表数据在点击后再按需读取。
            val ctx = binding.root.context
            binding.recordFileDate.text = item.dateLabel
            binding.recordFileName.text = item.fileName
            binding.recordFileMeta.text = ctx.getString(
                R.string.performance_record_item_meta,
                item.recordCount,
                sizeFormatter.format(item.fileSizeBytes / 1024f),
                timeFormatter.format(Date(item.lastModifiedAt))
            )
            binding.root.setOnClickListener { onItemClick?.invoke(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick?.invoke(item)
                true
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<PerformanceLogFileSummary>() {
        override fun areItemsTheSame(
            oldItem: PerformanceLogFileSummary,
            newItem: PerformanceLogFileSummary
        ): Boolean = oldItem.fileName == newItem.fileName

        override fun areContentsTheSame(
            oldItem: PerformanceLogFileSummary,
            newItem: PerformanceLogFileSummary
        ): Boolean = oldItem == newItem
    }
}

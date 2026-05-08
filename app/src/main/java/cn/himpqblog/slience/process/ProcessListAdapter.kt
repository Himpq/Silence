package cn.himpqblog.slience.process

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.himpqblog.slience.R
import cn.himpqblog.slience.databinding.ItemProcessAppBinding
import java.util.Locale

class ProcessListAdapter(
    private val onItemClick: (ProcessAppItem) -> Unit = {},
    private val onItemLongClick: (ProcessAppItem) -> Unit = {}
) : ListAdapter<ProcessAppItem, ProcessListAdapter.ProcessViewHolder>(DiffCallback) {

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProcessViewHolder {
        val binding = ItemProcessAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProcessViewHolder(binding, onItemClick, onItemLongClick)
    }

    override fun onBindViewHolder(holder: ProcessViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).packageName.hashCode().toLong()
    }

    class ProcessViewHolder(
        private val binding: ItemProcessAppBinding,
        private val onItemClick: (ProcessAppItem) -> Unit,
        private val onItemLongClick: (ProcessAppItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ProcessAppItem) {
            val context = binding.root.context
            binding.appIcon.setImageDrawable(item.icon ?: context.packageManager.defaultActivityIcon)
            binding.appName.text = item.appName

            val stateText = if (item.isFrozen) {
                context.getString(R.string.process_state_frozen)
            } else {
                context.getString(R.string.process_state_running)
            }

            binding.appDetails.text = buildSummaryText(item, stateText)
            binding.processList.text = buildProcessListText(item)

            val memoryText = context.getString(
                R.string.process_memory_label,
                formatBytes(item.memoryBytes)
            )
            val cpuText = context.getString(
                R.string.process_cpu_label,
                String.format(Locale.US, "%.1f%%", item.cpuPercent)
            )

            binding.memoryUsage.text = memoryText
            binding.cpuUsage.text = cpuText
            binding.root.setOnClickListener {
                onItemClick(item)
            }
            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }

        private fun buildSummaryText(item: ProcessAppItem, stateText: String): CharSequence {
            val context = binding.root.context
            val builder = SpannableStringBuilder(
                context.getString(
                    R.string.process_frozen_line,
                    item.processCount,
                    stateText,
                    item.frozenProcessCount
                )
            )
            if (item.isFrozen) {
                val start = builder.length
                val freezeModeText = when (item.freezeMode) {
                    "V2" -> context.getString(R.string.process_freeze_mode_placeholder)
                    else -> context.getString(R.string.process_freeze_mode_system)
                }
                builder.append(" ($freezeModeText)")
                builder.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.warning_yellow_text)),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return builder
        }

        private fun buildProcessListText(item: ProcessAppItem): CharSequence {
            val context = binding.root.context
            val highlightColor = ContextCompat.getColor(context, R.color.warning_yellow_text)
            val builder = SpannableStringBuilder()
            item.processNames.forEachIndexed { index, name ->
                if (index > 0) builder.append(", ")
                val start = builder.length
                builder.append(name)
                if (item.frozenProcessNames.contains(name)) {
                    builder.setSpan(
                        ForegroundColorSpan(highlightColor),
                        start,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            return builder
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ProcessAppItem>() {
        override fun areItemsTheSame(oldItem: ProcessAppItem, newItem: ProcessAppItem): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: ProcessAppItem, newItem: ProcessAppItem): Boolean {
            return oldItem.processCount == newItem.processCount &&
                oldItem.frozenProcessCount == newItem.frozenProcessCount &&
                oldItem.processNames == newItem.processNames &&
                oldItem.frozenProcessNames == newItem.frozenProcessNames &&
                oldItem.childProcessNames == newItem.childProcessNames &&
                oldItem.processEntries == newItem.processEntries &&
                oldItem.memoryBytes == newItem.memoryBytes &&
                kotlin.math.abs(oldItem.cpuPercent - newItem.cpuPercent) < 0.1 &&
                oldItem.isFrozen == newItem.isFrozen &&
                oldItem.appName == newItem.appName &&
                oldItem.freezeMode == newItem.freezeMode
        }
    }
}

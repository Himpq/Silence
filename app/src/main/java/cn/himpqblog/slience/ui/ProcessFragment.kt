package cn.himpqblog.slience.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cn.himpqblog.slience.R
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.databinding.DialogProcessDetailBinding
import cn.himpqblog.slience.databinding.FragmentProcessBinding
import cn.himpqblog.slience.databinding.ViewStatePopupBinding
import cn.himpqblog.slience.hook.RuntimeLogStore
import cn.himpqblog.slience.process.AppRuntimeState
import cn.himpqblog.slience.process.CpuSnapshot
import cn.himpqblog.slience.process.ProcessAppItem
import cn.himpqblog.slience.process.ProcessInspector
import cn.himpqblog.slience.process.ProcessListAdapter
import cn.himpqblog.slience.root.Permission
import cn.himpqblog.slience.settings.SettingsStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.button.MaterialButton
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProcessFragment : Fragment() {

    private var _binding: FragmentProcessBinding? = null
    private val binding: FragmentProcessBinding
        get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshing = AtomicBoolean(false)
    private val statePolling = AtomicBoolean(false)
    private val adapter = ProcessListAdapter(
        onItemClick = ::showProcessRulePreview,
        onItemLongClick = ::showProcessSettingsDialog
    )
    private val stateCache = ConcurrentHashMap<String, AppRuntimeState>()

    private val freezeConditionOptions = listOf("AUDIO", "NETWORK", "VISIBLE")
    private val statePollBatchSize = 4

    private var lastSnapshot: CpuSnapshot? = null
    private var lastStatePollAt: Long = 0L
    private var statePopupWindow: PopupWindow? = null
    private var statePollCursor: Int = 0

    private val dismissStatePopupRunnable = Runnable {
        statePopupWindow?.dismiss()
        statePopupWindow = null
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshProcessList()
            val context = context
            val intervalMs = if (context == null) {
                3000L
            } else {
                SettingsStore.getProcessRefreshIntervalSeconds(context) * 1000L
            }
            mainHandler.postDelayed(this, intervalMs)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProcessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.processRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.processRecyclerView.adapter = adapter
        binding.processRecyclerView.itemAnimator = null

        binding.processStatusValue.text = getString(R.string.process_refreshing)
        binding.requestRootButton.setOnClickListener {
            binding.processStatusValue.text = getString(R.string.process_refreshing)
            Permission.requestRoot { result ->
                binding.processStatusValue.text = if (result.granted) {
                    getString(R.string.process_root_granted)
                } else {
                    getString(R.string.process_root_missing)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(dismissStatePopupRunnable)
        statePopupWindow?.dismiss()
        statePopupWindow = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(pollRunnable)
        _binding = null
    }

    private fun refreshProcessList() {
        if (!refreshing.compareAndSet(false, true)) {
            return
        }

        Thread {
            try {
                val context = context?.applicationContext ?: return@Thread
                val result = ProcessInspector.collect(context, lastSnapshot)
                lastSnapshot = result.snapshot ?: lastSnapshot

                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread

                    val uiStart = SystemClock.elapsedRealtime()
                    val sortedItems = sortItems(result.items)
                    adapter.submitList(sortedItems)
                    RuntimeLogStore.appendDiagnostic(
                        "process-ui",
                        "items=${result.items.size} processes=${result.items.sumOf { it.processCount }} error=${result.errorMessage ?: "none"} uiSubmit=${SystemClock.elapsedRealtime() - uiStart}ms",
                        "process_ui_refresh",
                        5000L
                    )
                    binding.processStatusValue.text = when {
                        result.errorMessage != null -> getString(R.string.process_root_missing)
                        result.items.isEmpty() -> getString(R.string.process_empty)
                        else -> getString(
                            R.string.process_status_summary,
                            result.items.size,
                            result.items.sumOf { it.processCount }
                        )
                    }
                }
                pollCachedStatesIfNeeded(result.items)
            } finally {
                refreshing.set(false)
            }
        }.start()
    }

    private fun showProcessSettingsDialog(item: ProcessAppItem) {
        val context = context ?: return
        val dialogBinding = DialogProcessDetailBinding.inflate(layoutInflater)
        val savedRule = FreezeListStore.loadRule(context, item.packageName)
        var isWhitelist = savedRule?.isWhitelist == true
        val targetOptions = buildTargetOptions(item)
        val targetBoxes = createTargetBoxes(
            container = dialogBinding.freezeTargetsContainer,
            options = targetOptions,
            selected = savedRule?.freezeProcesses?.toSet().orEmpty().ifEmpty { setOf("ALL") }
        )
        val conditionBoxes = createConditionBoxes(
            container = dialogBinding.freezeConditionsContainer,
            selected = savedRule?.dontFreezeWhen?.toSet().orEmpty()
        )
        val childText = if (item.processEntries.isEmpty()) {
            getString(R.string.process_no_subprocess)
        } else {
            item.processEntries.joinToString(", ") { "${it.displayName}(${it.pid})" }
        }
        dialogBinding.packageValue.text = item.packageName
        dialogBinding.uidValue.text = item.uid.toString()
        dialogBinding.processCountValue.text = item.processCount.toString()
        dialogBinding.childrenValue.text = childText
        dialogBinding.memoryValue.text = formatBytes(item.memoryBytes)
        dialogBinding.cpuValue.text = String.format(Locale.US, "%.1f%%", item.cpuPercent)
        dialogBinding.freezeValue.text = buildFreezeText(item.isFrozen, item.freezeMode, item.frozenProcessCount, item.processCount)
        dialogBinding.freezeToggleButton.text = getString(
            if (item.isFrozen) R.string.process_action_unfreeze else R.string.process_action_freeze
        )
        applyWhitelistButtonStyle(dialogBinding.whitelistToggleButton, isWhitelist)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(item.appName)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.process_action_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedTargets = readSelectedTargets(targetBoxes)
            val selectedConditions = readSelectedConditions(conditionBoxes)
            FreezeListStore.saveRule(
                context = context,
                packageName = item.packageName,
                freezeProcesses = selectedTargets,
                dontFreezeWhen = selectedConditions,
                isWhitelist = isWhitelist
            )
            Toast.makeText(context, R.string.process_rule_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialogBinding.whitelistToggleButton.setOnClickListener {
            isWhitelist = !isWhitelist
            FreezeListStore.setWhitelist(context, item.packageName, isWhitelist)
            applyWhitelistButtonStyle(dialogBinding.whitelistToggleButton, isWhitelist)
            Toast.makeText(
                context,
                if (isWhitelist) R.string.process_whitelist_enabled else R.string.process_whitelist_disabled,
                Toast.LENGTH_SHORT
            ).show()
        }
        dialogBinding.freezeToggleButton.setOnClickListener {
            val selectedTargets = readSelectedTargets(targetBoxes).toSet()
            val success = ProcessInspector.toggleFreeze(item, selectedTargets)
            if (!success) {
                Toast.makeText(context, R.string.process_toggle_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(context, R.string.process_toggle_success, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            refreshProcessList()
        }
    }

    private fun buildFreezeText(
        isFrozen: Boolean,
        freezeMode: String,
        frozenProcessCount: Int,
        processCount: Int
    ): String {
        if (!isFrozen) {
            return getString(R.string.process_state_running)
        }
        val modeText = when (freezeMode) {
            "V2" -> getString(R.string.process_freeze_mode_placeholder)
            else -> getString(R.string.process_freeze_mode_system)
        }
        return "$modeText / ${getString(R.string.process_state_frozen)} ($frozenProcessCount/$processCount)"
    }

    private fun showProcessRulePreview(item: ProcessAppItem) {
        val context = context ?: return
        val whitelistSuffix = if (FreezeListStore.loadRule(context, item.packageName)?.isWhitelist == true) {
            " (${getString(R.string.process_state_whitelist)})"
        } else {
            ""
        }
        val cached = stateCache[item.packageName]
        if (cached == null) {
            Thread {
                val state = runCatching { ProcessInspector.inspectRuntimeState(item) }
                    .getOrDefault(AppRuntimeState(false, false, false))
                stateCache[item.packageName] = state
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    showStatePopup(item, buildStateText(context, state) + whitelistSuffix)
                }
            }.start()
            return
        }
        showStatePopup(item, buildStateText(context, cached) + whitelistSuffix)
    }

    private fun pollCachedStatesIfNeeded(items: List<ProcessAppItem>) {
        val context = context?.applicationContext ?: return
        if (items.isEmpty()) return
        val intervalMs = SettingsStore.getAppStatePollIntervalSeconds(context) * 1000L
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatePollAt < intervalMs) return
        if (!statePolling.compareAndSet(false, true)) return
        lastStatePollAt = now

        val freezeRuleTargets = items.filter { FreezeListStore.loadRule(context, it.packageName) != null }
        val targets = (freezeRuleTargets + items.filterNot { it.isFrozen })
            .distinctBy { it.packageName }
        if (targets.isEmpty()) {
            statePolling.set(false)
            return
        }
        Thread {
            try {
                val keep = targets.map { it.packageName }.toSet()
                stateCache.keys.toList().forEach { key ->
                    if (!keep.contains(key)) {
                        stateCache.remove(key)
                    }
                }
                val batch = nextStateBatch(targets)
                batch.forEach { item ->
                    val state = ProcessInspector.inspectRuntimeState(item)
                    stateCache[item.packageName] = state
                    RuntimeLogStore.appendDiagnostic(
                        source = "state",
                        message = "${item.packageName} ${buildStateText(context, state)}",
                        throttleKey = "state_poll_${item.packageName}",
                        throttleMs = 3000L,
                        category = RuntimeLogStore.LogCategory.ALL
                    )
                }
            } finally {
                statePolling.set(false)
            }
        }.start()
    }

    private fun nextStateBatch(targets: List<ProcessAppItem>): List<ProcessAppItem> {
        if (targets.size <= statePollBatchSize) {
            statePollCursor = 0
            return targets
        }
        if (statePollCursor >= targets.size) {
            statePollCursor = 0
        }
        val end = (statePollCursor + statePollBatchSize).coerceAtMost(targets.size)
        val batch = targets.subList(statePollCursor, end)
        statePollCursor = if (end >= targets.size) 0 else end
        return batch
    }

    private fun buildStateText(context: android.content.Context, state: AppRuntimeState?): String {
        if (state == null) {
            return context.getString(R.string.process_state_cache_empty)
        }
        val labels = buildList {
            if (state.isAudioActive) add(context.getString(R.string.process_state_audio))
            if (state.isNetworkActive) add(context.getString(R.string.process_state_network))
            if (state.isVisible) add(context.getString(R.string.process_state_visible))
        }
        return if (labels.isEmpty()) {
            context.getString(R.string.process_state_idle)
        } else {
            labels.joinToString(" , ")
        }
    }

    private fun showStatePopup(item: ProcessAppItem, text: String) {
        val activity = activity ?: return
        val popupBinding = ViewStatePopupBinding.inflate(layoutInflater)
        popupBinding.statePopupIcon.setImageDrawable(
            item.icon ?: requireContext().packageManager.defaultActivityIcon
        )
        popupBinding.statePopupTitle.text = item.appName
        popupBinding.statePopupMessage.text = text

        mainHandler.removeCallbacks(dismissStatePopupRunnable)
        statePopupWindow?.dismiss()
        statePopupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = false
            elevation = resources.displayMetrics.density * 12f
            showAtLocation(
                activity.window.decorView,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                0,
                resources.getDimensionPixelSize(R.dimen.state_popup_offset_top)
            )
        }
        mainHandler.postDelayed(dismissStatePopupRunnable, 1800L)
    }

    private fun buildTargetOptions(item: ProcessAppItem): List<String> {
        val names = item.processEntries.map { it.displayName }.distinct()
        return buildList {
            add("ALL")
            addAll(names)
        }
    }

    private fun createTargetBoxes(
        container: LinearLayout,
        options: List<String>,
        selected: Set<String>
    ): List<MaterialCheckBox> {
        container.removeAllViews()
        val boxes = options.map { option ->
            MaterialCheckBox(container.context).apply {
                text = option
                isChecked = if (selected.isEmpty()) option == "ALL" else selected.contains(option)
            }
        }
        boxes.forEach { box ->
            box.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) {
                    if (boxes.none { it.isChecked }) {
                        boxes.firstOrNull { it.text == "ALL" }?.isChecked = true
                    }
                    return@setOnCheckedChangeListener
                }
                if (box.text == "ALL") {
                    boxes.filter { it !== box }.forEach { it.isChecked = false }
                } else {
                    boxes.firstOrNull { it.text == "ALL" }?.isChecked = false
                }
            }
            container.addView(box)
        }
        return boxes
    }

    private fun createConditionBoxes(
        container: LinearLayout,
        selected: Set<String>
    ): List<MaterialCheckBox> {
        container.removeAllViews()
        return freezeConditionOptions.map { option ->
            MaterialCheckBox(container.context).apply {
                text = option
                isChecked = selected.contains(option)
                container.addView(this)
            }
        }
    }

    private fun readSelectedTargets(boxes: List<MaterialCheckBox>): List<String> {
        val selected = boxes.filter { it.isChecked }.map { it.text.toString() }
        return if (selected.isEmpty()) listOf("ALL") else selected
    }

    private fun readSelectedConditions(boxes: List<MaterialCheckBox>): List<String> {
        return boxes.filter { it.isChecked }.map { it.text.toString() }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun applyWhitelistButtonStyle(button: MaterialButton, enabled: Boolean) {
        val context = button.context
        button.text = getString(R.string.process_action_whitelist)
        button.alpha = if (enabled) 1f else 1f
        if (enabled) {
            button.setBackgroundColor(context.getColor(android.R.color.holo_green_dark))
            button.setTextColor(context.getColor(android.R.color.white))
        } else {
            button.setBackgroundColor(context.getColor(android.R.color.transparent))
            button.setTextColor(context.getColor(R.color.text_secondary))
        }
    }

    private fun sortItems(items: List<ProcessAppItem>): List<ProcessAppItem> {
        val context = context ?: return items
        return when (SettingsStore.getProcessSortMode(context)) {
            SettingsStore.ProcessSortMode.MEMORY -> items.sortedWith(
                compareByDescending<ProcessAppItem> { it.isFrozen }
                    .thenByDescending { it.memoryBytes }
                    .thenByDescending { it.cpuPercent }
                    .thenBy { it.appName.lowercase(Locale.getDefault()) }
            )

            SettingsStore.ProcessSortMode.CPU -> items.sortedWith(
                compareByDescending<ProcessAppItem> { it.isFrozen }
                    .thenByDescending { it.cpuPercent }
                    .thenByDescending { it.memoryBytes }
                    .thenBy { it.appName.lowercase(Locale.getDefault()) }
            )
        }
    }
}

package cn.himpqblog.slience.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cn.himpqblog.slience.R
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.databinding.DialogProcessDetailBinding
import cn.himpqblog.slience.databinding.FragmentProcessBinding
import cn.himpqblog.slience.hook.RuntimeLogStore
import cn.himpqblog.slience.process.AppRuntimeState
import cn.himpqblog.slience.process.CpuSnapshot
import cn.himpqblog.slience.process.ProcessAppItem
import cn.himpqblog.slience.process.ProcessInspector
import cn.himpqblog.slience.process.ProcessListAdapter
import cn.himpqblog.slience.root.Permission
import cn.himpqblog.slience.settings.SettingsStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProcessFragment : Fragment() {
    companion object {
        private const val SELF_PACKAGE = "cn.himpqblog.slience"
    }

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
            val ctx = context
            val intervalMs = if (ctx == null) {
                3000L
            } else {
                SettingsStore.getProcessRefreshIntervalSeconds(ctx) * 1000L
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
        binding.forcePollButton.setOnClickListener {
            val ctx = context?.applicationContext ?: return@setOnClickListener
            runCatching {
                val token = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
                val written = FreezeListStore.writeForcePollTriggerToken(token)
                RuntimeLogStore.appendDiagnostic(
                    source = "process",
                    message = "force poll trigger written token=$token write=$written",
                    throttleKey = "force_poll_trigger_ui",
                    throttleMs = 0L
                )
                Toast.makeText(ctx, "已触发后台轮询", Toast.LENGTH_SHORT).show()
            }.onFailure {
                RuntimeLogStore.appendDiagnostic(
                    source = "process",
                    message = "force poll trigger failed: ${it.message ?: it.javaClass.simpleName}",
                    throttleKey = "force_poll_trigger_fail",
                    throttleMs = 0L,
                    category = RuntimeLogStore.LogCategory.ERROR
                )
                Toast.makeText(ctx, "触发失败", Toast.LENGTH_SHORT).show()
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
                val ctx = context?.applicationContext ?: return@Thread
                val result = ProcessInspector.collect(ctx, lastSnapshot)
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
            } catch (t: Throwable) {
                RuntimeLogStore.appendDiagnostic(
                    source = "process",
                    message = "refreshProcessList failed: ${t.javaClass.name}: ${t.message ?: "no-message"}",
                    throttleKey = "process_refresh_exception",
                    throttleMs = 0L,
                    category = RuntimeLogStore.LogCategory.ERROR
                )
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    binding.processStatusValue.text = "进程刷新失败"
                }
            } finally {
                refreshing.set(false)
            }
        }.start()
    }

    private fun showProcessSettingsDialog(item: ProcessAppItem) {
        val ctx = context ?: return
        val dialogBinding = DialogProcessDetailBinding.inflate(layoutInflater)
        val savedRule = FreezeListStore.loadRule(ctx, item.packageName)
        val isSelfPackage = item.packageName == SELF_PACKAGE
        var isWhitelist = isSelfPackage || savedRule?.isWhitelist == true
        val targetOptions = buildTargetOptions(item)
        val targetBoxes = createTargetBoxes(
            container = dialogBinding.freezeTargetsContainer,
            options = targetOptions,
            selected = if (isSelfPackage) {
                setOf("ALL")
            } else {
                savedRule?.freezeProcesses?.toSet().orEmpty().ifEmpty { setOf("ALL") }
            }
        )
        val conditionBoxes = createConditionBoxes(
            container = dialogBinding.freezeConditionsContainer,
            selected = if (isSelfPackage) emptySet() else savedRule?.dontFreezeWhen?.toSet().orEmpty()
        )
        val childText = if (item.processEntries.isEmpty()) {
            getString(R.string.process_no_subprocess)
        } else {
            item.processEntries.joinToString(", ") {
                val frozenTag = if (it.isFrozen) "(已冻结)" else ""
                "${it.displayName}(${it.pid})$frozenTag"
            }
        }
        dialogBinding.packageValue.text = item.packageName
        dialogBinding.uidValue.text = item.uid.toString()
        dialogBinding.processCountValue.text = item.processCount.toString()
        dialogBinding.childrenValue.text = childText
        dialogBinding.memoryValue.text = formatBytes(item.memoryBytes)
        dialogBinding.cpuValue.text = String.format(Locale.US, "%.1f%%", item.cpuPercent)
        dialogBinding.freezeValue.text = buildFreezeText(
            item.isFrozen,
            item.freezeMode,
            item.frozenProcessCount,
            item.processCount
        )
        dialogBinding.freezeToggleButton.text = getString(
            if (item.isFrozen) R.string.process_action_unfreeze else R.string.process_action_freeze
        )
        applyWhitelistButtonStyle(dialogBinding.whitelistToggleButton, isWhitelist)
        if (isSelfPackage) {
            targetBoxes.forEach {
                it.isChecked = it.text.toString() == "ALL"
                it.isEnabled = false
            }
            conditionBoxes.forEach {
                it.isChecked = false
                it.isEnabled = false
            }
            dialogBinding.whitelistToggleButton.isEnabled = false
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(item.appName)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.process_action_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedTargets = readSelectedTargets(targetBoxes)
            val selectedConditions = readSelectedConditions(conditionBoxes)
            FreezeListStore.saveRule(
                context = ctx,
                packageName = item.packageName,
                freezeProcesses = selectedTargets,
                dontFreezeWhen = selectedConditions,
                isWhitelist = isWhitelist
            )
            Toast.makeText(ctx, R.string.process_rule_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialogBinding.whitelistToggleButton.setOnClickListener {
            if (isSelfPackage) {
                Toast.makeText(ctx, R.string.process_self_whitelist_fixed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isWhitelist = !isWhitelist
            FreezeListStore.setWhitelist(ctx, item.packageName, isWhitelist)
            applyWhitelistButtonStyle(dialogBinding.whitelistToggleButton, isWhitelist)
            Toast.makeText(
                ctx,
                if (isWhitelist) R.string.process_whitelist_enabled else R.string.process_whitelist_disabled,
                Toast.LENGTH_SHORT
            ).show()
        }
        dialogBinding.freezeToggleButton.setOnClickListener {
            val selectedTargets = readSelectedTargets(targetBoxes).toSet()
            val success = ProcessInspector.toggleFreeze(item, selectedTargets)
            if (!success) {
                Toast.makeText(ctx, R.string.process_toggle_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(ctx, R.string.process_toggle_success, Toast.LENGTH_SHORT).show()
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
            "SYSTEM_V2" -> getString(R.string.process_freeze_mode_system)
            else -> getString(R.string.process_freeze_mode_system)
        }
        return "$modeText / ${getString(R.string.process_state_frozen)} ($frozenProcessCount/$processCount)"
    }

    private fun showProcessRulePreview(item: ProcessAppItem) {
        val ctx = context ?: return
        val whitelistSuffix = if (FreezeListStore.loadRule(ctx, item.packageName)?.isWhitelist == true) {
            " (${getString(R.string.process_state_whitelist)})"
        } else {
            ""
        }
        val appContext = ctx.applicationContext
        Thread {
            val state = runCatching { ProcessInspector.inspectRuntimeState(appContext, item) }
                .getOrElse { stateCache[item.packageName] ?: AppRuntimeState(false, false, false) }
            stateCache[item.packageName] = state
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                showStatePopup(item, buildStateText(ctx, state) + whitelistSuffix)
            }
        }.start()
    }

    private fun pollCachedStatesIfNeeded(items: List<ProcessAppItem>) {
        val ctx = context ?: return
        val appContext = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        val intervalMs = SettingsStore.getAppStatePollIntervalSeconds(ctx) * 1000L
        if (now - lastStatePollAt < intervalMs) {
            return
        }
        if (!statePolling.compareAndSet(false, true)) {
            return
        }
        lastStatePollAt = now

        val targets = nextStateBatch(items.filter { !it.isFrozen })
        if (targets.isEmpty()) {
            statePolling.set(false)
            return
        }

        Thread {
            try {
                val updates = LinkedHashMap<String, AppRuntimeState>(targets.size)
                targets.forEach { item ->
                    updates[item.packageName] = runCatching {
                        ProcessInspector.inspectRuntimeState(appContext, item)
                    }.getOrDefault(AppRuntimeState(false, false, false))
                }
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    updates.forEach { (pkg, state) ->
                        stateCache[pkg] = state
                    }
                    adapter.notifyDataSetChanged()
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
            if (state.isForeground) {
                add(context.getString(R.string.process_state_foreground))
            } else if (state.isVisible) {
                add(context.getString(R.string.process_state_visible))
            }
            if (state.isAudioActive) add(context.getString(R.string.process_state_audio))
            if (state.isNetworkActive) add(context.getString(R.string.process_state_network))
        }
        return if (labels.isEmpty()) {
            context.getString(R.string.process_state_idle)
        } else {
            labels.joinToString(" , ")
        }
    }

    private fun showStatePopup(item: ProcessAppItem, text: String) {
        val hostActivity = activity ?: return
        val popupView = layoutInflater.inflate(R.layout.view_process_state_popup, null, false)
        popupView.findViewById<ImageView>(R.id.processStatePopupIcon).setImageDrawable(
            item.icon ?: requireContext().packageManager.defaultActivityIcon
        )
        popupView.findViewById<TextView>(R.id.processStatePopupTitle).text = if (item.isFrozen) {
            "${item.appName}(已冻结)"
        } else {
            item.appName
        }
        popupView.findViewById<TextView>(R.id.processStatePopupMessage).text = text

        mainHandler.removeCallbacks(dismissStatePopupRunnable)
        statePopupWindow?.dismiss()
        statePopupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = false
            elevation = resources.displayMetrics.density * 12f
            showAtLocation(
                hostActivity.window.decorView,
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
        val ctx = button.context
        button.text = getString(R.string.process_action_whitelist)
        button.alpha = 1f
        if (enabled) {
            button.setBackgroundColor(ctx.getColor(android.R.color.holo_green_dark))
            button.setTextColor(ctx.getColor(android.R.color.white))
        } else {
            button.setBackgroundColor(ctx.getColor(android.R.color.transparent))
            button.setTextColor(ctx.getColor(R.color.text_secondary))
        }
    }

    private fun sortItems(items: List<ProcessAppItem>): List<ProcessAppItem> {
        val ctx = context ?: return items
        return when (SettingsStore.getProcessSortMode(ctx)) {
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

package cn.himpqblog.silence.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.himpqblog.silence.R
import cn.himpqblog.silence.daemon.DaemonPowerSnapshot
import cn.himpqblog.silence.daemon.SilenceDaemonClient
import cn.himpqblog.silence.daemon.SilenceDaemonManager
import cn.himpqblog.silence.daemon.toCpuCoreFrequencySnapshot
import cn.himpqblog.silence.databinding.DialogPerformanceModeDetailBinding
import cn.himpqblog.silence.databinding.FragmentPerformanceBinding
import cn.himpqblog.silence.databinding.ItemPerformanceRangeSliderBinding
import cn.himpqblog.silence.notification.PersistentStatusNotificationService
import cn.himpqblog.silence.perf.CpuCoreFrequency
import cn.himpqblog.silence.perf.CpuCoreFrequencySnapshot
import cn.himpqblog.silence.perf.CpuRangePreference
import cn.himpqblog.silence.perf.PerformanceConfigStore
import cn.himpqblog.silence.perf.PerformanceLogStore
import cn.himpqblog.silence.perf.PerformanceMode
import cn.himpqblog.silence.perf.PerformanceModePreference
import cn.himpqblog.silence.perf.PerformanceModePreferenceStore
import cn.himpqblog.silence.perf.PerformanceProfileStore
import cn.himpqblog.silence.perf.PerformanceRecordingScheduler
import cn.himpqblog.silence.settings.SettingsStore
import cn.himpqblog.silence.ui.widgets.PerformanceMonitorView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import java.util.ArrayDeque
import java.util.Locale

class PerformanceFragment : Fragment() {

    private data class RangeSliderRow(
        val coreIndex: Int,
        val frequenciesMhz: List<Int>,
        val slider: RangeSlider
    )

    private data class PowerInfo(
        val powerW: Float,
        val batteryLevelPercent: Int?,
        val voltageV: Float,
        val temperatureC: Float?
    ) {
        val formattedPower: String
            get() = String.format(Locale.US, "%.2fW", powerW)

        val formattedBattery: String
            get() = if (batteryLevelPercent == null) {
                ""
            } else {
                String.format(Locale.US, "%d%% %.2fv", batteryLevelPercent, voltageV)
            }

        val formattedTemperature: String
            get() = temperatureC?.let { String.format(Locale.US, "%.1f°C", it) }.orEmpty()
    }

    private var _binding: FragmentPerformanceBinding? = null
    private val binding: FragmentPerformanceBinding
        get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usageHistory = linkedMapOf<Int, ArrayDeque<Float>>()
    private var latestSnapshot: CpuCoreFrequencySnapshot? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshPerformancePage()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPerformanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 性能页的入口绑定都集中在这里：默认挡位、调度开关、记录开关和跳转入口。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        binding.performanceScheduleEnabledSwitch.isChecked = SettingsStore.isPerformanceScheduleEnabled(ctx)
        binding.performanceScheduleEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            val resolved = isChecked && !SettingsStore.isExternalPerformanceModeEnabled(ctx)
            SettingsStore.setPerformanceScheduleEnabled(ctx, resolved)
            if (isChecked != resolved) {
                binding.performanceScheduleEnabledSwitch.isChecked = resolved
                Toast.makeText(ctx, R.string.performance_schedule_conflict_hint, Toast.LENGTH_SHORT).show()
            }
        }
        binding.performanceEnabledSwitch.isChecked = SettingsStore.isPowerRecordEnabled(ctx)
        binding.performanceEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            val previous = SettingsStore.isPowerRecordEnabled(ctx)
            SettingsStore.setPowerRecordEnabled(ctx, isChecked)
            PerformanceRecordingScheduler.notifySettingsChanged(ctx)
            if (previous != isChecked) {
                PerformanceLogStore.recordEvent(
                    ctx,
                    eventName = "power_record_enabled_changed",
                    oldValue = previous.toString(),
                    newValue = isChecked.toString(),
                    detail = "performance_page"
                )
            }
        }
        val openRecords = {
            startActivity(Intent(ctx, PerformanceRecordsActivity::class.java))
        }
        binding.openPerformanceRecordsButton.setOnClickListener { openRecords() }
        binding.powerSummaryCard.setOnClickListener { openRecords() }
        binding.switchPerformanceProfileButton.setOnClickListener {
            showProfileSelector(ctx)
        }
        bindPerformanceModeButtons(ctx)
        refreshPerformanceSummary(ctx)
        refreshProfileSummary(ctx)
        refreshPerformancePage()
    }

    override fun onResume() {
        super.onResume()
        SilenceDaemonManager.setSamplingInterval(requireContext(), 1_000)
        mainHandler.removeCallbacks(pollRunnable)
        refreshPerformanceSummary(requireContext())
        refreshProfileSummary(requireContext())
        mainHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(pollRunnable)
        context?.let { SilenceDaemonManager.setSamplingInterval(it, 5_000) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(pollRunnable)
        _binding = null
    }

    // 四个挡位按钮共用一套绑定逻辑，点击切默认挡位，长按进详细参数配置。
    private fun bindPerformanceModeButtons(context: Context) {
        val buttons = linkedMapOf(
            PerformanceMode.POWER_SAVE to binding.modePowerSave,
            PerformanceMode.BALANCED to binding.modeBalanced,
            PerformanceMode.PERFORMANCE to binding.modePerformance,
            PerformanceMode.EXTREME to binding.modeExtreme
        )
        buttons.forEach { (mode, button) ->
            button.setOnClickListener {
                PerformanceConfigStore.setDefaultMode(context, mode)
                refreshPerformanceSummary(context)
                PersistentStatusNotificationService.requestImmediateRefresh(context)
            }
            button.setOnLongClickListener {
                showModePreferenceDialog(mode)
                true
            }
        }
        updateModeButtons(context, buttons, PerformanceConfigStore.readConfig(context).defaultMode)
    }

    // 页头摘要负责同步展示当前默认挡位和记录开关状态，避免页面信息分裂。
    private fun refreshPerformanceSummary(context: Context) {
        val config = PerformanceConfigStore.readConfig(context)
        binding.performanceDefaultSummary.text = getString(
            R.string.home_performance_default_summary,
            getString(config.defaultMode.labelRes)
        )
        updateModeButtons(
            context = context,
            modeButtons = linkedMapOf(
                PerformanceMode.POWER_SAVE to binding.modePowerSave,
                PerformanceMode.BALANCED to binding.modeBalanced,
                PerformanceMode.PERFORMANCE to binding.modePerformance,
                PerformanceMode.EXTREME to binding.modeExtreme
            ),
            selectedMode = config.defaultMode
        )
    }

    private fun refreshProfileSummary(context: Context) {
        val profile = PerformanceProfileStore.activeProfile(context)
        binding.activePerformanceProfileName.text = profile.name
        binding.activePerformanceProfileSummary.text = getString(
            R.string.performance_profile_summary_format,
            profile.description
        )
    }

    private fun showProfileSelector(context: Context) {
        val profiles = PerformanceProfileStore.listProfiles(context)
        if (profiles.isEmpty()) {
            Toast.makeText(context, R.string.performance_profile_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val activeId = PerformanceProfileStore.activeProfileId(context)
        val labels = profiles.map { profile ->
            "${profile.name}\n${profile.description}"
        }.toTypedArray()
        val selectedIndex = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.performance_profile_dialog_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, index ->
                val selected = profiles[index]
                if (PerformanceProfileStore.setActiveProfile(context, selected.id)) {
                    refreshPerformanceSummary(context)
                    refreshProfileSummary(context)
                    PersistentStatusNotificationService.requestImmediateRefresh(context)
                    Toast.makeText(
                        context,
                        getString(R.string.performance_profile_switch_saved, selected.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
            }
            .setNeutralButton(R.string.performance_profile_duplicate) { _, _ ->
                showDuplicateProfileDialog(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDuplicateProfileDialog(context: Context) {
        val input = TextInputEditText(context).apply {
            hint = getString(R.string.performance_profile_name_hint)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.performance_profile_duplicate_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val profile = PerformanceProfileStore.duplicateActiveProfile(
                    context,
                    input.text?.toString().orEmpty()
                )
                if (profile == null) {
                    Toast.makeText(context, R.string.performance_profile_name_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        getString(R.string.performance_profile_duplicate_saved, profile.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // 这里只做 UI 选中态同步，不直接修改任何持久化配置。
    private fun updateModeButtons(
        context: Context,
        modeButtons: Map<PerformanceMode, MaterialButton>,
        selectedMode: PerformanceMode
    ) {
        modeButtons.forEach { (mode, button) ->
            val selected = mode == selectedMode
            button.isChecked = selected
            button.strokeWidth = if (selected) 0 else 1
            button.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.brand_primary_dim else R.color.surface_card_soft
                )
            )
            button.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.text_primary else R.color.text_secondary
                )
            )
        }
    }

    // 每秒刷新一次性能页：更新每核使用率图、功耗卡片，并按设置决定是否追加样本。
    private fun refreshPerformancePage() {
        val ctx = context?.applicationContext ?: return
        Thread {
            val daemonSnapshot = SilenceDaemonClient.getSnapshot(ctx)
            val snapshot = daemonSnapshot?.toCpuCoreFrequencySnapshot()
            val currentMode = PerformanceConfigStore.resolveModeForPackage(
                ctx,
                daemonSnapshot?.foregroundPackageName
            )
            val sample = snapshot?.let { buildSample(it, currentMode) }
            val powerInfo = daemonSnapshot?.power?.toPowerInfo()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                latestSnapshot = snapshot
                binding.performanceMonitorView.render(sample)
                binding.powerSummaryValue.text =
                    powerInfo?.formattedPower ?: getString(R.string.performance_power_unknown)
                binding.batterySummaryValue.text =
                    powerInfo?.formattedBattery ?: getString(R.string.performance_battery_unknown)
                binding.temperatureSummaryValue.text =
                    powerInfo?.formattedTemperature ?: getString(R.string.performance_temperature_unknown)
            }
        }.start()
    }

    // 把 daemon 返回的真实每核使用率压进短历史，供核心使用率图复用。
    private fun buildSample(
        snapshot: CpuCoreFrequencySnapshot,
        currentMode: PerformanceMode
    ): PerformanceMonitorView.Sample {
        val cores = snapshot.cores
            .sortedBy { it.coreIndex }
            .take(8)
            .map { core ->
                val currentUsage = core.usagePercent?.coerceIn(0f, 100f)
                val history = usageHistory.getOrPut(core.coreIndex) { ArrayDeque() }
                if (currentUsage != null) {
                    history.addLast(currentUsage)
                    while (history.size > 5) {
                        history.removeFirst()
                    }
                }
                PerformanceMonitorView.CoreHistory(
                    coreIndex = core.coreIndex,
                    currentUsagePercent = currentUsage,
                    historyUsagePercent = history.toList()
                )
            }
        return PerformanceMonitorView.Sample(
            currentMode = currentMode,
            cores = cores
        )
    }

    // 长按挡位按钮弹出详细配置，当前编辑的是该挡位的前后台亲和性和频率范围。
    private fun showModePreferenceDialog(mode: PerformanceMode) {
        val ctx = context ?: return
        val snapshot = latestSnapshot ?: run {
            Toast.makeText(ctx, R.string.performance_daemon_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val preference = PerformanceModePreferenceStore.readModePreference(ctx, mode, snapshot)
        val dialogBinding = DialogPerformanceModeDetailBinding.inflate(layoutInflater)
        dialogBinding.foregroundAffinityInput.setText(preference.foregroundAffinity)
        dialogBinding.backgroundAffinityInput.setText(preference.backgroundAffinity)
        val normalRows = bindRangeSliders(
            container = dialogBinding.normalRangesContainer,
            snapshot = snapshot,
            ranges = preference.normalRanges
        )
        val burstRows = bindRangeSliders(
            container = dialogBinding.burstRangesContainer,
            snapshot = snapshot,
            ranges = preference.burstRanges
        )

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.performance_pref_title_format, getString(mode.labelRes)))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.process_action_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val normalRanges = collectSliderRanges(normalRows)
            val burstRanges = collectSliderRanges(burstRows)
            if (normalRanges.isEmpty() || burstRanges.isEmpty()) {
                Toast.makeText(ctx, R.string.performance_pref_parse_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updated = PerformanceModePreference(
                foregroundAffinity = dialogBinding.foregroundAffinityInput.text?.toString()?.trim().orEmpty(),
                backgroundAffinity = dialogBinding.backgroundAffinityInput.text?.toString()?.trim().orEmpty(),
                normalRanges = normalRanges,
                burstRanges = burstRanges
            )
            PerformanceModePreferenceStore.saveModePreference(ctx, mode, updated)
            Toast.makeText(ctx, R.string.performance_pref_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    // 每个核心一行滑条，频点直接来自系统可用频率列表，避免手填出非法值。
    private fun bindRangeSliders(
        container: LinearLayout,
        snapshot: CpuCoreFrequencySnapshot?,
        ranges: Map<Int, CpuRangePreference>
    ): List<RangeSliderRow> {
        container.removeAllViews()
        val rows = ArrayList<RangeSliderRow>()
        val cores = snapshot?.cores?.sortedBy { it.coreIndex }.orEmpty()
        val allCoreIndexes = (cores.map { it.coreIndex } + ranges.keys).distinct().sorted()
        allCoreIndexes.forEach { coreIndex ->
            val core = cores.firstOrNull { it.coreIndex == coreIndex }
            val frequencies = resolveSliderFrequencies(core, ranges[coreIndex])
            if (frequencies.isEmpty()) {
                return@forEach
            }
            val range = ranges[coreIndex] ?: CpuRangePreference(
                minMhz = frequencies.first(),
                maxMhz = frequencies.last()
            )
            val itemBinding = ItemPerformanceRangeSliderBinding.inflate(layoutInflater, container, false)
            itemBinding.coreLabel.text = "C$coreIndex"
            itemBinding.rangeSlider.valueFrom = 0f
            itemBinding.rangeSlider.valueTo = frequencies.lastIndex.toFloat()
            itemBinding.rangeSlider.stepSize = 1f
            val startIndex = frequencies.indexOfNearest(range.minMhz)
            val endIndex = frequencies.indexOfNearest(range.maxMhz).coerceAtLeast(startIndex)
            itemBinding.rangeSlider.values = listOf(startIndex.toFloat(), endIndex.toFloat())
            updateSliderSummary(itemBinding, frequencies)
            itemBinding.rangeSlider.addOnChangeListener { _, _, _ ->
                updateSliderSummary(itemBinding, frequencies)
            }
            container.addView(itemBinding.root)
            rows += RangeSliderRow(
                coreIndex = coreIndex,
                frequenciesMhz = frequencies,
                slider = itemBinding.rangeSlider
            )
        }
        return rows
    }

    // 滑条标签始终展示当前选择的最小值和最大值，便于快速确认范围。
    private fun updateSliderSummary(
        itemBinding: ItemPerformanceRangeSliderBinding,
        frequencies: List<Int>
    ) {
        val values = itemBinding.rangeSlider.values
        val minIndex = values.getOrNull(0)?.toInt() ?: 0
        val maxIndex = values.getOrNull(1)?.toInt() ?: minIndex
        val minValue = frequencies.getOrElse(minIndex) { frequencies.first() }
        val maxValue = frequencies.getOrElse(maxIndex) { frequencies.last() }
        itemBinding.rangeValue.text = "${minValue}-${maxValue}MHz"
    }

    // 保存前把 UI 滑条状态回收成结构化频率范围，供 JSON 和日志共用。
    private fun collectSliderRanges(rows: List<RangeSliderRow>): Map<Int, CpuRangePreference> {
        return LinkedHashMap<Int, CpuRangePreference>().apply {
            rows.forEach { row ->
                val values = row.slider.values
                val minIndex = values.getOrNull(0)?.toInt() ?: 0
                val maxIndex = values.getOrNull(1)?.toInt() ?: minIndex
                put(
                    row.coreIndex,
                    CpuRangePreference(
                        minMhz = row.frequenciesMhz.getOrElse(minIndex) { row.frequenciesMhz.first() },
                        maxMhz = row.frequenciesMhz.getOrElse(maxIndex) { row.frequenciesMhz.last() }
                    )
                )
            }
        }
    }

    // 优先使用系统提供的离散频点，只有缺失时才回退到当前最小/当前/最大值兜底。
    private fun resolveSliderFrequencies(
        core: CpuCoreFrequency?,
        storedRange: CpuRangePreference?
    ): List<Int> {
        val candidates = core?.availableFrequenciesMhz.orEmpty()
        if (candidates.isNotEmpty()) {
            return candidates.distinct().sorted()
        }
        return listOfNotNull(
            storedRange?.minMhz,
            storedRange?.maxMhz,
            core?.minMhz,
            core?.currentMhz,
            core?.maxMhz
        ).distinct().sorted()
    }

    private fun List<Int>.indexOfNearest(target: Int): Int {
        if (isEmpty()) {
            return 0
        }
        return indices.minByOrNull { index -> kotlin.math.abs(this[index] - target) } ?: 0
    }

    // 功耗卡片只关心展示字段，所以这里把 daemon 快照转换成 UI 友好的格式。
    private fun DaemonPowerSnapshot.toPowerInfo(): PowerInfo? {
        if (!available) return null
        return PowerInfo(
            powerW = powerW,
            batteryLevelPercent = batteryLevelPercent,
            voltageV = voltageV,
            temperatureC = temperatureC
        )
    }
}

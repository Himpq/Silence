package cn.himpqblog.slience.ui

import android.Manifest
import android.os.Bundle
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import cn.himpqblog.slience.R
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.databinding.FragmentFeaturesBinding
import cn.himpqblog.slience.notification.PersistentStatusNotificationService
import cn.himpqblog.slience.perf.PerformanceExternalModeWriter
import cn.himpqblog.slience.perf.PerformanceLogStore
import cn.himpqblog.slience.perf.PerformanceRecordingScheduler
import cn.himpqblog.slience.settings.SettingsStore

class FeaturesFragment : Fragment() {

    private var _binding: FragmentFeaturesBinding? = null
    private val binding: FragmentFeaturesBinding
        get() = _binding!!
    private var suppressPersistentNotificationCallback = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val context = context ?: return@registerForActivityResult
        if (granted) {
            SettingsStore.setPersistentNotificationEnabled(context, true)
            PersistentStatusNotificationService.syncState(context)
            return@registerForActivityResult
        }

        suppressPersistentNotificationCallback = true
        binding.persistentNotificationSwitch.isChecked = false
        suppressPersistentNotificationCallback = false
        SettingsStore.setPersistentNotificationEnabled(context, false)
        PersistentStatusNotificationService.syncState(context)
        Toast.makeText(context, R.string.settings_notification_permission_denied, Toast.LENGTH_SHORT).show()
    }

    // 设置页本身不做业务计算，这里只负责安全创建并持有视图绑定。
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeaturesBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 设置页的所有开关、输入框和事件日志入口都在这里完成绑定。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        when (SettingsStore.getProcessSortMode(context)) {
            SettingsStore.ProcessSortMode.CPU -> binding.sortByCpu.isChecked = true
            SettingsStore.ProcessSortMode.MEMORY -> binding.sortByMemory.isChecked = true
        }
        binding.statePollIntervalInput.setText(
            SettingsStore.getAppStatePollIntervalSeconds(context).toString()
        )
        binding.statePollIntervalInput.hint = getString(cn.himpqblog.slience.R.string.settings_state_poll_hint)
        binding.processRefreshIntervalInput.setText(
            SettingsStore.getProcessRefreshIntervalSeconds(context).toString()
        )
        binding.hookPollIntervalInput.setText(
            SettingsStore.getHookPollIntervalSeconds(context).toString()
        )
        binding.hookEnabledSwitch.isChecked = SettingsStore.isHookEnabled(context)
        binding.freezeHookEnabledSwitch.isChecked = SettingsStore.isFreezeHookEnabled(context)
        binding.performanceHookEnabledSwitch.isChecked = SettingsStore.isPerformanceHookEnabled(context)
        binding.processDebugLogSwitch.isChecked = SettingsStore.isProcessDebugLogEnabled(context)
        binding.foregroundDebugLogSwitch.isChecked = SettingsStore.isForegroundDebugLogEnabled(context)
        binding.persistentNotificationSwitch.isChecked = SettingsStore.isPersistentNotificationEnabled(context)
        binding.powerRecordEnabledSwitch.isChecked = SettingsStore.isPowerRecordEnabled(context)
        binding.powerRecordPollIntervalInput.setText(
            SettingsStore.getPowerRecordPollIntervalSeconds(context).toString()
        )
        binding.externalModeEnabledSwitch.isChecked = SettingsStore.isExternalPerformanceModeEnabled(context)
        binding.externalModePathInput.setText(
            SettingsStore.getExternalPerformanceModePath(context)
        )
        binding.processRefreshIntervalInput.hint = getString(cn.himpqblog.slience.R.string.settings_process_refresh_hint)
        binding.sortModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.sortByMemory.id -> SettingsStore.ProcessSortMode.MEMORY
                else -> SettingsStore.ProcessSortMode.CPU
            }
            SettingsStore.setProcessSortMode(context, mode)
        }
        binding.statePollIntervalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: return
                SettingsStore.setAppStatePollIntervalSeconds(context, value)
            }
        })
        binding.processRefreshIntervalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: return
                SettingsStore.setProcessRefreshIntervalSeconds(context, value)
            }
        })
        binding.hookPollIntervalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: return
                SettingsStore.setHookPollIntervalSeconds(context, value)
                FreezeListStore.syncRuntimeMirror(context)
            }
        })
        binding.hookEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setHookEnabled(context, isChecked)
            FreezeListStore.syncRuntimeMirror(context)
        }
        binding.freezeHookEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setFreezeHookEnabled(context, isChecked)
            FreezeListStore.syncRuntimeMirror(context)
        }
        binding.performanceHookEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setPerformanceHookEnabled(context, isChecked)
            FreezeListStore.syncRuntimeMirror(context)
        }
        binding.processDebugLogSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setProcessDebugLogEnabled(context, isChecked)
            FreezeListStore.syncRuntimeMirror(context)
        }
        binding.foregroundDebugLogSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setForegroundDebugLogEnabled(context, isChecked)
        }
        binding.persistentNotificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressPersistentNotificationCallback) {
                return@setOnCheckedChangeListener
            }
            SettingsStore.setPersistentNotificationEnabled(context, isChecked)
            if (!isChecked) {
                PersistentStatusNotificationService.syncState(context)
                return@setOnCheckedChangeListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    context,
                    R.string.settings_notification_permission_required,
                    Toast.LENGTH_SHORT
                ).show()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnCheckedChangeListener
            }
            PersistentStatusNotificationService.syncState(context)
        }
        binding.powerRecordEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            val previous = SettingsStore.isPowerRecordEnabled(context)
            SettingsStore.setPowerRecordEnabled(context, isChecked)
            PerformanceRecordingScheduler.notifySettingsChanged(context)
            if (previous != isChecked) {
                PerformanceLogStore.recordEvent(
                    context,
                    eventName = "power_record_enabled_changed",
                    oldValue = previous.toString(),
                    newValue = isChecked.toString()
                )
            }
        }
        binding.powerRecordPollIntervalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: return
                val previous = SettingsStore.getPowerRecordPollIntervalSeconds(context)
                SettingsStore.setPowerRecordPollIntervalSeconds(context, value)
                PerformanceRecordingScheduler.notifySettingsChanged(context)
                if (previous != value) {
                    PerformanceLogStore.recordEvent(
                        context,
                        eventName = "power_record_interval_changed",
                        oldValue = previous.toString(),
                        newValue = value.toString()
                    )
                }
            }
        })
        binding.externalModeEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            val path = binding.externalModePathInput.text?.toString()?.trim().orEmpty()
            val previous = SettingsStore.isExternalPerformanceModeEnabled(context)
            val resolved = isChecked && path.isNotBlank()
            SettingsStore.setExternalPerformanceModeEnabled(context, resolved)
            if (resolved && SettingsStore.isPerformanceScheduleEnabled(context)) {
                SettingsStore.setPerformanceScheduleEnabled(context, false)
            }
            if (resolved) {
                PerformanceExternalModeWriter.syncCurrentMode(context)
            } else {
                PerformanceExternalModeWriter.resetCache()
            }
            if (previous != resolved) {
                PerformanceLogStore.recordEvent(
                    context,
                    eventName = "external_mode_enabled_changed",
                    oldValue = previous.toString(),
                    newValue = resolved.toString(),
                    detail = if (path.isBlank()) "path_empty_auto_disable" else null
                )
            }
            if (isChecked != resolved) {
                binding.externalModeEnabledSwitch.isChecked = resolved
            }
        }
        binding.externalModePathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val previous = SettingsStore.getExternalPerformanceModePath(context)
                val newPath = s?.toString()?.trim().orEmpty()
                if (previous == newPath) {
                    return
                }
                SettingsStore.setExternalPerformanceModePath(context, newPath)
                if (newPath.isBlank()) {
                    if (SettingsStore.isExternalPerformanceModeEnabled(context)) {
                        SettingsStore.setExternalPerformanceModeEnabled(context, false)
                        binding.externalModeEnabledSwitch.isChecked = false
                    }
                    PerformanceExternalModeWriter.resetCache()
                } else if (SettingsStore.isExternalPerformanceModeEnabled(context)) {
                    PerformanceExternalModeWriter.resetCache()
                    PerformanceExternalModeWriter.syncCurrentMode(context)
                }
                PerformanceLogStore.recordEvent(
                    context,
                    eventName = "external_mode_path_changed",
                    oldValue = previous.ifBlank { "<empty>" },
                    newValue = newPath.ifBlank { "<empty>" }
                )
            }
        })

        if (binding.persistentNotificationSwitch.isChecked &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PersistentStatusNotificationService.hasNotificationPermission(context)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 及时释放 binding，避免设置页反复切换后持有旧视图。
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

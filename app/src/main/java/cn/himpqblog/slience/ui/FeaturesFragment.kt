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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeaturesBinding.inflate(inflater, container, false)
        return binding.root
    }

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
        binding.processDebugLogSwitch.isChecked = SettingsStore.isProcessDebugLogEnabled(context)
        binding.foregroundDebugLogSwitch.isChecked = SettingsStore.isForegroundDebugLogEnabled(context)
        binding.persistentNotificationSwitch.isChecked = SettingsStore.isPersistentNotificationEnabled(context)
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

        if (binding.persistentNotificationSwitch.isChecked &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PersistentStatusNotificationService.hasNotificationPermission(context)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

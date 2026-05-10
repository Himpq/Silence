package cn.himpqblog.slience.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.databinding.FragmentFeaturesBinding
import cn.himpqblog.slience.settings.SettingsStore

class FeaturesFragment : Fragment() {

    private var _binding: FragmentFeaturesBinding? = null
    private val binding: FragmentFeaturesBinding
        get() = _binding!!

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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

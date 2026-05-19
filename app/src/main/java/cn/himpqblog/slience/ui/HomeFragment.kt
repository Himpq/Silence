package cn.himpqblog.slience.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cn.himpqblog.slience.R
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.databinding.FragmentHomeBinding
import cn.himpqblog.slience.hook.RuntimeLogStore
import cn.himpqblog.slience.settings.SettingsStore
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding: FragmentHomeBinding
        get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshing = AtomicBoolean(false)
    private val hookStatusPollingStopped = AtomicBoolean(false)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshHookStatus()
            mainHandler.postDelayed(this, 4000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hookStatusPollingStopped.set(false)
        binding.hookStatusValue.text = getString(R.string.module_state_checking)
        val ctx = requireContext()
        binding.hookEnabledSwitch.isChecked = SettingsStore.isHookEnabled(ctx)
        binding.hookEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setHookEnabled(ctx, isChecked)
            FreezeListStore.syncRuntimeMirror(ctx)
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(pollRunnable)
        if (!hookStatusPollingStopped.get()) {
            mainHandler.post(pollRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(pollRunnable)
        _binding = null
    }

    private fun refreshHookStatus() {
        if (hookStatusPollingStopped.get()) {
            return
        }
        if (!refreshing.compareAndSet(false, true)) {
            return
        }
        Thread {
            try {
                RuntimeLogStore.refreshFromRuntime()
                val status = RuntimeLogStore.snapshotHookStatus()
                val stamp = LocalTime.now().format(timeFormatter)
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.hookStatusValue.text = "$status | $stamp"
                    }
                }
                if (status == "Hook debug events active" || status.contains("active", ignoreCase = true)) {
                    hookStatusPollingStopped.set(true)
                    mainHandler.removeCallbacks(pollRunnable)
                }
            } finally {
                refreshing.set(false)
            }
        }.start()
    }
}

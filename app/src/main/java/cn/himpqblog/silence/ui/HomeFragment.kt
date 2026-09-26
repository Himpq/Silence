package cn.himpqblog.silence.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.himpqblog.silence.R
import cn.himpqblog.silence.config.FreezeListStore
import cn.himpqblog.silence.daemon.DaemonConfigSynchronizer
import cn.himpqblog.silence.daemon.DaemonStatusStore
import cn.himpqblog.silence.daemon.SilenceDaemonManager
import cn.himpqblog.silence.databinding.FragmentHomeBinding
import cn.himpqblog.silence.hook.RuntimeLogStore
import cn.himpqblog.silence.settings.SettingsStore
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
    private var daemonStatusReceiverRegistered = false

    private val daemonStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DaemonStatusStore.ACTION_STATUS_CHANGED && _binding != null) {
                renderDaemonStatus()
            }
        }
    }

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
            DaemonConfigSynchronizer.submit(ctx)
            renderEnabledFeatures()
        }
        binding.daemonRetryButton.setOnClickListener {
            SilenceDaemonManager.requestStart(ctx)
            renderDaemonStatus()
        }
        renderDaemonStatus()
        renderEnabledFeatures()
    }

    override fun onStart() {
        super.onStart()
        if (!daemonStatusReceiverRegistered) {
            ContextCompat.registerReceiver(
                requireContext(),
                daemonStatusReceiver,
                IntentFilter(DaemonStatusStore.ACTION_STATUS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            daemonStatusReceiverRegistered = true
        }
        renderDaemonStatus()
    }

    override fun onStop() {
        if (daemonStatusReceiverRegistered) {
            runCatching { requireContext().unregisterReceiver(daemonStatusReceiver) }
            daemonStatusReceiverRegistered = false
        }
        super.onStop()
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

    private fun renderDaemonStatus() {
        if (_binding == null) return
        val status = DaemonStatusStore.read(requireContext())
        binding.homeDaemonStatusValue.text = when (status.code) {
            "connected" -> getString(R.string.home_daemon_status_connected, status.pid)
            "starting" -> status.summary
            else -> getString(R.string.home_daemon_status_failed)
        }
        binding.homeDaemonStatusDetail.text = buildString {
            append(status.summary)
            if (status.detail.isNotBlank()) {
                append("\n")
                append(status.detail)
            }
        }
    }

    private fun renderEnabledFeatures() {
        if (_binding == null) return
        val context = requireContext()
        val enabled = buildList {
            if (SettingsStore.isHookEnabled(context)) add(getString(R.string.home_enabled_item_hook))
            if (SettingsStore.isFreezeHookEnabled(context)) add(getString(R.string.home_enabled_item_freeze))
            if (SettingsStore.isPerformanceHookEnabled(context)) add(getString(R.string.home_enabled_item_performance))
            if (SettingsStore.isPersistentNotificationEnabled(context)) add(getString(R.string.home_enabled_item_notification))
            if (SettingsStore.isPowerRecordEnabled(context)) add(getString(R.string.home_enabled_item_record))
        }
        binding.homeEnabledValue.text = enabled.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
            ?: getString(R.string.home_enabled_empty)
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

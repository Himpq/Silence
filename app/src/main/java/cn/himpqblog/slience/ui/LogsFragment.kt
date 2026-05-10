package cn.himpqblog.slience.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import cn.himpqblog.slience.R
import cn.himpqblog.slience.databinding.FragmentLogsBinding
import cn.himpqblog.slience.hook.RuntimeLogStore
import cn.himpqblog.slience.settings.SettingsStore
import java.util.concurrent.atomic.AtomicBoolean

class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding: FragmentLogsBinding
        get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshing = AtomicBoolean(false)

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshLogs()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.logTextView.setTextIsSelectable(true)
        binding.logTextView.text = getString(R.string.logs_empty_hint)
        RuntimeLogStore.setRecordMode(currentLogMode())
        setupLogModeDropdown()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
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

    fun clearLogs() {
        RuntimeLogStore.clear()
        renderLogs(emptyList())
    }

    fun copyAllLogs() {
        val logs = RuntimeLogStore.snapshotLogs(currentLogMode())
        if (logs.isEmpty()) {
            Toast.makeText(requireContext(), R.string.logs_copy_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(requireContext(), R.string.logs_copy_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val text = logs.joinToString("\n")
        clipboard.setPrimaryClip(ClipData.newPlainText("Silence Logs", text))
        Toast.makeText(requireContext(), R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    fun forceRefresh() {
        refreshLogs()
    }

    private fun refreshLogs() {
        if (!refreshing.compareAndSet(false, true)) {
            return
        }
        val appContext = context?.applicationContext
        if (appContext == null) {
            refreshing.set(false)
            return
        }
        Thread {
            try {
                RuntimeLogStore.refreshFromRuntime()
                val mode = SettingsStore.getLogRecordMode(appContext)
                val logs = RuntimeLogStore.snapshotLogs(mode)
                activity?.runOnUiThread {
                    if (!isAdded || _binding == null) return@runOnUiThread
                    renderLogs(logs)
                }
            } finally {
                refreshing.set(false)
            }
        }.start()
    }

    private fun renderLogs(logs: List<String>) {
        val bindingRef = _binding ?: return
        if (logs.isEmpty()) {
            bindingRef.logTextView.text = getString(R.string.logs_empty_hint)
            return
        }
        val previousScrollY = bindingRef.logScrollView.scrollY
        bindingRef.logTextView.text = logs.joinToString("\n")
        bindingRef.logScrollView.post {
            if (_binding == null) return@post
            val b = _binding ?: return@post
            val maxScroll = (b.logTextView.height - b.logScrollView.height).coerceAtLeast(0)
            b.logScrollView.scrollTo(0, previousScrollY.coerceAtMost(maxScroll))
        }
    }

    private fun setupLogModeDropdown() {
        val labels = listOf(
            getString(R.string.logs_mode_error),
            getString(R.string.logs_mode_log),
            getString(R.string.logs_mode_all)
        )
        binding.logModeDropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        )
        binding.logModeDropdown.setText(labelForMode(currentLogMode()), false)
        binding.logModeDropdown.setOnItemClickListener { _, _, position, _ ->
            val mode = when (position) {
                0 -> SettingsStore.LogRecordMode.ERROR
                1 -> SettingsStore.LogRecordMode.LOG
                else -> SettingsStore.LogRecordMode.ALL
            }
            SettingsStore.setLogRecordMode(requireContext(), mode)
            RuntimeLogStore.setRecordMode(mode)
            renderLogs(RuntimeLogStore.snapshotLogs(mode))
        }
    }

    private fun currentLogMode(): SettingsStore.LogRecordMode {
        return SettingsStore.getLogRecordMode(requireContext())
    }

    private fun labelForMode(mode: SettingsStore.LogRecordMode): String {
        return when (mode) {
            SettingsStore.LogRecordMode.ERROR -> getString(R.string.logs_mode_error)
            SettingsStore.LogRecordMode.LOG -> getString(R.string.logs_mode_log)
            SettingsStore.LogRecordMode.ALL -> getString(R.string.logs_mode_all)
        }
    }
}

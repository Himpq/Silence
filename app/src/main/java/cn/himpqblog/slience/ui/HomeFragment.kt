package cn.himpqblog.slience.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cn.himpqblog.slience.R
import cn.himpqblog.slience.databinding.FragmentHomeBinding
import cn.himpqblog.slience.hook.RuntimeLogStore
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding: FragmentHomeBinding
        get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshing = AtomicBoolean(false)
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
        binding.hookStatusValue.text = getString(R.string.module_state_checking)
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

    private fun refreshHookStatus() {
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
            } finally {
                refreshing.set(false)
            }
        }.start()
    }
}

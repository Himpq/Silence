package cn.himpqblog.slience.process

import android.graphics.drawable.Drawable

data class ProcessAppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val uid: Int,
    val processCount: Int,
    val frozenProcessCount: Int,
    val processNames: List<String>,
    val frozenProcessNames: Set<String>,
    val childProcessNames: List<String>,
    val processEntries: List<ProcessEntry>,
    val isFrozen: Boolean,
    val freezeMode: String,
    val memoryBytes: Long,
    val cpuPercent: Double
)

data class ProcessEntry(
    val pid: Int,
    val displayName: String,
    val isFrozen: Boolean
)

data class CpuSnapshot(
    val totalTicks: Long,
    val pidTicks: Map<Int, Long>
)

data class AppRuntimeState(
    val isAudioActive: Boolean,
    val isNetworkActive: Boolean,
    val isVisible: Boolean
)

data class ProcessCollectResult(
    val items: List<ProcessAppItem>,
    val snapshot: CpuSnapshot?,
    val errorMessage: String? = null
)

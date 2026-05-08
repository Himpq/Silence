package cn.himpqblog.slience.hook

@Suppress("unused")
class Hook {

    companion object {
        const val MODERN_API_MIN = 100
        const val MODERN_API_TARGET = 101
    }

    fun initPlaceholder() {
        // Placeholder for the LSPosed modern entry.
        //
        // The previous implementation targeted a mismatched libxposed API and
        // caused Kotlin compilation failures. Keep this class as a stable
        // marker until the exact official modern API source/version is chosen.
        //
        // Current entry layout:
        // - assets/xposed_init -> HookLegacy
        // - META-INF/xposed/java_init.list -> Hook
    }
}

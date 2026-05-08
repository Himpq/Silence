package cn.himpqblog.slience

import android.os.Build
import android.os.Bundle
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import cn.himpqblog.slience.databinding.ActivityMainBinding
import cn.himpqblog.slience.ui.FeaturesFragment
import cn.himpqblog.slience.ui.HomeFragment
import cn.himpqblog.slience.ui.LogsFragment
import cn.himpqblog.slience.ui.ProcessFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTab: Int = 0

    private val pageTitles by lazy {
        listOf(
            getString(R.string.page_home),
            getString(R.string.page_process),
            getString(R.string.page_logs),
            getString(R.string.page_features)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (binding.topToolbar.menu.size() == 0) {
            binding.topToolbar.inflateMenu(R.menu.menu_top_actions)
        }
        window.statusBarColor = ContextCompat.getColor(this, R.color.surface_panel)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.surface_panel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        applyWindowInsets()
        setupToolbarActions()
        setupBottomNavigation()

        if (savedInstanceState == null) {
            switchToTab(0)
            binding.bottomNav.selectedItemId = R.id.nav_home
        } else {
            currentTab = tabFromMenuId(binding.bottomNav.selectedItemId)
            applyTopBarState(currentTab)
        }
    }

    private fun applyWindowInsets() {
        val toolbarBaseHeight = resources.getDimensionPixelSize(R.dimen.top_bar_height)
        val bottomNavBaseHeight = resources.getDimensionPixelSize(R.dimen.bottom_nav_height)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.topToolbar.updatePadding(top = systemBars.top)
            binding.topToolbar.updateLayoutParams {
                height = toolbarBaseHeight + systemBars.top
            }

            binding.bottomNav.updatePadding(bottom = systemBars.bottom)
            binding.bottomNav.updateLayoutParams {
                height = bottomNavBaseHeight + systemBars.bottom
            }

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val target = tabFromMenuId(item.itemId)
            if (target != currentTab) {
                switchToTab(target)
            }
            true
        }
    }

    private fun setupToolbarActions() {
        val tint = ContextCompat.getColor(this, R.color.text_primary)
        for (i in 0 until binding.topToolbar.menu.size()) {
            binding.topToolbar.menu.getItem(i).icon?.setTint(tint)
        }

        binding.topToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_logs -> {
                    currentLogsFragment()?.clearLogs()
                    true
                }

                R.id.action_copy_logs -> {
                    currentLogsFragment()?.copyAllLogs()
                    true
                }

                else -> false
            }
        }
    }

    private fun switchToTab(index: Int) {
        currentTab = index
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.contentContainer, createTabFragment(index), tagFor(index))
            .commit()

        applyTopBarState(index)
    }

    private fun applyTopBarState(index: Int) {
        binding.pageTitle.text = pageTitles.getOrElse(index) { getString(R.string.page_home) }
        binding.topToolbar.menu.findItem(R.id.action_clear_logs)?.isVisible = index == 2
        binding.topToolbar.menu.findItem(R.id.action_copy_logs)?.isVisible = index == 2
        binding.topToolbar.post {
            val menuView = (0 until binding.topToolbar.childCount)
                .map { binding.topToolbar.getChildAt(it) }
                .filterIsInstance<ActionMenuView>()
                .firstOrNull()
            menuView?.translationY = if (index == 2) {
                -resources.displayMetrics.density * 2f
            } else {
                0f
            }
        }
    }

    private fun currentLogsFragment(): LogsFragment? {
        return supportFragmentManager.findFragmentById(R.id.contentContainer) as? LogsFragment
    }

    private fun createTabFragment(index: Int): Fragment {
        return when (index) {
            0 -> HomeFragment()
            1 -> ProcessFragment()
            2 -> LogsFragment()
            3 -> FeaturesFragment()
            else -> HomeFragment()
        }
    }

    private fun tabFromMenuId(menuId: Int): Int {
        return when (menuId) {
            R.id.nav_home -> 0
            R.id.nav_process -> 1
            R.id.nav_logs -> 2
            R.id.nav_features -> 3
            else -> 0
        }
    }

    private fun tagFor(index: Int): String {
        return when (index) {
            0 -> "tab_home"
            1 -> "tab_process"
            2 -> "tab_logs"
            3 -> "tab_features"
            else -> "tab_home"
        }
    }
}

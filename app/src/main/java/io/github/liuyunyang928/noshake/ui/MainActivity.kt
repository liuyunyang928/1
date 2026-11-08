package io.github.liuyunyang928.noshake.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.liuyunyang928.noshake.BuildConfig
import io.github.liuyunyang928.noshake.R
import io.github.liuyunyang928.noshake.core.ConfigStore
import io.github.liuyunyang928.noshake.core.Contract
import io.github.liuyunyang928.noshake.databinding.ActivityMainBinding
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理界面：总开关、LSPosed 状态、应用列表（搜索 / 筛选 / 开关）。
 *
 * 配置读写全部通过 [ConfigStore] 完成，界面内不缓存任何配置副本，
 * 因此 Activity 重建、回到前台时都能拿到最新状态。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: ConfigStore
    private lateinit var appRepository: AppRepository
    private lateinit var appAdapter: AppListAdapter

    /** PackageManager 返回的全部可启动应用（不含拦截状态），只在主线程访问。 */
    private var installedApps: List<InstalledApp> = emptyList()

    /** 应用列表是否已完成首次加载，用于区分「加载中」与「没有可管理的应用」。 */
    private var appsLoaded = false

    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ConfigStore 直接读 SharedPreferences，Activity 重建后重新读取即可恢复开关状态。
        configStore = ConfigStore(this)
        appRepository = AppRepository(this)

        setupToolbar()
        setupStatusCard()
        setupSearch()
        setupFilter(savedInstanceState)
        setupAppList()

        updateBlockedSummary()
        updateLsposedStatus()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从 LSPosed 管理器或系统安装界面返回，回到前台时刷新外部状态。
        updateLsposedStatus()
        updateBlockedSummary()
    }

    // ------------------------------------------------------------------ 初始化

    private fun setupToolbar() {
        // 菜单在这里 inflate（而不是在布局里用 app:menu），避免依赖 Toolbar 的 menu 属性。
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_instructions -> {
                    showInstructionsDialog()
                    true
                }
                R.id.menu_github -> {
                    openUrl(Contract.PROJECT_URL)
                    true
                }
                R.id.menu_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupStatusCard() {
        // 先移除监听再回填初始值，避免把「恢复状态」误当成用户操作写回配置。
        binding.switchMaster.setOnCheckedChangeListener(null)
        binding.switchMaster.isChecked = configStore.masterEnabled
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            configStore.masterEnabled = isChecked
        }

        // 严格模式（实验性）：仅写入配置，不影响列表排序/筛选，无需刷新列表。
        binding.switchStrictMode.setOnCheckedChangeListener(null)
        binding.switchStrictMode.isChecked = configStore.strictMode
        binding.switchStrictMode.setOnCheckedChangeListener { _, isChecked ->
            configStore.strictMode = isChecked
        }

        binding.buttonOpenLsposed.setOnClickListener { openLsposed() }
    }

    private fun setupSearch() {
        // 按应用名 / 包名实时过滤（忽略大小写）。
        binding.searchEditText.doAfterTextChanged {
            renderAppList()
        }
    }

    private fun setupFilter(savedInstanceState: Bundle?) {
        // 默认「全部」；Activity 重建时恢复用户上次的选择。
        val enabledOnly = savedInstanceState?.getBoolean(STATE_FILTER_ENABLED_ONLY) == true
        binding.filterGroup.check(
            if (enabledOnly) R.id.btnFilterEnabled else R.id.btnFilterAll,
        )
        binding.filterGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                renderAppList()
            }
        }
    }

    private fun setupAppList() {
        appAdapter = AppListAdapter { entry -> toggleBlocked(entry) }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = appAdapter
    }

    // ------------------------------------------------------------------ 数据加载

    private fun loadInstalledApps() {
        if (loadJob?.isActive == true) return

        appsLoaded = false
        updateListStates(hasRows = false)

        loadJob = lifecycleScope.launch {
            // 扫描 PackageManager 可能较慢，放到 IO 线程；withContext 返回后自动回到主线程更新 UI。
            val result = withContext(Dispatchers.IO) {
                runCatching { appRepository.loadLaunchableApps() }
            }
            installedApps = result.getOrNull().orEmpty()
            appsLoaded = true
            updateBlockedSummary()
            renderAppList()
            if (result.isFailure) {
                showSnackbar(R.string.state_load_failed)
            }
        }
    }

    // ------------------------------------------------------------------ 列表渲染

    private fun renderAppList() {
        if (!appsLoaded) {
            // 加载完成前不渲染列表，只显示加载态。
            updateListStates(hasRows = false)
            return
        }

        // 每次都从 ConfigStore 读取最新配置，保证与模块实际使用的数据一致。
        val blockedPackages = configStore.blockedPackages
        val query = binding.searchEditText.text?.toString()?.trim().orEmpty()
        val enabledOnly = binding.filterGroup.checkedButtonId == R.id.btnFilterEnabled
        val collator = Collator.getInstance(Locale.CHINA)

        val comparator = Comparator<AppEntry> { first, second ->
            when {
                // 已启用的应用置顶，其余按中文排序（中文 Collator，可正确处理拼音顺序）。
                first.blocked != second.blocked -> if (first.blocked) -1 else 1
                else -> {
                    val byLabel = collator.compare(first.label, second.label)
                    if (byLabel != 0) byLabel else first.packageName.compareTo(second.packageName)
                }
            }
        }

        val rows = installedApps
            .filter { app -> matchesQuery(app, query) }
            .map { app ->
                AppEntry(
                    packageName = app.packageName,
                    label = app.label,
                    icon = app.icon,
                    blocked = app.packageName in blockedPackages,
                )
            }
            .filter { entry -> !enabledOnly || entry.blocked }
            .sortedWith(comparator)

        appAdapter.submitList(rows)
        updateListStates(hasRows = rows.isNotEmpty())
    }

    private fun updateListStates(hasRows: Boolean) {
        // 加载中显示进度；加载完成后按是否有数据切换空态文案。
        binding.loadingState.isVisible = !appsLoaded
        binding.emptyState.isVisible = appsLoaded && !hasRows
        if (appsLoaded && !hasRows) {
            binding.textEmpty.setText(
                if (installedApps.isEmpty()) {
                    R.string.state_empty_no_apps
                } else {
                    R.string.state_empty_filtered
                },
            )
        }
    }

    private fun matchesQuery(app: InstalledApp, query: String): Boolean {
        if (query.isEmpty()) return true
        return app.label.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true)
    }

    // ------------------------------------------------------------------ 状态刷新

    private fun updateBlockedSummary() {
        binding.textBlockedSummary.text =
            getString(R.string.status_enabled_summary, configStore.blockedCount)
    }

    @Suppress("DEPRECATION") // 带 Int flags 的 getPackageInfo 在 minSdk 26 上仍是最合适的重载。
    private fun isLsposedInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(Contract.LSPOSED_MANAGER_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun updateLsposedStatus() {
        if (isLsposedInstalled()) {
            binding.textLsposedStatus.setText(R.string.lsposed_installed)
            binding.textLsposedStatus.setTextColor(
                ContextCompat.getColor(this, R.color.noshake_text_secondary),
            )
            binding.iconLsposedStatus.setImageResource(R.drawable.ic_check_circle)
            binding.iconLsposedStatus.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.noshake_ok),
            )
        } else {
            // 未安装 LSPosed 时模块不会生效，用警示色突出提示。
            binding.textLsposedStatus.setText(R.string.lsposed_missing)
            binding.textLsposedStatus.setTextColor(
                ContextCompat.getColor(this, R.color.noshake_warning),
            )
            binding.iconLsposedStatus.setImageResource(R.drawable.ic_warning)
            binding.iconLsposedStatus.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.noshake_warning),
            )
        }
    }

    // ------------------------------------------------------------------ 交互逻辑

    private fun toggleBlocked(entry: AppEntry) {
        // 以 ConfigStore 中的最新状态为准，不依赖 Adapter 内的列表快照。
        val blocked = !configStore.isBlocked(entry.packageName)
        configStore.setBlocked(entry.packageName, blocked)
        updateBlockedSummary()
        // 重新排序 / 过滤：已启用项置顶，且「已启用」筛选下取消开关后立即移除该行。
        renderAppList()
    }

    private fun openLsposed() {
        val launchIntent = packageManager.getLaunchIntentForPackage(Contract.LSPOSED_MANAGER_PACKAGE)
        if (launchIntent == null) {
            showSnackbar(R.string.lsposed_open_failed)
            return
        }
        try {
            startActivity(launchIntent)
        } catch (e: ActivityNotFoundException) {
            showSnackbar(R.string.lsposed_open_failed)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            showSnackbar(R.string.link_open_failed)
        }
    }

    private fun showSnackbar(messageRes: Int) {
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ 对话框

    private fun showInstructionsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.instructions_title)
            .setMessage(R.string.instructions_message)
            .setPositiveButton(R.string.action_got_it, null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(
                getString(R.string.about_message, BuildConfig.VERSION_NAME, Contract.PROJECT_URL),
            )
            .setPositiveButton(R.string.action_open_github) { _, _ ->
                openUrl(Contract.PROJECT_URL)
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 筛选是纯 UI 状态，单独保存；开关状态以 ConfigStore 为准，无需保存。
        outState.putBoolean(
            STATE_FILTER_ENABLED_ONLY,
            binding.filterGroup.checkedButtonId == R.id.btnFilterEnabled,
        )
    }

    private companion object {
        const val STATE_FILTER_ENABLED_ONLY = "state_filter_enabled_only"
    }
}

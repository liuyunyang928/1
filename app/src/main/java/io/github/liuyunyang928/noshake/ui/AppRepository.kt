package io.github.liuyunyang928.noshake.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * PackageManager 返回的一个可启动应用。
 *
 * 这里只保存应用本身的原始信息，不包含拦截状态；拦截状态属于配置，由 Activity 在渲染时
 * 从 [io.github.liuyunyang928.noshake.core.ConfigStore] 读取，避免列表数据与配置不同步。
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

/**
 * 已安装应用的数据源。
 *
 * 只负责扫描带启动器入口的应用；筛选、排序、开关状态都由 MainActivity 结合 ConfigStore 处理，
 * 因此切换配置或搜索时无需重新扫描应用列表。
 */
class AppRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    /**
     * 返回所有带启动器入口的应用：同一包名只保留第一个，并排除本应用自身。
     *
     * 涉及跨进程 Binder 调用与图标加载，必须在后台线程调用。
     */
    @Suppress("DEPRECATION") // Int flags 的 queryIntentActivities 在 minSdk 26 上仍是最合适的重载。
    fun loadLaunchableApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(launcherIntent, 0)

        val seenPackages = HashSet<String>(resolveInfos.size)
        val apps = ArrayList<InstalledApp>(resolveInfos.size)

        for (resolveInfo in resolveInfos) {
            val activityInfo = resolveInfo.activityInfo ?: continue
            val packageName = activityInfo.packageName ?: continue
            // 排除本应用自身；同一应用可能有多个启动 Activity，按包名去重。
            if (packageName == appContext.packageName) continue
            if (!seenPackages.add(packageName)) continue

            val applicationInfo = activityInfo.applicationInfo ?: continue
            val label = try {
                applicationInfo.loadLabel(packageManager).toString().ifBlank { packageName }
            } catch (e: Exception) {
                packageName
            }
            val icon = try {
                applicationInfo.loadIcon(packageManager)
            } catch (e: Exception) {
                // 极少数损坏的包可能读不到图标，退回本应用图标占位，保证列表项完整。
                appContext.applicationInfo.loadIcon(packageManager)
            }
            apps.add(InstalledApp(packageName, label, icon))
        }
        return apps
    }
}

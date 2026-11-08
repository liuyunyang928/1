package io.github.liuyunyang928.noshake.xposed

import android.content.pm.ApplicationInfo
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XposedBridge
import io.github.liuyunyang928.noshake.BuildConfig

/**
 * LSPosed module entry point (referenced from `assets/xposed_init`).
 *
 * This class is instantiated by LSPosed inside every process that is included in the module's
 * scope. Installing the hooks here (before the target application starts) guarantees that no
 * SensorManager call can slip through.
 */
class ModuleEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val packageName = lpparam.packageName ?: return

            // Never touch the system process or our own process.
            if (packageName == "android" || packageName == BuildConfig.APPLICATION_ID) return

            // Safety net: skip framework/system UIDs. Blocking sensors there could break
            // auto-rotation or other OS features and is never needed for the use case.
            val appInfo: ApplicationInfo? = lpparam.appInfo
            if (appInfo != null && appInfo.uid < Process.FIRST_APPLICATION_UID) return

            SensorBlocker.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}

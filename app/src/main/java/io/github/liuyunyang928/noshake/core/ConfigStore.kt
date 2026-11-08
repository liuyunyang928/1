package io.github.liuyunyang928.noshake.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Read/write access to the module configuration. Used by the UI (writes) and by
 * [ConfigProvider] (reads on behalf of hooked processes).
 *
 * The preferences file is opened with `MODE_WORLD_READABLE` on purpose: when the module is
 * enabled, LSPosed intercepts that mode and makes the file readable by hooked application
 * processes, which read it through `XSharedPreferences`. Without LSPosed the call throws a
 * `SecurityException` (targetSdk >= 24) and we transparently fall back to private mode; in that
 * case hooked processes get the configuration through [ConfigProvider] instead.
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences = openPreferences(context)

    /** Global switch. When disabled the module does not block anything. */
    var masterEnabled: Boolean
        get() = prefs.getBoolean(Contract.KEY_MASTER, true)
        set(value) = prefs.edit().putBoolean(Contract.KEY_MASTER, value).apply()

    /**
     * Strict mode also hides motion sensors from `getDefaultSensor()`/`getSensorList()`.
     * Disabled by default because some apps do not null-check the result.
     */
    var strictMode: Boolean
        get() = prefs.getBoolean(Contract.KEY_STRICT, false)
        set(value) = prefs.edit().putBoolean(Contract.KEY_STRICT, value).apply()

    val blockedPackages: Set<String>
        get() = prefs.getStringSet(Contract.KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    val blockedCount: Int
        get() = blockedPackages.size

    fun isBlocked(packageName: String): Boolean = blockedPackages.contains(packageName)

    fun setBlocked(packageName: String, blocked: Boolean) {
        val next = blockedPackages.toMutableSet()
        val changed = if (blocked) next.add(packageName) else next.remove(packageName)
        if (changed) {
            prefs.edit().putStringSet(Contract.KEY_BLOCKED, next).apply()
        }
    }

    fun clearBlocked() {
        prefs.edit().remove(Contract.KEY_BLOCKED).apply()
    }

    private companion object {
        @Suppress("DEPRECATION") // MODE_WORLD_READABLE is intercepted by LSPosed on purpose.
        fun openPreferences(context: Context): SharedPreferences {
            val appContext = context.applicationContext
            return try {
                appContext.getSharedPreferences(Contract.PREFS_NAME, Context.MODE_WORLD_READABLE)
            } catch (e: SecurityException) {
                appContext.getSharedPreferences(Contract.PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}

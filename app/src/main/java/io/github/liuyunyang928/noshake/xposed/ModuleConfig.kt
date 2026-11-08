package io.github.liuyunyang928.noshake.xposed

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import de.robv.android.xposed.AndroidAppHelper
import de.robv.android.xposed.XposedBridge
import io.github.liuyunyang928.noshake.BuildConfig
import io.github.liuyunyang928.noshake.core.Contract
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration cache that lives inside the hooked application process.
 *
 * The configuration is written by our own UI (different UID) and is read through the
 * [io.github.liuyunyang928.noshake.core.ConfigProvider] content provider. If the provider is
 * unreachable (theme/ROM restrictions, package visibility, ...) we fall back to the legacy
 * XSharedPreferences mechanism. When both channels fail the module fails *closed*: the app was
 * explicitly put into the LSPosed scope by the user, so blocking it is the intended behaviour.
 */
internal object ModuleConfig {

    private data class Snapshot(val master: Boolean, val strict: Boolean, val blocked: Set<String>)

    /** Re-read the configuration at most every 30s, so toggling in the UI takes effect quickly. */
    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var nextReloadAt: Long = 0L

    private val loggedFailure = AtomicBoolean(false)

    fun shouldBlock(packageName: String): Boolean {
        val snap = current() ?: return true
        return snap.master && snap.blocked.contains(packageName)
    }

    fun isStrictMode(packageName: String): Boolean {
        val snap = current() ?: return false
        return snap.master && snap.strict
    }

    private fun current(): Snapshot? {
        val now = System.currentTimeMillis()
        val cached = snapshot
        if (cached != null && now < nextReloadAt) return cached

        synchronized(this) {
            val s = snapshot
            val n = System.currentTimeMillis()
            if (s != null && n < nextReloadAt) return s

            nextReloadAt = n + CACHE_TTL_MS
            val loaded = load()
            if (loaded != null) snapshot = loaded
            return loaded ?: s
        }
    }

    private fun load(): Snapshot? = loadFromProvider() ?: loadFromXSharedPreferences()

    private fun loadFromProvider(): Snapshot? {
        return try {
            val app: Application? = AndroidAppHelper.currentApplication()
            val resolver = app?.contentResolver
            if (resolver == null) {
                null
            } else {
                resolver.query(Uri.parse(Contract.CONFIG_URI), null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        Snapshot(
                            master = cursor.getInt(0) != 0,
                            strict = cursor.getInt(1) != 0,
                            blocked = splitPackages(cursor.getString(2)),
                        )
                    } else {
                        null
                    }
                }
            }
        } catch (t: Throwable) {
            logFailure("read config through provider failed", t)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromXSharedPreferences(): Snapshot? {
        return try {
            val clazz = Class.forName("de.robv.android.xposed.XSharedPreferences")
            val constructor = clazz.getConstructor(String::class.java, String::class.java)
            val prefs = constructor.newInstance(
                BuildConfig.APPLICATION_ID,
                Contract.PREFS_NAME,
            ) as? SharedPreferences ?: return null

            runCatching { clazz.getMethod("reload").invoke(prefs) }

            // An unreadable preferences file yields an empty instance; only trust it if it
            // actually contains something we wrote.
            if (!prefs.contains(Contract.KEY_MASTER) && !prefs.contains(Contract.KEY_BLOCKED)) {
                return null
            }

            Snapshot(
                master = prefs.getBoolean(Contract.KEY_MASTER, true),
                strict = prefs.getBoolean(Contract.KEY_STRICT, false),
                blocked = prefs.getStringSet(Contract.KEY_BLOCKED, emptySet())?.toSet().orEmpty(),
            )
        } catch (t: Throwable) {
            logFailure("read config through XSharedPreferences failed", t)
            null
        }
    }

    private fun splitPackages(raw: String?): Set<String> =
        raw?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    private fun logFailure(message: String, t: Throwable) {
        if (loggedFailure.compareAndSet(false, true)) {
            XposedBridge.log("NoShake: $message: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

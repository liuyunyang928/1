@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package io.github.liuyunyang928.noshake.xposed

import android.hardware.Sensor
import android.hardware.SensorListener
import android.hardware.SensorManager
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.liuyunyang928.noshake.core.Contract
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs the motion-sensor hooks inside the target application process.
 *
 * Strategy (v1): deny the registration instead of forging sensor events.
 *
 * - `SensorManager.registerListener(SensorEventListener, Sensor, ...)` (all four overloads)
 *   and `requestTriggerSensor(...)` return `false` for motion sensors. The advertising SDK
 *   therefore never receives a single sensor event and cannot detect a shake. Apps keep
 *   working, they simply believe that the sensor is unavailable.
 * - The deprecated `SensorListener` bit-mask API (two overloads) is covered as well, because
 *   it is an easy-to-miss bypass (`registerListener(SensorListener, int)`).
 * - Optional "strict mode" additionally hides these sensors from `getDefaultSensor()` and
 *   `getSensorList()`; disabled by default because some apps do not null-check the result.
 *
 * Only sensor *registration* is intercepted, no private/hidden framework field is touched, so
 * the hook stays stable across Android versions and ROM modifications are detected through the
 * installed-hook counter below.
 */
internal object SensorBlocker {

    private val logged = AtomicBoolean(false)

    @Suppress("DEPRECATION") // Deprecated SensorListener bit-mask API.
    private val LEGACY_MOTION_MASK: Int =
        SensorManager.SENSOR_ORIENTATION or SensorManager.SENSOR_ACCELEROMETER

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return
        val sensorManagerClass = SensorManager::class.java

        // SensorEventListener API: every overload takes the Sensor as the second argument.
        val sensorHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val sensor = param.args?.getOrNull(1) as? Sensor ?: return
                if (sensor.type !in Contract.MOTION_SENSOR_TYPES) return
                if (!ModuleConfig.shouldBlock(packageName)) return
                param.setResult(false)
                logBlockedOnce(packageName)
            }
        }

        // Deprecated SensorListener API: the second argument is a bit-mask of SENSOR_* values.
        val legacyHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args?.getOrNull(0) !is SensorListener) return
                val mask = param.args?.getOrNull(1) as? Int ?: return
                if (mask and LEGACY_MOTION_MASK == 0) return
                if (!ModuleConfig.shouldBlock(packageName)) return
                param.setResult(false)
                logBlockedOnce(packageName)
            }
        }

        var hooked = 0
        hooked += hookSafely(sensorManagerClass, "registerListener", sensorHook)
        hooked += hookSafely(sensorManagerClass, "registerListener", legacyHook)
        hooked += hookSafely(sensorManagerClass, "requestTriggerSensor", sensorHook)

        // Strict mode (off by default): pretend the motion sensors do not exist at all.
        val defaultSensorHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!ModuleConfig.isStrictMode(packageName)) return
                val type = param.args?.getOrNull(0) as? Int ?: return
                if (type in Contract.MOTION_SENSOR_TYPES) param.setResult(null)
            }
        }
        val sensorListHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!ModuleConfig.isStrictMode(packageName)) return
                val sensors = param.getResult() as? List<Sensor> ?: return
                param.setResult(sensors.filterNot { it.type in Contract.MOTION_SENSOR_TYPES })
            }
        }
        hooked += hookSafely(sensorManagerClass, "getDefaultSensor", defaultSensorHook)
        hooked += hookSafely(sensorManagerClass, "getSensorList", sensorListHook)
        hooked += hookSafely(sensorManagerClass, "getDynamicSensorList", sensorListHook)

        // Diagnostics: a count of 0 means the ROM replaced/renamed SensorManager and the module
        // would otherwise fail silently. The line is visible in the LSPosed log.
        if (hooked == 0) {
            XposedBridge.log("NoShake: WARNING no SensorManager method could be hooked in $packageName")
        }
    }

    private fun hookSafely(
        hookClass: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
    ): Int {
        return try {
            XposedBridge.hookAllMethods(hookClass, methodName, callback)?.size ?: 0
        } catch (t: Throwable) {
            XposedBridge.log("NoShake: failed to hook $methodName: $t")
            0
        }
    }

    private fun logBlockedOnce(packageName: String) {
        if (logged.compareAndSet(false, true)) {
            XposedBridge.log("NoShake: blocking motion sensors for $packageName")
        }
    }
}

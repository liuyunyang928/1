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
 * - `createSensorDirectReport(...)` (API 26) is covered, otherwise a Java app could bypass
 *   the listener API and read sensors through a `SensorDirectChannel`.
 * - Optional "strict mode" additionally hides these sensors from `getDefaultSensor()` and
 *   `getSensorList()`; disabled by default because some apps do not null-check the result.
 *
 * Only sensor *registration* is intercepted, no private/hidden framework field is touched, so
 * the hook stays stable across Android versions. OEMs sometimes override the public
 * `registerListener` methods in their own `SystemSensorManager` subclass; those declarations are
 * hooked too when the class is available.
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
                try {
                    val sensor = param.args?.getOrNull(1) as? Sensor ?: return
                    if (sensor.type !in Contract.MOTION_SENSOR_TYPES) return
                    if (!ModuleConfig.shouldBlock(packageName)) return
                    param.setResult(false)
                    logBlockedOnce(packageName)
                } catch (t: Throwable) {
                    XposedBridge.log("NoShake: sensor hook failed: $t")
                }
            }
        }

        // Deprecated SensorListener API: the second argument is a bit-mask of SENSOR_* values.
        val legacyHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (param.args?.getOrNull(0) !is SensorListener) return
                    val mask = param.args?.getOrNull(1) as? Int ?: return
                    if (mask and LEGACY_MOTION_MASK == 0) return
                    if (!ModuleConfig.shouldBlock(packageName)) return
                    param.setResult(false)
                    logBlockedOnce(packageName)
                } catch (t: Throwable) {
                    XposedBridge.log("NoShake: legacy sensor hook failed: $t")
                }
            }
        }

        // createSensorDirectReport(Sensor, int rateLevel, int reportToken): the Sensor is the
        // first argument here, unlike in registerListener().
        val directReportHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val sensor = param.args?.getOrNull(0) as? Sensor ?: return
                    if (sensor.type !in Contract.MOTION_SENSOR_TYPES) return
                    if (!ModuleConfig.shouldBlock(packageName)) return
                    param.setResult(null)
                    logBlockedOnce(packageName)
                } catch (t: Throwable) {
                    XposedBridge.log("NoShake: direct report hook failed: $t")
                }
            }
        }

        var hooked = 0
        hooked += hookSafely(sensorManagerClass, "registerListener", sensorHook)
        hooked += hookSafely(sensorManagerClass, "registerListener", legacyHook)
        hooked += hookSafely(sensorManagerClass, "requestTriggerSensor", sensorHook)
        hooked += hookSafely(sensorManagerClass, "createSensorDirectReport", directReportHook)

        // OEMs may override the public registration methods in their SystemSensorManager
        // subclass, in which case a virtual call would bypass the hooks above.
        hooked += hookSubclassOverrides(lpparam, sensorHook, legacyHook)

        // Strict mode (off by default): pretend the motion sensors do not exist at all.
        val defaultSensorHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (!ModuleConfig.isStrictMode(packageName)) return
                    val type = param.args?.getOrNull(0) as? Int ?: return
                    if (type in Contract.MOTION_SENSOR_TYPES) param.setResult(null)
                } catch (t: Throwable) {
                    XposedBridge.log("NoShake: strict mode hook failed: $t")
                }
            }
        }
        val sensorListHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (!ModuleConfig.isStrictMode(packageName)) return
                    val sensors = param.getResult() as? List<Sensor> ?: return
                    param.setResult(sensors.filterNot { it.type in Contract.MOTION_SENSOR_TYPES })
                } catch (t: Throwable) {
                    XposedBridge.log("NoShake: sensor list hook failed: $t")
                }
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

    private fun hookSubclassOverrides(
        lpparam: XC_LoadPackage.LoadPackageParam,
        sensorHook: XC_MethodHook,
        legacyHook: XC_MethodHook,
    ): Int {
        return try {
            val systemSensorManager = Class.forName(
                "android.hardware.SystemSensorManager",
                false,
                lpparam.classLoader,
            )
            var count = 0
            count += hookSafely(systemSensorManager, "registerListener", sensorHook)
            count += hookSafely(systemSensorManager, "registerListener", legacyHook)
            count += hookSafely(systemSensorManager, "requestTriggerSensor", sensorHook)
            count
        } catch (t: Throwable) {
            // Expected on most ROMs (the class simply does not override these methods).
            0
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

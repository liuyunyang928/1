package io.github.liuyunyang928.noshake.core

import android.hardware.Sensor

/**
 * Constants shared between the UI process and the hooked target processes.
 *
 * Keep this file free of Android UI dependencies: it is loaded inside every application
 * process that the LSPosed module hooks.
 */
object Contract {

    /** Authority of [ConfigProvider]; hooked processes read the configuration from it. */
    const val CONFIG_AUTHORITY = "io.github.liuyunyang928.noshake.config"

    /** Path served by [ConfigProvider]; other paths return an empty cursor. */
    const val CONFIG_PATH = "/config"

    /** URI queried by the module to obtain the configuration snapshot. */
    const val CONFIG_URI = "content://$CONFIG_AUTHORITY$CONFIG_PATH"

    /** Column indexes of the one-row configuration cursor. */
    const val COLUMN_MASTER = 0
    const val COLUMN_STRICT = 1
    const val COLUMN_BLOCKED = 2

    const val PREFS_NAME = "noshake_config"
    const val KEY_MASTER = "master_enabled"
    const val KEY_STRICT = "strict_mode"
    const val KEY_BLOCKED = "blocked_packages"

    const val LSPOSED_MANAGER_PACKAGE = "org.lsposed.manager"
    const val PROJECT_URL = "https://github.com/liuyunyang928/1"

    /**
     * Sensor types that can be used to detect "动作与方向" (a shake) and that are therefore
     * blocked for the selected apps.
     *
     * Deliberately a minimal set, so unrelated features keep working:
     * - light / proximity / magnetic field sensors are NOT blocked;
     * - `TYPE_SIGNIFICANT_MOTION` (17) is NOT blocked, it is used by step counters and other
     *   activity-recognition features;
     * - rarely used sensors (`TYPE_POSE_6DOF`, `TYPE_STATIONARY_DETECT`, ...) are left alone.
     *
     * The numeric constants are compile-time inlined, therefore newer types can be listed even
     * though the app also runs on older Android versions.
     */
    val MOTION_SENSOR_TYPES: Set<Int> = setOf(
        Sensor.TYPE_ACCELEROMETER,               // 1
        Sensor.TYPE_GYROSCOPE,                   // 4
        Sensor.TYPE_GRAVITY,                     // 9
        Sensor.TYPE_LINEAR_ACCELERATION,         // 10
        Sensor.TYPE_ROTATION_VECTOR,             // 11
        Sensor.TYPE_GAME_ROTATION_VECTOR,        // 15
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,      // 16
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, // 20
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,  // 35
    )
}

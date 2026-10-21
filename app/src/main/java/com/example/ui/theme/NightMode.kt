@file:Suppress("DEPRECATION")

package com.example.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.preference.PreferenceManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.util.SolarTimes
import com.example.util.TunnelDetector
import kotlinx.coroutines.delay

/** What decides the app's switch to dark theme. */
enum class NightModeSource(val key: String) {
    /** Android's “dark theme” setting. */
    SYSTEM("system"),

    /** Sunrise and sunset where you are. */
    SOLAR("solar");

    companion object {
        fun fromKey(key: String?): NightModeSource =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

object NightModePreferences {

    internal const val KEY_SOURCE = "pref_night_mode_source"
    internal const val KEY_TUNNEL = "pref_night_mode_tunnel"

    /** Position of the last map center, written by the ViewModel at each move. */
    private const val KEY_LAST_LAT = "pref_last_lat"
    private const val KEY_LAST_LNG = "pref_last_lng"

    /** Center of France, like the map's default centering. */
    private const val FALLBACK_LAT = 46.603354
    private const val FALLBACK_LNG = 1.888334

    fun getSource(context: Context): NightModeSource =
        NightModeSource.fromKey(
            PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_SOURCE, null)
        )

    fun setSource(context: Context, source: NightModeSource) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_SOURCE, source.key)
            .apply()
    }

    fun isTunnelDetectionEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_TUNNEL, true)

    fun setTunnelDetectionEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_TUNNEL, enabled)
            .apply()
    }

    /**
     * Can the device measure ambient light?
     *
     * Not every phone has a light sensor. Without it, tunnel detection
     * can do nothing; only the solar computation remains.
     */
    fun hasLightSensor(context: Context): Boolean {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return manager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    }

    /**
     * Position to use for the solar computation.
     *
     * We use the last map center already stored rather than asking the GPS for a
     * position: sunrise and sunset only need an accuracy of a few tens of
     * kilometers — one degree of longitude shifts the times by only four minutes —
     * and this avoids turning the GPS on to choose a background color.
     */
    fun lastKnownLocation(context: Context): Pair<Double, Double> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.contains(KEY_LAST_LAT) || !prefs.contains(KEY_LAST_LNG)) {
            return FALLBACK_LAT to FALLBACK_LNG
        }
        return prefs.getFloat(KEY_LAST_LAT, FALLBACK_LAT.toFloat()).toDouble() to
                prefs.getFloat(KEY_LAST_LNG, FALLBACK_LNG.toFloat()).toDouble()
    }
}

/** Rate at which day and night are reevaluated. */
private const val SOLAR_REFRESH_MS = 60_000L

/**
 * Is the dark theme active?
 *
 * Resolved only once by [MyApplicationTheme] then shared, so that the whole
 * display agrees — including what escapes Material, like the map's tile
 * filter. Calling `isSystemInDarkTheme()` directly elsewhere
 * would ignore solar mode.
 */
val LocalIsDarkTheme = compositionLocalOf { false }

/** The current night mode setting, reevaluated as soon as the user changes it. */
@Composable
private fun rememberNightModeSource(): NightModeSource {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var source by remember(context) { mutableStateOf(NightModePreferences.getSource(context)) }

    // Without this listening, changing the setting would only take effect at the next
    // start of the app.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == NightModePreferences.KEY_SOURCE) {
                source = NightModePreferences.getSource(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return source
}

/** The tunnel detection setting, reevaluated as soon as the user changes it. */
@Composable
private fun rememberTunnelDetectionEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var enabled by remember(context) {
        mutableStateOf(NightModePreferences.isTunnelDetectionEnabled(context))
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == NightModePreferences.KEY_TUNNEL) {
                enabled = NightModePreferences.isTunnelDetectionEnabled(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return enabled
}

/** Is it daytime, here and now? Reevaluated every minute. */
@Composable
private fun rememberIsDaylight(): Boolean {
    val context = LocalContext.current
    var isDaylight by remember(context) {
        val (lat, lng) = NightModePreferences.lastKnownLocation(context)
        mutableStateOf(SolarTimes.isDaylight(lat, lng, System.currentTimeMillis()))
    }

    LaunchedEffect(context) {
        while (true) {
            // The position is reread at each turn: it changes along the trip, and
            // the setting does not have to be restarted to take it into account.
            val (lat, lng) = NightModePreferences.lastKnownLocation(context)
            isDaylight = SolarTimes.isDaylight(lat, lng, System.currentTimeMillis())
            delay(SOLAR_REFRESH_MS)
        }
    }

    return isDaylight
}

/**
 * Follows the light sensor and says whether we are in a covered and dark
 * space. Returns `false` if listening is disabled or if the device has no
 * light sensor.
 */
@Composable
private fun rememberIsInDarkEnclosure(enabled: Boolean): Boolean {
    val context = LocalContext.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val lightSensor = remember(sensorManager) { sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) }

    if (!enabled || sensorManager == null || lightSensor == null) return false

    var isInside by remember(lightSensor) { mutableStateOf(false) }
    val detector = remember(lightSensor) { TunnelDetector() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sensorManager, lightSensor, detector) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values.firstOrNull() ?: return
                // Monotonic clock: the system time can jump, the confirmation
                // delays would then become absurd.
                isInside = detector.onReading(lux, SystemClock.elapsedRealtime())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // Listening tied to the lifecycle: no need to ask the sensor when the
        // app is not on screen.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> sensorManager.registerListener(
                    listener,
                    lightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )

                Lifecycle.Event.ON_STOP -> {
                    sensorManager.unregisterListener(listener)
                    // Readings will resume after a cut of unknown duration:
                    // the ambient reference from before no longer makes sense.
                    detector.reset()
                    isInside = false
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }

    return isInside
}

/** Should the dark theme be shown, given the chosen night mode setting? */
@Composable
fun rememberIsDarkTheme(): Boolean {
    val systemDark = isSystemInDarkTheme()
    return when (rememberNightModeSource()) {
        NightModeSource.SYSTEM -> systemDark
        NightModeSource.SOLAR -> {
            // Both calls must be unconditional: a short-circuited && would
            // make the next composable call conditional, which Compose's
            // positional memoization does not tolerate as an argument.
            val isDaylight = rememberIsDaylight()
            val tunnelEnabled = rememberTunnelDetectionEnabled()

            // The sensor is only listened to by day: at night, the theme is already dark
            // and no reading could change the result.
            val inEnclosure = rememberIsInDarkEnclosure(enabled = isDaylight && tunnelEnabled)

            !isDaylight || inEnclosure
        }
    }
}

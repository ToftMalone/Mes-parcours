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

/** Ce qui décide du passage de l'application en thème sombre. */
enum class NightModeSource(val key: String) {
    /** Le réglage « thème sombre » d'Android. */
    SYSTEM("system"),

    /** Le lever et le coucher du soleil à l'endroit où l'on se trouve. */
    SOLAR("solar");

    companion object {
        fun fromKey(key: String?): NightModeSource =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

object NightModePreferences {

    internal const val KEY_SOURCE = "pref_night_mode_source"
    internal const val KEY_TUNNEL = "pref_night_mode_tunnel"

    /** Position du dernier centre de carte, écrite par le ViewModel à chaque déplacement. */
    private const val KEY_LAST_LAT = "pref_last_lat"
    private const val KEY_LAST_LNG = "pref_last_lng"

    /** Centre de la France, comme le centrage par défaut de la carte. */
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
     * L'appareil sait-il mesurer la luminosité ambiante ?
     *
     * Tous les téléphones n'ont pas de capteur de lumière. Sans lui, la détection de
     * tunnel ne peut rien faire ; seul le calcul solaire reste.
     */
    fun hasLightSensor(context: Context): Boolean {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return manager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    }

    /**
     * Position à utiliser pour le calcul solaire.
     *
     * On se sert du dernier centre de carte déjà mémorisé plutôt que de demander une
     * position au GPS : le lever et le coucher du soleil ne demandent qu'une
     * précision de quelques dizaines de kilomètres — un degré de longitude ne décale
     * les heures que de quatre minutes — et cela évite d'allumer le GPS pour choisir
     * une couleur de fond.
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

/** Cadence de réévaluation du jour et de la nuit. */
private const val SOLAR_REFRESH_MS = 60_000L

/**
 * Le thème sombre est-il actif ?
 *
 * Résolu une seule fois par [MyApplicationTheme] puis partagé, afin que tout
 * l'affichage s'accorde — y compris ce qui échappe à Material, comme le filtre de
 * tuiles de la carte. Appeler `isSystemInDarkTheme()` directement ailleurs
 * ignorerait le mode solaire.
 */
val LocalIsDarkTheme = compositionLocalOf { false }

/** Le réglage de mode nuit courant, réévalué dès que l'utilisateur le change. */
@Composable
private fun rememberNightModeSource(): NightModeSource {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var source by remember(context) { mutableStateOf(NightModePreferences.getSource(context)) }

    // Sans cette écoute, changer le réglage n'aurait d'effet qu'au prochain
    // démarrage de l'application.
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

/** Le réglage de détection de tunnel, réévalué dès que l'utilisateur le change. */
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

/** Fait-il jour, ici et maintenant ? Réévalué chaque minute. */
@Composable
private fun rememberIsDaylight(): Boolean {
    val context = LocalContext.current
    var isDaylight by remember(context) {
        val (lat, lng) = NightModePreferences.lastKnownLocation(context)
        mutableStateOf(SolarTimes.isDaylight(lat, lng, System.currentTimeMillis()))
    }

    LaunchedEffect(context) {
        while (true) {
            // La position est relue à chaque tour : elle change au fil du trajet, et
            // le réglage n'a pas à être redémarré pour en tenir compte.
            val (lat, lng) = NightModePreferences.lastKnownLocation(context)
            isDaylight = SolarTimes.isDaylight(lat, lng, System.currentTimeMillis())
            delay(SOLAR_REFRESH_MS)
        }
    }

    return isDaylight
}

/**
 * Suit le capteur de luminosité et indique si l'on se trouve dans un espace couvert
 * et sombre. Renvoie `false` si l'écoute est désactivée ou si l'appareil n'a pas de
 * capteur de lumière.
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
                // Horloge monotone : l'heure système peut sauter, les délais de
                // confirmation deviendraient alors absurdes.
                isInside = detector.onReading(lux, SystemClock.elapsedRealtime())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // Écoute liée au cycle de vie : inutile de solliciter le capteur quand
        // l'application n'est pas à l'écran.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> sensorManager.registerListener(
                    listener,
                    lightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )

                Lifecycle.Event.ON_STOP -> {
                    sensorManager.unregisterListener(listener)
                    // Les relevés reprendront après une coupure de durée inconnue :
                    // la référence ambiante d'avant n'a plus de sens.
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

/** Faut-il afficher le thème sombre, compte tenu du réglage de mode nuit choisi ? */
@Composable
fun rememberIsDarkTheme(): Boolean {
    val systemDark = isSystemInDarkTheme()
    return when (rememberNightModeSource()) {
        NightModeSource.SYSTEM -> systemDark
        NightModeSource.SOLAR -> {
            // Les deux appels doivent être inconditionnels : un && court-circuité
            // rendrait l'appel composable suivant conditionnel, ce que la
            // mémorisation par position de Compose ne tolère pas en argument.
            val isDaylight = rememberIsDaylight()
            val tunnelEnabled = rememberTunnelDetectionEnabled()

            // Le capteur n'est écouté que de jour : la nuit, le thème est déjà sombre
            // et aucun relevé ne pourrait changer le résultat.
            val inEnclosure = rememberIsInDarkEnclosure(enabled = isDaylight && tunnelEnabled)

            !isDaylight || inEnclosure
        }
    }
}

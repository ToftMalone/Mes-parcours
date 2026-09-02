package com.example.util

import android.content.Context
import android.preference.PreferenceManager
import com.example.BuildConfig
import org.osmdroid.config.Configuration
import java.io.File

object OsmConfig {
    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return

            val appContext = context.applicationContext
            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            val config = Configuration.getInstance()

            // 1. Load default/saved preferences first
            config.load(appContext, prefs)

            // 2. Identité annoncée aux serveurs de tuiles, comme l'exige la politique
            // d'usage d'OpenStreetMap : elle demande une version réelle, pour pouvoir
            // écarter une version précise si elle se comportait mal. Le « 1.0 » figé
            // qui traînait ici n'a jamais correspondu à quoi que ce soit de publié.
            // Sans espace : c'est un jeton de user-agent.
            config.userAgentValue =
                "MesParcours/${BuildConfig.VERSION_NAME} (${appContext.packageName})"

            // 3. Le défaut d'osmdroid (2 téléchargements de tuiles en parallèle) suffisait
            // à peine en Mapnik, dont les tuiles vectorielles sont petites ; en vue
            // satellite, les tuiles JPEG de Google pèsent bien plus lourd et le même
            // écran plein (souvent 20-40 tuiles) se remplissait alors visiblement au
            // compte-gouttes. De même, le cache mémoire par défaut (9 tuiles au-delà de
            // l'écran) redemandait des tuiles à peine quittées au moindre zoom ou
            // recentrage.
            config.tileDownloadThreads = 6.toShort()
            config.cacheMapTileCount = 24.toShort()

            // 4. Explicitly set valid app-private cache paths AFTER config.load()
            val cacheDir = appContext.externalCacheDir ?: appContext.cacheDir
            val osmdroidBasePath = File(cacheDir, "osmdroid")
            val osmdroidTileCache = File(osmdroidBasePath, "tiles")

            if (!osmdroidBasePath.exists()) {
                osmdroidBasePath.mkdirs()
            }
            if (!osmdroidTileCache.exists()) {
                osmdroidTileCache.mkdirs()
            }

            config.osmdroidBasePath = osmdroidBasePath
            config.osmdroidTileCache = osmdroidTileCache

            // 5. Persist updated configuration paths into SharedPreferences
            config.save(appContext, prefs)

            isInitialized = true
        }
    }
}

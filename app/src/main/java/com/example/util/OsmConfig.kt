package com.example.util

import android.content.Context
import android.preference.PreferenceManager
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
            // d'usage d'OpenStreetMap. Sans espace : c'est un jeton de user-agent.
            config.userAgentValue = "MesParcours/1.0 (${appContext.packageName})"

            // 3. Explicitly set valid app-private cache paths AFTER config.load()
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

            // 4. Persist updated configuration paths into SharedPreferences
            config.save(appContext, prefs)

            isInitialized = true
        }
    }
}

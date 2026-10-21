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

            // 2. Identity announced to the tile servers, as OpenStreetMap's usage
            // policy requires: it asks for a real version, so as to be able to
            // rule out a specific version if it misbehaved. The frozen “1.0”
            // that lingered here never matched anything that was published.
            // No spaces: it is a user-agent token.
            config.userAgentValue =
                "MesParcours/${BuildConfig.VERSION_NAME} (${appContext.packageName})"

            // 3. osmdroid's default (2 parallel tile downloads) was barely enough
            // in Mapnik, whose vector tiles are small; in satellite view,
            // Google's JPEG tiles weigh much more and the same full
            // screen (often 20-40 tiles) then visibly filled in a
            // trickle. Likewise, the default memory cache (9 tiles beyond
            // the screen) asked again for tiles just left at the slightest zoom or
            // recentering.
            config.tileDownloadThreads = 10.toShort()
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

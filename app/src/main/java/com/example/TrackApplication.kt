package com.example

import android.app.Application
import com.example.util.OsmConfig

class TrackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OsmConfig.init(this)
    }
}

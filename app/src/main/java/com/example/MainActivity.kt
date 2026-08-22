package com.example

import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.ui.screen.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils

class MainActivity : ComponentActivity() {

    private val trackViewModel: TrackViewModel by viewModels {
        TrackViewModel.Factory(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        trackViewModel.updateAppForegroundStatus(true)
    }

    override fun onStop() {
        super.onStop()
        trackViewModel.updateAppForegroundStatus(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // OsmConfig.init est déjà appelé par TrackApplication.onCreate, qui s'exécute
        // avant toute activité : le refaire ici ne servait à rien.
        super.onCreate(savedInstanceState)

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        FormatUtils.isMetric = prefs.getBoolean("pref_is_metric", true)

        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MyApplicationTheme {
                MainScreen(
                    viewModel = trackViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

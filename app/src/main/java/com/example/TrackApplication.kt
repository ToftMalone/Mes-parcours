package com.example

import android.app.Application
import com.example.data.repository.TrackRepository
import com.example.util.FormatUtils
import com.example.util.OnboardingPreferences
import com.example.util.OsmConfig
import com.example.util.update.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OsmConfig.init(this)
        // Formats (durations, track names) need resources outside composition.
        FormatUtils.init(this)

        // Schedules the periodic update check — see UpdateCheckWorker for why a
        // background task was chosen over continuous monitoring. No effect if it is
        // already scheduled.
        UpdateCheckWorker.schedule(this)

        // Marks the welcome screen as already seen on an installation that was in use
        // before it was introduced — see OnboardingPreferences.backfillIfAlreadyUsed.
        // Before any database access: opening it would create it and skew the result.
        OnboardingPreferences.backfillIfAlreadyUsed(
            context = this,
            hasExistingDatabase = getDatabasePath("my_tracks_db").exists()
        )

        // Color backfill, once, on the first launch after the move to per-track colors:
        // every track already in the database explicitly receives the color it displayed
        // so far. Without it, a user who had set per-category colors would see their whole
        // history change appearance without having asked for anything.
        //
        // On a background thread: this is a database write, and the Room migration
        // runs on this first opening. A failure must not prevent the app from
        // starting — at worst colors fall back to their defaults.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // An import killed along with the app leaves its reserved row in the database,
            // hidden from the history: it is removed here, since no import can be running
            // at process start (see TrackRepository.importTrackFromUri).
            runCatching {
                TrackRepository.getInstance(this@TrackApplication).cleanupInterruptedImports()
            }
            runCatching {
                TrackRepository.getInstance(this@TrackApplication)
                    .backfillDisplayColors(this@TrackApplication)
            }
        }
    }
}

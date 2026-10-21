package com.example

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.data.repository.TrackRepository
import com.example.ui.screen.DetailView
import com.example.ui.screen.HistoryTab
import com.example.ui.screen.SettingsTab
import com.example.ui.screen.TrackingTab
import com.example.data.model.LiveStats
import com.example.util.update.AvailableUpdate
import com.example.ui.screen.ToolsTab
import com.example.ui.screen.WelcomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.TrackViewModel
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the redesigned screens, populated like the mockup: a way to compare the
 * real rendering of Compose with the mockup without a phone at hand.
 *
 * Same principle as `DesignSystemScreenshotTest`: continuous integration records
 * the images (`-Proborazzi.test.record=true`) and attaches them to its run. Nothing is
 * compared with a reference; a test only fails on an exception.
 *
 * `captureScreenRoboImage` photographs all windows, sheets and dialogs
 * included — `onRoot()` would only see the screen beneath them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class ScreensScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: TrackViewModel
    private lateinit var repository: TrackRepository

    /** Status bar and floating navigation bar, as MainScreen reserves them. */
    private val tabPadding = PaddingValues(top = 32.dp, bottom = 88.dp)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TrackRepository.createForTesting(db)
        viewModel = TrackViewModel(repository, context)
    }

    @After
    fun tearDown() {
        preloadJobs.forEach { it.cancel() }
        // We stop the ViewModel first, and **do not** close the database.
        //
        // Closing a database that Room flows are still observing makes their
        // next query fail; the exception then goes into a coroutine that nobody
        // awaits, and the *next* test inherits it (`UncaughtExceptionsBeforeTest`).
        // The luck of ordering and computation time did not expose it in the
        // development CI, it exposed it at the release of 2.0. An in-memory
        // database has nothing to release: it disappears with its test.
        viewModel.viewModelScope.cancel()
    }

    /** The mockup's tracks, more or less. */
    private fun seed() = runBlocking {
        val day = 86_400_000L
        val base = 1_790_500_000_000L // late September 2026
        fun t(
            name: String, daysAgo: Int, km: Double, minutes: Long, dplus: Double, color: Long,
            visible: Boolean, imported: Boolean = false, merged: Boolean = false, source: Long? = null
        ) = Track(
            name = name,
            startTime = base - daysAgo * day,
            endTime = base - daysAgo * day + minutes * 60_000,
            totalDistance = km * 1000,
            duration = minutes * 60,
            elevationGain = dplus,
            elevationLoss = dplus,
            avgSpeed = km * 1000 / (minutes * 60),
            maxSpeed = 2.7,
            isImported = imported,
            isMerged = merged,
            isSelectedForMap = visible,
            sourceColor = source?.toInt(),
            displayColor = if (source != null) null else color.toInt()
        )
        val tracks = listOf(
            t("Lake Vassivière loop", 0, 18.4, 252, 386.0, 0xFFE8505B, true),
            t("Climb to the Croix pass", 8, 32.7, 125, 912.0, 0xFF2F7DE1, true),
            t("Walk along the Loire", 11, 6.1, 84, 42.0, 0xFFF2A516, false),
            t("Trajet Lyon → Annecy", 16, 142.0, 111, 1204.0, 0xFF8E5BE8, false),
            t("Tour du Mont-Blanc · stage 3", 25, 21.9, 460, 1480.0, 0xFF13A38A, false, imported = true),
            t("Customs officers' path", 30, 12.3, 210, 310.0, 0, true, imported = true, source = 0xFFFF7043),
            t("GR 34 · Perros-Guirec", 44, 9.8, 175, 188.0, 0xFFE0569B, false, imported = true)
        )
        for (track in tracks) {
            val id = db.trackDao.insertTrack(track)
            db.trackDao.insertTrackPoints((0 until 60).map { i ->
                TrackPoint(
                    trackId = id,
                    latitude = 45.76 + i * 0.0006,
                    longitude = 1.86 + kotlin.math.sin(i / 6.0) * 0.004,
                    altitude = 650.0 + kotlin.math.sin(i / 9.0) * 60,
                    timestamp = track.startTime + i * 60_000L
                )
            })
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Loads the list of tracks before composing the screen. Room reads it in the
     * background and returns it through the main thread: on the Record screen, next to
     * the osmdroid map, that return was not always processed while Compose
     * waited, and “Start” tapped too early saw an empty history — it
     * then started the recording instead of opening the choice. So we subscribe
     * ourselves and run the main thread until the tracks arrive.
     */
    private fun preloadTracks() {
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            viewModel.allTracks.collect {}
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.allTracks.value.isEmpty() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        check(viewModel.allTracks.value.isNotEmpty()) { "Tracks not loaded" }
        preloadJobs += job
    }

    private val preloadJobs = mutableListOf<kotlinx.coroutines.Job>()

    private fun shoot(name: String, dark: Boolean) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/refonte/${name}_${if (dark) "dark" else "light"}.png")
    }

    private fun history(dark: Boolean) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                HistoryTab(
                    viewModel = viewModel,
                    onNavigateToDetails = {},
                    contentPadding = tabPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @Test fun history_light() { seed(); history(false); waitForTag("history_list"); shoot("history", false) }
    @Test fun history_dark() { seed(); history(true); waitForTag("history_list"); shoot("history", true) }

    @Test fun history_empty() {
        history(false)
        waitForTag("history_empty_recorded")
        shoot("history_empty", false)
    }

    @Test fun history_actions() {
        seed(); history(false); waitForTag("history_list")
        compose.onNodeWithTag("track_actions_1").performClick()
        waitForTag("action_toggle_map")
        shoot("history_actions", false)
    }

    @Test fun history_color_google_earth() {
        seed(); history(false); waitForTag("history_list")
        compose.onNodeWithTag("history_category_IMPORTED").performClick()
        waitForTag("track_color_button_6")
        compose.onNodeWithTag("track_color_button_6").performClick()
        waitForTag("color_swatch_from_file")
        shoot("history_color", false)
    }

    @Test fun history_deletion() {
        seed(); history(false); waitForTag("history_list")
        compose.onNodeWithTag("track_actions_3").performClick()
        waitForTag("delete_track_button_3")
        compose.onNodeWithTag("delete_track_button_3").performClick()
        waitForTag("dialog_delete_confirm_button")
        shoot("history_deletion", false)
    }

    // ------------------------------------------------------------ Detail sheet

    private fun detail(trackId: Long, dark: Boolean) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                DetailView(trackId = trackId, viewModel = viewModel, onBackClick = {})
            }
        }
    }

    @Test fun detail_light() { seed(); detail(1, false); waitForTag("altitude_profile"); shoot("detail", false) }
    @Test fun detail_dark() { seed(); detail(1, true); waitForTag("altitude_profile"); shoot("detail", true) }

    @Test fun detail_export() {
        seed(); detail(1, false); waitForTag("export_button")
        compose.onNodeWithTag("export_button").performClick()
        waitForTag("export_gpx_button")
        shoot("detail_export", false)
    }

    @Test fun detail_empty() {
        runBlocking { db.trackDao.insertTrack(Track(name = "Walk along the Loire", startTime = 1_789_600_000_000L)) }
        detail(1, false)
        waitForTag("detail_no_coordinates")
        shoot("detail_empty", false)
    }

    // ------------------------------------------------------------------ Tools

    private fun tools(dark: Boolean) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                ToolsTab(viewModel = viewModel, contentPadding = tabPadding, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test fun tools_light() { tools(false); waitForTag("tools_tab"); shoot("tools", false) }
    @Test fun tools_dark() { tools(true); waitForTag("tools_tab"); shoot("tools", true) }

    @Test fun tools_merge() {
        seed(); tools(false); waitForTag("tools_tab")
        compose.onNodeWithTag("open_merge_tool_button").performClick()
        waitForTag("merge_source_1")
        compose.onNodeWithTag("merge_source_1").performClick()
        compose.onNodeWithTag("merge_source_2").performClick()
        shoot("tools_merge", false)
    }

    @Test fun tools_merge_missing() {
        tools(false); waitForTag("tools_tab")
        compose.onNodeWithTag("open_merge_tool_button").performClick()
        waitForTag("tool_need_tracks")
        shoot("tools_merge_missing", false)
    }

    @Test fun tools_split() {
        seed(); tools(true); waitForTag("tools_tab")
        compose.onNodeWithTag("open_split_tool_button").performClick()
        waitForTag("split_mode_gap")
        compose.onNodeWithTag("split_mode_gap").performClick()
        shoot("tools_split", true)
    }

    @Test fun tools_trim() {
        seed(); tools(false); waitForTag("tools_tab")
        compose.onNodeWithTag("open_trim_tool_button").performClick()
        waitForTag("trim_kept_bar")
        shoot("tools_trim", false)
    }

    // -------------------------------------------------------------- Settings

    private val sampleUpdate = AvailableUpdate(
        versionCode = 99,
        versionName = "1.5",
        apkUrl = "https://github.com/ToftMalone/Mes-parcours/releases/download/v1.5/mes-parcours.apk",
        notes = listOf("New interface", "Elevation profile in the detail sheet"),
        sha256 = "0".repeat(64)
    )

    private fun settings(dark: Boolean, update: AvailableUpdate? = null) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                SettingsTab(contentPadding = tabPadding, availableUpdate = update, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test fun settings_light() { settings(false, sampleUpdate); waitForTag("settings_screen_root"); shoot("settings", false) }
    @Test fun settings_dark() { settings(true); waitForTag("settings_screen_root"); shoot("settings", true) }

    @Test fun settings_whatsnew() {
        settings(false, sampleUpdate); waitForTag("settings_screen_root")
        compose.onNodeWithTag("version_badge").performScrollTo().performClick()
        waitForTag("release_notes_dialog")
        shoot("settings_whatsnew", false)
    }

    @Test fun settings_about() {
        settings(false); waitForTag("settings_screen_root")
        compose.onNodeWithTag("open_about_button").performScrollTo().performClick()
        waitForTag("about_page")
        shoot("settings_about", false)
    }

    // ----------------------------------------------------------------- Welcome

    private fun welcome(dark: Boolean, pages: Int) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) { WelcomeScreen(onRequestPermissions = {}) }
        }
        waitForTag("welcome_screen")
        repeat(pages) {
            compose.onNodeWithTag("welcome_next_button").performClick()
            compose.mainClock.advanceTimeBy(1_000)
        }
    }

    @Test fun welcome_1() { welcome(false, 0); shoot("welcome_1", false) }
    @Test fun welcome_1_dark() { welcome(true, 0); shoot("welcome_1", true) }
    @Test fun welcome_2() { welcome(false, 1); shoot("welcome_2", false) }
    @Test fun welcome_3() { welcome(false, 2); waitForTag("welcome_continue_button"); shoot("welcome_3", false) }

    // --------------------------------------------------------------- Record

    private fun recording(dark: Boolean, permission: Boolean = true) {
        repository.updateGpsStatus(com.example.data.model.GpsStatus.FOUND)
        repository.updateGpsAccuracy(4f)
        repository.updateAltitude(com.example.util.AltitudeFix(184.0, 3f))
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                TrackingTab(viewModel = viewModel, hasLocationPermission = permission, onRequestPermission = {})
            }
        }
    }

    private fun startLive(paused: Boolean) {
        repository.setTrackingState(true)
        repository.setRecordingPaused(paused)
        repository.updateLiveStats(LiveStats(durationSec = 1462, distanceMeters = 3440.0, currentSpeedMps = 3.6))
    }

    @Test fun record_light() { recording(false); waitForTag("live_stats_panel"); shoot("record", false) }
    @Test fun record_dark() { recording(true); waitForTag("live_stats_panel"); shoot("record", true) }

    @Test fun record_choice() {
        seed(); preloadTracks(); recording(false); waitForTag("action_fab")
        compose.onNodeWithTag("action_fab").performClick()
        waitForTag("start_new_track_fab")
        shoot("record_choice", false)
    }

    @Test fun record_recording() { startLive(false); recording(false); waitForTag("stop_fab"); shoot("record_recording", false) }
    @Test fun record_recording_dark() { startLive(false); recording(true); waitForTag("stop_fab"); shoot("record_recording", true) }
    /** Narrow phone (360 dp): the four columns of the banner must fit. */
    @Config(qualifiers = RobolectricDeviceQualifiers.SmallPhone)
    @Test fun record_recording_narrow() { startLive(false); recording(false); waitForTag("stop_fab"); shoot("record_recording_narrow", false) }

    @Test fun record_pause() { startLive(true); recording(false); waitForTag("stop_fab"); shoot("record_pause", false) }

    @Test fun record_no_permission() {
        recording(false, permission = false)
        waitForTag("permission_denied_card")
        shoot("record_no_permission", false)
    }

    @Test fun record_resume() {
        seed(); preloadTracks(); recording(false); waitForTag("action_fab")
        compose.onNodeWithTag("action_fab").performClick()
        waitForTag("resume_existing_track_fab")
        compose.onNodeWithTag("resume_existing_track_fab").performClick()
        waitForTag("resume_picker_cancel")
        shoot("record_resume", false)
    }
}

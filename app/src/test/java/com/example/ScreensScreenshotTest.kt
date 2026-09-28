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
 * Captures des écrans de la refonte, peuplés comme la maquette : de quoi comparer le
 * rendu réel de Compose à la maquette sans téléphone sous la main.
 *
 * Même principe que `DesignSystemScreenshotTest` : l'intégration continue enregistre
 * les images (`-Proborazzi.test.record=true`) et les joint à son exécution. Rien n'est
 * comparé à une référence ; un test n'échoue que sur une exception.
 *
 * `captureScreenRoboImage` photographie toutes les fenêtres, feuilles et dialogues
 * compris — `onRoot()` ne verrait que l'écran sous eux.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class ScreensScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: TrackViewModel
    private lateinit var repository: TrackRepository

    /** Barre d'état et barre de navigation flottante, telles que MainScreen les réserve. */
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
        db.close()
    }

    /** Les parcours de la maquette, à peu de chose près. */
    private fun seed() = runBlocking {
        val day = 86_400_000L
        val base = 1_790_500_000_000L // fin septembre 2026
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
            t("Boucle du lac de Vassivière", 0, 18.4, 252, 386.0, 0xFFE8505B, true),
            t("Montée au col de la Croix", 8, 32.7, 125, 912.0, 0xFF2F7DE1, true),
            t("Balade des bords de Loire", 11, 6.1, 84, 42.0, 0xFFF2A516, false),
            t("Trajet Lyon → Annecy", 16, 142.0, 111, 1204.0, 0xFF8E5BE8, false),
            t("Tour du Mont-Blanc · étape 3", 25, 21.9, 460, 1480.0, 0xFF13A38A, false, imported = true),
            t("Sentier des douaniers", 30, 12.3, 210, 310.0, 0, true, imported = true, source = 0xFFFF7043),
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
     * Charge la liste des parcours avant de composer l'écran. Room la lit en
     * arrière-plan et la rend par le fil principal : sur l'écran Enregistrer, à côté
     * de la carte osmdroid, ce retour n'était pas toujours traité pendant l'attente
     * de Compose, et « Démarrer » touché trop tôt voyait un historique vide — il
     * lançait alors l'enregistrement au lieu d'ouvrir le choix. On s'abonne donc
     * soi-même et l'on fait tourner le fil principal jusqu'à l'arrivée des parcours.
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
        check(viewModel.allTracks.value.isNotEmpty()) { "Parcours non chargés" }
        preloadJobs += job
    }

    private val preloadJobs = mutableListOf<kotlinx.coroutines.Job>()

    private fun shoot(name: String, dark: Boolean) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/refonte/${name}_${if (dark) "sombre" else "clair"}.png")
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

    @Test fun historique_clair() { seed(); history(false); waitForTag("history_list"); shoot("historique", false) }
    @Test fun historique_sombre() { seed(); history(true); waitForTag("history_list"); shoot("historique", true) }

    @Test fun historique_vide() {
        history(false)
        waitForTag("history_empty_recorded")
        shoot("historique_vide", false)
    }

    @Test fun historique_actions() {
        seed(); history(false); waitForTag("history_list")
        compose.onNodeWithTag("track_actions_1").performClick()
        waitForTag("action_toggle_map")
        shoot("historique_actions", false)
    }

    @Test fun historique_couleur_google_earth() {
        seed(); history(false); waitForTag("history_list")
        compose.onNodeWithTag("history_category_Importés").performClick()
        waitForTag("track_color_button_6")
        compose.onNodeWithTag("track_color_button_6").performClick()
        waitForTag("color_swatch_from_file")
        shoot("historique_couleur", false)
    }

    @Test fun historique_suppression() {
        seed(); history(false); waitForTag("history_list")
        compose.onNodeWithTag("track_actions_3").performClick()
        waitForTag("delete_track_button_3")
        compose.onNodeWithTag("delete_track_button_3").performClick()
        waitForTag("dialog_delete_confirm_button")
        shoot("historique_suppression", false)
    }

    // ------------------------------------------------------------ Fiche détail

    private fun detail(trackId: Long, dark: Boolean) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                DetailView(trackId = trackId, viewModel = viewModel, onBackClick = {})
            }
        }
    }

    @Test fun detail_clair() { seed(); detail(1, false); waitForTag("altitude_profile"); shoot("detail", false) }
    @Test fun detail_sombre() { seed(); detail(1, true); waitForTag("altitude_profile"); shoot("detail", true) }

    @Test fun detail_export() {
        seed(); detail(1, false); waitForTag("export_button")
        compose.onNodeWithTag("export_button").performClick()
        waitForTag("export_gpx_button")
        shoot("detail_export", false)
    }

    @Test fun detail_vide() {
        runBlocking { db.trackDao.insertTrack(Track(name = "Balade des bords de Loire", startTime = 1_789_600_000_000L)) }
        detail(1, false)
        waitForTag("detail_no_coordinates")
        shoot("detail_vide", false)
    }

    // ------------------------------------------------------------------ Outils

    private fun tools(dark: Boolean) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                ToolsTab(viewModel = viewModel, contentPadding = tabPadding, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test fun outils_clair() { tools(false); waitForTag("tools_tab"); shoot("outils", false) }
    @Test fun outils_sombre() { tools(true); waitForTag("tools_tab"); shoot("outils", true) }

    @Test fun outils_fusion() {
        seed(); tools(false); waitForTag("tools_tab")
        compose.onNodeWithTag("open_merge_tool_button").performClick()
        waitForTag("merge_source_1")
        compose.onNodeWithTag("merge_source_1").performClick()
        compose.onNodeWithTag("merge_source_2").performClick()
        shoot("outils_fusion", false)
    }

    @Test fun outils_fusion_manque() {
        tools(false); waitForTag("tools_tab")
        compose.onNodeWithTag("open_merge_tool_button").performClick()
        waitForTag("tool_need_tracks")
        shoot("outils_fusion_manque", false)
    }

    @Test fun outils_decoupe() {
        seed(); tools(true); waitForTag("tools_tab")
        compose.onNodeWithTag("open_split_tool_button").performClick()
        waitForTag("split_mode_gap")
        compose.onNodeWithTag("split_mode_gap").performClick()
        shoot("outils_decoupe", true)
    }

    @Test fun outils_rognage() {
        seed(); tools(false); waitForTag("tools_tab")
        compose.onNodeWithTag("open_trim_tool_button").performClick()
        waitForTag("trim_kept_bar")
        shoot("outils_rognage", false)
    }

    // -------------------------------------------------------------- Paramètres

    private val sampleUpdate = AvailableUpdate(
        versionCode = 99,
        versionName = "1.5",
        apkUrl = "https://github.com/ToftMalone/Mes-parcours/releases/download/v1.5/mes-parcours.apk",
        notes = listOf("Nouvelle interface", "Profil d'altitude dans la fiche détail"),
        sha256 = "0".repeat(64)
    )

    private fun settings(dark: Boolean, update: AvailableUpdate? = null) {
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                SettingsTab(contentPadding = tabPadding, availableUpdate = update, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test fun parametres_clair() { settings(false, sampleUpdate); waitForTag("settings_screen_root"); shoot("parametres", false) }
    @Test fun parametres_sombre() { settings(true); waitForTag("settings_screen_root"); shoot("parametres", true) }

    @Test fun parametres_nouveautes() {
        settings(false, sampleUpdate); waitForTag("settings_screen_root")
        compose.onNodeWithTag("version_badge").performScrollTo().performClick()
        waitForTag("release_notes_dialog")
        shoot("parametres_nouveautes", false)
    }

    @Test fun parametres_a_propos() {
        settings(false); waitForTag("settings_screen_root")
        compose.onNodeWithTag("open_about_button").performScrollTo().performClick()
        waitForTag("about_page")
        shoot("parametres_a_propos", false)
    }

    // ----------------------------------------------------------------- Accueil

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

    @Test fun accueil_1() { welcome(false, 0); shoot("accueil_1", false) }
    @Test fun accueil_1_sombre() { welcome(true, 0); shoot("accueil_1", true) }
    @Test fun accueil_2() { welcome(false, 1); shoot("accueil_2", false) }
    @Test fun accueil_3() { welcome(false, 2); waitForTag("welcome_continue_button"); shoot("accueil_3", false) }

    // --------------------------------------------------------------- Enregistrer

    private fun recording(dark: Boolean, permission: Boolean = true) {
        repository.updateGpsStatus("Signal trouvé")
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

    @Test fun enregistrer_clair() { recording(false); waitForTag("live_stats_panel"); shoot("enregistrer", false) }
    @Test fun enregistrer_sombre() { recording(true); waitForTag("live_stats_panel"); shoot("enregistrer", true) }

    @Test fun enregistrer_choix() {
        seed(); preloadTracks(); recording(false); waitForTag("action_fab")
        compose.onNodeWithTag("action_fab").performClick()
        waitForTag("start_new_track_fab")
        shoot("enregistrer_choix", false)
    }

    @Test fun enregistrer_en_cours() { startLive(false); recording(false); waitForTag("stop_fab"); shoot("enregistrer_en_cours", false) }
    @Test fun enregistrer_en_cours_sombre() { startLive(false); recording(true); waitForTag("stop_fab"); shoot("enregistrer_en_cours", true) }
    @Test fun enregistrer_pause() { startLive(true); recording(false); waitForTag("stop_fab"); shoot("enregistrer_pause", false) }

    @Test fun enregistrer_sans_permission() {
        recording(false, permission = false)
        waitForTag("permission_denied_card")
        shoot("enregistrer_sans_permission", false)
    }

    @Test fun enregistrer_reprise() {
        seed(); preloadTracks(); recording(false); waitForTag("action_fab")
        compose.onNodeWithTag("action_fab").performClick()
        waitForTag("resume_existing_track_fab")
        compose.onNodeWithTag("resume_existing_track_fab").performClick()
        waitForTag("resume_picker_cancel")
        shoot("enregistrer_reprise", false)
    }
}

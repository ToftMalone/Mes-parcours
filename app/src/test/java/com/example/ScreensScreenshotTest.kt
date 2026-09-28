package com.example

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.example.util.update.AvailableUpdate
import com.example.ui.screen.ToolsTab
import com.example.ui.screen.WelcomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.TrackViewModel
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
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

    /** Barre d'état et barre de navigation flottante, telles que MainScreen les réserve. */
    private val tabPadding = PaddingValues(top = 32.dp, bottom = 88.dp)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = TrackViewModel(TrackRepository.createForTesting(db), context)
    }

    @After
    fun tearDown() {
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
        compose.onNodeWithTag("version_badge").performClick()
        waitForTag("release_notes_dialog")
        shoot("parametres_nouveautes", false)
    }

    @Test fun parametres_a_propos() {
        settings(false); waitForTag("settings_screen_root")
        compose.onNodeWithTag("open_about_button").performClick()
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
}

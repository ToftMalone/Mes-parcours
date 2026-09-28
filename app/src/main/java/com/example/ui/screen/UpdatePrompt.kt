package com.example.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.component.CookieShape
import com.example.ui.component.MpFilledButton
import com.example.ui.component.MpSheet
import com.example.ui.component.MpTextButton
import com.example.ui.component.ShapeBadge
import com.example.ui.component.SunShape
import com.example.ui.component.WaveProgress
import com.example.ui.theme.StatXlTextStyle
import com.example.util.update.AvailableUpdate
import com.example.util.update.UpdateChecker
import com.example.util.update.UpdateConfig
import com.example.util.update.UpdateDownloader
import com.example.util.update.isNewerThan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Étapes de la proposition de mise à jour. */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    data class Ready(val update: AvailableUpdate, val apk: File) : UpdateState
    data class NeedsPermission(val update: AvailableUpdate, val apk: File) : UpdateState
    data class Failed(val update: AvailableUpdate, val progress: Float) : UpdateState
}

/**
 * Recherche une nouvelle version au démarrage et propose de l'installer.
 *
 * N'affiche jamais rien tant qu'aucune mise à jour n'est disponible, et reste
 * entièrement inerte si [UpdateConfig] n'est pas renseigné : aucune requête n'est
 * alors émise. Un échec de recherche est silencieux — ne pas joindre le serveur ne
 * concerne pas l'utilisateur.
 *
 * Chaque étape s'affiche dans une feuille du bas, comme sur la maquette.
 *
 * @param reopenTrigger Incrémenté par l'appelant (le bouton des réglages) pour
 * rouvrir la feuille sur la mise à jour déjà détectée, sans relancer une recherche
 * réseau ni redémarrer l'application.
 * @param isVisible Suspend l'affichage sans sortir de la composition, le temps qu'un
 * autre écran occupe la place. Sortir vraiment de la composition effacerait la mise à
 * jour déjà trouvée, et le prochain retour relancerait une requête réseau tout en
 * rouvrant une feuille que l'utilisateur avait écartée.
 * @param onUpdateAvailable Prévient l'appelant dès qu'une mise à jour est détectée,
 * pour qu'il puisse afficher un badge persistant même une fois la feuille ignorée.
 */
@Composable
fun UpdatePrompt(
    reopenTrigger: Int = 0,
    isVisible: Boolean = true,
    onUpdateAvailable: (AvailableUpdate) -> Unit = {}
) {
    if (!UpdateConfig.isConfigured) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    // Conservée même une fois la feuille ignorée (state redevenu Idle), pour pouvoir
    // la rouvrir sur la même mise à jour sans reconsulter le réseau.
    var knownUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    // « Continuer en arrière-plan » : la feuille se range, le téléchargement se
    // poursuit tant que l'application est ouverte, et la feuille revient d'elle-même
    // à l'étape suivante (prêt à installer, ou interrompu).
    var hiddenWhileDownloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Le ménage d'abord, et à chaque lancement plutôt qu'à la seule découverte
        // d'une version plus récente : un APK déjà téléchargé ne resservira jamais —
        // `download` réécrit systématiquement le fichier — et n'a donc aucune raison
        // de survivre au lancement suivant, que l'installation ait abouti ou que
        // l'utilisateur y ait renoncé. Le faire seulement quand une nouveauté se
        // présentait laissait une vingtaine de Mio sur le disque entre deux
        // publications, sauvegardés avec le reste jusqu'à ce que `backup_rules.xml`
        // les en écarte.
        //
        // Sur Dispatchers.IO : c'est un parcours de répertoire et des suppressions,
        // rien qui ait sa place sur le thread principal.
        withContext(Dispatchers.IO) { UpdateDownloader.clearDownloads(context) }

        val update = UpdateChecker.fetchLatest() ?: return@LaunchedEffect
        if (update.isNewerThan(BuildConfig.VERSION_CODE)) {
            knownUpdate = update
            state = UpdateState.Available(update)
            onUpdateAvailable(update)
        }
    }

    LaunchedEffect(reopenTrigger) {
        if (reopenTrigger > 0) {
            // Un téléchargement déjà en cours se montre tel quel plutôt que de repartir
            // de zéro.
            if (state is UpdateState.Downloading) {
                hiddenWhileDownloading = false
            } else {
                knownUpdate?.let { state = UpdateState.Available(it) }
            }
        }
    }

    val dismiss = {
        downloadJob?.cancel()
        downloadJob = null
        state = UpdateState.Idle
    }

    val startDownload = { update: AvailableUpdate ->
        hiddenWhileDownloading = false
        state = UpdateState.Downloading(update, 0f)
        downloadJob = scope.launch {
            var last = 0f
            val apk = UpdateDownloader.download(context, update) { progress ->
                last = progress
                state = UpdateState.Downloading(update, progress)
            }
            hiddenWhileDownloading = false
            state = when {
                apk == null -> UpdateState.Failed(update, last)
                UpdateDownloader.canRequestInstall(context) -> UpdateState.Ready(update, apk)
                else -> UpdateState.NeedsPermission(update, apk)
            }
        }
    }

    // L'état reste vivant, seul l'affichage est suspendu.
    if (!isVisible) return

    when (val current = state) {
        UpdateState.Idle -> Unit

        is UpdateState.Available -> UpdateSheet(onDismiss = dismiss, testTag = "update_available_dialog") {
            ShapeBadge(
                icon = Icons.Rounded.SystemUpdate,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = SunShape
            )
            SheetTitle("Nouvelle version disponible")
            Text(
                "Version ${current.update.versionName}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (current.update.notes.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    current.update.notes.forEach { note ->
                        Text("• $note", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                    }
                }
            }
            SheetButtons(
                laterLabel = "Plus tard",
                onLater = dismiss,
                confirmLabel = "Télécharger",
                confirmIcon = Icons.Rounded.Download,
                onConfirm = { startDownload(current.update) }
            )
        }

        is UpdateState.Downloading -> if (!hiddenWhileDownloading) {
            UpdateSheet(onDismiss = { hiddenWhileDownloading = true }, testTag = "update_downloading_dialog") {
                Text(
                    "Téléchargement de la ${current.update.versionName}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                    Text("${(current.progress * 100).toInt()}", style = StatXlTextStyle.copy(fontSize = 72.sp, lineHeight = 72.sp))
                    Text(
                        "%",
                        style = StatXlTextStyle.copy(fontSize = 32.sp, lineHeight = 40.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                }
                WaveProgress(progress = current.progress, modifier = Modifier.padding(top = 10.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    MpTextButton("Annuler", onClick = dismiss)
                    MpTextButton("Continuer en arrière-plan", onClick = { hiddenWhileDownloading = true })
                }
            }
        }

        is UpdateState.Ready -> UpdateSheet(onDismiss = dismiss, testTag = "update_ready_dialog") {
            ShapeBadge(
                icon = Icons.Rounded.Check,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = SunShape,
                size = 72.dp,
                iconSize = 40.dp
            )
            SheetTitle("Prêt à installer")
            SheetBody(
                "La version ${current.update.versionName} est téléchargée. Vos parcours sont " +
                    "conservés ; Android va vous demander de confirmer l'installation."
            )
            SheetButtons(
                laterLabel = "Plus tard",
                onLater = dismiss,
                confirmLabel = "Installer",
                confirmIcon = null,
                onConfirm = {
                    context.startActivity(UpdateDownloader.installIntent(context, current.apk))
                    state = UpdateState.Idle
                }
            )
        }

        is UpdateState.NeedsPermission -> UpdateSheet(onDismiss = dismiss, testTag = "update_permission_dialog") {
            ShapeBadge(
                icon = Icons.Rounded.AdminPanelSettings,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = CookieShape
            )
            SheetTitle("Autorisation requise")
            SheetBody(
                "Pour installer la mise à jour, autorisez « Mes parcours » à installer des " +
                    "applications. Cette étape n'est demandée qu'une seule fois."
            )
            SheetButtons(
                laterLabel = "Plus tard",
                onLater = dismiss,
                confirmLabel = "Ouvrir les réglages",
                confirmIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                onConfirm = {
                    context.startActivity(UpdateDownloader.unknownSourcesSettingsIntent(context))
                    state = UpdateState.Ready(current.update, current.apk)
                }
            )
        }

        is UpdateState.Failed -> UpdateSheet(onDismiss = dismiss, testTag = "update_failed_dialog") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
            ) {
                Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(30.dp))
            }
            SheetTitle("Téléchargement interrompu")
            val pct = (current.progress * 100).toInt()
            SheetBody(
                if (pct > 0) "Le téléchargement s'est arrêté à $pct %. Vérifiez votre réseau puis réessayez."
                else "La mise à jour n'a pas pu être téléchargée. Vérifiez votre réseau puis réessayez."
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Box(
                    modifier = Modifier
                        .weight(current.progress.coerceIn(0.001f, 0.999f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .weight(1f - current.progress.coerceIn(0.001f, 0.999f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                )
            }
            SheetButtons(
                laterLabel = "Plus tard",
                onLater = dismiss,
                confirmLabel = "Réessayer",
                confirmIcon = Icons.Rounded.Refresh,
                onConfirm = { startDownload(current.update) }
            )
        }
    }
}

/** Coquille commune aux étapes, pour garder la même présentation. */
@Composable
private fun UpdateSheet(onDismiss: () -> Unit, testTag: String, content: @Composable ColumnScope.() -> Unit) {
    MpSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().testTag(testTag), content = content)
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = null),
        modifier = Modifier.padding(top = 14.dp)
    )
}

@Composable
private fun SheetBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun SheetButtons(
    laterLabel: String,
    onLater: () -> Unit,
    confirmLabel: String,
    confirmIcon: ImageVector?,
    onConfirm: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
    ) {
        MpTextButton(laterLabel, onClick = onLater)
        MpFilledButton(
            confirmLabel,
            onClick = onConfirm,
            icon = confirmIcon,
            height = 52.dp,
            modifier = Modifier.testTag("update_confirm_button")
        )
    }
}

package com.example.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.ui.theme.EmeraldPrimary
import com.example.util.update.AvailableUpdate
import com.example.util.update.UpdateChecker
import com.example.util.update.UpdateConfig
import com.example.util.update.UpdateDownloader
import com.example.util.update.isNewerThan
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Étapes de la proposition de mise à jour. */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    data class Ready(val update: AvailableUpdate, val apk: File) : UpdateState
    data class NeedsPermission(val update: AvailableUpdate, val apk: File) : UpdateState
    data class Failed(val update: AvailableUpdate) : UpdateState
}

/**
 * Recherche une nouvelle version au démarrage et propose de l'installer.
 *
 * N'affiche jamais rien tant qu'aucune mise à jour n'est disponible, et reste
 * entièrement inerte si [UpdateConfig] n'est pas renseigné : aucune requête n'est
 * alors émise. Un échec de recherche est silencieux — ne pas joindre le serveur ne
 * concerne pas l'utilisateur.
 *
 * @param reopenTrigger Incrémenté par l'appelant (le bouton des réglages) pour
 * rouvrir le bandeau sur la mise à jour déjà détectée, sans relancer une recherche
 * réseau ni redémarrer l'application.
 * @param isVisible Suspend l'affichage sans sortir de la composition, le temps qu'un
 * autre écran occupe la place. Sortir vraiment de la composition effacerait la mise à
 * jour déjà trouvée, et le prochain retour relancerait une requête réseau tout en
 * rouvrant un bandeau que l'utilisateur avait écarté.
 * @param onUpdateAvailable Prévient l'appelant dès qu'une mise à jour est détectée,
 * pour qu'il puisse afficher un badge persistant même une fois le bandeau ignoré.
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
    // Conservée même une fois le bandeau ignoré (state redevenu Idle), pour pouvoir
    // le rouvrir sur la même mise à jour sans reconsulter le réseau.
    var knownUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }

    LaunchedEffect(Unit) {
        val update = UpdateChecker.fetchLatest() ?: return@LaunchedEffect
        if (update.isNewerThan(BuildConfig.VERSION_CODE)) {
            // Les APK d'une version précédente ne servent plus à rien.
            UpdateDownloader.clearDownloads(context)
            knownUpdate = update
            state = UpdateState.Available(update)
            onUpdateAvailable(update)
        }
    }

    LaunchedEffect(reopenTrigger) {
        if (reopenTrigger > 0) {
            knownUpdate?.let { state = UpdateState.Available(it) }
        }
    }

    val dismiss = {
        downloadJob?.cancel()
        downloadJob = null
        state = UpdateState.Idle
    }

    val startDownload = { update: AvailableUpdate ->
        state = UpdateState.Downloading(update, 0f)
        downloadJob = scope.launch {
            val apk = UpdateDownloader.download(context, update) { progress ->
                state = UpdateState.Downloading(update, progress)
            }
            state = when {
                apk == null -> UpdateState.Failed(update)
                UpdateDownloader.canRequestInstall(context) -> UpdateState.Ready(update, apk)
                else -> UpdateState.NeedsPermission(update, apk)
            }
        }
    }

    // L'état reste vivant, seul l'affichage est suspendu.
    if (!isVisible) return

    when (val current = state) {
        UpdateState.Idle -> Unit

        is UpdateState.Available -> UpdateDialog(
            title = "Nouvelle version disponible",
            testTag = "update_available_dialog",
            body = {
                Text(
                    text = "Version ${current.update.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldPrimary
                )
                if (current.update.notes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    current.update.notes.forEach { note ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "• ",
                                fontWeight = FontWeight.Bold,
                                color = EmeraldPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmLabel = "Télécharger",
            onConfirm = { startDownload(current.update) },
            dismissLabel = "Plus tard",
            onDismiss = dismiss
        )

        is UpdateState.Downloading -> UpdateDialog(
            title = "Téléchargement…",
            testTag = "update_downloading_dialog",
            body = {
                LinearProgressIndicator(
                    progress = { current.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = EmeraldPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${(current.progress * 100).toInt()} %",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmLabel = null,
            onConfirm = {},
            dismissLabel = "Annuler",
            onDismiss = dismiss
        )

        is UpdateState.Ready -> UpdateDialog(
            title = "Prêt à installer",
            testTag = "update_ready_dialog",
            body = {
                Text(
                    text = "La version ${current.update.versionName} est téléchargée. " +
                            "Android va vous demander de confirmer l'installation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmLabel = "Installer",
            onConfirm = {
                context.startActivity(UpdateDownloader.installIntent(context, current.apk))
                state = UpdateState.Idle
            },
            dismissLabel = "Plus tard",
            onDismiss = dismiss
        )

        is UpdateState.NeedsPermission -> UpdateDialog(
            title = "Autorisation requise",
            testTag = "update_permission_dialog",
            body = {
                Text(
                    text = "Pour installer la mise à jour, Android demande d'autoriser " +
                            "« Mes parcours » à installer des applications. " +
                            "Cette autorisation n'est à donner qu'une seule fois.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmLabel = "Ouvrir les réglages",
            onConfirm = {
                context.startActivity(UpdateDownloader.unknownSourcesSettingsIntent(context))
                state = UpdateState.Ready(current.update, current.apk)
            },
            dismissLabel = "Plus tard",
            onDismiss = dismiss
        )

        is UpdateState.Failed -> UpdateDialog(
            title = "Téléchargement interrompu",
            testTag = "update_failed_dialog",
            body = {
                Text(
                    text = "La mise à jour n'a pas pu être téléchargée. " +
                            "Vérifiez la connexion et réessayez.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmLabel = "Réessayer",
            onConfirm = { startDownload(current.update) },
            dismissLabel = "Fermer",
            onDismiss = dismiss
        )
    }
}

/** Coquille commune aux étapes, pour garder la même présentation. */
@Composable
private fun UpdateDialog(
    title: String,
    testTag: String,
    body: @Composable () -> Unit,
    confirmLabel: String?,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Update,
                contentDescription = null,
                tint = EmeraldPrimary
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                body()
            }
        },
        confirmButton = {
            if (confirmLabel != null) {
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.testTag(testTag)
    )
}

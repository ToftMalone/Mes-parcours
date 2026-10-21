package com.example.ui.screen

import com.example.R
import androidx.compose.ui.res.stringResource
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

/** Steps of the update proposal. */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    data class Ready(val update: AvailableUpdate, val apk: File) : UpdateState
    data class NeedsPermission(val update: AvailableUpdate, val apk: File) : UpdateState
    data class Failed(val update: AvailableUpdate, val progress: Float) : UpdateState
}

/**
 * Looks for a new version at startup and offers to install it.
 *
 * Never displays anything as long as no update is available, and stays
 * entirely inert if [UpdateConfig] is not filled in: no request is
 * then issued. A failed search is silent — not reaching the server is none
 * of the user's concern.
 *
 * Each step is shown in a bottom sheet, as on the mockup.
 *
 * @param reopenTrigger Incremented by the caller (the settings button) to
 * reopen the sheet on the update already detected, without launching a network
 * search again or restarting the app.
 * @param isVisible Suspends the display without leaving the composition, while another
 * screen takes the place. Really leaving the composition would erase the update
 * already found, and the next return would relaunch a network request while
 * reopening a sheet the user had dismissed.
 * @param onUpdateAvailable Notifies the caller as soon as an update is detected,
 * so that it can show a persistent badge even once the sheet is ignored.
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
    // Kept even once the sheet is dismissed (state back to Idle), to be able to
    // reopen it on the same update without consulting the network again.
    var knownUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    // “Continue in background”: the sheet tucks away, the download continues
    // as long as the app is open, and the sheet comes back by itself
    // at the next step (ready to install, or interrupted).
    var hiddenWhileDownloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Cleanup first, and at every launch rather than only when a newer
        // version is discovered: an already downloaded APK will never be used again —
        // `download` systematically rewrites the file — and therefore has no reason
        // to survive until the next launch, whether the installation succeeded or
        // the user gave up on it. Doing it only when something new
        // showed up left some twenty MiB on disk between two
        // releases, backed up with the rest until `backup_rules.xml`
        // excluded them.
        //
        // On Dispatchers.IO: it is a directory walk and deletions,
        // nothing that belongs on the main thread.
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
            // A download already in progress is shown as is rather than starting
            // from zero.
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

    // The state stays alive, only the display is suspended.
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
            SheetTitle(stringResource(R.string.update_new_version))
            Text(
                stringResource(R.string.update_version_label, current.update.versionName),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            val notes = current.update.notesFor(java.util.Locale.getDefault().language)
            if (notes.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    notes.forEach { note ->
                        Text("• $note", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                    }
                }
            }
            SheetButtons(
                laterLabel = stringResource(R.string.common_later),
                onLater = dismiss,
                confirmLabel = stringResource(R.string.update_download),
                confirmIcon = Icons.Rounded.Download,
                onConfirm = { startDownload(current.update) }
            )
        }

        is UpdateState.Downloading -> if (!hiddenWhileDownloading) {
            UpdateSheet(onDismiss = { hiddenWhileDownloading = true }, testTag = "update_downloading_dialog") {
                Text(
                    stringResource(R.string.update_downloading, current.update.versionName),
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
                    MpTextButton(stringResource(R.string.common_cancel), onClick = dismiss)
                    MpTextButton(stringResource(R.string.update_continue_background), onClick = { hiddenWhileDownloading = true })
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
            SheetTitle(stringResource(R.string.update_ready_title))
            SheetBody(stringResource(R.string.update_ready_body, current.update.versionName))
            SheetButtons(
                laterLabel = stringResource(R.string.common_later),
                onLater = dismiss,
                confirmLabel = stringResource(R.string.update_install),
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
            SheetTitle(stringResource(R.string.update_permission_title))
            SheetBody(stringResource(R.string.update_permission_body))
            SheetButtons(
                laterLabel = stringResource(R.string.common_later),
                onLater = dismiss,
                confirmLabel = stringResource(R.string.update_open_settings),
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
            SheetTitle(stringResource(R.string.update_failed_title))
            val pct = (current.progress * 100).toInt()
            SheetBody(
                if (pct > 0) stringResource(R.string.update_failed_at_percent, pct)
                else stringResource(R.string.update_failed_generic)
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
                laterLabel = stringResource(R.string.common_later),
                onLater = dismiss,
                confirmLabel = stringResource(R.string.update_retry),
                confirmIcon = Icons.Rounded.Refresh,
                onConfirm = { startDownload(current.update) }
            )
        }
    }
}

/** Common shell for the steps, to keep the same presentation. */
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

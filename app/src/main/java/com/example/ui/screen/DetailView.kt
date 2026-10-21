package com.example.ui.screen

import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.WrongLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.ui.component.CookieShape
import com.example.ui.component.LocalAppMessenger
import com.example.ui.component.MapViewContainer
import com.example.ui.component.MpDialog
import com.example.ui.component.MpSheet
import com.example.ui.component.ShapeBadge
import com.example.ui.component.SheetHeader
import com.example.ui.component.SunShape
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.StatXlTextStyle
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils
import com.example.util.TrackStylePreferences
import kotlinx.coroutines.delay

/** Height of the map at the top of the sheet; the statistics drawer overlaps it by 32 dp. */
private val MAP_HEIGHT = 340.dp
private val DRAWER_TOP = 308.dp

@Composable
fun DetailView(
    trackId: Long,
    viewModel: TrackViewModel,
    onBackClick: () -> Unit,
    onResumeTrack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    val isTracking by viewModel.isTracking.collectAsState()
    val activeTrackId by viewModel.currentTrackId.collectAsState()

    val goBack = {
        viewModel.selectTrack(null)
        onBackClick()
    }

    // Support system back press
    BackHandler { goBack() }

    // Bind selection for loading
    LaunchedEffect(trackId) {
        viewModel.selectTrack(trackId)
    }

    val track by viewModel.selectedTrack.collectAsState()
    val points by viewModel.selectedTrackPoints.collectAsState()

    // Points arrive after the track itself. Without this delay, a track whose
    // points are still being read would pass for “empty” for a fraction of a second,
    // its warning flashing before the map.
    var pointsSettled by remember(trackId) { mutableStateOf(false) }
    LaunchedEffect(trackId) {
        delay(800)
        pointsSettled = true
    }

    var isRenaming by rememberSaveable { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showRefused by rememberSaveable { mutableStateOf(false) }

    // File name offered to Android's picker: the track's own, stripped
    // of what a file system would refuse.
    fun fileNameFor(t: Track, extension: String) =
        t.name.replace("[\\\\/:*?\"<>|]".toRegex(), "_").replace("\\s+".toRegex(), "_") + extension

    lateinit var launchGpx: () -> Unit
    lateinit var launchKml: () -> Unit

    fun onExportResult(fileName: String, retry: () -> Unit) = Pair(
        { messenger.show(context.getString(R.string.export_done, fileName), Icons.Rounded.DownloadDone) },
        { err: String ->
            messenger.show(
                context.getString(R.string.export_failed, err),
                Icons.Rounded.Error,
                isError = true,
                actionLabel = context.getString(R.string.common_retry),
                onAction = retry
            )
        }
    )

    val gpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            track?.let { t ->
                val (ok, fail) = onExportResult(fileNameFor(t, ".gpx")) { launchGpx() }
                viewModel.saveGPXToUri(context, it, t, onSuccess = ok, onError = fail)
            }
        }
    }

    val kmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            track?.let { t ->
                val (ok, fail) = onExportResult(fileNameFor(t, ".kml")) { launchKml() }
                viewModel.saveKMLToUri(context, it, t, onSuccess = ok, onError = fail)
            }
        }
    }
    launchGpx = { track?.let { gpxLauncher.launch(fileNameFor(it, ".gpx")) } }
    launchKml = { track?.let { kmlLauncher.launch(fileNameFor(it, ".kml")) } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("detail_screen")
    ) {
        val currentTrack = track
        val trackColor = currentTrack?.let {
            Color(
                TrackStylePreferences.resolveTrackColor(
                    it.displayColor, it.sourceColor, it.isImported, it.isMerged
                )
            )
        } ?: MaterialTheme.colorScheme.primary

        // Used both for the map color (red while the recording
        // continues, otherwise this Detail shows the current track in its default
        // color, as if it were already finished) and for the “Resume” button.
        val isCurrentRecording = currentTrack != null &&
            (currentTrack.isRecording || (isTracking && activeTrackId == currentTrack.id))

        val isEmpty = currentTrack != null && points.isEmpty() && pointsSettled &&
            currentTrack.totalDistance == 0.0
        val isLoading = currentTrack == null

        // ------------------------------------------------------------ Map
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MAP_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            when {
                isLoading || (points.isEmpty() && !isEmpty) -> MapLoadingPlaceholder()
                isEmpty -> NoCoordinatesPlaceholder()
                else -> {
                    MapViewContainer(
                        points = points,
                        modifier = Modifier.fillMaxSize(),
                        isInteractivityEnabled = false,
                        isImported = currentTrack!!.isImported,
                        isMerged = currentTrack.isMerged,
                        sourceColor = currentTrack.sourceColor,
                        displayColor = currentTrack.displayColor,
                        displayWidthDp = currentTrack.displayWidthDp,
                        isCurrentTracking = isCurrentRecording,
                        onViewportChanged = { viewModel.updateMapViewport(it) }
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 22.dp, end = 16.dp)
                    ) {
                        LegendChip(stringResource(R.string.legend_start), MaterialTheme.colorScheme.primary)
                        LegendChip(
                            stringResource(if (isCurrentRecording) R.string.legend_position else R.string.legend_finish),
                            MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Surface(
                onClick = goBack,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 12.dp)
                    .size(48.dp)
                    .testTag("back_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), modifier = Modifier.size(24.dp))
                }
            }
        }

        // ------------------------------------------------- Statistics drawer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = DRAWER_TOP)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 120.dp)
                .navigationBarsPadding()
        ) {
            when {
                currentTrack == null -> StatsSkeleton()
                isEmpty -> EmptyTrackContent(
                    track = currentTrack,
                    trackColor = trackColor,
                    onDelete = { showDelete = true }
                )
                else -> TrackStatsContent(
                    track = currentTrack,
                    trackColor = trackColor,
                    points = points,
                    onRename = {
                        renameText = currentTrack.name
                        isRenaming = true
                    }
                )
            }
        }

        // ------------------------------------------------------- Bottom actions
        if (currentTrack != null && !isEmpty) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
            ) {
                if (!isCurrentRecording) {
                    BottomAction(
                        text = stringResource(R.string.detail_resume_track),
                        icon = Icons.Rounded.Route,
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                        onClick = {
                            if (isTracking) {
                                showRefused = true
                            } else {
                                viewModel.resumeTrack(context, currentTrack.id) {
                                    messenger.show(context.getString(R.string.recording_resumed, currentTrack.name), Icons.Rounded.Route)
                                    viewModel.selectTrack(null)
                                    onBackClick()
                                    onResumeTrack?.invoke()
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("resume_track_button")
                    )
                }
                BottomAction(
                    text = stringResource(R.string.detail_export),
                    icon = Icons.Rounded.IosShare,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { showExport = true },
                    modifier = (if (isCurrentRecording) Modifier.weight(1f) else Modifier)
                        .testTag("export_button")
                )
            }
        }
    }

    // ------------------------------------------------------------ Sheets

    if (showExport && track != null) {
        MpSheet(onDismissRequest = { showExport = false }) {
            SheetHeader(stringResource(R.string.export_sheet_title), stringResource(R.string.export_sheet_subtitle))
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ExportCard(
                    format = "GPX",
                    description = stringResource(R.string.export_gpx_description),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        showExport = false
                        launchGpx()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_gpx_button")
                )
                ExportCard(
                    format = "KML",
                    description = stringResource(R.string.export_kml_description),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = {
                        showExport = false
                        launchKml()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_kml_button")
                )
            }
        }
    }

    // ------------------------------------------------------------ Dialogs

    if (isRenaming) {
        val current = track
        MpDialog(
            onDismissRequest = { isRenaming = false },
            title = stringResource(R.string.rename_title),
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.rename_title),
            confirmEnabled = renameText.isNotBlank(),
            onConfirm = {
                current?.let { viewModel.renameTrack(it, renameText) }
                isRenaming = false
                messenger.show(context.getString(R.string.track_renamed_toast), Icons.Rounded.Edit)
            }
        ) {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                singleLine = true,
                label = { Text(stringResource(R.string.rename_field_label)) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .testTag("rename_track_field")
            )
        }
    }

    if (showDelete) {
        track?.let { t ->
            DeleteTrackDialog(
                trackName = t.name,
                onDismiss = { showDelete = false },
                onConfirm = {
                    showDelete = false
                    // A recording that has just started is empty, hence also presents
                    // this button: it is the repository that refuses, and we stay here.
                    viewModel.deleteTrack(t.id) { deleted ->
                        if (deleted) {
                            messenger.show(context.getString(R.string.track_deleted_toast), Icons.Rounded.Delete)
                            goBack()
                        } else {
                            messenger.show(context.getString(R.string.delete_refused_recording), Icons.Rounded.Error, isError = true)
                        }
                    }
                }
            )
        }
    }

    if (showRefused) {
        track?.let { t ->
            ResumeRefusedDialog(
                trackName = t.name,
                onDismiss = { showRefused = false },
                onOpenRecording = {
                    showRefused = false
                    viewModel.selectTrack(null)
                    onResumeTrack?.invoke() ?: onBackClick()
                }
            )
        }
    }
}

// ------------------------------------------------------------------ Contents

@Composable
private fun TrackStatsContent(
    track: Track,
    trackColor: Color,
    points: List<TrackPoint>,
    onRename: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = track.name,
            style = DetailTitleStyle(),
            color = colors.onBackground,
            modifier = Modifier
                .weight(1f)
                .testTag("detail_track_name")
        )
        Surface(
            onClick = onRename,
            shape = CircleShape,
            color = colors.surfaceContainer,
            contentColor = colors.onSurface,
            modifier = Modifier
                .size(44.dp)
                .testTag("rename_track_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.rename_track_cd), modifier = Modifier.size(22.dp))
            }
        }
    }
    TrackMeta(track, trackColor)

    Spacer(modifier = Modifier.height(16.dp))
    // Distance and duration: the feature card, across the full width.
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.primaryContainer)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.detail_stat_distance), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            Text(
                FormatUtils.formatDistanceShort(track.totalDistance),
                style = StatXlTextStyle.copy(fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-1).sp),
                color = colors.onPrimaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.detail_stat_duration), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            Text(
                FormatUtils.formatDurationShort(track.duration),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 32.sp),
                color = colors.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(stringResource(R.string.detail_stat_avg_speed), FormatUtils.formatSpeed(track.avgSpeed), modifier = Modifier.weight(1f))
        StatCard(stringResource(R.string.detail_stat_max_speed), FormatUtils.formatSpeed(track.maxSpeed), modifier = Modifier.weight(1f))
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(
            stringResource(R.string.detail_stat_elev_gain), "+" + FormatUtils.formatElevationShort(track.elevationGain),
            icon = Icons.Rounded.TrendingUp, iconTint = colors.primary, modifier = Modifier.weight(1f)
        )
        StatCard(
            stringResource(R.string.detail_stat_elev_loss), "−" + FormatUtils.formatElevationShort(track.elevationLoss),
            icon = Icons.Rounded.TrendingDown, iconTint = colors.tertiary, modifier = Modifier.weight(1f)
        )
    }

    val profile = remember(points) { altitudeProfile(points) }
    if (profile != null) {
        Spacer(modifier = Modifier.height(8.dp))
        AltitudeProfileCard(profile, FormatUtils.formatDistanceShort(profile.totalMeters))
    }
}

@Composable
private fun EmptyTrackContent(track: Track, trackColor: Color, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(track.name, style = DetailTitleStyle(), color = colors.onBackground)
    TrackMeta(track, trackColor)
    Spacer(modifier = Modifier.height(18.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.tertiaryContainer)
            .padding(18.dp)
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(24.dp))
        Column {
            Text(
                stringResource(R.string.detail_empty_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.onTertiaryContainer
            )
            Text(
                stringResource(R.string.detail_empty_body),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.detail_stat_distance, R.string.detail_stat_duration, R.string.detail_stat_elevation).forEach { labelRes ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surfaceContainerLow)
                    .padding(14.dp)
            ) {
                Text(stringResource(labelRes), fontSize = 12.sp, color = colors.onSurfaceVariant)
                Text("—", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), color = colors.onSurface)
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    BottomAction(
        text = stringResource(R.string.detail_delete_empty),
        icon = Icons.Rounded.Delete,
        container = colors.errorContainer,
        content = colors.onErrorContainer,
        onClick = onDelete,
        height = 52.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("delete_empty_track_button")
    )
}

@Composable
private fun DetailTitleStyle() = MaterialTheme.typography.headlineMedium.copy(
    fontFamily = DisplayFontFamily,
    fontSize = 28.sp,
    lineHeight = 31.sp
)

/** “● Recorded · Sun. Sep 27 · 09:14”. */
@Composable
private fun TrackMeta(track: Track, trackColor: Color) {
    val category = when {
        track.isMerged -> stringResource(R.string.track_kind_merged)
        track.isImported -> stringResource(R.string.track_kind_imported)
        else -> stringResource(R.string.track_kind_recorded)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(trackColor)
        )
        Text(
            stringResource(R.string.track_meta, category, FormatUtils.formatDayDate(track.startTime)),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 28.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun LegendChip(label: String, dot: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun BottomAction(
    text: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 60.dp
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(height / 2),
        color = container,
        contentColor = content,
        modifier = modifier.height(height)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.padding(horizontal = 22.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun ExportCard(
    format: String,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(format, style = StatXlTextStyle.copy(fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = 0.sp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// --------------------------------------------------------- Loading and empty

/** Map being loaded: a spinning cookie on a neutral background. */
@Composable
private fun MapLoadingPlaceholder() {
    val transition = rememberInfiniteTransition(label = "detail_loading")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "detail_loading_angle"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            )
            .testTag("detail_loading")
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .rotate(angle)
                .clip(CookieShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun NoCoordinatesPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("detail_no_coordinates")
    ) {
        ShapeBadge(
            icon = Icons.Rounded.WrongLocation,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = SunShape,
            size = 96.dp,
            iconSize = 44.dp
        )
        Text(
            stringResource(R.string.detail_no_coordinates),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Skeleton of the drawer while the track loads. */
@Composable
private fun StatsSkeleton() {
    val block = MaterialTheme.colorScheme.surfaceContainer
    @Composable
    fun Bar(width: Float, height: Dp, radius: Dp) = Box(
        modifier = Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(block)
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Bar(0.7f, 28.dp, 14.dp)
        Bar(0.4f, 16.dp, 8.dp)
        Spacer(modifier = Modifier.height(0.dp))
        Bar(1f, 88.dp, 28.dp)
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(block)
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ Elevation profile

private class AltitudeProfile(
    /** Cumulative distance (m) and altitude (m) of each retained point. */
    val distances: FloatArray,
    val altitudes: FloatArray,
    val totalMeters: Double,
    val min: Double,
    val max: Double
)

/**
 * Elevation profile drawn from the displayed points.
 *
 * These are the display points (silhouette and detail of the visible area, see
 * invariant 3), never reread for the occasion: for a curve 84 dp high,
 * a few hundred points are plenty. They are put back in track
 * order (by identifier) and deduplicated, since silhouette and detail may
 * overlap.
 *
 * No profile without usable altitude: a KML without `<ele>` carries altitudes
 * all at zero, which would draw a misleading flat line.
 */
private fun altitudeProfile(points: List<TrackPoint>): AltitudeProfile? {
    if (points.size < 2) return null
    val ordered = points.distinctBy { it.id }.sortedBy { it.id }
    val alts = ordered.map { it.altitude }
    val min = alts.min()
    val max = alts.max()
    if (max - min < 1.0) return null

    val distances = FloatArray(ordered.size)
    var total = 0.0
    for (i in 1 until ordered.size) {
        // A segment that resumes after a pause does not count the straight-line
        // distance separating it from the previous one, as everywhere else.
        if (!ordered[i].isDiscontinuous) {
            total += haversine(ordered[i - 1], ordered[i])
        }
        distances[i] = total.toFloat()
    }
    if (total <= 0.0) return null
    return AltitudeProfile(distances, alts.map { it.toFloat() }.toFloatArray(), total, min, max)
}

private fun haversine(a: TrackPoint, b: TrackPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * r * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
}

@Composable
private fun AltitudeProfileCard(profile: AltitudeProfile, totalLabel: String) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerLow)
            .padding(16.dp)
            .testTag("altitude_profile")
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.detail_altitude_profile), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onSurface, modifier = Modifier.weight(1f))
            Text(
                "${FormatUtils.formatElevationShort(profile.min).removeSuffix(" m")} – ${FormatUtils.formatElevationShort(profile.max)}",
                fontSize = 13.sp,
                color = colors.onSurfaceVariant
            )
        }
        val line = colors.primary
        val area = colors.primaryContainer
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(top = 10.dp)
        ) {
            val w = size.width
            val h = size.height
            val span = (profile.max - profile.min).toFloat()
            // A margin at the top, so the peak does not touch the edge.
            fun y(alt: Float) = h - (alt - profile.min.toFloat()) / span * h * 0.85f
            fun x(d: Float) = d / profile.totalMeters.toFloat() * w
            val curve = Path().apply {
                moveTo(x(profile.distances[0]), y(profile.altitudes[0]))
                for (i in 1 until profile.distances.size) lineTo(x(profile.distances[i]), y(profile.altitudes[i]))
            }
            val fill = Path().apply {
                addPath(curve)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(fill, area)
            drawPath(curve, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)) {
            Text("0 km", fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(totalLabel, fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}

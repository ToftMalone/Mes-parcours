package com.example.ui.screen

import androidx.annotation.StringRes
import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Polyline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Track
import com.example.ui.component.TrackThicknessControl
import com.example.ui.component.CookieShape
import com.example.ui.component.LocalAppMessenger
import com.example.ui.component.MpDialog
import com.example.ui.component.MpFilledButton
import com.example.ui.component.MpSheet
import com.example.ui.component.MpSwitch
import com.example.ui.component.MpTextButton
import com.example.ui.component.ShapeBadge
import com.example.ui.component.SheetHeader
import com.example.ui.component.SunShape
import com.example.ui.component.TrackTile
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils
import com.example.util.TrackStylePreferences
import kotlinx.coroutines.delay

/** The three history categories, which do not overlap. */
private enum class HistoryCategory(@StringRes val labelRes: Int) {
    RECORDED(R.string.history_recorded),
    IMPORTED(R.string.history_imported),
    MERGED(R.string.history_merged)
}

@Composable
fun HistoryTab(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onResumeTrack: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Margins left by the status bar and the floating navigation bar. */
    contentPadding: PaddingValues = PaddingValues(),
    /** “Start a recording” (empty state) and “View recording”. */
    onOpenRecording: () -> Unit = {},
    /** “Open tools”, from the empty state of merged tracks. */
    onOpenTools: () -> Unit = {}
) {
    val tracks by viewModel.allTracks.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    var selectedCategory by rememberSaveable { mutableStateOf(HistoryCategory.RECORDED) }

    // Track whose actions sheet, palette or deletion confirmation
    // is open; null = closed. We keep the identifier rather than the track: the
    // list recomposes at every write, and the sheet must show the up-to-date state.
    var actionsTrackId by remember { mutableStateOf<Long?>(null) }
    var colorTrackId by remember { mutableStateOf<Long?>(null) }
    var deleteTrackId by remember { mutableStateOf<Long?>(null) }
    var refusedTrackId by remember { mutableStateOf<Long?>(null) }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importTrack(
                context = context,
                uri = it,
                onSuccess = { trackId ->
                    messenger.show(context.getString(R.string.track_imported_toast), Icons.Rounded.TaskAlt)
                    viewModel.selectTrack(trackId)
                    onNavigateToDetails(trackId)
                },
                onError = { error ->
                    messenger.show(error, Icons.Rounded.Error, isError = true)
                }
            )
        }
    }
    val importFile = { filePickerLauncher.launch("*/*") }

    // Three categories that do not overlap: a merged track leaves the one
    // it came from, otherwise it would appear twice in the history.
    //
    // Merges made between the tab's removal and its return carry isMerged
    // at false in the database: they therefore stay in their original category. Nothing
    // makes it possible to recognize them afterwards, and moving them unprompted would be worse than
    // leaving them where the user has seen them so far.
    val byCategory = mapOf(
        HistoryCategory.RECORDED to tracks.filter { !it.isImported && !it.isMerged },
        HistoryCategory.IMPORTED to tracks.filter { it.isImported && !it.isMerged },
        HistoryCategory.MERGED to tracks.filter { it.isMerged }
    )
    val shown = byCategory.getValue(selectedCategory)

    // Resuming a track: refused, with an explanation, while a recording is running.
    val resume: (Track) -> Unit = { track ->
        if (isTracking) {
            refusedTrackId = track.id
        } else {
            viewModel.resumeTrack(context, track.id) {
                messenger.show(context.getString(R.string.recording_resumed, track.name), Icons.Rounded.Polyline)
                onResumeTrack(track.id)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(contentPadding.calculateTopPadding()))
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
                    .testTag("history_categories")
            ) {
                HistoryCategory.entries.forEach { category ->
                    CategoryChip(
                        // Stable identifier: the label changes with the language.
                        tag = category.name,
                        label = stringResource(category.labelRes),
                        count = byCategory.getValue(category).size,
                        selected = category == selectedCategory,
                        onClick = { selectedCategory = category },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (shown.isEmpty()) {
                    CategoryEmptyState(
                        category = selectedCategory,
                        onAction = when (selectedCategory) {
                            HistoryCategory.RECORDED -> onOpenRecording
                            HistoryCategory.IMPORTED -> importFile
                            HistoryCategory.MERGED -> onOpenTools
                        },
                        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding())
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // The bottom lets the import button and the floating bar through:
                        // the last card must be able to scroll up above both.
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = contentPadding.calculateBottomPadding() + 96.dp
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("history_list")
                    ) {
                        itemsIndexed(shown, key = { _, it -> it.id }) { index, track ->
                            StaggeredHistoryCard(index) {
                                TrackHistoryCard(
                                    track = track,
                                    trackColor = Color(trackDisplayColor(track)),
                                    onClick = { onNavigateToDetails(track.id) },
                                    isSelectedForMap = track.isSelectedForMap,
                                    onMapSelectionToggle = { viewModel.toggleTrackSelectionForMap(track) },
                                    onColorClick = { colorTrackId = track.id },
                                    onMoreClick = { actionsTrackId = track.id }
                                )
                            }
                        }
                    }
                }
            }
        }

        ImportFab(
            onClick = importFile,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp)
        )
    }

    // ------------------------------------------------------------ Sheets

    tracks.firstOrNull { it.id == actionsTrackId }?.let { track ->
        TrackActionsSheet(
            track = track,
            trackColor = Color(trackDisplayColor(track)),
            onDismiss = { actionsTrackId = null },
            onToggleMap = { viewModel.toggleTrackSelectionForMap(track) },
            onChangeColor = {
                actionsTrackId = null
                colorTrackId = track.id
            },
            onResume = {
                actionsTrackId = null
                resume(track)
            },
            // No deletion for the track being recorded: see TrackRepository.deleteTrack.
            onDelete = if (viewModel.isBeingRecorded(track)) null else {
                {
                    actionsTrackId = null
                    deleteTrackId = track.id
                }
            }
        )
    }

    tracks.firstOrNull { it.id == colorTrackId }?.let { track ->
        TrackColorSheet(
            track = track,
            onDismiss = { colorTrackId = null },
            onApply = { color, widthDp ->
                viewModel.setTrackStyle(track, color, widthDp)
                colorTrackId = null
            }
        )
    }

    // ------------------------------------------------------------ Dialogs

    tracks.firstOrNull { it.id == deleteTrackId }?.let { track ->
        DeleteTrackDialog(
            trackName = track.name,
            onDismiss = { deleteTrackId = null },
            onConfirm = {
                deleteTrackId = null
                viewModel.deleteTrack(track.id) { deleted ->
                    if (deleted) messenger.show(context.getString(R.string.track_deleted_toast), Icons.Rounded.Delete)
                    else messenger.show(context.getString(R.string.delete_refused_recording), Icons.Rounded.Error, isError = true)
                }
            }
        )
    }

    tracks.firstOrNull { it.id == refusedTrackId }?.let { track ->
        ResumeRefusedDialog(
            trackName = track.name,
            onDismiss = { refusedTrackId = null },
            onOpenRecording = {
                refusedTrackId = null
                onOpenRecording()
            }
        )
    }
}

/**
 * Makes a history card appear by sliding in, with a slight offset depending on
 * [index], and also animates its place in the list ([LazyItemScope.animateItem]) —
 * useful on deletion or category change, where the remaining
 * cards slide to their new place rather than jump.
 *
 * The offset between cards is capped: beyond a dozen, waiting one's turn
 * would take longer than the user takes to scroll there. It is
 * a trim, not a slowdown — an app sometimes consulted one-handed
 * mid-trip must not make people wait to read their statistics.
 *
 * **The slide is driven by hand, and above all not entrusted to `AnimatedVisibility`**,
 * which **clips its content to its own bounds** as soon as a transition moves it. Content
 * moved down by a sixth of its height is therefore sliced off flat at the bottom —
 * rounded corner and shadow included. Measured frame by frame on the author's screen recording:
 * throughout the animation the card stopped on a sharp edge with
 * the background right below, not one pixel of shadow, then the shadow reappeared all
 * at once on the last frame. That is exactly the reported defect.
 *
 * A `graphicsLayer` clips nothing (`clip` is `false` by default): what overflows
 * is drawn whole from one end of the movement to the other.
 *
 * **No progressive fade here either, and for a separate reason.** As soon as an
 * *intermediate* opacity is applied, Android composes the element offscreen in a
 * buffer **at the exact dimensions of the card**: everything that overflows ends up
 * sliced. The cards of the redesign no longer carry a shadow, but the rule holds for
 * whoever gives them one back. The fade that `animateItem()` applies by itself is
 * turned off for this reason (`fadeInSpec` and `fadeOutSpec` are not null by default,
 * which its name does not let you guess). Only the **binary** opacity below is
 * allowed: at zero, nothing is drawn, so there is nothing to slice.
 *
 * Do not reintroduce a progressive fade or an `AnimatedVisibility` transition on an
 * element that carries a shadow without first removing its elevation.
 */
@Composable
private fun LazyItemScope.StaggeredHistoryCard(index: Int, content: @Composable () -> Unit) {
    // 1 = card still offset downward, 0 = in place.
    val slide = remember { Animatable(1f) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(minOf(index, 12) * 30L)
        visible = true
        slide.animateTo(0f, tween(260))
    }
    Box(
        modifier = Modifier
            .animateItem(fadeInSpec = null, fadeOutSpec = null)
            .graphicsLayer {
                translationY = slide.value * 24.dp.toPx()
                // All or nothing: the card is not drawn before its turn. An intermediate
                // value would slice its shadow (see above).
                alpha = if (visible) 1f else 0f
            }
    ) {
        content()
    }
}

/**
 * Display color of a track: its explicit choice, failing that its file's,
 * failing that the default of its category.
 *
 * A simple shortcut so as not to rewrite the four arguments on every card.
 */
private fun trackDisplayColor(track: Track): Int =
    TrackStylePreferences.resolveTrackColor(
        displayColor = track.displayColor,
        sourceColor = track.sourceColor,
        isImported = track.isImported,
        isMerged = track.isMerged
    )

/**
 * Category chip: full and rounded as a pill when chosen, a soft
 * rectangle otherwise — the change of radius is the mockup's animation.
 */
@Composable
private fun CategoryChip(
    tag: String,
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val radius by animateDpAsState(if (selected) 24.dp else 10.dp, spring(0.6f, 800f), label = "chip_radius")
    val container by animateColorAsState(if (selected) colors.primary else colors.surfaceContainer, label = "chip_bg")
    val content by animateColorAsState(if (selected) colors.onPrimary else colors.onSurfaceVariant, label = "chip_fg")
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(radius))
            .background(container)
            .clickable(onClick = onClick)
            .testTag("history_category_$tag")
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = content,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) colors.onPrimary else colors.surfaceContainerHighest)
                .padding(horizontal = 6.dp)
        ) {
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) colors.primary else colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Card of a track in the history: colored tile, name, date, three figures,
 * then the eye that shows it on the map and the actions menu.
 *
 * The colored tile always opens the track's palette, like the chip it
 * replaces; the actions sheet leads there too, for those who would not guess the gesture.
 */
@Composable
fun TrackHistoryCard(
    track: Track,
    onClick: () -> Unit,
    isSelectedForMap: Boolean = false,
    onMapSelectionToggle: (Boolean) -> Unit = {},
    /** Opens this track's palette; null makes the tile inert. */
    onColorClick: (() -> Unit)? = null,
    /** Opens the actions sheet; null hides the button. */
    onMoreClick: (() -> Unit)? = null,
    /** Display color of this specific track. */
    trackColor: Color = Color(
        TrackStylePreferences.defaultColorFor(track.isImported, track.isMerged)
    )
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 14.dp, bottom = 14.dp)
            .testTag("track_card_${track.id}")
    ) {
        TrackTile(
            color = trackColor,
            modifier = if (onColorClick != null) {
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onColorClick)
                    .testTag("track_color_button_${track.id}")
            } else Modifier
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // A track that still carries its file's color says so: it is
                // the only possible origin of a file color here.
                if (track.sourceColor != null && track.displayColor == null) {
                    OutlinedTag(stringResource(R.string.tag_google_earth))
                }
            }
            Text(
                text = trackSubtitle(track),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                CardStat(Icons.Rounded.Straighten, FormatUtils.formatDistanceShort(track.totalDistance))
                CardStat(Icons.Rounded.Schedule, FormatUtils.formatDurationShort(track.duration))
                CardStat(Icons.Rounded.TrendingUp, FormatUtils.formatElevationShort(track.elevationGain))
            }
        }

        val eyeBg by animateColorAsState(
            if (isSelectedForMap) colors.primaryContainer else colors.surfaceContainer,
            label = "eye_bg"
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(eyeBg)
                .clickable { onMapSelectionToggle(!isSelectedForMap) }
                .testTag("map_toggle_${track.id}")
        ) {
            Icon(
                imageVector = if (isSelectedForMap) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = stringResource(if (isSelectedForMap) R.string.track_hide_from_map else R.string.track_show_on_map),
                tint = if (isSelectedForMap) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        if (onMoreClick != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 28.dp, height = 44.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onMoreClick)
                    .testTag("track_actions_${track.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.track_actions),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** Date, or origin for an imported or merged track. */
@Composable
private fun trackSubtitle(track: Track): String = when {
    track.isMerged -> stringResource(R.string.track_subtitle_merged, FormatUtils.formatShortDate(track.startTime))
    track.isImported -> stringResource(R.string.track_subtitle_imported, FormatUtils.formatShortDate(track.startTime))
    else -> FormatUtils.formatDayDate(track.startTime)
}

@Composable
private fun CardStat(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun OutlinedTag(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(20.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Floating “Import GPX / KML” button, above the navigation bar. */
@Composable
private fun ImportFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier.testTag("import_button")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .height(60.dp)
                .padding(start = 18.dp, end = 22.dp)
        ) {
            Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.history_import_fab), style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
        }
    }
}

// --------------------------------------------------------------------- Empty states

/**
 * Empty state of a category: an illustration on an expressive shape, a title, a
 * sentence, and the action that fills the category — it is not always found in
 * the history (record, merge), nothing would let you guess.
 */
@Composable
private fun CategoryEmptyState(
    category: HistoryCategory,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    data class Empty(
        val icon: ImageVector, val bg: Color, val fg: Color, val shape: Shape,
        val title: String, val body: String, val button: String, val buttonIcon: ImageVector
    )
    val e = when (category) {
        HistoryCategory.RECORDED -> Empty(
            Icons.Rounded.Hiking, colors.primaryContainer, colors.onPrimaryContainer, CookieShape,
            stringResource(R.string.empty_recorded_title),
            stringResource(R.string.empty_recorded_body),
            stringResource(R.string.empty_recorded_button), Icons.Rounded.RadioButtonChecked
        )
        HistoryCategory.IMPORTED -> Empty(
            Icons.Rounded.UploadFile, colors.tertiaryContainer, colors.onTertiaryContainer, SunShape,
            stringResource(R.string.empty_imported_title),
            stringResource(R.string.empty_imported_body),
            stringResource(R.string.empty_imported_button), Icons.Rounded.UploadFile
        )
        HistoryCategory.MERGED -> Empty(
            Icons.Rounded.Merge, colors.secondaryContainer, colors.onSecondaryContainer, RoundedCornerShape(44.dp),
            stringResource(R.string.empty_merged_title),
            stringResource(R.string.empty_merged_body),
            stringResource(R.string.empty_merged_button), Icons.Rounded.Handyman
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp)
            .testTag("history_empty_${category.name.lowercase()}")
    ) {
        ShapeBadge(
            icon = e.icon,
            containerColor = e.bg,
            contentColor = e.fg,
            shape = e.shape,
            size = 168.dp,
            iconSize = 72.dp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = e.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = null),
            color = colors.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = e.body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(22.dp))
        MpFilledButton(
            text = e.button,
            icon = e.buttonIcon,
            onClick = onAction,
            height = 52.dp,
            modifier = Modifier.testTag("empty_state_action_button")
        )
    }
}

/**
 * Empty state of the “Imported” category, with its import button. Kept public for
 * the legacy screenshot test (`GreetingScreenshotTest`).
 */
@Composable
fun EmptyHistoryState(onImportClick: () -> Unit) {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        CategoryEmptyState(HistoryCategory.IMPORTED, onImportClick)
    }
}

// ------------------------------------------------------------------ Sheets

/** Actions sheet of a track: map, color, resume, deletion. */
@Composable
private fun TrackActionsSheet(
    track: Track,
    trackColor: Color,
    onDismiss: () -> Unit,
    onToggleMap: () -> Unit,
    onChangeColor: () -> Unit,
    onResume: () -> Unit,
    onDelete: (() -> Unit)?
) {
    MpSheet(onDismissRequest = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrackTile(color = trackColor, size = 48.dp, corner = 16.dp, iconSize = 26.dp)
            Column {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${FormatUtils.formatDistanceShort(track.totalDistance)} · ${FormatUtils.formatDurationShort(track.duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.clip(RoundedCornerShape(24.dp))
        ) {
            SheetRow(Icons.Rounded.Visibility, stringResource(R.string.sheet_show_on_map), onClick = onToggleMap, testTag = "action_toggle_map") {
                MpSwitch(checked = track.isSelectedForMap, onCheckedChange = { onToggleMap() })
            }
            SheetRow(Icons.Rounded.Palette, stringResource(R.string.sheet_change_color), onClick = onChangeColor, testTag = "action_change_color") {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(trackColor)
                )
            }
            SheetRow(Icons.Rounded.Polyline, stringResource(R.string.sheet_resume_track), onClick = onResume, testTag = "resume_track_card_button_${track.id}")
            if (onDelete != null) {
                SheetRow(
                    Icons.Rounded.Delete, stringResource(R.string.common_delete), onClick = onDelete,
                    color = MaterialTheme.colorScheme.error,
                    testTag = "delete_track_button_${track.id}"
                )
            }
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * Appearance of a track: its color and line thickness, chosen
 * together since the preview shows them together.
 *
 * The choice is only applied on “Apply”: one tries a color or thickness
 * on the preview without redrawing the track at each chip touched.
 *
 * A track whose file carried a color can get it back: “Keep the
 * Google Earth color” sets `displayColor` to null, which is exactly “original
 * color” (see TrackStylePreferences.resolveTrackColor). Offering it on a GPX,
 * or on a recording, would be an option that does nothing: it does not appear.
 */
@Composable
private fun TrackColorSheet(
    track: Track,
    onDismiss: () -> Unit,
    onApply: (color: Int?, widthDp: Float) -> Unit
) {
    val context = LocalContext.current
    var pendingWidth by remember {
        mutableFloatStateOf(TrackStylePreferences.resolveThicknessDp(context, track.displayWidthDp))
    }
    val hasFileColor = track.sourceColor != null
    var keepFileColor by remember { mutableStateOf(hasFileColor && track.displayColor == null) }
    var pending by remember { mutableIntStateOf(trackDisplayColor(track)) }
    val previewColor = if (keepFileColor) track.sourceColor ?: pending else pending

    MpSheet(onDismissRequest = onDismiss) {
        SheetHeader(stringResource(R.string.style_title), track.name)
        Spacer(modifier = Modifier.height(14.dp))
        TrackColorPreview(color = Color(previewColor), widthDp = pendingWidth)

        if (hasFileColor) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { keepFileColor = !keepFileColor }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("color_swatch_from_file")
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.style_keep_file_color), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.style_file_color_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MpSwitch(checked = keepFileColor, onCheckedChange = { keepFileColor = it })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        // The chips fade while the file color is kept: two checked
        // choices at once would read as a bug.
        val dim = if (keepFileColor) 0.38f else 1f
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = dim }
                .testTag("category_color_picker")
        ) {
            TrackStylePreferences.COLOR_PALETTE.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    row.forEach { color ->
                        ColorSwatch(
                            color = Color(color),
                            selected = !keepFileColor && color == pending,
                            enabled = !keepFileColor,
                            onClick = { pending = color },
                            modifier = Modifier.testTag("color_swatch_$color")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        TrackThicknessControl(value = pendingWidth, onValueChange = { pendingWidth = it })

        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            MpTextButton(stringResource(R.string.common_cancel), onDismiss)
            MpFilledButton(
                text = stringResource(R.string.common_apply),
                onClick = { onApply(if (keepFileColor) null else pending, pendingWidth) },
                modifier = Modifier.testTag("color_apply_button")
            )
        }
    }
}

/** Color chip: round at rest, rounded square checked when chosen. */
@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val radius by animateDpAsState(if (selected) 16.dp else 26.dp, spring(0.6f, 800f), label = "swatch_radius")
    val scale by animateFloatAsState(if (selected) 1f else 0.86f, spring(0.6f, 800f), label = "swatch_scale")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(52.dp)
            .scale(scale)
            .clip(RoundedCornerShape(radius))
            .background(color)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.style_color_chosen), tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Preview of the chosen appearance: a wavy line on a neutral map background, at
 * the real thickness — dp converted by the screen density, exactly as
 * `TrackStylePreferences.getStrokeWidth` does for the map.
 */
@Composable
private fun TrackColorPreview(color: Color, widthDp: Float) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.03f, h * 0.68f)
            cubicTo(w * 0.22f, h * 0.30f, w * 0.38f, h * 0.95f, w * 0.55f, h * 0.55f)
            cubicTo(w * 0.70f, h * 0.20f, w * 0.85f, h * 0.30f, w * 0.97f, h * 0.36f)
        }
        drawPath(path, color, style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ----------------------------------------------------------------- Dialogs

@Composable
fun DeleteTrackDialog(trackName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    MpDialog(
        onDismissRequest = onDismiss,
        icon = {
            ShapeBadge(
                icon = Icons.Rounded.DeleteForever,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(22.dp)
            )
        },
        title = stringResource(R.string.delete_dialog_title),
        body = stringResource(R.string.delete_dialog_body, trackName),
        dismissLabel = stringResource(R.string.common_cancel),
        confirmLabel = stringResource(R.string.common_delete),
        onConfirm = onConfirm,
        confirmContainerColor = MaterialTheme.colorScheme.error,
        confirmContentColor = MaterialTheme.colorScheme.onError,
        confirmTestTag = "dialog_delete_confirm_button"
    )
}

/** Resume refused: only one track can be recorded at a time. */
@Composable
fun ResumeRefusedDialog(trackName: String, onDismiss: () -> Unit, onOpenRecording: () -> Unit) {
    MpDialog(
        onDismissRequest = onDismiss,
        icon = {
            ShapeBadge(
                icon = Icons.Rounded.RadioButtonChecked,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = CircleShape
            )
        },
        title = stringResource(R.string.resume_refused_title),
        body = stringResource(R.string.resume_refused_body, trackName),
        dismissLabel = stringResource(R.string.common_ok),
        confirmLabel = stringResource(R.string.resume_refused_confirm),
        onConfirm = onOpenRecording
    )
}

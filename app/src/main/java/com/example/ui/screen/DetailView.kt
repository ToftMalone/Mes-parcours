package com.example.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.material.icons.filled.Save
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.component.MapViewContainer
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailView(
    trackId: Long,
    viewModel: TrackViewModel,
    onBackClick: () -> Unit,
    onResumeTrack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isTracking by viewModel.isTracking.collectAsState()
    val activeTrackId by viewModel.currentTrackId.collectAsState()
    
    // Support system back press
    BackHandler {
        viewModel.selectTrack(null)
        onBackClick()
    }
    
    // Bind selection for loading
    LaunchedEffect(trackId) {
        viewModel.selectTrack(trackId)
    }

    val track by viewModel.selectedTrack.collectAsState()
    val points by viewModel.selectedTrackPoints.collectAsState()

    var isRenaming by rememberSaveable { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }

    val gpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            track?.let { t ->
                viewModel.saveGPXToUri(context, it, t,
                    onSuccess = {
                        Toast.makeText(context, "Fichier GPX enregistré !", Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        Toast.makeText(context, "Erreur de sauvegarde : $err", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    val kmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            track?.let { t ->
                viewModel.saveKMLToUri(context, it, t,
                    onSuccess = {
                        Toast.makeText(context, "Fichier KML enregistré !", Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        Toast.makeText(context, "Erreur de sauvegarde : $err", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = track?.name ?: "Détails du parcours",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    ) 
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.selectTrack(null)
                            onBackClick()
                        },
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    track?.let { current ->
                        IconButton(
                            onClick = {
                                renameText = current.name
                                isRenaming = true
                            },
                            modifier = Modifier.testTag("rename_track_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Renommer le parcours",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        modifier = modifier.fillMaxSize().testTag("detail_screen")
    ) { innerPadding ->
        
        val currentTrack = track
        if (currentTrack == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Chargement de votre parcours...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        val scrollState = rememberScrollState()

        // Utilisé à la fois pour la couleur de la carte (rouge tant que l'enregistrement
        // continue, sans quoi ce Détail affiche la trace en cours dans sa couleur par
        // défaut, comme si elle était déjà terminée) et pour le bouton « Reprendre ».
        val isCurrentRecording = currentTrack.isRecording || (isTracking && activeTrackId == currentTrack.id)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            
            // 1. High-Contrast Mini Map Card with custom border
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                if (points.isNotEmpty()) {
                    MapViewContainer(
                        points = points,
                        modifier = Modifier.fillMaxSize(),
                        isInteractivityEnabled = false,
                        isImported = currentTrack.isImported,
                        isMerged = currentTrack.isMerged,
                        sourceColor = currentTrack.sourceColor,
                        isCurrentTracking = isCurrentRecording,
                        onViewportChanged = { viewModel.updateMapViewport(it) }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Aucune coordonnée disponible",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Category tag & Timestamp Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        text = "Parcours GPS",
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = FormatUtils.formatDate(currentTrack.startTime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 2. Comprehensive Statistics Grid Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.05f)
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "MÉTRIQUES EXPLORATEUR",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )

                    // Principal values
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DetailMetric(
                            title = "DISTANCE PARCOURUE",
                            value = FormatUtils.formatDistance(currentTrack.totalDistance),
                            highlightColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DetailMetric(
                            title = "DURÉE ENREGISTRÉE",
                            value = FormatUtils.formatDuration(currentTrack.duration),
                            highlightColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Spacer(
                        modifier = Modifier
                            .height(1.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.height(2.dp))

                    // Secondary grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DetailSubMetric(
                            icon = Icons.Default.Speed,
                            label = "Vitesse moy.",
                            value = FormatUtils.formatSpeed(currentTrack.avgSpeed)
                        )
                        DetailSubMetric(
                            icon = Icons.Default.Speed,
                            label = "Vitesse max.",
                            value = FormatUtils.formatSpeed(currentTrack.maxSpeed)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DetailSubMetric(
                            icon = Icons.Default.TrendingUp,
                            label = "Dénivelé positif",
                            value = FormatUtils.formatElevation(currentTrack.elevationGain)
                        )
                        DetailSubMetric(
                            icon = Icons.Default.TrendingDown,
                            label = "Dénivelé négatif",
                            value = FormatUtils.formatElevation(currentTrack.elevationLoss)
                        )
                    }
                }
            }

            // 2.5 Reprendre la trace CTA Button
            if (!isCurrentRecording) {
                Button(
                    onClick = {
                        if (isTracking) {
                            Toast.makeText(context, "Un enregistrement est déjà en cours. Veuillez l'arrêter avant de reprendre un autre parcours.", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.resumeTrack(context, currentTrack.id) {
                                Toast.makeText(context, "Reprise de la trace \"${currentTrack.name}\"", Toast.LENGTH_SHORT).show()
                                viewModel.selectTrack(null)
                                onBackClick()
                                onResumeTrack?.invoke()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("resume_track_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        Text("Reprendre la trace", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                }
            }

            // 3. Export Data Tools Block
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Exportation & Sauvegardes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val safeName = currentTrack.name
                                .replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                                .replace("\\s+".toRegex(), "_")
                            gpxLauncher.launch("$safeName.gpx")
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("export_gpx_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Enregistrer GPX", fontWeight = FontWeight.Black)
                        }
                    }

                    Button(
                        onClick = {
                            val safeName = currentTrack.name
                                .replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                                .replace("\\s+".toRegex(), "_")
                            kmlLauncher.launch("$safeName.kml")
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("export_kml_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Enregistrer KML", fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }

    if (isRenaming) {
        val current = track
        AlertDialog(
            onDismissRequest = { isRenaming = false },
            title = { Text("Renommer le parcours") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_track_field")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        current?.let { viewModel.renameTrack(it, renameText) }
                        isRenaming = false
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text("Renommer")
                }
            },
            dismissButton = {
                TextButton(onClick = { isRenaming = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
fun DetailMetric(
    title: String,
    value: String,
    highlightColor: Color
) {
    Column {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = highlightColor,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun DetailSubMetric(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.width(150.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

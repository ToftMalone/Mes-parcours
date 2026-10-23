package com.example.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Briques d'interface partagées par les écrans de la refonte : formes « cookie » et
// « soleil » de Material 3 Expressive, dialogues, feuilles, interrupteur à coche,
// tuile de parcours, et messages en bas d'écran.
//
// Tout ce qui se répète d'un écran à l'autre dans la maquette vit ici, pour qu'un
// réglage de forme ou de couleur ne se fasse qu'une fois.

// ------------------------------------------------------------------------ Formes

// Contours relevés tels quels dans la maquette (clip-path SVG, coordonnées 0 à 100).
// Material 3 Expressive les fournit dans androidx.graphics.shapes, absente de la
// version de Compose du projet : les recopier point par point est plus sûr que d'en
// approcher la silhouette par une formule.

private val COOKIE_OUTLINE = floatArrayOf(
    98.5f, 50f, 97.8f, 53.1f, 95.9f, 56f, 93.2f, 58.6f, 90.3f, 60.8f, 88f, 62.9f, 86.5f, 65.1f, 86f, 67.7f,
    86.2f, 70.9f, 86.6f, 74.4f, 86.7f, 78.2f, 86f, 81.6f, 84.3f, 84.3f, 81.6f, 86f, 78.2f, 86.7f, 74.4f, 86.6f,
    70.9f, 86.2f, 67.7f, 86f, 65.1f, 86.5f, 62.9f, 88f, 60.8f, 90.3f, 58.6f, 93.2f, 56f, 95.9f, 53.1f, 97.8f,
    50f, 98.5f, 46.9f, 97.8f, 44f, 95.9f, 41.4f, 93.2f, 39.2f, 90.3f, 37.1f, 88f, 34.9f, 86.5f, 32.3f, 86f,
    29.1f, 86.2f, 25.6f, 86.6f, 21.8f, 86.7f, 18.4f, 86f, 15.7f, 84.3f, 14f, 81.6f, 13.3f, 78.2f, 13.4f, 74.4f,
    13.8f, 70.9f, 14f, 67.7f, 13.5f, 65.1f, 12f, 62.9f, 9.7f, 60.8f, 6.8f, 58.6f, 4.1f, 56f, 2.2f, 53.1f,
    1.5f, 50f, 2.2f, 46.9f, 4.1f, 44f, 6.8f, 41.4f, 9.7f, 39.2f, 12f, 37.1f, 13.5f, 34.9f, 14f, 32.3f,
    13.8f, 29.1f, 13.4f, 25.6f, 13.3f, 21.8f, 14f, 18.4f, 15.7f, 15.7f, 18.4f, 14f, 21.8f, 13.3f, 25.6f, 13.4f,
    29.1f, 13.8f, 32.3f, 14f, 34.9f, 13.5f, 37.1f, 12f, 39.2f, 9.7f, 41.4f, 6.8f, 44f, 4.1f, 46.9f, 2.2f,
    50f, 1.5f, 53.1f, 2.2f, 56f, 4.1f, 58.6f, 6.8f, 60.8f, 9.7f, 62.9f, 12f, 65.1f, 13.5f, 67.7f, 14f,
    70.9f, 13.8f, 74.4f, 13.4f, 78.2f, 13.3f, 81.6f, 14f, 84.3f, 15.7f, 86f, 18.4f, 86.7f, 21.8f, 86.6f, 25.6f,
    86.2f, 29.1f, 86f, 32.3f, 86.5f, 34.9f, 88f, 37.1f, 90.3f, 39.2f, 93.2f, 41.4f, 95.9f, 44f, 97.8f, 46.9f
)

private val SUN_OUTLINE = floatArrayOf(
    98f, 50f, 97f, 53.1f, 94.6f, 55.9f, 92.1f, 58.4f, 90.6f, 60.9f, 90.6f, 63.8f, 91.6f, 67.2f, 92.3f, 70.8f,
    91.6f, 74f, 89.2f, 76.2f, 85.7f, 77.4f, 82.2f, 78.3f, 79.7f, 79.7f, 78.3f, 82.2f, 77.4f, 85.7f, 76.2f, 89.2f,
    74f, 91.6f, 70.8f, 92.3f, 67.2f, 91.6f, 63.8f, 90.6f, 60.9f, 90.6f, 58.4f, 92.1f, 55.9f, 94.6f, 53.1f, 97f,
    50f, 98f, 46.9f, 97f, 44.1f, 94.6f, 41.6f, 92.1f, 39.1f, 90.6f, 36.2f, 90.6f, 32.8f, 91.6f, 29.2f, 92.3f,
    26f, 91.6f, 23.8f, 89.2f, 22.6f, 85.7f, 21.7f, 82.2f, 20.3f, 79.7f, 17.8f, 78.3f, 14.3f, 77.4f, 10.8f, 76.2f,
    8.4f, 74f, 7.7f, 70.8f, 8.4f, 67.2f, 9.4f, 63.8f, 9.4f, 60.9f, 7.9f, 58.4f, 5.4f, 55.9f, 3f, 53.1f,
    2f, 50f, 3f, 46.9f, 5.4f, 44.1f, 7.9f, 41.6f, 9.4f, 39.1f, 9.4f, 36.2f, 8.4f, 32.8f, 7.7f, 29.2f,
    8.4f, 26f, 10.8f, 23.8f, 14.3f, 22.6f, 17.8f, 21.7f, 20.3f, 20.3f, 21.7f, 17.8f, 22.6f, 14.3f, 23.8f, 10.8f,
    26f, 8.4f, 29.2f, 7.7f, 32.8f, 8.4f, 36.2f, 9.4f, 39.1f, 9.4f, 41.6f, 7.9f, 44.1f, 5.4f, 46.9f, 3f,
    50f, 2f, 53.1f, 3f, 55.9f, 5.4f, 58.4f, 7.9f, 60.9f, 9.4f, 63.8f, 9.4f, 67.2f, 8.4f, 70.8f, 7.7f,
    74f, 8.4f, 76.2f, 10.8f, 77.4f, 14.3f, 78.3f, 17.8f, 79.7f, 20.3f, 82.2f, 21.7f, 85.7f, 22.6f, 89.2f, 23.8f,
    91.6f, 26f, 92.3f, 29.2f, 91.6f, 32.8f, 90.6f, 36.2f, 90.6f, 39.1f, 92.1f, 41.6f, 94.6f, 44.1f, 97f, 46.9f
)

/** Polygone dont les sommets sont donnés en pourcentage de la boîte (0 à 100). */
private class OutlineShape(private val points: FloatArray) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val sx = size.width / 100f
        val sy = size.height / 100f
        val path = Path().apply {
            moveTo(points[0] * sx, points[1] * sy)
            var i = 2
            while (i < points.size) {
                lineTo(points[i] * sx, points[i + 1] * sy)
                i += 2
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/** « Cookie » à huit lobes : icônes héros, états vides, chargement. */
val CookieShape: Shape = OutlineShape(COOKIE_OUTLINE)

/** « Soleil » à douze rayons : succès, mise à jour. */
val SunShape: Shape = OutlineShape(SUN_OUTLINE)

/**
 * Icône posée sur une forme pleine : le motif « héros » de la maquette, en tête des
 * dialogues, des feuilles et des états vides.
 */
@Composable
fun ShapeBadge(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CookieShape,
    size: Dp = 64.dp,
    iconSize: Dp = 30.dp
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor)
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
    }
}

/**
 * Tuile colorée d'un parcours : l'icône d'itinéraire, en blanc sur la couleur du
 * tracé. Elle remplace la pastille ronde de l'ancien historique.
 */
@Composable
fun TrackTile(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    corner: Dp = 14.dp,
    iconSize: Dp = 22.dp
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(color)
    ) {
        Icon(Icons.Rounded.Route, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

// ---------------------------------------------------------------- Interrupteur

/** Interrupteur de la maquette : une coche dans la poignée quand il est activé. */
@Composable
fun MpSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else null,
        colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary)
    )
}

// -------------------------------------------------------------------- Boutons

/** Bouton plein en pilule, 48 dp : l'action principale d'un dialogue ou d'une feuille. */
@Composable
fun MpFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    height: Dp = 48.dp
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp),
        modifier = modifier.height(height)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
    }
}

/** Bouton texte de la maquette : l'action secondaire, en couleur primaire. */
@Composable
fun MpTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.height(48.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ------------------------------------------------------------------- Dialogues

/**
 * Dialogue de la maquette : icône « héros » centrée, titre en Bricolage, texte, et
 * deux boutons alignés à droite. Coins de 32 dp sur `surfaceContainerHigh`.
 *
 * Un `AlertDialog` de Material, habillé : il garde tout ce qu'un dialogue doit faire
 * (retour arrière, voile, accessibilité) sans rien réécrire.
 */
@Composable
fun MpDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: (@Composable () -> Unit)? = null,
    dismissLabel: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    confirmContainerColor: Color = MaterialTheme.colorScheme.primary,
    confirmContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    confirmTestTag: String? = null,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
        icon = icon,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(lineHeight = 28.sp),
                textAlign = if (icon != null) TextAlign.Center else TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = if (body != null || content != null) {
            {
                Column {
                    if (body != null) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    content?.invoke(this)
                }
            }
        } else null,
        confirmButton = {
            MpFilledButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled,
                containerColor = confirmContainerColor,
                contentColor = confirmContentColor,
                modifier = if (confirmTestTag != null) Modifier.testTag(confirmTestTag) else Modifier
            )
        },
        dismissButton = dismissLabel?.let { label -> { MpTextButton(label, onDismiss) } }
    )
}

// -------------------------------------------------------------------- Feuilles

/**
 * Feuille montante de la maquette : coins de 32 dp en haut, `surfaceContainerLow`,
 * poignée de 32 × 4 dp. Le contenu est déjà dans ses marges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MpSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 18.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outline)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
                .navigationBarsPadding(),
            content = content
        )
    }
}

/** Titre de feuille (« Couleur du tracé ») et sa ligne d'explication. */
@Composable
fun SheetHeader(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    if (subtitle != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// -------------------------------------------------------------------- Messages

/** Un message bref en bas d'écran, avec une action facultative. */
data class AppMessage(
    val text: String,
    val icon: ImageVector,
    val isError: Boolean = false,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    // Deux messages identiques à la suite doivent tout de même se remontrer.
    val id: Long = System.nanoTime()
)

/**
 * Messages brefs de la maquette, à la place des `Toast` d'Android : ils prennent les
 * couleurs de l'application, portent une icône, et peuvent offrir une action
 * (« Réessayer »). Un seul à la fois ; le suivant remplace le précédent.
 */
@Stable
class AppMessenger {
    var current by mutableStateOf<AppMessage?>(null)
        private set

    fun show(
        text: String,
        icon: ImageVector,
        isError: Boolean = false,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        current = AppMessage(text, icon, isError, actionLabel, onAction)
    }

    fun dismiss(message: AppMessage) {
        if (current?.id == message.id) current = null
    }
}

val LocalAppMessenger = staticCompositionLocalOf { AppMessenger() }

/**
 * Affiche le message courant de [messenger], [bottomPadding] au-dessus du bas de
 * l'écran (la barre de navigation, qu'il ne doit pas recouvrir).
 *
 * **Il glisse, il ne se fond pas.** Le bandeau porte une ombre, et une opacité
 * intermédiaire la trancherait net (voir « L'ombre tranchée » dans CLAUDE.md) ; il
 * entre donc par translation depuis sous le bord de l'écran, où il n'est pas visible,
 * et en ressort de même.
 */
@Composable
fun AppMessageHost(
    messenger: AppMessenger,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val message = messenger.current
    // Le message affiché survit à sa disparition le temps de l'animation de sortie.
    var shown by remember { mutableStateOf<AppMessage?>(null) }
    val slide = remember { Animatable(1f) }

    LaunchedEffect(message?.id) {
        if (message != null) {
            if (shown != null && shown?.id != message.id) {
                slide.animateTo(1f, tween(150))
            }
            shown = message
            slide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
            delay(if (message.actionLabel != null) 5_000 else 3_200)
            messenger.dismiss(message)
        } else if (shown != null) {
            slide.animateTo(1f, tween(200))
            shown = null
        }
    }

    val current = shown ?: return
    val container = if (current.isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.inverseSurface
    val content = if (current.isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.inverseOnSurface
    val action = if (current.isError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.inversePrimary

    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding)
            .graphicsLayer {
                // Assez bas pour sortir entièrement de l'écran, ombre comprise.
                translationY = slide.value * (size.height + bottomPadding.toPx() + 48.dp.toPx())
            }
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = container,
            contentColor = content,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_message")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Icon(current.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(
                    current.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
                    modifier = Modifier.weight(1f)
                )
                if (current.actionLabel != null && current.onAction != null) {
                    TextButton(onClick = {
                        current.onAction.invoke()
                        messenger.dismiss(current)
                    }) {
                        Text(current.actionLabel, style = MaterialTheme.typography.labelLarge, color = action)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Progression

/**
 * Progression en vague de la maquette (Material 3 Expressive) : la part accomplie
 * ondule, le reste est un trait plat, séparé d'un vide. La vague avance d'elle-même,
 * ce qui montre que le travail continue même quand le pourcentage stagne.
 */
@Composable
fun WaveProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(900, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "wave_phase"
    )
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxWidth().height(14.dp)) {
        val p = progress.coerceIn(0f, 1f)
        val mid = size.height / 2
        val stroke = 4.dp.toPx()
        val gap = 6.dp.toPx()
        val waveEnd = size.width * p
        if (waveEnd > 0f) {
            val amplitude = size.height / 3.5f
            val wavelength = 20.dp.toPx()
            val shift = phase * wavelength
            val path = Path()
            var x = 0f
            path.moveTo(0f, mid + amplitude * kotlin.math.sin((x + shift) / wavelength * 2 * Math.PI).toFloat())
            while (x < waveEnd) {
                x = (x + 2f).coerceAtMost(waveEnd)
                path.lineTo(x, mid + amplitude * kotlin.math.sin((x + shift) / wavelength * 2 * Math.PI).toFloat())
            }
            drawPath(
                path, color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
        val trackStart = if (waveEnd > 0f) waveEnd + gap else 0f
        if (trackStart < size.width - stroke) {
            drawLine(
                trackColor,
                androidx.compose.ui.geometry.Offset(trackStart, mid),
                androidx.compose.ui.geometry.Offset(size.width - stroke, mid),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Point d'arrivée, comme l'indicateur linéaire de Material 3.
            drawCircle(color, radius = stroke / 2, center = androidx.compose.ui.geometry.Offset(size.width - stroke / 2, mid))
        }
    }
}

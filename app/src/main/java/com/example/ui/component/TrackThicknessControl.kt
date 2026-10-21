package com.example.ui.component

import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.TrackStylePreferences
import java.util.Locale

/** "3.5" rather than "3,5" (the language's separator); and "6" rather than "6.0". */
private fun formatThicknessDp(value: Float): String {
    val rounded = Math.round(value * 10) / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString()
    else String.format(Locale.US, "%.1f", rounded)
        .replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator)
}

/** Step of the “− / +” button: half a dp, the smallest visible difference. */
private const val THICKNESS_STEP_DP = 0.5f

/**
 * Line thickness of a track, chosen in the same sheet as its color.
 *
 * Three ways that answer each other: the “− / +” buttons for a fine adjustment, the
 * central field for a precise value (comma accepted), the slider for a
 * quick adjustment. None is stepped on predefined levels, except the buttons'
 * step.
 *
 * Controlled from outside: nothing is saved here, the sheet only applies the
 * choice on “Apply”, as for the color.
 */
@Composable
fun TrackThicknessControl(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme

    fun clamp(dp: Float) = dp.coerceIn(TrackStylePreferences.MIN_THICKNESS_DP, TrackStylePreferences.MAX_THICKNESS_DP)

    var textValue by remember { mutableStateOf(formatThicknessDp(value)) }
    var editing by remember { mutableStateOf(false) }
    // Follows the buttons and the slider, but not during typing: rewriting the field
    // at every keystroke would erase a comma that was just typed.
    LaunchedEffect(value) { if (!editing) textValue = formatThicknessDp(value) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.thickness_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = 4.dp)
            ) {
                StepperButton(Icons.Rounded.Remove, stringResource(R.string.thickness_thinner), "track_thickness_minus") {
                    onValueChange(clamp(value - THICKNESS_STEP_DP))
                }
                BasicTextField(
                    value = textValue,
                    onValueChange = { input ->
                        textValue = input
                        input.replace(',', '.').toFloatOrNull()?.let { onValueChange(clamp(it)) }
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = colors.onSurface,
                        fontFamily = MaterialTheme.typography.labelLarge.fontFamily
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .width(36.dp)
                        // An out-of-bounds or unreadable entry is brought back to the display as soon
                        // as the field is left, rather than staying there contradicting
                        // the thickness actually retained.
                        .onFocusChanged { focus ->
                            editing = focus.isFocused
                            if (!focus.isFocused) textValue = formatThicknessDp(value)
                        }
                        .testTag("track_thickness_field")
                )
                Text("dp", fontSize = 12.sp, color = colors.onSurfaceVariant)
                StepperButton(Icons.Rounded.Add, stringResource(R.string.thickness_thicker), "track_thickness_plus") {
                    onValueChange(clamp(value + THICKNESS_STEP_DP))
                }
            }
        }
        ThicknessSlider(value = value, onValueChange = { onValueChange(clamp(it)) })
    }
}

@Composable
private fun StepperButton(icon: ImageVector, description: String, testTag: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** Slider from the mockup: thick track cut around a vertical handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThicknessSlider(value: Float, onValueChange: (Float) -> Unit) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.secondaryContainer
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = TrackStylePreferences.MIN_THICKNESS_DP..TrackStylePreferences.MAX_THICKNESS_DP,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(44.dp)
            .testTag("track_thickness_slider"),
        thumb = {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(active)
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
            ) {
                val gap = 6.dp.toPx()
                val split = size.width * fraction
                val r = CornerRadius(8.dp.toPx())
                if (split - gap > 0f) drawRoundRect(active, Offset.Zero, Size(split - gap, size.height), r)
                if (split + gap < size.width) {
                    drawRoundRect(inactive, Offset(split + gap, 0f), Size(size.width - split - gap, size.height), r)
                }
            }
        }
    )
}

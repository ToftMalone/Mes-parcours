package com.example.ui.component

import android.location.Geocoder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Champ de recherche d'un lieu, posé en haut des cartes des outils : on tape une ville,
 * la carte s'y rend. Demandé par le père de l'auteur, pour ne plus avoir à faire glisser
 * la carte à travers la France jusqu'à l'endroit voulu.
 *
 * La recherche passe par le `Geocoder` d'Android, qui interroge le service du téléphone
 * (celui de Google le plus souvent) : seul le texte tapé part, jamais la position. Sans
 * réseau, ou sur un téléphone sans ce service, un message le dit plutôt que rien.
 */
@Composable
fun MapSearchBar(
    onFound: (latitude: Double, longitude: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    val search: () -> Unit = search@{
        val text = query.trim()
        if (text.isEmpty() || searching) return@search
        if (!Geocoder.isPresent()) {
            messenger.show(context.getString(R.string.map_search_unavailable), Icons.Rounded.SearchOff, isError = true)
            return@search
        }
        focus.clearFocus()
        searching = true
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.FRANCE).getFromLocationName(text, 1)?.firstOrNull()
                }
            }
            searching = false
            val address = found.getOrNull()
            when {
                found.isFailure -> messenger.show(context.getString(R.string.map_search_failed), Icons.Rounded.SearchOff, isError = true)
                address == null -> messenger.show(context.getString(R.string.map_search_not_found, text), Icons.Rounded.SearchOff, isError = true)
                else -> onFound(address.latitude, address.longitude)
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(4.dp, RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerHigh, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp)
    ) {
        if (searching) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            decorationBox = { field ->
                if (query.isEmpty()) {
                    Text(stringResource(R.string.map_search_hint), color = colors.onSurfaceVariant, fontSize = 16.sp)
                }
                field()
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .testTag("map_search_field")
        )
    }
}

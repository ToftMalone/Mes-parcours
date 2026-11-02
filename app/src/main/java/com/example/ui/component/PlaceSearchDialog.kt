package com.example.ui.component

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.R
import kotlinx.coroutines.launch

/**
 * Fenêtre de recherche d'un lieu de la carte principale : on y tape une adresse, une ville
 * ou autre chose, et [onFound] reçoit l'endroit trouvé. Demandée par l'auteur, derrière un
 * bouton déployable plutôt qu'un champ affiché en permanence sur la carte.
 */
@Composable
fun PlaceSearchDialog(
    onDismiss: () -> Unit,
    onFound: (latitude: Double, longitude: Double) -> Unit
) {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val search: () -> Unit = search@{
        val text = query.trim()
        if (text.isEmpty() || searching) return@search
        searching = true
        scope.launch {
            val address = searchPlace(context, messenger, text)
            searching = false
            if (address != null) onFound(address.latitude, address.longitude)
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    MpDialog(
        onDismissRequest = onDismiss,
        icon = {
            ShapeBadge(
                icon = Icons.Rounded.Search,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(22.dp)
            )
        },
        title = stringResource(R.string.place_search_title),
        confirmLabel = stringResource(if (searching) R.string.place_search_running else R.string.place_search_go),
        confirmEnabled = query.isNotBlank() && !searching,
        confirmTestTag = "place_search_confirm",
        dismissLabel = stringResource(R.string.common_cancel),
        onConfirm = search
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.place_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag("place_search_field")
        )
    }
}

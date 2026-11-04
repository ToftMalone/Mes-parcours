package com.example.ui.component

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.example.util.DebugLog
import kotlin.math.hypot

/**
 * Note dans le journal ([DebugLog]) chaque toucher de la zone : appui, appui long,
 * glissement ou geste à plusieurs doigts, avec **ce qui a été touché** — le libellé,
 * l'étiquette de test et l'état de l'élément (case cochée, valeur d'un curseur…).
 *
 * Demandé par l'auteur : « il faut que ce journal note TOUT ce qui se passe dans
 * l'appli ». Plutôt que d'ajouter une ligne dans chacun des centaines de boutons (et
 * d'en oublier), on regarde après coup quel élément se trouvait sous le doigt, dans
 * l'arbre sémantique de Compose — celui-là même que lisent les tests et les services
 * d'accessibilité. Tout bouton, présent ou futur, est donc couvert sans y penser.
 *
 * Observation seule : le geste est lu à la passe `Initial`, avant tout le monde, et
 * jamais consommé. Chaque fenêtre a son arbre (un dialogue, une feuille montante sont
 * des fenêtres à part) : la modification se pose à la racine de chacune.
 */
@Composable
fun Modifier.logTouches(area: String): Modifier {
    val view = LocalView.current
    val position = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { position[0] = it }
        .pointerInput(area) {
            val starts = HashMap<PointerId, Triple<Offset, Long, Float>>()
            var maxPointers = 0
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed > maxPointers) maxPointers = pressed
                    for (change in event.changes) {
                        if (change.pressed && !change.previousPressed) {
                            starts[change.id] = Triple(change.position, change.uptimeMillis, 0f)
                        } else if (change.pressed) {
                            starts[change.id]?.let { (origin, time, far) ->
                                val d = (change.position - origin).getDistance()
                                if (d > far) starts[change.id] = Triple(origin, time, d)
                            }
                        } else if (!change.pressed && change.previousPressed) {
                            val start = starts.remove(change.id) ?: continue
                            val coords = position[0]
                            val toWindow = { o: Offset -> coords?.takeIf { it.isAttached }?.localToWindow(o) ?: o }
                            val from = toWindow(start.first)
                            val to = toWindow(change.position)
                            val duration = change.uptimeMillis - start.second
                            val moved = start.third > viewConfiguration.touchSlop
                            val fingers = maxPointers
                            if (starts.isEmpty()) maxPointers = 0
                            val what = describeAt(view, from)
                            val kind = when {
                                fingers > 1 -> "Geste à $fingers doigts"
                                moved -> "Glissement"
                                duration >= viewConfiguration.longPressTimeoutMillis -> "Appui long"
                                else -> "Appui"
                            }
                            val where = if (moved) "de ${fmt(from)} à ${fmt(to)}" else "en ${fmt(from)}"
                            DebugLog.i("Toucher", "$kind ($area) $where, $duration ms : $what")
                        }
                    }
                }
            }
        }
}

private fun fmt(o: Offset) = "(${o.x.toInt()}, ${o.y.toInt()})"

/** L'élément sous le doigt, le plus petit qui réagit au toucher, décrit pour le journal. */
private fun describeAt(view: View, point: Offset): String = runCatching {
    val owner = (view as? RootForTest)?.semanticsOwner ?: return "élément inconnu"
    val under = owner.getAllSemanticsNodes(mergingEnabled = true)
        .filter { it.boundsInWindow.contains(point) }
    val interactive = under.filter(::isInteractive)
    val node = (interactive.ifEmpty { under.filter { describe(it).isNotEmpty() } })
        .minByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
        ?: return "rien d'identifiable (fond, carte…)"
    describe(node).ifEmpty { "élément sans libellé" } + if (node in interactive) "" else " (non cliquable)"
}.getOrElse { "élément inconnu (${it.javaClass.simpleName})" }

private fun isInteractive(node: SemanticsNode): Boolean {
    val c = node.config
    return SemanticsActions.OnClick in c || SemanticsActions.OnLongClick in c ||
        SemanticsActions.SetProgress in c || SemanticsActions.SetText in c ||
        SemanticsProperties.ToggleableState in c
}

private fun describe(node: SemanticsNode): String {
    val c = node.config
    val parts = mutableListOf<String>()
    c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
        ?.let { parts += "« $it »" }
    c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
        ?.let { parts += "icône « $it »" }
    c.getOrNull(SemanticsProperties.EditableText)?.text?.let { parts += "champ « $it »" }
    c.getOrNull(SemanticsProperties.TestTag)?.let { parts += "[$it]" }
    c.getOrNull(SemanticsProperties.ToggleableState)?.let { parts += "état $it" }
    c.getOrNull(SemanticsProperties.Selected)?.let { parts += if (it) "sélectionné" else "non sélectionné" }
    c.getOrNull(SemanticsProperties.StateDescription)?.let { parts += "« $it »" }
    c.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { parts += "valeur ${it.current} sur ${it.range}" }
    c.getOrNull(SemanticsProperties.Disabled)?.let { parts += "désactivé" }
    return parts.joinToString(" ")
}

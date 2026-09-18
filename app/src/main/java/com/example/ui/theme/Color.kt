package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Palette « chaleureuse et motivante », en remplacement du bleu-nuit d'origine.
// Couleurs de rôle, partagées entre les deux thèmes — un signal GPS trouvé
// reste vert, une alerte reste rouge, qu'on soit en thème sombre ou clair.
// ---------------------------------------------------------------------------

/** Actions principales, signal GPS trouvé, bordures des cartes flottantes. */
val PrimaryGreen = Color(0xFF22C55E)

/** Accent chaud : altitude/dénivelé, mises en avant. */
val AmberAccent = Color(0xFFF5A524)

/** Alerte, arrêt d'enregistrement, signal GPS perdu. */
val AlertRed = Color(0xFFEF4444)

/**
 * Réservée à la superposition de plusieurs tracés sur la carte (overlay).
 * Ne pas l'employer comme couleur de composant d'interface : c'est la seule
 * couleur du thème qui garde un sens en dehors de la palette « chaleureuse ».
 */
val OverlaySecondary = Color(0xFF818CF8)

/**
 * Conservée sous ce nom pour `UpdatePrompt.kt`, qui l'importe directement et
 * n'est pas concerné par la refonte du thème — seule la valeur change, pour
 * que le bandeau de mise à jour suive la nouvelle palette sans qu'on ait à y
 * toucher.
 */
val EmeraldPrimary = PrimaryGreen

// Fond sombre (thème par défaut de l'application).
val DarkBackground = Color(0xFF14110D)
val DarkSurface = Color(0xFF1E1912)
val DarkOnSurface = Color(0xFFF5F1EC)
val DarkOnSurfaceSecondary = Color(0xFF8A8177)

// Fond clair — variante dérivée du sombre dans le même esprit : papier crème
// plutôt que blanc froid, texte brun profond plutôt que noir pur.
val LightBackground = Color(0xFFFBF7F1)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1C1712)
val LightOnSurfaceSecondary = Color(0xFF6B6255)

/**
 * Texte/icône sur une surface verte pleine (FAB, boutons pleins) : un vert
 * presque noir plutôt qu'un blanc, qui reste dans le ton de la palette au
 * lieu d'un contraste froid.
 */
val OnPrimaryGreen = Color(0xFF0B1F13)

/** Contour neutre à faible opacité, pour les séparateurs et bordures discrètes. */
val SoftBorder = Color(0x338A8177)

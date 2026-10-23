package com.example.util

import android.content.Context
import androidx.annotation.StringRes

/**
 * Message d'erreur destiné à l'utilisateur, qui voyage sous forme de ressource.
 *
 * Le dépôt de données n'a pas de `Context` : il ne peut pas lire lui-même un texte
 * dans les ressources. Il lève donc une exception qui porte l'identifiant du texte,
 * et c'est l'écran qui le résout au moment de l'afficher.
 */
interface UserFacing {
    @get:StringRes
    val messageRes: Int
    val messageArgs: List<Any>
}

/** [IllegalArgumentException] dont le message est une ressource (voir [UserFacing]). */
class UserFacingArgumentException(
    @StringRes override val messageRes: Int,
    override val messageArgs: List<Any> = emptyList()
) : IllegalArgumentException("string resource #$messageRes"), UserFacing

/** [IllegalStateException] dont le message est une ressource (voir [UserFacing]). */
class UserFacingStateException(
    @StringRes override val messageRes: Int,
    override val messageArgs: List<Any> = emptyList()
) : IllegalStateException("string resource #$messageRes"), UserFacing

/**
 * Le texte à montrer pour cette erreur : la ressource si elle en porte une, sinon le
 * message du système, sinon [fallbackRes].
 */
fun Throwable.userMessage(context: Context, @StringRes fallbackRes: Int): String =
    if (this is UserFacing) context.getString(messageRes, *messageArgs.toTypedArray())
    else localizedMessage ?: context.getString(fallbackRes)

/** Comme [userMessage], mais avec un préfixe quand l'erreur ne vient pas de nous. */
fun Throwable.userMessageOr(context: Context, wrap: (String?) -> String): String =
    if (this is UserFacing) context.getString(messageRes, *messageArgs.toTypedArray())
    else wrap(localizedMessage)

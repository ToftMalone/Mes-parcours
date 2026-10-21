package com.example.util

import android.content.Context
import androidx.annotation.StringRes

/**
 * Error message meant for the user, which travels as a resource.
 *
 * The data layer has no `Context`: it cannot write a message in the device's
 * language itself. It therefore throws an exception that carries the text's
 * identifier, and it is the screen that resolves it when displaying it —
 * hence in the current language, even if it changed in the meantime.
 */
interface UserFacing {
    @get:StringRes
    val messageRes: Int
    val messageArgs: List<Any>
}

/** [IllegalArgumentException] whose message is a resource (see [UserFacing]). */
class UserFacingArgumentException(
    @StringRes override val messageRes: Int,
    override val messageArgs: List<Any> = emptyList()
) : IllegalArgumentException("string resource #$messageRes"), UserFacing

/** [IllegalStateException] whose message is a resource (see [UserFacing]). */
class UserFacingStateException(
    @StringRes override val messageRes: Int,
    override val messageArgs: List<Any> = emptyList()
) : IllegalStateException("string resource #$messageRes"), UserFacing

/**
 * The text to show for this error: the resource if it carries one, otherwise the
 * system message, otherwise [fallbackRes].
 */
fun Throwable.userMessage(context: Context, @StringRes fallbackRes: Int): String =
    if (this is UserFacing) context.getString(messageRes, *messageArgs.toTypedArray())
    else localizedMessage ?: context.getString(fallbackRes)

/** Like [userMessage], but with a prefix when the error does not come from us. */
fun Throwable.userMessageOr(context: Context, wrap: (String?) -> String): String =
    if (this is UserFacing) context.getString(messageRes, *messageArgs.toTypedArray())
    else wrap(localizedMessage)

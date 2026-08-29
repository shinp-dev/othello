package com.example.othello

import androidx.annotation.StringRes
import androidx.core.util.PatternsCompat

internal object EmailAddressValidator {
    fun isValid(value: String): Boolean =
        value.isNotBlank() && PatternsCompat.EMAIL_ADDRESS.matcher(value).matches()
}

internal class InvalidEmailAddressException : IllegalArgumentException()

@StringRes
internal fun Throwable.emailInputErrorResource(): Int? =
    if (this is InvalidEmailAddressException) R.string.email_format_invalid else null

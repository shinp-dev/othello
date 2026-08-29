package com.example.othello

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class EmailAddressValidationTest {
    @Test
    fun representativeEmailFormatsAreValidatedByTheSharedValidator() {
        listOf(
            "person@example.com",
            "first.last+tag@example.co.jp",
            "person@example.test",
        ).forEach { assertTrue(EmailAddressValidator.isValid(it), it) }

        listOf(
            "",
            "name",
            "@example.com",
            "name@",
            "name@@example.com",
            "name example@example.com",
        ).forEach { assertFalse(EmailAddressValidator.isValid(it), it) }
    }

    @Test
    fun invalidEmailFailureMapsOnlyToTheEmailInputError() {
        assertEquals(
            R.string.email_format_invalid,
            InvalidEmailAddressException().emailInputErrorResource(),
        )
        assertFalse(
            InvalidEmailAddressException().emailInputErrorResource() == R.string.reset_email_failed,
        )
        assertNull(IllegalStateException("temporary failure").emailInputErrorResource())
    }
}

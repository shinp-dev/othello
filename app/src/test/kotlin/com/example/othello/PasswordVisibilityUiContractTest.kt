package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PasswordVisibilityUiContractTest {
    private val authGate = File("src/main/kotlin/com/example/othello/AuthGate.kt").readText()
    private val loginScreen = authGate.substringAfter("private fun LoginScreen(")
        .substringBefore("private fun AuthCheckingScreen")
    private val passwordField = loginScreen.substringAfter("value = password,")
        .substringBefore("if (mode == LoginMode.SIGN_UP)")

    @Test
    fun passwordStartsHiddenAndCanToggleBetweenHiddenAndVisible() {
        assertTrue("var passwordVisible by remember { mutableStateOf(false) }" in loginScreen)
        assertTrue(
            "visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()" in
                passwordField,
        )
        assertTrue("onClick = { passwordVisible = !passwordVisible }" in passwordField)
        assertTrue("Icons.Filled.VisibilityOff else Icons.Filled.Visibility" in passwordField)
    }

    @Test
    fun toggleKeepsTheSharedPasswordValueAndExistingBusyBehavior() {
        assertEquals(1, Regex("value = password,").findAll(loginScreen).count())
        assertTrue("onValueChange = onPasswordChange" in passwordField)
        assertTrue("enabled = !busy" in passwordField)

        val trailingIcon = passwordField.substringAfter("trailingIcon = {")
        assertFalse("password =" in trailingIcon)
    }

    @Test
    fun toggleDescriptionMatchesTheAvailableAction() {
        assertTrue(
            "if (passwordVisible) R.string.hide_password else R.string.show_password" in passwordField,
        )
    }
}

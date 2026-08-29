package com.example.othello

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.othello.auth.SignUpResult
import com.example.othello.auth.UserSession
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaSpacing
import kotlinx.coroutines.launch

private enum class LoginMode { LOGIN, SIGN_UP }

@Composable
internal fun AuthGate(
    sessionOwner: OnlineSessionViewModel,
    authenticatedContent: @Composable (UserSession) -> Unit,
) {
    val authState by sessionOwner.authState.collectAsStateWithLifecycle()
    when (val state = authState) {
        AuthState.Checking -> AuthCheckingScreen()
        is AuthState.Authenticated -> key(state.session.userId) { authenticatedContent(state.session) }
        AuthState.Unauthenticated -> LoginRoute(sessionOwner)
        is AuthState.Error -> AuthStartupErrorScreen(
            state = state,
            onRetry = if (state.kind == AuthStartupErrorKind.SESSION_RESTORE) {
                sessionOwner::retrySessionRestore
            } else null,
        )
    }
}

@Composable
private fun LoginRoute(sessionOwner: OnlineSessionViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }

    fun clearMessages() {
        emailError = null
        loginError = null
        notice = null
        noticeIsError = false
    }

    fun showEmailInputError(failure: Throwable): Boolean {
        val resource = failure.emailInputErrorResource() ?: return false
        emailError = context.getString(resource)
        return true
    }

    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        LoginScreen(
            mode = mode,
            email = email,
            password = password,
            busy = busy,
            emailError = emailError,
            loginError = loginError,
            notice = notice,
            noticeIsError = noticeIsError,
            onEmailChange = {
                email = it
                emailError = null
            },
            onPasswordChange = { password = it },
            onLogin = {
                scope.launch {
                    busy = true
                    clearMessages()
                    sessionOwner.signIn(email, password)
                        .onFailure {
                            if (!showEmailInputError(it)) {
                                loginError = authErrorMessage(AuthOperation.LOGIN, it, context)
                            }
                        }
                    busy = false
                }
            },
            onOpenSignUp = {
                clearMessages()
                mode = LoginMode.SIGN_UP
            },
            onSignUp = {
                scope.launch {
                    busy = true
                    clearMessages()
                    sessionOwner.signUp(email, password)
                        .onSuccess { result ->
                            if (result == SignUpResult.EmailConfirmationRequired) {
                                mode = LoginMode.LOGIN
                                password = ""
                                notice = context.getString(R.string.confirmation_email_sent)
                            }
                        }
                        .onFailure {
                            if (!showEmailInputError(it)) {
                                notice = authErrorMessage(AuthOperation.SIGN_UP, it, context)
                                noticeIsError = true
                            }
                        }
                    busy = false
                }
            },
            onBackToLogin = {
                clearMessages()
                mode = LoginMode.LOGIN
            },
            onPasswordReset = {
                scope.launch {
                    busy = true
                    clearMessages()
                    sessionOwner.requestPasswordReset(email)
                        .onSuccess {
                            notice = context.getString(R.string.reset_email_sent)
                        }
                        .onFailure {
                            if (!showEmailInputError(it)) {
                                notice = context.getString(R.string.reset_email_failed)
                                noticeIsError = true
                            }
                        }
                    busy = false
                }
            },
        )
    }
}

@Composable
private fun LoginScreen(
    mode: LoginMode,
    email: String,
    password: String,
    busy: Boolean,
    emailError: String?,
    loginError: String?,
    notice: String?,
    noticeIsError: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onOpenSignUp: () -> Unit,
    onSignUp: () -> Unit,
    onBackToLogin: () -> Unit,
    onPasswordReset: () -> Unit,
) {
    val iconDescription = appString(R.string.app_icon_description)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = iconDescription
                }
            },
            modifier = Modifier.size(96.dp),
        )
        Text(appString(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = ChanrivaColors.accent)
        Text(appString(R.string.login_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            appString(if (mode == LoginMode.LOGIN) R.string.login else R.string.create_account),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(appString(R.string.account_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            singleLine = true,
            enabled = !busy,
            isError = emailError != null,
            supportingText = emailError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(appString(if (mode == LoginMode.LOGIN) R.string.password else R.string.chanriva_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (mode == LoginMode.SIGN_UP) {
            Text(
                appString(R.string.password_guidance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        loginError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
        notice?.let {
            Text(
                it,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.size(28.dp))
        if (mode == LoginMode.LOGIN) {
            Button(
                onClick = onLogin,
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(appString(R.string.login)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onOpenSignUp, enabled = !busy) { Text(appString(R.string.create_account)) }
                TextButton(onClick = onPasswordReset, enabled = !busy && email.isNotBlank()) {
                    Text(appString(R.string.forgot_password))
                }
            }
        } else {
            OutlinedButton(
                onClick = onSignUp,
                enabled = !busy && email.isNotBlank() && password.length >= 8,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(appString(R.string.create_account_action)) }
            TextButton(onClick = onBackToLogin, enabled = !busy) { Text(appString(R.string.back_to_login)) }
        }
    }
}

@Composable
private fun AuthCheckingScreen() {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(appString(R.string.login_checking), modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun AuthStartupErrorScreen(state: AuthState.Error, onRetry: (() -> Unit)?) {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier.fillMaxSize().padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                appString(if (state.kind == AuthStartupErrorKind.CONFIGURATION) R.string.app_configuration_error else R.string.session_check_error),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(state.message, modifier = Modifier.padding(top = 12.dp))
            onRetry?.let {
                OutlinedButton(onClick = it, modifier = Modifier.padding(top = 16.dp)) { Text(appString(R.string.retry)) }
            }
        }
    }
}

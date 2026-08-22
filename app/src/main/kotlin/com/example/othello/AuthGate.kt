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
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }

    fun clearMessages() {
        loginError = null
        notice = null
        noticeIsError = false
    }

    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        LoginScreen(
            mode = mode,
            email = email,
            password = password,
            busy = busy,
            loginError = loginError,
            notice = notice,
            noticeIsError = noticeIsError,
            onEmailChange = { email = it },
            onPasswordChange = { password = it },
            onLogin = {
                scope.launch {
                    busy = true
                    clearMessages()
                    sessionOwner.signIn(email, password)
                        .onFailure { loginError = authErrorMessage(AuthOperation.LOGIN, it) }
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
                                notice = "確認メールを送信しました。メール内のリンクを開いてからログインしてください。"
                            }
                        }
                        .onFailure {
                            notice = authErrorMessage(AuthOperation.SIGN_UP, it)
                            noticeIsError = true
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
                            notice = "再設定メールを送信しました。登録済みの場合はメールをご確認ください。"
                        }
                        .onFailure {
                            notice = "再設定メールを送信できませんでした。しばらく時間をおいてお試しください。"
                            noticeIsError = true
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
                    contentDescription = "ちゃんりば アプリアイコン"
                }
            },
            modifier = Modifier.size(96.dp),
        )
        Text("ちゃんりば", style = MaterialTheme.typography.headlineMedium, color = ChanrivaColors.accent)
        Text("ちゃんと残る、ちゃんと振り返れるリバーシ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (mode == LoginMode.LOGIN) "ログイン" else "アカウント作成",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("メールアドレス") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(if (mode == LoginMode.LOGIN) "パスワード" else "ちゃんりば用パスワード") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (mode == LoginMode.SIGN_UP) {
            Text(
                "Gmailなどで使っているパスワードとは別のものを設定してください。",
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
            ) { Text("ログイン") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onOpenSignUp, enabled = !busy) { Text("アカウント作成") }
                TextButton(onClick = onPasswordReset, enabled = !busy && email.isNotBlank()) {
                    Text("パスワードを忘れた場合")
                }
            }
        } else {
            OutlinedButton(
                onClick = onSignUp,
                enabled = !busy && email.isNotBlank() && password.length >= 8,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("アカウントを作成") }
            TextButton(onClick = onBackToLogin, enabled = !busy) { Text("ログインに戻る") }
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
            Text("ログイン状態を確認しています", modifier = Modifier.padding(top = 16.dp))
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
                if (state.kind == AuthStartupErrorKind.CONFIGURATION) "アプリ設定エラー" else "ログイン状態の確認エラー",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(state.message, modifier = Modifier.padding(top = 12.dp))
            onRetry?.let {
                OutlinedButton(onClick = it, modifier = Modifier.padding(top = 16.dp)) { Text("再試行") }
            }
        }
    }
}

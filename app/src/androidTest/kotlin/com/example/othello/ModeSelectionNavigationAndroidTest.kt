package com.example.othello

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class ModeSelectionNavigationAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun realEventOpensDirectlyAndSystemBackReturnsToChoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPAN)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val choiceLabel = localizedContext.getString(R.string.enjoy_together_accessibility)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    AuthenticatedModeRoute(userId = "mode-selection-navigation-test") { _ -> }
                }
            }
        }

        composeRule.onNodeWithContentDescription(choiceLabel).performClick()
        composeRule.onNodeWithText("リアルイベント").assertExists()
        Espresso.pressBack()
        composeRule.onNodeWithContentDescription(choiceLabel).assertExists()
    }
}

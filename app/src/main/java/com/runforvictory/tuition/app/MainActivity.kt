package com.runforvictory.tuition.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.runforvictory.tuition.authentication.AuthViewModel
import com.runforvictory.tuition.core.theme.RunForVictoryTheme
import com.runforvictory.tuition.navigation.AppNavHost
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as RunForVictoryApp
                return AuthViewModel(app.container.authRepository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RunForVictoryApp
        setContent {
            val currentLanguage by app.container.localeManager.currentLanguage.collectAsState()
            val locale = remember(currentLanguage) { Locale(currentLanguage) }
            val baseConfig = LocalConfiguration.current
            val localizedConfiguration = remember(currentLanguage, baseConfig) {
                Configuration(baseConfig).apply {
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
            }
            val context = LocalContext.current
            val localizedContext = remember(currentLanguage, context) {
                context.createConfigurationContext(localizedConfiguration)
            }

            CompositionLocalProvider(
                LocalConfiguration provides localizedConfiguration,
                LocalContext provides localizedContext
            ) {
                RunForVictoryTheme {
                    AppNavHost(
                        authViewModel = authViewModel,
                        appContainer = app.container
                    )
                }
            }
        }
    }
}

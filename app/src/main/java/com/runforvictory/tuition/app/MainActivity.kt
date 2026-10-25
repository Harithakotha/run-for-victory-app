package com.runforvictory.tuition.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.runforvictory.tuition.authentication.AuthViewModel
import com.runforvictory.tuition.core.theme.RunForVictoryTheme
import com.runforvictory.tuition.navigation.AppNavHost

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
            RunForVictoryTheme {
                AppNavHost(
                    authViewModel = authViewModel,
                    appContainer = app.container
                )
            }
        }
    }
}

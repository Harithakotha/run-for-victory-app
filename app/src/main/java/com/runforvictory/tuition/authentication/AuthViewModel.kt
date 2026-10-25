package com.runforvictory.tuition.authentication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.data.AuthRepository
import com.runforvictory.tuition.domain.InputValidator
import com.runforvictory.tuition.domain.UserProfile
import com.runforvictory.tuition.domain.UserRole
import com.runforvictory.tuition.domain.ValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val emailError: String? = null,
    val password: String = "",
    val passwordError: String? = null,
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isCheckingSession: Boolean = true,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isPasswordResetDialogOpen: Boolean = false,
    val resetEmail: String = "",
    val resetEmailError: String? = null,
    val isResettingPassword: Boolean = false,
    val authenticatedUser: UserProfile? = null
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        restoreSession()
    }

    fun restoreSession() {
        _uiState.update { it.copy(isCheckingSession = true) }
        viewModelScope.launch {
            when (val result = authRepository.restoreSession()) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isCheckingSession = false,
                            authenticatedUser = result.data
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isCheckingSession = false,
                            authenticatedUser = null,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {
                    _uiState.update { it.copy(isCheckingSession = true) }
                }
            }
        }
    }

    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, emailError = null, errorMessage = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, passwordError = null, errorMessage = null) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun openPasswordResetDialog() {
        _uiState.update {
            it.copy(
                isPasswordResetDialogOpen = true,
                resetEmail = it.email,
                resetEmailError = null,
                errorMessage = null,
                successMessage = null
            )
        }
    }

    fun dismissPasswordResetDialog() {
        _uiState.update {
            it.copy(
                isPasswordResetDialogOpen = false,
                resetEmail = "",
                resetEmailError = null
            )
        }
    }

    fun onResetEmailChanged(email: String) {
        _uiState.update { it.copy(resetEmail = email, resetEmailError = null) }
    }

    fun sendPasswordReset() {
        val state = _uiState.value
        val validation = InputValidator.validateEmail(state.resetEmail)
        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(resetEmailError = validation.errorMessage) }
            return
        }

        _uiState.update { it.copy(isResettingPassword = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = authRepository.sendPasswordReset(state.resetEmail)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isResettingPassword = false,
                            isPasswordResetDialogOpen = false,
                            successMessage = "Password reset instructions sent to ${state.resetEmail}."
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isResettingPassword = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {
                    _uiState.update { it.copy(isResettingPassword = true) }
                }
            }
        }
    }

    fun signIn() {
        val state = _uiState.value

        val emailValidation = InputValidator.validateEmail(state.email)
        val passwordValidation = InputValidator.validatePassword(state.password)

        if (emailValidation is ValidationResult.Invalid || passwordValidation is ValidationResult.Invalid) {
            _uiState.update {
                it.copy(
                    emailError = (emailValidation as? ValidationResult.Invalid)?.errorMessage,
                    passwordError = (passwordValidation as? ValidationResult.Invalid)?.errorMessage
                )
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }

        viewModelScope.launch {
            when (val result = authRepository.signIn(state.email, state.password)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            authenticatedUser = result.data
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {
                    _uiState.update { it.copy(isLoading = true) }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _uiState.update {
                AuthUiState(isCheckingSession = false)
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}

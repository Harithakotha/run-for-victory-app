package com.runforvictory.tuition.authentication

import com.runforvictory.tuition.domain.InputValidator
import com.runforvictory.tuition.domain.ValidationResult
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidationTest {

    @Test
    fun validEmail_returnsValid() {
        val result = InputValidator.validateEmail("owner@runforvictory.com")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun emptyEmail_returnsInvalid() {
        val result = InputValidator.validateEmail("")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun invalidEmailFormat_returnsInvalid() {
        val result = InputValidator.validateEmail("invalid-email-format")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun validPassword_returnsValid() {
        val result = InputValidator.validatePassword("SecurePass123!")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun shortPassword_returnsInvalid() {
        val result = InputValidator.validatePassword("12345")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun emptyPassword_returnsInvalid() {
        val result = InputValidator.validatePassword("")
        assertTrue(result is ValidationResult.Invalid)
    }
}

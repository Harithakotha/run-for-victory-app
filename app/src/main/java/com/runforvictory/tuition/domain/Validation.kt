package com.runforvictory.tuition.domain

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val errorMessage: String) : ValidationResult
}

object InputValidator {
    private val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$".toRegex()
    private val PHONE_REGEX = "^[0-9+ -]{10,15}\$".toRegex()

    fun validateEmail(email: String?): ValidationResult {
        if (email.isNullOrBlank()) {
            return ValidationResult.Invalid("Email address cannot be empty.")
        }
        if (!EMAIL_REGEX.matches(email.trim())) {
            return ValidationResult.Invalid("Please enter a valid email address.")
        }
        return ValidationResult.Valid
    }

    fun validatePassword(password: String?): ValidationResult {
        if (password.isNullOrBlank()) {
            return ValidationResult.Invalid("Password cannot be empty.")
        }
        if (password.length < 6) {
            return ValidationResult.Invalid("Password must be at least 6 characters long.")
        }
        return ValidationResult.Valid
    }

    fun validateStudentName(name: String?): ValidationResult {
        if (name.isNullOrBlank()) {
            return ValidationResult.Invalid("Student full name is required.")
        }
        if (name.trim().length < 2) {
            return ValidationResult.Invalid("Student name must be at least 2 characters.")
        }
        return ValidationResult.Valid
    }

    fun validateGrade(grade: String?): ValidationResult {
        if (grade.isNullOrBlank()) {
            return ValidationResult.Invalid("Grade / Class is required.")
        }
        return ValidationResult.Valid
    }

    fun validateGuardianName(name: String?): ValidationResult {
        if (name.isNullOrBlank()) {
            return ValidationResult.Invalid("Guardian full name is required.")
        }
        if (name.trim().length < 2) {
            return ValidationResult.Invalid("Guardian name must be at least 2 characters.")
        }
        return ValidationResult.Valid
    }

    fun validatePhoneNumber(phone: String?, isRequired: Boolean = false): ValidationResult {
        if (phone.isNullOrBlank()) {
            return if (isRequired) ValidationResult.Invalid("Contact phone number is required.") else ValidationResult.Valid
        }
        if (!PHONE_REGEX.matches(phone.trim())) {
            return ValidationResult.Invalid("Enter a valid 10-digit phone number.")
        }
        return ValidationResult.Valid
    }

    fun validateArchivalReason(reason: String?): ValidationResult {
        if (reason.isNullOrBlank()) {
            return ValidationResult.Invalid("Archival reason is required for audit trail.")
        }
        if (reason.trim().length < 5) {
            return ValidationResult.Invalid("Please provide a meaningful archival reason (min 5 chars).")
        }
        return ValidationResult.Valid
    }

    fun validateWorkReport(subject: String?, workCompleted: String?): ValidationResult {
        if (subject.isNullOrBlank()) {
            return ValidationResult.Invalid("Subject is required.")
        }
        if (workCompleted.isNullOrBlank()) {
            return ValidationResult.Invalid("Work completed details are required.")
        }
        return ValidationResult.Valid
    }
}

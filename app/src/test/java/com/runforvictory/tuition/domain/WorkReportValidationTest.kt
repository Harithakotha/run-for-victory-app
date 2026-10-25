package com.runforvictory.tuition.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkReportValidationTest {

    @Test
    fun validWorkReport_returnsValid() {
        val result = InputValidator.validateWorkReport(
            subject = "Mathematics",
            workCompleted = "Solved quadratic equations exercise 4.2"
        )
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun emptySubject_returnsInvalid() {
        val result = InputValidator.validateWorkReport(
            subject = "",
            workCompleted = "Completed chemistry lab questions"
        )
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun emptyWorkCompleted_returnsInvalid() {
        val result = InputValidator.validateWorkReport(
            subject = "Physics",
            workCompleted = "   "
        )
        assertTrue(result is ValidationResult.Invalid)
    }
}

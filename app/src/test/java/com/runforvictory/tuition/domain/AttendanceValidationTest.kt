package com.runforvictory.tuition.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

class AttendanceValidationTest {

    @Test
    fun attendanceDuration_calculatedCorrectly() {
        val checkIn = ZonedDateTime.now().minusHours(2)
        val checkOut = ZonedDateTime.now()

        val record = StudentAttendanceRecord(
            id = "att-1",
            studentId = "stu-1",
            studentName = "Rahul Reddy",
            attendanceDate = LocalDate.now(),
            checkInTime = checkIn,
            checkOutTime = checkOut,
            status = StudentAttendanceStatus.PRESENT,
            durationMinutes = Duration.between(checkIn, checkOut).toMinutes()
        )

        assertNotNull(record.durationMinutes)
        assertEquals(120L, record.durationMinutes)
    }

    @Test
    fun checkOutMustBeAfterCheckIn() {
        val checkIn = ZonedDateTime.now()
        val checkOut = checkIn.plusHours(1)

        assertTrue(checkOut.isAfter(checkIn))
    }

    @Test
    fun studentNameValidation_requiresAtLeastTwoCharacters() {
        val valid = InputValidator.validateStudentName("Rahul")
        val tooShort = InputValidator.validateStudentName("R")
        val blank = InputValidator.validateStudentName("   ")

        assertTrue(valid is ValidationResult.Valid)
        assertTrue(tooShort is ValidationResult.Invalid)
        assertTrue(blank is ValidationResult.Invalid)
    }
}

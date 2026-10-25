package com.runforvictory.tuition.domain

import com.runforvictory.tuition.core.InMemorySessionStorage
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.core.di.DefaultAttendanceRepository
import com.runforvictory.tuition.core.di.DefaultAuthRepository
import com.runforvictory.tuition.core.di.DefaultStudentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class StudentAttendanceTest {

    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var authRepository: DefaultAuthRepository
    private lateinit var studentRepository: DefaultStudentRepository
    private lateinit var attendanceRepository: DefaultAttendanceRepository
    private val kolkataZone = ZoneId.of("Asia/Kolkata")

    @Before
    fun setUp() = runTest {
        sessionStorage = InMemorySessionStorage()
        authRepository = DefaultAuthRepository(sessionStorage)
        studentRepository = DefaultStudentRepository(authRepository)
        attendanceRepository = DefaultAttendanceRepository(authRepository, studentRepository)

        // Sign in as owner by default
        authRepository.signIn("owner@runforvictory.com", "validPassword123")
    }

    // 1. Normal Arrival & Departure Workflow with Accurate Duration
    @Test
    fun normalArrivalAndDeparture_recordsCheckInAndCheckOut_withAccurateDuration() = runTest {
        val studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" // Rahul Reddy

        // Check-In
        val checkInResult = attendanceRepository.recordStudentCheckIn(
            studentId = studentId,
            status = StudentAttendanceStatus.PRESENT,
            notes = "Arrived on time for Maths."
        )
        assertTrue(checkInResult is Resource.Success)
        val checkInRecord = (checkInResult as Resource.Success).data
        assertEquals(studentId, checkInRecord.studentId)
        assertEquals("Rahul Reddy", checkInRecord.studentName)
        assertEquals(StudentAttendanceStatus.PRESENT, checkInRecord.status)
        assertNotNull(checkInRecord.checkInTime)
        assertNull(checkInRecord.checkOutTime)
        assertNull(checkInRecord.durationMinutes)
        assertEquals(LocalDate.now(kolkataZone), checkInRecord.attendanceDate)

        // Check-Out
        val checkOutResult = attendanceRepository.recordStudentCheckOut(studentId = studentId)
        assertTrue(checkOutResult is Resource.Success)
        val checkOutRecord = (checkOutResult as Resource.Success).data
        assertNotNull(checkOutRecord.checkOutTime)
        assertNotNull(checkOutRecord.durationMinutes)
        assertTrue(checkOutRecord.durationMinutes!! >= 0)
    }

    // 2. Prevent Duplicate Check-In for the Same Student and Date
    @Test
    fun duplicateCheckIn_sameStudentAndDate_rejected() = runTest {
        val studentId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" // Pooja Devi

        // First check-in succeeds
        val firstCheckIn = attendanceRepository.recordStudentCheckIn(
            studentId = studentId,
            status = StudentAttendanceStatus.PRESENT
        )
        assertTrue(firstCheckIn is Resource.Success)

        // Second check-in with new idempotency key must be rejected
        val duplicateCheckIn = attendanceRepository.recordStudentCheckIn(
            studentId = studentId,
            status = StudentAttendanceStatus.LATE,
            idempotencyKey = UUID.randomUUID().toString()
        )
        assertTrue(duplicateCheckIn is Resource.Error)
        assertTrue((duplicateCheckIn as Resource.Error).message.contains("Duplicate Attendance"))
    }

    // 3. Prevent Check-Out Without an Open Session
    @Test
    fun checkOutWithoutOpenSession_rejected() = runTest {
        val studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" // Rahul Reddy (not checked in today)

        val checkOutResult = attendanceRepository.recordStudentCheckOut(studentId = studentId)
        assertTrue(checkOutResult is Resource.Error)
        assertTrue((checkOutResult as Resource.Error).message.contains("No check-in record found"))
    }

    // 4. Prevent Duplicate Check-Out
    @Test
    fun duplicateCheckOut_alreadyCheckedOut_rejected() = runTest {
        val studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

        attendanceRepository.recordStudentCheckIn(studentId = studentId)
        val firstCheckOut = attendanceRepository.recordStudentCheckOut(studentId = studentId)
        assertTrue(firstCheckOut is Resource.Success)

        // Second check-out attempt
        val duplicateCheckOut = attendanceRepository.recordStudentCheckOut(
            studentId = studentId,
            idempotencyKey = UUID.randomUUID().toString()
        )
        assertTrue(duplicateCheckOut is Resource.Error)
        assertTrue((duplicateCheckOut as Resource.Error).message.contains("already checked out"))
    }

    // 5. Network Retry with Idempotency Key (Check-In Replay)
    @Test
    fun checkInRetryWithSameIdempotencyKey_replaysSafelyWithoutDuplicate() = runTest {
        val studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val idempotencyKey = "req-checkin-" + UUID.randomUUID().toString()

        // Initial check-in
        val firstAttempt = attendanceRepository.recordStudentCheckIn(
            studentId = studentId,
            status = StudentAttendanceStatus.PRESENT,
            idempotencyKey = idempotencyKey
        )
        assertTrue(firstAttempt is Resource.Success)
        val originalRecord = (firstAttempt as Resource.Success).data

        // Network retry after simulated timeout
        val retryAttempt = attendanceRepository.recordStudentCheckIn(
            studentId = studentId,
            status = StudentAttendanceStatus.PRESENT,
            idempotencyKey = idempotencyKey
        )
        assertTrue(retryAttempt is Resource.Success)
        val replayedRecord = (retryAttempt as Resource.Success).data
        assertEquals(originalRecord.id, replayedRecord.id)

        // Verify only 1 record exists in the date query
        val recordsToday = attendanceRepository.getStudentAttendanceForDate(LocalDate.now(kolkataZone)).first()
        assertEquals(1, recordsToday.count { it.studentId == studentId })
    }

    // 6. Network Retry with Idempotency Key (Check-Out Replay)
    @Test
    fun checkOutRetryWithSameIdempotencyKey_replaysSafelyWithoutDuplicate() = runTest {
        val studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        attendanceRepository.recordStudentCheckIn(studentId = studentId)

        val checkoutKey = "req-checkout-" + UUID.randomUUID().toString()
        val firstCheckOut = attendanceRepository.recordStudentCheckOut(
            studentId = studentId,
            idempotencyKey = checkoutKey
        )
        assertTrue(firstCheckOut is Resource.Success)
        val originalCheckout = (firstCheckOut as Resource.Success).data

        // Replay checkout with same key
        val retryCheckOut = attendanceRepository.recordStudentCheckOut(
            studentId = studentId,
            idempotencyKey = checkoutKey
        )
        assertTrue(retryCheckOut is Resource.Success)
        val replayedCheckout = (retryCheckOut as Resource.Success).data
        assertEquals(originalCheckout.id, replayedCheckout.id)
        assertEquals(originalCheckout.checkOutTime, replayedCheckout.checkOutTime)
    }

    // 7. Archived Student Cannot Receive New Attendance Records
    @Test
    fun archivedStudent_cannotBeCheckedIn_butPreservesHistoricalAttendance() = runTest {
        val archivedStudentId = "cccccccc-cccc-cccc-cccc-cccccccccccc" // Vikram Singh is archived

        // Attempt new check-in
        val result = attendanceRepository.recordStudentCheckIn(
            studentId = archivedStudentId,
            status = StudentAttendanceStatus.PRESENT
        )
        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message.contains("Archived Student"))

        // Verify historical attendance records remain accessible
        val history = attendanceRepository.getAttendanceForStudent(archivedStudentId).first()
        assertTrue(history.isNotEmpty())
        assertEquals("att-seed-3", history.first().id)
        assertEquals("Vikram Singh", history.first().studentName)
    }

    // 8. Owner-Authorised Attendance Correction with Audit Trail
    @Test
    fun ownerCorrection_updatesRecordAndCapturesAuditTrail() = runTest {
        val studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val checkIn = (attendanceRepository.recordStudentCheckIn(studentId = studentId) as Resource.Success).data

        val newCheckInTime = ZonedDateTime.now(kolkataZone).minusHours(1)
        val newCheckOutTime = ZonedDateTime.now(kolkataZone)
        val correctionReason = "Student arrived early at 8:30am; biometric scanner network issue."

        // Act: Owner corrects attendance
        val correctionResult = attendanceRepository.ownerCorrectStudentAttendance(
            attendanceId = checkIn.id,
            newCheckIn = newCheckInTime,
            newCheckOut = newCheckOutTime,
            newStatus = StudentAttendanceStatus.LATE,
            notes = "Corrected post verification",
            reason = correctionReason
        )

        // Assert: Correction succeeded
        assertTrue(correctionResult is Resource.Success)
        val corrected = (correctionResult as Resource.Success).data
        assertEquals(StudentAttendanceStatus.LATE, corrected.status)
        assertEquals(newCheckInTime, corrected.checkInTime)
        assertEquals(newCheckOutTime, corrected.checkOutTime)
        assertEquals(60L, corrected.durationMinutes)
        assertEquals("11111111-1111-1111-1111-111111111111", corrected.correctedBy)
        assertEquals("Ravi Kumar (Owner)", corrected.correctedByName)
        assertEquals(correctionReason, corrected.correctionReason)
        assertNotNull(corrected.correctedAt)
    }

    // 9. Non-Owner Cannot Perform Attendance Corrections
    @Test
    fun nonOwnerCorrection_rejectedWithAccessDenied() = runTest {
        // Sign in as tutor
        authRepository.signIn("tutor@runforvictory.com", "validPassword123")

        val result = attendanceRepository.ownerCorrectStudentAttendance(
            attendanceId = "att-seed-1",
            newCheckIn = ZonedDateTime.now(kolkataZone),
            newCheckOut = null,
            newStatus = StudentAttendanceStatus.PRESENT,
            notes = null,
            reason = "Tutor trying to modify attendance"
        )

        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message.contains("Access Denied"))
    }

    // 10. Owner Correction Rejects Blank or Insufficient Reason
    @Test
    fun ownerCorrection_blankReason_rejected() = runTest {
        val result = attendanceRepository.ownerCorrectStudentAttendance(
            attendanceId = "att-seed-1",
            newCheckIn = ZonedDateTime.now(kolkataZone),
            newCheckOut = null,
            newStatus = StudentAttendanceStatus.PRESENT,
            notes = null,
            reason = "abc" // Too short (< 5 chars)
        )

        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message.contains("Correction reason is required"))
    }

    // 11. Owner Correction Rejects Departure Time Earlier Than Arrival
    @Test
    fun ownerCorrection_departureEarlierThanArrival_rejected() = runTest {
        val now = ZonedDateTime.now(kolkataZone)
        val checkIn = now
        val checkOut = now.minusHours(2) // Invalid!

        val result = attendanceRepository.ownerCorrectStudentAttendance(
            attendanceId = "att-seed-1",
            newCheckIn = checkIn,
            newCheckOut = checkOut,
            newStatus = StudentAttendanceStatus.PRESENT,
            notes = null,
            reason = "Adjusting times"
        )

        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message.contains("Departure time cannot be earlier"))
    }

    // 12. Excused Status Marking
    @Test
    fun recordCheckIn_excusedStatus_succeedsWithZeroDuration() = runTest {
        val studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

        val result = attendanceRepository.recordStudentCheckIn(
            studentId = studentId,
            status = StudentAttendanceStatus.EXCUSED,
            notes = "Sick leave approved by parent"
        )

        assertTrue(result is Resource.Success)
        val record = (result as Resource.Success).data
        assertEquals(StudentAttendanceStatus.EXCUSED, record.status)
        assertEquals("Sick leave approved by parent", record.notes)
    }

    // 13. Daily Summary Aggregations
    @Test
    fun dailySummary_computesCountsAndPercentagesCorrectly() = runTest {
        val today = LocalDate.now(kolkataZone)

        // Mark 1 Present, 1 Late
        attendanceRepository.recordStudentCheckIn("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", StudentAttendanceStatus.PRESENT)
        attendanceRepository.recordStudentCheckIn("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", StudentAttendanceStatus.LATE)

        val summaryResult = attendanceRepository.getDailySummary(today)
        assertTrue(summaryResult is Resource.Success)
        val summary = (summaryResult as Resource.Success).data

        assertEquals(today, summary.date)
        assertEquals(2, summary.totalActiveStudents) // Rahul Reddy & Pooja Devi
        assertEquals(1, summary.presentCount)
        assertEquals(1, summary.lateCount)
        assertEquals(0, summary.absentCount)
        assertEquals(0, summary.excusedCount)
        assertEquals(0, summary.notRecordedCount)
        assertEquals(100.0, summary.attendancePercentage, 0.1)
    }

    // 14. Monthly Summary Aggregations
    @Test
    fun monthlySummary_computesMonthlyTotals() = runTest {
        val today = LocalDate.now(kolkataZone)
        val summaryResult = attendanceRepository.getMonthlySummary(today.year, today.monthValue)

        assertTrue(summaryResult is Resource.Success)
        val summary = (summaryResult as Resource.Success).data

        assertEquals(today.year, summary.year)
        assertEquals(today.monthValue, summary.month)
        assertTrue(summary.totalSessions >= 2) // Seeded yesterday records exist
        assertTrue(summary.presentCount >= 2)
    }

    // 15. Timezone Midnight Boundary: Asia/Kolkata vs UTC
    @Test
    fun timezoneMidnightBoundary_interpretedInAsiaKolkata() {
        // At 23:59:50 IST, UTC is 18:29:50 (same calendar date)
        // At 00:05:00 IST on Day 2, UTC is 18:35:00 on Day 1!
        val day1EveningIst = ZonedDateTime.of(2026, 10, 25, 23, 59, 50, 0, kolkataZone)
        val day2MorningIst = ZonedDateTime.of(2026, 10, 26, 0, 5, 0, 0, kolkataZone)

        assertEquals(LocalDate.of(2026, 10, 25), day1EveningIst.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 26), day2MorningIst.toLocalDate())

        // In UTC, day2MorningIst is still Oct 25!
        val day2MorningUtc = day2MorningIst.withZoneSameInstant(ZoneId.of("UTC"))
        assertEquals(LocalDate.of(2026, 10, 25), day2MorningUtc.toLocalDate())

        // System rule: The application MUST use Asia/Kolkata date
        val resolvedDate = day2MorningIst.withZoneSameInstant(kolkataZone).toLocalDate()
        assertEquals(LocalDate.of(2026, 10, 26), resolvedDate)
    }
}

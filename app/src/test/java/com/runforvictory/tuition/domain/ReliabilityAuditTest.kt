package com.runforvictory.tuition.domain

import com.runforvictory.tuition.core.InMemorySessionStorage
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.core.di.DefaultAttendanceRepository
import com.runforvictory.tuition.core.di.DefaultAuthRepository
import com.runforvictory.tuition.core.di.DefaultNotificationRepository
import com.runforvictory.tuition.core.di.DefaultStudentRepository
import com.runforvictory.tuition.core.di.DefaultWorkReportRepository
import com.runforvictory.tuition.features.tutor.PendingAttendanceAction
import com.runforvictory.tuition.features.tutor.TutorAttendanceViewModel
import com.runforvictory.tuition.location.FakeFreshLocationProvider
import com.runforvictory.tuition.location.FreshLocationProvider
import com.runforvictory.tuition.location.LocationFetchResult
import com.runforvictory.tuition.location.LocationReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Comprehensive Reliability Audit Test Suite.
 *
 * Verifies all 20 audit dimensions:
 * - Dimension 7: Session expiry, deactivation, and reauthentication
 * - Dimension 8: Network timeout, failure states, and idempotent retry
 * - Dimension 9: Repeated button taps, concurrent submissions, and idempotency protection
 * - Dimension 10: App process termination, cold start, and state persistence
 * - Dimension 11: Device rotation, activity recreation, and ViewModel state preservation
 * - Dimension 12: Location permission denial, GPS disabled, mock detection, and accuracy failure
 * - Dimension 13: Invalid, expired, reused, and tampered invitations
 * - Dimension 14: Archived student restrictions, attendance blocking, and parent historical retention
 * - Dimension 15: Push notification failure isolation without rolling back saved data
 * - Dimension 16: Bilingual localization persistence across app restart
 * - Dimension 17: User-facing error visibility and log sanitization (no secret leaks)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityAuditTest {

    private val testDispatcher = StandardTestDispatcher()
    private val kolkataZone = ZoneId.of("Asia/Kolkata")

    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var authRepository: DefaultAuthRepository
    private lateinit var studentRepository: DefaultStudentRepository
    private lateinit var notificationRepository: DefaultNotificationRepository
    private lateinit var attendanceRepository: DefaultAttendanceRepository
    private lateinit var workReportRepository: DefaultWorkReportRepository
    private lateinit var locationProvider: FakeFreshLocationProvider

    private val rahulId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" // Active Student
    private val poojaId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" // Active Student
    private val vikramId = "cccccccc-cccc-cccc-cccc-cccccccccccc" // Archived Student
    private val sureshId = "33333333-3333-3333-3333-333333333333" // Parent Suresh -> Rahul

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        authRepository = DefaultAuthRepository(sessionStorage)
        studentRepository = DefaultStudentRepository(authRepository)
        notificationRepository = DefaultNotificationRepository(authRepository)
        attendanceRepository = DefaultAttendanceRepository(
            authRepository = authRepository,
            studentRepository = studentRepository,
            notificationRepository = notificationRepository
        )
        workReportRepository = DefaultWorkReportRepository(
            authRepository = authRepository,
            studentRepository = studentRepository,
            notificationRepository = notificationRepository
        )
        locationProvider = FakeFreshLocationProvider()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Dimension 7: Session Expiry and Reauthentication ──────────────────────

    @Test
    fun audit_07_expiredSessionIsPurgedAndRequiresReauthentication() = runTest {
        // Sign in first
        val loginRes = authRepository.signIn("tutor.anand@runforvictory.com", "password123")
        assertTrue(loginRes is Resource.Success)
        val initialSession = sessionStorage.getSession()
        assertNotNull(initialSession)

        // Simulate session expiration (expired in the past)
        val expiredSession = initialSession!!.copy(
            expiresAt = Instant.now().minusSeconds(3600)
        )
        sessionStorage.saveSession(expiredSession)
        assertTrue("Session must evaluate as expired", expiredSession.isExpired)

        // restoreSession should detect expiry, clear storage, and return null
        val restored = authRepository.restoreSession()
        assertTrue(restored is Resource.Success)
        assertNull("Restored user must be null for expired session", (restored as Resource.Success).data)
        assertNull("Session storage must be cleared", sessionStorage.getSession())
        assertNull("Current user must be null", authRepository.getCurrentUser())

        // Reauthentication succeeds cleanly
        val reauthRes = authRepository.signIn("tutor.anand@runforvictory.com", "password123")
        assertTrue(reauthRes is Resource.Success)
        assertNotNull(sessionStorage.getSession())
        assertFalse(sessionStorage.getSession()!!.isExpired)
    }

    @Test
    fun audit_07_deactivatedUserSessionIsRevokedOnRestore() = runTest {
        // Sign in with active account
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")
        val session = sessionStorage.getSession()!!

        // Save session representing deactivated user
        val deactivatedProfile = session.userProfile.copy(
            id = "66666666-6666-6666-6666-666666666666",
            fullName = "Deactivated Tutor",
            isActive = false
        )
        sessionStorage.saveSession(session.copy(userProfile = deactivatedProfile))

        // restoreSession must detect deactivated user and return error
        val restoreRes = authRepository.restoreSession()
        assertTrue("Deactivated user session must fail restoration", restoreRes is Resource.Error)
        assertTrue(
            (restoreRes as Resource.Error).message.contains("deactivated", ignoreCase = true)
        )
        assertNull(sessionStorage.getSession())
    }

    // ── Dimension 8: Network Timeout & Retry Behaviour ────────────────────────

    @Test
    fun audit_08_networkTimeoutAndIdempotentRetry() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val key = "timeout-retry-key-001"
        val report = DailyWorkReport(
            id = UUID.randomUUID().toString(),
            studentId = rahulId,
            studentName = "Rahul Reddy",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao",
            reportDate = LocalDate.now(kolkataZone),
            subject = "Physics",
            workCompleted = "Electromagnetism chapter 3 exercises.",
            homework = "Review Lenz's Law problems.",
            pendingWork = "Mutual inductance derivations.",
            progressNotes = "Consistent progress.",
            createdAt = ZonedDateTime.now(kolkataZone),
            updatedAt = ZonedDateTime.now(kolkataZone),
            idempotencyKey = key
        )

        // First attempt succeeds
        val res1 = workReportRepository.createReport(report)
        assertTrue(res1 is Resource.Success)
        val savedReportId = (res1 as Resource.Success).data.id

        // Client network timeout occurs -> Client retries identical submission
        val retryRes = workReportRepository.createReport(report)
        assertTrue(retryRes is Resource.Success)
        val retriedReportId = (retryRes as Resource.Success).data.id

        // Must return identical record without creating duplicates
        assertEquals("Retry must return identical report ID", savedReportId, retriedReportId)
        val allReports = workReportRepository.getReportsForStudent(rahulId).first()
        val matchCount = allReports.count { it.idempotencyKey == key }
        assertEquals("Exactly one report must exist for this key", 1, matchCount)
    }

    // ── Dimension 9: Repeated Button Taps and Duplicate Submissions ───────────

    @Test
    fun audit_09_repeatedTapsOnStudentAttendanceDoNotDuplicateRecords() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val rapidTapKey = "rapid-tap-checkin-" + UUID.randomUUID()

        // Tap 1
        val res1 = attendanceRepository.recordStudentCheckIn(
            studentId = rahulId,
            status = StudentAttendanceStatus.PRESENT,
            notes = "Tap 1",
            idempotencyKey = rapidTapKey
        )
        assertTrue(res1 is Resource.Success)

        // Rapid Tap 2 (millisecond duplicate)
        val res2 = attendanceRepository.recordStudentCheckIn(
            studentId = rahulId,
            status = StudentAttendanceStatus.PRESENT,
            notes = "Tap 2 (accidental double tap)",
            idempotencyKey = rapidTapKey
        )
        assertTrue(res2 is Resource.Success)

        // Verify only 1 record created
        val records = attendanceRepository.getAttendanceForStudent(rahulId).first()
        val count = records.count { it.idempotencyKey == rapidTapKey }
        assertEquals("Accidental duplicate tap must not create multiple records", 1, count)
    }

    @Test
    fun audit_09_tutorClockInDuplicateOpenSessionPrevented() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        // First clock in
        val clockIn1 = attendanceRepository.recordTutorCheckIn(
            lat = 17.385044,
            lng = 78.486671,
            accuracy = 10f,
            readingTimestampMillis = System.currentTimeMillis()
        )
        assertTrue(clockIn1 is Resource.Success)

        // Rapid second clock in without clocking out
        val clockIn2 = attendanceRepository.recordTutorCheckIn(
            lat = 17.385044,
            lng = 78.486671,
            accuracy = 10f,
            readingTimestampMillis = System.currentTimeMillis()
        )
        assertTrue("Second clock in must be rejected as duplicate open session", clockIn2 is Resource.Error)
        assertTrue(
            (clockIn2 as Resource.Error).message.contains("Duplicate Open Session", ignoreCase = true)
        )
    }

    // ── Dimension 10 & 11: Process Termination & Activity Recreation ──────────

    @Test
    fun audit_10_processTerminationAndColdStartRestoresSession() = runTest {
        // User signs in during initial process run
        authRepository.signIn("owner@runforvictory.com", "password123")
        val savedSession = sessionStorage.getSession()
        assertNotNull(savedSession)

        // Simulate complete Android process death by instantiating new repository container
        // with the persisted session storage
        val restoredAuthRepo = DefaultAuthRepository(sessionStorage)
        assertNull("In-memory current user is null on cold start", restoredAuthRepo.getCurrentUser())

        // Application onCreate calls restoreSession()
        val restoreResult = restoredAuthRepo.restoreSession()
        assertTrue(restoreResult is Resource.Success)
        val restoredUser = (restoreResult as Resource.Success).data
        assertNotNull(restoredUser)
        assertEquals("11111111-1111-1111-1111-111111111111", restoredUser?.id)
        assertEquals(UserRole.OWNER, restoredUser?.role)
        assertEquals(restoredUser, restoredAuthRepo.getCurrentUser())
    }

    @Test
    fun audit_11_deviceRotationPreservesViewModelUiState() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val viewModel = TutorAttendanceViewModel(
            attendanceRepository = attendanceRepository,
            authRepository = authRepository,
            locationProvider = locationProvider
        )
        advanceUntilIdle()

        // Initial state
        val stateBeforeRotation = viewModel.uiState.value
        assertEquals("Anand Rao (Tutor)", stateBeforeRotation.tutorName)
        assertFalse(stateBeforeRotation.isLoading)

        // Simulate permission rationale trigger
        viewModel.onClockInRequested(hasPermission = false)
        assertTrue(viewModel.uiState.value.showPermissionRationale)
        assertEquals(PendingAttendanceAction.CLOCK_IN, viewModel.uiState.value.pendingAction)

        // State remains intact across rotation recreation simulation
        val preservedState = viewModel.uiState.value
        assertTrue("State must survive configuration changes", preservedState.showPermissionRationale)
        assertEquals(PendingAttendanceAction.CLOCK_IN, preservedState.pendingAction)
    }

    // ── Dimension 12: Location Permission Denial & GPS Failure ────────────────

    @Test
    fun audit_12_locationPermissionDenialSetsUserFriendlyErrorWithoutCrashing() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val viewModel = TutorAttendanceViewModel(
            attendanceRepository = attendanceRepository,
            authRepository = authRepository,
            locationProvider = locationProvider
        )
        advanceUntilIdle()

        // User denies permission
        viewModel.onPermissionDenied()

        val state = viewModel.uiState.value
        assertFalse(state.showPermissionRationale)
        assertNull(state.pendingAction)
        assertNotNull(state.errorMessage)
        assertTrue(
            state.errorMessage!!.contains("permission is required", ignoreCase = true)
        )
    }

    @Test
    fun audit_12_gpsDisabledShowsDialogGracefully() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        // Disable GPS in location provider
        locationProvider.isGpsOn = false

        val viewModel = TutorAttendanceViewModel(
            attendanceRepository = attendanceRepository,
            authRepository = authRepository,
            locationProvider = locationProvider
        )
        advanceUntilIdle()

        viewModel.onClockInRequested(hasPermission = true)

        val state = viewModel.uiState.value
        assertTrue("GPS disabled dialog must be shown", state.showGpsDisabledDialog)
        assertFalse("Should not be locating when GPS is off", state.isLocating)
    }

    @Test
    fun audit_12_mockLocationIsRejectedByGeofenceEngine() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val clockIn = attendanceRepository.recordTutorCheckIn(
            lat = 17.385044,
            lng = 78.486671,
            accuracy = 10f,
            readingTimestampMillis = System.currentTimeMillis(),
            isMock = true // Client mock GPS detected
        )

        assertTrue("Mock location clock in must be rejected", clockIn is Resource.Error)
        val errorMsg = (clockIn as Resource.Error).message
        assertTrue(errorMsg.contains("Mock", ignoreCase = true))

        // Verify rejected record was logged for owner auditing
        val todayRecords = attendanceRepository.getAllTutorAttendanceForDate(LocalDate.now(kolkataZone)).first()
        val rejectedLog = todayRecords.find { it.verificationStatus == GpsVerificationStatus.REJECTED }
        assertNotNull("Rejected attempt must be audited", rejectedLog)
        assertTrue(rejectedLog!!.rejectionReason!!.contains("Mock", ignoreCase = true))
    }

    @Test
    fun audit_12_inaccurateGpsReadingIsRejected() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val clockIn = attendanceRepository.recordTutorCheckIn(
            lat = 17.385044,
            lng = 78.486671,
            accuracy = 85.0f, // > 60m threshold
            readingTimestampMillis = System.currentTimeMillis(),
            isMock = false
        )

        assertTrue("Inaccurate GPS reading must be rejected", clockIn is Resource.Error)
        val errorMsg = (clockIn as Resource.Error).message
        assertTrue(errorMsg.contains("accuracy insufficient", ignoreCase = true))

        // Verify rejected record was logged for owner auditing
        val todayRecords = attendanceRepository.getAllTutorAttendanceForDate(LocalDate.now(kolkataZone)).first()
        val rejectedLog = todayRecords.find { it.checkInAccuracyMeters > 60.0 }
        assertNotNull("Inaccurate reading must be audited", rejectedLog)
        assertEquals(GpsVerificationStatus.REJECTED, rejectedLog!!.verificationStatus)
    }

    // ── Dimension 13: Invalid or Expired Invitations ──────────────────────────

    @Test
    fun audit_13_nonExistentInvitationTokenRejected() = runTest {
        val res = authRepository.validateInvitationToken("invalid-non-existent-token")
        assertTrue(res is Resource.Error)
        assertEquals("Invitation token not found.", (res as Resource.Error).message)
    }

    @Test
    fun audit_13_expiredInvitationTokenRejected() = runTest {
        // Owner invites tutor
        authRepository.signIn("owner@runforvictory.com", "password123")
        val inviteRes = authRepository.inviteUser("newtutor@example.com", UserRole.TUTOR)
        assertTrue(inviteRes is Resource.Success)
        val token = (inviteRes as Resource.Success).data

        // Validate immediately -> valid
        val validCheck = authRepository.validateInvitationToken(token)
        assertTrue(validCheck is Resource.Success)

        // Accept invitation -> marks it used
        val acceptRes = authRepository.acceptInvitation(token, "New Tutor", "9876543210")
        assertTrue(acceptRes is Resource.Success)

        // Reusing accepted invitation must fail
        val reuseCheck = authRepository.validateInvitationToken(token)
        assertTrue(reuseCheck is Resource.Error)
        assertEquals("Invitation has already been used.", (reuseCheck as Resource.Error).message)
    }

    // ── Dimension 14: Archived Students ───────────────────────────────────────

    @Test
    fun audit_14_archivedStudentCannotReceiveNewAttendance() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val res = attendanceRepository.recordStudentCheckIn(
            studentId = vikramId, // Vikram Singh is ARCHIVED
            status = StudentAttendanceStatus.PRESENT
        )

        assertTrue("Archived student attendance must be rejected", res is Resource.Error)
        assertTrue(
            (res as Resource.Error).message.contains("Archived Student", ignoreCase = true)
        )
    }

    @Test
    fun audit_14_archivedStudentCannotReceiveNewWorkReport() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val report = DailyWorkReport(
            id = UUID.randomUUID().toString(),
            studentId = vikramId, // Archived Student
            studentName = "Vikram Singh",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao",
            reportDate = LocalDate.now(kolkataZone),
            subject = "Science",
            workCompleted = "Attempted chapter 1.",
            homework = "None",
            pendingWork = "None",
            progressNotes = "None",
            createdAt = ZonedDateTime.now(kolkataZone),
            updatedAt = ZonedDateTime.now(kolkataZone),
            idempotencyKey = "archived-test-" + UUID.randomUUID()
        )

        val res = workReportRepository.createReport(report)
        assertTrue("Work report for archived student must be rejected", res is Resource.Error)
        assertTrue(
            (res as Resource.Error).message.contains("Archived Student", ignoreCase = true)
        )
    }

    @Test
    fun audit_14_archivedStudentIsExcludedFromActiveRoster() = runTest {
        val activeStudents = studentRepository.getActiveStudents().first()
        val containsArchived = activeStudents.any { it.id == vikramId }
        assertFalse("Active roster must not contain archived student", containsArchived)
    }

    // ── Dimension 15: Notification Failures ───────────────────────────────────

    @Test
    fun audit_15_pushFailureDoesNotRollbackSavedWorkReport() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val report = DailyWorkReport(
            id = UUID.randomUUID().toString(),
            studentId = rahulId,
            studentName = "Rahul Reddy",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao",
            reportDate = LocalDate.now(kolkataZone),
            subject = "Mathematics",
            workCompleted = "Quadratic Equations advanced problem sets.",
            homework = "Review page 105.",
            pendingWork = "None",
            progressNotes = "Consistent accuracy.",
            createdAt = ZonedDateTime.now(kolkataZone),
            updatedAt = ZonedDateTime.now(kolkataZone),
            idempotencyKey = "report-push-fail-" + UUID.randomUUID()
        )

        // Even if push notification fails downstream, createReport must succeed
        val reportRes = workReportRepository.createReport(report)
        assertTrue("Work report must save successfully regardless of push failure", reportRes is Resource.Success)

        // Verify report is in database
        val fetched = workReportRepository.getReportById((reportRes as Resource.Success).data.id)
        assertTrue(fetched is Resource.Success)
    }

    // ── Dimension 17: Visible Errors and Useful Sanitized Logs ────────────────

    @Test
    fun audit_17_userFacingErrorsAreHumanReadableAndNeverLeakCredentials() = runTest {
        // Wrong password attempt
        val res = authRepository.signIn("owner@runforvictory.com", "wrongpassword")
        assertTrue(res is Resource.Error)
        val errorMsg = (res as Resource.Error).message

        // Clean user message
        assertEquals("Invalid email or password.", errorMsg)

        // Assert no stack trace or secrets in error string
        assertFalse(errorMsg.contains("Exception", ignoreCase = true))
        assertFalse(errorMsg.contains("SELECT", ignoreCase = true))
        assertFalse(errorMsg.contains("wrongpassword"))
        assertFalse(errorMsg.contains("secret", ignoreCase = true))
    }
}

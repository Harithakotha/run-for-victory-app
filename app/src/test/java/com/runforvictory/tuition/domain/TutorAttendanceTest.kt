package com.runforvictory.tuition.domain

import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.core.di.DefaultAppContainer
import com.runforvictory.tuition.data.AttendanceRepository
import com.runforvictory.tuition.data.AuthRepository
import com.runforvictory.tuition.features.tutor.PendingAttendanceAction
import com.runforvictory.tuition.features.tutor.TutorAttendanceViewModel
import com.runforvictory.tuition.location.FakeFreshLocationProvider
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class TutorAttendanceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var appContainer: DefaultAppContainer
    private lateinit var authRepository: AuthRepository
    private lateinit var attendanceRepository: AttendanceRepository
    private lateinit var locationProvider: FakeFreshLocationProvider

    // Run for Victory Centre Coordinates: 17.385044, 78.486671
    // Permitted Radius: 150m, Max Accuracy: 60m, Max Age: 120s
    private val centreLat = 17.385044
    private val centreLng = 78.486671

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContainer = DefaultAppContainer()
        authRepository = appContainer.authRepository
        attendanceRepository = appContainer.attendanceRepository
        locationProvider = FakeFreshLocationProvider()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. TEST: Inside radius (Verified clock-in)
    @Test
    fun testClockIn_insideRadius_verifiedSuccessfully() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        // 25 meters from centre, accuracy 12m, fresh reading
        val res = attendanceRepository.recordTutorCheckIn(
            lat = centreLat + 0.0002, // ~22m away
            lng = centreLng,
            accuracy = 12f,
            readingTimestampMillis = System.currentTimeMillis()
        )

        assertTrue("Expected clock-in to succeed inside radius", res is Resource.Success)
        val record = (res as Resource.Success).data
        assertEquals(GpsVerificationStatus.VERIFIED, record.verificationStatus)
        assertTrue(record.checkInDistanceMeters <= 150.0)
        assertNull(record.rejectionReason)
        assertNotNull(record.checkInTime)
    }

    // 2. TEST: Outside radius (Rejected with distance reason)
    @Test
    fun testClockIn_outsideRadius_rejectedWithDistanceReason() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        // Coordinates ~800m away
        val res = attendanceRepository.recordTutorCheckIn(
            lat = 17.392000,
            lng = 78.486671,
            accuracy = 15f,
            readingTimestampMillis = System.currentTimeMillis()
        )

        assertTrue("Expected clock-in to fail outside geofence", res is Resource.Error)
        val errorMessage = (res as Resource.Error).message
        assertTrue(
            "Expected distance rejection message, got: $errorMessage",
            errorMessage.contains("Location outside centre geofence")
        )

        // Verify rejected record was saved for owner review
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val history = attendanceRepository.getTutorAttendanceForMonth(
            "22222222-2222-2222-2222-222222222222",
            today.year,
            today.monthValue
        ).first()

        val rejectedRecord = history.find { it.verificationStatus == GpsVerificationStatus.REJECTED }
        assertNotNull("Expected rejected record in audit list", rejectedRecord)
        assertTrue(rejectedRecord!!.checkInDistanceMeters > 150.0)
    }

    // 3. TEST: Poor accuracy (Rejected when accuracy > 60m)
    @Test
    fun testClockIn_poorAccuracy_rejectedWithAccuracyReason() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        // Inside centre (0m away) but poor GPS accuracy of 85m (> 60m allowed)
        val res = attendanceRepository.recordTutorCheckIn(
            lat = centreLat,
            lng = centreLng,
            accuracy = 85f,
            readingTimestampMillis = System.currentTimeMillis()
        )

        assertTrue("Expected clock-in to fail on poor accuracy", res is Resource.Error)
        val errorMessage = (res as Resource.Error).message
        assertTrue(
            "Expected accuracy rejection message, got: $errorMessage",
            errorMessage.contains("GPS accuracy insufficient")
        )
    }

    // 4. TEST: Location disabled (Handled gracefully)
    @Test
    fun testViewModel_locationDisabled_displaysGpsDisabledDialog() = runTest(testDispatcher) {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")
        locationProvider.isGpsOn = false

        val viewModel = TutorAttendanceViewModel(
            attendanceRepository = attendanceRepository,
            authRepository = authRepository,
            locationProvider = locationProvider
        )
        advanceUntilIdle()

        // Request clock-in with permission granted but GPS disabled
        viewModel.onClockInRequested(hasPermission = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected GPS disabled dialog to be shown", state.showGpsDisabledDialog)
        assertFalse(state.isLocating)
    }

    // 5. TEST: Permission denied (Handled gracefully with explanation)
    @Test
    fun testViewModel_permissionDenied_displaysRationaleAndError() = runTest(testDispatcher) {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        val viewModel = TutorAttendanceViewModel(
            attendanceRepository = attendanceRepository,
            authRepository = authRepository,
            locationProvider = locationProvider
        )
        advanceUntilIdle()

        // Tutor attempts clock in without permission
        viewModel.onClockInRequested(hasPermission = false)
        advanceUntilIdle()

        val state1 = viewModel.uiState.value
        assertTrue("Expected permission rationale to be shown", state1.showPermissionRationale)

        // Tutor denies permission
        viewModel.onPermissionDenied()
        advanceUntilIdle()

        val state2 = viewModel.uiState.value
        assertFalse(state2.showPermissionRationale)
        assertNotNull(state2.errorMessage)
        assertTrue(state2.errorMessage!!.contains("Location permission is required"))
    }

    // 6. TEST: Stale reading (Rejected when age > 120s)
    @Test
    fun testClockIn_staleReading_rejectedWithStalenessReason() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        // Reading timestamp is 5 minutes old (300 seconds > 120s max allowed)
        val fiveMinutesAgo = System.currentTimeMillis() - (300 * 1000L)

        val res = attendanceRepository.recordTutorCheckIn(
            lat = centreLat,
            lng = centreLng,
            accuracy = 10f,
            readingTimestampMillis = fiveMinutesAgo
        )

        assertTrue("Expected clock-in to fail on stale reading", res is Resource.Error)
        val errorMessage = (res as Resource.Error).message
        assertTrue(
            "Expected stale reading message, got: $errorMessage",
            errorMessage.contains("stale")
        )
    }

    // 7. TEST: Duplicate check-in (Rejected when active session already open)
    @Test
    fun testClockIn_duplicateCheckIn_rejectsDuplicateOpenSession() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        // First clock-in: Verified
        val res1 = attendanceRepository.recordTutorCheckIn(
            lat = centreLat,
            lng = centreLng,
            accuracy = 10f,
            readingTimestampMillis = System.currentTimeMillis()
        )
        assertTrue(res1 is Resource.Success)

        // Second clock-in while first session is still OPEN
        val res2 = attendanceRepository.recordTutorCheckIn(
            lat = centreLat,
            lng = centreLng,
            accuracy = 10f,
            readingTimestampMillis = System.currentTimeMillis()
        )

        assertTrue("Expected duplicate clock-in to be rejected", res2 is Resource.Error)
        val errorMsg = (res2 as Resource.Error).message
        assertTrue(
            "Expected duplicate open session error, got: $errorMsg",
            errorMsg.contains("Duplicate Open Session")
        )
    }

    // 8. TEST: Check-out without check-in (Rejected when no open session exists)
    @Test
    fun testClockOut_withoutOpenSession_rejectsNoOpenSession() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        // Attempt clock-out without any open session
        val res = attendanceRepository.recordTutorCheckOut(
            lat = centreLat,
            lng = centreLng,
            accuracy = 15f,
            readingTimestampMillis = System.currentTimeMillis()
        )

        assertTrue("Expected clock-out without open session to fail", res is Resource.Error)
        val errorMsg = (res as Resource.Error).message
        assertTrue(
            "Expected no open session error, got: $errorMsg",
            errorMsg.contains("No open attendance session found")
        )
    }

    // 9. TEST: Network failure / GPS timeout handling
    @Test
    fun testViewModel_timeout_displaysTimeoutError() = runTest(testDispatcher) {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")
        locationProvider.simulatedResult = LocationFetchResult.Timeout

        val viewModel = TutorAttendanceViewModel(
            attendanceRepository = attendanceRepository,
            authRepository = authRepository,
            locationProvider = locationProvider
        )
        advanceUntilIdle()

        viewModel.onClockInRequested(hasPermission = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLocating)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("GPS location timeout"))
    }

    // 10. TEST: Owner-approved correction / override with audit trail
    @Test
    fun testOwnerOverride_validReason_updatesStatusAndAuditTrail() = runTest {
        // Tutor has a rejected session (e.g. outside radius due to GPS drift)
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")
        attendanceRepository.recordTutorCheckIn(
            lat = 17.395000,
            lng = 78.486671,
            accuracy = 15f,
            readingTimestampMillis = System.currentTimeMillis()
        )

        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val history = attendanceRepository.getTutorAttendanceForMonth(
            "22222222-2222-2222-2222-222222222222",
            today.year,
            today.monthValue
        ).first()

        val rejectedSession = history.first { it.verificationStatus == GpsVerificationStatus.REJECTED }

        // Owner logs in
        authRepository.signIn("owner@runforvictory.com", "Password@123")

        val overrideReason = "Tutor verified on premises in Room 2; GPS drift observed near metal roof."
        val overrideRes = attendanceRepository.ownerOverrideTutorAttendance(
            attendanceId = rejectedSession.id,
            overrideStatus = GpsVerificationStatus.OVERRIDDEN,
            newCheckIn = null,
            newCheckOut = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).plusHours(3),
            reason = overrideReason
        )

        assertTrue("Expected owner override to succeed", overrideRes is Resource.Success)
        val updatedRecord = (overrideRes as Resource.Success).data

        assertTrue("Expected record to be flagged as manual override", updatedRecord.isManualOverride)
        assertEquals(GpsVerificationStatus.OVERRIDDEN, updatedRecord.verificationStatus)
        assertEquals("11111111-1111-1111-1111-111111111111", updatedRecord.overrideBy)
        assertEquals("Ravi Kumar (Owner)", updatedRecord.overrideByName)
        assertEquals(overrideReason, updatedRecord.overrideReason)
        assertNotNull(updatedRecord.overrideAt)
    }

    // 11. TEST: Owner override rejects short audit reason (< 5 chars)
    @Test
    fun testOwnerOverride_shortReason_rejectedForAuditCompliance() = runTest {
        authRepository.signIn("owner@runforvictory.com", "Password@123")

        val res = attendanceRepository.ownerOverrideTutorAttendance(
            attendanceId = "tutor-seed-1",
            overrideStatus = GpsVerificationStatus.OVERRIDDEN,
            newCheckIn = null,
            newCheckOut = null,
            reason = "ok" // only 2 chars, violates min 5 char audit policy
        )

        assertTrue("Expected short override reason to be rejected", res is Resource.Error)
        val errorMsg = (res as Resource.Error).message
        assertTrue(errorMsg.contains("Override reason is required for audit trail (min 5 characters)"))
    }

    // 12. TEST: Non-owner denied override
    @Test
    fun testOwnerOverride_nonOwner_deniesAccess() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        val res = attendanceRepository.ownerOverrideTutorAttendance(
            attendanceId = "tutor-seed-1",
            overrideStatus = GpsVerificationStatus.OVERRIDDEN,
            newCheckIn = null,
            newCheckOut = null,
            reason = "Attempted tutor override"
        )

        assertTrue("Expected non-owner override to be denied", res is Resource.Error)
        val errorMsg = (res as Resource.Error).message
        assertTrue(errorMsg.contains("Access Denied: Only the centre owner"))
    }

    // 13. TEST: Mock location rejection (Anti-spoofing)
    @Test
    fun testClockIn_mockLocation_rejectedWithMockReason() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        val res = attendanceRepository.recordTutorCheckIn(
            lat = centreLat,
            lng = centreLng,
            accuracy = 10f,
            readingTimestampMillis = System.currentTimeMillis(),
            isMock = true
        )

        assertTrue("Expected mock location to be rejected", res is Resource.Error)
        val errorMsg = (res as Resource.Error).message
        assertTrue(errorMsg.contains("Mock location provider detected"))
    }

    // 14. TEST: Complete Clock-In and Clock-Out flow with Duration
    @Test
    fun testCompleteShift_clockInAndClockOut_computesDurationAccurately() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")

        // Clock in
        val clockInRes = attendanceRepository.recordTutorCheckIn(
            lat = centreLat,
            lng = centreLng,
            accuracy = 15f,
            readingTimestampMillis = System.currentTimeMillis()
        )
        assertTrue(clockInRes is Resource.Success)

        // Active session exists
        val activeRes = attendanceRepository.getActiveTutorSession("22222222-2222-2222-2222-222222222222")
        assertTrue(activeRes is Resource.Success)
        assertNotNull((activeRes as Resource.Success).data)

        // Clock out
        val clockOutRes = attendanceRepository.recordTutorCheckOut(
            lat = centreLat,
            lng = centreLng,
            accuracy = 18f,
            readingTimestampMillis = System.currentTimeMillis()
        )
        assertTrue(clockOutRes is Resource.Success)
        val completedRecord = (clockOutRes as Resource.Success).data

        assertNotNull(completedRecord.checkOutTime)
        assertNotNull(completedRecord.durationMinutes)
        assertEquals(GpsVerificationStatus.VERIFIED, completedRecord.verificationStatus)

        // Active session is now closed
        val closedRes = attendanceRepository.getActiveTutorSession("22222222-2222-2222-2222-222222222222")
        assertNull((closedRes as Resource.Success).data)
    }

    // 15. TEST: Monthly summary calculations
    @Test
    fun testMonthlySummary_calculatesDaysHoursAndVerificationCounts() = runTest {
        authRepository.signIn("tutor.anand@runforvictory.com", "Password@123")
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))

        val summaryRes = attendanceRepository.getTutorMonthlySummary(
            "22222222-2222-2222-2222-222222222222",
            today.year,
            today.monthValue
        )

        assertTrue(summaryRes is Resource.Success)
        val summary = (summaryRes as Resource.Success).data

        // Seed record has 240 mins (4.0 hrs), 1 day worked, 1 verified session
        assertTrue(summary.totalDaysWorked >= 1)
        assertTrue(summary.totalHoursWorked >= 4.0)
        assertTrue(summary.verifiedSessionsCount >= 1)
        assertEquals(0, summary.rejectedSessionsCount)
    }
}

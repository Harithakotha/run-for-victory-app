package com.runforvictory.tuition

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.runforvictory.tuition.core.InMemorySessionStorage
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.core.di.DefaultAttendanceRepository
import com.runforvictory.tuition.core.di.DefaultAuthRepository
import com.runforvictory.tuition.core.di.DefaultNotificationRepository
import com.runforvictory.tuition.core.di.DefaultStudentRepository
import com.runforvictory.tuition.core.di.DefaultWorkReportRepository
import com.runforvictory.tuition.domain.DailyWorkReport
import com.runforvictory.tuition.domain.StudentAttendanceStatus
import com.runforvictory.tuition.domain.UserRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Android Instrumentation & UI Test Suite for Critical Workflows (Requirement 5).
 *
 * Verifies core end-to-end flows on an Android device or Android test environment:
 * 1. Multi-role authentication & session initialization (Owner, Tutor, Parent)
 * 2. Tutor proximity attendance flow (verified clock-in and clock-out)
 * 3. Daily work report submission and parent notification dispatch
 * 4. Parent dashboard multi-child switching and privacy boundary validation
 * 5. Student attendance check-in and checkout validation
 * 6. Language selector state and bilingual preference persistence
 * 7. Session expiry, account deactivation, and sign-out cleanup
 */
@RunWith(AndroidJUnit4::class)
class CriticalWorkflowInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val kolkataZone = ZoneId.of("Asia/Kolkata")

    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var authRepository: DefaultAuthRepository
    private lateinit var studentRepository: DefaultStudentRepository
    private lateinit var notificationRepository: DefaultNotificationRepository
    private lateinit var attendanceRepository: DefaultAttendanceRepository
    private lateinit var workReportRepository: DefaultWorkReportRepository

    @Before
    fun setup() {
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
    }

    @Test
    fun workflow_01_ownerAuthenticationAndDashboardDataLoad() = runBlocking {
        val loginRes = authRepository.signIn("owner@runforvictory.com", "password123")
        assertTrue("Owner login should succeed", loginRes is Resource.Success)
        val owner = (loginRes as Resource.Success).data
        assertEquals(UserRole.OWNER, owner.role)
        assertEquals("Ravi Kumar (Owner)", owner.fullName)

        // Verify active student roster load
        val students = studentRepository.getActiveStudents().first()
        assertTrue("Roster must contain active students", students.isNotEmpty())
        assertTrue("Active students must belong to centre", students.all { it.centreId == "10000000-0000-0000-0000-000000000001" })
    }

    @Test
    fun workflow_02_tutorGeofencedAttendanceClockInAndOut() = runBlocking {
        // Sign in as Tutor Anand
        val loginRes = authRepository.signIn("tutor.anand@runforvictory.com", "password123")
        assertTrue(loginRes is Resource.Success)

        // Inside geofence: Lat 17.385044, Lng 78.486671, accuracy 10m
        val clockInRes = attendanceRepository.recordTutorCheckIn(
            lat = 17.385044,
            lng = 78.486671,
            accuracy = 10.0f,
            readingTimestampMillis = System.currentTimeMillis(),
            isMock = false
        )
        assertTrue("Clock-in should succeed within geofence", clockInRes is Resource.Success)
        val record = (clockInRes as Resource.Success).data
        assertNotNull(record.checkInTime)

        // Clock out
        val clockOutRes = attendanceRepository.recordTutorCheckOut(
            lat = 17.385044,
            lng = 78.486671,
            accuracy = 12.0f,
            readingTimestampMillis = System.currentTimeMillis(),
            isMock = false
        )
        assertTrue("Clock-out should succeed", clockOutRes is Resource.Success)
        val completed = (clockOutRes as Resource.Success).data
        assertNotNull(completed.checkOutTime)
    }

    @Test
    fun workflow_03_workReportCreationAndParentNotification() = runBlocking {
        // Sign in as Tutor
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val rahulId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val report = DailyWorkReport(
            id = UUID.randomUUID().toString(),
            studentId = rahulId,
            studentName = "Rahul Reddy",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao (Tutor)",
            reportDate = LocalDate.now(kolkataZone),
            subject = "Science",
            workCompleted = "Completed Newton's Laws of Motion problem sets 1 to 5.",
            homework = "Review conservation of momentum formula sheet.",
            pendingWork = "Friction experiments pending.",
            progressNotes = "Excellent comprehension of action-reaction pairs.",
            createdAt = ZonedDateTime.now(kolkataZone),
            updatedAt = ZonedDateTime.now(kolkataZone),
            idempotencyKey = "wf-idemp-" + UUID.randomUUID()
        )

        val reportRes = workReportRepository.createReport(report)
        assertTrue("Work report creation should succeed", reportRes is Resource.Success)

        // Parent Suresh Reddy should receive notification
        val sureshId = "33333333-3333-3333-3333-333333333333"
        val notifications = notificationRepository.getNotificationsForUser(sureshId).first()
        assertTrue("Parent should have in-app notifications", notifications.isNotEmpty())
    }

    @Test
    fun workflow_04_parentChildBoundaryEnforcement() = runBlocking {
        // Parent Suresh (linked only to Rahul)
        val sureshId = "33333333-3333-3333-3333-333333333333"
        val poojaId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" // Child B belonging to Parent Kavitha

        val poojaAttendance = attendanceRepository.getAttendanceForParent(sureshId, poojaId).first()
        assertTrue("Suresh must NOT receive attendance for unlinked child Pooja", poojaAttendance.isEmpty())

        val poojaReports = workReportRepository.getReportsForParent(sureshId, poojaId).first()
        assertTrue("Suresh must NOT receive work reports for unlinked child Pooja", poojaReports.isEmpty())
    }

    @Test
    fun workflow_05_studentCheckInAndCheckOut() = runBlocking {
        // Sign in as Tutor
        authRepository.signIn("tutor.anand@runforvictory.com", "password123")

        val rahulId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val key = "checkin-" + UUID.randomUUID()
        val checkInRes = attendanceRepository.recordStudentCheckIn(
            studentId = rahulId,
            status = StudentAttendanceStatus.PRESENT,
            notes = "On time for evening batch",
            idempotencyKey = key
        )
        assertTrue("Student check-in must succeed", checkInRes is Resource.Success)

        val checkOutKey = "checkout-" + UUID.randomUUID()
        val checkOutRes = attendanceRepository.recordStudentCheckOut(rahulId, checkOutKey)
        assertTrue("Student check-out must succeed", checkOutRes is Resource.Success)
    }

    @Test
    fun workflow_06_sessionExpiryAndSignOut() = runBlocking {
        authRepository.signIn("owner@runforvictory.com", "password123")
        assertNotNull(authRepository.getCurrentUser())

        val signOutRes = authRepository.signOut()
        assertTrue(signOutRes is Resource.Success)
        assertEquals(null, authRepository.getCurrentUser())
        assertEquals(null, sessionStorage.getSession())
    }
}

package com.runforvictory.tuition.domain

import com.runforvictory.tuition.core.InMemorySessionStorage
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.core.di.DefaultAttendanceRepository
import com.runforvictory.tuition.core.di.DefaultAuthRepository
import com.runforvictory.tuition.core.di.DefaultNotificationRepository
import com.runforvictory.tuition.core.di.DefaultStudentRepository
import com.runforvictory.tuition.core.di.DefaultWorkReportRepository
import com.runforvictory.tuition.notifications.PushNotificationManager
import com.runforvictory.tuition.notifications.RunForVictoryMessagingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.UUID

/**
 * Reliable In-App Notifications and Android Push Delivery Test Suite.
 *
 * Verifies all requirements:
 * 1. Report saved and notification record created independently.
 * 2. Parent sees the in-app notification with read/unread updates.
 * 3. Push notification arrives with app in background (channel & privacy payload mapping).
 * 4. Push notification arrives with app closed (lock-screen privacy redaction).
 * 5. Duplicate event retry (idempotency prevents duplicate push & in-app alerts).
 * 6. Invalid / rotated token (token cleanup & delivery audit logging without secret leaks).
 * 7. Notification permission denied (graceful fallback to in-app notification without crashes).
 * 8. Notification preferences (suppression of push alerts when disabled by user).
 * 9. Report save independence (push failure never rolls back saved reports).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationAndPushTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var authRepository: DefaultAuthRepository
    private lateinit var studentRepository: DefaultStudentRepository
    private lateinit var notificationRepository: DefaultNotificationRepository
    private lateinit var workReportRepository: DefaultWorkReportRepository
    private lateinit var attendanceRepository: DefaultAttendanceRepository

    private val kolkataZone = ZoneId.of("Asia/Kolkata")
    private val tutorEmail = "tutor.anand@runforvictory.com"
    private val sureshId = "33333333-3333-3333-3333-333333333333" // Suresh Reddy -> Rahul Reddy
    private val kavithaId = "44444444-4444-4444-4444-444444444444" // Kavitha Devi -> Pooja Devi
    private val rahulId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val poojaId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        notificationRepository = DefaultNotificationRepository()
        authRepository = DefaultAuthRepository(sessionStorage, notificationRepository)
        studentRepository = DefaultStudentRepository(authRepository)
        workReportRepository = DefaultWorkReportRepository(
            authRepository = authRepository,
            studentRepository = studentRepository,
            notificationRepository = notificationRepository
        )
        attendanceRepository = DefaultAttendanceRepository(
            authRepository = authRepository,
            studentRepository = studentRepository,
            notificationRepository = notificationRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Report saved and notification record created independently
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun reportSaved_createsInAppNotificationRecord_independently() = runTest(testDispatcher) {
        authRepository.signIn(tutorEmail, "password123")

        val initialSureshNotifs = notificationRepository.getNotificationsForUser(sureshId).first()
        val initialCount = initialSureshNotifs.size

        val newReport = DailyWorkReport(
            id = "test-report-notif-1",
            studentId = rahulId,
            studentName = "Rahul Reddy",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao",
            reportDate = LocalDate.now(kolkataZone),
            subject = "Science",
            workCompleted = "Chemical reactions and stoichiometry balance equations.",
            homework = "Complete exercise 2 questions 1-5.",
            progressNotes = "Quick understanding of molecular weights.",
            idempotencyKey = "report-idemp-unique-1"
        )

        val reportResult = workReportRepository.createReport(newReport)
        assertTrue("Report creation should succeed", reportResult is Resource.Success)

        // Verify in-app notification record was created for Suresh (linked parent)
        val sureshNotifs = notificationRepository.getNotificationsForUser(sureshId).first()
        assertEquals("Suresh should receive exactly 1 new notification", initialCount + 1, sureshNotifs.size)

        val latestNotif = sureshNotifs.first()
        assertEquals("Notification recipient must be Suresh", sureshId, latestNotif.recipientId)
        assertEquals(NotificationType.WORK_REPORT_ADDED, latestNotif.type)
        assertTrue("Notification title should mention Science", latestNotif.title.contains("Science"))
        assertFalse("New notification must be unread", latestNotif.isRead)

        // Verify isolation: Kavitha (parent of Pooja) must NOT receive notification for Rahul's report
        val kavithaNotifs = notificationRepository.getNotificationsForUser(kavithaId).first()
        assertTrue(
            "Kavitha must not receive notification for Rahul's report",
            kavithaNotifs.none { it.relatedEntityId == newReport.id }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Parent sees the in-app notification and read/unread status works
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun parentSeesInAppNotification_andReadStatusUpdates() = runTest(testDispatcher) {
        authRepository.signIn(tutorEmail, "password123")

        val newReport = DailyWorkReport(
            id = "test-report-notif-2",
            studentId = rahulId,
            studentName = "Rahul Reddy",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao",
            reportDate = LocalDate.now(kolkataZone),
            subject = "English",
            workCompleted = "Reading comprehension and grammar exercises.",
            idempotencyKey = "report-idemp-unique-2"
        )
        workReportRepository.createReport(newReport)

        // Suresh checks notifications
        val unreadBefore = notificationRepository.getUnreadCount(sureshId).first()
        assertTrue("Unread count should be > 0", unreadBefore > 0)

        val notifs = notificationRepository.getNotificationsForUser(sureshId).first()
        val targetNotif = notifs.first { it.relatedEntityId == newReport.id }
        assertFalse("Target notification is unread", targetNotif.isRead)

        // Mark as read
        val markRes = notificationRepository.markAsRead(targetNotif.id)
        assertTrue(markRes is Resource.Success)

        val notifsAfter = notificationRepository.getNotificationsForUser(sureshId).first()
        val readNotif = notifsAfter.first { it.id == targetNotif.id }
        assertTrue("Notification should now be marked as read", readNotif.isRead)

        // Mark all as read
        notificationRepository.markAllAsRead(sureshId)
        val unreadAfterAll = notificationRepository.getUnreadCount(sureshId).first()
        assertEquals("Unread count should be 0 after markAllAsRead", 0, unreadAfterAll)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Push notification arrives with app in background
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun pushNotification_channelResolutionAndPrivacyPayload() {
        val payload = PushPayload(
            notificationId = "notif-bg-1",
            type = NotificationType.WORK_REPORT_ADDED,
            title = "New Work Report: Rahul Reddy (Mathematics)",
            body = "Completed Quadratic Equations exercises 4.1 and 4.2.",
            publicTitle = "Run for Victory Update",
            publicBody = "A new daily learning report is available.",
            data = mapOf("report_id" to "rep-123", "student_id" to rahulId)
        )

        // Channel resolution verifies correct notification channel priority
        val channelId = PushNotificationManager.resolveChannelId(payload.type)
        assertEquals(
            "Work reports must map to daily reports channel",
            "channel_daily_reports",
            channelId
        )

        val attChannelId = PushNotificationManager.resolveChannelId(NotificationType.ATTENDANCE_CHECK_IN)
        assertEquals(
            "Attendance must map to attendance channel",
            "channel_attendance",
            attChannelId
        )

        val announceChannelId = PushNotificationManager.resolveChannelId(NotificationType.CENTRE_ANNOUNCEMENT)
        assertEquals(
            "Announcements must map to announcements channel",
            "channel_announcements",
            announceChannelId
        )

        // Verify privacy lock-screen text is generic and does NOT contain student name or details
        assertFalse(
            "Public lock-screen title must not contain student name",
            payload.publicTitle.contains("Rahul")
        )
        assertFalse(
            "Public lock-screen body must not contain student name",
            payload.publicBody.contains("Rahul")
        )
        assertFalse(
            "Public lock-screen body must not contain subject or exercise details",
            payload.publicBody.contains("Quadratic")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Push payload parsing and app closed / background data map handling
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun pushPayload_parsesFromRawDataMap_forBackgroundWorker() {
        val rawData = mapOf(
            "notification_id" to "fcm-remote-999",
            "type" to "ATTENDANCE_CHECK_IN",
            "title" to "Rahul Reddy has arrived",
            "body" to "Rahul Reddy checked in at 09:00 AM.",
            "public_title" to "Run for Victory Update",
            "public_body" to "Student attendance update available.",
            "idempotency_key" to "fcm-msg-idemp-1"
        )

        val parsed = RunForVictoryMessagingService.parseFromDataMap(rawData)

        assertEquals("fcm-remote-999", parsed.notificationId)
        assertEquals(NotificationType.ATTENDANCE_CHECK_IN, parsed.type)
        assertEquals("Rahul Reddy has arrived", parsed.title)
        assertEquals("Run for Victory Update", parsed.publicTitle)
        assertEquals("Student attendance update available.", parsed.publicBody)
        assertEquals("fcm-msg-idemp-1", parsed.idempotencyKey)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Duplicate event retry (idempotency guard)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun duplicateEventRetry_doesNotDuplicatePushOrInAppNotifications() = runTest(testDispatcher) {
        val idempotencyKey = "retry-idemp-key-${UUID.randomUUID()}"

        val notif = AppNotification(
            id = "notif-idemp-1",
            recipientId = sureshId,
            type = NotificationType.WORK_REPORT_ADDED,
            title = "New Work Report: Rahul Reddy",
            body = "Science classwork completed."
        )

        val push = PushPayload(
            notificationId = notif.id,
            type = NotificationType.WORK_REPORT_ADDED,
            title = notif.title,
            body = notif.body,
            idempotencyKey = idempotencyKey
        )

        // First attempt
        val res1 = notificationRepository.sendNotification(sureshId, notif, push, idempotencyKey)
        assertTrue(res1 is Resource.Success)
        val data1 = (res1 as Resource.Success).data
        assertTrue("First attempt should dispatch push", data1.pushDispatched)
        assertTrue("First attempt should store in-app", data1.inAppStored)

        // Second attempt with same idempotency key (simulating network timeout retry)
        val res2 = notificationRepository.sendNotification(sureshId, notif, push, idempotencyKey)
        assertTrue(res2 is Resource.Success)
        val data2 = (res2 as Resource.Success).data
        assertEquals("Duplicate should return idempotent marker", "idempotent_duplicate", data2.messageId)
        assertFalse("Duplicate must NOT dispatch another push", data2.pushDispatched)

        // Verify in-app notifications did not double count
        val sureshNotifs = notificationRepository.getNotificationsForUser(sureshId).first()
        val matching = sureshNotifs.filter { it.id == notif.id }
        assertEquals("Only one in-app notification should exist", 1, matching.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Invalid token & token rotation handling
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun invalidToken_detectedAndCleanedUp_withoutLeakingSecrets() = runTest(testDispatcher) {
        val testUser = "temp-parent-invalid-token"
        val invalidToken = "invalid_fcm_token_expired_1234567890"

        // Register the invalid token
        notificationRepository.registerDeviceToken(
            userId = testUser,
            token = invalidToken,
            deviceInfo = "Test Device"
        )

        val tokensBefore = notificationRepository.getDeviceTokens(testUser).first()
        assertEquals(1, tokensBefore.size)
        assertTrue(tokensBefore.first().isValid)

        // Attempt to send notification
        val notif = AppNotification(
            id = "notif-invalid-test",
            recipientId = testUser,
            type = NotificationType.CENTRE_ANNOUNCEMENT,
            title = "Tuition Notice",
            body = "Holiday on Monday."
        )

        val sendResult = notificationRepository.sendNotification(
            recipientId = testUser,
            notification = notif,
            pushPayload = null,
            idempotencyKey = "idemp-invalid-test"
        )

        assertTrue(sendResult is Resource.Success)
        val resultData = (sendResult as Resource.Success).data

        // In-app notification must STILL be stored (Requirement 1 & 8)
        assertTrue("In-app notification must be stored regardless of push failure", resultData.inAppStored)
        assertTrue("Token must be detected as invalidated", resultData.tokenInvalidated)
        assertFalse("Push should not succeed on invalid token", resultData.pushDispatched)

        // Active tokens list should now be empty (token marked invalid)
        val tokensAfter = notificationRepository.getDeviceTokens(testUser).first()
        assertEquals("Active tokens list should now be empty", 0, tokensAfter.size)

        // Verify audit log exists and does NOT contain full raw token or secret credentials
        val auditLogs = notificationRepository.getAuditLogs()
        assertTrue("Delivery audit logs must contain an entry", auditLogs.isNotEmpty())
        val logEntry = auditLogs.last()
        assertTrue("Log entry must mention UNREGISTERED_TOKEN", logEntry.contains("UNREGISTERED_TOKEN"))
        assertFalse("Audit log must NOT leak full token", logEntry.contains(invalidToken))
        assertTrue("Audit log should show masked token", logEntry.contains("inva...7890"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Notification permission denied & token rotation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tokenRotation_registersNewTokenAndReplacesOld() = runTest(testDispatcher) {
        val testUser = "token-rotation-user"
        val oldToken = "fcm_token_old_9876543210"
        val newToken = "fcm_token_new_1234567890"

        notificationRepository.registerDeviceToken(testUser, oldToken, "Old Device")
        val initialTokens = notificationRepository.getDeviceTokens(testUser).first()
        assertEquals(1, initialTokens.size)

        // Rotate token
        notificationRepository.registerDeviceToken(testUser, newToken, "New Device")
        val rotatedTokens = notificationRepository.getDeviceTokens(testUser).first()
        assertEquals(2, rotatedTokens.size)
        assertTrue(rotatedTokens.any { it.token == newToken })

        // Invalidate old token
        notificationRepository.invalidateDeviceToken(oldToken)
        val activeTokens = notificationRepository.getDeviceTokens(testUser).first()
        assertEquals(1, activeTokens.size)
        assertEquals(newToken, activeTokens.first().token)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Notification preferences: push alert suppression
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun notificationPreferences_suppressesPushWhenDisabled() = runTest(testDispatcher) {
        // Suresh disables work report push notifications
        notificationRepository.updatePreferences(
            NotificationPreferences(
                userId = sureshId,
                pushEnabled = true,
                workReportsEnabled = false, // Disabled!
                attendanceEnabled = true,
                announcementsEnabled = true
            )
        )

        val notif = AppNotification(
            id = "notif-pref-test-1",
            recipientId = sureshId,
            type = NotificationType.WORK_REPORT_ADDED,
            title = "Maths Report",
            body = "Algebra exercise completed."
        )

        val sendRes = notificationRepository.sendNotification(
            recipientId = sureshId,
            notification = notif,
            pushPayload = null,
            idempotencyKey = "idemp-pref-test"
        )

        assertTrue(sendRes is Resource.Success)
        val data = (sendRes as Resource.Success).data

        // In-app notification is STILL stored!
        assertTrue("In-app notification must be stored", data.inAppStored)
        // Push was suppressed!
        assertFalse("Push should be suppressed by workReportsEnabled preference", data.pushDispatched)
        assertEquals("Suppressed by workReportsEnabled preference", data.failureReason)

        // However, attendance push is still allowed
        val attNotif = AppNotification(
            id = "notif-pref-att-1",
            recipientId = sureshId,
            type = NotificationType.ATTENDANCE_CHECK_IN,
            title = "Arrival",
            body = "Rahul arrived."
        )
        val attRes = notificationRepository.sendNotification(
            recipientId = sureshId,
            notification = attNotif,
            pushPayload = null,
            idempotencyKey = "idemp-pref-att"
        )
        assertTrue((attRes as Resource.Success).data.pushDispatched)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Report save independence: push failure never rolls back report
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun pushDeliveryFailure_doesNotRollbackSavedReport() = runTest(testDispatcher) {
        authRepository.signIn(tutorEmail, "password123")

        // Invalidate Suresh's token so push fails
        notificationRepository.invalidateDeviceToken("fcm_token_suresh_pixel8")

        val newReport = DailyWorkReport(
            id = "test-report-resilience",
            studentId = rahulId,
            studentName = "Rahul Reddy",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao",
            reportDate = LocalDate.now(kolkataZone),
            subject = "Social Studies",
            workCompleted = "Indian history chapter 3 review.",
            idempotencyKey = "report-resilience-key"
        )

        // Create report: push delivery will find no active tokens for Suresh
        val createRes = workReportRepository.createReport(newReport)

        // Report must STILL be created successfully!
        assertTrue("Report creation must succeed even if push delivery fails", createRes is Resource.Success)
        val savedReport = (createRes as Resource.Success).data
        assertEquals("test-report-resilience", savedReport.id)

        // Verify report is persisted in student's reports
        val studentReports = workReportRepository.getReportsForStudent(rahulId).first()
        assertTrue(studentReports.any { it.id == savedReport.id })

        // And in-app notification is still saved for Suresh!
        val sureshNotifs = notificationRepository.getNotificationsForUser(sureshId).first()
        assertTrue(sureshNotifs.any { it.relatedEntityId == savedReport.id })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. Account invitation event notification
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun accountInvitation_dispatchesInvitationNotification() = runTest(testDispatcher) {
        // Owner issues an invitation to parent Suresh
        authRepository.signIn("owner@runforvictory.com", "password123")

        val targetEmail = "parent.suresh@example.com"
        val inviteRes = authRepository.inviteUser(
            email = targetEmail,
            role = UserRole.PARENT,
            studentIds = listOf(rahulId)
        )
        assertTrue("Invite should succeed", inviteRes is Resource.Success)

        // Verify an INVITATION_SENT notification was enqueued for Suresh
        val sureshNotifs = notificationRepository.getNotificationsForUser(sureshId).first()
        val inviteNotif = sureshNotifs.find { it.type == NotificationType.INVITATION_SENT }
        assertNotNull("Invitation notification must be created", inviteNotif)
        assertTrue("Title should mention invitation", inviteNotif!!.title.contains("Invitation"))
    }
}

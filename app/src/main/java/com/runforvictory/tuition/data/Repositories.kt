package com.runforvictory.tuition.data

import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.domain.DailyWorkReport
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.StudentAttendanceRecord
import com.runforvictory.tuition.domain.StudentHistory
import com.runforvictory.tuition.domain.StudentStatus
import com.runforvictory.tuition.domain.TuitionCenterConfig
import com.runforvictory.tuition.domain.TutorAttendanceRecord
import com.runforvictory.tuition.domain.UserProfile
import com.runforvictory.tuition.domain.UserRole
import com.runforvictory.tuition.domain.AppNotification
import com.runforvictory.tuition.domain.AttendanceDailySummary
import com.runforvictory.tuition.domain.AttendanceMonthlySummary
import com.runforvictory.tuition.domain.DeviceToken
import com.runforvictory.tuition.domain.GpsVerificationStatus
import com.runforvictory.tuition.domain.NotificationDeliveryResult
import com.runforvictory.tuition.domain.NotificationPreferences
import com.runforvictory.tuition.domain.ParentWeeklySummary
import com.runforvictory.tuition.domain.PushPayload
import com.runforvictory.tuition.domain.StudentAttendanceStatus
import com.runforvictory.tuition.domain.TutorMonthlySummary
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZonedDateTime

interface AuthRepository {
    suspend fun signIn(email: String, password: String): Resource<UserProfile>
    suspend fun signOut(): Resource<Unit>
    suspend fun restoreSession(): Resource<UserProfile?>
    suspend fun getCurrentUser(): UserProfile?
    suspend fun sendPasswordReset(email: String): Resource<Unit>
    suspend fun inviteUser(email: String, role: UserRole, studentIds: List<String> = emptyList()): Resource<String>
    suspend fun validateInvitationToken(token: String): Resource<Boolean>
    suspend fun acceptInvitation(token: String, fullName: String, phone: String?): Resource<UserProfile>
    suspend fun deactivateUser(userId: String, reason: String): Resource<Unit>
}

interface StudentRepository {
    fun getStudents(
        query: String = "",
        gradeFilter: String? = null,
        statusFilter: StudentStatus? = null
    ): Flow<List<Student>>

    fun getActiveStudents(): Flow<List<Student>>
    fun getArchivedStudents(): Flow<List<Student>>
    suspend fun getStudentById(id: String): Resource<Student>
    suspend fun createStudent(student: Student): Resource<Student>
    suspend fun updateStudent(student: Student): Resource<Student>
    suspend fun archiveStudent(id: String, reason: String): Resource<Unit>
    suspend fun restoreStudent(id: String): Resource<Unit>
    suspend fun getStudentHistory(studentId: String): Resource<StudentHistory>
    suspend fun permanentDeleteStudent(id: String, confirmationPhrase: String, justification: String): Resource<Unit>
    fun getLinkedStudentsForParent(parentId: String): Flow<List<Student>>
}

interface AttendanceRepository {
    suspend fun recordStudentCheckIn(
        studentId: String,
        status: StudentAttendanceStatus = StudentAttendanceStatus.PRESENT,
        notes: String? = null,
        idempotencyKey: String = java.util.UUID.randomUUID().toString()
    ): Resource<StudentAttendanceRecord>

    suspend fun recordStudentCheckOut(
        studentId: String,
        idempotencyKey: String = java.util.UUID.randomUUID().toString()
    ): Resource<StudentAttendanceRecord>

    fun getStudentAttendanceForDate(date: LocalDate): Flow<List<StudentAttendanceRecord>>
    fun getAttendanceForStudent(studentId: String): Flow<List<StudentAttendanceRecord>>
    suspend fun getDailySummary(date: LocalDate): Resource<AttendanceDailySummary>
    suspend fun getMonthlySummary(year: Int, month: Int): Resource<AttendanceMonthlySummary>
    suspend fun ownerCorrectStudentAttendance(
        attendanceId: String,
        newCheckIn: java.time.ZonedDateTime,
        newCheckOut: java.time.ZonedDateTime?,
        newStatus: StudentAttendanceStatus,
        notes: String?,
        reason: String
    ): Resource<StudentAttendanceRecord>

    suspend fun recordTutorCheckIn(
        lat: Double,
        lng: Double,
        accuracy: Float,
        readingTimestampMillis: Long? = null,
        isMock: Boolean = false
    ): Resource<TutorAttendanceRecord>

    suspend fun recordTutorCheckOut(
        lat: Double,
        lng: Double,
        accuracy: Float,
        readingTimestampMillis: Long? = null,
        isMock: Boolean = false
    ): Resource<TutorAttendanceRecord>

    suspend fun getActiveTutorSession(tutorId: String): Resource<TutorAttendanceRecord?>
    suspend fun getTutorMonthlySummary(tutorId: String, year: Int, month: Int): Resource<TutorMonthlySummary>
    fun getTutorAttendanceForMonth(tutorId: String, year: Int, month: Int): Flow<List<TutorAttendanceRecord>>
    fun getAllTutorAttendanceForDate(date: LocalDate): Flow<List<TutorAttendanceRecord>>
    suspend fun ownerOverrideTutorAttendance(
        attendanceId: String,
        overrideStatus: GpsVerificationStatus,
        newCheckIn: ZonedDateTime?,
        newCheckOut: ZonedDateTime?,
        reason: String
    ): Resource<TutorAttendanceRecord>

    // Parent-authorized attendance queries (server-enforced parent_children isolation)
    fun getAttendanceForParent(parentId: String, studentId: String): Flow<List<StudentAttendanceRecord>>
    suspend fun getWeeklySummaryForParent(parentId: String, studentId: String): Resource<ParentWeeklySummary>
    suspend fun getMonthlySummaryForParent(parentId: String, studentId: String, year: Int, month: Int): Resource<AttendanceMonthlySummary>
}

interface WorkReportRepository {
    suspend fun createReport(report: DailyWorkReport): Resource<DailyWorkReport>
    suspend fun editReport(
        reportId: String,
        subject: String,
        workCompleted: String,
        homework: String?,
        pendingWork: String?,
        progressNotes: String?,
        reason: String
    ): Resource<DailyWorkReport>
    fun getReportsForStudent(studentId: String): Flow<List<DailyWorkReport>>
    fun getReportsByTutor(tutorId: String): Flow<List<DailyWorkReport>>
    fun getAllReportsForDate(date: LocalDate): Flow<List<DailyWorkReport>>
    fun getReportsForParent(parentId: String, studentId: String? = null): Flow<List<DailyWorkReport>>
    suspend fun getReportById(reportId: String): Resource<DailyWorkReport>
}

interface NotificationRepository {
    fun getNotificationsForUser(userId: String): Flow<List<AppNotification>>
    fun getUnreadCount(userId: String): Flow<Int>
    suspend fun markAsRead(notificationId: String): Resource<Unit>
    suspend fun markAllAsRead(userId: String): Resource<Unit>
    suspend fun refreshNotifications(userId: String): Resource<Unit>
    suspend fun getPreferences(userId: String): Resource<NotificationPreferences>
    suspend fun updatePreferences(preferences: NotificationPreferences): Resource<Unit>
    suspend fun registerDeviceToken(userId: String, token: String, deviceInfo: String? = null): Resource<Unit>
    fun getDeviceTokens(userId: String): Flow<List<DeviceToken>>
    suspend fun invalidateDeviceToken(token: String): Resource<Unit>
    suspend fun sendNotification(
        recipientId: String,
        notification: AppNotification,
        pushPayload: PushPayload? = null,
        idempotencyKey: String? = null
    ): Resource<NotificationDeliveryResult>
}

interface TuitionCenterRepository {
    suspend fun getConfig(): Resource<TuitionCenterConfig>
    suspend fun updateConfig(config: TuitionCenterConfig): Resource<TuitionCenterConfig>
}

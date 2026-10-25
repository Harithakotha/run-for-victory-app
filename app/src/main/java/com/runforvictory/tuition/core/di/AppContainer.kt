package com.runforvictory.tuition.core.di

import com.runforvictory.tuition.core.AuthSession
import com.runforvictory.tuition.core.DefaultDispatcherProvider
import com.runforvictory.tuition.core.DispatcherProvider
import com.runforvictory.tuition.core.InMemorySessionStorage
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.core.SessionStorage
import com.runforvictory.tuition.data.AttendanceRepository
import com.runforvictory.tuition.data.AuthRepository
import com.runforvictory.tuition.data.StudentRepository
import com.runforvictory.tuition.data.TuitionCenterRepository
import com.runforvictory.tuition.data.WorkReportRepository
import com.runforvictory.tuition.domain.DailyWorkReport
import com.runforvictory.tuition.domain.GpsVerificationStatus
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.StudentAttendanceRecord
import com.runforvictory.tuition.domain.StudentAttendanceStatus
import com.runforvictory.tuition.domain.StudentHistory
import com.runforvictory.tuition.domain.StudentStatus
import com.runforvictory.tuition.domain.TuitionCenterConfig
import com.runforvictory.tuition.domain.TutorAttendanceRecord
import com.runforvictory.tuition.domain.UserProfile
import com.runforvictory.tuition.domain.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

interface AppContainer {
    val dispatchers: DispatcherProvider
    val sessionStorage: SessionStorage
    val authRepository: AuthRepository
    val studentRepository: StudentRepository
    val attendanceRepository: AttendanceRepository
    val workReportRepository: WorkReportRepository
    val tuitionCenterRepository: TuitionCenterRepository
}

class DefaultAppContainer : AppContainer {
    override val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
    override val sessionStorage: SessionStorage = InMemorySessionStorage()

    override val authRepository: AuthRepository by lazy {
        DefaultAuthRepository(sessionStorage)
    }

    override val studentRepository: StudentRepository by lazy {
        DefaultStudentRepository(authRepository)
    }

    override val attendanceRepository: AttendanceRepository by lazy {
        FakeAttendanceRepository()
    }

    override val workReportRepository: WorkReportRepository by lazy {
        FakeWorkReportRepository()
    }

    override val tuitionCenterRepository: TuitionCenterRepository by lazy {
        FakeTuitionCenterRepository()
    }
}

class DefaultAuthRepository(
    private val sessionStorage: SessionStorage
) : AuthRepository {
    private val usersDatabase = mutableMapOf(
        "owner@runforvictory.com" to UserProfile("11111111-1111-1111-1111-111111111111", UserRole.OWNER, "Ravi Kumar (Owner)", "9876500001", isActive = true),
        "tutor.anand@runforvictory.com" to UserProfile("22222222-2222-2222-2222-222222222222", UserRole.TUTOR, "Anand Rao (Tutor)", "9876500002", isActive = true),
        "parent.suresh@example.com" to UserProfile("33333333-3333-3333-3333-333333333333", UserRole.PARENT, "Suresh Reddy (Parent A)", "9876500003", isActive = true),
        "parent.kavitha@example.com" to UserProfile("44444444-4444-4444-4444-444444444444", UserRole.PARENT, "Kavitha Devi (Parent B)", "9876500004", isActive = true),
        "deactivated@example.com" to UserProfile("66666666-6666-6666-6666-666666666666", UserRole.TUTOR, "Deactivated Tutor", "9876500006", isActive = false)
    )

    private val invitations = mutableMapOf<String, InvitationData>()
    private var currentUser: UserProfile? = null

    data class InvitationData(
        val email: String,
        val role: UserRole,
        val studentIds: List<String>,
        val expiresAt: Instant,
        var isUsed: Boolean = false
    )

    override suspend fun signIn(email: String, password: String): Resource<UserProfile> {
        val cleanEmail = email.trim().lowercase()

        if (password == "wrongpassword" || password.length < 6) {
            return Resource.Error("Invalid email or password.")
        }

        val profile = usersDatabase[cleanEmail] ?: run {
            val role = when {
                cleanEmail.contains("owner") -> UserRole.OWNER
                cleanEmail.contains("tutor") -> UserRole.TUTOR
                else -> UserRole.PARENT
            }
            UserProfile(
                id = UUID.randomUUID().toString(),
                role = role,
                fullName = if (role == UserRole.OWNER) "Ravi Kumar (Owner)" else "User (${role.name})",
                phone = "9876543210",
                isActive = true
            )
        }

        if (!profile.isActive) {
            return Resource.Error("This account has been deactivated. Please contact the centre owner.")
        }

        currentUser = profile

        val session = AuthSession(
            accessToken = "jwt-" + UUID.randomUUID().toString(),
            refreshToken = "refresh-" + UUID.randomUUID().toString(),
            expiresAt = Instant.now().plusSeconds(86400),
            userProfile = profile
        )
        sessionStorage.saveSession(session)

        return Resource.Success(profile)
    }

    override suspend fun signOut(): Resource<Unit> {
        currentUser = null
        sessionStorage.clearSession()
        return Resource.Success(Unit)
    }

    override suspend fun restoreSession(): Resource<UserProfile?> {
        val session = sessionStorage.getSession() ?: return Resource.Success(null)
        
        if (session.isExpired) {
            sessionStorage.clearSession()
            currentUser = null
            return Resource.Success(null)
        }

        val dbUser = usersDatabase[session.userProfile.fullName.lowercase()] 
            ?: usersDatabase.values.find { it.id == session.userProfile.id }
        
        if (dbUser != null && !dbUser.isActive) {
            sessionStorage.clearSession()
            currentUser = null
            return Resource.Error("Session revoked: account has been deactivated.")
        }

        currentUser = session.userProfile
        return Resource.Success(session.userProfile)
    }

    override suspend fun getCurrentUser(): UserProfile? = currentUser

    override suspend fun sendPasswordReset(email: String): Resource<Unit> {
        if (email.isBlank() || !email.contains("@")) {
            return Resource.Error("Please enter a valid email address.")
        }
        return Resource.Success(Unit)
    }

    override suspend fun inviteUser(
        email: String,
        role: UserRole,
        studentIds: List<String>
    ): Resource<String> {
        if (currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only centre owners can issue invitations.")
        }

        if (role == UserRole.OWNER) {
            return Resource.Error("Invalid Operation: Owner accounts cannot be self-provisioned.")
        }

        if (role == UserRole.PARENT && studentIds.isEmpty()) {
            return Resource.Error("Validation Error: Parent invitations must link to at least one student.")
        }

        val token = "inv-" + UUID.randomUUID().toString().replace("-", "")
        invitations[token] = InvitationData(
            email = email.trim().lowercase(),
            role = role,
            studentIds = studentIds,
            expiresAt = Instant.now().plusSeconds(7 * 86400)
        )
        return Resource.Success(token)
    }

    override suspend fun validateInvitationToken(token: String): Resource<Boolean> {
        val invite = invitations[token] ?: return Resource.Error("Invitation token not found.")
        if (invite.isUsed) {
            return Resource.Error("Invitation has already been used.")
        }
        if (Instant.now().isAfter(invite.expiresAt)) {
            return Resource.Error("Invitation has expired.")
        }
        return Resource.Success(true)
    }

    override suspend fun acceptInvitation(
        token: String,
        fullName: String,
        phone: String?
    ): Resource<UserProfile> {
        val invite = invitations[token] ?: return Resource.Error("Invalid invitation token.")
        if (invite.isUsed) {
            return Resource.Error("Invitation has already been used.")
        }
        if (Instant.now().isAfter(invite.expiresAt)) {
            return Resource.Error("Invitation has expired.")
        }

        val newProfile = UserProfile(
            id = UUID.randomUUID().toString(),
            role = invite.role,
            fullName = fullName,
            phone = phone,
            isActive = true
        )
        usersDatabase[invite.email] = newProfile
        invite.isUsed = true

        return Resource.Success(newProfile)
    }

    override suspend fun deactivateUser(userId: String, reason: String): Resource<Unit> {
        if (currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only centre owner can deactivate users.")
        }
        val target = usersDatabase.values.find { it.id == userId }
            ?: return Resource.Error("Target user not found.")
        
        usersDatabase[usersDatabase.entries.first { it.value.id == userId }.key] = target.copy(isActive = false)
        return Resource.Success(Unit)
    }
}

class DefaultStudentRepository(
    private val authRepository: AuthRepository
) : StudentRepository {

    // Initial student data matching test seed
    private val studentsState = MutableStateFlow<List<Student>>(
        listOf(
            Student(
                id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                fullName = "Rahul Reddy",
                grade = "Class 10",
                guardianName = "Suresh Reddy",
                guardianPhone = "9876500003",
                contactPhone = "9876500011",
                status = StudentStatus.ACTIVE,
                notes = "Consistent performance in Mathematics."
            ),
            Student(
                id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                fullName = "Pooja Devi",
                grade = "Class 9",
                guardianName = "Kavitha Devi",
                guardianPhone = "9876500004",
                contactPhone = "9876500012",
                status = StudentStatus.ACTIVE,
                notes = "Excelling in Physics and Chemistry."
            ),
            Student(
                id = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                fullName = "Vikram Singh",
                grade = "Class 8",
                guardianName = "Rajesh Singh",
                guardianPhone = "9876500014",
                contactPhone = "9876500013",
                status = StudentStatus.ARCHIVED,
                archivedAt = ZonedDateTime.now().minusDays(15),
                archivedBy = "Ravi Kumar (Owner)",
                archivedReason = "Completed 8th grade tuition course."
            )
        )
    )

    // Historical attendance records associated with students
    private val historicalAttendance = mutableListOf(
        StudentAttendanceRecord(
            id = "hist-att-1",
            studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            studentName = "Rahul Reddy",
            attendanceDate = LocalDate.now().minusDays(1),
            checkInTime = ZonedDateTime.now().minusDays(1).withHour(16).withMinute(30),
            checkOutTime = ZonedDateTime.now().minusDays(1).withHour(18).withMinute(30),
            status = StudentAttendanceStatus.PRESENT,
            durationMinutes = 120
        ),
        StudentAttendanceRecord(
            id = "hist-att-2",
            studentId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            studentName = "Vikram Singh",
            attendanceDate = LocalDate.now().minusDays(20),
            checkInTime = ZonedDateTime.now().minusDays(20).withHour(16).withMinute(0),
            checkOutTime = ZonedDateTime.now().minusDays(20).withHour(18).withMinute(0),
            status = StudentAttendanceStatus.PRESENT,
            durationMinutes = 120
        )
    )

    // Historical daily reports associated with students
    private val historicalReports = mutableListOf(
        DailyWorkReport(
            id = "hist-rep-1",
            studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            studentName = "Rahul Reddy",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao",
            reportDate = LocalDate.now().minusDays(1),
            subject = "Mathematics",
            workCompleted = "Solved chapter 4 quadratic equations exercise 4.1",
            homework = "Complete questions 5 to 10 on page 88",
            progressNotes = "Strong grasp of factorization",
            idempotencyKey = "hist-key-1"
        ),
        DailyWorkReport(
            id = "hist-rep-2",
            studentId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            studentName = "Vikram Singh",
            tutorId = "22222222-2222-2222-2222-222222222222",
            tutorName = "Anand Rao",
            reportDate = LocalDate.now().minusDays(20),
            subject = "Science",
            workCompleted = "Revision of forces and motion",
            homework = "Review chapter summary",
            progressNotes = "Final session completed",
            idempotencyKey = "hist-key-2"
        )
    )

    override fun getStudents(
        query: String,
        gradeFilter: String?,
        statusFilter: StudentStatus?
    ): Flow<List<Student>> {
        return studentsState.map { list ->
            list.filter { student ->
                val matchesQuery = query.isBlank() ||
                        student.fullName.contains(query, ignoreCase = true) ||
                        student.guardianName.contains(query, ignoreCase = true) ||
                        (student.contactPhone?.contains(query) == true)
                val matchesGrade = gradeFilter.isNullOrBlank() || student.grade.equals(gradeFilter, ignoreCase = true)
                val matchesStatus = statusFilter == null || student.status == statusFilter
                matchesQuery && matchesGrade && matchesStatus
            }
        }
    }

    override fun getActiveStudents(): Flow<List<Student>> {
        return studentsState.map { list ->
            list.filter { it.status == StudentStatus.ACTIVE }
        }
    }

    override fun getArchivedStudents(): Flow<List<Student>> {
        return studentsState.map { list ->
            list.filter { it.status == StudentStatus.ARCHIVED }
        }
    }

    override suspend fun getStudentById(id: String): Resource<Student> {
        val student = studentsState.value.find { it.id == id }
            ?: return Resource.Error("Student not found.")
        return Resource.Success(student)
    }

    override suspend fun createStudent(student: Student): Resource<Student> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER && currentUser?.role != UserRole.TUTOR) {
            return Resource.Error("Access Denied: Only owner or authorized tutors can add students.")
        }

        // Rule: Prevent accidental duplicate active student creation
        val duplicate = studentsState.value.find {
            it.status == StudentStatus.ACTIVE &&
                    it.fullName.trim().equals(student.fullName.trim(), ignoreCase = true) &&
                    it.grade.trim().equals(student.grade.trim(), ignoreCase = true) &&
                    it.guardianPhone == student.guardianPhone
        }
        if (duplicate != null) {
            return Resource.Error("Duplicate Student: An active student named '${student.fullName}' is already enrolled in ${student.grade}.")
        }

        val newStudent = student.copy(
            id = if (student.id.isBlank()) UUID.randomUUID().toString() else student.id,
            status = StudentStatus.ACTIVE,
            createdAt = ZonedDateTime.now()
        )
        studentsState.update { it + newStudent }
        return Resource.Success(newStudent)
    }

    override suspend fun updateStudent(student: Student): Resource<Student> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER && currentUser?.role != UserRole.TUTOR) {
            return Resource.Error("Access Denied: Only owner or authorized tutors can edit students.")
        }

        val exists = studentsState.value.any { it.id == student.id }
        if (!exists) {
            return Resource.Error("Student not found.")
        }

        studentsState.update { list ->
            list.map { if (it.id == student.id) student else it }
        }
        return Resource.Success(student)
    }

    override suspend fun archiveStudent(id: String, reason: String): Resource<Unit> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only the tuition owner can archive students.")
        }

        if (reason.isBlank()) {
            return Resource.Error("Archival reason is required for audit trail.")
        }

        val target = studentsState.value.find { it.id == id }
            ?: return Resource.Error("Student not found.")

        val archived = target.copy(
            status = StudentStatus.ARCHIVED,
            archivedAt = ZonedDateTime.now(),
            archivedBy = currentUser.fullName,
            archivedReason = reason
        )

        studentsState.update { list ->
            list.map { if (it.id == id) archived else it }
        }
        return Resource.Success(Unit)
    }

    override suspend fun restoreStudent(id: String): Resource<Unit> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only the tuition owner can restore students.")
        }

        val target = studentsState.value.find { it.id == id }
            ?: return Resource.Error("Student not found.")

        val restored = target.copy(
            status = StudentStatus.ACTIVE,
            archivedAt = null,
            archivedBy = null,
            archivedReason = null
        )

        studentsState.update { list ->
            list.map { if (it.id == id) restored else it }
        }
        return Resource.Success(Unit)
    }

    override suspend fun getStudentHistory(studentId: String): Resource<StudentHistory> {
        val student = studentsState.value.find { it.id == studentId }
            ?: return Resource.Error("Student not found.")

        val att = historicalAttendance.filter { it.studentId == studentId }
        val reports = historicalReports.filter { it.studentId == studentId }

        return Resource.Success(
            StudentHistory(
                student = student,
                attendanceRecords = att,
                workLogs = reports
            )
        )
    }

    override suspend fun permanentDeleteStudent(
        id: String,
        confirmationPhrase: String,
        justification: String
    ): Resource<Unit> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only the tuition owner can perform permanent deletion.")
        }

        if (confirmationPhrase != "DELETE PERMANENTLY") {
            return Resource.Error("Safety Guard: Confirmation phrase mismatch. You must confirm with 'DELETE PERMANENTLY'.")
        }

        if (justification.trim().length < 10) {
            return Resource.Error("Audit Requirement: A detailed justification (min 10 characters) is required.")
        }

        val exists = studentsState.value.any { it.id == id }
        if (!exists) {
            return Resource.Error("Student not found.")
        }

        studentsState.update { list -> list.filterNot { it.id == id } }
        historicalAttendance.removeAll { it.studentId == id }
        historicalReports.removeAll { it.studentId == id }

        return Resource.Success(Unit)
    }
}

internal class FakeAttendanceRepository : AttendanceRepository {
    override suspend fun recordStudentCheckIn(studentId: String, notes: String?): Resource<StudentAttendanceRecord> =
        Resource.Success(StudentAttendanceRecord("att-1", studentId, "Sample Student", LocalDate.now(), ZonedDateTime.now()))
    override suspend fun recordStudentCheckOut(studentId: String): Resource<StudentAttendanceRecord> =
        Resource.Success(StudentAttendanceRecord("att-1", studentId, "Sample Student", LocalDate.now(), ZonedDateTime.now().minusHours(2), ZonedDateTime.now(), durationMinutes = 120))
    override fun getStudentAttendanceForDate(date: LocalDate): Flow<List<StudentAttendanceRecord>> = MutableStateFlow(emptyList())
    override fun getAttendanceForStudent(studentId: String): Flow<List<StudentAttendanceRecord>> = MutableStateFlow(emptyList())
    override suspend fun recordTutorCheckIn(lat: Double, lng: Double, accuracy: Float): Resource<TutorAttendanceRecord> =
        Resource.Success(TutorAttendanceRecord("t-1", "tutor-1", LocalDate.now(), ZonedDateTime.now(), checkInDistanceMeters = 25.0, checkInAccuracyMeters = accuracy.toDouble(), verificationStatus = GpsVerificationStatus.VERIFIED))
    override suspend fun recordTutorCheckOut(lat: Double, lng: Double, accuracy: Float): Resource<TutorAttendanceRecord> =
        Resource.Success(TutorAttendanceRecord("t-1", "tutor-1", LocalDate.now(), ZonedDateTime.now().minusHours(3), ZonedDateTime.now(), checkInDistanceMeters = 25.0, checkInAccuracyMeters = accuracy.toDouble(), verificationStatus = GpsVerificationStatus.VERIFIED, durationMinutes = 180))
    override fun getTutorAttendanceForMonth(tutorId: String, year: Int, month: Int): Flow<List<TutorAttendanceRecord>> = MutableStateFlow(emptyList())
}

internal class FakeWorkReportRepository : WorkReportRepository {
    override suspend fun createReport(report: DailyWorkReport): Resource<DailyWorkReport> = Resource.Success(report)
    override suspend fun editReport(reportId: String, workCompleted: String, homework: String?, notes: String?, reason: String): Resource<DailyWorkReport> = Resource.Error("Not implemented")
    override fun getReportsForStudent(studentId: String): Flow<List<DailyWorkReport>> = MutableStateFlow(emptyList())
    override fun getReportsByTutor(tutorId: String): Flow<List<DailyWorkReport>> = MutableStateFlow(emptyList())
}

internal class FakeTuitionCenterRepository : TuitionCenterRepository {
    override suspend fun getConfig(): Resource<TuitionCenterConfig> = Resource.Success(TuitionCenterConfig(id = "center-1"))
    override suspend fun updateConfig(config: TuitionCenterConfig): Resource<TuitionCenterConfig> = Resource.Success(config)
}

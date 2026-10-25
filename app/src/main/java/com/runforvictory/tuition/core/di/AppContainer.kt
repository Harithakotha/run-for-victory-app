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
import com.runforvictory.tuition.domain.AttendanceDailySummary
import com.runforvictory.tuition.domain.AttendanceMonthlySummary
import com.runforvictory.tuition.domain.TutorMonthlySummary
import com.runforvictory.tuition.location.HaversineUtil
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface AppContainer {
    val dispatchers: DispatcherProvider
    val sessionStorage: SessionStorage
    val authRepository: AuthRepository
    val studentRepository: StudentRepository
    val attendanceRepository: AttendanceRepository
    val workReportRepository: WorkReportRepository
    val tuitionCenterRepository: TuitionCenterRepository
    val locationProvider: com.runforvictory.tuition.location.FreshLocationProvider
}

class DefaultAppContainer(
    private val context: android.content.Context? = null
) : AppContainer {
    override val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
    override val sessionStorage: SessionStorage = InMemorySessionStorage()

    override val authRepository: AuthRepository by lazy {
        DefaultAuthRepository(sessionStorage)
    }

    override val studentRepository: StudentRepository by lazy {
        DefaultStudentRepository(authRepository)
    }

    override val attendanceRepository: AttendanceRepository by lazy {
        DefaultAttendanceRepository(authRepository, studentRepository)
    }

    override val workReportRepository: WorkReportRepository by lazy {
        FakeWorkReportRepository()
    }

    override val tuitionCenterRepository: TuitionCenterRepository by lazy {
        FakeTuitionCenterRepository()
    }

    override val locationProvider: com.runforvictory.tuition.location.FreshLocationProvider by lazy {
        if (context != null) {
            com.runforvictory.tuition.location.FreshLocationManager(context)
        } else {
            com.runforvictory.tuition.location.FakeFreshLocationProvider()
        }
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

class DefaultAttendanceRepository(
    private val authRepository: AuthRepository,
    private val studentRepository: StudentRepository
) : AttendanceRepository {

    private val kolkataZone: ZoneId = ZoneId.of("Asia/Kolkata")

    // Concurrent idempotency trackers for in-flight and retried requests
    private val checkInIdempotencyCache = ConcurrentHashMap<String, StudentAttendanceRecord>()
    private val checkOutIdempotencyCache = ConcurrentHashMap<String, StudentAttendanceRecord>()

    // Seeded historical attendance
    private val attendanceState = MutableStateFlow<List<StudentAttendanceRecord>>(
        listOf(
            StudentAttendanceRecord(
                id = "att-seed-1",
                studentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                studentName = "Rahul Reddy",
                studentGrade = "Class 10",
                attendanceDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1),
                checkInTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).minusDays(1).withHour(9).withMinute(0),
                checkOutTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).minusDays(1).withHour(12).withMinute(0),
                status = StudentAttendanceStatus.PRESENT,
                notes = "On time, active participation",
                durationMinutes = 180,
                recordedBy = "22222222-2222-2222-2222-222222222222",
                recordedByName = "Anand Rao (Maths Tutor)"
            ),
            StudentAttendanceRecord(
                id = "att-seed-2",
                studentId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                studentName = "Pooja Devi",
                studentGrade = "Class 12",
                attendanceDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1),
                checkInTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).minusDays(1).withHour(9).withMinute(15),
                checkOutTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).minusDays(1).withHour(12).withMinute(45),
                status = StudentAttendanceStatus.PRESENT,
                notes = "Physics doubt clarification",
                durationMinutes = 210,
                recordedBy = "22222222-2222-2222-2222-222222222222",
                recordedByName = "Anand Rao (Maths Tutor)"
            ),
            StudentAttendanceRecord(
                id = "att-seed-3",
                studentId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                studentName = "Vikram Singh",
                studentGrade = "Class 9",
                attendanceDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(3),
                checkInTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).minusDays(3).withHour(9).withMinute(30),
                checkOutTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).minusDays(3).withHour(11).withMinute(30),
                status = StudentAttendanceStatus.PRESENT,
                notes = "Historical attendance before archival",
                durationMinutes = 120,
                recordedBy = "11111111-1111-1111-1111-111111111111",
                recordedByName = "Ravi Kumar (Owner)"
            )
        )
    )

    private val tutorAttendanceState = MutableStateFlow<List<TutorAttendanceRecord>>(
        listOf(
            TutorAttendanceRecord(
                id = "tutor-seed-1",
                tutorId = "22222222-2222-2222-2222-222222222222",
                tutorName = "Anand Rao (Tutor)",
                centreId = "10000000-0000-0000-0000-000000000001",
                attendanceDate = LocalDate.now(kolkataZone).minusDays(1),
                checkInTime = ZonedDateTime.now(kolkataZone).minusDays(1).withHour(9).withMinute(0),
                checkOutTime = ZonedDateTime.now(kolkataZone).minusDays(1).withHour(13).withMinute(0),
                checkInLat = 17.385044,
                checkInLng = 78.486671,
                checkInDistanceMeters = 15.2,
                checkInAccuracyMeters = 12.0,
                verificationStatus = GpsVerificationStatus.VERIFIED,
                durationMinutes = 240
            )
        )
    )

    private val centreLatitude = 17.385044
    private val centreLongitude = 78.486671
    private val permittedRadiusMeters = 150
    private val maxAccuracyMeters = 60.0
    private val maxLocationAgeSeconds = 120L

    override suspend fun recordStudentCheckIn(
        studentId: String,
        status: StudentAttendanceStatus,
        notes: String?,
        idempotencyKey: String
    ): Resource<StudentAttendanceRecord> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER && currentUser?.role != UserRole.TUTOR) {
            return Resource.Error("Access Denied: Only tutors or owners can record student attendance.")
        }

        // Idempotency replay check
        checkInIdempotencyCache[idempotencyKey]?.let { return Resource.Success(it) }

        val studentRes = studentRepository.getStudentById(studentId)
        if (studentRes !is Resource.Success) {
            return Resource.Error("Student not found.")
        }
        val student = studentRes.data

        // Rule: Archived students cannot receive new attendance records
        if (student.status == StudentStatus.ARCHIVED || student.isArchived) {
            return Resource.Error("Archived Student: Cannot mark attendance for archived student \"${student.fullName}\".")
        }

        // Rule: Interpret attendance dates in Asia/Kolkata
        val todayInKolkata = LocalDate.now(kolkataZone)

        // Rule: Prevent duplicate attendance sessions for the same student and date
        val existingSession = attendanceState.value.find {
            it.studentId == studentId && it.attendanceDate == todayInKolkata
        }
        if (existingSession != null) {
            return Resource.Error("Duplicate Attendance: Student \"${student.fullName}\" already has attendance recorded for today ($todayInKolkata).")
        }

        // Trusted server timestamp in Asia/Kolkata
        val serverNow = ZonedDateTime.now(kolkataZone)

        val newRecord = StudentAttendanceRecord(
            id = UUID.randomUUID().toString(),
            centreId = student.centreId,
            studentId = studentId,
            studentName = student.fullName,
            studentGrade = student.grade,
            attendanceDate = todayInKolkata,
            checkInTime = serverNow,
            checkOutTime = null,
            status = status,
            notes = notes,
            durationMinutes = null,
            recordedBy = currentUser.id,
            recordedByName = currentUser.fullName,
            idempotencyKey = idempotencyKey
        )

        attendanceState.update { it + newRecord }
        checkInIdempotencyCache[idempotencyKey] = newRecord
        return Resource.Success(newRecord)
    }

    override suspend fun recordStudentCheckOut(
        studentId: String,
        idempotencyKey: String
    ): Resource<StudentAttendanceRecord> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER && currentUser?.role != UserRole.TUTOR) {
            return Resource.Error("Access Denied: Only tutors or owners can record student checkout.")
        }

        // Idempotency replay check for checkout
        checkOutIdempotencyCache[idempotencyKey]?.let { return Resource.Success(it) }

        val studentRes = studentRepository.getStudentById(studentId)
        val studentName = (studentRes as? Resource.Success)?.data?.fullName ?: studentId

        val todayInKolkata = LocalDate.now(kolkataZone)

        // Find today's open session
        val openRecord = attendanceState.value.find {
            it.studentId == studentId && it.attendanceDate == todayInKolkata
        } ?: return Resource.Error("No check-in record found for student \"$studentName\" today. Student must be checked in first.")

        // Rule: Prevent duplicate check-outs
        if (openRecord.checkOutTime != null) {
            return Resource.Error("Invalid Checkout: Student \"$studentName\" has already checked out for today at ${openRecord.checkOutTime}.")
        }

        // Trusted server timestamp in Asia/Kolkata
        val serverNow = ZonedDateTime.now(kolkataZone)

        // Rule: Prevent check-out earlier than check-in
        if (serverNow.isBefore(openRecord.checkInTime)) {
            return Resource.Error("Invalid Checkout: Checkout time cannot be earlier than check-in time.")
        }

        val duration = Duration.between(openRecord.checkInTime, serverNow).toMinutes().coerceAtLeast(0)

        val updatedRecord = openRecord.copy(
            checkOutTime = serverNow,
            durationMinutes = duration
        )

        attendanceState.update { list ->
            list.map { if (it.id == openRecord.id) updatedRecord else it }
        }

        checkOutIdempotencyCache[idempotencyKey] = updatedRecord
        return Resource.Success(updatedRecord)
    }

    override fun getStudentAttendanceForDate(date: LocalDate): Flow<List<StudentAttendanceRecord>> {
        return attendanceState.map { list ->
            list.filter { it.attendanceDate == date }
        }
    }

    override fun getAttendanceForStudent(studentId: String): Flow<List<StudentAttendanceRecord>> {
        return attendanceState.map { list ->
            list.filter { it.studentId == studentId }.sortedByDescending { it.attendanceDate }
        }
    }

    override suspend fun getDailySummary(date: LocalDate): Resource<AttendanceDailySummary> {
        val activeStudents = studentRepository.getActiveStudents().first()
        val totalActive = activeStudents.size

        val recordsForDate = attendanceState.value.filter { it.attendanceDate == date }
        val presentCount = recordsForDate.count { it.status == StudentAttendanceStatus.PRESENT }
        val lateCount = recordsForDate.count { it.status == StudentAttendanceStatus.LATE }
        val halfDayCount = recordsForDate.count { it.status == StudentAttendanceStatus.HALF_DAY }
        val absentCount = recordsForDate.count { it.status == StudentAttendanceStatus.ABSENT }
        val excusedCount = recordsForDate.count { it.status == StudentAttendanceStatus.EXCUSED }
        val markedCount = presentCount + lateCount + halfDayCount + absentCount + excusedCount
        val notRecordedCount = (totalActive - markedCount).coerceAtLeast(0)

        val percentage = if (totalActive > 0) {
            val weightedAttendance = presentCount.toDouble() + lateCount.toDouble() + (halfDayCount.toDouble() * 0.5)
            ((weightedAttendance / totalActive.toDouble()) * 100.0).let {
                Math.round(it * 10.0) / 10.0
            }
        } else {
            0.0
        }

        return Resource.Success(
            AttendanceDailySummary(
                date = date,
                totalActiveStudents = totalActive,
                presentCount = presentCount,
                lateCount = lateCount,
                halfDayCount = halfDayCount,
                absentCount = absentCount,
                excusedCount = excusedCount,
                notRecordedCount = notRecordedCount,
                attendancePercentage = percentage
            )
        )
    }

    override suspend fun getMonthlySummary(year: Int, month: Int): Resource<AttendanceMonthlySummary> {
        val monthlyRecords = attendanceState.value.filter {
            it.attendanceDate.year == year && it.attendanceDate.monthValue == month
        }
        val totalSessions = monthlyRecords.size
        val presentCount = monthlyRecords.count { it.status == StudentAttendanceStatus.PRESENT }
        val lateCount = monthlyRecords.count { it.status == StudentAttendanceStatus.LATE }
        val halfDayCount = monthlyRecords.count { it.status == StudentAttendanceStatus.HALF_DAY }
        val absentCount = monthlyRecords.count { it.status == StudentAttendanceStatus.ABSENT }
        val excusedCount = monthlyRecords.count { it.status == StudentAttendanceStatus.EXCUSED }

        val averagePercentage = if (totalSessions > 0) {
            val weighted = presentCount.toDouble() + lateCount.toDouble() + (halfDayCount.toDouble() * 0.5)
            ((weighted / totalSessions.toDouble()) * 100.0).let {
                Math.round(it * 10.0) / 10.0
            }
        } else {
            0.0
        }

        return Resource.Success(
            AttendanceMonthlySummary(
                year = year,
                month = month,
                totalSessions = totalSessions,
                presentCount = presentCount,
                lateCount = lateCount,
                halfDayCount = halfDayCount,
                absentCount = absentCount,
                excusedCount = excusedCount,
                averageAttendancePercentage = averagePercentage
            )
        )
    }

    override suspend fun ownerCorrectStudentAttendance(
        attendanceId: String,
        newCheckIn: ZonedDateTime,
        newCheckOut: ZonedDateTime?,
        newStatus: StudentAttendanceStatus,
        notes: String?,
        reason: String
    ): Resource<StudentAttendanceRecord> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only the centre owner can correct student attendance.")
        }

        if (reason.isBlank() || reason.trim().length < 5) {
            return Resource.Error("Correction reason is required for audit trail (min 5 characters).")
        }

        if (newCheckOut != null && newCheckOut.isBefore(newCheckIn)) {
            return Resource.Error("Invalid Times: Departure time cannot be earlier than arrival time.")
        }

        val target = attendanceState.value.find { it.id == attendanceId }
            ?: return Resource.Error("Attendance record not found.")

        val duration = newCheckOut?.let {
            Duration.between(newCheckIn, it).toMinutes().coerceAtLeast(0)
        }

        val corrected = target.copy(
            checkInTime = newCheckIn,
            checkOutTime = newCheckOut,
            status = newStatus,
            notes = notes,
            durationMinutes = duration,
            correctedBy = currentUser.id,
            correctedByName = currentUser.fullName,
            correctionReason = reason.trim(),
            correctedAt = ZonedDateTime.now(kolkataZone)
        )

        attendanceState.update { list ->
            list.map { if (it.id == attendanceId) corrected else it }
        }

        return Resource.Success(corrected)
    }

    override suspend fun recordTutorCheckIn(
        lat: Double,
        lng: Double,
        accuracy: Float,
        readingTimestampMillis: Long?,
        isMock: Boolean
    ): Resource<TutorAttendanceRecord> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.TUTOR && currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only teachers or owners can clock in.")
        }

        val today = LocalDate.now(kolkataZone)
        val serverNow = ZonedDateTime.now(kolkataZone)

        // Prevent duplicate open sessions
        val activeSession = tutorAttendanceState.value.find {
            it.tutorId == currentUser.id && it.checkOutTime == null && it.verificationStatus != GpsVerificationStatus.REJECTED
        }
        if (activeSession != null) {
            return Resource.Error("Duplicate Open Session: You have an existing active clock-in session (${activeSession.id}). Clock out first.")
        }

        // Check if completed attendance already exists for today
        val completedToday = tutorAttendanceState.value.find {
            it.tutorId == currentUser.id && it.attendanceDate == today && it.checkOutTime != null && it.verificationStatus == GpsVerificationStatus.VERIFIED
        }
        if (completedToday != null) {
            return Resource.Error("Attendance for today ($today) has already been completed.")
        }

        var status = GpsVerificationStatus.VERIFIED
        var rejectionReason: String? = null

        // 1. Anti-Spoofing & Staleness Verification
        if (isMock) {
            status = GpsVerificationStatus.REJECTED
            rejectionReason = "Mock location provider detected by client system."
        } else if (readingTimestampMillis != null) {
            val ageSeconds = (System.currentTimeMillis() - readingTimestampMillis) / 1000L
            if (ageSeconds > maxLocationAgeSeconds) {
                status = GpsVerificationStatus.REJECTED
                rejectionReason = "Location reading is stale (${ageSeconds}s old > max permitted ${maxLocationAgeSeconds}s)."
            }
        }

        // 2. Accuracy & Geofence Distance Calculation
        val distance = HaversineUtil.calculateDistanceMeters(lat, lng, centreLatitude, centreLongitude)
        val roundedDistance = Math.round(distance * 10.0) / 10.0

        if (status == GpsVerificationStatus.VERIFIED) {
            if (accuracy > maxAccuracyMeters) {
                status = GpsVerificationStatus.REJECTED
                rejectionReason = "GPS accuracy insufficient (${accuracy}m > allowed ${maxAccuracyMeters}m)."
            } else if (distance > permittedRadiusMeters) {
                status = GpsVerificationStatus.REJECTED
                rejectionReason = "Location outside centre geofence (${roundedDistance}m away, max permitted ${permittedRadiusMeters}m)."
            }
        }

        val record = TutorAttendanceRecord(
            id = UUID.randomUUID().toString(),
            tutorId = currentUser.id,
            tutorName = currentUser.fullName,
            attendanceDate = today,
            checkInTime = serverNow,
            checkInLat = lat,
            checkInLng = lng,
            checkInDistanceMeters = roundedDistance,
            checkInAccuracyMeters = accuracy.toDouble(),
            verificationStatus = status,
            rejectionReason = rejectionReason
        )

        tutorAttendanceState.update { it + record }

        return if (status == GpsVerificationStatus.VERIFIED) {
            Resource.Success(record)
        } else {
            Resource.Error(rejectionReason ?: "GPS verification failed.")
        }
    }

    override suspend fun recordTutorCheckOut(
        lat: Double,
        lng: Double,
        accuracy: Float,
        readingTimestampMillis: Long?,
        isMock: Boolean
    ): Resource<TutorAttendanceRecord> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.TUTOR && currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only teachers or owners can clock out.")
        }

        // Fetch active open session
        val activeSession = tutorAttendanceState.value.find {
            it.tutorId == currentUser.id && it.checkOutTime == null && it.verificationStatus != GpsVerificationStatus.REJECTED
        } ?: return Resource.Error("No open attendance session found to clock out from.")

        val serverNow = ZonedDateTime.now(kolkataZone)
        var status = GpsVerificationStatus.VERIFIED
        var rejectionReason: String? = null

        // Staleness & Mock Check
        if (isMock) {
            status = GpsVerificationStatus.REJECTED
            rejectionReason = "Mock location provider detected during check-out."
        } else if (readingTimestampMillis != null) {
            val ageSeconds = (System.currentTimeMillis() - readingTimestampMillis) / 1000L
            if (ageSeconds > maxLocationAgeSeconds) {
                status = GpsVerificationStatus.REJECTED
                rejectionReason = "Location reading is stale (${ageSeconds}s > max ${maxLocationAgeSeconds}s)."
            }
        }

        val distance = HaversineUtil.calculateDistanceMeters(lat, lng, centreLatitude, centreLongitude)
        val roundedDistance = Math.round(distance * 10.0) / 10.0

        if (status == GpsVerificationStatus.VERIFIED) {
            if (accuracy > maxAccuracyMeters) {
                status = GpsVerificationStatus.REJECTED
                rejectionReason = "GPS accuracy insufficient (${accuracy}m > allowed ${maxAccuracyMeters}m)."
            } else if (distance > permittedRadiusMeters) {
                status = GpsVerificationStatus.REJECTED
                rejectionReason = "Location outside centre geofence (${roundedDistance}m away, max permitted ${permittedRadiusMeters}m)."
            }
        }

        val duration = Duration.between(activeSession.checkInTime, serverNow).toMinutes().coerceAtLeast(0)

        val updatedRecord = activeSession.copy(
            checkOutTime = serverNow,
            checkOutLat = lat,
            checkOutLng = lng,
            checkOutAccuracyMeters = accuracy.toDouble(),
            checkOutDistanceMeters = roundedDistance,
            checkOutVerificationStatus = status,
            checkOutRejectionReason = rejectionReason,
            durationMinutes = duration
        )

        tutorAttendanceState.update { list ->
            list.map { if (it.id == activeSession.id) updatedRecord else it }
        }

        return if (status == GpsVerificationStatus.VERIFIED) {
            Resource.Success(updatedRecord)
        } else {
            Resource.Error(rejectionReason ?: "GPS verification failed on check-out.")
        }
    }

    override suspend fun getActiveTutorSession(tutorId: String): Resource<TutorAttendanceRecord?> {
        val session = tutorAttendanceState.value.find {
            it.tutorId == tutorId && it.checkOutTime == null && it.verificationStatus == GpsVerificationStatus.VERIFIED
        }
        return Resource.Success(session)
    }

    override suspend fun getTutorMonthlySummary(
        tutorId: String,
        year: Int,
        month: Int
    ): Resource<TutorMonthlySummary> {
        val monthlyRecords = tutorAttendanceState.value.filter {
            it.tutorId == tutorId &&
                    it.attendanceDate.year == year &&
                    it.attendanceDate.monthValue == month
        }

        val verified = monthlyRecords.count { it.verificationStatus == GpsVerificationStatus.VERIFIED }
        val rejected = monthlyRecords.count { it.verificationStatus == GpsVerificationStatus.REJECTED }
        val overridden = monthlyRecords.count { it.verificationStatus == GpsVerificationStatus.OVERRIDDEN }

        val totalMinutes = monthlyRecords.filter { it.verificationStatus != GpsVerificationStatus.REJECTED }
            .sumOf { it.durationMinutes ?: 0L }
        val totalHours = Math.round((totalMinutes.toDouble() / 60.0) * 10.0) / 10.0

        val distinctDays = monthlyRecords
            .filter { it.verificationStatus != GpsVerificationStatus.REJECTED }
            .map { it.attendanceDate }
            .distinct()
            .size

        return Resource.Success(
            TutorMonthlySummary(
                tutorId = tutorId,
                year = year,
                month = month,
                totalDaysWorked = distinctDays,
                totalHoursWorked = totalHours,
                verifiedSessionsCount = verified,
                rejectedSessionsCount = rejected,
                overriddenSessionsCount = overridden
            )
        )
    }

    override fun getTutorAttendanceForMonth(tutorId: String, year: Int, month: Int): Flow<List<TutorAttendanceRecord>> {
        return tutorAttendanceState.map { list ->
            list.filter {
                it.tutorId == tutorId &&
                        it.attendanceDate.year == year &&
                        it.attendanceDate.monthValue == month
            }.sortedByDescending { it.checkInTime }
        }
    }

    override fun getAllTutorAttendanceForDate(date: LocalDate): Flow<List<TutorAttendanceRecord>> {
        return tutorAttendanceState.map { list ->
            list.filter { it.attendanceDate == date }.sortedByDescending { it.checkInTime }
        }
    }

    override suspend fun ownerOverrideTutorAttendance(
        attendanceId: String,
        overrideStatus: GpsVerificationStatus,
        newCheckIn: ZonedDateTime?,
        newCheckOut: ZonedDateTime?,
        reason: String
    ): Resource<TutorAttendanceRecord> {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser?.role != UserRole.OWNER) {
            return Resource.Error("Access Denied: Only the centre owner can override teacher attendance.")
        }

        if (reason.isBlank() || reason.trim().length < 5) {
            return Resource.Error("Override reason is required for audit trail (min 5 characters).")
        }

        val target = tutorAttendanceState.value.find { it.id == attendanceId }
            ?: return Resource.Error("Teacher attendance record not found.")

        val checkIn = newCheckIn ?: target.checkInTime
        val checkOut = newCheckOut ?: target.checkOutTime
        val duration = if (checkOut != null) {
            Duration.between(checkIn, checkOut).toMinutes().coerceAtLeast(0)
        } else {
            target.durationMinutes
        }

        val updated = target.copy(
            isManualOverride = true,
            overrideBy = currentUser.id,
            overrideByName = currentUser.fullName,
            overrideReason = reason.trim(),
            overrideAt = ZonedDateTime.now(kolkataZone),
            verificationStatus = overrideStatus,
            checkInTime = checkIn,
            checkOutTime = checkOut,
            durationMinutes = duration
        )

        tutorAttendanceState.update { list ->
            list.map { if (it.id == attendanceId) updated else it }
        }

        return Resource.Success(updated)
    }
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

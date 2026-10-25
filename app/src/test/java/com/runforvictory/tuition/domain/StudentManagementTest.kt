package com.runforvictory.tuition.domain

import com.runforvictory.tuition.core.InMemorySessionStorage
import com.runforvictory.tuition.core.Resource
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
import java.util.UUID

class StudentManagementTest {

    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var authRepository: DefaultAuthRepository
    private lateinit var studentRepository: DefaultStudentRepository

    @Before
    fun setUp() = runTest {
        sessionStorage = InMemorySessionStorage()
        authRepository = DefaultAuthRepository(sessionStorage)
        studentRepository = DefaultStudentRepository(authRepository)

        // Sign in as owner by default
        authRepository.signIn("owner@runforvictory.com", "validPassword123")
    }

    // 1. Create Student
    @Test
    fun createStudent_succeeds_andAddsToActiveDirectory() = runTest {
        val newStudent = Student(
            id = UUID.randomUUID().toString(),
            fullName = "Ananya Sharma",
            grade = "Class 10",
            guardianName = "Sunil Sharma",
            guardianPhone = "9876543210",
            contactPhone = "9876543211",
            notes = "Interested in advanced physics."
        )

        val result = studentRepository.createStudent(newStudent)
        assertTrue(result is Resource.Success)

        val activeStudents = studentRepository.getActiveStudents().first()
        assertTrue(activeStudents.any { it.fullName == "Ananya Sharma" })
    }

    // 2. Prevent Duplicate Active Student Creation
    @Test
    fun createDuplicateStudent_failsWithDuplicateError() = runTest {
        val student = Student(
            id = UUID.randomUUID().toString(),
            fullName = "Rahul Reddy", // Already exists in Class 10
            grade = "Class 10",
            guardianName = "Suresh Reddy",
            guardianPhone = "9876500003"
        )

        val result = studentRepository.createStudent(student)
        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message.contains("Duplicate Student"))
    }

    // 3. Edit Student
    @Test
    fun editStudent_updatesDetailsSuccessfully() = runTest {
        val existing = studentRepository.getStudentById("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        assertTrue(existing is Resource.Success)
        val student = (existing as Resource.Success).data

        val updated = student.copy(
            grade = "Class 10 - Advanced Batch",
            notes = "Promoted to specialized science section."
        )

        val editResult = studentRepository.updateStudent(updated)
        assertTrue(editResult is Resource.Success)

        val fetched = studentRepository.getStudentById("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        assertEquals("Class 10 - Advanced Batch", (fetched as Resource.Success).data.grade)
        assertEquals("Promoted to specialized science section.", fetched.data.notes)
    }

    // 4. Archive Student & Verify Author + Reason
    @Test
    fun archiveStudent_setsArchivedStatus_andCapturesReason() = runTest {
        val archiveResult = studentRepository.archiveStudent(
            id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            reason = "Family relocated to Bengaluru."
        )
        assertTrue(archiveResult is Resource.Success)

        val student = (studentRepository.getStudentById("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb") as Resource.Success).data
        assertEquals(StudentStatus.ARCHIVED, student.status)
        assertTrue(student.isArchived)
        assertEquals("Family relocated to Bengaluru.", student.archivedReason)
        assertEquals("Ravi Kumar (Owner)", student.archivedBy)
        assertNotNull(student.archivedAt)
    }

    // 5. Verify Archived Student Disappears from Active Attendance Lists
    @Test
    fun archivedStudent_disappearsFromActiveAttendanceList() = runTest {
        // Initial active students contains Pooja Devi
        val initialActive = studentRepository.getActiveStudents().first()
        assertTrue(initialActive.any { it.id == "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" })

        // Archive Pooja Devi
        studentRepository.archiveStudent("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "Course completed")

        // Act: Query active students for attendance register
        val updatedActive = studentRepository.getActiveStudents().first()

        // Assert: Excluded from active attendance list
        assertFalse(updatedActive.any { it.id == "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" })

        // Assert: Present in archived students query
        val archivedList = studentRepository.getArchivedStudents().first()
        assertTrue(archivedList.any { it.id == "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" })
    }

    // 6. Restore Archived Student
    @Test
    fun restoreStudent_reactivatesStudent_withoutDuplicateRecords() = runTest {
        // Vikram Singh is archived initially
        val initialArchived = studentRepository.getArchivedStudents().first()
        assertTrue(initialArchived.any { it.id == "cccccccc-cccc-cccc-cccc-cccccccccccc" })

        // Restore Vikram Singh
        val restoreResult = studentRepository.restoreStudent("cccccccc-cccc-cccc-cccc-cccccccccccc")
        assertTrue(restoreResult is Resource.Success)

        // Verify active status
        val student = (studentRepository.getStudentById("cccccccc-cccc-cccc-cccc-cccccccccccc") as Resource.Success).data
        assertEquals(StudentStatus.ACTIVE, student.status)
        assertFalse(student.isArchived)
        assertNull(student.archivedAt)
        assertNull(student.archivedReason)

        // Verify reappearance in active directory
        val activeList = studentRepository.getActiveStudents().first()
        assertTrue(activeList.any { it.id == "cccccccc-cccc-cccc-cccc-cccccccccccc" })

        // Assert no duplicate IDs exist
        val count = activeList.count { it.id == "cccccccc-cccc-cccc-cccc-cccccccccccc" }
        assertEquals(1, count)
    }

    // 7. Verify Historical Records (Attendance & Reports) Remain Intact After Archiving
    @Test
    fun archivedStudent_previousReportsAndAttendanceRemainAccessible() = runTest {
        // Vikram Singh has historical attendance and reports
        val historyResult = studentRepository.getStudentHistory("cccccccc-cccc-cccc-cccc-cccccccccccc")
        assertTrue(historyResult is Resource.Success)

        val history = (historyResult as Resource.Success).data
        assertEquals("Vikram Singh", history.student.fullName)
        assertTrue("Historical attendance must be preserved", history.attendanceRecords.isNotEmpty())
        assertTrue("Historical work logs must be preserved", history.workLogs.isNotEmpty())
        assertEquals("Science", history.workLogs.first().subject)
    }

    // 8. Safety Guard: Permanent Deletion Requires Specific Confirmation Phrase & Justification
    @Test
    fun permanentDeleteStudent_requiresExactConfirmationPhrase() = runTest {
        // Attempt with wrong phrase
        val wrongPhraseResult = studentRepository.permanentDeleteStudent(
            id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            confirmationPhrase = "delete",
            justification = "Valid 15 character justification"
        )
        assertTrue(wrongPhraseResult is Resource.Error)
        assertTrue((wrongPhraseResult as Resource.Error).message.contains("Confirmation phrase mismatch"))

        // Attempt with short justification
        val shortJustificationResult = studentRepository.permanentDeleteStudent(
            id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            confirmationPhrase = "DELETE PERMANENTLY",
            justification = "Too short"
        )
        assertTrue(shortJustificationResult is Resource.Error)
        assertTrue((shortJustificationResult as Resource.Error).message.contains("Audit Requirement"))
    }
}

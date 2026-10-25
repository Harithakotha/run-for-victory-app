package com.runforvictory.tuition.features.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.data.AuthRepository
import com.runforvictory.tuition.data.StudentRepository
import com.runforvictory.tuition.data.WorkReportRepository
import com.runforvictory.tuition.domain.DailyWorkReport
import com.runforvictory.tuition.domain.InputValidator
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.UserRole
import com.runforvictory.tuition.domain.ValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

data class WorkReportUiState(
    val activeStudents: List<Student> = emptyList(),
    val selectedStudentId: String = "",
    val subject: String = "",
    val workCompleted: String = "",
    val homework: String = "",
    val pendingWork: String = "",
    val progressNotes: String = "",
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val isSubmitting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val allReports: List<DailyWorkReport> = emptyList(),
    val filterStudentId: String? = null,
    val filterSubject: String = "",
    val filterDate: LocalDate? = null,
    val showEditDialog: Boolean = false,
    val editingReport: DailyWorkReport? = null,
    val editSubject: String = "",
    val editWorkCompleted: String = "",
    val editHomework: String = "",
    val editPendingWork: String = "",
    val editProgressNotes: String = "",
    val editReason: String = "",
    val editError: String? = null,
    val currentUserId: String? = null,
    val currentUserRole: UserRole? = null
) {
    val filteredReports: List<DailyWorkReport>
        get() {
            return allReports.filter { report ->
                val matchesStudent = filterStudentId == null || report.studentId == filterStudentId
                val matchesSubject = filterSubject.isBlank() || report.subject.contains(filterSubject, ignoreCase = true)
                val matchesDate = filterDate == null || report.reportDate == filterDate
                matchesStudent && matchesSubject && matchesDate
            }
        }
}

class WorkReportViewModel(
    private val workReportRepository: WorkReportRepository,
    private val studentRepository: StudentRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val kolkataZone = ZoneId.of("Asia/Kolkata")
    private val _uiState = MutableStateFlow(WorkReportUiState())
    val uiState: StateFlow<WorkReportUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    fun loadInitialData() {
        viewModelScope.launch {
            val user = authRepository.getCurrentUser()
            _uiState.update {
                it.copy(
                    currentUserId = user?.id,
                    currentUserRole = user?.role
                )
            }

            // Load active students
            studentRepository.getActiveStudents().collect { students ->
                _uiState.update { state ->
                    state.copy(
                        activeStudents = students,
                        selectedStudentId = if (state.selectedStudentId.isBlank() && students.isNotEmpty()) {
                            students.first().id
                        } else {
                            state.selectedStudentId
                        }
                    )
                }
            }
        }

        // Load all reports for history
        viewModelScope.launch {
            workReportRepository.getAllReportsForDate(LocalDate.now(kolkataZone)).collect { reports ->
                _uiState.update { it.copy(allReports = reports) }
            }
        }

        loadAllReports()
    }

    private fun loadAllReports() {
        viewModelScope.launch {
            // Subscribe to reports by tutor or all students
            studentRepository.getActiveStudents().first().let { students ->
                if (students.isNotEmpty()) {
                    val firstStudentId = students.first().id
                    workReportRepository.getReportsForStudent(firstStudentId).collect {
                        // Refresh full history
                        refreshFullHistory()
                    }
                }
            }
        }
    }

    private suspend fun refreshFullHistory() {
        val today = LocalDate.now(kolkataZone)
        workReportRepository.getAllReportsForDate(today).collect { reports ->
            _uiState.update { it.copy(allReports = reports) }
        }
    }

    fun onStudentSelected(studentId: String) {
        _uiState.update { it.copy(selectedStudentId = studentId, errorMessage = null) }
    }

    fun onSubjectChanged(subject: String) {
        _uiState.update { it.copy(subject = subject, errorMessage = null) }
    }

    fun onWorkCompletedChanged(work: String) {
        _uiState.update { it.copy(workCompleted = work, errorMessage = null) }
    }

    fun onHomeworkChanged(homework: String) {
        _uiState.update { it.copy(homework = homework) }
    }

    fun onPendingWorkChanged(pending: String) {
        _uiState.update { it.copy(pendingWork = pending) }
    }

    fun onProgressNotesChanged(notes: String) {
        _uiState.update { it.copy(progressNotes = notes) }
    }

    fun onFilterStudent(studentId: String?) {
        _uiState.update { it.copy(filterStudentId = studentId) }
    }

    fun onFilterSubject(subject: String) {
        _uiState.update { it.copy(filterSubject = subject) }
    }

    fun onFilterDate(date: LocalDate?) {
        _uiState.update { it.copy(filterDate = date) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(filterStudentId = null, filterSubject = "", filterDate = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun submitReport() {
        val state = _uiState.value
        val validation = InputValidator.validateWorkReportFull(
            studentId = state.selectedStudentId,
            subject = state.subject,
            workCompleted = state.workCompleted
        )

        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(errorMessage = validation.errorMessage) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val currentUser = authRepository.getCurrentUser()
            val studentName = state.activeStudents.find { it.id == state.selectedStudentId }?.fullName ?: "Student"

            val report = DailyWorkReport(
                id = UUID.randomUUID().toString(),
                studentId = state.selectedStudentId,
                studentName = studentName,
                tutorId = currentUser?.id ?: "",
                tutorName = currentUser?.fullName ?: "Tutor",
                reportDate = LocalDate.now(kolkataZone),
                subject = state.subject.trim(),
                workCompleted = state.workCompleted.trim(),
                homework = state.homework.trim().ifBlank { null },
                pendingWork = state.pendingWork.trim().ifBlank { null },
                progressNotes = state.progressNotes.trim().ifBlank { null },
                idempotencyKey = state.idempotencyKey
            )

            when (val result = workReportRepository.createReport(report)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            statusMessage = "Work report logged successfully for $studentName!",
                            subject = "",
                            workCompleted = "",
                            homework = "",
                            pendingWork = "",
                            progressNotes = "",
                            idempotencyKey = UUID.randomUUID().toString(), // Reset for next report
                            allReports = listOf(result.data) + it.allReports
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun openEditDialog(report: DailyWorkReport) {
        _uiState.update {
            it.copy(
                showEditDialog = true,
                editingReport = report,
                editSubject = report.subject,
                editWorkCompleted = report.workCompleted,
                editHomework = report.homework ?: "",
                editPendingWork = report.pendingWork ?: "",
                editProgressNotes = report.progressNotes ?: "",
                editReason = "",
                editError = null
            )
        }
    }

    fun dismissEditDialog() {
        _uiState.update {
            it.copy(
                showEditDialog = false,
                editingReport = null,
                editError = null
            )
        }
    }

    fun onEditSubjectChanged(s: String) { _uiState.update { it.copy(editSubject = s, editError = null) } }
    fun onEditWorkCompletedChanged(w: String) { _uiState.update { it.copy(editWorkCompleted = w, editError = null) } }
    fun onEditHomeworkChanged(h: String) { _uiState.update { it.copy(editHomework = h) } }
    fun onEditPendingWorkChanged(p: String) { _uiState.update { it.copy(editPendingWork = p) } }
    fun onEditProgressNotesChanged(n: String) { _uiState.update { it.copy(editProgressNotes = n) } }
    fun onEditReasonChanged(r: String) { _uiState.update { it.copy(editReason = r, editError = null) } }

    fun submitEdit() {
        val state = _uiState.value
        val report = state.editingReport ?: return

        val reasonValidation = InputValidator.validateReportEditReason(state.editReason)
        if (reasonValidation is ValidationResult.Invalid) {
            _uiState.update { it.copy(editError = reasonValidation.errorMessage) }
            return
        }

        if (state.editSubject.isBlank()) {
            _uiState.update { it.copy(editError = "Subject cannot be blank.") }
            return
        }
        if (state.editWorkCompleted.isBlank()) {
            _uiState.update { it.copy(editError = "Work completed cannot be blank.") }
            return
        }

        viewModelScope.launch {
            when (val res = workReportRepository.editReport(
                reportId = report.id,
                subject = state.editSubject,
                workCompleted = state.editWorkCompleted,
                homework = state.editHomework,
                pendingWork = state.editPendingWork,
                progressNotes = state.editProgressNotes,
                reason = state.editReason
            )) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            showEditDialog = false,
                            editingReport = null,
                            statusMessage = "Work report successfully updated and audited.",
                            allReports = it.allReports.map { old -> if (old.id == report.id) res.data else old }
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(editError = res.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }
}

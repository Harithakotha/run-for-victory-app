package com.runforvictory.tuition.features.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.data.AttendanceRepository
import com.runforvictory.tuition.data.AuthRepository
import com.runforvictory.tuition.data.StudentRepository
import com.runforvictory.tuition.domain.AttendanceDailySummary
import com.runforvictory.tuition.domain.AttendanceMonthlySummary
import com.runforvictory.tuition.domain.InputValidator
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.StudentAttendanceItem
import com.runforvictory.tuition.domain.StudentAttendanceRecord
import com.runforvictory.tuition.domain.StudentAttendanceStatus
import com.runforvictory.tuition.domain.UserRole
import com.runforvictory.tuition.domain.ValidationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

enum class SummaryViewMode {
    DAILY,
    MONTHLY
}

data class StudentAttendanceUiState(
    val selectedDate: LocalDate = LocalDate.now(ZoneId.of("Asia/Kolkata")),
    val searchQuery: String = "",
    val gradeFilter: String? = null,
    val statusFilter: StudentAttendanceStatus? = null,
    val summaryViewMode: SummaryViewMode = SummaryViewMode.DAILY,
    val dailySummary: AttendanceDailySummary? = null,
    val monthlySummary: AttendanceMonthlySummary? = null,
    val isLoadingSummary: Boolean = false,
    val inFlightStudentIds: Set<String> = emptySet(),
    val currentUserRole: UserRole = UserRole.TUTOR,
    // Correction Dialog State
    val isCorrectionDialogOpen: Boolean = false,
    val recordToCorrect: StudentAttendanceRecord? = null,
    val correctionCheckIn: ZonedDateTime? = null,
    val correctionCheckOut: ZonedDateTime? = null,
    val correctionStatus: StudentAttendanceStatus = StudentAttendanceStatus.PRESENT,
    val correctionNotes: String = "",
    val correctionReason: String = "",
    val correctionReasonError: String? = null,
    val isSavingCorrection: Boolean = false,
    // Student History Dialog State
    val isHistoryDialogOpen: Boolean = false,
    val selectedStudentForHistory: Student? = null,
    val studentHistoryRecords: List<StudentAttendanceRecord> = emptyList(),
    val isLoadingHistory: Boolean = false,
    // Feedback Banners
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class StudentAttendanceViewModel(
    private val attendanceRepository: AttendanceRepository,
    private val studentRepository: StudentRepository,
    private val authRepository: AuthRepository,
    private val kolkataZone: ZoneId = ZoneId.of("Asia/Kolkata")
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        StudentAttendanceUiState(
            selectedDate = LocalDate.now(kolkataZone)
        )
    )
    val uiState: StateFlow<StudentAttendanceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val user = authRepository.getCurrentUser()
            if (user != null) {
                _uiState.update { it.copy(currentUserRole = user.role) }
            }
            loadSummaries(_uiState.value.selectedDate)
        }
    }

    // Reactive items stream combining active students, attendance for selected date, search, and filters
    val attendanceItems: StateFlow<List<StudentAttendanceItem>> = _uiState.flatMapLatest { state ->
        combine(
            studentRepository.getActiveStudents(),
            attendanceRepository.getStudentAttendanceForDate(state.selectedDate)
        ) { activeStudents, attendanceList ->
            val attendanceByStudentId = attendanceList.associateBy { it.studentId }

            activeStudents
                .filter { student ->
                    val matchesQuery = state.searchQuery.isBlank() ||
                            student.fullName.contains(state.searchQuery, ignoreCase = true) ||
                            student.grade.contains(state.searchQuery, ignoreCase = true)
                    val matchesGrade = state.gradeFilter.isNullOrBlank() ||
                            student.grade.equals(state.gradeFilter, ignoreCase = true)
                    matchesQuery && matchesGrade
                }
                .map { student ->
                    val record = attendanceByStudentId[student.id]
                    StudentAttendanceItem(
                        student = student,
                        attendance = record,
                        isProcessing = state.inFlightStudentIds.contains(student.id)
                    )
                }
                .filter { item ->
                    if (state.statusFilter == null) true
                    else item.attendance?.status == state.statusFilter
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onDateChanged(date: LocalDate) {
        // Enforce boundary: do not allow picking future dates
        val today = LocalDate.now(kolkataZone)
        if (date.isAfter(today)) {
            _uiState.update { it.copy(errorMessage = "Cannot mark or view attendance for future dates.") }
            return
        }
        _uiState.update { it.copy(selectedDate = date, errorMessage = null) }
        loadSummaries(date)
    }

    fun onPreviousDay() {
        val newDate = _uiState.value.selectedDate.minusDays(1)
        onDateChanged(newDate)
    }

    fun onNextDay() {
        val today = LocalDate.now(kolkataZone)
        val newDate = _uiState.value.selectedDate.plusDays(1)
        if (!newDate.isAfter(today)) {
            onDateChanged(newDate)
        }
    }

    fun onToday() {
        onDateChanged(LocalDate.now(kolkataZone))
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onGradeFilterChanged(grade: String?) {
        _uiState.update { it.copy(gradeFilter = if (it.gradeFilter == grade) null else grade) }
    }

    fun onStatusFilterChanged(status: StudentAttendanceStatus?) {
        _uiState.update { it.copy(statusFilter = if (it.statusFilter == status) null else status) }
    }

    fun onSummaryViewModeChanged(mode: SummaryViewMode) {
        _uiState.update { it.copy(summaryViewMode = mode) }
    }

    fun recordCheckIn(
        studentId: String,
        status: StudentAttendanceStatus = StudentAttendanceStatus.PRESENT,
        notes: String? = null
    ) {
        // Debounce repeated taps
        if (_uiState.value.inFlightStudentIds.contains(studentId)) return

        _uiState.update {
            it.copy(
                inFlightStudentIds = it.inFlightStudentIds + studentId,
                errorMessage = null,
                successMessage = null
            )
        }

        viewModelScope.launch {
            val idempotencyKey = UUID.randomUUID().toString()
            val result = attendanceRepository.recordStudentCheckIn(
                studentId = studentId,
                status = status,
                notes = notes,
                idempotencyKey = idempotencyKey
            )

            _uiState.update { current ->
                val remainingIds = current.inFlightStudentIds - studentId
                when (result) {
                    is Resource.Success -> {
                        current.copy(
                            inFlightStudentIds = remainingIds,
                            successMessage = "Check-in recorded for ${result.data.studentName} (${result.data.status})."
                        )
                    }
                    is Resource.Error -> {
                        current.copy(
                            inFlightStudentIds = remainingIds,
                            errorMessage = result.message
                        )
                    }
                    is Resource.Loading -> current
                }
            }
            loadSummaries(_uiState.value.selectedDate)
        }
    }

    fun recordCheckOut(studentId: String) {
        if (_uiState.value.inFlightStudentIds.contains(studentId)) return

        _uiState.update {
            it.copy(
                inFlightStudentIds = it.inFlightStudentIds + studentId,
                errorMessage = null,
                successMessage = null
            )
        }

        viewModelScope.launch {
            val idempotencyKey = UUID.randomUUID().toString()
            val result = attendanceRepository.recordStudentCheckOut(
                studentId = studentId,
                idempotencyKey = idempotencyKey
            )

            _uiState.update { current ->
                val remainingIds = current.inFlightStudentIds - studentId
                when (result) {
                    is Resource.Success -> {
                        val duration = result.data.durationMinutes ?: 0
                        current.copy(
                            inFlightStudentIds = remainingIds,
                            successMessage = "Check-out recorded for ${result.data.studentName} (Duration: ${duration}m)."
                        )
                    }
                    is Resource.Error -> {
                        current.copy(
                            inFlightStudentIds = remainingIds,
                            errorMessage = result.message
                        )
                    }
                    is Resource.Loading -> current
                }
            }
            loadSummaries(_uiState.value.selectedDate)
        }
    }

    // Owner Correction Workflow
    fun openCorrectionDialog(record: StudentAttendanceRecord) {
        if (_uiState.value.currentUserRole != UserRole.OWNER) {
            _uiState.update { it.copy(errorMessage = "Access Denied: Only centre owner can correct attendance.") }
            return
        }

        _uiState.update {
            it.copy(
                isCorrectionDialogOpen = true,
                recordToCorrect = record,
                correctionCheckIn = record.checkInTime,
                correctionCheckOut = record.checkOutTime,
                correctionStatus = record.status,
                correctionNotes = record.notes ?: "",
                correctionReason = "",
                correctionReasonError = null,
                errorMessage = null
            )
        }
    }

    fun closeCorrectionDialog() {
        _uiState.update {
            it.copy(
                isCorrectionDialogOpen = false,
                recordToCorrect = null,
                correctionReasonError = null
            )
        }
    }

    fun onCorrectionReasonChanged(reason: String) {
        _uiState.update {
            it.copy(
                correctionReason = reason,
                correctionReasonError = null
            )
        }
    }

    fun onCorrectionStatusChanged(status: StudentAttendanceStatus) {
        _uiState.update { it.copy(correctionStatus = status) }
    }

    fun onCorrectionNotesChanged(notes: String) {
        _uiState.update { it.copy(correctionNotes = notes) }
    }

    fun onCorrectionCheckInAdjusted(newTime: ZonedDateTime) {
        _uiState.update { it.copy(correctionCheckIn = newTime) }
    }

    fun onCorrectionCheckOutAdjusted(newTime: ZonedDateTime?) {
        _uiState.update { it.copy(correctionCheckOut = newTime) }
    }

    fun submitCorrection() {
        val state = _uiState.value
        val record = state.recordToCorrect ?: return

        val reasonValidation = InputValidator.validateCorrectionReason(state.correctionReason)
        if (reasonValidation is ValidationResult.Invalid) {
            _uiState.update { it.copy(correctionReasonError = reasonValidation.errorMessage) }
            return
        }

        val checkIn = state.correctionCheckIn ?: record.checkInTime
        val checkOut = state.correctionCheckOut

        val timeValidation = InputValidator.validateAttendanceTimes(checkIn, checkOut)
        if (timeValidation is ValidationResult.Invalid) {
            _uiState.update { it.copy(errorMessage = timeValidation.errorMessage) }
            return
        }

        _uiState.update { it.copy(isSavingCorrection = true) }

        viewModelScope.launch {
            val result = attendanceRepository.ownerCorrectStudentAttendance(
                attendanceId = record.id,
                newCheckIn = checkIn,
                newCheckOut = checkOut,
                newStatus = state.correctionStatus,
                notes = state.correctionNotes.ifBlank { null },
                reason = state.correctionReason.trim()
            )

            _uiState.update { current ->
                when (result) {
                    is Resource.Success -> {
                        current.copy(
                            isSavingCorrection = false,
                            isCorrectionDialogOpen = false,
                            recordToCorrect = null,
                            successMessage = "Attendance record corrected with audit log entry."
                        )
                    }
                    is Resource.Error -> {
                        current.copy(
                            isSavingCorrection = false,
                            errorMessage = result.message
                        )
                    }
                    is Resource.Loading -> current
                }
            }
            loadSummaries(_uiState.value.selectedDate)
        }
    }

    // Student History Workflow
    fun openHistoryDialog(student: Student) {
        _uiState.update {
            it.copy(
                isHistoryDialogOpen = true,
                selectedStudentForHistory = student,
                isLoadingHistory = true,
                studentHistoryRecords = emptyList()
            )
        }

        viewModelScope.launch {
            attendanceRepository.getAttendanceForStudent(student.id).collect { records ->
                _uiState.update {
                    it.copy(
                        studentHistoryRecords = records,
                        isLoadingHistory = false
                    )
                }
            }
        }
    }

    fun closeHistoryDialog() {
        _uiState.update {
            it.copy(
                isHistoryDialogOpen = false,
                selectedStudentForHistory = null,
                studentHistoryRecords = emptyList()
            )
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    private fun loadSummaries(date: LocalDate) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSummary = true) }
            val daily = attendanceRepository.getDailySummary(date)
            val monthly = attendanceRepository.getMonthlySummary(date.year, date.monthValue)

            _uiState.update {
                it.copy(
                    dailySummary = (daily as? Resource.Success)?.data,
                    monthlySummary = (monthly as? Resource.Success)?.data,
                    isLoadingSummary = false
                )
            }
        }
    }
}

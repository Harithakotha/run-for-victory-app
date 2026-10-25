package com.runforvictory.tuition.features.owner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.data.StudentRepository
import com.runforvictory.tuition.domain.InputValidator
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.StudentHistory
import com.runforvictory.tuition.domain.StudentStatus
import com.runforvictory.tuition.domain.ValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class StudentManagementUiState(
    val searchQuery: String = "",
    val gradeFilter: String? = null,
    val selectedStatus: StudentStatus = StudentStatus.ACTIVE,
    val isAddEditDialogOpen: Boolean = false,
    val editingStudent: Student? = null,
    val formFullName: String = "",
    val formFullNameError: String? = null,
    val formGrade: String = "Class 10",
    val formGradeError: String? = null,
    val formGuardianName: String = "",
    val formGuardianNameError: String? = null,
    val formGuardianPhone: String = "",
    val formGuardianPhoneError: String? = null,
    val formContactPhone: String = "",
    val formNotes: String = "",
    val isSaving: Boolean = false,
    val isArchiveDialogOpen: Boolean = false,
    val studentToArchive: Student? = null,
    val archiveReason: String = "",
    val archiveReasonError: String? = null,
    val isArchiving: Boolean = false,
    val isRestoreDialogOpen: Boolean = false,
    val studentToRestore: Student? = null,
    val isRestoring: Boolean = false,
    val isHistoryDialogOpen: Boolean = false,
    val selectedStudentHistory: StudentHistory? = null,
    val isLoadingHistory: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudentManagementViewModel(
    private val studentRepository: StudentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudentManagementUiState())
    val uiState: StateFlow<StudentManagementUiState> = _uiState.asStateFlow()

    // Reactive student stream based on search, grade filter, and status tab
    val students: StateFlow<List<Student>> = combine(
        _uiState.asStateFlow()
    ) { stateArray ->
        val state = stateArray[0]
        Triple(state.searchQuery, state.gradeFilter, state.selectedStatus)
    }.flatMapLatest { (query, grade, status) ->
        studentRepository.getStudents(
            query = query,
            gradeFilter = grade,
            statusFilter = status
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onGradeFilterChanged(grade: String?) {
        _uiState.update { it.copy(gradeFilter = if (it.gradeFilter == grade) null else grade) }
    }

    fun onStatusTabSelected(status: StudentStatus) {
        _uiState.update { it.copy(selectedStatus = status) }
    }

    fun openAddStudentDialog() {
        _uiState.update {
            it.copy(
                isAddEditDialogOpen = true,
                editingStudent = null,
                formFullName = "",
                formFullNameError = null,
                formGrade = "Class 10",
                formGradeError = null,
                formGuardianName = "",
                formGuardianNameError = null,
                formGuardianPhone = "",
                formGuardianPhoneError = null,
                formContactPhone = "",
                formNotes = "",
                errorMessage = null,
                successMessage = null
            )
        }
    }

    fun openEditStudentDialog(student: Student) {
        _uiState.update {
            it.copy(
                isAddEditDialogOpen = true,
                editingStudent = student,
                formFullName = student.fullName,
                formFullNameError = null,
                formGrade = student.grade,
                formGradeError = null,
                formGuardianName = student.guardianName,
                formGuardianNameError = null,
                formGuardianPhone = student.guardianPhone ?: "",
                formGuardianPhoneError = null,
                formContactPhone = student.contactPhone ?: "",
                formNotes = student.notes ?: "",
                errorMessage = null,
                successMessage = null
            )
        }
    }

    fun dismissAddEditDialog() {
        _uiState.update { it.copy(isAddEditDialogOpen = false, editingStudent = null) }
    }

    fun onFormFullNameChanged(name: String) {
        _uiState.update { it.copy(formFullName = name, formFullNameError = null) }
    }

    fun onFormGradeChanged(grade: String) {
        _uiState.update { it.copy(formGrade = grade, formGradeError = null) }
    }

    fun onFormGuardianNameChanged(name: String) {
        _uiState.update { it.copy(formGuardianName = name, formGuardianNameError = null) }
    }

    fun onFormGuardianPhoneChanged(phone: String) {
        _uiState.update { it.copy(formGuardianPhone = phone, formGuardianPhoneError = null) }
    }

    fun onFormContactPhoneChanged(phone: String) {
        _uiState.update { it.copy(formContactPhone = phone) }
    }

    fun onFormNotesChanged(notes: String) {
        _uiState.update { it.copy(formNotes = notes) }
    }

    fun saveStudent() {
        val state = _uiState.value

        val nameVal = InputValidator.validateStudentName(state.formFullName)
        val gradeVal = InputValidator.validateGrade(state.formGrade)
        val guardianVal = InputValidator.validateGuardianName(state.formGuardianName)
        val phoneVal = InputValidator.validatePhoneNumber(state.formGuardianPhone, isRequired = false)

        if (nameVal is ValidationResult.Invalid ||
            gradeVal is ValidationResult.Invalid ||
            guardianVal is ValidationResult.Invalid ||
            phoneVal is ValidationResult.Invalid
        ) {
            _uiState.update {
                it.copy(
                    formFullNameError = (nameVal as? ValidationResult.Invalid)?.errorMessage,
                    formGradeError = (gradeVal as? ValidationResult.Invalid)?.errorMessage,
                    formGuardianNameError = (guardianVal as? ValidationResult.Invalid)?.errorMessage,
                    formGuardianPhoneError = (phoneVal as? ValidationResult.Invalid)?.errorMessage
                )
            }
            return
        }

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            val studentToSave = state.editingStudent?.copy(
                fullName = state.formFullName.trim(),
                grade = state.formGrade.trim(),
                guardianName = state.formGuardianName.trim(),
                guardianPhone = state.formGuardianPhone.trim().ifBlank { null },
                contactPhone = state.formContactPhone.trim().ifBlank { null },
                notes = state.formNotes.trim().ifBlank { null }
            ) ?: Student(
                id = UUID.randomUUID().toString(),
                fullName = state.formFullName.trim(),
                grade = state.formGrade.trim(),
                guardianName = state.formGuardianName.trim(),
                guardianPhone = state.formGuardianPhone.trim().ifBlank { null },
                contactPhone = state.formContactPhone.trim().ifBlank { null },
                notes = state.formNotes.trim().ifBlank { null },
                status = StudentStatus.ACTIVE
            )

            val result = if (state.editingStudent != null) {
                studentRepository.updateStudent(studentToSave)
            } else {
                studentRepository.createStudent(studentToSave)
            }

            when (result) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isAddEditDialogOpen = false,
                            editingStudent = null,
                            successMessage = "Student '${studentToSave.fullName}' saved successfully."
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {
                    _uiState.update { it.copy(isSaving = true) }
                }
            }
        }
    }

    fun openArchiveDialog(student: Student) {
        _uiState.update {
            it.copy(
                isArchiveDialogOpen = true,
                studentToArchive = student,
                archiveReason = "",
                archiveReasonError = null,
                errorMessage = null
            )
        }
    }

    fun dismissArchiveDialog() {
        _uiState.update { it.copy(isArchiveDialogOpen = false, studentToArchive = null) }
    }

    fun onArchiveReasonChanged(reason: String) {
        _uiState.update { it.copy(archiveReason = reason, archiveReasonError = null) }
    }

    fun confirmArchive() {
        val state = _uiState.value
        val student = state.studentToArchive ?: return

        val reasonVal = InputValidator.validateArchivalReason(state.archiveReason)
        if (reasonVal is ValidationResult.Invalid) {
            _uiState.update { it.copy(archiveReasonError = reasonVal.errorMessage) }
            return
        }

        _uiState.update { it.copy(isArchiving = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = studentRepository.archiveStudent(student.id, state.archiveReason.trim())) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isArchiving = false,
                            isArchiveDialogOpen = false,
                            studentToArchive = null,
                            successMessage = "Student '${student.fullName}' archived successfully."
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isArchiving = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {
                    _uiState.update { it.copy(isArchiving = true) }
                }
            }
        }
    }

    fun openRestoreDialog(student: Student) {
        _uiState.update {
            it.copy(
                isRestoreDialogOpen = true,
                studentToRestore = student,
                errorMessage = null
            )
        }
    }

    fun dismissRestoreDialog() {
        _uiState.update { it.copy(isRestoreDialogOpen = false, studentToRestore = null) }
    }

    fun confirmRestore() {
        val student = _uiState.value.studentToRestore ?: return
        _uiState.update { it.copy(isRestoring = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = studentRepository.restoreStudent(student.id)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isRestoring = false,
                            isRestoreDialogOpen = false,
                            studentToRestore = null,
                            successMessage = "Student '${student.fullName}' restored to active directory."
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isRestoring = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {
                    _uiState.update { it.copy(isRestoring = true) }
                }
            }
        }
    }

    fun viewStudentHistory(student: Student) {
        _uiState.update {
            it.copy(
                isHistoryDialogOpen = true,
                isLoadingHistory = true,
                selectedStudentHistory = null
            )
        }

        viewModelScope.launch {
            when (val result = studentRepository.getStudentHistory(student.id)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingHistory = false,
                            selectedStudentHistory = result.data
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingHistory = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {
                    _uiState.update { it.copy(isLoadingHistory = true) }
                }
            }
        }
    }

    fun dismissHistoryDialog() {
        _uiState.update { it.copy(isHistoryDialogOpen = false, selectedStudentHistory = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}

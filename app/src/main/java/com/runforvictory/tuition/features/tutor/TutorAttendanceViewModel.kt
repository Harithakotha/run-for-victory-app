package com.runforvictory.tuition.features.tutor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.data.AttendanceRepository
import com.runforvictory.tuition.data.AuthRepository
import com.runforvictory.tuition.domain.GpsVerificationStatus
import com.runforvictory.tuition.domain.TutorAttendanceRecord
import com.runforvictory.tuition.domain.TutorMonthlySummary
import com.runforvictory.tuition.domain.UserRole
import com.runforvictory.tuition.location.FreshLocationProvider
import com.runforvictory.tuition.location.LocationFetchResult
import com.runforvictory.tuition.location.LocationReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class TutorAttendanceUiState(
    val tutorId: String = "",
    val tutorName: String = "",
    val activeSession: TutorAttendanceRecord? = null,
    val monthlySummary: TutorMonthlySummary? = null,
    val monthlyRecords: List<TutorAttendanceRecord> = emptyList(),
    val isLoading: Boolean = false,
    val isLocating: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val showPermissionRationale: Boolean = false,
    val showGpsDisabledDialog: Boolean = false,
    val pendingAction: PendingAttendanceAction? = null
) {
    val isClockedIn: Boolean get() = activeSession != null
}

enum class PendingAttendanceAction {
    CLOCK_IN,
    CLOCK_OUT
}

class TutorAttendanceViewModel(
    private val attendanceRepository: AttendanceRepository,
    private val authRepository: AuthRepository,
    private val locationProvider: FreshLocationProvider
) : ViewModel() {

    private val kolkataZone = ZoneId.of("Asia/Kolkata")
    private val _uiState = MutableStateFlow(TutorAttendanceUiState())
    val uiState: StateFlow<TutorAttendanceUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val currentUser = authRepository.getCurrentUser()
            if (currentUser == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "User session expired. Please sign in.") }
                return@launch
            }

            val tutorId = currentUser.id
            val today = LocalDate.now(kolkataZone)

            _uiState.update {
                it.copy(
                    tutorId = tutorId,
                    tutorName = currentUser.fullName
                )
            }

            val activeRes = attendanceRepository.getActiveTutorSession(tutorId)
            val active = if (activeRes is Resource.Success) activeRes.data else null

            val summaryRes = attendanceRepository.getTutorMonthlySummary(tutorId, today.year, today.monthValue)
            val summary = if (summaryRes is Resource.Success) summaryRes.data else null

            _uiState.update {
                it.copy(
                    activeSession = active,
                    monthlySummary = summary,
                    isLoading = false
                )
            }

            observeMonthlyRecords(tutorId, today.year, today.monthValue)
        }
    }

    private fun observeMonthlyRecords(tutorId: String, year: Int, month: Int) {
        viewModelScope.launch {
            attendanceRepository.getTutorAttendanceForMonth(tutorId, year, month).collect { records ->
                _uiState.update {
                    it.copy(monthlyRecords = records)
                }
            }
        }
    }

    fun onClockInRequested(hasPermission: Boolean) {
        if (!hasPermission) {
            _uiState.update {
                it.copy(
                    showPermissionRationale = true,
                    pendingAction = PendingAttendanceAction.CLOCK_IN
                )
            }
            return
        }

        if (!locationProvider.isGpsEnabled()) {
            _uiState.update { it.copy(showGpsDisabledDialog = true) }
            return
        }

        executeLocationAttendance(PendingAttendanceAction.CLOCK_IN)
    }

    fun onClockOutRequested(hasPermission: Boolean) {
        if (!hasPermission) {
            _uiState.update {
                it.copy(
                    showPermissionRationale = true,
                    pendingAction = PendingAttendanceAction.CLOCK_OUT
                )
            }
            return
        }

        if (!locationProvider.isGpsEnabled()) {
            _uiState.update { it.copy(showGpsDisabledDialog = true) }
            return
        }

        executeLocationAttendance(PendingAttendanceAction.CLOCK_OUT)
    }

    fun onPermissionRationaleConfirmed() {
        _uiState.update { it.copy(showPermissionRationale = false) }
    }

    fun onPermissionGranted() {
        val pending = _uiState.value.pendingAction
        _uiState.update { it.copy(showPermissionRationale = false, pendingAction = null) }
        if (pending != null) {
            executeLocationAttendance(pending)
        }
    }

    fun onPermissionDenied() {
        _uiState.update {
            it.copy(
                showPermissionRationale = false,
                pendingAction = null,
                errorMessage = "Location permission is required to verify proximity to the tuition centre."
            )
        }
    }

    fun dismissGpsDisabledDialog() {
        _uiState.update { it.copy(showGpsDisabledDialog = false) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    private fun executeLocationAttendance(action: PendingAttendanceAction) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLocating = true, errorMessage = null, statusMessage = null) }

            when (val fetchResult = locationProvider.getFreshLocation(timeoutMs = 15_000L)) {
                is LocationFetchResult.PermissionDenied -> {
                    _uiState.update {
                        it.copy(
                            isLocating = false,
                            errorMessage = "Location permission denied. Cannot verify attendance."
                        )
                    }
                }
                is LocationFetchResult.GpsDisabled -> {
                    _uiState.update {
                        it.copy(
                            isLocating = false,
                            showGpsDisabledDialog = true
                        )
                    }
                }
                is LocationFetchResult.Timeout -> {
                    _uiState.update {
                        it.copy(
                            isLocating = false,
                            errorMessage = "GPS location timeout. Please check your signal and try again."
                        )
                    }
                }
                is LocationFetchResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLocating = false,
                            errorMessage = fetchResult.message
                        )
                    }
                }
                is LocationFetchResult.Success -> {
                    submitAttendanceReading(action, fetchResult.reading)
                }
            }
        }
    }

    fun submitAttendanceReading(action: PendingAttendanceAction, reading: LocationReading) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLocating = true, errorMessage = null) }

            val today = LocalDate.now(kolkataZone)
            val tutorId = _uiState.value.tutorId

            if (action == PendingAttendanceAction.CLOCK_IN) {
                when (val res = attendanceRepository.recordTutorCheckIn(
                    lat = reading.latitude,
                    lng = reading.longitude,
                    accuracy = reading.accuracyMeters,
                    readingTimestampMillis = reading.timestampMillis,
                    isMock = reading.isMock
                )) {
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isLocating = false,
                                activeSession = res.data,
                                statusMessage = "Clock-in verified! ${res.data.checkInDistanceMeters}m from centre (Accuracy: ±${res.data.checkInAccuracyMeters.toInt()}m)."
                            )
                        }
                        refreshMonthlyData(tutorId, today.year, today.monthValue)
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isLocating = false,
                                errorMessage = res.message
                            )
                        }
                        refreshMonthlyData(tutorId, today.year, today.monthValue)
                    }
                    is Resource.Loading -> {}
                }
            } else {
                when (val res = attendanceRepository.recordTutorCheckOut(
                    lat = reading.latitude,
                    lng = reading.longitude,
                    accuracy = reading.accuracyMeters,
                    readingTimestampMillis = reading.timestampMillis,
                    isMock = reading.isMock
                )) {
                    is Resource.Success -> {
                        val duration = res.data.durationMinutes ?: 0L
                        val hours = duration / 60
                        val mins = duration % 60
                        _uiState.update {
                            it.copy(
                                isLocating = false,
                                activeSession = null,
                                statusMessage = "Clock-out verified! Session duration: ${hours}h ${mins}m."
                            )
                        }
                        refreshMonthlyData(tutorId, today.year, today.monthValue)
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isLocating = false,
                                errorMessage = res.message
                            )
                        }
                        refreshMonthlyData(tutorId, today.year, today.monthValue)
                    }
                    is Resource.Loading -> {}
                }
            }
        }
    }

    private suspend fun refreshMonthlyData(tutorId: String, year: Int, month: Int) {
        val summaryRes = attendanceRepository.getTutorMonthlySummary(tutorId, year, month)
        if (summaryRes is Resource.Success) {
            _uiState.update { it.copy(monthlySummary = summaryRes.data) }
        }
    }
}

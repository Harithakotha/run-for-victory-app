package com.runforvictory.tuition.features.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.data.AttendanceRepository
import com.runforvictory.tuition.data.AuthRepository
import com.runforvictory.tuition.data.NotificationRepository
import com.runforvictory.tuition.data.StudentRepository
import com.runforvictory.tuition.data.WorkReportRepository
import com.runforvictory.tuition.domain.AppNotification
import com.runforvictory.tuition.domain.AttendanceMonthlySummary
import com.runforvictory.tuition.domain.DailyWorkReport
import com.runforvictory.tuition.domain.NotificationPreferences
import com.runforvictory.tuition.domain.ParentWeeklySummary
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.StudentAttendanceRecord
import com.runforvictory.tuition.domain.StudentAttendanceStatus
import com.runforvictory.tuition.domain.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class ParentTab { HOME, REPORTS, ATTENDANCE, NOTIFICATIONS }

data class ParentDashboardUiState(
    // Loading / error / session
    val isLoading: Boolean = true,
    val isChildDataLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOffline: Boolean = false,
    val sessionExpired: Boolean = false,

    // Profile & linked children
    val parentProfile: UserProfile? = null,
    val linkedChildren: List<Student> = emptyList(),
    val selectedChildId: String? = null,

    // Today's attendance
    val todayAttendance: StudentAttendanceRecord? = null,

    // Work reports (paginated)
    val reports: List<DailyWorkReport> = emptyList(),
    val reportPage: Int = 0,
    val hasMoreReports: Boolean = false,

    // Attendance history
    val attendanceHistory: List<StudentAttendanceRecord> = emptyList(),
    val weeklySummary: ParentWeeklySummary? = null,
    val monthlySummary: AttendanceMonthlySummary? = null,

    // Notifications
    val notifications: List<AppNotification> = emptyList(),
    val unreadNotificationCount: Int = 0,
    val preferences: NotificationPreferences? = null,
    val isPreferencesDialogVisible: Boolean = false,

    // Active tab
    val selectedTab: ParentTab = ParentTab.HOME
) {
    val selectedChild: Student? get() = linkedChildren.find { it.id == selectedChildId }
    val hasLinkedChildren: Boolean get() = linkedChildren.isNotEmpty()
    val todayStatusLabel: String
        get() = when (todayAttendance?.status) {
            StudentAttendanceStatus.PRESENT -> "Present"
            StudentAttendanceStatus.LATE -> "Late"
            StudentAttendanceStatus.ABSENT -> "Absent"
            StudentAttendanceStatus.EXCUSED -> "Excused"
            StudentAttendanceStatus.HALF_DAY -> "Half Day"
            else -> "Not recorded"
        }
    val pageSize: Int get() = 15
    val recentReports: List<DailyWorkReport> get() = reports.take(5)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ParentDashboardViewModel(
    private val authRepository: AuthRepository,
    private val studentRepository: StudentRepository,
    private val attendanceRepository: AttendanceRepository,
    private val workReportRepository: WorkReportRepository,
    private val notificationRepository: NotificationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParentDashboardUiState())
    val uiState: StateFlow<ParentDashboardUiState> = _uiState.asStateFlow()

    private val kolkataZone = ZoneId.of("Asia/Kolkata")
    private var childDataJob: Job? = null

    init {
        loadParentDashboard()
    }

    // ── Bootstrap ────────────────────────────────────────────────────────────

    fun loadParentDashboard() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            // Verify session
            val user = try {
                authRepository.getCurrentUser()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, sessionExpired = true,
                        errorMessage = "Session expired. Please sign in again.")
                }
                return@launch
            }

            if (user == null) {
                _uiState.update {
                    it.copy(isLoading = false, sessionExpired = true,
                        errorMessage = "Session expired. Please sign in again.")
                }
                return@launch
            }

            _uiState.update { it.copy(parentProfile = user) }

            // Load linked children reactively
            studentRepository.getLinkedStudentsForParent(user.id)
                .catch { e ->
                    _uiState.update {
                        it.copy(isLoading = false,
                            isOffline = e.message?.contains("network", ignoreCase = true) == true,
                            errorMessage = e.message ?: "Failed to load children.")
                    }
                }
                .collect { children ->
                    val firstId = _uiState.value.selectedChildId ?: children.firstOrNull()?.id
                    _uiState.update { s ->
                        s.copy(
                            isLoading = false,
                            linkedChildren = children,
                            selectedChildId = firstId
                        )
                    }
                    // Load data for the initially-selected child
                    if (firstId != null) {
                        loadChildData(user.id, firstId)
                    }
                }
        }

        // Observe notifications independently
        viewModelScope.launch {
            val userId = authRepository.getCurrentUser()?.id ?: return@launch
            combine(
                notificationRepository.getNotificationsForUser(userId),
                notificationRepository.getUnreadCount(userId)
            ) { notifs, count -> Pair(notifs, count) }.collect { (notifs, count) ->
                _uiState.update { it.copy(notifications = notifs, unreadNotificationCount = count) }
            }
        }
    }

    // ── Child selection ──────────────────────────────────────────────────────

    fun selectChild(childId: String) {
        if (_uiState.value.selectedChildId == childId) return
        _uiState.update { it.copy(selectedChildId = childId, reportPage = 0) }
        val parentId = _uiState.value.parentProfile?.id ?: return
        loadChildData(parentId, childId)
    }

    // ── Load all data for a child ────────────────────────────────────────────

    private fun loadChildData(parentId: String, childId: String) {
        childDataJob?.cancel()
        childDataJob = viewModelScope.launch {
            _uiState.update { it.copy(isChildDataLoading = true) }

            // Today's attendance
            val today = LocalDate.now(kolkataZone)
            attendanceRepository.getAttendanceForParent(parentId, childId)
                .catch { /* silently emit empty */ }
                .collect { records ->
                    val todayRecord = records.firstOrNull { it.attendanceDate == today }
                    _uiState.update { it.copy(todayAttendance = todayRecord) }
                }
        }

        viewModelScope.launch {
            // Attendance history
            attendanceRepository.getAttendanceForParent(parentId, childId)
                .catch { }
                .collect { records ->
                    _uiState.update { it.copy(attendanceHistory = records) }
                }
        }

        viewModelScope.launch {
            // Work reports
            workReportRepository.getReportsForParent(parentId, childId)
                .catch { }
                .collect { reports ->
                    val page = _uiState.value.reportPage
                    val pageSize = _uiState.value.pageSize
                    val paged = reports.take((page + 1) * pageSize)
                    _uiState.update {
                        it.copy(
                            reports = paged,
                            hasMoreReports = reports.size > paged.size,
                            isChildDataLoading = false
                        )
                    }
                }
        }

        viewModelScope.launch {
            // Weekly summary
            val weeklyResult = attendanceRepository.getWeeklySummaryForParent(parentId, childId)
            if (weeklyResult is Resource.Success) {
                _uiState.update { it.copy(weeklySummary = weeklyResult.data) }
            }
        }

        viewModelScope.launch {
            // Monthly summary — current month
            val now = LocalDate.now(kolkataZone)
            val monthlyResult = attendanceRepository.getMonthlySummaryForParent(
                parentId, childId, now.year, now.monthValue
            )
            if (monthlyResult is Resource.Success) {
                _uiState.update { it.copy(monthlySummary = monthlyResult.data) }
            }
        }
    }

    // ── Tab navigation ───────────────────────────────────────────────────────

    fun selectTab(tab: ParentTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    // ── Notifications ────────────────────────────────────────────────────────

    fun markNotificationRead(notificationId: String) {
        viewModelScope.launch {
            notificationRepository.markAsRead(notificationId)
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            val userId = _uiState.value.parentProfile?.id ?: return@launch
            notificationRepository.markAllAsRead(userId)
        }
    }

    fun refreshNotifications() {
        val userId = _uiState.value.parentProfile?.id ?: return
        viewModelScope.launch {
            notificationRepository.refreshNotifications(userId)
        }
    }

    fun loadPreferences() {
        val userId = _uiState.value.parentProfile?.id ?: return
        viewModelScope.launch {
            val res = notificationRepository.getPreferences(userId)
            if (res is Resource.Success) {
                _uiState.update { it.copy(preferences = res.data) }
            }
        }
    }

    fun updatePreferences(
        pushEnabled: Boolean,
        workReportsEnabled: Boolean,
        attendanceEnabled: Boolean,
        announcementsEnabled: Boolean
    ) {
        val userId = _uiState.value.parentProfile?.id ?: return
        val updated = NotificationPreferences(
            userId = userId,
            pushEnabled = pushEnabled,
            workReportsEnabled = workReportsEnabled,
            attendanceEnabled = attendanceEnabled,
            announcementsEnabled = announcementsEnabled
        )
        viewModelScope.launch {
            notificationRepository.updatePreferences(updated)
            _uiState.update { it.copy(preferences = updated, isPreferencesDialogVisible = false) }
        }
    }

    fun setPreferencesDialogVisible(visible: Boolean) {
        if (visible && _uiState.value.preferences == null) {
            loadPreferences()
        }
        _uiState.update { it.copy(isPreferencesDialogVisible = visible) }
    }

    // ── Reports pagination ───────────────────────────────────────────────────

    fun loadMoreReports() {
        if (!_uiState.value.hasMoreReports) return
        val parentId = _uiState.value.parentProfile?.id ?: return
        val childId = _uiState.value.selectedChildId ?: return
        _uiState.update { it.copy(reportPage = it.reportPage + 1) }
        loadChildData(parentId, childId)
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    fun refresh() {
        _uiState.update { it.copy(errorMessage = null, isOffline = false, reportPage = 0) }
        loadParentDashboard()
    }

    // ── Dismiss error ────────────────────────────────────────────────────────

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

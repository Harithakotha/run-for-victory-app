package com.runforvictory.tuition.features.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runforvictory.tuition.R
import com.runforvictory.tuition.core.theme.BrandGoldPrimary
import com.runforvictory.tuition.core.theme.BrandNavyPrimary
import com.runforvictory.tuition.core.theme.BrandTealPrimary
import com.runforvictory.tuition.core.theme.StatusError
import com.runforvictory.tuition.core.theme.StatusInfo
import com.runforvictory.tuition.core.theme.StatusSuccess
import com.runforvictory.tuition.core.theme.StatusWarning
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.StudentAttendanceItem
import com.runforvictory.tuition.domain.StudentAttendanceRecord
import com.runforvictory.tuition.domain.StudentAttendanceStatus
import com.runforvictory.tuition.domain.UserRole
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentAttendanceScreen(
    viewModel: StudentAttendanceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val attendanceItems by viewModel.attendanceItems.collectAsState()

    val kolkataZone = remember { ZoneId.of("Asia/Kolkata") }
    val today = remember { LocalDate.now(kolkataZone) }
    val isToday = (uiState.selectedDate == today)

    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(id = R.string.attendance_register_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${uiState.selectedDate.format(dateFormatter)} (IST)",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandGoldPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.action_cancel)
                        )
                    }
                },
                actions = {
                    if (!isToday) {
                        IconButton(onClick = { viewModel.onToday() }) {
                            Icon(
                                imageVector = Icons.Default.Today,
                                contentDescription = stringResource(id = R.string.attendance_today_button)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandNavyPrimary,
                    titleContentColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.surface,
                    actionIconContentColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Surface(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Date Navigation Strip
                DateNavigationStrip(
                    selectedDate = uiState.selectedDate,
                    isToday = isToday,
                    dateFormatter = dateFormatter,
                    onPrevious = { viewModel.onPreviousDay() },
                    onNext = { viewModel.onNextDay() },
                    onToday = { viewModel.onToday() }
                )

                // Feedback Banners
                uiState.errorMessage?.let { err ->
                    FeedbackBanner(
                        message = err,
                        isError = true,
                        onDismiss = { viewModel.clearFeedback() }
                    )
                }
                uiState.successMessage?.let { success ->
                    FeedbackBanner(
                        message = success,
                        isError = false,
                        onDismiss = { viewModel.clearFeedback() }
                    )
                }

                // Summary Dashboard (Daily vs Monthly)
                SummaryDashboard(
                    uiState = uiState,
                    onModeChange = { viewModel.onSummaryViewModeChanged(it) }
                )

                // Search & Filter Bar
                FilterBar(
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = { viewModel.onSearchQueryChanged(it) },
                    selectedGrade = uiState.gradeFilter,
                    onGradeChange = { viewModel.onGradeFilterChanged(it) },
                    selectedStatus = uiState.statusFilter,
                    onStatusChange = { viewModel.onStatusFilterChanged(it) }
                )

                // Student Attendance List
                if (attendanceItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(id = R.string.attendance_no_records_for_date),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(attendanceItems, key = { it.student.id }) { item ->
                            StudentAttendanceCard(
                                item = item,
                                timeFormatter = timeFormatter,
                                canCorrect = (uiState.currentUserRole == UserRole.OWNER),
                                onCheckIn = { status, notes ->
                                    viewModel.recordCheckIn(item.student.id, status, notes)
                                },
                                onCheckOut = {
                                    viewModel.recordCheckOut(item.student.id)
                                },
                                onCorrect = { record ->
                                    viewModel.openCorrectionDialog(record)
                                },
                                onHistory = {
                                    viewModel.openHistoryDialog(item.student)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Owner Correction Dialog
    if (uiState.isCorrectionDialogOpen && uiState.recordToCorrect != null) {
        AttendanceCorrectionDialog(
            record = uiState.recordToCorrect!!,
            reason = uiState.correctionReason,
            reasonError = uiState.correctionReasonError,
            status = uiState.correctionStatus,
            notes = uiState.correctionNotes,
            isSaving = uiState.isSavingCorrection,
            onReasonChange = { viewModel.onCorrectionReasonChanged(it) },
            onStatusChange = { viewModel.onCorrectionStatusChanged(it) },
            onNotesChange = { viewModel.onCorrectionNotesChanged(it) },
            onDismiss = { viewModel.closeCorrectionDialog() },
            onSubmit = { viewModel.submitCorrection() }
        )
    }

    // Student Attendance History Dialog
    if (uiState.isHistoryDialogOpen && uiState.selectedStudentForHistory != null) {
        StudentAttendanceHistoryDialog(
            student = uiState.selectedStudentForHistory!!,
            historyRecords = uiState.studentHistoryRecords,
            isLoading = uiState.isLoadingHistory,
            dateFormatter = dateFormatter,
            timeFormatter = timeFormatter,
            onDismiss = { viewModel.closeHistoryDialog() }
        )
    }
}

@Composable
private fun DateNavigationStrip(
    selectedDate: LocalDate,
    isToday: Boolean,
    dateFormatter: DateTimeFormatter,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Previous Day",
                    tint = BrandNavyPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable(onClick = onToday)
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = BrandTealPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = selectedDate.format(dateFormatter),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(BrandTealPrimary)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "TODAY",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            IconButton(
                onClick = onNext,
                enabled = !isToday
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next Day",
                    tint = if (!isToday) BrandNavyPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
private fun SummaryDashboard(
    uiState: StudentAttendanceUiState,
    onModeChange: (SummaryViewMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            TabRow(
                selectedTabIndex = if (uiState.summaryViewMode == SummaryViewMode.DAILY) 0 else 1,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = (uiState.summaryViewMode == SummaryViewMode.DAILY),
                    onClick = { onModeChange(SummaryViewMode.DAILY) },
                    text = { Text(stringResource(id = R.string.attendance_daily_summary)) }
                )
                Tab(
                    selected = (uiState.summaryViewMode == SummaryViewMode.MONTHLY),
                    onClick = { onModeChange(SummaryViewMode.MONTHLY) },
                    text = { Text(stringResource(id = R.string.attendance_monthly_summary)) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (uiState.summaryViewMode == SummaryViewMode.DAILY) {
                val daily = uiState.dailySummary
                if (daily != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SummaryMetricPill("Total", "${daily.totalActiveStudents}", MaterialTheme.colorScheme.onSurface)
                        SummaryMetricPill("Present", "${daily.presentCount}", StatusSuccess)
                        SummaryMetricPill("Late", "${daily.lateCount}", StatusWarning)
                        SummaryMetricPill("Excused", "${daily.excusedCount}", StatusInfo)
                        SummaryMetricPill("Absent", "${daily.absentCount}", StatusError)
                        SummaryMetricPill("Not Marked", "${daily.notRecordedCount}", Color.Gray)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Attendance Rate: ${daily.attendancePercentage}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandTealPrimary
                    )
                }
            } else {
                val monthly = uiState.monthlySummary
                if (monthly != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SummaryMetricPill("Sessions", "${monthly.totalSessions}", MaterialTheme.colorScheme.onSurface)
                        SummaryMetricPill("Present", "${monthly.presentCount}", StatusSuccess)
                        SummaryMetricPill("Late", "${monthly.lateCount}", StatusWarning)
                        SummaryMetricPill("Excused", "${monthly.excusedCount}", StatusInfo)
                        SummaryMetricPill("Absent", "${monthly.absentCount}", StatusError)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Monthly Avg Rate: ${monthly.averageAttendancePercentage}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandGoldPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricPill(
    label: String,
    count: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedGrade: String?,
    onGradeChange: (String?) -> Unit,
    selectedStatus: StudentAttendanceStatus?,
    onStatusChange: (StudentAttendanceStatus?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(id = R.string.action_search) + " student...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val grades = listOf("Class 8", "Class 9", "Class 10", "Class 11", "Class 12")
            item {
                FilterChip(
                    selected = selectedGrade == null,
                    onClick = { onGradeChange(null) },
                    label = { Text("All Grades") }
                )
            }
            items(grades) { grade ->
                FilterChip(
                    selected = (selectedGrade == grade),
                    onClick = { onGradeChange(grade) },
                    label = { Text(grade) }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedStatus == null,
                    onClick = { onStatusChange(null) },
                    label = { Text(stringResource(id = R.string.attendance_filter_all)) }
                )
            }
            items(StudentAttendanceStatus.values()) { status ->
                FilterChip(
                    selected = (selectedStatus == status),
                    onClick = { onStatusChange(status) },
                    label = { Text(status.name) }
                )
            }
        }
    }
}

@Composable
private fun StudentAttendanceCard(
    item: StudentAttendanceItem,
    timeFormatter: DateTimeFormatter,
    canCorrect: Boolean,
    onCheckIn: (status: StudentAttendanceStatus, notes: String?) -> Unit,
    onCheckOut: () -> Unit,
    onCorrect: (StudentAttendanceRecord) -> Unit,
    onHistory: () -> Unit
) {
    val record = item.attendance
    var checkInMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Avatar, Name & Grade, and Status Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(BrandNavyPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.student.fullName.take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Column {
                        Text(
                            text = item.student.fullName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BrandNavyPrimary
                        )
                        Text(
                            text = item.student.grade,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                StatusBadge(status = record?.status)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Attendance Timestamps & Duration Row
            if (record != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.attendance_arrival_time),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = record.checkInTime.format(timeFormatter),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(id = R.string.attendance_departure_time),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = record.checkOutTime?.format(timeFormatter) ?: "--:--",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Duration",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val duration = record.calculateCurrentDuration()
                        Text(
                            text = if (record.checkOutTime != null) "${duration}m" else "Active: ${duration}m",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (record.checkOutTime != null) BrandNavyPrimary else BrandTealPrimary
                        )
                    }
                }

                // If record was corrected by owner, show audit badge
                if (record.correctedBy != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Audited: Corrected by ${record.correctedByName ?: "Owner"} - \"${record.correctionReason}\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandGoldPrimary,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            } else {
                Text(
                    text = "No attendance recorded for this date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.canCheckIn) {
                    Box(modifier = Modifier.weight(1f)) {
                        Button(
                            onClick = { checkInMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !item.isProcessing,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
                        ) {
                            if (item.isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(id = R.string.action_check_in))
                            }
                        }

                        DropdownMenu(
                            expanded = checkInMenuExpanded,
                            onDismissRequest = { checkInMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.attendance_status_present)) },
                                onClick = {
                                    checkInMenuExpanded = false
                                    onCheckIn(StudentAttendanceStatus.PRESENT, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.attendance_status_late)) },
                                onClick = {
                                    checkInMenuExpanded = false
                                    onCheckIn(StudentAttendanceStatus.LATE, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.attendance_status_excused)) },
                                onClick = {
                                    checkInMenuExpanded = false
                                    onCheckIn(StudentAttendanceStatus.EXCUSED, "Excused with guardian permission")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.attendance_status_absent)) },
                                onClick = {
                                    checkInMenuExpanded = false
                                    onCheckIn(StudentAttendanceStatus.ABSENT, "Absent")
                                }
                            )
                        }
                    }
                } else if (item.canCheckOut) {
                    Button(
                        onClick = onCheckOut,
                        modifier = Modifier.weight(1f),
                        enabled = !item.isProcessing,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandNavyPrimary)
                    ) {
                        if (item.isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(id = R.string.action_check_out))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusSuccess)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Session Complete")
                    }
                }

                // History Button
                IconButton(onClick = onHistory) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "View History",
                        tint = BrandNavyPrimary
                    )
                }

                // Owner Correction Button
                if (canCorrect && record != null) {
                    IconButton(onClick = { onCorrect(record) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Owner Correct",
                            tint = BrandGoldPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: StudentAttendanceStatus?) {
    val (label, bg, fg) = when (status) {
        StudentAttendanceStatus.PRESENT -> Triple("PRESENT", StatusSuccess.copy(alpha = 0.15f), StatusSuccess)
        StudentAttendanceStatus.LATE -> Triple("LATE", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        StudentAttendanceStatus.HALF_DAY -> Triple("HALF DAY", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        StudentAttendanceStatus.EXCUSED -> Triple("EXCUSED", StatusInfo.copy(alpha = 0.15f), StatusInfo)
        StudentAttendanceStatus.ABSENT -> Triple("ABSENT", StatusError.copy(alpha = 0.15f), StatusError)
        null -> Triple("NOT MARKED", Color.Gray.copy(alpha = 0.15f), Color.DarkGray)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FeedbackBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) StatusError.copy(alpha = 0.12f) else StatusSuccess.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) StatusError else StatusSuccess,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = if (isError) StatusError else StatusSuccess,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AttendanceCorrectionDialog(
    record: StudentAttendanceRecord,
    reason: String,
    reasonError: String?,
    status: StudentAttendanceStatus,
    notes: String,
    isSaving: Boolean,
    onReasonChange: (String) -> Unit,
    onStatusChange: (StudentAttendanceStatus) -> Unit,
    onNotesChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Owner Correction: ${record.studentName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Select Corrected Status:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(StudentAttendanceStatus.values()) { itemStatus ->
                        FilterChip(
                            selected = (status == itemStatus),
                            onClick = { onStatusChange(itemStatus) },
                            label = { Text(itemStatus.name) }
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = onNotesChange,
                    label = { Text("Correction Notes (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Audit Reason * (Required)") },
                    isError = reasonError != null,
                    supportingText = reasonError?.let { { Text(it, color = StatusError) } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Student arrived at 9am, check-in delayed due to power cut.") },
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = BrandGoldPrimary)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                } else {
                    Text("Apply Correction")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun StudentAttendanceHistoryDialog(
    student: Student,
    historyRecords: List<StudentAttendanceRecord>,
    isLoading: Boolean,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "${student.fullName} - Attendance History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = student.grade,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandNavyPrimary)
                }
            } else if (historyRecords.isEmpty()) {
                Text(stringResource(id = R.string.attendance_history_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(historyRecords) { rec ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = rec.attendanceDate.format(dateFormatter),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    StatusBadge(rec.status)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "In: ${rec.checkInTime.format(timeFormatter)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "Out: ${rec.checkOutTime?.format(timeFormatter) ?: "--:--"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${rec.calculateCurrentDuration()}m",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (rec.correctionReason != null) {
                                    Text(
                                        text = "Audit: ${rec.correctionReason}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BrandGoldPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

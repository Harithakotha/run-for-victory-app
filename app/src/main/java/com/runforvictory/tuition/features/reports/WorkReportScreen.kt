package com.runforvictory.tuition.features.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runforvictory.tuition.R
import com.runforvictory.tuition.core.theme.BrandGoldPrimary
import com.runforvictory.tuition.core.theme.BrandNavyPrimary
import com.runforvictory.tuition.core.theme.BrandTealPrimary
import com.runforvictory.tuition.core.theme.StatusError
import com.runforvictory.tuition.core.theme.StatusSuccess
import com.runforvictory.tuition.core.theme.StatusWarning
import com.runforvictory.tuition.domain.DailyWorkReport
import com.runforvictory.tuition.domain.UserRole
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkReportScreen(
    viewModel: WorkReportViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.report_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandNavyPrimary,
                    titleContentColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Surface(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = BrandTealPrimary
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("Log New Report", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("Report History", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.History, contentDescription = null) }
                    )
                }

                if (selectedTabIndex == 0) {
                    CreateWorkReportForm(
                        uiState = uiState,
                        onStudentSelected = viewModel::onStudentSelected,
                        onSubjectChanged = viewModel::onSubjectChanged,
                        onWorkCompletedChanged = viewModel::onWorkCompletedChanged,
                        onHomeworkChanged = viewModel::onHomeworkChanged,
                        onPendingWorkChanged = viewModel::onPendingWorkChanged,
                        onProgressNotesChanged = viewModel::onProgressNotesChanged,
                        onSubmit = viewModel::submitReport
                    )
                } else {
                    WorkReportHistoryList(
                        uiState = uiState,
                        onFilterStudent = viewModel::onFilterStudent,
                        onFilterSubject = viewModel::onFilterSubject,
                        onClearFilters = viewModel::clearFilters,
                        onEditReport = viewModel::openEditDialog
                    )
                }
            }
        }
    }

    // Edit Report Dialog
    if (uiState.showEditDialog && uiState.editingReport != null) {
        EditWorkReportDialog(
            report = uiState.editingReport!!,
            subject = uiState.editSubject,
            workCompleted = uiState.editWorkCompleted,
            homework = uiState.editHomework,
            pendingWork = uiState.editPendingWork,
            progressNotes = uiState.editProgressNotes,
            reason = uiState.editReason,
            error = uiState.editError,
            onSubjectChange = viewModel::onEditSubjectChanged,
            onWorkCompletedChange = viewModel::onEditWorkCompletedChanged,
            onHomeworkChange = viewModel::onEditHomeworkChanged,
            onPendingWorkChange = viewModel::onEditPendingWorkChanged,
            onProgressNotesChange = viewModel::onEditProgressNotesChanged,
            onReasonChange = viewModel::onEditReasonChanged,
            onDismiss = viewModel::dismissEditDialog,
            onConfirm = viewModel::submitEdit
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateWorkReportForm(
    uiState: WorkReportUiState,
    onStudentSelected: (String) -> Unit,
    onSubjectChanged: (String) -> Unit,
    onWorkCompletedChanged: (String) -> Unit,
    onHomeworkChanged: (String) -> Unit,
    onPendingWorkChanged: (String) -> Unit,
    onProgressNotesChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    var studentDropdownExpanded by remember { mutableStateOf(false) }
    val selectedStudent = uiState.activeStudents.find { it.id == uiState.selectedStudentId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(id = R.string.report_select_student_subject),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )

                // Student Dropdown Picker
                ExposedDropdownMenuBox(
                    expanded = studentDropdownExpanded,
                    onExpandedChange = { studentDropdownExpanded = !studentDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedStudent?.let { "${it.fullName} (${it.grade})" } ?: stringResource(id = R.string.parent_children_selector),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(id = R.string.nav_students) + " *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = studentDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = studentDropdownExpanded,
                        onDismissRequest = { studentDropdownExpanded = false }
                    ) {
                        uiState.activeStudents.forEach { student ->
                            DropdownMenuItem(
                                text = { Text("${student.fullName} (${student.grade})") },
                                onClick = {
                                    onStudentSelected(student.id)
                                    studentDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = uiState.subject,
                    onValueChange = onSubjectChanged,
                    label = { Text(stringResource(id = R.string.report_subject_label) + " *") },
                    placeholder = { Text("e.g., Mathematics, Physics, Chemistry") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(id = R.string.report_academic_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )

                OutlinedTextField(
                    value = uiState.workCompleted,
                    onValueChange = onWorkCompletedChanged,
                    label = { Text(stringResource(id = R.string.report_work_completed_label) + " *") },
                    placeholder = { Text("Detailed topics, exercises, and concepts covered today") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.homework,
                    onValueChange = onHomeworkChanged,
                    label = { Text(stringResource(id = R.string.report_homework_label)) },
                    placeholder = { Text("Assigned exercises, reading, or problems for home") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.pendingWork,
                    onValueChange = onPendingWorkChanged,
                    label = { Text("Pending / Incomplete Work") },
                    placeholder = { Text("Any topics or exercises left unfinished for follow-up") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.progressNotes,
                    onValueChange = onProgressNotesChanged,
                    label = { Text(stringResource(id = R.string.report_progress_notes_label)) },
                    placeholder = { Text("Teacher remarks on comprehension, speed, and behaviour") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Button(
            onClick = onSubmit,
            enabled = !uiState.isSubmitting && uiState.selectedStudentId.isNotBlank() && uiState.subject.isNotBlank() && uiState.workCompleted.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Submit Work Report", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WorkReportHistoryList(
    uiState: WorkReportUiState,
    onFilterStudent: (String?) -> Unit,
    onFilterSubject: (String) -> Unit,
    onClearFilters: () -> Unit,
    onEditReport: (DailyWorkReport) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Filter bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uiState.filterSubject,
                onValueChange = onFilterSubject,
                placeholder = { Text("Search subject...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            if (uiState.filterSubject.isNotBlank() || uiState.filterStudentId != null) {
                IconButton(onClick = onClearFilters) {
                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear Filters")
                }
            }
        }

        // Student filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.filterStudentId == null,
                onClick = { onFilterStudent(null) },
                label = { Text("All Students") }
            )
            uiState.activeStudents.take(3).forEach { student ->
                FilterChip(
                    selected = uiState.filterStudentId == student.id,
                    onClick = { onFilterStudent(student.id) },
                    label = { Text(student.fullName.split(" ").first()) }
                )
            }
        }

        val reports = uiState.filteredReports

        if (reports.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(id = R.string.report_no_filtered_reports),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(reports, key = { it.id }) { report ->
                    val canEdit = uiState.currentUserRole == UserRole.OWNER ||
                            (uiState.currentUserRole == UserRole.TUTOR && report.tutorId == uiState.currentUserId)
                    WorkReportCard(
                        report = report,
                        canEdit = canEdit,
                        onEdit = { onEditReport(report) }
                    )
                }
            }
        }
    }
}

@Composable
fun WorkReportCard(
    report: DailyWorkReport,
    canEdit: Boolean = false,
    onEdit: () -> Unit = {}
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = report.studentName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandNavyPrimary
                    )
                    Text(
                        text = "${report.subject} • ${report.reportDate.format(dateFormatter)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandTealPrimary
                    )
                }

                if (report.isEdited) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = BrandGoldPrimary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = stringResource(id = R.string.report_edited_badge),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = BrandGoldPrimary
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Work Completed
            Column {
                Text(
                    text = stringResource(id = R.string.report_work_completed_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = report.workCompleted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandNavyPrimary
                )
            }

            // Homework Assigned
            report.homework?.let { hw ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = null,
                        tint = BrandTealPrimary,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.report_homework_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = BrandTealPrimary
                        )
                        Text(
                            text = hw,
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandNavyPrimary
                        )
                    }
                }
            }

            // Pending Work (if applicable)
            report.pendingWork?.let { pending ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.HourglassBottom,
                        contentDescription = null,
                        tint = StatusWarning,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.report_pending_work_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusWarning
                        )
                        Text(
                            text = pending,
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandNavyPrimary
                        )
                    }
                }
            }

            // Progress Notes
            report.progressNotes?.let { notes ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.report_progress_notes_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Audit footer & authoring tutor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.report_author_label, report.tutorName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (canEdit) {
                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(id = R.string.action_edit), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (report.isEdited && report.editReason != null) {
                Text(
                    text = stringResource(id = R.string.report_correction_reason_prefix, report.editReason, report.editedByName ?: stringResource(id = R.string.role_owner)),
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandGoldPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun EditWorkReportDialog(
    report: DailyWorkReport,
    subject: String,
    workCompleted: String,
    homework: String,
    pendingWork: String,
    progressNotes: String,
    reason: String,
    error: String?,
    onSubjectChange: (String) -> Unit,
    onWorkCompletedChange: (String) -> Unit,
    onHomeworkChange: (String) -> Unit,
    onPendingWorkChange: (String) -> Unit,
    onProgressNotesChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = R.string.report_edit_dialog_header, report.studentName),
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = subject,
                    onValueChange = onSubjectChange,
                    label = { Text("Subject *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = workCompleted,
                    onValueChange = onWorkCompletedChange,
                    label = { Text("Work Completed *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = homework,
                    onValueChange = onHomeworkChange,
                    label = { Text("Homework Assigned") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pendingWork,
                    onValueChange = onPendingWorkChange,
                    label = { Text("Pending Work") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = progressNotes,
                    onValueChange = onProgressNotesChange,
                    label = { Text("Progress Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Mandatory Edit Reason (min 5 chars) *") },
                    placeholder = { Text("e.g. Added homework details requested by student") },
                    minLines = 2,
                    isError = error != null,
                    supportingText = {
                        error?.let { Text(it, color = StatusError) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

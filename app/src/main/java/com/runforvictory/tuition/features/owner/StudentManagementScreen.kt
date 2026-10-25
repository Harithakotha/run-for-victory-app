package com.runforvictory.tuition.features.owner

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.runforvictory.tuition.R
import com.runforvictory.tuition.core.theme.BrandGoldPrimary
import com.runforvictory.tuition.core.theme.BrandNavyPrimary
import com.runforvictory.tuition.core.theme.BrandTealPrimary
import com.runforvictory.tuition.core.theme.StatusError
import com.runforvictory.tuition.core.theme.StatusSuccess
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.StudentStatus
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentManagementScreen(
    viewModel: StudentManagementViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val students by viewModel.students.collectAsState()

    val grades = listOf("All", "Class 6", "Class 7", "Class 8", "Class 9", "Class 10")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Student Directory",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(id = R.string.app_name) + " - " + stringResource(id = R.string.app_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = BrandGoldPrimary
                        )
                    }
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::openAddStudentDialog,
                containerColor = BrandTealPrimary,
                contentColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Student")
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Success / Error Notice Banners
                uiState.successMessage?.let { msg ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = msg, style = MaterialTheme.typography.bodySmall, color = StatusSuccess)
                        }
                    }
                }

                uiState.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = StatusError, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = error, style = MaterialTheme.typography.bodySmall, color = StatusError)
                        }
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    placeholder = { Text("Search by student or guardian name…") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = BrandTealPrimary)
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandTealPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Grade Filter Chips Row
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(grades) { grade ->
                        val isSelected = if (grade == "All") uiState.gradeFilter == null else uiState.gradeFilter == grade
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.onGradeFilterChanged(if (grade == "All") null else grade)
                            },
                            label = { Text(grade) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrandNavyPrimary,
                                selectedLabelColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Active vs Archived Segmented Tabs
                val selectedTabIndex = if (uiState.selectedStatus == StudentStatus.ACTIVE) 0 else 1
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = BrandNavyPrimary
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { viewModel.onStatusTabSelected(StudentStatus.ACTIVE) },
                        text = {
                            Text(
                                text = "Active Students",
                                fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { viewModel.onStatusTabSelected(StudentStatus.ARCHIVED) },
                        text = {
                            Text(
                                text = "Archived Students",
                                fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Student List
                if (students.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (uiState.selectedStatus == StudentStatus.ACTIVE)
                                    "No active students match your filters."
                                else
                                    "No archived students.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(students, key = { it.id }) { student ->
                            StudentDirectoryCard(
                                student = student,
                                onViewHistory = { viewModel.viewStudentHistory(student) },
                                onEdit = { viewModel.openEditStudentDialog(student) },
                                onArchive = { viewModel.openArchiveDialog(student) },
                                onRestore = { viewModel.openRestoreDialog(student) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Add / Edit Student Dialog
    if (uiState.isAddEditDialogOpen) {
        AddEditStudentDialog(
            isEditing = uiState.editingStudent != null,
            fullName = uiState.formFullName,
            fullNameError = uiState.formFullNameError,
            grade = uiState.formGrade,
            gradeError = uiState.formGradeError,
            guardianName = uiState.formGuardianName,
            guardianNameError = uiState.formGuardianNameError,
            guardianPhone = uiState.formGuardianPhone,
            guardianPhoneError = uiState.formGuardianPhoneError,
            contactPhone = uiState.formContactPhone,
            notes = uiState.formNotes,
            isSaving = uiState.isSaving,
            onFullNameChanged = viewModel::onFormFullNameChanged,
            onGradeChanged = viewModel::onFormGradeChanged,
            onGuardianNameChanged = viewModel::onFormGuardianNameChanged,
            onGuardianPhoneChanged = viewModel::onFormGuardianPhoneChanged,
            onContactPhoneChanged = viewModel::onFormContactPhoneChanged,
            onNotesChanged = viewModel::onFormNotesChanged,
            onSave = viewModel::saveStudent,
            onDismiss = viewModel::dismissAddEditDialog
        )
    }

    // Archive Confirmation Dialog
    if (uiState.isArchiveDialogOpen && uiState.studentToArchive != null) {
        val student = uiState.studentToArchive!!
        AlertDialog(
            onDismissRequest = viewModel::dismissArchiveDialog,
            title = {
                Text(
                    text = "Archive Student",
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Are you sure you want to archive ${student.fullName} (${student.grade})?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Note: Archived students are excluded from active attendance, but all historical attendance and work reports are preserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = uiState.archiveReason,
                        onValueChange = viewModel::onArchiveReasonChanged,
                        label = { Text("Reason for Archiving *") },
                        placeholder = { Text("e.g. Completed Class 10 board exams") },
                        isError = uiState.archiveReasonError != null,
                        supportingText = {
                            uiState.archiveReasonError?.let {
                                Text(text = it, color = StatusError)
                            }
                        },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmArchive,
                    enabled = !uiState.isArchiving,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    if (uiState.isArchiving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.surface)
                    } else {
                        Text("Archive Student")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = viewModel::dismissArchiveDialog) {
                    Text("Cancel")
                }
            }
        )
    }

    // Restore Confirmation Dialog
    if (uiState.isRestoreDialogOpen && uiState.studentToRestore != null) {
        val student = uiState.studentToRestore!!
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreDialog,
            title = {
                Text(
                    text = "Restore Student",
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )
            },
            text = {
                Text(
                    text = "Restore ${student.fullName} back to the active student directory? The student will immediately be available for daily attendance.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmRestore,
                    enabled = !uiState.isRestoring,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
                ) {
                    if (uiState.isRestoring) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.surface)
                    } else {
                        Text("Restore to Active")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = viewModel::dismissRestoreDialog) {
                    Text("Cancel")
                }
            }
        )
    }

    // View Student History Dialog
    if (uiState.isHistoryDialogOpen) {
        StudentHistoryDialog(
            history = uiState.selectedStudentHistory,
            isLoading = uiState.isLoadingHistory,
            onDismiss = viewModel::dismissHistoryDialog
        )
    }
}

@Composable
private fun StudentDirectoryCard(
    student: Student,
    onViewHistory: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewHistory),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(BrandNavyPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = student.fullName.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.surface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = student.fullName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BrandNavyPrimary
                        )
                        Text(
                            text = student.grade,
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandTealPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (student.status == StudentStatus.ACTIVE) StatusSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant
                ) {
                    Text(
                        text = student.status.name,
                        color = if (student.status == StudentStatus.ACTIVE) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Guardian and Contact Details
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = BrandTealPrimary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Guardian: ${student.guardianName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            student.guardianPhone?.let { phone ->
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = BrandTealPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = phone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Archival Context if archived
            if (student.status == StudentStatus.ARCHIVED) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Archived on ${student.archivedAt?.format(DateTimeFormatter.ofPattern("dd MMM yyyy")) ?: "N/A"} by ${student.archivedBy ?: "Owner"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        student.archivedReason?.let { reason ->
                            Text(
                                text = "Reason: $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onViewHistory) {
                    Icon(Icons.Default.History, contentDescription = "History", tint = BrandNavyPrimary)
                }

                if (student.status == StudentStatus.ACTIVE) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = BrandTealPrimary)
                    }
                    IconButton(onClick = onArchive) {
                        Icon(Icons.Default.Archive, contentDescription = "Archive", tint = StatusError)
                    }
                } else {
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Default.Restore, contentDescription = "Restore", tint = BrandTealPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddEditStudentDialog(
    isEditing: Boolean,
    fullName: String,
    fullNameError: String?,
    grade: String,
    gradeError: String?,
    guardianName: String,
    guardianNameError: String?,
    guardianPhone: String,
    guardianPhoneError: String?,
    contactPhone: String,
    notes: String,
    isSaving: Boolean,
    onFullNameChanged: (String) -> Unit,
    onGradeChanged: (String) -> Unit,
    onGuardianNameChanged: (String) -> Unit,
    onGuardianPhoneChanged: (String) -> Unit,
    onContactPhoneChanged: (String) -> Unit,
    onNotesChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) "Edit Student Profile" else "Add New Student",
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = onFullNameChanged,
                    label = { Text("Full Name *") },
                    isError = fullNameError != null,
                    supportingText = { fullNameError?.let { Text(it, color = StatusError) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = grade,
                    onValueChange = onGradeChanged,
                    label = { Text("Grade / Class *") },
                    placeholder = { Text("e.g. Class 10") },
                    isError = gradeError != null,
                    supportingText = { gradeError?.let { Text(it, color = StatusError) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = guardianName,
                    onValueChange = onGuardianNameChanged,
                    label = { Text("Guardian Name *") },
                    isError = guardianNameError != null,
                    supportingText = { guardianNameError?.let { Text(it, color = StatusError) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = guardianPhone,
                    onValueChange = onGuardianPhoneChanged,
                    label = { Text("Guardian Phone") },
                    isError = guardianPhoneError != null,
                    supportingText = { guardianPhoneError?.let { Text(it, color = StatusError) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = contactPhone,
                    onValueChange = onContactPhoneChanged,
                    label = { Text("Student Contact (Optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = notes,
                    onValueChange = onNotesChanged,
                    label = { Text("Academic Notes / Remarks") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = BrandNavyPrimary)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.surface)
                } else {
                    Text("Save Student")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun StudentHistoryDialog(
    history: com.runforvictory.tuition.domain.StudentHistory?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = history?.student?.fullName ?: "Student Records",
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
        },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandNavyPrimary)
                }
            } else if (history != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Profile") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Attendance (${history.attendanceRecords.size})") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Reports (${history.workLogs.size})") }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                        when (selectedTab) {
                            0 -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Grade: ${history.student.grade}", fontWeight = FontWeight.SemiBold)
                                    Text("Guardian: ${history.student.guardianName}")
                                    history.student.guardianPhone?.let { Text("Guardian Phone: $it") }
                                    history.student.contactPhone?.let { Text("Student Contact: $it") }
                                    Text("Status: ${history.student.status.name}")
                                    Text("Enrolled: ${history.student.createdAt.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))}")
                                    history.student.notes?.let { Text("Notes: $it") }
                                }
                            }
                            1 -> {
                                if (history.attendanceRecords.isEmpty()) {
                                    Text("No attendance records logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        history.attendanceRecords.forEach { att ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp)) {
                                                    Text(text = "Date: ${att.attendanceDate} - ${att.status.name}", fontWeight = FontWeight.Bold)
                                                    Text(text = "Time: ${att.checkInTime.format(DateTimeFormatter.ofPattern("HH:mm"))} to ${att.checkOutTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "Session in progress"}")
                                                    att.durationMinutes?.let { Text(text = "Duration: $it mins") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            2 -> {
                                if (history.workLogs.isEmpty()) {
                                    Text("No daily work reports recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        history.workLogs.forEach { log ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp)) {
                                                    Text(text = "${log.subject} - ${log.reportDate}", fontWeight = FontWeight.Bold, color = BrandTealPrimary)
                                                    Text(text = "Classwork: ${log.workCompleted}")
                                                    log.homework?.let { Text(text = "Homework: $it", fontWeight = FontWeight.SemiBold) }
                                                    log.progressNotes?.let { Text(text = "Notes: $it") }
                                                    Text(text = "By Teacher: ${log.tutorName}", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = BrandNavyPrimary)) {
                Text("Close")
            }
        }
    )
}

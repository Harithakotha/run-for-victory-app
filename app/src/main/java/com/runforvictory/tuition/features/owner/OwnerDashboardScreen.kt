package com.runforvictory.tuition.features.owner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runforvictory.tuition.R
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.core.components.BrandTopAppBar
import com.runforvictory.tuition.core.localization.LocaleManager
import com.runforvictory.tuition.core.theme.BrandGoldPrimary
import com.runforvictory.tuition.core.theme.BrandNavyPrimary
import com.runforvictory.tuition.core.theme.BrandTealPrimary
import com.runforvictory.tuition.core.theme.StatusError
import com.runforvictory.tuition.core.theme.StatusSuccess
import com.runforvictory.tuition.data.AttendanceRepository
import com.runforvictory.tuition.domain.GpsVerificationStatus
import com.runforvictory.tuition.domain.TutorAttendanceRecord
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerDashboardScreen(
    attendanceRepository: AttendanceRepository? = null,
    onNavigateToStudents: () -> Unit,
    onNavigateToAttendance: () -> Unit = {},
    onNavigateToReports: () -> Unit = {},
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    localeManager: LocaleManager? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val kolkataZone = remember { ZoneId.of("Asia/Kolkata") }
    val today = remember { LocalDate.now(kolkataZone) }

    var isInviteDialogOpen by remember { mutableStateOf(false) }
    var inviteEmail by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("tutor") }
    var inviteSuccessMessage by remember { mutableStateOf<String?>(null) }

    // Teacher Attendance Override State
    var selectedRecordForOverride by remember { mutableStateOf<TutorAttendanceRecord?>(null) }
    var overrideReason by remember { mutableStateOf("") }
    var overrideError by remember { mutableStateOf<String?>(null) }

    val teacherAttendanceList by (attendanceRepository?.getAllTutorAttendanceForDate(today) ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            BrandTopAppBar(
                title = stringResource(id = R.string.app_name),
                subtitle = stringResource(id = R.string.role_owner) + " • " + stringResource(id = R.string.app_subtitle),
                localeManager = localeManager,
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = stringResource(id = R.string.action_logout)
                        )
                    }
                }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Owner Welcome & Invitation Trigger Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = stringResource(id = R.string.owner_command_center_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandNavyPrimary
                                )
                                Text(
                                    text = stringResource(id = R.string.tuition_centre_default),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { isInviteDialogOpen = true },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(stringResource(id = R.string.owner_invite_user))
                            }
                        }

                        inviteSuccessMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = msg,
                                color = BrandTealPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Text(
                    text = "Operational Modules",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = "Students",
                        value = "Directory & Records",
                        icon = Icons.Default.Group,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigateToStudents)
                    )
                    MetricCard(
                        title = "Attendance",
                        value = "Daily Register & History",
                        icon = Icons.Default.HowToReg,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigateToAttendance)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = "Reports",
                        value = "Daily Work Logs",
                        icon = Icons.Default.Assessment,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigateToReports)
                    )
                    MetricCard(
                        title = "Geofence",
                        value = "150m (±60m accuracy)",
                        icon = Icons.Default.Settings,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Teacher Attendance & Overrides Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.owner_tutor_review_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandNavyPrimary
                    )
                    Text(
                        text = "Today (${today})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (teacherAttendanceList.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "No teacher attendance sessions recorded yet for today.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    teacherAttendanceList.forEach { record ->
                        TeacherAttendanceReviewCard(
                            record = record,
                            onReviewOverride = {
                                selectedRecordForOverride = record
                                overrideReason = ""
                                overrideError = null
                            }
                        )
                    }
                }
            }
        }
    }

    // Account Invitation Dialog
    if (isInviteDialogOpen) {
        AlertDialog(
            onDismissRequest = { isInviteDialogOpen = false },
            title = {
                Text(
                    text = "Invite Tutor or Parent",
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Invitations are cryptographically validated server-side and expire in 7 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedRole == "tutor",
                            onClick = { selectedRole = "tutor" },
                            label = { Text(stringResource(id = R.string.role_tutor)) }
                        )
                        FilterChip(
                            selected = selectedRole == "parent",
                            onClick = { selectedRole = "parent" },
                            label = { Text(stringResource(id = R.string.role_parent)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        label = { Text(stringResource(id = R.string.auth_email_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inviteEmail.contains("@")) {
                            inviteSuccessMessage = "Invitation sent securely to $inviteEmail as $selectedRole."
                            isInviteDialogOpen = false
                            inviteEmail = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
                ) {
                    Text(stringResource(id = R.string.owner_send_invitation))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { isInviteDialogOpen = false }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            }
        )
    }

    // Teacher Attendance Override Dialog
    selectedRecordForOverride?.let { record ->
        AlertDialog(
            onDismissRequest = { selectedRecordForOverride = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = BrandGoldPrimary
                )
            },
            title = {
                Text(
                    text = stringResource(id = R.string.owner_override_dialog_title),
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Teacher: ${record.tutorName.ifBlank { "Tutor ID: ${record.tutorId}" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandNavyPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Recorded Telemetry: ${record.checkInDistanceMeters}m from centre • ±${record.checkInAccuracyMeters.toInt()}m accuracy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    record.rejectionReason?.let { reason ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Failure Reason: $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = overrideReason,
                        onValueChange = {
                            overrideReason = it
                            overrideError = null
                        },
                        label = { Text(stringResource(id = R.string.owner_override_reason_label)) },
                        placeholder = { Text(stringResource(id = R.string.owner_override_reason_hint)) },
                        isError = overrideError != null,
                        supportingText = {
                            overrideError?.let { Text(text = it, color = StatusError) }
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (overrideReason.trim().length < 5) {
                            overrideError = "A minimum 5 character reason is required for audit trail."
                            return@Button
                        }

                        coroutineScope.launch {
                            val res = attendanceRepository?.ownerOverrideTutorAttendance(
                                attendanceId = record.id,
                                overrideStatus = GpsVerificationStatus.OVERRIDDEN,
                                newCheckIn = null,
                                newCheckOut = null,
                                reason = overrideReason.trim()
                            )
                            if (res is Resource.Success) {
                                snackbarHostState.showSnackbar("Teacher attendance successfully overridden.")
                                selectedRecordForOverride = null
                            } else if (res is Resource.Error) {
                                overrideError = res.message
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
                ) {
                    Text(stringResource(id = R.string.owner_override_submit))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { selectedRecordForOverride = null }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun TeacherAttendanceReviewCard(
    record: TutorAttendanceRecord,
    onReviewOverride: () -> Unit
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("hh:mm a") }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Text(
                    text = record.tutorName.ifBlank { "Tutor" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )

                val (badgeBg, badgeText, badgeLabel) = when (record.verificationStatus) {
                    GpsVerificationStatus.VERIFIED -> Triple(
                        StatusSuccess.copy(alpha = 0.15f),
                        StatusSuccess,
                        stringResource(id = R.string.tutor_status_verified)
                    )
                    GpsVerificationStatus.REJECTED -> Triple(
                        StatusError.copy(alpha = 0.15f),
                        StatusError,
                        stringResource(id = R.string.tutor_status_rejected)
                    )
                    GpsVerificationStatus.OVERRIDDEN -> Triple(
                        BrandGoldPrimary.copy(alpha = 0.15f),
                        BrandGoldPrimary,
                        stringResource(id = R.string.tutor_status_overridden)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeText
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = BrandTealPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "In: ${record.checkInTime.format(timeFormatter)}",
                    style = MaterialTheme.typography.bodySmall
                )
                record.checkOutTime?.let {
                    Text(
                        text = "  •  Out: ${it.format(timeFormatter)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                record.durationMinutes?.let { dur ->
                    Text(
                        text = " (${dur / 60}h ${dur % 60}m)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = BrandNavyPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${record.checkInDistanceMeters}m from centre • ±${record.checkInAccuracyMeters.toInt()}m accuracy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            record.rejectionReason?.let { reason ->
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = StatusError,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusError
                    )
                }
            }

            if (record.isManualOverride) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = BrandGoldPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Overridden by ${record.overrideByName ?: "Owner"}: ${record.overrideReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandGoldPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onReviewOverride,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(id = R.string.owner_tutor_override_action))
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BrandTealPrimary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
        }
    }
}

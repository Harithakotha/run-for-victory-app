package com.runforvictory.tuition.features.tutor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.runforvictory.tuition.R
import com.runforvictory.tuition.core.components.BrandTopAppBar
import com.runforvictory.tuition.core.localization.LocaleManager
import com.runforvictory.tuition.core.theme.BrandGoldPrimary
import com.runforvictory.tuition.core.theme.BrandNavyPrimary
import com.runforvictory.tuition.core.theme.BrandTealPrimary
import com.runforvictory.tuition.core.theme.StatusError
import com.runforvictory.tuition.core.theme.StatusWarning
import com.runforvictory.tuition.core.theme.StatusSuccess
import com.runforvictory.tuition.domain.GpsVerificationStatus
import com.runforvictory.tuition.domain.TutorAttendanceRecord
import com.runforvictory.tuition.domain.TutorMonthlySummary
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorDashboardScreen(
    viewModel: TutorAttendanceViewModel,
    onNavigateToAttendance: () -> Unit = {},
    onNavigateToReports: () -> Unit = {},
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    localeManager: LocaleManager? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Foreground location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

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
            BrandTopAppBar(
                title = stringResource(id = R.string.app_name),
                subtitle = stringResource(id = R.string.role_tutor) + " • " + uiState.tutorName.ifBlank { stringResource(id = R.string.app_subtitle) },
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Live GPS Attendance Clock-In / Clock-Out Card
                item {
                    TutorShiftCard(
                        activeSession = uiState.activeSession,
                        isLocating = uiState.isLocating,
                        onClockIn = { viewModel.onClockInRequested(hasLocationPermission) },
                        onClockOut = { viewModel.onClockOutRequested(hasLocationPermission) }
                    )
                }

                // 2. Monthly Summary Card
                uiState.monthlySummary?.let { summary ->
                    item {
                        TutorMonthlySummaryCard(summary = summary)
                    }
                }

                // 3. Quick Action Navigation (Student Attendance & Work Reports)
                item {
                    Text(
                        text = stringResource(id = R.string.tutor_quick_actions),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandNavyPrimary
                    )
                }

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToAttendance),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.HowToReg,
                                contentDescription = null,
                                tint = BrandTealPrimary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(id = R.string.attendance_register_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(id = R.string.tutor_action_mark_attendance_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToReports),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Assignment,
                                contentDescription = null,
                                tint = BrandGoldPrimary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(id = R.string.report_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(id = R.string.tutor_action_work_report_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 4. Attendance History Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(id = R.string.tutor_recent_history_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BrandNavyPrimary
                        )
                        Text(
                            text = stringResource(id = R.string.tutor_sessions_count, uiState.monthlyRecords.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 5. Attendance History Items
                if (uiState.monthlyRecords.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(id = R.string.tutor_empty_history),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.monthlyRecords, key = { it.id }) { record ->
                        TutorAttendanceHistoryItem(record = record)
                    }
                }

                // 6. Disclaimer
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.tutor_gps_disclaimer),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Permission Rationale Dialog
    if (uiState.showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { viewModel.onPermissionDenied() },
            title = {
                Text(
                    text = stringResource(id = R.string.location_permission_rationale_title),
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(id = R.string.location_permission_rationale_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.onPermissionRationaleConfirmed()
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
                ) {
                    Text(stringResource(id = R.string.action_grant_permission))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.onPermissionDenied() }) {
                    Text(stringResource(id = R.string.action_not_now))
                }
            }
        )
    }

    // GPS Disabled Dialog
    if (uiState.showGpsDisabledDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissGpsDisabledDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = StatusError
                )
            },
            title = {
                Text(
                    text = stringResource(id = R.string.gps_disabled_title),
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(id = R.string.gps_disabled_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissGpsDisabledDialog()
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
                ) {
                    Text(stringResource(id = R.string.action_open_settings))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissGpsDisabledDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TutorShiftCard(
    activeSession: TutorAttendanceRecord?,
    isLocating: Boolean,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit
) {
    val isClockedIn = activeSession != null
    val timeFormatter = remember { DateTimeFormatter.ofPattern("hh:mm a") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = if (isClockedIn) StatusSuccess else BrandTealPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = if (isClockedIn) stringResource(id = R.string.tutor_shift_active) else stringResource(id = R.string.tutor_shift_ready),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandNavyPrimary
                    )
                }

                // Status chip
                val (chipColor, chipText) = if (isClockedIn) {
                    Pair(StatusSuccess, "ACTIVE")
                } else {
                    Pair(BrandTealPrimary, "STANDBY")
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = chipColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = chipText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = chipColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (activeSession != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.tutor_check_in_at, activeSession.checkInTime.format(timeFormatter)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = BrandNavyPrimary
                        )
                        Text(
                            text = "${activeSession.checkInDistanceMeters}m from centre (±${activeSession.checkInAccuracyMeters.toInt()}m)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = BrandNavyPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val currentDuration = activeSession.calculateCurrentDuration()
                            val hours = currentDuration / 60
                            val mins = currentDuration % 60
                            Text(
                                text = "${hours}h ${mins}m",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = BrandNavyPrimary
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(id = R.string.tutor_proximity_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLocating) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = BrandTealPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(id = R.string.tutor_gps_verifying),
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrandTealPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onClockIn,
                        enabled = !isClockedIn,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandTealPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Login,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.action_clock_in))
                    }
                    Button(
                        onClick = onClockOut,
                        enabled = isClockedIn,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandNavyPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.action_clock_out))
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorMonthlySummaryCard(summary: TutorMonthlySummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.tutor_monthly_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryStat(title = stringResource(id = R.string.tutor_days_worked), value = "${summary.totalDaysWorked} days")
                SummaryStat(title = stringResource(id = R.string.tutor_total_hours), value = "${summary.totalHoursWorked} hrs")
                SummaryStat(title = stringResource(id = R.string.tutor_verified_sessions), value = "${summary.verifiedSessionsCount}")
                SummaryStat(title = stringResource(id = R.string.tutor_overridden_sessions), value = "${summary.overriddenSessionsCount}")
            }
        }
    }
}

@Composable
private fun SummaryStat(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = BrandNavyPrimary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TutorAttendanceHistoryItem(record: TutorAttendanceRecord) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("hh:mm a") }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.attendanceDate.format(dateFormatter),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )

                VerificationStatusBadge(status = record.verificationStatus)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )

                    record.checkOutTime?.let { outTime ->
                        Text(
                            text = "  •  Out: ${outTime.format(timeFormatter)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                record.durationMinutes?.let { dur ->
                    val hrs = dur / 60
                    val mins = dur % 60
                    Text(
                        text = "${hrs}h ${mins}m",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandNavyPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Proximity & Accuracy telemetry
            Text(
                text = "${record.checkInDistanceMeters}m from centre • ±${record.checkInAccuracyMeters.toInt()}m accuracy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Rejection reason (if rejected)
            if (record.verificationStatus == GpsVerificationStatus.REJECTED && record.rejectionReason != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = StatusError,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = record.rejectionReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusError
                    )
                }
            }

            // Manual owner override annotation (if overridden)
            if (record.isManualOverride) {
                Spacer(modifier = Modifier.height(6.dp))
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
                        fontWeight = FontWeight.Medium,
                        color = BrandGoldPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun VerificationStatusBadge(status: GpsVerificationStatus) {
    val (bgColor, textColor, label) = when (status) {
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
        color = bgColor
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

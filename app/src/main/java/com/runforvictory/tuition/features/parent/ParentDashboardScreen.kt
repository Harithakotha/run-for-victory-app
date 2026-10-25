package com.runforvictory.tuition.features.parent

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runforvictory.tuition.R
import com.runforvictory.tuition.core.theme.BrandGoldPrimary
import com.runforvictory.tuition.core.theme.BrandNavyPrimary
import com.runforvictory.tuition.core.theme.BrandTealPrimary
import com.runforvictory.tuition.core.theme.StatusError
import com.runforvictory.tuition.core.theme.StatusSuccess
import com.runforvictory.tuition.core.theme.StatusWarning
import com.runforvictory.tuition.domain.AppNotification
import com.runforvictory.tuition.domain.AttendanceMonthlySummary
import com.runforvictory.tuition.core.components.BrandTopAppBar
import com.runforvictory.tuition.core.localization.LocaleManager
import com.runforvictory.tuition.domain.DailyWorkReport
import com.runforvictory.tuition.domain.NotificationPreferences
import com.runforvictory.tuition.domain.NotificationType
import com.runforvictory.tuition.domain.ParentWeeklySummary
import com.runforvictory.tuition.domain.Student
import com.runforvictory.tuition.domain.StudentAttendanceRecord
import com.runforvictory.tuition.domain.StudentAttendanceStatus
import com.runforvictory.tuition.domain.StudentStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// Entry point
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDashboardScreen(
    viewModel: ParentDashboardViewModel,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    localeManager: LocaleManager? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val kolkataZone = remember { ZoneId.of("Asia/Kolkata") }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("hh:mm a") }

    // Show errors via snackbar
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            snackbarHostState.showSnackbar(uiState.errorMessage!!)
            viewModel.dismissError()
        }
    }

    // Session-expired redirect
    LaunchedEffect(uiState.sessionExpired) {
        if (uiState.sessionExpired) onSignOut()
    }

    Scaffold(
        topBar = {
            BrandTopAppBar(
                title = stringResource(R.string.app_name),
                subtitle = uiState.parentProfile?.let { p ->
                    p.fullName.substringBefore(" (").ifBlank { p.fullName }
                } ?: (stringResource(R.string.role_parent) + " • " + stringResource(R.string.app_subtitle)),
                localeManager = localeManager,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_retry),
                            tint = MaterialTheme.colorScheme.surface
                        )
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = stringResource(R.string.action_logout),
                            tint = MaterialTheme.colorScheme.surface
                        )
                    }
                }
            )
        },
        bottomBar = {
            ParentBottomNav(
                selectedTab = uiState.selectedTab,
                unreadCount = uiState.unreadNotificationCount,
                onTabSelected = { viewModel.selectTab(it) }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = if (uiState.isOffline) StatusWarning else StatusError,
                    contentColor = Color.White
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when {
                uiState.isLoading -> FullScreenLoader()
                else -> {
                    Column {
                        // Offline banner
                        if (uiState.isOffline) OfflineBanner { viewModel.refresh() }

                        // Child selector (always visible at top)
                        if (uiState.linkedChildren.isNotEmpty()) {
                            ChildSelectorRow(
                                children = uiState.linkedChildren,
                                selectedChildId = uiState.selectedChildId,
                                onChildSelected = { viewModel.selectChild(it) }
                            )
                        }

                        if (uiState.isChildDataLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = BrandTealPrimary
                            )
                        }

                        // Tab content
                        when (uiState.selectedTab) {
                            ParentTab.HOME -> HomeTab(
                                uiState = uiState,
                                dateFormatter = dateFormatter,
                                timeFormatter = timeFormatter,
                                kolkataZone = kolkataZone,
                                onLoadMoreReports = { viewModel.loadMoreReports() }
                            )
                            ParentTab.REPORTS -> ReportsTab(
                                reports = uiState.reports,
                                hasMore = uiState.hasMoreReports,
                                dateFormatter = dateFormatter,
                                timeFormatter = timeFormatter,
                                kolkataZone = kolkataZone,
                                onLoadMore = { viewModel.loadMoreReports() },
                                noChildren = !uiState.hasLinkedChildren
                            )
                            ParentTab.ATTENDANCE -> AttendanceTab(
                                history = uiState.attendanceHistory,
                                weekly = uiState.weeklySummary,
                                monthly = uiState.monthlySummary,
                                dateFormatter = dateFormatter,
                                timeFormatter = timeFormatter,
                                kolkataZone = kolkataZone,
                                noChildren = !uiState.hasLinkedChildren
                            )
                            ParentTab.NOTIFICATIONS -> NotificationsTab(
                                notifications = uiState.notifications,
                                onMarkRead = { viewModel.markNotificationRead(it) },
                                onMarkAllRead = { viewModel.markAllNotificationsRead() },
                                onRefresh = { viewModel.refreshNotifications() },
                                onOpenPreferences = { viewModel.setPreferencesDialogVisible(true) }
                            )
                        }
                    }
                }
            }

            if (uiState.isPreferencesDialogVisible && uiState.preferences != null) {
                NotificationPreferencesDialog(
                    preferences = uiState.preferences!!,
                    onDismiss = { viewModel.setPreferencesDialogVisible(false) },
                    onSave = { push, reports, attendance, announcements ->
                        viewModel.updatePreferences(push, reports, attendance, announcements)
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom navigation
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ParentBottomNav(
    selectedTab: ParentTab,
    unreadCount: Int,
    onTabSelected: (ParentTab) -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        val tabs = listOf(
            Triple(ParentTab.HOME, Icons.Default.Home, R.string.nav_home),
            Triple(ParentTab.REPORTS, Icons.AutoMirrored.Filled.Assignment, R.string.nav_reports),
            Triple(ParentTab.ATTENDANCE, Icons.Default.CalendarMonth, R.string.nav_attendance),
            Triple(ParentTab.NOTIFICATIONS, Icons.Default.Notifications, R.string.nav_notifications)
        )
        tabs.forEach { (tab, icon, labelRes) ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    if (tab == ParentTab.NOTIFICATIONS && unreadCount > 0) {
                        BadgedBox(badge = {
                            Badge { Text(text = if (unreadCount > 9) "9+" else unreadCount.toString()) }
                        }) {
                            Icon(imageVector = icon, contentDescription = null)
                        }
                    } else {
                        Icon(imageVector = icon, contentDescription = null)
                    }
                },
                label = { Text(stringResource(labelRes), maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BrandNavyPrimary,
                    selectedTextColor = BrandNavyPrimary,
                    indicatorColor = BrandGoldPrimary.copy(alpha = 0.15f)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Child selector
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ChildSelectorRow(
    children: List<Student>,
    selectedChildId: String?,
    onChildSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.parent_children_selector),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(children, key = { it.id }) { child ->
                FilterChip(
                    selected = selectedChildId == child.id,
                    onClick = { onChildSelected(child.id) },
                    label = {
                        Column {
                            Text(
                                text = child.fullName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = buildString {
                                    append(child.grade)
                                    if (child.status == StudentStatus.ARCHIVED) append(" · ${stringResource(R.string.student_archived_badge)}")
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandTealPrimary.copy(alpha = 0.18f),
                        selectedLabelColor = BrandNavyPrimary,
                        selectedLeadingIconColor = BrandNavyPrimary
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HOME TAB
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HomeTab(
    uiState: ParentDashboardUiState,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    kolkataZone: ZoneId,
    onLoadMoreReports: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        // No children linked
        if (!uiState.hasLinkedChildren) {
            item {
                NoChildrenCard()
            }
            return@LazyColumn
        }

        // Today's attendance card
        item {
            SectionHeader(
                icon = Icons.Default.CheckCircle,
                title = stringResource(R.string.parent_today_summary)
            )
        }
        item {
            TodayAttendanceCard(
                record = uiState.todayAttendance,
                childName = uiState.selectedChild?.fullName ?: "",
                dateFormatter = dateFormatter,
                timeFormatter = timeFormatter,
                kolkataZone = kolkataZone
            )
        }

        // Recent work reports
        item {
            SectionHeader(
                icon = Icons.AutoMirrored.Filled.Assignment,
                title = stringResource(R.string.parent_recent_reports)
            )
        }
        if (uiState.recentReports.isEmpty()) {
            item { EmptyStateCard(message = stringResource(R.string.parent_no_reports_yet)) }
        } else {
            items(uiState.recentReports, key = { it.id }) { report ->
                WorkReportCard(
                    report = report,
                    dateFormatter = dateFormatter,
                    timeFormatter = timeFormatter,
                    kolkataZone = kolkataZone
                )
            }
            if (uiState.hasMoreReports) {
                item {
                    OutlinedButton(
                        onClick = onLoadMoreReports,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_load_more_reports))
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REPORTS TAB
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReportsTab(
    reports: List<DailyWorkReport>,
    hasMore: Boolean,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    kolkataZone: ZoneId,
    onLoadMore: () -> Unit,
    noChildren: Boolean
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Text(
                text = stringResource(R.string.nav_reports),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
        }

        if (noChildren) {
            item { NoChildrenCard() }
        } else if (reports.isEmpty()) {
            item { EmptyStateCard(message = stringResource(R.string.parent_no_reports_yet)) }
        } else {
            items(reports, key = { it.id }) { report ->
                WorkReportCard(
                    report = report,
                    dateFormatter = dateFormatter,
                    timeFormatter = timeFormatter,
                    kolkataZone = kolkataZone
                )
            }
            if (hasMore) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandNavyPrimary)
                    ) {
                        Text(stringResource(R.string.action_load_more_reports))
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTENDANCE TAB
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AttendanceTab(
    history: List<StudentAttendanceRecord>,
    weekly: ParentWeeklySummary?,
    monthly: AttendanceMonthlySummary?,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    kolkataZone: ZoneId,
    noChildren: Boolean
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Text(
                text = stringResource(R.string.parent_attendance_calendar),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
        }

        if (noChildren) {
            item { NoChildrenCard() }
            return@LazyColumn
        }

        // Weekly summary
        if (weekly != null) {
            item { WeeklySummaryCard(summary = weekly) }
        }

        // Monthly summary
        if (monthly != null) {
            item { MonthlySummaryCard(summary = monthly) }
        }

        // Date-sorted history
        item {
            SectionHeader(
                icon = Icons.Default.CalendarMonth,
                title = stringResource(R.string.attendance_history_title)
            )
        }

        if (history.isEmpty()) {
            item { EmptyStateCard(message = stringResource(R.string.attendance_history_empty)) }
        } else {
            items(history, key = { it.id }) { record ->
                AttendanceHistoryCard(
                    record = record,
                    dateFormatter = dateFormatter,
                    timeFormatter = timeFormatter,
                    kolkataZone = kolkataZone
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NOTIFICATIONS TAB
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotificationsTab(
    notifications: List<AppNotification>,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPreferences: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.nav_notifications),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandNavyPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh_notifications),
                            tint = BrandNavyPrimary
                        )
                    }
                    IconButton(onClick = onOpenPreferences) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.action_notification_preferences),
                            tint = BrandNavyPrimary
                        )
                    }
                    if (notifications.any { !it.isRead }) {
                        OutlinedButton(
                            onClick = onMarkAllRead,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandNavyPrimary)
                        ) {
                            Text(
                                text = stringResource(R.string.action_mark_all_read),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        if (notifications.isEmpty()) {
            item { EmptyStateCard(message = stringResource(R.string.parent_no_notifications)) }
        } else {
            items(notifications, key = { it.id }) { notif ->
                NotificationCard(
                    notification = notif,
                    onTap = { if (!notif.isRead) onMarkRead(notif.id) }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun NotificationPreferencesDialog(
    preferences: NotificationPreferences,
    onDismiss: () -> Unit,
    onSave: (push: Boolean, reports: Boolean, attendance: Boolean, announcements: Boolean) -> Unit
) {
    var pushEnabled by remember { mutableStateOf(preferences.pushEnabled) }
    var reportsEnabled by remember { mutableStateOf(preferences.workReportsEnabled) }
    var attendanceEnabled by remember { mutableStateOf(preferences.attendanceEnabled) }
    var announcementsEnabled by remember { mutableStateOf(preferences.announcementsEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.notification_preferences_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PreferenceSwitchRow(
                    title = stringResource(R.string.notification_pref_push_enabled),
                    checked = pushEnabled,
                    onCheckedChange = { pushEnabled = it }
                )
                HorizontalDivider()
                PreferenceSwitchRow(
                    title = stringResource(R.string.notification_pref_work_reports),
                    checked = reportsEnabled && pushEnabled,
                    enabled = pushEnabled,
                    onCheckedChange = { reportsEnabled = it }
                )
                PreferenceSwitchRow(
                    title = stringResource(R.string.notification_pref_attendance),
                    checked = attendanceEnabled && pushEnabled,
                    enabled = pushEnabled,
                    onCheckedChange = { attendanceEnabled = it }
                )
                PreferenceSwitchRow(
                    title = stringResource(R.string.notification_pref_announcements),
                    checked = announcementsEnabled && pushEnabled,
                    enabled = pushEnabled,
                    onCheckedChange = { announcementsEnabled = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(pushEnabled, reportsEnabled, attendanceEnabled, announcementsEnabled)
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandNavyPrimary)
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable Cards
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TodayAttendanceCard(
    record: StudentAttendanceRecord?,
    childName: String,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    kolkataZone: ZoneId
) {
    val statusColor = when (record?.status) {
        StudentAttendanceStatus.PRESENT -> StatusSuccess
        StudentAttendanceStatus.LATE -> StatusWarning
        StudentAttendanceStatus.ABSENT -> StatusError
        StudentAttendanceStatus.EXCUSED -> MaterialTheme.colorScheme.secondary
        StudentAttendanceStatus.HALF_DAY -> BrandGoldPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (record?.status) {
        StudentAttendanceStatus.PRESENT -> stringResource(R.string.attendance_status_present)
        StudentAttendanceStatus.LATE -> stringResource(R.string.attendance_status_late)
        StudentAttendanceStatus.ABSENT -> stringResource(R.string.attendance_status_absent)
        StudentAttendanceStatus.EXCUSED -> stringResource(R.string.attendance_status_excused)
        StudentAttendanceStatus.HALF_DAY -> stringResource(R.string.attendance_status_half_day)
        else -> stringResource(R.string.attendance_status_not_recorded)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (childName.isNotBlank()) childName else stringResource(R.string.attendance_today_button),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val dateText = record?.attendanceDate?.format(dateFormatter)
                        ?: java.time.LocalDate.now(kolkataZone).format(dateFormatter)
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Status badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (record != null) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                // Arrival
                val arrivalIst = record.checkInTime.withZoneSameInstant(kolkataZone)
                AttendanceTimeRow(
                    label = stringResource(R.string.attendance_arrival_time),
                    time = arrivalIst.format(timeFormatter) + " IST",
                    color = StatusSuccess
                )

                // Departure — show missing without fabricating a time
                val departureIst = record.checkOutTime?.withZoneSameInstant(kolkataZone)
                Spacer(modifier = Modifier.height(6.dp))
                AttendanceTimeRow(
                    label = stringResource(R.string.attendance_departure_time),
                    time = if (departureIst != null)
                        departureIst.format(timeFormatter) + " IST"
                    else
                        stringResource(R.string.attendance_checkout_pending),
                    color = if (departureIst != null) StatusError else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Duration
                if (record.durationMinutes != null && record.durationMinutes > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.attendance_duration, record.durationMinutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Notes
                record.notes?.let { notes ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.attendance_no_records_for_date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AttendanceTimeRow(label: String, time: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun AttendanceHistoryCard(
    record: StudentAttendanceRecord,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    kolkataZone: ZoneId
) {
    val statusColor = when (record.status) {
        StudentAttendanceStatus.PRESENT -> StatusSuccess
        StudentAttendanceStatus.LATE -> StatusWarning
        StudentAttendanceStatus.ABSENT -> StatusError
        StudentAttendanceStatus.EXCUSED -> MaterialTheme.colorScheme.secondary
        StudentAttendanceStatus.HALF_DAY -> BrandGoldPrimary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Date
            Column(modifier = Modifier.weight(1.2f)) {
                Text(
                    text = record.attendanceDate.format(dateFormatter),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val arrival = record.checkInTime.withZoneSameInstant(kolkataZone)
                val departure = record.checkOutTime?.withZoneSameInstant(kolkataZone)
                Text(
                    text = "${stringResource(R.string.attendance_arrival_time)}: ${arrival.format(timeFormatter)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (departure != null) {
                    Text(
                        text = "${stringResource(R.string.attendance_departure_time)}: ${departure.format(timeFormatter)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.attendance_checkout_pending),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Duration
            record.durationMinutes?.let { mins ->
                Text(
                    text = "${mins / 60}h ${mins % 60}m",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.8f)
                )
            }
            // Status pill
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = statusColor.copy(alpha = 0.14f),
                modifier = Modifier.weight(0.9f)
            ) {
                Text(
                    text = record.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun WeeklySummaryCard(summary: ParentWeeklySummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = BrandTealPrimary.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.attendance_weekly_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
            Text(
                text = summary.weekLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryStatItem(label = stringResource(R.string.attendance_status_present), value = summary.presentDays.toString(), color = StatusSuccess)
                SummaryStatItem(label = stringResource(R.string.attendance_status_absent), value = summary.absentDays.toString(), color = StatusError)
                SummaryStatItem(label = stringResource(R.string.attendance_status_late), value = summary.lateDays.toString(), color = StatusWarning)
                SummaryStatItem(label = stringResource(R.string.parent_attendance_rate), value = "${summary.attendanceRate.toInt()}%", color = BrandNavyPrimary)
            }
        }
    }
}

@Composable
private fun MonthlySummaryCard(summary: AttendanceMonthlySummary) {
    val monthName = java.time.Month.of(summary.month).name.lowercase().replaceFirstChar { it.uppercase() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = BrandNavyPrimary.copy(alpha = 0.06f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "$monthName ${summary.year} — ${stringResource(R.string.attendance_monthly_summary)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryStatItem(label = stringResource(R.string.attendance_status_present), value = summary.presentCount.toString(), color = StatusSuccess)
                SummaryStatItem(label = stringResource(R.string.attendance_status_absent), value = summary.absentCount.toString(), color = StatusError)
                SummaryStatItem(label = stringResource(R.string.attendance_status_late), value = summary.lateCount.toString(), color = StatusWarning)
                SummaryStatItem(label = stringResource(R.string.attendance_status_excused), value = summary.excusedCount.toString(), color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.attendance_rate_label, summary.averageAttendancePercentage),
                style = MaterialTheme.typography.labelSmall,
                color = BrandNavyPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SummaryStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
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
private fun WorkReportCard(
    report: DailyWorkReport,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    kolkataZone: ZoneId
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Subject + Date header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = null,
                        tint = BrandGoldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = report.subject,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = BrandNavyPrimary
                    )
                }
                Text(
                    text = report.reportDate.format(dateFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${stringResource(R.string.report_author_label_prefix)}${report.tutorName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Work completed
            ReportDetailRow(
                label = stringResource(R.string.report_work_completed_label),
                body = report.workCompleted
            )

            // Homework
            report.homework?.let { hw ->
                Spacer(modifier = Modifier.height(6.dp))
                ReportDetailRow(label = stringResource(R.string.report_homework_label), body = hw)
            }

            // Pending work
            report.pendingWork?.let { pw ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.HourglassBottom,
                        contentDescription = null,
                        tint = StatusWarning,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.report_pending_work_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusWarning,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = pw,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Teacher notes
            report.progressNotes?.let { notes ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = BrandTealPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.report_progress_notes_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandTealPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Submission timestamp
            val createdIst = report.createdAt.withZoneSameInstant(kolkataZone)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.parent_submitted_at, createdIst.format(timeFormatter)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            // Edited badge
            if (report.isEdited) {
                Text(
                    text = stringResource(R.string.parent_edited_by, report.editedByName ?: stringResource(R.string.role_tutor)),
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandGoldPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ReportDetailRow(label: String, body: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NotificationCard(
    notification: AppNotification,
    onTap: () -> Unit
) {
    val (icon, tint) = when (notification.type) {
        NotificationType.ATTENDANCE_CHECK_IN -> Pair(Icons.Default.CheckCircle, StatusSuccess)
        NotificationType.ATTENDANCE_CHECK_OUT -> Pair(Icons.Default.Person, BrandNavyPrimary)
        NotificationType.WORK_REPORT_ADDED -> Pair(Icons.AutoMirrored.Filled.Assignment, BrandGoldPrimary)
        NotificationType.WORK_REPORT_EDITED -> Pair(Icons.AutoMirrored.Filled.Assignment, StatusWarning)
        NotificationType.CENTRE_ANNOUNCEMENT -> Pair(Icons.Default.Info, BrandTealPrimary)
        NotificationType.INVITATION_SENT -> Pair(Icons.Default.Person, BrandGoldPrimary)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!notification.isRead)
                BrandNavyPrimary.copy(alpha = 0.06f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (!notification.isRead) 2.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Unread dot
            if (!notification.isRead) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(BrandNavyPrimary)
                        .align(Alignment.CenterVertically)
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Spacer(modifier = Modifier.width(16.dp))
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!notification.isRead) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = notification.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared / utility composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = BrandNavyPrimary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = BrandNavyPrimary
        )
    }
}

@Composable
private fun EmptyStateCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Assignment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun NoChildrenCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = StatusWarning.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = StatusWarning
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.parent_no_children_linked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Surface(color = StatusWarning) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.error_network_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_retry),
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusWarning
                )
            }
        }
    }
}

@Composable
private fun FullScreenLoader() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BrandTealPrimary)
    }
}

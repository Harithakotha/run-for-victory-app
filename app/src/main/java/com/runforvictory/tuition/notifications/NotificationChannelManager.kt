package com.runforvictory.tuition.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannelManager {
    const val CHANNEL_DAILY_REPORTS = "channel_daily_reports"
    const val CHANNEL_ATTENDANCE = "channel_attendance"
    const val CHANNEL_ANNOUNCEMENTS = "channel_announcements"

    fun setupNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val dailyReportsChannel = NotificationChannel(
                CHANNEL_DAILY_REPORTS,
                "Daily Work Reports",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for student classwork and homework updates."
                enableLights(true)
                enableVibration(true)
            }

            val attendanceChannel = NotificationChannel(
                CHANNEL_ATTENDANCE,
                "Attendance Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for student arrival, departure, and attendance status."
            }

            val announcementsChannel = NotificationChannel(
                CHANNEL_ANNOUNCEMENTS,
                "Announcements & Notices",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General notices and holiday announcements from the centre owner."
            }

            notificationManager.createNotificationChannels(
                listOf(dailyReportsChannel, attendanceChannel, announcementsChannel)
            )
        }
    }
}

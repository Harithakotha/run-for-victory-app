package com.runforvictory.tuition.notifications

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.runforvictory.tuition.R
import com.runforvictory.tuition.app.MainActivity
import com.runforvictory.tuition.domain.NotificationType
import com.runforvictory.tuition.domain.PushPayload

object PushNotificationManager {

    /**
     * Checks if the app has permission to post notifications.
     * On Android 13+ (API 33), requires POST_NOTIFICATIONS runtime permission.
     * On API < 33, notifications are enabled by default unless disabled in OS settings.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Maps the business notification type to the appropriate high or default priority channel.
     */
    fun resolveChannelId(type: NotificationType): String {
        return when (type) {
            NotificationType.WORK_REPORT_ADDED,
            NotificationType.WORK_REPORT_EDITED -> NotificationChannelManager.CHANNEL_DAILY_REPORTS
            NotificationType.ATTENDANCE_CHECK_IN,
            NotificationType.ATTENDANCE_CHECK_OUT -> NotificationChannelManager.CHANNEL_ATTENDANCE
            NotificationType.INVITATION_SENT,
            NotificationType.CENTRE_ANNOUNCEMENT -> NotificationChannelManager.CHANNEL_ANNOUNCEMENTS
        }
    }

    /**
     * Builds and posts a privacy-preserving Android notification.
     *
     * Privacy Requirement:
     * When the device is locked, sensitive student details (name, grade, specific classwork)
     * are redacted from the lock-screen by attaching a public version (setPublicVersion)
     * with generic text ("Run for Victory Update: New tuition update available").
     * When unlocked, the full details are displayed.
     *
     * Permission Requirement:
     * If POST_NOTIFICATIONS is not granted, this function returns false without crashing.
     */
    fun postNotification(
        context: Context,
        payload: PushPayload,
        notificationId: Int = (payload.notificationId.hashCode() and 0x7FFFFFFF)
    ): Boolean {
        if (!hasNotificationPermission(context)) {
            // Permission denied: gracefully return false; in-app notification remains stored
            return false
        }

        val channelId = resolveChannelId(payload.type)

        // PendingIntent to launch MainActivity
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notification_id", payload.notificationId)
            putExtra("notification_type", payload.type.name)
            for ((key, value) in payload.data) {
                putExtra(key, value)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // 1. Redacted Public Notification for Lock Screen (Privacy-Safe)
        val publicNotification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_run_for_victory_logo)
            .setContentTitle(payload.publicTitle)
            .setContentText(payload.publicBody)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // 2. Full Private Notification (Visible only when unlocked or in private viewer)
        val privateNotification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_run_for_victory_logo)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, privateNotification)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}

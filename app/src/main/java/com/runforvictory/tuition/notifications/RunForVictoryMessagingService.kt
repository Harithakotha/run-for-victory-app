package com.runforvictory.tuition.notifications

import android.content.Context
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.data.NotificationRepository
import com.runforvictory.tuition.domain.AppNotification
import com.runforvictory.tuition.domain.NotificationDeliveryResult
import com.runforvictory.tuition.domain.NotificationType
import com.runforvictory.tuition.domain.PushPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Service for handling Android Push Notifications (Firebase Cloud Messaging).
 *
 * Designed to operate whether the app is in foreground, background, or closed:
 * - When in background or closed, system notifications are posted to the notification tray
 *   using privacy-preserving lock-screen text.
 * - Handles token rotation and invalid token cleanup.
 * - Enforces recipient preferences before displaying push alerts.
 */
class RunForVictoryMessagingService(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /**
     * Called when FCM issues a new or rotated registration token.
     * The token is safely updated in the backend repository.
     */
    fun onNewToken(
        userId: String,
        newToken: String,
        notificationRepository: NotificationRepository,
        deviceInfo: String? = "Android SDK ${android.os.Build.VERSION.SDK_INT}"
    ) {
        scope.launch {
            notificationRepository.registerDeviceToken(
                userId = userId,
                token = newToken,
                deviceInfo = deviceInfo
            )
        }
    }

    /**
     * Processes an incoming push payload.
     *
     * 1. Checks user preferences (if push or specific event type is disabled, drops push).
     * 2. Checks system notification permission (if denied, suppresses tray notification).
     * 3. Posts privacy-safe notification to system notification tray.
     */
    suspend fun onMessageReceived(
        context: Context,
        payload: PushPayload,
        recipientId: String,
        notificationRepository: NotificationRepository
    ): NotificationDeliveryResult {
        // 1. Check user notification preferences
        val prefRes = notificationRepository.getPreferences(recipientId)
        if (prefRes is Resource.Success) {
            val pref = prefRes.data
            if (!pref.pushEnabled) {
                return NotificationDeliveryResult(
                    success = true,
                    inAppStored = true,
                    pushDispatched = false,
                    failureReason = "Suppressed by pushEnabled preference"
                )
            }
            when (payload.type) {
                NotificationType.WORK_REPORT_ADDED,
                NotificationType.WORK_REPORT_EDITED -> {
                    if (!pref.workReportsEnabled) {
                        return NotificationDeliveryResult(
                            success = true,
                            inAppStored = true,
                            pushDispatched = false,
                            failureReason = "Suppressed by workReportsEnabled preference"
                        )
                    }
                }
                NotificationType.ATTENDANCE_CHECK_IN,
                NotificationType.ATTENDANCE_CHECK_OUT -> {
                    if (!pref.attendanceEnabled) {
                        return NotificationDeliveryResult(
                            success = true,
                            inAppStored = true,
                            pushDispatched = false,
                            failureReason = "Suppressed by attendanceEnabled preference"
                        )
                    }
                }
                NotificationType.CENTRE_ANNOUNCEMENT,
                NotificationType.INVITATION_SENT -> {
                    if (!pref.announcementsEnabled) {
                        return NotificationDeliveryResult(
                            success = true,
                            inAppStored = true,
                            pushDispatched = false,
                            failureReason = "Suppressed by announcementsEnabled preference"
                        )
                    }
                }
            }
        }

        // 2. Check if notification permission is granted
        val hasPermission = PushNotificationManager.hasNotificationPermission(context)
        if (!hasPermission) {
            // Permission denied: do not attempt tray notification, but return inAppStored = true
            return NotificationDeliveryResult(
                success = false,
                inAppStored = true,
                pushDispatched = false,
                failureReason = "Permission denied: POST_NOTIFICATIONS not granted"
            )
        }

        // 3. Post to system notification tray
        val posted = PushNotificationManager.postNotification(context, payload)
        return NotificationDeliveryResult(
            success = posted,
            inAppStored = true,
            pushDispatched = posted,
            messageId = if (posted) "fcm-${UUID.randomUUID()}" else null,
            failureReason = if (!posted) "Failed to post notification to system tray" else null
        )
    }

    companion object {
        /**
         * Parses raw FCM data payload map into domain PushPayload.
         */
        fun parseFromDataMap(data: Map<String, String>): PushPayload {
            val typeStr = data["type"] ?: NotificationType.CENTRE_ANNOUNCEMENT.name
            val type = try {
                NotificationType.valueOf(typeStr)
            } catch (e: Exception) {
                NotificationType.CENTRE_ANNOUNCEMENT
            }

            return PushPayload(
                notificationId = data["notification_id"] ?: UUID.randomUUID().toString(),
                type = type,
                title = data["title"] ?: "Run for Victory Update",
                body = data["body"] ?: "A new update is available in your app.",
                publicTitle = data["public_title"] ?: "Run for Victory Update",
                publicBody = data["public_body"] ?: "New tuition update available.",
                data = data,
                idempotencyKey = data["idempotency_key"]
            )
        }
    }
}

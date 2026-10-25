# Firebase Cloud Messaging (FCM) Setup & Troubleshooting Guide
## Run for Victory — Tuition Management Application
**Subtitle:** By Ravi Kumar  
**Package:** `com.runforvictory.tuition`  
**Primary Timezone:** `Asia/Kolkata` (IST)

---

## 1. Architecture Overview

Run for Victory implements a dual notification architecture that guarantees reliability, security, and privacy:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             BACKEND / SUPABASE                             │
│                                                                             │
│  Tutor creates Report / Attendance update                                    │
│       │                                                                     │
│       ├──► 1. Persistent In-App Notification (public.app_notifications)     │
│       │       (Stored INDEPENDENTLY of push delivery; guaranteed delivery)  │
│       │                                                                     │
│       └──► 2. Outbound Push Queue (public.outbound_push_queue)              │
│               (Evaluates recipient preferences & idempotency key)            │
│                       │                                                     │
│                       ▼                                                     │
│            FCM v1 HTTP REST Endpoint                                        │
│            (Authenticated via Server-Side Service Account Vault)            │
└───────────────────────┬─────────────────────────────────────────────────────┘
                        │ Push Payload (Encrypted over TLS)
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ANDROID CLIENT (KOTLIN)                           │
│                                                                             │
│  RunForVictoryMessagingService                                              │
│       │                                                                     │
│       ├──► In-App Notification Store (Real-time Flow / Refresh mechanism)   │
│       │                                                                     │
│       └──► System Tray Notification (PushNotificationManager)               │
│               ├── Unlocked View: Detailed subject, work, and tutor note     │
│               └── Locked View (setPublicVersion): Generic, privacy-safe text│
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Security Invariants

1. **Zero Client Secrets**:
   - Neither Firebase Admin SDK credentials nor Supabase service-role keys are EVER bundled in Android assets or the APK.
   - All push dispatches originate from trusted server-side functions / edge functions.
2. **Strict Parent-Child Authorization**:
   - Notifications are dispatched only to parent accounts linked in `public.parent_children`.
   - Client requests supplying arbitrary student IDs are rejected server-side.
3. **Lock-Screen Privacy Redaction**:
   - When a parent's phone is locked, notifications use `NotificationCompat.VISIBILITY_PRIVATE` and attach a public version (`setPublicVersion`) with generic text:
     - Public Title: `"Run for Victory Update"`
     - Public Body: `"You have a new update from your tuition centre."`
   - Student names, class levels, subjects, homework, or progress notes are NEVER visible on a locked screen.
4. **Resilient Decoupling**:
   - A failure in FCM delivery or an invalid token will **NEVER** abort or roll back a saved daily work report or attendance record.
5. **No Secret Leaks in Logs**:
   - Device tokens are masked (e.g. `fcm_...7890`) in audit logs and error messages.

---

## 3. Firebase Console Configuration

### Step 1: Create or Select Firebase Project
1. Open the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add project** and name it `Run for Victory Tuition`.
3. Disable Google Analytics (or enable if required for production telemetry).

### Step 2: Register Android Application
1. In Project Overview, click the Android icon to add an app.
2. **Android package name**: `com.runforvictory.tuition` (must match `build.gradle.kts` exactly).
3. **App nickname**: `Run for Victory Android`.
4. **Debug signing certificate SHA-1**:
   - Run in terminal:
     ```bash
     keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
     ```
   - Copy the SHA-1 and SHA-256 fingerprints into the Firebase Console.
5. Download `google-services.json` and place it in `app/google-services.json`.

### Step 3: Generate Service Account Key (Server-Side Only)
1. Go to **Project Settings** > **Service accounts**.
2. Under **Firebase Admin SDK**, select **Node.js** or **Go**.
3. Click **Generate new private key** and download the JSON file.
4. **CRITICAL**: Store this JSON in your Supabase Vault or backend environment variables:
   - Secret Name: `FIREBASE_SERVICE_ACCOUNT_KEY`
   - Never commit this file to Git or add it to `app/src/main/assets/`.

---

## 4. Server-Side Push Dispatch Implementation

### FCM v1 HTTP API Endpoint
- **URL**: `https://fcm.googleapis.com/v1/projects/{project-id}/messages:send`
- **Method**: `POST`
- **Headers**:
  ```http
  Authorization: Bearer <OAUTH2_ACCESS_TOKEN>
  Content-Type: application/json; UTF-8
  ```

### Standardized Payload Structure

```json
{
  "message": {
    "token": "fcm_device_token_here",
    "notification": {
      "title": "New Work Report: Rahul Reddy (Mathematics)",
      "body": "Completed Quadratic Equations exercises 4.1 and 4.2."
    },
    "data": {
      "notification_id": "8fa1119b-6dbb-449e-b125-9df083818e69",
      "type": "WORK_REPORT",
      "student_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      "public_title": "Run for Victory Update",
      "public_body": "A new daily learning report is available."
    },
    "android": {
      "priority": "HIGH",
      "notification": {
        "channel_id": "channel_daily_reports",
        "visibility": "PRIVATE",
        "icon": "ic_run_for_victory_logo",
        "color": "#0B192C"
      }
    }
  }
}
```

---

## 5. Client Android Setup & Permissions

### Android 13+ (API 33) Runtime Permission
In `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
The app checks runtime permission using `PushNotificationManager.hasNotificationPermission(context)`:
- If granted: Posts high-priority notification to system tray.
- If denied: Suppresses system tray alert gracefully; in-app notification remains stored and visible in the Parent Dashboard.

### Notification Channels (API 26+)
Initialized automatically on app start in `RunForVictoryApp.kt`:
1. `channel_daily_reports` (Importance: High) — Vibration and sound enabled for daily reports.
2. `channel_attendance` (Importance: Default) — Arrival and departure updates.
3. `channel_announcements` (Importance: Default) — Tuition center announcements and invitations.

---

## 6. Troubleshooting Guide

| Symptom | Probable Cause | Diagnostic & Resolution |
| :--- | :--- | :--- |
| **`UNREGISTERED` error from FCM** | Device token has expired or app was uninstalled | The server automatically invalidates the token in `public.device_tokens`. When the user next opens the app, `onNewToken` issues a new token and re-registers it. |
| **Parent does not receive push alerts** | 1. User disabled push in Notification Preferences.<br>2. Android system permission denied.<br>3. Device in aggressive battery saver mode. | 1. Check Parent Dashboard > Notification Settings (gear icon) to ensure switches are ON.<br>2. Check Android Settings > Apps > Run for Victory > Notifications.<br>3. Check battery optimization: exempt Run for Victory from deep sleep. |
| **Notification arrives but text is hidden on lock screen** | Expected Privacy Feature | When phone is locked, generic public text is shown by design. Unlock the device with fingerprint/PIN to view full student details. |
| **Duplicate notifications on poor connectivity** | Network retry re-sending request | Verify `idempotency_key` is passed with the request. The database and client cache automatically drop duplicate messages with the same idempotency key. |
| **Push fails with `401 Unauthorized` on server** | Service account OAuth2 token expired or clock drift | Ensure server clock is synchronized with NTP (`Asia/Kolkata` / UTC). Refresh OAuth2 token using `google-auth-library`. |
| **Notifications not refreshing in app** | Flow not active | Tap the Refresh icon (top-right of Notifications tab) to trigger an immediate sync with `public.app_notifications`. |

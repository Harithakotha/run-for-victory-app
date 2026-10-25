# Known Limitations
**Project:** Run for Victory by Ravi Kumar  
**Document Version:** 1.0.0  
**Last Updated:** October 25, 2026  

---

## 1. Local Testing Environment & Android Device Connectivity

### Limitation
The local development environment operates headlessly without an active, attached physical Android device or running Android emulator (`adb devices` reports 0 connected targets).

### Mitigation & Impact
- Android instrumentation and UI tests are fully structured and compiled into `app/src/androidTest/java/com/runforvictory/tuition/CriticalWorkflowInstrumentationTest.kt` (`compileDebugAndroidTestKotlin` passes with 0 errors).
- All critical user workflows (Owner authentication, Tutor clock-in/out, Student attendance, Daily work reports, Parent data isolation, Session expiry) are backed by hermetic JVM integration test suites in `ReliabilityAuditTest.kt`.
- Continuous Integration (CI) runners equipped with Android Emulators (e.g. GitHub Actions `reactivecircus/android-emulator-runner`) should be utilized for final physical touch-event validation.

---

## 2. In-Memory Idempotency Cache vs. Long-Term Offline Queue

### Limitation
Client-side idempotency keys for student attendance check-in/out, work reports, and tutor clock-in are tracked using thread-safe in-memory concurrent maps (`ConcurrentHashMap`).

### Mitigation & Impact
- Rapid repeated button taps, network glitches, and transient request retries within the same app session are 100% deduplicated.
- If the Android OS terminates the application process while a device is offline, pending unsubmitted requests are not persisted in an encrypted local SQLite/Room queue.
- *Recommended Enhancement for v1.1:* Integrate Android Jetpack Room for persistent offline request queueing with WorkManager background synchronization.

---

## 3. Indoor GPS Accuracy & Triangulation Limits

### Limitation
Tutor clock-in requires high-precision GPS readings with accuracy $\le 60\text{ meters}$ and Haversine distance $\le 150\text{ meters}$ from the tuition centre coordinates (`17.385044, 78.486671`). In deep concrete basements or ground floors with heavy structural shielding, raw satellite GPS signals may degrade to $>60\text{m}$ accuracy.

### Mitigation & Impact
- The location provider activates Google Play Services FusedLocationProvider with `PRIORITY_HIGH_ACCURACY`, combining GPS, Wi-Fi RTT, and cell towers.
- Tutors experiencing degraded GPS are prompted with a clear error dialog (`"GPS accuracy insufficient (X m > allowed 60m). Please move closer to a window or entrance."`).
- For legitimate indoor cases, the Centre Owner can utilize the built-in `ownerOverrideTutorAttendance` procedure with an audited justification reason.

---

## 4. Vendor-Specific Android Battery Management (Doze Mode)

### Limitation
Certain Android OEM distributions (notably Xiaomi MIUI/HyperOS, OPPO ColorOS, and Vivo FunTouch OS) enforce aggressive non-standard battery optimization policies that can delay high-priority Firebase Cloud Messaging (FCM) background push notifications when the device is locked.

### Mitigation & Impact
- Push notifications carry high priority (`priority: high`) in the FCM payload.
- All notifications are persisted independently in the PostgreSQL database (`in_app_notifications`).
- When the parent opens the app or returns to the dashboard, a fresh pull query executes automatically, ensuring zero lost notifications.
- The app provides an informational settings banner guiding users to exclude "Run for Victory" from OEM battery optimization.

---

## 5. Regional Language Localization Scope

### Limitation
The application provides comprehensive, bilingual localization for **English** and **Telugu** across all 100+ user-facing strings, buttons, form validations, errors, and empty states. Other regional languages (such as Hindi, Urdu, or Kannada) are not currently translated.

### Mitigation & Impact
- The architecture uses Android string resources (`res/values/strings.xml` and `res/values-te/strings.xml`) managed by `LocaleManager`.
- Adding new regional languages requires only adding standard `res/values-<lang>/strings.xml` resource bundles without touching Kotlin presentation code.

---

## 6. Historical Attendance Pagination Threshold

### Limitation
The parent dashboard and tutor history screens currently fetch monthly summaries and date-bounded lists. For students with multi-year histories (>500 attendance records), fetching unbounded lists could increase memory consumption.

### Mitigation & Impact
- The database schema indexes `(student_id, attendance_date DESC)`.
- UI screens default to displaying the current month's attendance, with month/year navigation controls, effectively bounding result sets to $\le 31$ records per query.

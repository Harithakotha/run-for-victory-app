# Production Deployment Checklist
**Project:** Run for Victory by Ravi Kumar  
**Target Release:** v1.0.0 Production  
**Target Platform:** Android (API Level 26+ / Android 8.0 to Android 15)  
**Backend:** Supabase PostgreSQL 15+ & Firebase Cloud Messaging (FCM)  

---

## Pre-Deployment Verification Summary
Before proceeding with deployment, verify that all automated audit suites have completed:
- [x] Android Unit Tests: `gradlew.bat testDebugUnitTest` (140/140 passed)
- [x] Database Migration Tests: `node supabase/tests/verify_migrations.js` (66/66 passed)
- [x] RLS Multi-Tenant Security Tests: `node supabase/tests/verify_security_rules.js` (10/10 passed)
- [x] Android Lint: `gradlew.bat lintDebug` (0 errors)
- [x] Release APK Secret Audit: Verified zero embedded credentials or private keys

---

## Phase 1: Supabase Database Migration & Backend Deployment

### 1.1 Apply Migrations in Topological Order
Execute the 10 migration files sequentially against the production Supabase PostgreSQL instance:
```bash
# Using Supabase CLI
supabase db push

# Or using psql in strict topological sequence
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_001_initial_schema_and_rls.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_001_tuition_centre_schema.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_002_rls_policies.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_003_functions_and_rpcs.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_004_account_provisioning.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_005_student_management_enhancements.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_006_student_attendance_enhancements.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_007_teacher_attendance_enhancements.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_008_work_reporting_enhancements.sql
psql -h <db_host> -U postgres -d postgres -f supabase/migrations/20261025_009_notifications_and_push_delivery.sql
```

### 1.2 Verify Extensions & RLS Status
Verify that PostGIS and pgcrypto are active and RLS is enabled across all tables:
```sql
SELECT extname FROM pg_extension WHERE extname IN ('pgcrypto', 'postgis');

-- Ensure all tables have rowsecurity = true
SELECT tablename, rowsecurity 
FROM pg_tables 
WHERE schemaname = 'public';
```

### 1.3 Vault Backend Credentials
Store elevated backend secrets strictly in Supabase Vault / Cloud Secrets:
- [ ] `SUPABASE_SERVICE_ROLE_KEY` (Never distribute to Android clients)
- [ ] `FIREBASE_ADMIN_SERVICE_ACCOUNT_JSON` (Vaulted for push worker)

---

## Phase 2: Firebase Cloud Messaging Configuration

### 2.1 FCM Service Account
- [ ] Create Google Cloud / Firebase Project: `run-for-victory-production`.
- [ ] Generate Firebase Admin Service Account Private Key.
- [ ] Store the JSON credentials in the backend push dispatch worker (Edge Function or secure backend service).

### 2.2 Client Push Configuration
- [ ] Download `google-services.json` from Firebase Console.
- [ ] Place `google-services.json` in `app/` (Ensure it is in `.gitignore` if sensitive or for private repositories).
- [ ] Verify client notification channels:
  - `attendance_channel` (Importance: HIGH)
  - `reports_channel` (Importance: DEFAULT)
  - `general_channel` (Importance: LOW)

---

## Phase 3: Android Release Signing & Packaging

### 3.1 Production Keystore Generation
Generate a cryptographically secure release keystore (if not already existing):
```bash
keytool -genkey -v \
  -keystore run_for_victory_release.jks \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -alias runforvictory_key
```

### 3.2 Build Release APK
```bash
gradlew.bat assembleRelease
```
Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

### 3.3 Zipalign & Apksigner
Align and sign the release package:
```bash
# 1. Align the APK
zipalign -v -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app-release-aligned.apk

# 2. Sign with release key
apksigner sign \
  --ks run_for_victory_release.jks \
  --ks-key-alias runforvictory_key \
  --out app-release-signed.apk \
  app-release-aligned.apk

# 3. Verify signature
apksigner verify --verbose app-release-signed.apk
```

---

## Phase 4: Production Verification & Smoke Testing

### 4.1 Initial Owner Account Setup
- [ ] Log in as Owner: `owner@runforvictory.com`.
- [ ] Verify Tuition Centre branding: **Run for Victory By Ravi Kumar** (Navy, Teal, Gold palette).
- [ ] Verify centre GPS coordinates: Lat `17.385044`, Lng `78.486671`, Radius `150m`.

### 4.2 Staff & Parent Invitation Smoke Test
- [ ] Issue invitation for tutor: `tutor.anand@runforvictory.com`.
- [ ] Issue invitation for parent: `parent.suresh@example.com` linked to student Rahul Reddy.
- [ ] Claim invitation using 7-day token on mobile device.
- [ ] Verify role permissions: Tutor sees Clock-In; Parent sees only linked child Rahul.

### 4.3 Geofenced Attendance Verification
- [ ] Tutor performs clock-in inside geofence ($\le 150\text{m}$, accuracy $\le 60\text{m}$).
- [ ] Verify clock-in succeeds and status is `VERIFIED`.
- [ ] Tutor attempts mock location or out-of-boundary clock-in.
- [ ] Verify rejection with audited entry in `teacher_attendance`.

### 4.4 Localization Smoke Test
- [ ] Toggle language selector to **తెలుగు (Telugu)**.
- [ ] Verify all dashboard labels, buttons, and attendance cards display natural Telugu.
- [ ] Restart app and verify Telugu preference persists.
- [ ] Toggle back to **English** and verify instant UI update.

---

## Phase 5: Backup & Disaster Recovery Runbook

### 5.1 Automated Database Backups
- **Continuous Backups (PITR):** Enable Supabase Point-In-Time-Recovery with 7-day or 30-day WAL retention.
- **Daily Logical Dump:** Configure scheduled cron job:
```bash
pg_dump -h <host> -U postgres -d postgres -F c -b -v -f /backups/rfv_backup_$(date +%Y%m%d_%H%M%S).dump
```

### 5.2 Disaster Recovery Restore Procedure
In the event of accidental data corruption or infrastructure failure:
```bash
# 1. Stop write traffic (Maintenance mode)
# 2. Restore logical backup cleanly
pg_restore -h <host> -U postgres -d postgres --clean --if-exists -v /backups/rfv_backup_<timestamp>.dump

# 3. Verify RLS policies are intact
SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname = 'public';

# 4. Resume write traffic
```

### 5.3 Contingency Contact
- **Centre Owner & Administrator:** Ravi Kumar
- **Primary Support:** `support@runforvictory.com`

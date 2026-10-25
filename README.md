# Run for Victory
### By Ravi Kumar
**Native Android Tuition Management Application & Supabase Backend**

*Package:* `com.runforvictory.tuition`  
*Target SDK:* Android 14 (API 34)  
*Min SDK:* Android 8.0 Oreo (API 26)  
*Primary Timezone:* `Asia/Kolkata` (IST, UTC+5:30)  
*Supported Languages:* English (`en`), Telugu (`te` - తెలుగు)

---

## 📌 Project Overview
**Run for Victory** is a production-oriented tuition-centre management application designed for:
1. **Tuition Owner (Ravi Kumar)**: Complete operational control over tutors, parents, students, GPS geofences, and audited attendance corrections.
2. **Tutors**: Secure attendance clock-in verified by server-side GPS geofencing, student daily check-in/out registers, and daily work/homework reporting with author audit trails.
3. **Parents**: Secure, isolated portal to view real-time attendance, daily progress reports, homework assignments, and teacher notes for their linked children only.

---

## 🗄 Backend & Database Architecture (Supabase / PostgreSQL)

### 1. Version-Controlled SQL Migrations (`supabase/migrations/`)
- [`20261025_001_tuition_centre_schema.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/migrations/20261025_001_tuition_centre_schema.sql):
  - 11 core tables (`tuition_centres`, `profiles`, `students`, `parent_children`, `teacher_attendance`, `student_attendance`, `work_logs`, `app_notifications`, `device_tokens`, `audit_logs`, `account_invitations`).
  - UUID primary keys, foreign key constraints with cascade/restrict rules, and partial indexes.
  - Multi-tenant isolation: every operational record references `centre_id`.
  - Archival lifecycle: Archiving sets `status = 'ARCHIVED'` and `archived_at` without deleting historical attendance or work reports.
- [`20261025_002_rls_policies.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/migrations/20261025_002_rls_policies.sql):
  - Zero-trust deny-by-default on all tables.
  - Separate granular policies for `owner`, `tutor`, and `parent`.
  - Non-recursive `SECURITY DEFINER` role resolvers with fixed `search_path = public`.
  - Tamper protection trigger `trg_guard_profile_mutation` preventing users from mutating their own role or centre membership.
- [`20261025_003_functions_and_rpcs.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/migrations/20261025_003_functions_and_rpcs.sql):
  - `haversine_distance_meters`: Geodesic distance calculation.
  - `record_teacher_clock_in` & `record_teacher_clock_out`: Atomic tutor attendance with server-enforced accuracy and geofence distance verification; guards against duplicate open sessions.
  - `record_student_check_in` & `record_student_check_out`: Atomic student attendance; rejects archived students and duplicate daily sessions.
  - `create_work_log_entry`: Atomic work report logging with idempotency key deduplication and automatic resilient parent notification enqueuing.
  - `archive_student_record` & `restore_student_record`: Audited student lifecycle management.
  - `owner_override_teacher_attendance` & `owner_correct_student_attendance`: Audited corrections with reason logs.
- [`20261025_004_account_provisioning.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/migrations/20261025_004_account_provisioning.sql):
  - Server-side invitation issuance and cryptographic tokens.
  - Role-based invitations (tutors and parents; owners cannot self-provision).
  - Explicit parent-to-student relationship validation.
  - Safe user deactivation RPC preventing deactivated accounts from performing any protected operations.
- [`20261025_005_student_management_enhancements.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/migrations/20261025_005_student_management_enhancements.sql):
  - Student notes and archival audit fields (`archived_by`, `archived_at`, `archive_reason`).
  - Audited archive and restore RPCs.
  - Restricted permanent deletion RPC requiring explicit confirmation phrase verification and justification.
- [`20261025_006_student_attendance_enhancements.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/migrations/20261025_006_student_attendance_enhancements.sql):
  - Support for `EXCUSED` attendance status.
  - Idempotent check-in and check-out RPCs preventing duplicates during network retries.
  - Server-side trusted timestamps evaluated in `Asia/Kolkata` (IST, UTC+05:30).
  - Audited owner attendance correction RPC logging to `audit_logs`.
  - Daily and monthly attendance aggregation functions.
- [`20261025_007_teacher_attendance_enhancements.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/migrations/20261025_007_teacher_attendance_enhancements.sql):
  - Centre GPS settings: `max_location_age_seconds` (120s), `owner_override_policy`.
  - Enhanced teacher attendance table with `override_at` and `is_mock_flagged`.
  - Server-side trusted RPCs: `record_teacher_clock_in` and `record_teacher_clock_out` with anti-staleness, mock rejection, Haversine geofence calculation, and single open session enforcement.
  - Audited `owner_override_teacher_attendance` RPC capturing actor, timestamp, and mandatory reason.

### 2. Secure Initial Owner Account Creation Procedure
Because public registration must **never** grant privileged owner permissions, the initial tuition-centre owner account is provisioned directly via trusted administrative database initialization:

```sql
-- 1. Create the tuition centre record
INSERT INTO public.tuition_centres (id, name, contact_phone, latitude, longitude, radius_meters)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    'Run for Victory Tuition Centre',
    '+919876543210',
    17.385044,
    78.486671,
    150
) ON CONFLICT (id) DO NOTHING;

-- 2. Insert initial Owner into auth.users using bcrypt salt
INSERT INTO auth.users (
    instance_id,
    id,
    aud,
    role,
    email,
    encrypted_password,
    email_confirmed_at,
    raw_app_meta_data,
    raw_user_meta_data,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000000',
    '11111111-1111-1111-1111-111111111111',
    'authenticated',
    'authenticated',
    'owner@runforvictory.com',
    crypt('OwnerSecurePass2026!', gen_salt('bf')),
    NOW(),
    '{"provider":"email","providers":["email"]}',
    '{"full_name":"Ravi Kumar (Owner)","role":"owner"}',
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- 3. Link profile with OWNER role and centre_id
INSERT INTO public.profiles (
    id,
    centre_id,
    role,
    full_name,
    phone,
    is_active
) VALUES (
    '11111111-1111-1111-1111-111111111111',
    '10000000-0000-0000-0000-000000000001',
    'owner',
    'Ravi Kumar (Owner)',
    '+919876543210',
    true
) ON CONFLICT (id) DO NOTHING;
```

### 3. Seed Data & Security Tests
- Seed fixtures: [`supabase/seeds/seed.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/seeds/seed.sql) (synthetic test centres, accounts, and students).
- SQL test suite: [`supabase/tests/security_tests.sql`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/tests/security_tests.sql).
- Automated test runner: [`supabase/tests/verify_security_rules.js`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/supabase/tests/verify_security_rules.js).
- Complete security documentation: [`SECURITY_TEST_PLAN.md`](file:///C:/Users/Nikitha%20kotha/.gemini/antigravity/scratch/run_for_victory/SECURITY_TEST_PLAN.md).

---

## 📱 Android Client Implementation

### 1. Key Modules & Features
- **Student Attendance Register (`features/attendance/StudentAttendanceScreen.kt`)**:
  - Live active student roster displaying student name, grade/class, status badge, check-in time, check-out time, and current session duration (e.g. `Active: 2h 15m` or `Completed: 3h 00m`).
  - Action buttons: Check-in with status selection (`PRESENT`, `LATE`, `EXCUSED`, `ABSENT`), Check-out button (only active when checked in without departure).
  - Date navigation strip with previous day, next day (future dates disabled), and "Today" button in `Asia/Kolkata` (IST).
  - Daily Summary tab: Total active students, present, late, excused, absent, unrecorded counts, and attendance rate %.
  - Monthly Summary tab: Total sessions, status breakdown, and monthly attendance average %.
  - Student attendance history sheet with chronological records and duration breakdown.
  - Owner-authorised attendance correction dialog with mandatory reason audit logging and departure time validation.
  - In-flight request debouncing and idempotency replay safety during network retries.
- **Student Management Directory (`features/owner/StudentManagementScreen.kt`)**:
  - Live search by student name, contact number, or guardian phone.
  - Filter chips for Class/Grade and segmented status tabs (Active vs Archived).
  - Add & Edit Student dialogs with real-time validation and duplicate submission prevention.
  - Archive Student modal requiring mandatory reason entry for the audit trail.
  - Restore Student flow reactivating students to attendance registers without duplicating records.
  - Full Student History sheet displaying past attendance records and daily work reports.
  - Restricted Permanent Deletion guard requiring exact confirmation phrase `"PERMANENTLY DELETE STUDENT"` and audit justification.
- **Authentication & Role Navigation (`authentication/`, `navigation/`)**:
  - Unified Login Screen with branding, session restoration, and password reset dialog.
  - Dedicated dashboards for **Owner**, **Tutor**, and **Parent**.
  - Secure session storage with automatic revocation when accounts are deactivated.
- **Location Verification & Utilities (`location/`)**:
  - Geofence calculation via Haversine formula.
  - Strict client-side validation against mock GPS providers and stale readings before transmitting to server RPC.

---

## 🛠 Verification & Test Results

### 1. Backend Security Test Suite
```bash
node supabase/tests/verify_security_rules.js
```
- **Test 1 (Owner Access)**: Owner can view authorised centre records (Centre A only, 0 for Centre B). ➔ **PASS**
- **Test 2 (Privilege Separation)**: Tutor cannot modify centre settings or execute owner archival RPCs. ➔ **PASS**
- **Test 3 (Parent Isolation)**: Parent A can read Child A; reading Child B returns 0 records. ➔ **PASS**
- **Test 4 (Attendance Immutability)**: Parent cannot insert or modify attendance records. ➔ **PASS**
- **Test 5 (Archived Student Protection)**: Archived students cannot receive new attendance records. ➔ **PASS**
- **Test 6 (Tenant Isolation)**: Users cannot cross tuition-centre boundaries (Centre A vs Centre B). ➔ **PASS**
- **Test 7 (Anonymous Access Denied)**: Anonymous users cannot read private records (0 rows across all tables). ➔ **PASS**
- **Test 8 (Idempotent Retry Guard)**: Attendance retry cannot create duplicate records for same date. ➔ **PASS**
- **Bonus Test 9 (GPS Geofence)**: Clock-in 10km outside geofence boundary is rejected. ➔ **PASS**
- **Bonus Test 10 (GPS Accuracy)**: Inaccurate GPS reading (>60m) is rejected. ➔ **PASS**

### 2. Android Client Tests & Build
- `.\gradlew.bat testDebugUnitTest` ➔ **50 unit tests passed, 0 failures**:
  - `StudentAttendanceTest`: 15/15 tests passed (arrival/departure, duplicate check-in, duplicate checkout, check-out without check-in, network retry idempotency replay, archived student guard, owner correction with audit trail, non-owner rejection, invalid reason rejection, departure earlier than arrival rejection, excused status, daily summary, monthly summary, midnight/timezone boundary in `Asia/Kolkata`).
  - `StudentManagementTest`: 8/8 tests passed (create, edit, duplicate rejection, archive, active list exclusion, restore, history preservation, permanent deletion guard).
  - `AccountManagementTest`: 7/7 tests passed (session expiry, token validation, user deactivation, role protection).
  - `AuthValidationTest`: 6/6 tests passed (credential validation, email formatting).
  - `AttendanceValidationTest`: 3/3 tests passed.
  - `WorkReportValidationTest`: 3/3 tests passed.
  - `HaversineDistanceTest`: 3/3 tests passed.
  - `LocationValidatorTest`: 5/5 tests passed (mock detection, accuracy tolerance, geofence radius).
- `.\gradlew.bat assembleDebug` ➔ **Debug APK generated (16.4 MB, `app-debug.apk`)**.
- `.\gradlew.bat lintDebug` ➔ **0 errors**.


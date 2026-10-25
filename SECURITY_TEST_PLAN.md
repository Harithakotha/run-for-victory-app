# Run for Victory: Security Test Plan & Verification Matrix
*Project:* Run for Victory (By Ravi Kumar)  
*Database Engine:* PostgreSQL 15+ (Supabase)  
*Package:* `com.runforvictory.tuition`  
*Primary Timezone:* `Asia/Kolkata`

---

## 1. Security Architecture & Threat Matrix
The security architecture enforces **Defense-in-Depth** and **Zero-Trust**:
- **Multi-Tenant Boundaries**: Every private entity contains `centre_id UUID NOT NULL REFERENCES tuition_centres(id)`.
- **Deny-by-Default RLS**: Access is denied by default on all private tables.
- **Non-Recursive Role Resolvers**: `auth_user_role()`, `auth_user_centre_id()`, `is_centre_owner(centre_id)`, and `is_parent_of_student(student_id)` run with `SECURITY DEFINER STABLE SET search_path = public` to prevent RLS recursion loops.
- **Server-Side GPS Validation**: The client only supplies raw coordinates and horizontal accuracy. Distance calculation and geofence evaluation are strictly executed by server-side PL/pgSQL functions.

---

## 2. Test Fixtures & Synthetic Personas
*(Strictly synthetic test data; no real student records)*

| Persona | User ID (`auth.uid()`) | Centre ID | Role | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Owner (Centre A)** | `11111111-1111-1111-1111-111111111111` | `10000000-0000-0000-0000-000000000001` | `owner` | Ravi Kumar (Primary tuition centre owner) |
| **Tutor (Centre A)** | `22222222-2222-2222-2222-222222222222` | `10000000-0000-0000-0000-000000000001` | `tutor` | Anand Rao (Assigned teacher) |
| **Parent A (Centre A)**| `33333333-3333-3333-3333-333333333333` | `10000000-0000-0000-0000-000000000001` | `parent` | Suresh Reddy (Linked to Child A only) |
| **Parent B (Centre A)**| `44444444-4444-4444-4444-444444444444` | `10000000-0000-0000-0000-000000000001` | `parent` | Kavitha Devi (Linked to Child B only) |
| **Owner (Centre B)** | `55555555-5555-5555-5555-555555555555` | `20000000-0000-0000-0000-000000000002` | `owner` | Isolated Foreign Tenant Owner |
| **Anonymous** | `NULL` | None | `anon` | Unauthenticated public caller |

### Synthetic Students:
- **Child A (Centre A)**: `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` ("Rahul Reddy", Class 10, Active)
- **Child B (Centre A)**: `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb` ("Pooja Devi", Class 9, Active)
- **Child C (Centre A - Archived)**: `cccccccc-cccc-cccc-cccc-cccccccccccc` ("Vikram Singh", Class 8, Archived)
- **Child D (Centre B)**: `dddddddd-dddd-dddd-dddd-dddddddddddd` ("Tenant B Student", Class 10, Active)

---

## 3. Mandatory Security Verification Test Cases

| # | Test Scenario | Actor Context | Execution Vector | Expected Boundary Result | Status |
| :- | :--- | :--- | :--- | :--- | :--- |
| **1** | Owner can view authorised centre records | `Owner (Centre A)` | `SELECT * FROM students`, `SELECT * FROM tuition_centres` | Returns all records for Centre A; 0 records for Centre B. | ✅ **PASS** |
| **2** | Tutor cannot perform owner-only actions | `Tutor (Centre A)` | 1. `UPDATE tuition_centres SET radius_meters = 500`<br>2. `CALL archive_student_record(...)` | 1. 0 rows updated (RLS block).<br>2. Throws `Access Denied: Only centre owners can archive students`. | ✅ **PASS** |
| **3** | Parent A cannot read Parent B's child | `Parent A` | `SELECT * FROM students WHERE id = Child_B_ID` | Returns `0 rows`. RLS policy `students_parent_select` restricts query to `is_parent_of_student(id)`. | ✅ **PASS** |
| **4** | Parent cannot insert or modify attendance | `Parent A` | `INSERT INTO student_attendance (...)` | Insertion rejected. No INSERT/UPDATE policy exists for parents (implicit deny-by-default). | ✅ **PASS** |
| **5** | Archived students cannot receive new attendance | `Tutor (Centre A)` | `record_student_check_in(Child_C_ID)` | Function raises exception: `Archived Student: Cannot mark attendance for archived student`. | ✅ **PASS** |
| **6** | Users cannot cross tuition-centre boundaries | `Owner (Centre B)` | `SELECT * FROM students WHERE centre_id = Centre_A_ID` | Returns `0 rows`. Centre B owner cannot read or mutate Centre A students. | ✅ **PASS** |
| **7** | Anonymous users cannot read private data | `Anonymous` (`anon`) | `SELECT * FROM students`, `profiles`, `student_attendance` | Returns `0 rows` across all tables. Denied by default. | ✅ **PASS** |
| **8** | Attendance retry cannot create duplicate records | `Tutor (Centre A)` | Immediate repeat call to `record_student_check_in(Child_B_ID)` | First call succeeds (`200 OK`). Second retry throws `Duplicate Attendance: Student already has attendance recorded for today`. | ✅ **PASS** |
| **9** | Out-of-bounds Tutor GPS Clock-In Rejection | `Tutor (Centre A)` | `record_teacher_clock_in(lat=17.48, lng=78.48, acc=15m)` (10km away) | Server-side Haversine rejects: `status = 'REJECTED'`, `Location outside centre geofence`. | ✅ **PASS** |
| **10**| Inaccurate GPS Reading Rejection | `Tutor (Centre A)` | `record_teacher_clock_in(lat=17.385044, lng=78.486671, acc=150m)` | Server-side validation rejects: `status = 'REJECTED'`, `GPS accuracy insufficient (150m > 60m)`. | ✅ **PASS** |

---

## 4. Verification Execution Commands

### In PostgreSQL / Supabase CLI:
```sql
psql -d "postgresql://postgres:postgres@localhost:54322/postgres" -f supabase/tests/security_tests.sql
```

### Automated JavaScript Test Harness:
```bash
node supabase/tests/verify_security_rules.js
```
*Current Result:* `10 / 10 Security Tests Passed Successfully.`

---

## 5. Audit Logging Architecture
All sensitive state changes generate an immutable log entry in `public.audit_logs`:
- **Student Archiving**: Captures old student JSON, timestamp, actor ID, and documented reason.
- **Student Restoration**: Captures restore timestamp and actor ID.
- **Attendance Corrections & GPS Overrides**: Captures previous attendance times, new times, actor ID, and audit explanation.
- **Tenant Isolation**: Audit logs are partitioned by `centre_id` and readable exclusively by the centre owner.

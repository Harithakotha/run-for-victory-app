-- ============================================================================
-- Migration: 20261025_001_tuition_centre_schema.sql
-- Project: Run for Victory (By Ravi Kumar)
-- Database: PostgreSQL 15+ (Supabase)
-- Target: Multi-tenant Tuition Centre Core Schema
-- Timezone: Asia/Kolkata
-- ============================================================================

-- Ensure required cryptographic extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Set session default timezone
SET timezone = 'Asia/Kolkata';

-- ============================================================================
-- 1. TUITION CENTRES (Tenants)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.tuition_centres (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    subtitle TEXT NOT NULL DEFAULT 'By Ravi Kumar',
    logo_url TEXT,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    radius_meters INTEGER NOT NULL DEFAULT 150 CHECK (radius_meters >= 10 AND radius_meters <= 5000),
    max_location_accuracy_meters DOUBLE PRECISION NOT NULL DEFAULT 60.0 CHECK (max_location_accuracy_meters >= 5.0),
    timezone TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_tuition_centres_active ON public.tuition_centres (is_active);

-- ============================================================================
-- 2. USER PROFILES (Bounded by Tenant Centre)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE RESTRICT,
    role VARCHAR(20) NOT NULL CHECK (role IN ('owner', 'tutor', 'parent')),
    full_name TEXT NOT NULL,
    phone TEXT,
    avatar_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_profiles_centre ON public.profiles (centre_id, role, is_active);
CREATE INDEX IF NOT EXISTS idx_profiles_user ON public.profiles (id);

-- ============================================================================
-- 3. STUDENTS (Bounded by Tenant Centre, Archival Lifecycle)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.students (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE RESTRICT,
    full_name TEXT NOT NULL,
    grade TEXT NOT NULL,
    date_of_birth DATE,
    contact_phone TEXT,
    guardian_name TEXT,
    guardian_phone TEXT,
    address TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    is_archived BOOLEAN GENERATED ALWAYS AS (status = 'ARCHIVED') STORED,
    archived_at TIMESTAMPTZ,
    archived_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Partial unique index: Prevent duplicate active students within the same centre
CREATE UNIQUE INDEX IF NOT EXISTS idx_students_unique_active 
ON public.students (centre_id, LOWER(TRIM(full_name)), LOWER(TRIM(grade)), contact_phone) 
WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_students_centre_status ON public.students (centre_id, status);

-- ============================================================================
-- 4. PARENT-CHILD LINKS (Explicit Relationship Mapping)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.parent_children (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE RESTRICT,
    parent_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES public.students(id) ON DELETE RESTRICT,
    relationship_type TEXT NOT NULL DEFAULT 'Parent',
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT unique_parent_child UNIQUE (parent_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_parent_children_parent ON public.parent_children (parent_id, is_active);
CREATE INDEX IF NOT EXISTS idx_parent_children_student ON public.parent_children (student_id, is_active);
CREATE INDEX IF NOT EXISTS idx_parent_children_centre ON public.parent_children (centre_id);

-- ============================================================================
-- 5. TEACHER ATTENDANCE (GPS Geofence Verified & Single Open Session Guard)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.teacher_attendance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE RESTRICT,
    teacher_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    attendance_date DATE NOT NULL DEFAULT (timezone('Asia/Kolkata', now())::date),
    check_in_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    check_out_time TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED', 'REJECTED', 'OVERRIDDEN')),
    
    -- Check-in Telemetry
    check_in_lat DOUBLE PRECISION NOT NULL,
    check_in_lng DOUBLE PRECISION NOT NULL,
    check_in_accuracy DOUBLE PRECISION NOT NULL,
    check_in_distance_meters DOUBLE PRECISION NOT NULL,
    check_in_verification_status TEXT NOT NULL CHECK (check_in_verification_status IN ('VERIFIED', 'REJECTED', 'OVERRIDDEN')),
    check_in_rejection_reason TEXT,
    
    -- Check-out Telemetry
    check_out_lat DOUBLE PRECISION,
    check_out_lng DOUBLE PRECISION,
    check_out_accuracy DOUBLE PRECISION,
    check_out_distance_meters DOUBLE PRECISION,
    check_out_verification_status TEXT CHECK (check_out_verification_status IN ('VERIFIED', 'REJECTED', 'OVERRIDDEN')),
    check_out_rejection_reason TEXT,
    
    -- Owner Manual Override Details
    is_manual_override BOOLEAN NOT NULL DEFAULT false,
    override_by UUID REFERENCES public.profiles(id),
    override_reason TEXT,
    
    -- Calculated duration
    duration_minutes INTEGER GENERATED ALWAYS AS (
        CASE WHEN check_out_time IS NOT NULL AND check_out_time >= check_in_time
             THEN (EXTRACT(EPOCH FROM (check_out_time - check_in_time))::INTEGER / 60)
             ELSE NULL END
    ) STORED,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    
    CONSTRAINT valid_teacher_checkout_time CHECK (check_out_time IS NULL OR check_out_time >= check_in_time)
);

-- Unique index to prevent duplicate open sessions for the same teacher
CREATE UNIQUE INDEX IF NOT EXISTS idx_teacher_unique_open_session 
ON public.teacher_attendance (teacher_id) 
WHERE check_out_time IS NULL AND status = 'OPEN';

-- Constraint to ensure one valid attendance per teacher per day
CREATE UNIQUE INDEX IF NOT EXISTS idx_teacher_unique_per_date 
ON public.teacher_attendance (teacher_id, attendance_date)
WHERE status != 'REJECTED';

CREATE INDEX IF NOT EXISTS idx_teacher_att_date ON public.teacher_attendance (centre_id, attendance_date DESC);
CREATE INDEX IF NOT EXISTS idx_teacher_att_teacher ON public.teacher_attendance (teacher_id, attendance_date DESC);

-- ============================================================================
-- 6. STUDENT ATTENDANCE (Prevent Duplicate Attendance Per Day & Date Isolated)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.student_attendance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES public.students(id) ON DELETE RESTRICT,
    attendance_date DATE NOT NULL DEFAULT (timezone('Asia/Kolkata', now())::date),
    check_in_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    check_out_time TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'PRESENT' CHECK (status IN ('PRESENT', 'LATE', 'HALF_DAY', 'ABSENT')),
    recorded_by UUID NOT NULL REFERENCES public.profiles(id),
    notes TEXT,
    duration_minutes INTEGER GENERATED ALWAYS AS (
        CASE WHEN check_out_time IS NOT NULL AND check_out_time >= check_in_time
             THEN (EXTRACT(EPOCH FROM (check_out_time - check_in_time))::INTEGER / 60)
             ELSE NULL END
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT unique_student_attendance_per_date UNIQUE (student_id, attendance_date),
    CONSTRAINT valid_student_checkout_time CHECK (check_out_time IS NULL OR check_out_time >= check_in_time)
);

CREATE INDEX IF NOT EXISTS idx_student_att_date ON public.student_attendance (centre_id, attendance_date DESC);
CREATE INDEX IF NOT EXISTS idx_student_att_student ON public.student_attendance (student_id, attendance_date DESC);

-- ============================================================================
-- 7. WORK LOGS (Daily Academic Reports with Author & Timestamp Preservation)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.work_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES public.students(id) ON DELETE RESTRICT,
    tutor_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    report_date DATE NOT NULL DEFAULT (timezone('Asia/Kolkata', now())::date),
    subject TEXT NOT NULL,
    work_completed TEXT NOT NULL,
    homework TEXT,
    progress_notes TEXT,
    idempotency_key UUID NOT NULL DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT unique_work_log_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_work_logs_centre_date ON public.work_logs (centre_id, report_date DESC);
CREATE INDEX IF NOT EXISTS idx_work_logs_student ON public.work_logs (student_id, report_date DESC);
CREATE INDEX IF NOT EXISTS idx_work_logs_tutor ON public.work_logs (tutor_id, report_date DESC);

-- ============================================================================
-- 8. APP NOTIFICATIONS (In-App Alerts)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.app_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('WORK_LOG', 'ATTENDANCE', 'SYSTEM', 'ANNOUNCEMENT')),
    reference_id UUID,
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_app_notif_user ON public.app_notifications (user_id, is_read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_notif_centre ON public.app_notifications (centre_id);

-- ============================================================================
-- 9. DEVICE TOKENS (Push Notification Tokens for FCM)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.device_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    token TEXT NOT NULL UNIQUE,
    platform TEXT NOT NULL DEFAULT 'android',
    device_info TEXT,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_device_tokens_user ON public.device_tokens (user_id);

-- ============================================================================
-- 10. AUDIT LOGS (Immutable History of Privileged & Sensitive Changes)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES public.profiles(id),
    action TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id UUID NOT NULL,
    old_values JSONB,
    new_values JSONB,
    reason TEXT,
    ip_address TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_centre ON public.audit_logs (centre_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity ON public.audit_logs (entity_type, entity_id);

-- ============================================================================
-- 11. ACCOUNT INVITATIONS / PROVISIONING RECORDS
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.account_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE CASCADE,
    invited_by UUID NOT NULL REFERENCES public.profiles(id),
    email TEXT NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('tutor', 'parent')),
    token TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + INTERVAL '7 days'),
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_active_invitation 
ON public.account_invitations (centre_id, LOWER(TRIM(email))) 
WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_invitations_centre ON public.account_invitations (centre_id, status);

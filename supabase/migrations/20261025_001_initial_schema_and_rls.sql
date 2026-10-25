-- ============================================================================
-- Migration: 20261025_001_initial_schema_and_rls.sql
-- Project: Run for Victory (By Ravi Kumar)
-- Package: com.runforvictory.tuition
-- Database: PostgreSQL (Supabase)
-- Timezone: Asia/Kolkata
-- Description: Core schema, indexes, RLS policies, audit logs, and server RPCs
-- ============================================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Set default timezone for session
SET timezone = 'Asia/Kolkata';

-- ============================================================================
-- 1. ENUMS AND UTILITY FUNCTIONS
-- ============================================================================

-- Function to calculate Haversine distance in meters between two coordinates
CREATE OR REPLACE FUNCTION haversine_distance_meters(
    lat1 DOUBLE PRECISION,
    lon1 DOUBLE PRECISION,
    lat2 DOUBLE PRECISION,
    lon2 DOUBLE PRECISION
)
RETURNS DOUBLE PRECISION AS $$
DECLARE
    r DOUBLE PRECISION := 6371000.0; -- Earth radius in meters
    phi1 DOUBLE PRECISION := radians(lat1);
    phi2 DOUBLE PRECISION := radians(lat2);
    delta_phi DOUBLE PRECISION := radians(lat2 - lat1);
    delta_lambda DOUBLE PRECISION := radians(lon2 - lon1);
    a DOUBLE PRECISION;
    c DOUBLE PRECISION;
BEGIN
    a := sin(delta_phi / 2.0)^2 + cos(phi1) * cos(phi2) * sin(delta_lambda / 2.0)^2;
    c := 2.0 * atan2(sqrt(a), sqrt(1.0 - a));
    RETURN r * c;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- ============================================================================
-- 2. CORE TABLES
-- ============================================================================

-- 2.1 Profiles Table (Extends Supabase auth.users)
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('owner', 'tutor', 'parent')),
    full_name TEXT NOT NULL,
    phone TEXT,
    avatar_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 2.2 Tuition Center Configuration Table
CREATE TABLE IF NOT EXISTS public.tuition_centers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL DEFAULT 'Run for Victory',
    subtitle TEXT NOT NULL DEFAULT 'By Ravi Kumar',
    logo_url TEXT,
    latitude DOUBLE PRECISION NOT NULL DEFAULT 17.385044, -- Hyderabad default
    longitude DOUBLE PRECISION NOT NULL DEFAULT 78.486671,
    radius_meters INTEGER NOT NULL DEFAULT 150 CHECK (radius_meters >= 10 AND radius_meters <= 5000),
    timezone TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    max_location_accuracy_meters DOUBLE PRECISION NOT NULL DEFAULT 60.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 2.3 Students Table
CREATE TABLE IF NOT EXISTS public.students (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name TEXT NOT NULL,
    grade TEXT NOT NULL,
    date_of_birth DATE,
    contact_phone TEXT,
    guardian_name TEXT,
    guardian_phone TEXT,
    address TEXT,
    is_archived BOOLEAN NOT NULL DEFAULT false,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Index for active students and unique constraint to prevent accidental duplicates
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_active_student 
ON public.students (LOWER(TRIM(full_name)), LOWER(TRIM(grade)), contact_phone) 
WHERE is_archived = false;

CREATE INDEX IF NOT EXISTS idx_students_archived ON public.students (is_archived);

-- 2.4 Parent-Student Link Table
CREATE TABLE IF NOT EXISTS public.parent_student_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES public.students(id) ON DELETE RESTRICT,
    relationship_type TEXT NOT NULL DEFAULT 'Parent',
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT unique_parent_student_link UNIQUE (parent_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_parent_links_parent ON public.parent_student_links (parent_id) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_parent_links_student ON public.parent_student_links (student_id) WHERE is_active = true;

-- 2.5 Student Attendance Table
CREATE TABLE IF NOT EXISTS public.student_attendance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
    CONSTRAINT unique_student_attendance_per_day UNIQUE (student_id, attendance_date),
    CONSTRAINT valid_student_check_out_time CHECK (check_out_time IS NULL OR check_out_time >= check_in_time)
);

CREATE INDEX IF NOT EXISTS idx_student_att_date ON public.student_attendance (attendance_date DESC);
CREATE INDEX IF NOT EXISTS idx_student_att_student ON public.student_attendance (student_id, attendance_date DESC);

-- 2.6 Tutor Attendance Table (Strict GPS & Server Validation)
CREATE TABLE IF NOT EXISTS public.tutor_attendance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tutor_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    attendance_date DATE NOT NULL DEFAULT (timezone('Asia/Kolkata', now())::date),
    check_in_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    check_out_time TIMESTAMPTZ,
    
    -- Check-in GPS telemetry
    check_in_lat DOUBLE PRECISION NOT NULL,
    check_in_lng DOUBLE PRECISION NOT NULL,
    check_in_accuracy DOUBLE PRECISION NOT NULL,
    check_in_distance_meters DOUBLE PRECISION NOT NULL,
    check_in_verification_status TEXT NOT NULL CHECK (check_in_verification_status IN ('VERIFIED', 'REJECTED', 'OVERRIDDEN')),
    check_in_rejection_reason TEXT,
    
    -- Check-out GPS telemetry
    check_out_lat DOUBLE PRECISION,
    check_out_lng DOUBLE PRECISION,
    check_out_accuracy DOUBLE PRECISION,
    check_out_distance_meters DOUBLE PRECISION,
    check_out_verification_status TEXT CHECK (check_out_verification_status IN ('VERIFIED', 'REJECTED', 'OVERRIDDEN')),
    check_out_rejection_reason TEXT,
    
    -- Manual overrides
    is_manual_override BOOLEAN NOT NULL DEFAULT false,
    override_by UUID REFERENCES public.profiles(id),
    override_reason TEXT,
    
    duration_minutes INTEGER GENERATED ALWAYS AS (
        CASE WHEN check_out_time IS NOT NULL AND check_out_time >= check_in_time
             THEN (EXTRACT(EPOCH FROM (check_out_time - check_in_time))::INTEGER / 60)
             ELSE NULL END
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT unique_tutor_attendance_per_day UNIQUE (tutor_id, attendance_date),
    CONSTRAINT valid_tutor_check_out_time CHECK (check_out_time IS NULL OR check_out_time >= check_in_time)
);

CREATE INDEX IF NOT EXISTS idx_tutor_att_date ON public.tutor_attendance (attendance_date DESC);
CREATE INDEX IF NOT EXISTS idx_tutor_att_tutor ON public.tutor_attendance (tutor_id, attendance_date DESC);

-- 2.7 Daily Work Reports
CREATE TABLE IF NOT EXISTS public.daily_work_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
    CONSTRAINT unique_work_report_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_work_reports_student ON public.daily_work_reports (student_id, report_date DESC);
CREATE INDEX IF NOT EXISTS idx_work_reports_tutor ON public.daily_work_reports (tutor_id, report_date DESC);
CREATE INDEX IF NOT EXISTS idx_work_reports_date ON public.daily_work_reports (report_date DESC);

-- 2.8 Daily Work Report Edit History (Audit)
CREATE TABLE IF NOT EXISTS public.daily_work_report_edits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id UUID NOT NULL REFERENCES public.daily_work_reports(id) ON DELETE CASCADE,
    edited_by UUID NOT NULL REFERENCES public.profiles(id),
    previous_work_completed TEXT NOT NULL,
    previous_homework TEXT,
    previous_progress_notes TEXT,
    edit_reason TEXT NOT NULL,
    edited_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_report_edits_report ON public.daily_work_report_edits (report_id, edited_at DESC);

-- 2.9 Attendance Audit Logs
CREATE TABLE IF NOT EXISTS public.attendance_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    record_type TEXT NOT NULL CHECK (record_type IN ('STUDENT', 'TUTOR')),
    record_id UUID NOT NULL,
    modified_by UUID NOT NULL REFERENCES public.profiles(id),
    action TEXT NOT NULL CHECK (action IN ('MANUAL_CHECK_IN', 'MANUAL_CHECK_OUT', 'CORRECTION', 'GPS_OVERRIDE', 'RESTORE')),
    old_values JSONB,
    new_values JSONB NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_att_audit_record ON public.attendance_audit_logs (record_id, created_at DESC);

-- 2.10 In-App Notifications
CREATE TABLE IF NOT EXISTS public.in_app_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('WORK_REPORT', 'ATTENDANCE', 'SYSTEM', 'ANNOUNCEMENT')),
    reference_id UUID,
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user ON public.in_app_notifications (user_id, is_read, created_at DESC);

-- 2.11 FCM Device Tokens
CREATE TABLE IF NOT EXISTS public.fcm_device_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    token TEXT NOT NULL UNIQUE,
    device_info TEXT,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON public.fcm_device_tokens (user_id);

-- 2.12 Outbound Notification Queue (for FCM push worker)
CREATE TABLE IF NOT EXISTS public.outbound_notification_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    data_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbound_queue_status ON public.outbound_notification_queue (status, created_at ASC);

-- ============================================================================
-- 3. HELPER FUNCTIONS FOR SECURITY DEFINER & RLS AVOIDANCE OF RECURSION
-- ============================================================================

-- Function to get current user's profile role without triggering recursive RLS
CREATE OR REPLACE FUNCTION public.get_current_user_role()
RETURNS TEXT AS $$
DECLARE
    v_role TEXT;
    v_is_active BOOLEAN;
BEGIN
    SELECT role, is_active INTO v_role, v_is_active 
    FROM public.profiles 
    WHERE id = auth.uid();

    IF v_is_active IS NOT TRUE THEN
        RETURN 'inactive';
    END IF;

    RETURN COALESCE(v_role, 'none');
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- Check if caller is active owner
CREATE OR REPLACE FUNCTION public.is_active_owner()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (public.get_current_user_role() = 'owner');
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- Check if caller is active tutor
CREATE OR REPLACE FUNCTION public.is_active_tutor()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (public.get_current_user_role() = 'tutor');
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- Check if caller is active parent
CREATE OR REPLACE FUNCTION public.is_active_parent()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (public.get_current_user_role() = 'parent');
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- Check if caller is linked to a specific student
CREATE OR REPLACE FUNCTION public.is_parent_linked_to_student(p_student_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
    IF NOT public.is_active_parent() THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1 FROM public.parent_student_links
        WHERE parent_id = auth.uid()
          AND student_id = p_student_id
          AND is_active = true
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- ============================================================================
-- 4. ROW-LEVEL SECURITY (RLS) POLICIES
-- ============================================================================

-- Enable RLS on every operational table
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tuition_centers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.students ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.parent_student_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.student_attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tutor_attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.daily_work_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.daily_work_report_edits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.attendance_audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.in_app_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.fcm_device_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.outbound_notification_queue ENABLE ROW LEVEL SECURITY;

-- 4.1 Profiles Policies
CREATE POLICY "Profiles: Users can read own profile"
ON public.profiles FOR SELECT
USING (id = auth.uid());

CREATE POLICY "Profiles: Owner can manage all profiles"
ON public.profiles FOR ALL
USING (public.is_active_owner());

CREATE POLICY "Profiles: Tutors can view tutors and students guardians"
ON public.profiles FOR SELECT
USING (public.is_active_tutor() AND is_active = true);

-- 4.2 Tuition Centers Policies
CREATE POLICY "Tuition Centers: Active users can view center info"
ON public.tuition_centers FOR SELECT
USING (public.get_current_user_role() IN ('owner', 'tutor', 'parent'));

CREATE POLICY "Tuition Centers: Only owner can modify settings"
ON public.tuition_centers FOR ALL
USING (public.is_active_owner());

-- 4.3 Students Policies
CREATE POLICY "Students: Owner full access"
ON public.students FOR ALL
USING (public.is_active_owner());

CREATE POLICY "Students: Tutors can view active students"
ON public.students FOR SELECT
USING (public.is_active_tutor() AND is_archived = false);

CREATE POLICY "Students: Parents can view linked active children"
ON public.students FOR SELECT
USING (public.is_parent_linked_to_student(id));

-- 4.4 Parent-Student Links Policies
CREATE POLICY "Links: Owner can manage all links"
ON public.parent_student_links FOR ALL
USING (public.is_active_owner());

CREATE POLICY "Links: Parents can view their own links"
ON public.parent_student_links FOR SELECT
USING (public.is_active_parent() AND parent_id = auth.uid());

-- 4.5 Student Attendance Policies
CREATE POLICY "Student Attendance: Owner full access"
ON public.student_attendance FOR ALL
USING (public.is_active_owner());

CREATE POLICY "Student Attendance: Tutors can view and mark attendance"
ON public.student_attendance FOR SELECT
USING (public.is_active_tutor());

CREATE POLICY "Student Attendance: Tutors can insert via verified RPC only or insert today"
ON public.student_attendance FOR INSERT
WITH CHECK (
    public.is_active_tutor() 
    AND recorded_by = auth.uid()
    AND attendance_date = (timezone('Asia/Kolkata', now())::date)
);

CREATE POLICY "Student Attendance: Parents can view their linked child attendance"
ON public.student_attendance FOR SELECT
USING (public.is_parent_linked_to_student(student_id));

-- 4.6 Tutor Attendance Policies
CREATE POLICY "Tutor Attendance: Owner full access"
ON public.tutor_attendance FOR ALL
USING (public.is_active_owner());

CREATE POLICY "Tutor Attendance: Tutors can view own attendance"
ON public.tutor_attendance FOR SELECT
USING (public.is_active_tutor() AND tutor_id = auth.uid());

-- NOTE: Direct INSERT/UPDATE on tutor_attendance is disabled for tutors.
-- All check-in/out MUST go through public.record_tutor_check_in / record_tutor_check_out RPCs!

-- 4.7 Daily Work Reports Policies
CREATE POLICY "Work Reports: Owner full access"
ON public.daily_work_reports FOR ALL
USING (public.is_active_owner());

CREATE POLICY "Work Reports: Tutors can view and create reports"
ON public.daily_work_reports FOR SELECT
USING (public.is_active_tutor());

CREATE POLICY "Work Reports: Tutors can insert own reports for active students"
ON public.daily_work_reports FOR INSERT
WITH CHECK (
    public.is_active_tutor()
    AND tutor_id = auth.uid()
    AND EXISTS (SELECT 1 FROM public.students s WHERE s.id = student_id AND s.is_archived = false)
);

CREATE POLICY "Work Reports: Parents can view reports of linked children"
ON public.daily_work_reports FOR SELECT
USING (public.is_parent_linked_to_student(student_id));

-- 4.8 Daily Work Report Edits Policies
CREATE POLICY "Report Edits: Owner can view all"
ON public.daily_work_report_edits FOR SELECT
USING (public.is_active_owner());

CREATE POLICY "Report Edits: Authoring tutors can view their edits"
ON public.daily_work_report_edits FOR SELECT
USING (public.is_active_tutor() AND edited_by = auth.uid());

-- 4.9 Attendance Audit Logs Policies
CREATE POLICY "Audit Logs: Only Owner can view audit logs"
ON public.attendance_audit_logs FOR SELECT
USING (public.is_active_owner());

-- 4.10 In-App Notifications Policies
CREATE POLICY "Notifications: Users can manage own notifications"
ON public.in_app_notifications FOR ALL
USING (user_id = auth.uid());

-- 4.11 FCM Device Tokens Policies
CREATE POLICY "FCM Tokens: Users can manage own tokens"
ON public.fcm_device_tokens FOR ALL
USING (user_id = auth.uid());

-- 4.12 Outbound Queue: Owner & Service Role only
CREATE POLICY "Outbound Queue: Only Owner and Service Role"
ON public.outbound_notification_queue FOR ALL
USING (public.is_active_owner());


-- ============================================================================
-- 5. TRUSTED SERVER FUNCTIONS & RPCs
-- ============================================================================

-- 5.1 Tutor Check-In with Server-Side GPS Validation
CREATE OR REPLACE FUNCTION public.record_tutor_check_in(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_accuracy DOUBLE PRECISION
)
RETURNS JSONB AS $$
DECLARE
    v_tutor_id UUID := auth.uid();
    v_center public.tuition_centers%ROWTYPE;
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_dist DOUBLE PRECISION;
    v_status TEXT := 'VERIFIED';
    v_rejection_reason TEXT := NULL;
    v_existing_id UUID;
    v_result_id UUID;
BEGIN
    -- Verify caller is active tutor
    IF NOT public.is_active_tutor() THEN
        RAISE EXCEPTION 'Access denied. Caller is not an active tutor.';
    END IF;

    -- Fetch active tuition center config
    SELECT * INTO v_center FROM public.tuition_centers LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tuition center configuration not found.';
    END IF;

    -- Check if already checked in today
    SELECT id INTO v_existing_id 
    FROM public.tutor_attendance
    WHERE tutor_id = v_tutor_id AND attendance_date = v_today;

    IF v_existing_id IS NOT NULL THEN
        RAISE EXCEPTION 'Tutor already checked in for today (%s). Duplicate check-in rejected.', v_today;
    END IF;

    -- Server-side Haversine Distance computation
    v_dist := public.haversine_distance_meters(p_lat, p_lng, v_center.latitude, v_center.longitude);

    -- Validate GPS accuracy
    IF p_accuracy IS NULL OR p_accuracy > v_center.max_location_accuracy_meters THEN
        v_status := 'REJECTED';
        v_rejection_reason := format('GPS accuracy insufficient (%s m > permitted %s m). Move to open sky or wait for GPS lock.', 
                                     COALESCE(p_accuracy::text, 'unknown'), v_center.max_location_accuracy_meters);
    ELSIF v_dist > v_center.radius_meters THEN
        v_status := 'REJECTED';
        v_rejection_reason := format('Outside tuition center boundary (Distance: %s m, Permitted: %s m).', 
                                     ROUND(v_dist::numeric, 1), v_center.radius_meters);
    END IF;

    -- Record attendance entry (Always recorded for audit, even if rejected)
    INSERT INTO public.tutor_attendance (
        tutor_id,
        attendance_date,
        check_in_time,
        check_in_lat,
        check_in_lng,
        check_in_accuracy,
        check_in_distance_meters,
        check_in_verification_status,
        check_in_rejection_reason
    ) VALUES (
        v_tutor_id,
        v_today,
        v_now,
        p_lat,
        p_lng,
        p_accuracy,
        v_dist,
        v_status,
        v_rejection_reason
    ) RETURNING id INTO v_result_id;

    IF v_status = 'REJECTED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'attendance_id', v_result_id,
            'status', v_status,
            'reason', v_rejection_reason,
            'distance_meters', ROUND(v_dist::numeric, 1),
            'permitted_radius', v_center.radius_meters,
            'accuracy_meters', p_accuracy
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'attendance_id', v_result_id,
        'status', v_status,
        'check_in_time', v_now,
        'distance_meters', ROUND(v_dist::numeric, 1)
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 5.2 Tutor Check-Out with Server-Side GPS Validation
CREATE OR REPLACE FUNCTION public.record_tutor_check_out(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_accuracy DOUBLE PRECISION
)
RETURNS JSONB AS $$
DECLARE
    v_tutor_id UUID := auth.uid();
    v_center public.tuition_centers%ROWTYPE;
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_att public.tutor_attendance%ROWTYPE;
    v_dist DOUBLE PRECISION;
    v_status TEXT := 'VERIFIED';
    v_rejection_reason TEXT := NULL;
BEGIN
    IF NOT public.is_active_tutor() THEN
        RAISE EXCEPTION 'Access denied. Caller is not an active tutor.';
    END IF;

    SELECT * INTO v_center FROM public.tuition_centers LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tuition center configuration not found.';
    END IF;

    -- Fetch today's check-in
    SELECT * INTO v_att 
    FROM public.tutor_attendance
    WHERE tutor_id = v_tutor_id AND attendance_date = v_today;

    IF v_att.id IS NULL THEN
        RAISE EXCEPTION 'No check-in record found for today (%s). Check-in first.', v_today;
    END IF;

    IF v_att.check_in_verification_status = 'REJECTED' AND NOT v_att.is_manual_override THEN
        RAISE EXCEPTION 'Cannot check out: Check-in was rejected and has not been approved by owner override.';
    END IF;

    IF v_att.check_out_time IS NOT NULL THEN
        RAISE EXCEPTION 'Tutor has already checked out for today at %.', v_att.check_out_time;
    END IF;

    -- Server-side Haversine Distance computation
    v_dist := public.haversine_distance_meters(p_lat, p_lng, v_center.latitude, v_center.longitude);

    IF p_accuracy IS NULL OR p_accuracy > v_center.max_location_accuracy_meters THEN
        v_status := 'REJECTED';
        v_rejection_reason := format('GPS accuracy insufficient (%s m > %s m).', 
                                     COALESCE(p_accuracy::text, 'unknown'), v_center.max_location_accuracy_meters);
    ELSIF v_dist > v_center.radius_meters THEN
        v_status := 'REJECTED';
        v_rejection_reason := format('Outside tuition center boundary (Distance: %s m, Permitted: %s m).', 
                                     ROUND(v_dist::numeric, 1), v_center.radius_meters);
    END IF;

    UPDATE public.tutor_attendance
    SET check_out_time = v_now,
        check_out_lat = p_lat,
        check_out_lng = p_lng,
        check_out_accuracy = p_accuracy,
        check_out_distance_meters = v_dist,
        check_out_verification_status = v_status,
        check_out_rejection_reason = v_rejection_reason,
        updated_at = v_now
    WHERE id = v_att.id;

    IF v_status = 'REJECTED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'attendance_id', v_att.id,
            'status', v_status,
            'reason', v_rejection_reason,
            'distance_meters', ROUND(v_dist::numeric, 1)
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'attendance_id', v_att.id,
        'status', v_status,
        'check_out_time', v_now,
        'duration_minutes', (EXTRACT(EPOCH FROM (v_now - v_att.check_in_time))::INTEGER / 60)
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 5.3 Owner Override for Tutor Attendance (Documented Audit Trail)
CREATE OR REPLACE FUNCTION public.owner_override_tutor_attendance(
    p_attendance_id UUID,
    p_override_status TEXT, -- 'OVERRIDDEN'
    p_new_check_in TIMESTAMPTZ DEFAULT NULL,
    p_new_check_out TIMESTAMPTZ DEFAULT NULL,
    p_reason TEXT DEFAULT 'Owner GPS manual verification'
)
RETURNS JSONB AS $$
DECLARE
    v_old_record JSONB;
    v_new_record JSONB;
BEGIN
    IF NOT public.is_active_owner() THEN
        RAISE EXCEPTION 'Access denied. Only the owner can override tutor attendance.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Override reason is strictly required for audit.';
    END IF;

    SELECT to_jsonb(t) INTO v_old_record FROM public.tutor_attendance t WHERE id = p_attendance_id;
    IF v_old_record IS NULL THEN
        RAISE EXCEPTION 'Tutor attendance record % not found.', p_attendance_id;
    END IF;

    UPDATE public.tutor_attendance
    SET is_manual_override = true,
        override_by = auth.uid(),
        override_reason = p_reason,
        check_in_verification_status = CASE WHEN p_override_status IS NOT NULL THEN p_override_status ELSE check_in_verification_status END,
        check_in_time = COALESCE(p_new_check_in, check_in_time),
        check_out_time = COALESCE(p_new_check_out, check_out_time),
        updated_at = clock_timestamp()
    WHERE id = p_attendance_id;

    SELECT to_jsonb(t) INTO v_new_record FROM public.tutor_attendance t WHERE id = p_attendance_id;

    INSERT INTO public.attendance_audit_logs (
        record_type, record_id, modified_by, action, old_values, new_values, reason
    ) VALUES (
        'TUTOR', p_attendance_id, auth.uid(), 'GPS_OVERRIDE', v_old_record, v_new_record, p_reason
    );

    RETURN jsonb_build_object('success', true, 'attendance_id', p_attendance_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 5.4 Student Check-In
CREATE OR REPLACE FUNCTION public.record_student_check_in(
    p_student_id UUID,
    p_status TEXT DEFAULT 'PRESENT',
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_student public.students%ROWTYPE;
    v_att_id UUID;
BEGIN
    -- Check permissions (Owner or Tutor)
    IF NOT (public.is_active_owner() OR public.is_active_tutor()) THEN
        RAISE EXCEPTION 'Access denied. Only owner or tutor can mark student attendance.';
    END IF;

    -- Verify student is active and not archived
    SELECT * INTO v_student FROM public.students WHERE id = p_student_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Student % not found.', p_student_id;
    END IF;

    IF v_student.is_archived THEN
        RAISE EXCEPTION 'Cannot mark attendance: Student % is archived.', v_student.full_name;
    END IF;

    -- Prevent duplicate check-in for today
    IF EXISTS (SELECT 1 FROM public.student_attendance WHERE student_id = p_student_id AND attendance_date = v_today) THEN
        RAISE EXCEPTION 'Student % already has attendance marked for today (%).', v_student.full_name, v_today;
    END IF;

    INSERT INTO public.student_attendance (
        student_id, attendance_date, check_in_time, status, recorded_by, notes
    ) VALUES (
        p_student_id, v_today, v_now, p_status, auth.uid(), p_notes
    ) RETURNING id INTO v_att_id;

    RETURN jsonb_build_object(
        'success', true,
        'attendance_id', v_att_id,
        'student_id', p_student_id,
        'check_in_time', v_now,
        'status', p_status
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 5.5 Student Check-Out
CREATE OR REPLACE FUNCTION public.record_student_check_out(
    p_student_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_att public.student_attendance%ROWTYPE;
BEGIN
    IF NOT (public.is_active_owner() OR public.is_active_tutor()) THEN
        RAISE EXCEPTION 'Access denied. Only owner or tutor can record student check-out.';
    END IF;

    SELECT * INTO v_att 
    FROM public.student_attendance 
    WHERE student_id = p_student_id AND attendance_date = v_today;

    IF v_att.id IS NULL THEN
        RAISE EXCEPTION 'No check-in record found for student today. Student must be checked in first.';
    END IF;

    IF v_att.check_out_time IS NOT NULL THEN
        RAISE EXCEPTION 'Student has already checked out for today at %.', v_att.check_out_time;
    END IF;

    UPDATE public.student_attendance
    SET check_out_time = v_now,
        updated_at = v_now
    WHERE id = v_att.id;

    RETURN jsonb_build_object(
        'success', true,
        'attendance_id', v_att.id,
        'check_out_time', v_now,
        'duration_minutes', (EXTRACT(EPOCH FROM (v_now - v_att.check_in_time))::INTEGER / 60)
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 5.6 Owner Correct Student Attendance (Audited)
CREATE OR REPLACE FUNCTION public.owner_correct_student_attendance(
    p_attendance_id UUID,
    p_check_in TIMESTAMPTZ,
    p_check_out TIMESTAMPTZ,
    p_status TEXT,
    p_notes TEXT,
    p_reason TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_old_record JSONB;
    v_new_record JSONB;
BEGIN
    IF NOT public.is_active_owner() THEN
        RAISE EXCEPTION 'Access denied. Only the owner can correct student attendance records.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Correction reason is strictly required for audit.';
    END IF;

    SELECT to_jsonb(a) INTO v_old_record FROM public.student_attendance a WHERE id = p_attendance_id;
    IF v_old_record IS NULL THEN
        RAISE EXCEPTION 'Student attendance record % not found.', p_attendance_id;
    END IF;

    UPDATE public.student_attendance
    SET check_in_time = COALESCE(p_check_in, check_in_time),
        check_out_time = p_check_out,
        status = COALESCE(p_status, status),
        notes = COALESCE(p_notes, notes),
        updated_at = clock_timestamp()
    WHERE id = p_attendance_id;

    SELECT to_jsonb(a) INTO v_new_record FROM public.student_attendance a WHERE id = p_attendance_id;

    INSERT INTO public.attendance_audit_logs (
        record_type, record_id, modified_by, action, old_values, new_values, reason
    ) VALUES (
        'STUDENT', p_attendance_id, auth.uid(), 'CORRECTION', v_old_record, v_new_record, p_reason
    );

    RETURN jsonb_build_object('success', true, 'attendance_id', p_attendance_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 5.7 Create Daily Work Report with Resilient Parent Notification Enqueue
CREATE OR REPLACE FUNCTION public.create_daily_work_report(
    p_student_id UUID,
    p_subject TEXT,
    p_work_completed TEXT,
    p_homework TEXT DEFAULT NULL,
    p_progress_notes TEXT DEFAULT NULL,
    p_idempotency_key UUID DEFAULT gen_random_uuid()
)
RETURNS JSONB AS $$
DECLARE
    v_tutor_id UUID := auth.uid();
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_student public.students%ROWTYPE;
    v_report_id UUID;
    v_parent_record RECORD;
    v_existing_id UUID;
BEGIN
    IF NOT (public.is_active_owner() OR public.is_active_tutor()) THEN
        RAISE EXCEPTION 'Access denied. Only authorized tutors or owner can create work reports.';
    END IF;

    -- Check idempotency
    SELECT id INTO v_existing_id 
    FROM public.daily_work_reports 
    WHERE idempotency_key = p_idempotency_key;

    IF v_existing_id IS NOT NULL THEN
        RETURN jsonb_build_object('success', true, 'report_id', v_existing_id, 'idempotent_duplicate', true);
    END IF;

    -- Validate active student
    SELECT * INTO v_student FROM public.students WHERE id = p_student_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Student % not found.', p_student_id;
    END IF;

    IF v_student.is_archived THEN
        RAISE EXCEPTION 'Cannot create work report for archived student %.', v_student.full_name;
    END IF;

    -- Insert work report
    INSERT INTO public.daily_work_reports (
        student_id, tutor_id, report_date, subject, work_completed, homework, progress_notes, idempotency_key
    ) VALUES (
        p_student_id, v_tutor_id, v_today, p_subject, p_work_completed, p_homework, p_progress_notes, p_idempotency_key
    ) RETURNING id INTO v_report_id;

    -- Notification resilience: Loop over linked parents and enqueue in-app and outbound notifications
    -- Any failure in notifications will NOT abort or rollback the work report insertion
    BEGIN
        FOR v_parent_record IN 
            SELECT psl.parent_id 
            FROM public.parent_student_links psl
            JOIN public.profiles p ON p.id = psl.parent_id
            WHERE psl.student_id = p_student_id AND psl.is_active = true AND p.is_active = true
        LOOP
            -- Create in-app notification
            INSERT INTO public.in_app_notifications (
                user_id,
                title,
                body,
                type,
                reference_id
            ) VALUES (
                v_parent_record.parent_id,
                'New Work Report Available',
                format('A new %s report has been recorded for %s.', p_subject, v_student.full_name),
                'WORK_REPORT',
                v_report_id
            );

            -- Enqueue push notification with privacy-preserving lock screen text
            INSERT INTO public.outbound_notification_queue (
                user_id,
                title,
                body,
                data_payload
            ) VALUES (
                v_parent_record.parent_id,
                'Daily Progress Update',
                'Your child has a new tuition update ready in Run for Victory.',
                jsonb_build_object(
                    'type', 'WORK_REPORT',
                    'report_id', v_report_id,
                    'student_id', p_student_id
                )
            );
        END LOOP;
    EXCEPTION WHEN OTHERS THEN
        -- Log failure to notice without rolling back report
        RAISE NOTICE 'Notification dispatch queueing failed: %', SQLERRM;
    END;

    RETURN jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'idempotent_duplicate', false
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 5.8 Edit Daily Work Report with Audit History
CREATE OR REPLACE FUNCTION public.edit_daily_work_report(
    p_report_id UUID,
    p_work_completed TEXT,
    p_homework TEXT,
    p_progress_notes TEXT,
    p_reason TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_report public.daily_work_reports%ROWTYPE;
BEGIN
    IF NOT (public.is_active_owner() OR public.is_active_tutor()) THEN
        RAISE EXCEPTION 'Access denied. Only owner or authoring tutor can edit work reports.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Edit reason is strictly required for audit.';
    END IF;

    SELECT * INTO v_report FROM public.daily_work_reports WHERE id = p_report_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Work report % not found.', p_report_id;
    END IF;

    -- If caller is tutor, must be author
    IF public.is_active_tutor() AND v_report.tutor_id != auth.uid() THEN
        RAISE EXCEPTION 'Access denied. Tutors can only edit reports they authored.';
    END IF;

    -- Record audit history
    INSERT INTO public.daily_work_report_edits (
        report_id, edited_by, previous_work_completed, previous_homework, previous_progress_notes, edit_reason
    ) VALUES (
        p_report_id, auth.uid(), v_report.work_completed, v_report.homework, v_report.progress_notes, p_reason
    );

    -- Update report
    UPDATE public.daily_work_reports
    SET work_completed = p_work_completed,
        homework = p_homework,
        progress_notes = p_progress_notes,
        updated_at = clock_timestamp()
    WHERE id = p_report_id;

    RETURN jsonb_build_object('success', true, 'report_id', p_report_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 5.9 Safe Student Archive & Restore
CREATE OR REPLACE FUNCTION public.archive_student(
    p_student_id UUID,
    p_reason TEXT
)
RETURNS JSONB AS $$
BEGIN
    IF NOT public.is_active_owner() THEN
        RAISE EXCEPTION 'Access denied. Only owner can archive students.';
    END IF;

    UPDATE public.students
    SET is_archived = true,
        archived_at = clock_timestamp(),
        updated_at = clock_timestamp()
    WHERE id = p_student_id;

    -- Notice: Parent links are preserved as per requirements!

    INSERT INTO public.attendance_audit_logs (
        record_type, record_id, modified_by, action, old_values, new_values, reason
    ) VALUES (
        'STUDENT', p_student_id, auth.uid(), 'MANUAL_CHECK_OUT', '{}'::jsonb, jsonb_build_object('is_archived', true), p_reason
    );

    RETURN jsonb_build_object('success', true, 'student_id', p_student_id, 'is_archived', true);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

CREATE OR REPLACE FUNCTION public.restore_student(
    p_student_id UUID
)
RETURNS JSONB AS $$
BEGIN
    IF NOT public.is_active_owner() THEN
        RAISE EXCEPTION 'Access denied. Only owner can restore students.';
    END IF;

    UPDATE public.students
    SET is_archived = false,
        archived_at = NULL,
        updated_at = clock_timestamp()
    WHERE id = p_student_id;

    INSERT INTO public.attendance_audit_logs (
        record_type, record_id, modified_by, action, old_values, new_values, reason
    ) VALUES (
        'STUDENT', p_student_id, auth.uid(), 'RESTORE', '{}'::jsonb, jsonb_build_object('is_archived', false), 'Owner restored student'
    );

    RETURN jsonb_build_object('success', true, 'student_id', p_student_id, 'is_archived', false);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

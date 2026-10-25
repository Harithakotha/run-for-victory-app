-- ============================================================================
-- Migration: 20261025_003_functions_and_rpcs.sql
-- Project: Run for Victory (By Ravi Kumar)
-- Database: PostgreSQL 15+ (Supabase)
-- Target: Atomic Attendance RPCs, GPS Validation, Audits & Notifications
-- ============================================================================

-- ============================================================================
-- 1. HAVERSINE GEODESIC DISTANCE CALCULATION
-- ============================================================================
CREATE OR REPLACE FUNCTION public.haversine_distance_meters(
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

REVOKE ALL ON FUNCTION public.haversine_distance_meters(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.haversine_distance_meters(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION) TO authenticated;

-- ============================================================================
-- 2. TEACHER ATTENDANCE ATOMIC CLOCK-IN
-- ============================================================================
CREATE OR REPLACE FUNCTION public.record_teacher_clock_in(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_accuracy DOUBLE PRECISION
)
RETURNS JSONB AS $$
DECLARE
    v_teacher_id UUID := auth.uid();
    v_centre_id UUID := public.auth_user_centre_id();
    v_role TEXT := public.auth_user_role();
    v_centre public.tuition_centres%ROWTYPE;
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_dist DOUBLE PRECISION;
    v_status TEXT := 'VERIFIED';
    v_session_status TEXT := 'OPEN';
    v_rejection_reason TEXT := NULL;
    v_existing_id UUID;
    v_result_id UUID;
BEGIN
    IF v_role != 'tutor' AND v_role != 'owner' THEN
        RAISE EXCEPTION 'Access Denied: Only active tutors or owners can clock in.';
    END IF;

    IF v_centre_id IS NULL THEN
        RAISE EXCEPTION 'User has no assigned tuition centre.';
    END IF;

    -- Fetch centre geofence configuration
    SELECT * INTO v_centre FROM public.tuition_centres WHERE id = v_centre_id AND is_active = true;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tuition centre not found or is currently inactive.';
    END IF;

    -- Data Rule: Prevent duplicate open sessions
    SELECT id INTO v_existing_id
    FROM public.teacher_attendance
    WHERE teacher_id = v_teacher_id 
      AND status = 'OPEN' 
      AND check_out_time IS NULL;

    IF v_existing_id IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate Open Session: You have an existing active clock-in session (%s). Clock out first.', v_existing_id;
    END IF;

    -- Check if verified attendance already completed today
    SELECT id INTO v_existing_id
    FROM public.teacher_attendance
    WHERE teacher_id = v_teacher_id 
      AND attendance_date = v_today 
      AND status IN ('OPEN', 'COMPLETED');

    IF v_existing_id IS NOT NULL THEN
        RAISE EXCEPTION 'Attendance for today (%s) has already been recorded.', v_today;
    END IF;

    -- Server-side Haversine verification
    v_dist := public.haversine_distance_meters(p_lat, p_lng, v_centre.latitude, v_centre.longitude);

    IF p_accuracy IS NULL OR p_accuracy > v_centre.max_location_accuracy_meters THEN
        v_status := 'REJECTED';
        v_session_status := 'REJECTED';
        v_rejection_reason := format('GPS accuracy insufficient (%.1f m > allowed %.1f m).', 
                                     COALESCE(p_accuracy, 999.0), v_centre.max_location_accuracy_meters);
    ELSIF v_dist > v_centre.radius_meters THEN
        v_status := 'REJECTED';
        v_session_status := 'REJECTED';
        v_rejection_reason := format('Location outside centre geofence (%.1f m away, max permitted %s m).', 
                                     v_dist, v_centre.radius_meters);
    END IF;

    INSERT INTO public.teacher_attendance (
        centre_id,
        teacher_id,
        attendance_date,
        check_in_time,
        status,
        check_in_lat,
        check_in_lng,
        check_in_accuracy,
        check_in_distance_meters,
        check_in_verification_status,
        check_in_rejection_reason
    ) VALUES (
        v_centre_id,
        v_teacher_id,
        v_today,
        v_now,
        v_session_status,
        p_lat,
        p_lng,
        p_accuracy,
        v_dist,
        v_status,
        v_rejection_reason
    ) RETURNING id INTO v_result_id;

    RETURN jsonb_build_object(
        'success', (v_status = 'VERIFIED'),
        'attendance_id', v_result_id,
        'status', v_status,
        'distance_meters', ROUND(v_dist::numeric, 1),
        'permitted_radius_meters', v_centre.radius_meters,
        'accuracy_meters', p_accuracy,
        'reason', v_rejection_reason,
        'check_in_time', v_now
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.record_teacher_clock_in(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_teacher_clock_in(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION) TO authenticated;

-- ============================================================================
-- 3. TEACHER ATTENDANCE ATOMIC CLOCK-OUT
-- ============================================================================
CREATE OR REPLACE FUNCTION public.record_teacher_clock_out(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_accuracy DOUBLE PRECISION
)
RETURNS JSONB AS $$
DECLARE
    v_teacher_id UUID := auth.uid();
    v_centre_id UUID := public.auth_user_centre_id();
    v_centre public.tuition_centres%ROWTYPE;
    v_att public.teacher_attendance%ROWTYPE;
    v_now TIMESTAMPTZ := clock_timestamp();
    v_dist DOUBLE PRECISION;
    v_status TEXT := 'VERIFIED';
    v_rejection_reason TEXT := NULL;
    v_duration INTEGER;
BEGIN
    SELECT * INTO v_centre FROM public.tuition_centres WHERE id = v_centre_id AND is_active = true;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tuition centre not found.';
    END IF;

    -- Fetch active open session
    SELECT * INTO v_att
    FROM public.teacher_attendance
    WHERE teacher_id = v_teacher_id 
      AND status = 'OPEN' 
      AND check_out_time IS NULL;

    IF v_att.id IS NULL THEN
        RAISE EXCEPTION 'No open attendance session found to clock out from.';
    END IF;

    v_dist := public.haversine_distance_meters(p_lat, p_lng, v_centre.latitude, v_centre.longitude);

    IF p_accuracy IS NULL OR p_accuracy > v_centre.max_location_accuracy_meters THEN
        v_status := 'REJECTED';
        v_rejection_reason := format('GPS accuracy insufficient (%.1f m > allowed %.1f m).', 
                                     COALESCE(p_accuracy, 999.0), v_centre.max_location_accuracy_meters);
    ELSIF v_dist > v_centre.radius_meters THEN
        v_status := 'REJECTED';
        v_rejection_reason := format('Location outside centre geofence (%.1f m away, max permitted %s m).', 
                                     v_dist, v_centre.radius_meters);
    END IF;

    v_duration := (EXTRACT(EPOCH FROM (v_now - v_att.check_in_time))::INTEGER / 60);

    UPDATE public.teacher_attendance
    SET check_out_time = v_now,
        status = 'COMPLETED',
        check_out_lat = p_lat,
        check_out_lng = p_lng,
        check_out_accuracy = p_accuracy,
        check_out_distance_meters = v_dist,
        check_out_verification_status = v_status,
        check_out_rejection_reason = v_rejection_reason,
        updated_at = v_now
    WHERE id = v_att.id;

    RETURN jsonb_build_object(
        'success', (v_status = 'VERIFIED'),
        'attendance_id', v_att.id,
        'status', v_status,
        'check_out_time', v_now,
        'duration_minutes', v_duration,
        'distance_meters', ROUND(v_dist::numeric, 1),
        'reason', v_rejection_reason
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.record_teacher_clock_out(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_teacher_clock_out(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION) TO authenticated;

-- ============================================================================
-- 4. STUDENT ATTENDANCE ATOMIC CHECK-IN
-- ============================================================================
CREATE OR REPLACE FUNCTION public.record_student_check_in(
    p_student_id UUID,
    p_status TEXT DEFAULT 'PRESENT',
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_role TEXT := public.auth_user_role();
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_student public.students%ROWTYPE;
    v_att_id UUID;
BEGIN
    IF v_role != 'owner' AND v_role != 'tutor' THEN
        RAISE EXCEPTION 'Access Denied: Only tutors or owners can record student attendance.';
    END IF;

    -- Validate student existence and centre tenant boundary
    SELECT * INTO v_student FROM public.students WHERE id = p_student_id AND centre_id = v_centre_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Student not found in your tuition centre.';
    END IF;

    -- Data Rule: Archived students cannot receive new attendance records
    IF v_student.status = 'ARCHIVED' THEN
        RAISE EXCEPTION 'Archived Student: Cannot mark attendance for archived student "%s".', v_student.full_name;
    END IF;

    -- Data Rule: Attendance must not be duplicated for the same student and session/date
    IF EXISTS (
        SELECT 1 FROM public.student_attendance 
        WHERE student_id = p_student_id AND attendance_date = v_today
    ) THEN
        RAISE EXCEPTION 'Duplicate Attendance: Student "%s" already has attendance recorded for today (%s).', 
                        v_student.full_name, v_today;
    END IF;

    INSERT INTO public.student_attendance (
        centre_id,
        student_id,
        attendance_date,
        check_in_time,
        status,
        recorded_by,
        notes
    ) VALUES (
        v_centre_id,
        p_student_id,
        v_today,
        v_now,
        p_status,
        auth.uid(),
        p_notes
    ) RETURNING id INTO v_att_id;

    RETURN jsonb_build_object(
        'success', true,
        'attendance_id', v_att_id,
        'student_id', p_student_id,
        'student_name', v_student.full_name,
        'check_in_time', v_now,
        'status', p_status
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.record_student_check_in(UUID, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_student_check_in(UUID, TEXT, TEXT) TO authenticated;

-- ============================================================================
-- 5. STUDENT ATTENDANCE ATOMIC CHECK-OUT
-- ============================================================================
CREATE OR REPLACE FUNCTION public.record_student_check_out(
    p_student_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_role TEXT := public.auth_user_role();
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_att public.student_attendance%ROWTYPE;
    v_duration INTEGER;
BEGIN
    IF v_role != 'owner' AND v_role != 'tutor' THEN
        RAISE EXCEPTION 'Access Denied: Only tutors or owners can record student checkout.';
    END IF;

    SELECT * INTO v_att
    FROM public.student_attendance
    WHERE student_id = p_student_id 
      AND centre_id = v_centre_id 
      AND attendance_date = v_today;

    IF v_att.id IS NULL THEN
        RAISE EXCEPTION 'No check-in record found for student today. Student must be checked in first.';
    END IF;

    IF v_att.check_out_time IS NOT NULL THEN
        RAISE EXCEPTION 'Student has already checked out for today at %.', v_att.check_out_time;
    END IF;

    v_duration := (EXTRACT(EPOCH FROM (v_now - v_att.check_in_time))::INTEGER / 60);

    UPDATE public.student_attendance
    SET check_out_time = v_now,
        updated_at = v_now
    WHERE id = v_att.id;

    RETURN jsonb_build_object(
        'success', true,
        'attendance_id', v_att.id,
        'check_out_time', v_now,
        'duration_minutes', v_duration
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.record_student_check_out(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_student_check_out(UUID) TO authenticated;

-- ============================================================================
-- 6. WORK LOG ATOMIC CREATION & IDEMPOTENT NOTIFICATION
-- ============================================================================
CREATE OR REPLACE FUNCTION public.create_work_log_entry(
    p_student_id UUID,
    p_subject TEXT,
    p_work_completed TEXT,
    p_homework TEXT DEFAULT NULL,
    p_progress_notes TEXT DEFAULT NULL,
    p_idempotency_key UUID DEFAULT gen_random_uuid()
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_role TEXT := public.auth_user_role();
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_student public.students%ROWTYPE;
    v_log_id UUID;
    v_existing_id UUID;
    v_parent_record RECORD;
BEGIN
    IF v_role != 'tutor' AND v_role != 'owner' THEN
        RAISE EXCEPTION 'Access Denied: Only tutors or owners can log daily work reports.';
    END IF;

    -- Idempotency check: prevent duplicate work reports during network retries
    SELECT id INTO v_existing_id 
    FROM public.work_logs 
    WHERE idempotency_key = p_idempotency_key;

    IF v_existing_id IS NOT NULL THEN
        RETURN jsonb_build_object(
            'success', true,
            'log_id', v_existing_id,
            'is_duplicate_retry', true
        );
    END IF;

    -- Validate student active status and tenant boundary
    SELECT * INTO v_student 
    FROM public.students 
    WHERE id = p_student_id AND centre_id = v_centre_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Student not found in your tuition centre.';
    END IF;

    IF v_student.status = 'ARCHIVED' THEN
        RAISE EXCEPTION 'Cannot record work log for archived student "%s".', v_student.full_name;
    END IF;

    INSERT INTO public.work_logs (
        centre_id,
        student_id,
        tutor_id,
        report_date,
        subject,
        work_completed,
        homework,
        progress_notes,
        idempotency_key
    ) VALUES (
        v_centre_id,
        p_student_id,
        auth.uid(),
        v_today,
        p_subject,
        p_work_completed,
        p_homework,
        p_progress_notes,
        p_idempotency_key
    ) RETURNING id INTO v_log_id;

    -- Safe in-app notification enqueue for linked parents
    BEGIN
        FOR v_parent_record IN
            SELECT pc.parent_id
            FROM public.parent_children pc
            JOIN public.profiles p ON p.id = pc.parent_id
            WHERE pc.student_id = p_student_id 
              AND pc.is_active = true 
              AND p.is_active = true
              AND pc.centre_id = v_centre_id
        LOOP
            INSERT INTO public.app_notifications (
                centre_id,
                user_id,
                title,
                body,
                type,
                reference_id
            ) VALUES (
                v_centre_id,
                v_parent_record.parent_id,
                format('New %s Work Report', p_subject),
                format('A new class report has been added for %s.', v_student.full_name),
                'WORK_LOG',
                v_log_id
            );
        END LOOP;
    EXCEPTION WHEN OTHERS THEN
        -- Notification failure must never roll back work log creation
        RAISE NOTICE 'Notification dispatch warning: %', SQLERRM;
    END;

    RETURN jsonb_build_object(
        'success', true,
        'log_id', v_log_id,
        'is_duplicate_retry', false
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.create_work_log_entry(UUID, TEXT, TEXT, TEXT, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_work_log_entry(UUID, TEXT, TEXT, TEXT, TEXT, UUID) TO authenticated;

-- ============================================================================
-- 7. AUDITED STUDENT ARCHIVE & RESTORE
-- ============================================================================
CREATE OR REPLACE FUNCTION public.archive_student_record(
    p_student_id UUID,
    p_reason TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_old JSONB;
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only centre owners can archive students.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Archival reason is required for audit trail.';
    END IF;

    SELECT to_jsonb(s) INTO v_old 
    FROM public.students 
    WHERE id = p_student_id AND centre_id = v_centre_id;

    IF v_old IS NULL THEN
        RAISE EXCEPTION 'Student not found in your centre.';
    END IF;

    UPDATE public.students
    SET status = 'ARCHIVED',
        archived_at = clock_timestamp(),
        archived_reason = p_reason,
        updated_at = clock_timestamp()
    WHERE id = p_student_id AND centre_id = v_centre_id;

    -- Note: Parent-child links and historical attendance are preserved.

    INSERT INTO public.audit_logs (
        centre_id,
        actor_id,
        action,
        entity_type,
        entity_id,
        old_values,
        new_values,
        reason
    ) VALUES (
        v_centre_id,
        auth.uid(),
        'ARCHIVE_STUDENT',
        'students',
        p_student_id,
        v_old,
        jsonb_build_object('status', 'ARCHIVED', 'reason', p_reason),
        p_reason
    );

    RETURN jsonb_build_object('success', true, 'student_id', p_student_id, 'status', 'ARCHIVED');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.archive_student_record(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.archive_student_record(UUID, TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.restore_student_record(
    p_student_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_old JSONB;
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only centre owners can restore students.';
    END IF;

    SELECT to_jsonb(s) INTO v_old 
    FROM public.students 
    WHERE id = p_student_id AND centre_id = v_centre_id;

    IF v_old IS NULL THEN
        RAISE EXCEPTION 'Student not found in your centre.';
    END IF;

    UPDATE public.students
    SET status = 'ACTIVE',
        archived_at = NULL,
        archived_reason = NULL,
        updated_at = clock_timestamp()
    WHERE id = p_student_id AND centre_id = v_centre_id;

    INSERT INTO public.audit_logs (
        centre_id,
        actor_id,
        action,
        entity_type,
        entity_id,
        old_values,
        new_values,
        reason
    ) VALUES (
        v_centre_id,
        auth.uid(),
        'RESTORE_STUDENT',
        'students',
        p_student_id,
        v_old,
        jsonb_build_object('status', 'ACTIVE'),
        'Owner restored student'
    );

    RETURN jsonb_build_object('success', true, 'student_id', p_student_id, 'status', 'ACTIVE');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.restore_student_record(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.restore_student_record(UUID) TO authenticated;

-- ============================================================================
-- 8. AUDITED ATTENDANCE CORRECTION & OVERRIDE
-- ============================================================================
CREATE OR REPLACE FUNCTION public.owner_override_teacher_attendance(
    p_attendance_id UUID,
    p_override_status TEXT,
    p_new_check_in TIMESTAMPTZ DEFAULT NULL,
    p_new_check_out TIMESTAMPTZ DEFAULT NULL,
    p_reason TEXT DEFAULT 'Owner GPS Verification Override'
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_old JSONB;
    v_new JSONB;
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only the centre owner can override teacher attendance.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Override reason is required for audit trail.';
    END IF;

    SELECT to_jsonb(t) INTO v_old 
    FROM public.teacher_attendance 
    WHERE id = p_attendance_id AND centre_id = v_centre_id;

    IF v_old IS NULL THEN
        RAISE EXCEPTION 'Teacher attendance record not found in your centre.';
    END IF;

    UPDATE public.teacher_attendance
    SET is_manual_override = true,
        override_by = auth.uid(),
        override_reason = p_reason,
        status = CASE WHEN p_new_check_out IS NOT NULL THEN 'COMPLETED' ELSE status END,
        check_in_verification_status = COALESCE(p_override_status, check_in_verification_status),
        check_in_time = COALESCE(p_new_check_in, check_in_time),
        check_out_time = COALESCE(p_new_check_out, check_out_time),
        updated_at = clock_timestamp()
    WHERE id = p_attendance_id AND centre_id = v_centre_id;

    SELECT to_jsonb(t) INTO v_new 
    FROM public.teacher_attendance 
    WHERE id = p_attendance_id;

    INSERT INTO public.audit_logs (
        centre_id,
        actor_id,
        action,
        entity_type,
        entity_id,
        old_values,
        new_values,
        reason
    ) VALUES (
        v_centre_id,
        auth.uid(),
        'TEACHER_ATTENDANCE_OVERRIDE',
        'teacher_attendance',
        p_attendance_id,
        v_old,
        v_new,
        p_reason
    );

    RETURN jsonb_build_object('success', true, 'attendance_id', p_attendance_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.owner_override_teacher_attendance(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.owner_override_teacher_attendance(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, TEXT) TO authenticated;

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
    v_centre_id UUID := public.auth_user_centre_id();
    v_old JSONB;
    v_new JSONB;
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only the centre owner can correct student attendance.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Correction reason is required for audit trail.';
    END IF;

    SELECT to_jsonb(a) INTO v_old 
    FROM public.student_attendance 
    WHERE id = p_attendance_id AND centre_id = v_centre_id;

    IF v_old IS NULL THEN
        RAISE EXCEPTION 'Student attendance record not found in your centre.';
    END IF;

    UPDATE public.student_attendance
    SET check_in_time = COALESCE(p_check_in, check_in_time),
        check_out_time = p_check_out,
        status = COALESCE(p_status, status),
        notes = COALESCE(p_notes, notes),
        updated_at = clock_timestamp()
    WHERE id = p_attendance_id AND centre_id = v_centre_id;

    SELECT to_jsonb(a) INTO v_new 
    FROM public.student_attendance 
    WHERE id = p_attendance_id;

    INSERT INTO public.audit_logs (
        centre_id,
        actor_id,
        action,
        entity_type,
        entity_id,
        old_values,
        new_values,
        reason
    ) VALUES (
        v_centre_id,
        auth.uid(),
        'STUDENT_ATTENDANCE_CORRECTION',
        'student_attendance',
        p_attendance_id,
        v_old,
        v_new,
        p_reason
    );

    RETURN jsonb_build_object('success', true, 'attendance_id', p_attendance_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.owner_correct_student_attendance(UUID, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.owner_correct_student_attendance(UUID, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, TEXT, TEXT) TO authenticated;

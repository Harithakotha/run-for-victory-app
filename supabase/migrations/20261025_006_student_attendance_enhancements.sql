-- ============================================================================
-- RUN FOR VICTORY - MIGRATION 20261025_006
-- Student Attendance Enhancements: Excused Status, Idempotent Retries,
-- Server Timestamps (Asia/Kolkata), and Audited Owner Corrections
-- ============================================================================

-- 1. ENHANCE STUDENT ATTENDANCE TABLE CONSTRAINT & AUDIT COLUMNS
ALTER TABLE public.student_attendance 
    DROP CONSTRAINT IF EXISTS student_attendance_status_check;

ALTER TABLE public.student_attendance 
    ADD CONSTRAINT student_attendance_status_check 
    CHECK (status IN ('PRESENT', 'LATE', 'HALF_DAY', 'ABSENT', 'EXCUSED'));

ALTER TABLE public.student_attendance 
    ADD COLUMN IF NOT EXISTS corrected_by UUID REFERENCES public.profiles(id),
    ADD COLUMN IF NOT EXISTS corrected_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS correction_reason TEXT,
    ADD COLUMN IF NOT EXISTS idempotency_key UUID DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS checkout_idempotency_key UUID;

CREATE INDEX IF NOT EXISTS idx_student_att_idempotency 
    ON public.student_attendance (idempotency_key);

CREATE INDEX IF NOT EXISTS idx_student_att_checkout_idempotency 
    ON public.student_attendance (checkout_idempotency_key);

-- ============================================================================
-- 2. ATOMIC STUDENT CHECK-IN WITH IDEMPOTENCY & SERVER TIMESTAMPS
-- ============================================================================
CREATE OR REPLACE FUNCTION public.record_student_check_in(
    p_student_id UUID,
    p_status TEXT DEFAULT 'PRESENT',
    p_notes TEXT DEFAULT NULL,
    p_idempotency_key UUID DEFAULT gen_random_uuid()
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_role TEXT := public.auth_user_role();
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_student public.students%ROWTYPE;
    v_att_id UUID;
    v_existing public.student_attendance%ROWTYPE;
BEGIN
    IF v_role != 'owner' AND v_role != 'tutor' THEN
        RAISE EXCEPTION 'Access Denied: Only tutors or owners can record student attendance.';
    END IF;

    -- Idempotency check: If this exact request was already processed, return previous record
    IF p_idempotency_key IS NOT NULL THEN
        SELECT * INTO v_existing 
        FROM public.student_attendance 
        WHERE idempotency_key = p_idempotency_key;

        IF v_existing.id IS NOT NULL THEN
            SELECT * INTO v_student FROM public.students WHERE id = v_existing.student_id;
            RETURN jsonb_build_object(
                'success', true,
                'attendance_id', v_existing.id,
                'student_id', v_existing.student_id,
                'student_name', v_student.full_name,
                'check_in_time', v_existing.check_in_time,
                'status', v_existing.status,
                'is_idempotent_replay', true
            );
        END IF;
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

    -- Validate status value
    IF p_status NOT IN ('PRESENT', 'LATE', 'HALF_DAY', 'ABSENT', 'EXCUSED') THEN
        RAISE EXCEPTION 'Invalid attendance status "%s". Allowed: PRESENT, LATE, HALF_DAY, ABSENT, EXCUSED.', p_status;
    END IF;

    -- Data Rule: Attendance must not be duplicated for the same student and session/date
    SELECT * INTO v_existing 
    FROM public.student_attendance 
    WHERE student_id = p_student_id AND attendance_date = v_today;

    IF v_existing.id IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate Attendance: Student "%s" already has attendance recorded for today (%s).', 
                        v_student.full_name, v_today;
    END IF;

    -- Insert authoritative record using server clock
    INSERT INTO public.student_attendance (
        centre_id,
        student_id,
        attendance_date,
        check_in_time,
        status,
        recorded_by,
        notes,
        idempotency_key
    ) VALUES (
        v_centre_id,
        p_student_id,
        v_today,
        v_now,
        p_status,
        auth.uid(),
        p_notes,
        p_idempotency_key
    ) RETURNING id INTO v_att_id;

    RETURN jsonb_build_object(
        'success', true,
        'attendance_id', v_att_id,
        'student_id', p_student_id,
        'student_name', v_student.full_name,
        'check_in_time', v_now,
        'status', p_status,
        'is_idempotent_replay', false
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.record_student_check_in(UUID, TEXT, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_student_check_in(UUID, TEXT, TEXT, UUID) TO authenticated;

-- ============================================================================
-- 3. ATOMIC STUDENT CHECK-OUT WITH OPEN SESSION GUARD & IDEMPOTENCY
-- ============================================================================
CREATE OR REPLACE FUNCTION public.record_student_check_out(
    p_student_id UUID,
    p_idempotency_key UUID DEFAULT gen_random_uuid()
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_role TEXT := public.auth_user_role();
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_now TIMESTAMPTZ := clock_timestamp();
    v_att public.student_attendance%ROWTYPE;
    v_duration INTEGER;
    v_student_name TEXT;
BEGIN
    IF v_role != 'owner' AND v_role != 'tutor' THEN
        RAISE EXCEPTION 'Access Denied: Only tutors or owners can record student checkout.';
    END IF;

    SELECT full_name INTO v_student_name 
    FROM public.students 
    WHERE id = p_student_id AND centre_id = v_centre_id;

    IF v_student_name IS NULL THEN
        RAISE EXCEPTION 'Student not found in your tuition centre.';
    END IF;

    -- Fetch today's session
    SELECT * INTO v_att
    FROM public.student_attendance
    WHERE student_id = p_student_id 
      AND centre_id = v_centre_id 
      AND attendance_date = v_today;

    IF v_att.id IS NULL THEN
        RAISE EXCEPTION 'No check-in record found for student "%s" today. Student must be checked in first.', v_student_name;
    END IF;

    -- If already checked out, check idempotency
    IF v_att.check_out_time IS NOT NULL THEN
        IF p_idempotency_key IS NOT NULL AND v_att.checkout_idempotency_key = p_idempotency_key THEN
            RETURN jsonb_build_object(
                'success', true,
                'attendance_id', v_att.id,
                'student_id', p_student_id,
                'student_name', v_student_name,
                'check_out_time', v_att.check_out_time,
                'duration_minutes', v_att.duration_minutes,
                'is_idempotent_replay', true
            );
        END IF;
        RAISE EXCEPTION 'Invalid Checkout: Student "%s" has already checked out for today at %.', v_student_name, v_att.check_out_time;
    END IF;

    -- Verify checkout time >= checkin time
    IF v_now < v_att.check_in_time THEN
        RAISE EXCEPTION 'Invalid Checkout: Checkout time cannot be earlier than check-in time.';
    END IF;

    v_duration := (EXTRACT(EPOCH FROM (v_now - v_att.check_in_time))::INTEGER / 60);

    UPDATE public.student_attendance
    SET check_out_time = v_now,
        checkout_idempotency_key = p_idempotency_key,
        updated_at = v_now
    WHERE id = v_att.id;

    RETURN jsonb_build_object(
        'success', true,
        'attendance_id', v_att.id,
        'student_id', p_student_id,
        'student_name', v_student_name,
        'check_out_time', v_now,
        'duration_minutes', v_duration,
        'is_idempotent_replay', false
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.record_student_check_out(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_student_check_out(UUID, UUID) TO authenticated;

-- ============================================================================
-- 4. AUDITED OWNER ATTENDANCE CORRECTION
-- ============================================================================
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
    v_now TIMESTAMPTZ := clock_timestamp();
    v_student_name TEXT;
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only the centre owner can correct student attendance.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Correction reason is required for audit trail.';
    END IF;

    IF p_check_out IS NOT NULL AND p_check_out < p_check_in THEN
        RAISE EXCEPTION 'Invalid Times: Check-out time (%s) cannot be earlier than check-in time (%s).', 
                        p_check_out, p_check_in;
    END IF;

    IF p_status NOT IN ('PRESENT', 'LATE', 'HALF_DAY', 'ABSENT', 'EXCUSED') THEN
        RAISE EXCEPTION 'Invalid attendance status "%s".', p_status;
    END IF;

    SELECT to_jsonb(a) INTO v_old 
    FROM public.student_attendance a
    WHERE a.id = p_attendance_id AND a.centre_id = v_centre_id;

    IF v_old IS NULL THEN
        RAISE EXCEPTION 'Student attendance record not found in your centre.';
    END IF;

    UPDATE public.student_attendance
    SET check_in_time = p_check_in,
        check_out_time = p_check_out,
        status = p_status,
        notes = p_notes,
        corrected_by = auth.uid(),
        corrected_at = v_now,
        correction_reason = p_reason,
        updated_at = v_now
    WHERE id = p_attendance_id;

    SELECT to_jsonb(a) INTO v_new 
    FROM public.student_attendance a
    WHERE a.id = p_attendance_id;

    -- Append audit entry
    INSERT INTO public.audit_logs (
        centre_id,
        user_id,
        action,
        table_name,
        record_id,
        old_data,
        new_data,
        reason
    ) VALUES (
        v_centre_id,
        auth.uid(),
        'UPDATE_STUDENT_ATTENDANCE_CORRECTION',
        'student_attendance',
        p_attendance_id,
        v_old,
        v_new,
        p_reason
    );

    RETURN jsonb_build_object(
        'success', true, 
        'attendance_id', p_attendance_id,
        'corrected_at', v_now
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.owner_correct_student_attendance(UUID, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.owner_correct_student_attendance(UUID, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, TEXT, TEXT) TO authenticated;

-- ============================================================================
-- 5. DAILY ATTENDANCE SUMMARY FUNCTION
-- ============================================================================
CREATE OR REPLACE FUNCTION public.get_daily_attendance_summary(
    p_date DATE DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_target_date DATE := COALESCE(p_date, (timezone('Asia/Kolkata', now())::date));
    v_total_active INT := 0;
    v_present INT := 0;
    v_late INT := 0;
    v_half_day INT := 0;
    v_absent INT := 0;
    v_excused INT := 0;
    v_unrecorded INT := 0;
    v_percentage NUMERIC := 0.0;
BEGIN
    -- Count active students in centre
    SELECT COUNT(*) INTO v_total_active 
    FROM public.students 
    WHERE centre_id = v_centre_id AND status = 'ACTIVE';

    -- Count attendance statuses for this date
    SELECT 
        COUNT(*) FILTER (WHERE status = 'PRESENT'),
        COUNT(*) FILTER (WHERE status = 'LATE'),
        COUNT(*) FILTER (WHERE status = 'HALF_DAY'),
        COUNT(*) FILTER (WHERE status = 'ABSENT'),
        COUNT(*) FILTER (WHERE status = 'EXCUSED')
    INTO v_present, v_late, v_half_day, v_absent, v_excused
    FROM public.student_attendance
    WHERE centre_id = v_centre_id AND attendance_date = v_target_date;

    v_unrecorded := GREATEST(0, v_total_active - (v_present + v_late + v_half_day + v_absent + v_excused));

    IF v_total_active > 0 THEN
        v_percentage := ROUND(((v_present + v_late + (v_half_day * 0.5))::numeric / v_total_active::numeric) * 100.0, 1);
    END IF;

    RETURN jsonb_build_object(
        'date', v_target_date,
        'total_active_students', v_total_active,
        'present_count', v_present,
        'late_count', v_late,
        'half_day_count', v_half_day,
        'absent_count', v_absent,
        'excused_count', v_excused,
        'unrecorded_count', v_unrecorded,
        'attendance_percentage', v_percentage
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.get_daily_attendance_summary(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_daily_attendance_summary(DATE) TO authenticated;

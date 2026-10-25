-- ============================================================================
-- RUN FOR VICTORY - MIGRATION 20261025_007
-- Teacher Attendance GPS Verification Enhancements:
-- Centre Settings, Server-Side Geofence Verification, Anti-Staleness,
-- Single Open Session Enforcer, and Audited Owner Override Policy
-- ============================================================================

-- 1. ADD CENTRE LOCATION SETTINGS & OVERRIDE POLICY
ALTER TABLE public.tuition_centres
    ADD COLUMN IF NOT EXISTS max_location_age_seconds INTEGER NOT NULL DEFAULT 120,
    ADD COLUMN IF NOT EXISTS owner_override_policy TEXT NOT NULL DEFAULT 'Owner can override GPS failures with documented audit reason';

-- 2. ENHANCE TEACHER ATTENDANCE TABLE AUDIT COLUMNS
ALTER TABLE public.teacher_attendance
    ADD COLUMN IF NOT EXISTS override_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS is_mock_flagged BOOLEAN NOT NULL DEFAULT false;

-- ============================================================================
-- 3. SERVER-SIDE TRUSTED TEACHER CLOCK-IN RPC
-- ============================================================================
CREATE OR REPLACE FUNCTION public.record_teacher_clock_in(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_accuracy DOUBLE PRECISION,
    p_reading_timestamp_ms BIGINT DEFAULT NULL,
    p_is_mock BOOLEAN DEFAULT false
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
    v_age_seconds BIGINT;
BEGIN
    IF v_role != 'tutor' AND v_role != 'owner' THEN
        RAISE EXCEPTION 'Access Denied: Only teachers or owners can clock in.';
    END IF;

    SELECT * INTO v_centre FROM public.tuition_centres WHERE id = v_centre_id AND is_active = true;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tuition centre not found or inactive.';
    END IF;

    -- Prevent duplicate open sessions
    SELECT id INTO v_existing_id
    FROM public.teacher_attendance
    WHERE teacher_id = v_teacher_id 
      AND status = 'OPEN' 
      AND check_out_time IS NULL;

    IF v_existing_id IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate Open Session: You have an existing active clock-in session (%s). Clock out first.', v_existing_id;
    END IF;

    -- Check if completed attendance already exists for today
    SELECT id INTO v_existing_id
    FROM public.teacher_attendance
    WHERE teacher_id = v_teacher_id 
      AND attendance_date = v_today 
      AND status = 'COMPLETED';

    IF v_existing_id IS NOT NULL THEN
        RAISE EXCEPTION 'Attendance for today (%s) has already been completed.', v_today;
    END IF;

    -- 1. Anti-Spoofing & Staleness Verification
    IF p_is_mock THEN
        v_status := 'REJECTED';
        v_session_status := 'REJECTED';
        v_rejection_reason := 'Mock location provider detected by client system.';
    ELSIF p_reading_timestamp_ms IS NOT NULL THEN
        v_age_seconds := ROUND((EXTRACT(EPOCH FROM clock_timestamp()) * 1000 - p_reading_timestamp_ms) / 1000);
        IF v_age_seconds > v_centre.max_location_age_seconds THEN
            v_status := 'REJECTED';
            v_session_status := 'REJECTED';
            v_rejection_reason := format('Location reading is stale (%s s old > max permitted %s s).', 
                                         v_age_seconds, v_centre.max_location_age_seconds);
        END IF;
    END IF;

    -- 2. Server-side Haversine Distance & Accuracy Verification
    v_dist := public.haversine_distance_meters(p_lat, p_lng, v_centre.latitude, v_centre.longitude);

    IF v_status = 'VERIFIED' THEN
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
        check_in_rejection_reason,
        is_mock_flagged
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
        v_rejection_reason,
        p_is_mock
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

REVOKE ALL ON FUNCTION public.record_teacher_clock_in(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, BIGINT, BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_teacher_clock_in(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, BIGINT, BOOLEAN) TO authenticated;

-- ============================================================================
-- 4. SERVER-SIDE TRUSTED TEACHER CLOCK-OUT RPC
-- ============================================================================
CREATE OR REPLACE FUNCTION public.record_teacher_clock_out(
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_accuracy DOUBLE PRECISION,
    p_reading_timestamp_ms BIGINT DEFAULT NULL,
    p_is_mock BOOLEAN DEFAULT false
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
    v_age_seconds BIGINT;
BEGIN
    SELECT * INTO v_centre FROM public.tuition_centres WHERE id = v_centre_id AND is_active = true;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tuition centre not found or inactive.';
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

    -- Staleness Check
    IF p_is_mock THEN
        v_status := 'REJECTED';
        v_rejection_reason := 'Mock location provider detected during check-out.';
    ELSIF p_reading_timestamp_ms IS NOT NULL THEN
        v_age_seconds := ROUND((EXTRACT(EPOCH FROM clock_timestamp()) * 1000 - p_reading_timestamp_ms) / 1000);
        IF v_age_seconds > v_centre.max_location_age_seconds THEN
            v_status := 'REJECTED';
            v_rejection_reason := format('Location reading is stale (%s s > max %s s).', 
                                         v_age_seconds, v_centre.max_location_age_seconds);
        END IF;
    END IF;

    v_dist := public.haversine_distance_meters(p_lat, p_lng, v_centre.latitude, v_centre.longitude);

    IF v_status = 'VERIFIED' THEN
        IF p_accuracy IS NULL OR p_accuracy > v_centre.max_location_accuracy_meters THEN
            v_status := 'REJECTED';
            v_rejection_reason := format('GPS accuracy insufficient (%.1f m > allowed %.1f m).', 
                                         COALESCE(p_accuracy, 999.0), v_centre.max_location_accuracy_meters);
        ELSIF v_dist > v_centre.radius_meters THEN
            v_status := 'REJECTED';
            v_rejection_reason := format('Location outside centre geofence (%.1f m away, max permitted %s m).', 
                                         v_dist, v_centre.radius_meters);
        END IF;
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

REVOKE ALL ON FUNCTION public.record_teacher_clock_out(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, BIGINT, BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_teacher_clock_out(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, BIGINT, BOOLEAN) TO authenticated;

-- ============================================================================
-- 5. AUDITED OWNER TEACHER OVERRIDE RPC
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
    v_now TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only the centre owner can override teacher attendance.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' OR LENGTH(TRIM(p_reason)) < 5 THEN
        RAISE EXCEPTION 'Override reason is required for audit trail (min 5 chars).';
    END IF;

    SELECT to_jsonb(t) INTO v_old 
    FROM public.teacher_attendance t
    WHERE t.id = p_attendance_id AND t.centre_id = v_centre_id;

    IF v_old IS NULL THEN
        RAISE EXCEPTION 'Teacher attendance record not found in your centre.';
    END IF;

    UPDATE public.teacher_attendance
    SET is_manual_override = true,
        override_by = auth.uid(),
        override_reason = p_reason,
        override_at = v_now,
        status = CASE WHEN p_new_check_out IS NOT NULL THEN 'COMPLETED' ELSE status END,
        check_in_verification_status = COALESCE(p_override_status, check_in_verification_status),
        check_in_time = COALESCE(p_new_check_in, check_in_time),
        check_out_time = COALESCE(p_new_check_out, check_out_time),
        updated_at = v_now
    WHERE id = p_attendance_id AND centre_id = v_centre_id;

    SELECT to_jsonb(t) INTO v_new 
    FROM public.teacher_attendance t
    WHERE t.id = p_attendance_id;

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
        'TEACHER_ATTENDANCE_OVERRIDE',
        'teacher_attendance',
        p_attendance_id,
        v_old,
        v_new,
        p_reason
    );

    RETURN jsonb_build_object(
        'success', true, 
        'attendance_id', p_attendance_id,
        'override_at', v_now
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.owner_override_teacher_attendance(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.owner_override_teacher_attendance(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, TEXT) TO authenticated;

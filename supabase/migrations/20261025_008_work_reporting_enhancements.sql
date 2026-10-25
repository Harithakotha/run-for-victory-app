-- ============================================================================
-- RUN FOR VICTORY - MIGRATION 20261025_008
-- Work Reporting Enhancements:
-- Pending Work Column, Audited Report Edits, Parent Retrieval RPC,
-- and Idempotency Guard
-- ============================================================================

-- 1. ADD PENDING WORK AND AUDIT COLUMNS TO WORK LOGS
ALTER TABLE public.work_logs
    ADD COLUMN IF NOT EXISTS pending_work TEXT,
    ADD COLUMN IF NOT EXISTS is_edited BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS edited_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS edit_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_work_logs_date ON public.work_logs (centre_id, report_date DESC);
CREATE INDEX IF NOT EXISTS idx_work_logs_student_date ON public.work_logs (student_id, report_date DESC);

-- ============================================================================
-- 2. ENHANCED ATOMIC WORK LOG CREATION WITH IDEMPOTENCY
-- ============================================================================
CREATE OR REPLACE FUNCTION public.create_work_log_entry(
    p_student_id UUID,
    p_subject TEXT,
    p_work_completed TEXT,
    p_homework TEXT DEFAULT NULL,
    p_progress_notes TEXT DEFAULT NULL,
    p_pending_work TEXT DEFAULT NULL,
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

    IF p_subject IS NULL OR TRIM(p_subject) = '' THEN
        RAISE EXCEPTION 'Validation Error: Subject is required.';
    END IF;

    IF p_work_completed IS NULL OR TRIM(p_work_completed) = '' THEN
        RAISE EXCEPTION 'Validation Error: Work completed description is required.';
    END IF;

    -- Idempotency check: prevent duplicate work reports during network retries / timeouts
    SELECT id INTO v_existing_id 
    FROM public.work_logs 
    WHERE idempotency_key = p_idempotency_key;

    IF v_existing_id IS NOT NULL THEN
        RETURN jsonb_build_object(
            'success', true,
            'log_id', v_existing_id,
            'is_duplicate_retry', true,
            'message', 'Report already logged with this idempotency key.'
        );
    END IF;

    -- Validate student active status and tenant boundary
    SELECT * INTO v_student 
    FROM public.students 
    WHERE id = p_student_id AND centre_id = v_centre_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Student not found in your tuition centre.';
    END IF;

    IF v_student.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'Cannot create work report for an inactive or archived student.';
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
        pending_work,
        idempotency_key,
        created_at,
        updated_at
    ) VALUES (
        v_centre_id,
        p_student_id,
        auth.uid(),
        v_today,
        TRIM(p_subject),
        TRIM(p_work_completed),
        NULLIF(TRIM(p_homework), ''),
        NULLIF(TRIM(p_progress_notes), ''),
        NULLIF(TRIM(p_pending_work), ''),
        p_idempotency_key,
        clock_timestamp(),
        clock_timestamp()
    ) RETURNING id INTO v_log_id;

    -- Enqueue notification to linked parents
    FOR v_parent_record IN (
        SELECT pc.parent_id 
        FROM public.parent_children pc
        JOIN public.profiles p ON p.id = pc.parent_id
        WHERE pc.student_id = p_student_id 
          AND p.centre_id = v_centre_id 
          AND p.is_active = true
    ) LOOP
        INSERT INTO public.app_notifications (
            centre_id,
            user_id,
            title,
            body,
            notification_type,
            reference_id
        ) VALUES (
            v_centre_id,
            v_parent_record.parent_id,
            format('New Work Report: %s (%s)', v_student.full_name, TRIM(p_subject)),
            format('Work completed: %s', LEFT(TRIM(p_work_completed), 100)),
            'WORK_LOG',
            v_log_id
        );
    END LOOP;

    RETURN jsonb_build_object(
        'success', true,
        'log_id', v_log_id,
        'is_duplicate_retry', false,
        'report_date', v_today
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.create_work_log_entry(UUID, TEXT, TEXT, TEXT, TEXT, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_work_log_entry(UUID, TEXT, TEXT, TEXT, TEXT, TEXT, UUID) TO authenticated;

-- ============================================================================
-- 3. AUDITED WORK LOG EDIT RPC
-- ============================================================================
CREATE OR REPLACE FUNCTION public.edit_work_log_entry(
    p_report_id UUID,
    p_subject TEXT,
    p_work_completed TEXT,
    p_homework TEXT DEFAULT NULL,
    p_pending_work TEXT DEFAULT NULL,
    p_progress_notes TEXT DEFAULT NULL,
    p_reason TEXT DEFAULT 'Work report correction'
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_role TEXT := public.auth_user_role();
    v_old JSONB;
    v_new JSONB;
    v_target public.work_logs%ROWTYPE;
    v_now TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF v_role != 'tutor' AND v_role != 'owner' THEN
        RAISE EXCEPTION 'Access Denied: Only tutors or owners can edit work reports.';
    END IF;

    IF p_reason IS NULL OR LENGTH(TRIM(p_reason)) < 5 THEN
        RAISE EXCEPTION 'Audit requirement: Edit reason must be at least 5 characters.';
    END IF;

    IF p_subject IS NULL OR TRIM(p_subject) = '' THEN
        RAISE EXCEPTION 'Validation Error: Subject cannot be blank.';
    END IF;

    IF p_work_completed IS NULL OR TRIM(p_work_completed) = '' THEN
        RAISE EXCEPTION 'Validation Error: Work completed description cannot be blank.';
    END IF;

    SELECT * INTO v_target 
    FROM public.work_logs 
    WHERE id = p_report_id AND centre_id = v_centre_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Work report not found in your tuition centre.';
    END IF;

    -- Tutors can only edit their own reports; owners can edit any report in centre
    IF v_role = 'tutor' AND v_target.tutor_id != auth.uid() THEN
        RAISE EXCEPTION 'Permission Denied: Tutors can only edit their own authored reports.';
    END IF;

    SELECT to_jsonb(v_target) INTO v_old;

    UPDATE public.work_logs
    SET subject = TRIM(p_subject),
        work_completed = TRIM(p_work_completed),
        homework = NULLIF(TRIM(p_homework), ''),
        pending_work = NULLIF(TRIM(p_pending_work), ''),
        progress_notes = NULLIF(TRIM(p_progress_notes), ''),
        is_edited = true,
        edited_by = auth.uid(),
        edited_at = v_now,
        edit_reason = TRIM(p_reason),
        updated_at = v_now
    WHERE id = p_report_id;

    SELECT to_jsonb(w) INTO v_new 
    FROM public.work_logs w 
    WHERE w.id = p_report_id;

    -- Insert into immutable audit log
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
        'WORK_REPORT_EDIT',
        'work_logs',
        p_report_id,
        v_old,
        v_new,
        TRIM(p_reason)
    );

    RETURN jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'edited_at', v_now
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.edit_work_log_entry(UUID, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.edit_work_log_entry(UUID, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated;

-- ============================================================================
-- 4. PARENT SECURE WORK LOG VIEW (RLS Enforcement Verified)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.get_parent_work_reports(
    p_student_id UUID DEFAULT NULL
)
RETURNS TABLE (
    id UUID,
    student_id UUID,
    student_name TEXT,
    tutor_name TEXT,
    report_date DATE,
    subject TEXT,
    work_completed TEXT,
    homework TEXT,
    pending_work TEXT,
    progress_notes TEXT,
    is_edited BOOLEAN,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
) AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_parent_id UUID := auth.uid();
BEGIN
    RETURN QUERY
    SELECT 
        w.id,
        w.student_id,
        s.full_name AS student_name,
        p.full_name AS tutor_name,
        w.report_date,
        w.subject,
        w.work_completed,
        w.homework,
        w.pending_work,
        w.progress_notes,
        w.is_edited,
        w.created_at,
        w.updated_at
    FROM public.work_logs w
    JOIN public.students s ON s.id = w.student_id
    JOIN public.profiles p ON p.id = w.tutor_id
    JOIN public.parent_children pc ON pc.student_id = w.student_id
    WHERE pc.parent_id = v_parent_id
      AND w.centre_id = v_centre_id
      AND (p_student_id IS NULL OR w.student_id = p_student_id)
    ORDER BY w.report_date DESC, w.created_at DESC;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.get_parent_work_reports(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_parent_work_reports(UUID) TO authenticated;

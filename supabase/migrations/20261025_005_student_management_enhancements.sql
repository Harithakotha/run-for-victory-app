-- ============================================================================
-- Migration: 20261025_005_student_management_enhancements.sql
-- Project: Run for Victory (By Ravi Kumar)
-- Database: PostgreSQL 15+ (Supabase)
-- Target: Student Management Fields, Archival Actor Tracking & Restricted Deletion
-- ============================================================================

-- Add archived_by and notes to students table
ALTER TABLE public.students 
ADD COLUMN IF NOT EXISTS archived_by UUID REFERENCES public.profiles(id),
ADD COLUMN IF NOT EXISTS notes TEXT;

-- ============================================================================
-- 1. UPDATE ARCHIVE RPC TO CAPTURE AUTHORISED ACTOR
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
        RAISE EXCEPTION 'Validation Error: Archival reason is required for audit trail.';
    END IF;

    SELECT to_jsonb(s) INTO v_old 
    FROM public.students 
    WHERE id = p_student_id AND centre_id = v_centre_id;

    IF v_old IS NULL THEN
        RAISE EXCEPTION 'Student not found in your tuition centre.';
    END IF;

    -- Update student status and actor
    UPDATE public.students
    SET status = 'ARCHIVED',
        archived_at = clock_timestamp(),
        archived_reason = p_reason,
        archived_by = auth.uid(),
        updated_at = clock_timestamp()
    WHERE id = p_student_id AND centre_id = v_centre_id;

    -- Historical records (attendance, work_logs, parent_children) are strictly preserved!

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
        jsonb_build_object('status', 'ARCHIVED', 'archived_by', auth.uid(), 'reason', p_reason),
        p_reason
    );

    RETURN jsonb_build_object(
        'success', true,
        'student_id', p_student_id,
        'status', 'ARCHIVED',
        'archived_at', clock_timestamp(),
        'archived_by', auth.uid()
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- ============================================================================
-- 2. RESTORE STUDENT RPC
-- ============================================================================
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
        RAISE EXCEPTION 'Student not found in your tuition centre.';
    END IF;

    UPDATE public.students
    SET status = 'ACTIVE',
        archived_at = NULL,
        archived_reason = NULL,
        archived_by = NULL,
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
        'Owner restored student to active directory'
    );

    RETURN jsonb_build_object('success', true, 'student_id', p_student_id, 'status', 'ACTIVE');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- ============================================================================
-- 3. PERMANENT DELETION RESTRICTED WORKFLOW (Separate, Documented Workflow)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.permanent_delete_student(
    p_student_id UUID,
    p_confirmation_phrase TEXT,
    p_justification TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_student public.students%ROWTYPE;
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only centre owners can perform permanent deletion.';
    END IF;

    -- Strict confirmation safety phrase
    IF p_confirmation_phrase != 'DELETE PERMANENTLY' THEN
        RAISE EXCEPTION 'Safety Guard: Confirmation phrase mismatch. You must confirm with "DELETE PERMANENTLY".';
    END IF;

    IF p_justification IS NULL OR length(trim(p_justification)) < 10 THEN
        RAISE EXCEPTION 'Audit Requirement: A detailed justification (min 10 characters) is required for permanent deletion.';
    END IF;

    SELECT * INTO v_student FROM public.students WHERE id = p_student_id AND centre_id = v_centre_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Student not found in your tuition centre.';
    END IF;

    -- Audit before deletion
    INSERT INTO public.audit_logs (
        centre_id,
        actor_id,
        action,
        entity_type,
        entity_id,
        old_values,
        reason
    ) VALUES (
        v_centre_id,
        auth.uid(),
        'PERMANENT_DELETE_STUDENT',
        'students',
        p_student_id,
        to_jsonb(v_student),
        p_justification
    );

    -- Clean up dependencies in strict order
    DELETE FROM public.work_logs WHERE student_id = p_student_id;
    DELETE FROM public.student_attendance WHERE student_id = p_student_id;
    DELETE FROM public.parent_children WHERE student_id = p_student_id;
    DELETE FROM public.students WHERE id = p_student_id AND centre_id = v_centre_id;

    RETURN jsonb_build_object(
        'success', true,
        'deleted_student_id', p_student_id,
        'deleted_student_name', v_student.full_name
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.permanent_delete_student(UUID, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.permanent_delete_student(UUID, TEXT, TEXT) TO authenticated;

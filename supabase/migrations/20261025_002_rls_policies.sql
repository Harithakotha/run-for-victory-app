-- ============================================================================
-- Migration: 20261025_002_rls_policies.sql
-- Project: Run for Victory (By Ravi Kumar)
-- Database: PostgreSQL 15+ (Supabase)
-- Target: Zero-Trust Row-Level Security (RLS) Policies & Role Resolvers
-- ============================================================================

-- ============================================================================
-- 1. NON-RECURSIVE SECURITY DEFINER HELPERS
-- ============================================================================

-- Get caller's active role without recursion
CREATE OR REPLACE FUNCTION public.auth_user_role()
RETURNS TEXT AS $$
DECLARE
    v_role TEXT;
    v_active BOOLEAN;
BEGIN
    SELECT role, is_active INTO v_role, v_active
    FROM public.profiles
    WHERE id = auth.uid();

    IF v_active IS NOT TRUE THEN
        RETURN 'none';
    END IF;

    RETURN COALESCE(v_role, 'none');
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- Get caller's assigned tuition centre ID
CREATE OR REPLACE FUNCTION public.auth_user_centre_id()
RETURNS UUID AS $$
DECLARE
    v_centre_id UUID;
    v_active BOOLEAN;
BEGIN
    SELECT centre_id, is_active INTO v_centre_id, v_active
    FROM public.profiles
    WHERE id = auth.uid();

    IF v_active IS NOT TRUE THEN
        RETURN NULL;
    END IF;

    RETURN v_centre_id;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- Check if caller is active owner of the target centre
CREATE OR REPLACE FUNCTION public.is_centre_owner(p_centre_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (
        public.auth_user_role() = 'owner' 
        AND public.auth_user_centre_id() = p_centre_id
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- Check if caller is parent explicitly linked to student
CREATE OR REPLACE FUNCTION public.is_parent_of_student(p_student_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
    IF public.auth_user_role() != 'parent' THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1 FROM public.parent_children pc
        WHERE pc.parent_id = auth.uid()
          AND pc.student_id = p_student_id
          AND pc.is_active = true
          AND pc.centre_id = public.auth_user_centre_id()
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public;

-- Restrict execution permissions
REVOKE ALL ON FUNCTION public.auth_user_role() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.auth_user_role() TO authenticated;

REVOKE ALL ON FUNCTION public.auth_user_centre_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.auth_user_centre_id() TO authenticated;

REVOKE ALL ON FUNCTION public.is_centre_owner(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_centre_owner(UUID) TO authenticated;

REVOKE ALL ON FUNCTION public.is_parent_of_student(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_parent_of_student(UUID) TO authenticated;

-- ============================================================================
-- 2. PREVENT ROLE & CENTRE TAMPERING TRIGGER
-- ============================================================================
CREATE OR REPLACE FUNCTION public.trg_guard_profile_mutation()
RETURNS TRIGGER AS $$
BEGIN
    -- Only service role or active owner of the centre can change roles or centre IDs
    IF (OLD.role IS DISTINCT FROM NEW.role OR OLD.centre_id IS DISTINCT FROM NEW.centre_id) THEN
        IF NOT public.is_centre_owner(OLD.centre_id) THEN
            RAISE EXCEPTION 'Security Violation: Users cannot modify their own role or centre membership.';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

DROP TRIGGER IF EXISTS guard_profile_mutation ON public.profiles;
CREATE TRIGGER guard_profile_mutation
    BEFORE UPDATE ON public.profiles
    FOR EACH ROW
    EXECUTE FUNCTION public.trg_guard_profile_mutation();

-- ============================================================================
-- 3. ENABLE RLS ON ALL TABLES (Deny-by-default)
-- ============================================================================
ALTER TABLE public.tuition_centres ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.students ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.parent_children ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.teacher_attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.student_attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.account_invitations ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- 4. TUITION CENTRES POLICIES
-- ============================================================================
CREATE POLICY "tuition_centres_select_members"
ON public.tuition_centres FOR SELECT
TO authenticated
USING (id = public.auth_user_centre_id());

CREATE POLICY "tuition_centres_owner_manage"
ON public.tuition_centres FOR ALL
TO authenticated
USING (public.is_centre_owner(id))
WITH CHECK (public.is_centre_owner(id));

-- ============================================================================
-- 5. PROFILES POLICIES
-- ============================================================================
-- Read own profile
CREATE POLICY "profiles_select_own"
ON public.profiles FOR SELECT
TO authenticated
USING (id = auth.uid());

-- Owner can read all profiles in their centre
CREATE POLICY "profiles_select_centre_owner"
ON public.profiles FOR SELECT
TO authenticated
USING (public.is_centre_owner(centre_id));

-- Tutors can read active tutors and students' guardian profiles in their centre
CREATE POLICY "profiles_select_tutor"
ON public.profiles FOR SELECT
TO authenticated
USING (
    public.auth_user_role() = 'tutor'
    AND centre_id = public.auth_user_centre_id()
    AND is_active = true
);

-- Users can update non-privileged fields on their own profile
CREATE POLICY "profiles_update_own"
ON public.profiles FOR UPDATE
TO authenticated
USING (id = auth.uid())
WITH CHECK (id = auth.uid());

-- Owner can manage all profiles in their centre
CREATE POLICY "profiles_owner_all"
ON public.profiles FOR ALL
TO authenticated
USING (public.is_centre_owner(centre_id))
WITH CHECK (public.is_centre_owner(centre_id));

-- ============================================================================
-- 6. STUDENTS POLICIES
-- ============================================================================
-- Owner full management within their centre
CREATE POLICY "students_owner_all"
ON public.students FOR ALL
TO authenticated
USING (public.is_centre_owner(centre_id))
WITH CHECK (public.is_centre_owner(centre_id));

-- Tutors can view active (non-archived) students in their centre
CREATE POLICY "students_tutor_select"
ON public.students FOR SELECT
TO authenticated
USING (
    public.auth_user_role() = 'tutor'
    AND centre_id = public.auth_user_centre_id()
    AND status = 'ACTIVE'
);

-- Parents can view only their linked children
CREATE POLICY "students_parent_select"
ON public.students FOR SELECT
TO authenticated
USING (
    public.is_parent_of_student(id)
    AND centre_id = public.auth_user_centre_id()
);

-- ============================================================================
-- 7. PARENT_CHILDREN POLICIES
-- ============================================================================
-- Owner can manage parent links for their centre
CREATE POLICY "parent_children_owner_all"
ON public.parent_children FOR ALL
TO authenticated
USING (public.is_centre_owner(centre_id))
WITH CHECK (public.is_centre_owner(centre_id));

-- Parents can view their own child links
CREATE POLICY "parent_children_parent_select"
ON public.parent_children FOR SELECT
TO authenticated
USING (
    parent_id = auth.uid()
    AND centre_id = public.auth_user_centre_id()
);

-- ============================================================================
-- 8. TEACHER ATTENDANCE POLICIES
-- ============================================================================
-- Owner full management within centre
CREATE POLICY "teacher_attendance_owner_all"
ON public.teacher_attendance FOR ALL
TO authenticated
USING (public.is_centre_owner(centre_id))
WITH CHECK (public.is_centre_owner(centre_id));

-- Teacher can view own attendance records
CREATE POLICY "teacher_attendance_select_own"
ON public.teacher_attendance FOR SELECT
TO authenticated
USING (
    teacher_id = auth.uid()
    AND centre_id = public.auth_user_centre_id()
);

-- Direct INSERT/UPDATE is restricted: mutations MUST happen through verified RPCs.
-- The RPC functions run as SECURITY DEFINER with Haversine GPS verification.

-- ============================================================================
-- 9. STUDENT ATTENDANCE POLICIES
-- ============================================================================
-- Owner full access within centre
CREATE POLICY "student_attendance_owner_all"
ON public.student_attendance FOR ALL
TO authenticated
USING (public.is_centre_owner(centre_id))
WITH CHECK (public.is_centre_owner(centre_id));

-- Tutors can view student attendance for their centre
CREATE POLICY "student_attendance_tutor_select"
ON public.student_attendance FOR SELECT
TO authenticated
USING (
    public.auth_user_role() = 'tutor'
    AND centre_id = public.auth_user_centre_id()
);

-- Tutors can insert attendance for active students in their centre for today
CREATE POLICY "student_attendance_tutor_insert"
ON public.student_attendance FOR INSERT
TO authenticated
WITH CHECK (
    public.auth_user_role() = 'tutor'
    AND centre_id = public.auth_user_centre_id()
    AND recorded_by = auth.uid()
    AND attendance_date = (timezone('Asia/Kolkata', now())::date)
    AND EXISTS (
        SELECT 1 FROM public.students s
        WHERE s.id = student_id 
          AND s.centre_id = public.auth_user_centre_id()
          AND s.status = 'ACTIVE'
    )
);

-- Parents can view attendance only for their linked children
CREATE POLICY "student_attendance_parent_select"
ON public.student_attendance FOR SELECT
TO authenticated
USING (
    public.is_parent_of_student(student_id)
    AND centre_id = public.auth_user_centre_id()
);

-- Parents cannot insert, update or delete attendance (implicit deny-by-default).

-- ============================================================================
-- 10. WORK LOGS POLICIES
-- ============================================================================
-- Owner full access within centre
CREATE POLICY "work_logs_owner_all"
ON public.work_logs FOR ALL
TO authenticated
USING (public.is_centre_owner(centre_id))
WITH CHECK (public.is_centre_owner(centre_id));

-- Tutors can view work logs for their centre
CREATE POLICY "work_logs_tutor_select"
ON public.work_logs FOR SELECT
TO authenticated
USING (
    public.auth_user_role() = 'tutor'
    AND centre_id = public.auth_user_centre_id()
);

-- Tutors can create work logs for active students in their centre
CREATE POLICY "work_logs_tutor_insert"
ON public.work_logs FOR INSERT
TO authenticated
WITH CHECK (
    public.auth_user_role() = 'tutor'
    AND centre_id = public.auth_user_centre_id()
    AND tutor_id = auth.uid()
    AND EXISTS (
        SELECT 1 FROM public.students s
        WHERE s.id = student_id 
          AND s.centre_id = public.auth_user_centre_id()
          AND s.status = 'ACTIVE'
    )
);

-- Tutors can update their own work logs
CREATE POLICY "work_logs_tutor_update_own"
ON public.work_logs FOR UPDATE
TO authenticated
USING (
    public.auth_user_role() = 'tutor'
    AND tutor_id = auth.uid()
    AND centre_id = public.auth_user_centre_id()
)
WITH CHECK (
    public.auth_user_role() = 'tutor'
    AND tutor_id = auth.uid()
    AND centre_id = public.auth_user_centre_id()
);

-- Parents can view work logs only for their linked children
CREATE POLICY "work_logs_parent_select"
ON public.work_logs FOR SELECT
TO authenticated
USING (
    public.is_parent_of_student(student_id)
    AND centre_id = public.auth_user_centre_id()
);

-- ============================================================================
-- 11. APP NOTIFICATIONS POLICIES
-- ============================================================================
CREATE POLICY "app_notifications_user_manage"
ON public.app_notifications FOR ALL
TO authenticated
USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());

CREATE POLICY "app_notifications_owner_view"
ON public.app_notifications FOR SELECT
TO authenticated
USING (public.is_centre_owner(centre_id));

-- ============================================================================
-- 12. DEVICE TOKENS POLICIES
-- ============================================================================
CREATE POLICY "device_tokens_user_manage"
ON public.device_tokens FOR ALL
TO authenticated
USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());

-- ============================================================================
-- 13. AUDIT LOGS POLICIES
-- ============================================================================
-- Only centre owner can view audit logs
CREATE POLICY "audit_logs_owner_select"
ON public.audit_logs FOR SELECT
TO authenticated
USING (public.is_centre_owner(centre_id));

-- Direct insert/update/delete on audit logs is forbidden for users
-- Logs are inserted exclusively by trusted server triggers and RPCs

-- ============================================================================
-- 14. ACCOUNT INVITATIONS POLICIES
-- ============================================================================
CREATE POLICY "account_invitations_owner_all"
ON public.account_invitations FOR ALL
TO authenticated
USING (public.is_centre_owner(centre_id))
WITH CHECK (public.is_centre_owner(centre_id));

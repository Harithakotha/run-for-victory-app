-- ============================================================================
-- Migration: 20261025_004_account_provisioning.sql
-- Project: Run for Victory (By Ravi Kumar)
-- Database: PostgreSQL 15+ (Supabase)
-- Target: Trusted Account-Provisioning, Invitations, & Access Deactivation
-- ============================================================================

-- Add target_student_ids to account_invitations for parent account linking
ALTER TABLE public.account_invitations 
ADD COLUMN IF NOT EXISTS target_student_ids UUID[] DEFAULT '{}'::UUID[];

-- ============================================================================
-- 1. OWNER INVITE TUTOR OR PARENT RPC
-- ============================================================================
CREATE OR REPLACE FUNCTION public.create_account_invitation(
    p_email TEXT,
    p_role TEXT,
    p_student_ids UUID[] DEFAULT '{}'::UUID[]
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_clean_email TEXT := LOWER(TRIM(p_email));
    v_token TEXT;
    v_invitation_id UUID;
    v_invalid_student UUID;
BEGIN
    -- Only active owner can issue invitations
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only centre owners can invite tutors or parents.';
    END IF;

    -- Role validation
    IF p_role NOT IN ('tutor', 'parent') THEN
        RAISE EXCEPTION 'Invalid Role: Can only invite tutors or parents. Owner accounts cannot be self-provisioned.';
    END IF;

    -- Email validation
    IF v_clean_email !~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' THEN
        RAISE EXCEPTION 'Invalid Email format: %', p_email;
    END IF;

    -- If parent, validate that all specified student IDs belong to the owner's centre and are active
    IF p_role = 'parent' THEN
        IF p_student_ids IS NULL OR array_length(p_student_ids, 1) IS NULL OR array_length(p_student_ids, 1) = 0 THEN
            RAISE EXCEPTION 'Validation Error: Parent invitations must link to at least one student.';
        END IF;

        -- Check for any foreign or non-existent students
        SELECT s.id INTO v_invalid_student
        FROM unnest(p_student_ids) AS requested_id
        LEFT JOIN public.students s ON s.id = requested_id AND s.centre_id = v_centre_id AND s.status = 'ACTIVE'
        WHERE s.id IS NULL
        LIMIT 1;

        IF v_invalid_student IS NOT NULL THEN
            RAISE EXCEPTION 'Validation Error: Student % is invalid or does not belong to your centre.', v_invalid_student;
        END IF;
    END IF;

    -- Check if an active invitation already exists for this email
    IF EXISTS (
        SELECT 1 FROM public.account_invitations
        WHERE centre_id = v_centre_id 
          AND LOWER(TRIM(email)) = v_clean_email 
          AND status = 'PENDING' 
          AND expires_at > clock_timestamp()
    ) THEN
        RAISE EXCEPTION 'Duplicate Invitation: A pending invitation already exists for %.', v_clean_email;
    END IF;

    -- Generate secure cryptographic token
    v_token := encode(gen_random_bytes(32), 'hex');

    -- Insert invitation record
    INSERT INTO public.account_invitations (
        centre_id,
        invited_by,
        email,
        role,
        token,
        target_student_ids,
        status,
        expires_at
    ) VALUES (
        v_centre_id,
        auth.uid(),
        v_clean_email,
        p_role,
        v_token,
        COALESCE(p_student_ids, '{}'::UUID[]),
        'PENDING',
        clock_timestamp() + INTERVAL '7 days'
    ) RETURNING id INTO v_invitation_id;

    -- Audit trail
    INSERT INTO public.audit_logs (
        centre_id,
        actor_id,
        action,
        entity_type,
        entity_id,
        new_values,
        reason
    ) VALUES (
        v_centre_id,
        auth.uid(),
        'CREATE_INVITATION',
        'account_invitations',
        v_invitation_id,
        jsonb_build_object(
            'email', v_clean_email,
            'role', p_role,
            'target_student_ids', p_student_ids
        ),
        format('Owner invited %s (%s)', v_clean_email, p_role)
    );

    RETURN jsonb_build_object(
        'success', true,
        'invitation_id', v_invitation_id,
        'email', v_clean_email,
        'role', p_role,
        'token', v_token,
        'expires_at', (clock_timestamp() + INTERVAL '7 days')
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.create_account_invitation(TEXT, TEXT, UUID[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_account_invitation(TEXT, TEXT, UUID[]) TO authenticated;

-- ============================================================================
-- 2. VALIDATE INVITATION TOKEN RPC (Publicly inspectable before signup)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.validate_invitation_token(
    p_token TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_inv public.account_invitations%ROWTYPE;
    v_centre public.tuition_centres%ROWTYPE;
BEGIN
    SELECT * INTO v_inv FROM public.account_invitations WHERE token = p_token;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('valid', false, 'reason', 'Invitation token not found.');
    END IF;

    IF v_inv.status != 'PENDING' THEN
        RETURN jsonb_build_object('valid', false, 'reason', format('Invitation has already been %s.', LOWER(v_inv.status)));
    END IF;

    IF v_inv.expires_at <= clock_timestamp() THEN
        UPDATE public.account_invitations SET status = 'EXPIRED' WHERE id = v_inv.id;
        RETURN jsonb_build_object('valid', false, 'reason', 'Invitation has expired.');
    END IF;

    SELECT * INTO v_centre FROM public.tuition_centres WHERE id = v_inv.centre_id;

    RETURN jsonb_build_object(
        'valid', true,
        'email', v_inv.email,
        'role', v_inv.role,
        'centre_name', v_centre.name,
        'centre_subtitle', v_centre.subtitle,
        'expires_at', v_inv.expires_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.validate_invitation_token(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.validate_invitation_token(TEXT) TO authenticated, anon;

-- ============================================================================
-- 3. ACCEPT INVITATION & COMPLETE ACCOUNT PROVISIONING RPC
-- ============================================================================
CREATE OR REPLACE FUNCTION public.accept_account_invitation(
    p_token TEXT,
    p_full_name TEXT,
    p_phone TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_inv public.account_invitations%ROWTYPE;
    v_user_id UUID := auth.uid();
    v_student_id UUID;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication Required: User must be signed in with Supabase Auth to claim invitation.';
    END IF;

    SELECT * INTO v_inv FROM public.account_invitations WHERE token = p_token FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Invalid Invitation: Token not found.';
    END IF;

    IF v_inv.status != 'PENDING' THEN
        RAISE EXCEPTION 'Invitation Misuse: Invitation is % and cannot be reused.', v_inv.status;
    END IF;

    IF v_inv.expires_at <= clock_timestamp() THEN
        UPDATE public.account_invitations SET status = 'EXPIRED' WHERE id = v_inv.id;
        RAISE EXCEPTION 'Invitation Expired: This invitation has expired. Contact the centre owner for a new invite.';
    END IF;

    -- Create or update profile bound to the invited centre and role
    INSERT INTO public.profiles (
        id,
        centre_id,
        role,
        full_name,
        phone,
        is_active,
        created_at,
        updated_at
    ) VALUES (
        v_user_id,
        v_inv.centre_id,
        v_inv.role,
        p_full_name,
        p_phone,
        true,
        clock_timestamp(),
        clock_timestamp()
    )
    ON CONFLICT (id) DO UPDATE SET
        centre_id = v_inv.centre_id,
        role = v_inv.role,
        full_name = EXCLUDED.full_name,
        phone = COALESCE(EXCLUDED.phone, public.profiles.phone),
        is_active = true,
        updated_at = clock_timestamp();

    -- If parent, link to explicitly specified students
    IF v_inv.role = 'parent' AND v_inv.target_student_ids IS NOT NULL THEN
        FOREACH v_student_id IN ARRAY v_inv.target_student_ids
        LOOP
            INSERT INTO public.parent_children (
                centre_id,
                parent_id,
                student_id,
                relationship_type,
                is_active
            ) VALUES (
                v_inv.centre_id,
                v_user_id,
                v_student_id,
                'Parent',
                true
            )
            ON CONFLICT (parent_id, student_id) DO UPDATE 
            SET is_active = true;
        END LOOP;
    END IF;

    -- Mark invitation accepted
    UPDATE public.account_invitations
    SET status = 'ACCEPTED',
        accepted_at = clock_timestamp()
    WHERE id = v_inv.id;

    -- Audit log
    INSERT INTO public.audit_logs (
        centre_id,
        actor_id,
        action,
        entity_type,
        entity_id,
        new_values,
        reason
    ) VALUES (
        v_inv.centre_id,
        v_user_id,
        'ACCEPT_INVITATION',
        'profiles',
        v_user_id,
        jsonb_build_object('role', v_inv.role, 'email', v_inv.email),
        'User completed invitation provisioning'
    );

    RETURN jsonb_build_object(
        'success', true,
        'user_id', v_user_id,
        'centre_id', v_inv.centre_id,
        'role', v_inv.role
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.accept_account_invitation(TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.accept_account_invitation(TEXT, TEXT, TEXT) TO authenticated;

-- ============================================================================
-- 4. DEACTIVATE USER (REVOKE ACCESS IMMEDIATELY)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.deactivate_user(
    p_user_id UUID,
    p_reason TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
    v_target public.profiles%ROWTYPE;
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only centre owners can deactivate user accounts.';
    END IF;

    IF p_user_id = auth.uid() THEN
        RAISE EXCEPTION 'Invalid Operation: Owner cannot deactivate their own account.';
    END IF;

    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Deactivation reason is strictly required for audit.';
    END IF;

    SELECT * INTO v_target 
    FROM public.profiles 
    WHERE id = p_user_id AND centre_id = v_centre_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Target user not found in your tuition centre.';
    END IF;

    -- Set is_active to false
    UPDATE public.profiles
    SET is_active = false,
        updated_at = clock_timestamp()
    WHERE id = p_user_id;

    -- Note: When is_active is false, public.auth_user_role() immediately returns 'none',
    -- cutting off all RLS policies across students, attendance, work logs, etc.

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
        'DEACTIVATE_USER',
        'profiles',
        p_user_id,
        jsonb_build_object('is_active', true),
        jsonb_build_object('is_active', false, 'reason', p_reason),
        p_reason
    );

    RETURN jsonb_build_object('success', true, 'user_id', p_user_id, 'is_active', false);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.deactivate_user(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.deactivate_user(UUID, TEXT) TO authenticated;

-- ============================================================================
-- 5. REACTIVATE USER
-- ============================================================================
CREATE OR REPLACE FUNCTION public.reactivate_user(
    p_user_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_centre_id UUID := public.auth_user_centre_id();
BEGIN
    IF NOT public.is_centre_owner(v_centre_id) THEN
        RAISE EXCEPTION 'Access Denied: Only centre owners can reactivate user accounts.';
    END IF;

    UPDATE public.profiles
    SET is_active = true,
        updated_at = clock_timestamp()
    WHERE id = p_user_id AND centre_id = v_centre_id;

    INSERT INTO public.audit_logs (
        centre_id,
        actor_id,
        action,
        entity_type,
        entity_id,
        new_values,
        reason
    ) VALUES (
        v_centre_id,
        auth.uid(),
        'REACTIVATE_USER',
        'profiles',
        p_user_id,
        jsonb_build_object('is_active', true),
        'Owner reactivated account'
    );

    RETURN jsonb_build_object('success', true, 'user_id', p_user_id, 'is_active', true);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.reactivate_user(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.reactivate_user(UUID) TO authenticated;

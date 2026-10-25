-- ============================================================================
-- Test Suite: security_tests.sql
-- Project: Run for Victory (By Ravi Kumar)
-- Database: PostgreSQL 15+ (Supabase)
-- Purpose: Verify 8 Mandatory Security Boundaries & RLS Enforcement
-- ============================================================================

BEGIN;

-- Run Seed Data Setup
\i supabase/seeds/seed.sql

-- Helper to set authenticated user session
CREATE OR REPLACE FUNCTION public.set_auth_context(p_user_id UUID, p_role TEXT DEFAULT 'authenticated')
RETURNS VOID AS $$
BEGIN
    PERFORM set_config('request.jwt.claim.sub', p_user_id::text, true);
    PERFORM set_config('role', p_role, true);
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- TEST 1: Owner can view authorised centre records
-- ============================================================================
SELECT public.set_auth_context('11111111-1111-1111-1111-111111111111', 'authenticated');

DO $$
DECLARE
    v_centre_cnt INT;
    v_student_cnt INT;
    v_att_cnt INT;
BEGIN
    SELECT count(*) INTO v_centre_cnt FROM public.tuition_centres;
    SELECT count(*) INTO v_student_cnt FROM public.students;
    SELECT count(*) INTO v_att_cnt FROM public.student_attendance;

    ASSERT v_centre_cnt = 1, 'TEST 1 FAILED: Owner should view exactly their own centre.';
    ASSERT v_student_cnt >= 3, 'TEST 1 FAILED: Owner should view all students in their centre.';
    ASSERT v_att_cnt >= 1, 'TEST 1 FAILED: Owner should view attendance in their centre.';
    RAISE NOTICE '✅ TEST 1 PASSED: Owner can view authorised centre records.';
END $$;

-- ============================================================================
-- TEST 2: Tutor cannot perform owner-only actions
-- ============================================================================
-- Simulate session as Tutor (Anand Rao)
SELECT public.set_auth_context('22222222-2222-2222-2222-222222222222', 'authenticated');

-- 2a. Tutor tries to update tuition centre radius/settings -> Must fail or update 0 rows
DO $$
DECLARE
    v_updated INT;
BEGIN
    UPDATE public.tuition_centres 
    SET radius_meters = 500 
    WHERE id = '10000000-0000-0000-0000-000000000001';
    GET DIAGNOSTICS v_updated = ROW_COUNT;

    ASSERT v_updated = 0, 'SECURITY VIOLATION: Tutor modified tuition centre configuration!';
    RAISE NOTICE '✅ TEST 2a PASSED: Tutor cannot modify centre settings.';
END $$;

-- 2b. Tutor tries to invoke owner archival RPC -> Must throw exception
DO $$
BEGIN
    BEGIN
        PERFORM public.archive_student_record('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Unauthorized attempt');
        RAISE EXCEPTION 'SECURITY VIOLATION: Tutor was able to execute archive_student_record!';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%Access Denied%' THEN
            RAISE NOTICE '✅ TEST 2b PASSED: archive_student_record rejected for tutor.';
        ELSE
            RAISE;
        END IF;
    END;
END $$;

-- ============================================================================
-- TEST 3: Parent A cannot read Parent B's child
-- ============================================================================
-- Simulate session as Parent A (Suresh Reddy, father of Rahul Reddy)
SELECT public.set_auth_context('33333333-3333-3333-3333-333333333333', 'authenticated');

DO $$
DECLARE
    v_child_a_cnt INT;
    v_child_b_cnt INT;
BEGIN
    -- Parent A can see Child A
    SELECT count(*) INTO v_child_a_cnt FROM public.students 
    WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    
    -- Parent A tries to see Child B (Pooja Devi)
    SELECT count(*) INTO v_child_b_cnt FROM public.students 
    WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

    ASSERT v_child_a_cnt = 1, 'TEST 3 FAILED: Parent A could not view their own child.';
    ASSERT v_child_b_cnt = 0, 'SECURITY VIOLATION: Parent A accessed Parent B child record!';
    RAISE NOTICE '✅ TEST 3 PASSED: Parent A cannot read Parent B child.';
END $$;

-- ============================================================================
-- TEST 4: Parent cannot insert or modify attendance
-- ============================================================================
SELECT public.set_auth_context('33333333-3333-3333-3333-333333333333', 'authenticated');

DO $$
DECLARE
    v_inserted INT := 0;
BEGIN
    BEGIN
        INSERT INTO public.student_attendance (
            centre_id, student_id, attendance_date, status, recorded_by
        ) VALUES (
            '10000000-0000-0000-0000-000000000001',
            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            (timezone('Asia/Kolkata', now())::date),
            'PRESENT',
            '33333333-3333-3333-3333-333333333333'
        );
        GET DIAGNOSTICS v_inserted = ROW_COUNT;
    EXCEPTION WHEN OTHERS THEN
        v_inserted := 0;
    END;

    ASSERT v_inserted = 0, 'SECURITY VIOLATION: Parent inserted attendance record!';
    RAISE NOTICE '✅ TEST 4 PASSED: Parent cannot insert or modify attendance.';
END $$;

-- ============================================================================
-- TEST 5: Archived students cannot receive new attendance records
-- ============================================================================
-- Simulate session as Tutor
SELECT public.set_auth_context('22222222-2222-2222-2222-222222222222', 'authenticated');

DO $$
BEGIN
    BEGIN
        -- Student C (Vikram Singh) is archived in seed data
        PERFORM public.record_student_check_in(
            'cccccccc-cccc-cccc-cccc-cccccccccccc',
            'PRESENT',
            'Attempt to check in archived student'
        );
        RAISE EXCEPTION 'SECURITY VIOLATION: Archived student received attendance check-in!';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%Archived Student%' THEN
            RAISE NOTICE '✅ TEST 5 PASSED: Archived student cannot receive new attendance.';
        ELSE
            RAISE;
        END IF;
    END;
END $$;

-- ============================================================================
-- TEST 6: Users cannot cross tuition-centre boundaries (Tenant A vs Tenant B)
-- ============================================================================
-- Simulate session as Centre B Owner
SELECT public.set_auth_context('55555555-5555-5555-5555-555555555555', 'authenticated');

DO $$
DECLARE
    v_centre_a_students INT;
    v_centre_b_students INT;
BEGIN
    -- Centre B owner tries to query Centre A students
    SELECT count(*) INTO v_centre_a_students 
    FROM public.students 
    WHERE centre_id = '10000000-0000-0000-0000-000000000001';

    -- Centre B owner queries Centre B students
    SELECT count(*) INTO v_centre_b_students 
    FROM public.students 
    WHERE centre_id = '20000000-0000-0000-0000-000000000002';

    ASSERT v_centre_a_students = 0, 'SECURITY VIOLATION: Tenant B owner read Tenant A student data!';
    ASSERT v_centre_b_students = 1, 'TEST 6 FAILED: Tenant B owner could not read own student data.';
    RAISE NOTICE '✅ TEST 6 PASSED: Users cannot cross tuition-centre boundaries.';
END $$;

-- ============================================================================
-- TEST 7: Anonymous users cannot read private data
-- ============================================================================
-- Simulate unauthenticated / anon role
PERFORM set_config('request.jwt.claim.sub', '', true);
PERFORM set_config('role', 'anon', true);

DO $$
DECLARE
    v_student_cnt INT;
    v_profile_cnt INT;
    v_attendance_cnt INT;
BEGIN
    SELECT count(*) INTO v_student_cnt FROM public.students;
    SELECT count(*) INTO v_profile_cnt FROM public.profiles;
    SELECT count(*) INTO v_attendance_cnt FROM public.student_attendance;

    ASSERT v_student_cnt = 0, 'SECURITY VIOLATION: Anon user read students table!';
    ASSERT v_profile_cnt = 0, 'SECURITY VIOLATION: Anon user read profiles table!';
    ASSERT v_attendance_cnt = 0, 'SECURITY VIOLATION: Anon user read attendance table!';
    RAISE NOTICE '✅ TEST 7 PASSED: Anonymous users cannot read private data.';
END $$;

-- ============================================================================
-- TEST 8: Attendance retry cannot create duplicate records
-- ============================================================================
-- Simulate session as Tutor
SELECT public.set_auth_context('22222222-2222-2222-2222-222222222222', 'authenticated');

DO $$
DECLARE
    v_today DATE := (timezone('Asia/Kolkata', now())::date);
    v_first_checkin JSONB;
BEGIN
    -- Student B (Pooja Devi) has not checked in yet today
    v_first_checkin := public.record_student_check_in(
        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        'PRESENT',
        'First Check-In'
    );
    ASSERT (v_first_checkin->>'success')::boolean = true, 'TEST 8 FAILED: Initial check-in should succeed.';

    -- Immediate retry (e.g. flaky network retry) -> Must be rejected
    BEGIN
        PERFORM public.record_student_check_in(
            'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
            'PRESENT',
            'Duplicate Check-In Retry'
        );
        RAISE EXCEPTION 'SECURITY VIOLATION: Duplicate attendance record was accepted on retry!';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%Duplicate Attendance%' THEN
            RAISE NOTICE '✅ TEST 8 PASSED: Attendance retry cannot create duplicate records.';
        ELSE
            RAISE;
        END IF;
    END;
END $$;

ROLLBACK;

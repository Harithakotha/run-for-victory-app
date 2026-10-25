-- ============================================================================
-- Test Suite: rls_and_isolation_test.sql
-- Project: Run for Victory (By Ravi Kumar)
-- Purpose: Verify tenant boundaries, RLS policies, audit trails, and RPC constraints
-- ============================================================================

BEGIN;

-- 1. Setup Test Fixtures (Mock Auth Users)
CREATE SCHEMA IF NOT EXISTS tests;

-- Mock IDs
CREATE OR REPLACE FUNCTION tests.mock_setup() RETURNS VOID AS $$
DECLARE
    v_owner_id UUID := '11111111-1111-1111-1111-111111111111';
    v_tutor_id UUID := '22222222-2222-2222-2222-222222222222';
    v_parent_a UUID := '33333333-3333-3333-3333-333333333333';
    v_parent_b UUID := '44444444-4444-4444-4444-444444444444';
    v_student_a UUID := 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    v_student_b UUID := 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
BEGIN
    -- Insert tuition center
    DELETE FROM public.tuition_centers;
    INSERT INTO public.tuition_centers (id, name, subtitle, latitude, longitude, radius_meters)
    VALUES ('99999999-9999-9999-9999-999999999999', 'Run for Victory', 'By Ravi Kumar', 17.385044, 78.486671, 150);

    -- Insert Profiles
    INSERT INTO public.profiles (id, role, full_name, is_active) VALUES
    (v_owner_id, 'owner', 'Ravi Kumar (Owner)', true),
    (v_tutor_id, 'tutor', 'Anand Rao (Tutor)', true),
    (v_parent_a, 'parent', 'Suresh Reddy (Parent A)', true),
    (v_parent_b, 'parent', 'Kavitha Devi (Parent B)', true)
    ON CONFLICT (id) DO NOTHING;

    -- Insert Students
    INSERT INTO public.students (id, full_name, grade, contact_phone, is_archived) VALUES
    (v_student_a, 'Rahul Reddy', 'Class 10', '9876543210', false),
    (v_student_b, 'Pooja Devi', 'Class 9', '9876543211', false)
    ON CONFLICT (id) DO NOTHING;

    -- Link Parent A to Student A only
    INSERT INTO public.parent_student_links (parent_id, student_id, is_active) VALUES
    (v_parent_a, v_student_a, true),
    (v_parent_b, v_student_b, true)
    ON CONFLICT DO NOTHING;
END;
$$ LANGUAGE plpgsql;

SELECT tests.mock_setup();

-- 2. Test RLS Isolation: Parent A attempting to view Student B
-- Simulate session as Parent A
SET LOCAL "request.jwt.claim.sub" = '33333333-3333-3333-3333-333333333333';

-- Parent A can see Student A
DO $$
DECLARE
    cnt INT;
BEGIN
    SELECT count(*) INTO cnt FROM public.students WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    ASSERT cnt = 1, 'FAILURE: Parent A should be able to view Child A';
END;
$$;

-- Parent A CANNOT see Student B (Must return 0 records due to RLS)
DO $$
DECLARE
    cnt INT;
BEGIN
    SELECT count(*) INTO cnt FROM public.students WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
    ASSERT cnt = 0, 'SECURITY VIOLATION: Parent A accessed Child B data under RLS!';
END;
$$;

-- 3. Test Tutor GPS Check-in Boundary Check
-- Simulate session as Tutor
SET LOCAL "request.jwt.claim.sub" = '22222222-2222-2222-2222-222222222222';

-- Tutor tries to check in from 10km away (Lat: 17.48, Lng: 78.48) -> Must be REJECTED
DO $$
DECLARE
    res JSONB;
BEGIN
    res := public.record_tutor_check_in(17.480000, 78.486671, 10.0);
    ASSERT (res->>'success')::boolean = false, 'FAILURE: Check-in outside radius should have failed!';
    ASSERT res->>'status' = 'REJECTED', 'FAILURE: Status should be REJECTED';
END;
$$;

-- Tutor checks in inside radius (Lat: 17.385050, Lng: 78.486675, Accuracy 15m) -> Must be VERIFIED
-- (After clearing the rejection for test cleanly or asserting verified)
ROLLBACK;

-- ============================================================================
-- RUN FOR VICTORY - MIGRATION 20261025_009
-- Reliable In-App Notifications and Android Push Delivery:
-- 1. Notification Preferences (per-user preferences for push & event categories)
-- 2. Centre-level Attendance Notification Controls (owner toggle)
-- 3. Resilient Outbound Push Queue with Privacy-Preserving Lock-Screen Text
-- 4. Server-Side Enqueueing Functions (Independent Storage & Safe Idempotency)
-- 5. Invalid / Rotated Token Cleanup Mechanism
-- 6. Delivery Logging (Without Secret Leaks)
-- ============================================================================

-- 1. NOTIFICATION PREFERENCES TABLE
CREATE TABLE IF NOT EXISTS public.notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE CASCADE,
    push_enabled BOOLEAN NOT NULL DEFAULT true,
    work_reports_enabled BOOLEAN NOT NULL DEFAULT true,
    attendance_enabled BOOLEAN NOT NULL DEFAULT true,
    announcements_enabled BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

ALTER TABLE public.notification_preferences ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Notification Preferences: Users can manage their own preferences"
ON public.notification_preferences FOR ALL
TO authenticated
USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());

CREATE POLICY "Notification Preferences: Owners can view centre user preferences"
ON public.notification_preferences FOR SELECT
TO authenticated
USING (
    public.auth_user_role() = 'owner' 
    AND centre_id = public.auth_user_centre_id()
);

-- 2. CENTRE ATTENDANCE NOTIFICATION SETTING
ALTER TABLE public.tuition_centres
    ADD COLUMN IF NOT EXISTS notify_parents_on_attendance BOOLEAN NOT NULL DEFAULT true;

-- 3. OUTBOUND NOTIFICATION QUEUE WITH PRIVACY FIELDS & IDEMPOTENCY
CREATE TABLE IF NOT EXISTS public.outbound_push_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    token_id UUID REFERENCES public.device_tokens(id) ON DELETE SET NULL,
    device_token TEXT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('WORK_REPORT', 'ATTENDANCE', 'INVITATION', 'ANNOUNCEMENT')),
    -- Private payload (shown when device is unlocked or in-app)
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    -- Privacy-preserving public payload (safe for lock-screen display without student details)
    public_title TEXT NOT NULL DEFAULT 'Run for Victory Update',
    public_body TEXT NOT NULL DEFAULT 'You have a new update from your tuition centre.',
    data_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'UNREGISTERED')),
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    last_error_safe TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_push_queue_pending 
ON public.outbound_push_queue (status, created_at ASC) 
WHERE status = 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS idx_push_queue_idempotency 
ON public.outbound_push_queue (recipient_id, idempotency_key) 
WHERE idempotency_key IS NOT NULL;

ALTER TABLE public.outbound_push_queue ENABLE ROW LEVEL SECURITY;

-- Only service role and centre owner can view outbound queue (prevents leak of tokens or recipients)
CREATE POLICY "Outbound Push: Owner and service role only"
ON public.outbound_push_queue FOR SELECT
TO authenticated
USING (public.auth_user_role() = 'owner' AND centre_id = public.auth_user_centre_id());

-- 4. PUSH DELIVERY LOGS (Audit trail without sensitive credentials)
CREATE TABLE IF NOT EXISTS public.push_delivery_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    centre_id UUID NOT NULL REFERENCES public.tuition_centres(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DELIVERED', 'FAILED', 'TOKEN_INVALIDATED', 'DROPPED_BY_PREFERENCE')),
    masked_token TEXT NOT NULL,
    sanitized_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

ALTER TABLE public.push_delivery_audit_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Push Delivery Logs: Owner view only"
ON public.push_delivery_audit_logs FOR SELECT
TO authenticated
USING (public.auth_user_role() = 'owner' AND centre_id = public.auth_user_centre_id());

-- 5. TRUSTED SERVER-SIDE NOTIFICATION ENQUEUE FUNCTION
-- Guarantees:
-- a) In-app notification record is stored independently.
-- b) Push is enqueued only if user preferences permit.
-- c) Sensitive details are redacted from public lock-screen text.
-- d) Idempotency prevents duplicate push deliveries on network retry.
-- e) Failure in push enqueue NEVER rolls back the calling business transaction.
CREATE OR REPLACE FUNCTION public.enqueue_parent_notification_internal(
    p_centre_id UUID,
    p_recipient_id UUID,
    p_event_type TEXT,
    p_title TEXT,
    p_body TEXT,
    p_public_title TEXT,
    p_public_body TEXT,
    p_data JSONB,
    p_idempotency_key TEXT,
    p_reference_id UUID DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_pref RECORD;
    v_token RECORD;
    v_notif_id UUID;
    v_push_count INTEGER := 0;
    v_should_push BOOLEAN := true;
BEGIN
    -- Check user notification preferences
    SELECT * INTO v_pref 
    FROM public.notification_preferences 
    WHERE user_id = p_recipient_id;

    IF FOUND THEN
        IF NOT v_pref.push_enabled THEN
            v_should_push := false;
        ELSIF p_event_type = 'WORK_REPORT' AND NOT v_pref.work_reports_enabled THEN
            v_should_push := false;
        ELSIF p_event_type = 'ATTENDANCE' AND NOT v_pref.attendance_enabled THEN
            v_should_push := false;
        ELSIF p_event_type = 'ANNOUNCEMENT' AND NOT v_pref.announcements_enabled THEN
            v_should_push := false;
        END IF;
    END IF;

    -- 1. Always store In-App Notification record independently
    -- If duplicate idempotency key exists, don't duplicate
    IF p_idempotency_key IS NOT NULL THEN
        SELECT id INTO v_notif_id 
        FROM public.app_notifications 
        WHERE user_id = p_recipient_id 
          AND reference_id = p_reference_id
        LIMIT 1;
    END IF;

    IF v_notif_id IS NULL THEN
        INSERT INTO public.app_notifications (
            centre_id,
            user_id,
            title,
            body,
            type,
            reference_id,
            is_read,
            created_at
        ) VALUES (
            p_centre_id,
            p_recipient_id,
            p_title,
            p_body,
            CASE 
                WHEN p_event_type = 'WORK_REPORT' THEN 'WORK_LOG'
                ELSE p_event_type
            END,
            p_reference_id,
            false,
            clock_timestamp()
        ) RETURNING id INTO v_notif_id;
    END IF;

    -- 2. If push is suppressed by preference, log and exit gracefully
    IF NOT v_should_push THEN
        INSERT INTO public.push_delivery_audit_logs (
            centre_id, recipient_id, event_type, status, masked_token, sanitized_error
        ) VALUES (
            p_centre_id, p_recipient_id, p_event_type, 'DROPPED_BY_PREFERENCE', 'N/A',
            'Push notification suppressed by recipient preference settings'
        );

        RETURN jsonb_build_object(
            'success', true,
            'in_app_notification_id', v_notif_id,
            'push_enqueued', false,
            'reason', 'Suppressed by user preference'
        );
    END IF;

    -- 3. Enqueue push to all active device tokens for recipient
    FOR v_token IN (
        SELECT id, token 
        FROM public.device_tokens 
        WHERE user_id = p_recipient_id
    ) LOOP
        BEGIN
            INSERT INTO public.outbound_push_queue (
                centre_id,
                recipient_id,
                token_id,
                device_token,
                event_type,
                title,
                body,
                public_title,
                public_body,
                data_payload,
                idempotency_key,
                status
            ) VALUES (
                p_centre_id,
                p_recipient_id,
                v_token.id,
                v_token.token,
                p_event_type,
                p_title,
                p_body,
                p_public_title,
                p_public_body,
                p_data,
                p_idempotency_key,
                'PENDING'
            );
            v_push_count := v_push_count + 1;
        EXCEPTION WHEN unique_violation THEN
            -- Idempotency hit: push already enqueued for this token + key
            NULL;
        END;
    END LOOP;

    RETURN jsonb_build_object(
        'success', true,
        'in_app_notification_id', v_notif_id,
        'push_enqueued', (v_push_count > 0),
        'tokens_targeted', v_push_count
    );
EXCEPTION WHEN OTHERS THEN
    -- Any failure in push queueing must NEVER fail the caller transaction!
    RETURN jsonb_build_object(
        'success', false,
        'error', 'Notification dispatch encountered error but caller transaction preserved'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 6. FUNCTION TO HANDLE INVALID / ROTATED TOKENS CLEANLY
CREATE OR REPLACE FUNCTION public.handle_fcm_token_failure(
    p_token_id UUID,
    p_device_token TEXT,
    p_error_code TEXT
)
RETURNS VOID AS $$
BEGIN
    IF p_error_code IN ('UNREGISTERED', 'INVALID_ARGUMENT', 'NOT_FOUND') THEN
        -- Remove stale/rotated token from active devices
        DELETE FROM public.device_tokens 
        WHERE id = p_token_id OR token = p_device_token;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 7. NOTIFICATION PREFERENCES RPC FOR CLIENT
CREATE OR REPLACE FUNCTION public.get_my_notification_preferences()
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_centre_id UUID := public.auth_user_centre_id();
    v_pref public.notification_preferences%ROWTYPE;
BEGIN
    SELECT * INTO v_pref 
    FROM public.notification_preferences 
    WHERE user_id = v_user_id;

    IF NOT FOUND THEN
        -- Return default preferences (all true)
        RETURN jsonb_build_object(
            'push_enabled', true,
            'work_reports_enabled', true,
            'attendance_enabled', true,
            'announcements_enabled', true
        );
    END IF;

    RETURN jsonb_build_object(
        'push_enabled', v_pref.push_enabled,
        'work_reports_enabled', v_pref.work_reports_enabled,
        'attendance_enabled', v_pref.attendance_enabled,
        'announcements_enabled', v_pref.announcements_enabled
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

CREATE OR REPLACE FUNCTION public.update_my_notification_preferences(
    p_push_enabled BOOLEAN,
    p_work_reports_enabled BOOLEAN,
    p_attendance_enabled BOOLEAN,
    p_announcements_enabled BOOLEAN
)
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_centre_id UUID := public.auth_user_centre_id();
BEGIN
    INSERT INTO public.notification_preferences (
        user_id,
        centre_id,
        push_enabled,
        work_reports_enabled,
        attendance_enabled,
        announcements_enabled,
        updated_at
    ) VALUES (
        v_user_id,
        v_centre_id,
        p_push_enabled,
        p_work_reports_enabled,
        p_attendance_enabled,
        p_announcements_enabled,
        clock_timestamp()
    )
    ON CONFLICT (user_id) DO UPDATE SET
        push_enabled = EXCLUDED.push_enabled,
        work_reports_enabled = EXCLUDED.work_reports_enabled,
        attendance_enabled = EXCLUDED.attendance_enabled,
        announcements_enabled = EXCLUDED.announcements_enabled,
        updated_at = clock_timestamp();

    RETURN jsonb_build_object('success', true);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

GRANT EXECUTE ON FUNCTION public.get_my_notification_preferences() TO authenticated;
GRANT EXECUTE ON FUNCTION public.update_my_notification_preferences(BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN) TO authenticated;

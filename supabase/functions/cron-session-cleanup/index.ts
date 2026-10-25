// ==============================================================================
// Supabase Edge Function: cron-session-cleanup
// Project: Run for Victory (By Ravi Kumar)
// Runtime: Deno / Supabase Edge Runtime
// Purpose: Scheduled Maintenance, Token Eviction, & Invitation Expiry
// ==============================================================================

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

serve(async (req: Request) => {
    try {
        const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
        const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
        const supabase = createClient(supabaseUrl, supabaseServiceKey);

        const now = new Date().toISOString();

        // 1. Mark Expired Invitations (TTL = 7 days)
        const { count: expiredInvites, error: inviteErr } = await supabase
            .from("account_invitations")
            .update({ status: "EXPIRED" })
            .eq("status", "PENDING")
            .lt("expires_at", now);

        // 2. Remove Stale Push Tokens (inactive > 90 days)
        const ninetyDaysAgo = new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString();
        const { count: purgedTokens, error: tokenErr } = await supabase
            .from("device_tokens")
            .delete()
            .lt("updated_at", ninetyDaysAgo);

        // 3. Purge Delivery Logs older than 30 days
        const thirtyDaysAgo = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString();
        const { count: purgedLogs, error: logErr } = await supabase
            .from("notification_delivery_logs")
            .delete()
            .lt("created_at", thirtyDaysAgo);

        const summary = {
            timestamp: now,
            expiredInvites: expiredInvites ?? 0,
            purgedTokens: purgedTokens ?? 0,
            purgedLogs: purgedLogs ?? 0,
            errors: [inviteErr, tokenErr, logErr].filter(Boolean)
        };

        return new Response(JSON.stringify(summary), {
            status: 200,
            headers: { "Content-Type": "application/json" }
        });
    } catch (err: any) {
        return new Response(JSON.stringify({ error: err.message }), {
            status: 500,
            headers: { "Content-Type": "application/json" }
        });
    }
});

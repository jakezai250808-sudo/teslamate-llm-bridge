-- teslamate-readonly-role.sql
-- Create a least-privilege, read-only PostgreSQL role for teslamate-llm-bridge.
--
-- Why: the bridge only ever runs read-only SELECT queries against your TeslaMate
-- database — every play's SQL is statically validated to reject writes (no
-- INSERT/UPDATE/DELETE/DDL, no multiple statements). Connecting it with a
-- dedicated read-only role is defense in depth: even a bug, a misconfiguration,
-- or a malicious play cannot modify data or read your Tesla credentials.
--
-- TeslaMate stores your encrypted Tesla access/refresh tokens in `private.tokens`
-- (schema `private`). This role is granted USAGE on the `public` schema ONLY —
-- never on `private` — so it cannot even reach the tokens table, on top of the
-- explicit per-table allowlist below.
--
-- Run this once, as a TeslaMate DB superuser/owner, e.g.:
--   psql -U teslamate -d teslamate -f docs/teslamate-readonly-role.sql
-- Then point TM_DB_USER / TM_DB_PASS in your .env at this new role.

-- 1. Create the login role. CHANGE THE PASSWORD before running.
CREATE ROLE teslamate_bridge WITH LOGIN PASSWORD 'change-me-please';

-- 2. Allow it to connect, and to use the PUBLIC schema only (NOT `private`).
GRANT CONNECT ON DATABASE teslamate TO teslamate_bridge;
GRANT USAGE  ON SCHEMA   public     TO teslamate_bridge;
--   (No USAGE on schema `private` → `private.tokens` is unreachable.)

-- 3. Grant SELECT ONLY on the public data tables the current play library reads.
GRANT SELECT ON
    public.cars,
    public.drives,
    public.charging_processes,
    public.positions
TO teslamate_bridge;

-- If you add plays that query other TeslaMate data tables (e.g. charges,
-- addresses, geofences), grant SELECT on those too, for example:
--   GRANT SELECT ON public.charges, public.addresses, public.geofences TO teslamate_bridge;

-- ---------------------------------------------------------------------------
-- Alternative (convenience over strictness): grant read on EVERY table in the
-- public schema. New plays then work without re-granting. This is still safe
-- with respect to your credentials, because the tokens table lives in the
-- separate `private` schema, which this role has no USAGE on:
--
--   GRANT SELECT ON ALL TABLES IN SCHEMA public TO teslamate_bridge;
--
-- Prefer the explicit allowlist above if you want the tightest possible grant.
-- ---------------------------------------------------------------------------

-- Verify what the role can read — only the public data tables should appear,
-- and nothing from the `private` schema:
--   SELECT table_schema, table_name
--   FROM information_schema.role_table_grants
--   WHERE grantee = 'teslamate_bridge'
--   ORDER BY table_schema, table_name;

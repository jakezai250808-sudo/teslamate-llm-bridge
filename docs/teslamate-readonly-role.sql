-- teslamate-readonly-role.sql
-- Create a least-privilege, read-only PostgreSQL role for teslamate-llm-bridge.
--
-- Why: the bridge only ever runs read-only SELECT queries against your TeslaMate
-- database — every play's SQL is statically validated to reject writes (no
-- INSERT/UPDATE/DELETE/DDL, no multiple statements). Connecting it with a
-- dedicated read-only role is defense in depth: even a bug, a misconfiguration,
-- or a malicious play cannot modify data or read your Tesla credentials.
--
-- The key property: this role is granted SELECT on a small, explicit allowlist
-- of data tables only — it is NEVER granted the `tokens` table that stores your
-- encrypted Tesla access/refresh credentials, regardless of which schema your
-- TeslaMate version keeps it in (older versions: `public.tokens`; current
-- versions: `private.tokens`).
--
-- Run this once, as a TeslaMate DB superuser/owner, e.g.:
--   psql -U teslamate -d teslamate -f docs/teslamate-readonly-role.sql
-- Then point TM_DB_USER / TM_DB_PASS in your .env at this new role.

-- 1. Create the login role. CHANGE THE PASSWORD before running.
CREATE ROLE teslamate_bridge WITH LOGIN PASSWORD 'change-me-please';

-- 2. Allow it to connect and to use the public schema (where the data tables live).
GRANT CONNECT ON DATABASE teslamate TO teslamate_bridge;
GRANT USAGE  ON SCHEMA   public     TO teslamate_bridge;

-- 3. Grant SELECT ONLY on the specific data tables the current play library reads.
--    Note we never GRANT the `tokens` table — that is what keeps your credentials
--    out of reach. Do NOT use `GRANT SELECT ON ALL TABLES IN SCHEMA public`:
--    on TeslaMate versions that keep `tokens` in the public schema that would
--    expose your credentials. List tables explicitly instead.
GRANT SELECT ON
    public.cars,
    public.drives,
    public.charging_processes,
    public.positions
TO teslamate_bridge;

-- If you add plays that query other TeslaMate data tables (e.g. charges,
-- addresses, geofences), add them to the allowlist, for example:
--   GRANT SELECT ON public.charges, public.addresses, public.geofences TO teslamate_bridge;
-- The one rule: never grant any access to `tokens`.

-- On current TeslaMate, `tokens` lives in the separate `private` schema, and
-- this role is given no USAGE on `private` — so the tokens table is doubly out
-- of reach (no schema access + not in the allowlist). The explicit allowlist
-- above is what protects you on every version, including older ones where
-- `tokens` is in `public`.

-- Verify what the role can read — `tokens` must NOT appear in the output:
--   SELECT table_schema, table_name
--   FROM information_schema.role_table_grants
--   WHERE grantee = 'teslamate_bridge'
--   ORDER BY table_schema, table_name;

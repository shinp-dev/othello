-- The trusted admin Worker lists pending verification submissions through
-- PostgREST. RLS bypass does not replace the underlying table privilege.
-- Keep the grant read-only; review and evidence cleanup remain RPC-only.
grant select on table public.verification_submissions to service_role;

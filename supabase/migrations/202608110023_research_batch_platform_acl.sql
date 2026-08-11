-- Hosted Supabase installs this event-trigger helper in `public` with the
-- PostgreSQL default PUBLIC EXECUTE grant. It is not an application RPC and
-- does not need to be executable by login roles. Keep the Research batch role
-- restricted to the wrappers introduced by migration 022.

do $$
begin
  if to_regprocedure('public.rls_auto_enable()') is not null then
    execute 'revoke execute on function public.rls_auto_enable() from public';
  end if;
end;
$$;

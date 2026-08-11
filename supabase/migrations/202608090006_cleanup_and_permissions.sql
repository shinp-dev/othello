-- Retention cleanup and deny-by-default permissions for internal helpers.

alter default privileges in schema public revoke execute on functions from public;
alter default privileges in schema public revoke execute on functions from anon;
alter default privileges in schema public revoke execute on functions from authenticated;

create or replace function public.cleanup_expired_match_submissions()
returns integer language plpgsql security definer set search_path = '' as $$
declare deleted_count integer;
begin
  delete from public.match_submissions s using public.matches m
   where s.match_id = m.id and s.expires_at <= now()
     and m.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED');
  get diagnostics deleted_count = row_count;
  return deleted_count;
end;
$$;

create or replace function public.cleanup_terminal_matches()
returns integer language plpgsql security definer set search_path = '' as $$
declare deleted_count integer;
begin
  perform public.cleanup_expired_match_submissions();
  delete from public.game_records g using public.matches m
   where g.match_id = m.id and g.expires_at <= now()
     and m.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED')
     and not exists (select 1 from public.user_game_records r where r.match_id = g.match_id);
  delete from public.matches m
   where m.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED')
     and m.retention_until is not null and m.retention_until <= now()
     and not exists (select 1 from public.game_records g where g.match_id = m.id)
     and not exists (select 1 from public.match_submissions s where s.match_id = m.id)
     and not exists (select 1 from public.active_match_participants a where a.match_id = m.id);
  get diagnostics deleted_count = row_count;
  return deleted_count;
end;
$$;

revoke execute on function public.prune_user_game_records(uuid) from public, anon, authenticated;
revoke execute on function public.prune_rating_history(uuid) from public, anon, authenticated;
revoke all on function public.cleanup_expired_match_submissions() from public;
revoke all on function public.cleanup_terminal_matches() from public;
grant execute on function public.cleanup_expired_match_submissions() to service_role;
grant execute on function public.cleanup_terminal_matches() to service_role;

comment on function public.cleanup_terminal_matches() is
  'Run from enqueue_or_match opportunistically or every hour with Supabase Cron/pg_cron.';

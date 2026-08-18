-- Account lifecycle policy for the initial release.
-- Unconfirmed registrations expire after 7 days. Confirmed accounts expire
-- after 365 days without an app access. Both paths only enqueue the existing
-- trusted account-deletion pipeline; this migration never deletes user data.

alter table public.profiles
  add column if not exists last_active_at timestamptz not null default now();

create index if not exists profiles_last_active_at_idx
  on public.profiles(last_active_at);

create or replace function public.touch_last_active()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  update public.profiles
     set last_active_at = now()
   where id = caller_id
     and deleted_at is null
     and last_active_at < now() - interval '1 day';
end;
$$;

create or replace function public.queue_expired_account_deletions()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  queued_count integer;
begin
  if auth.role() <> 'service_role' then
    raise exception 'admin service role required';
  end if;

  insert into public.account_deletion_requests(user_id, requested_at, status, completed_at)
  select u.id, now(), 'REQUESTED', null
    from auth.users u
    left join public.profiles p on p.id = u.id
   where (
     u.email_confirmed_at is null
     and u.created_at < now() - interval '7 days'
   ) or (
     u.email_confirmed_at is not null
     and p.last_active_at < now() - interval '365 days'
   )
   order by u.created_at
   limit 100
  on conflict (user_id) do nothing;
  get diagnostics queued_count = row_count;
  return queued_count;
end;
$$;

revoke all on function public.touch_last_active() from public, anon;
grant execute on function public.touch_last_active() to authenticated;
revoke all on function public.queue_expired_account_deletions() from public, anon, authenticated;
grant execute on function public.queue_expired_account_deletions() to service_role;

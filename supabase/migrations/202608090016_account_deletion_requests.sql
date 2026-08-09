-- Google Play account-deletion preparation. Android can only queue a request; a trusted
-- server-side worker must delete Auth/Storage data and anonymize shared records.
create table if not exists public.account_deletion_requests (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  requested_at timestamptz not null default now(),
  status text not null default 'REQUESTED' check (status in ('REQUESTED', 'PROCESSING', 'COMPLETED', 'REJECTED')),
  completed_at timestamptz
);

alter table public.account_deletion_requests enable row level security;
drop policy if exists "owner reads deletion request" on public.account_deletion_requests;
create policy "owner reads deletion request" on public.account_deletion_requests
  for select using (auth.uid() = user_id);

revoke all on table public.account_deletion_requests from public, anon, authenticated;
grant select on table public.account_deletion_requests to authenticated;
grant select, insert, update, delete on table public.account_deletion_requests to service_role;

create or replace function public.request_account_deletion()
returns timestamptz language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare request_time timestamptz;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if exists (
    select 1 from public.active_match_participants a where a.user_id = caller_id
  ) then raise exception 'active match must finish before account deletion'; end if;
  insert into public.account_deletion_requests(user_id, requested_at, status, completed_at)
  values (caller_id, now(), 'REQUESTED', null)
  on conflict (user_id) do update
    set requested_at = case
      when account_deletion_requests.status in ('COMPLETED', 'REJECTED') then now()
      else account_deletion_requests.requested_at
    end,
    status = case
      when account_deletion_requests.status in ('COMPLETED', 'REJECTED') then 'REQUESTED'
      else account_deletion_requests.status
    end,
    completed_at = case
      when account_deletion_requests.status in ('COMPLETED', 'REJECTED') then null
      else account_deletion_requests.completed_at
    end
  returning requested_at into request_time;
  return request_time;
end;
$$;

revoke all on function public.request_account_deletion() from public, anon;
grant execute on function public.request_account_deletion() to authenticated;

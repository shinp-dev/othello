-- Research 2E: stop collection at deletion request and unlink the account
-- identity before the trusted worker removes the Auth user. Research subjects,
-- accepted contributions, and aggregates are intentionally retained.

create or replace function public.request_account_deletion()
returns timestamptz
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  request_time timestamptz;
  subject_id uuid;
begin
  if caller_id is null then raise exception 'authentication required'; end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('othello.enqueue_or_match', 0)
  );
  if exists (
    select 1
      from public.active_match_participants a
     where a.user_id = caller_id
  ) then
    raise exception 'active match must finish before account deletion';
  end if;

  -- The subject row is the shared serialization point with CONFIRMED capture.
  select s.research_subject_id
    into subject_id
    from research_private.research_subjects s
   where s.account_user_id = caller_id
   for update;

  if subject_id is not null then
    update research_private.participation_periods
       set ended_at = coalesce(ended_at, now())
     where research_subject_id = subject_id
       and ended_at is null;
    update research_private.research_subjects
       set link_state = 'DELETION_PENDING'
     where research_subject_id = subject_id
       and link_state = 'LINKED';
  end if;

  delete from public.match_queue where user_id = caller_id;
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

create or replace function public.unlink_research_subject(p_user_id uuid)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  request_status text;
  subject_row research_private.research_subjects%rowtype;
begin
  if auth.role() <> 'service_role' then
    raise exception 'admin service role required';
  end if;

  select r.status
    into request_status
    from public.account_deletion_requests r
   where r.user_id = p_user_id
   for update;
  if request_status is null or request_status not in ('REQUESTED', 'PROCESSING') then
    raise exception 'account deletion is not unlinkable';
  end if;

  select s.*
    into subject_row
    from research_private.research_subjects s
   where s.account_user_id = p_user_id
   for update;

  if not found then
    -- A retry after a successful unlink is intentionally idempotent. The
    -- caller receives no subject identifier or historical account mapping.
    return 'ALREADY_UNLINKED';
  end if;

  update research_private.participation_periods
     set ended_at = coalesce(ended_at, now())
   where research_subject_id = subject_row.research_subject_id
     and ended_at is null;
  update research_private.research_subjects
     set account_user_id = null,
         link_state = 'UNLINKED',
         unlinked_at = coalesce(unlinked_at, now())
   where research_subject_id = subject_row.research_subject_id;
  return 'UNLINKED';
end;
$$;

revoke all on function public.unlink_research_subject(uuid) from public, anon, authenticated;
grant execute on function public.unlink_research_subject(uuid) to service_role;

-- Research foundation only: consent/policy/subject/participation state.
-- No match capture, research source, validator, aggregate, or Review API is created here.

create schema if not exists research_private;

revoke all on schema research_private from public, anon, authenticated;
grant usage on schema research_private to service_role;

alter default privileges in schema research_private revoke all on tables from public, anon, authenticated;
alter default privileges in schema research_private revoke all on sequences from public, anon, authenticated;
alter default privileges in schema research_private revoke execute on functions from public, anon, authenticated;

create table research_private.consent_versions (
  consent_version integer primary key check (consent_version > 0),
  effective_at timestamptz not null,
  document_sha256 text not null unique check (document_sha256 ~ '^[0-9a-f]{64}$'),
  summary text not null check (char_length(summary) between 1 and 500),
  created_at timestamptz not null default now()
);
create index research_consent_effective_idx
  on research_private.consent_versions (effective_at desc);

create table research_private.policy_versions (
  policy_version bigint generated always as identity primary key,
  effective_at timestamptz not null,
  research_consent_version integer not null references research_private.consent_versions(consent_version),
  eligibility_min_games integer not null default 10 check (eligibility_min_games > 0),
  eligibility_window_days integer not null default 90 check (eligibility_window_days > 0),
  position_min_users integer not null default 100 check (position_min_users > 0),
  move_min_users integer not null default 20 check (move_min_users > 0),
  min_decisions_per_qualifying_game integer not null default 10 check (min_decisions_per_qualifying_game > 0),
  ruleset_version integer not null default 1 check (ruleset_version > 0),
  normalization_version integer not null default 1 check (normalization_version > 0),
  collection_enabled boolean not null default false,
  is_active boolean not null default false,
  created_at timestamptz not null default now()
);

create unique index research_policy_one_active_idx
  on research_private.policy_versions ((true))
  where is_active;
create index research_policy_effective_idx
  on research_private.policy_versions (effective_at desc);

create table research_private.research_subjects (
  research_subject_id uuid primary key default gen_random_uuid(),
  account_user_id uuid,
  link_state text not null default 'LINKED'
    check (link_state in ('LINKED', 'DELETION_PENDING', 'UNLINKED')),
  linked_at timestamptz not null default now(),
  unlinked_at timestamptz,
  created_at timestamptz not null default now(),
  constraint research_subject_link_state_consistent check (
    (link_state = 'LINKED' and account_user_id is not null and unlinked_at is null)
    or (link_state = 'DELETION_PENDING' and account_user_id is not null and unlinked_at is null)
    or (link_state = 'UNLINKED' and account_user_id is null and unlinked_at is not null)
  )
);

create unique index research_subject_one_link_per_account_idx
  on research_private.research_subjects (account_user_id)
  where account_user_id is not null;
create index research_subject_link_state_idx
  on research_private.research_subjects (link_state);

create table research_private.participation_periods (
  participation_id uuid primary key default gen_random_uuid(),
  research_subject_id uuid not null references research_private.research_subjects(research_subject_id),
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  policy_version_at_start bigint not null references research_private.policy_versions(policy_version),
  consent_version integer not null references research_private.consent_versions(consent_version),
  created_at timestamptz not null default now(),
  constraint research_participation_period_range check (ended_at is null or ended_at >= started_at),
  constraint research_participation_subject_pair unique (participation_id, research_subject_id)
);

create unique index research_participation_one_open_idx
  on research_private.participation_periods (research_subject_id)
  where ended_at is null;
create index research_participation_subject_started_idx
  on research_private.participation_periods (research_subject_id, started_at desc);

alter table research_private.consent_versions enable row level security;
alter table research_private.policy_versions enable row level security;
alter table research_private.research_subjects enable row level security;
alter table research_private.participation_periods enable row level security;

revoke all on table
  research_private.consent_versions,
  research_private.policy_versions,
  research_private.research_subjects,
  research_private.participation_periods
from public, anon, authenticated;

grant select, insert, update, delete on table
  research_private.consent_versions,
  research_private.policy_versions,
  research_private.research_subjects,
  research_private.participation_periods
to service_role;

revoke all on sequence research_private.policy_versions_policy_version_seq from public, anon, authenticated;
grant usage, select on sequence research_private.policy_versions_policy_version_seq to service_role;

insert into research_private.consent_versions(
  consent_version,
  effective_at,
  document_sha256,
  summary
) values (
  1,
  now(),
  'd9ba89269ad4d623936f64056b82831f48e2f67e7b8798925cef866e6e593ad9',
  '研究参加、集合統計、Opt-out後とaccount削除後の研究寄与保持、Give-to-Getに関する同意 v1'
);

insert into research_private.policy_versions(
  effective_at,
  research_consent_version,
  eligibility_min_games,
  eligibility_window_days,
  position_min_users,
  move_min_users,
  min_decisions_per_qualifying_game,
  ruleset_version,
  normalization_version,
  collection_enabled,
  is_active
) values (
  now(), 1, 10, 90, 100, 20, 10, 1, 1, false, true
);

create or replace function research_private.get_participation_status_for(p_user_id uuid)
returns table (
  participation_on boolean,
  current_consent_version integer,
  agreed_consent_version integer,
  reconsent_required boolean,
  research_subject_linked boolean,
  current_period_exists boolean,
  current_participation_id uuid,
  current_period_started_at timestamptz,
  eligible boolean,
  can_view_research_data boolean,
  qualifying_game_count integer,
  required_game_count integer,
  window_days integer,
  collection_enabled boolean,
  collection_allowed boolean
)
language sql
stable
security definer
set search_path = ''
as $$
  with active_policy as (
    select p.*
      from research_private.policy_versions p
     where p.is_active
     limit 1
  ), linked_subject as (
    select s.*
      from research_private.research_subjects s
     where s.account_user_id = p_user_id
     order by s.created_at desc
     limit 1
  ), open_period as (
    select pp.*
      from research_private.participation_periods pp
     where pp.research_subject_id = (select research_subject_id from linked_subject)
       and pp.ended_at is null
     limit 1
  ), latest_period as (
    select pp.*
      from research_private.participation_periods pp
     where pp.research_subject_id = (select research_subject_id from linked_subject)
     order by pp.started_at desc
     limit 1
  )
  select
    coalesce(
      s.link_state = 'LINKED'
      and op.participation_id is not null
      and op.consent_version = p.research_consent_version,
      false
    ) as participation_on,
    p.research_consent_version,
    coalesce(op.consent_version, lp.consent_version) as agreed_consent_version,
    coalesce(
      s.link_state = 'LINKED'
      and op.participation_id is not null
      and op.consent_version <> p.research_consent_version,
      false
    ) as reconsent_required,
    coalesce(s.link_state = 'LINKED', false) as research_subject_linked,
    (op.participation_id is not null) as current_period_exists,
    op.participation_id as current_participation_id,
    op.started_at as current_period_started_at,
    false as eligible,
    false as can_view_research_data,
    0 as qualifying_game_count,
    p.eligibility_min_games as required_game_count,
    p.eligibility_window_days as window_days,
    p.collection_enabled,
    coalesce(
      p.collection_enabled
      and s.link_state = 'LINKED'
      and op.participation_id is not null
      and op.consent_version = p.research_consent_version,
      false
    ) as collection_allowed
  from active_policy p
  left join linked_subject s on true
  left join open_period op on true
  left join latest_period lp on true;
$$;

create or replace function public.get_research_participation_status()
returns table (
  participation_on boolean,
  current_consent_version integer,
  agreed_consent_version integer,
  reconsent_required boolean,
  research_subject_linked boolean,
  current_period_exists boolean,
  current_participation_id uuid,
  current_period_started_at timestamptz,
  eligible boolean,
  can_view_research_data boolean,
  qualifying_game_count integer,
  required_game_count integer,
  window_days integer,
  collection_enabled boolean,
  collection_allowed boolean
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  return query select * from research_private.get_participation_status_for(caller_id);
end;
$$;

create or replace function public.set_research_participation(
  p_enabled boolean,
  p_accepted_consent_version integer default null
)
returns table (
  participation_on boolean,
  current_consent_version integer,
  agreed_consent_version integer,
  reconsent_required boolean,
  research_subject_linked boolean,
  current_period_exists boolean,
  current_participation_id uuid,
  current_period_started_at timestamptz,
  eligible boolean,
  can_view_research_data boolean,
  qualifying_game_count integer,
  required_game_count integer,
  window_days integer,
  collection_enabled boolean,
  collection_allowed boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  active_policy research_private.policy_versions%rowtype;
  subject_row research_private.research_subjects%rowtype;
  open_period research_private.participation_periods%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;

  select p.* into active_policy
    from research_private.policy_versions p
   where p.is_active
   for share;
  if not found then raise exception 'active research policy required'; end if;

  select s.* into subject_row
    from research_private.research_subjects s
   where s.account_user_id = caller_id
   for update;

  if not p_enabled then
    if found then
      update research_private.participation_periods pp
         set ended_at = now()
       where pp.research_subject_id = subject_row.research_subject_id
         and pp.ended_at is null;
    end if;
    return query select * from research_private.get_participation_status_for(caller_id);
    return;
  end if;

  if p_accepted_consent_version is null
     or p_accepted_consent_version <> active_policy.research_consent_version then
    raise exception 'current research consent required';
  end if;
  if exists (
    select 1 from public.account_deletion_requests r
     where r.user_id = caller_id and r.status in ('REQUESTED', 'PROCESSING')
  ) then
    raise exception 'account deletion is pending';
  end if;

  if subject_row.research_subject_id is null then
    insert into research_private.research_subjects(account_user_id, link_state)
    values (caller_id, 'LINKED')
    on conflict (account_user_id) where account_user_id is not null do nothing;

    select s.* into subject_row
      from research_private.research_subjects s
     where s.account_user_id = caller_id
     for update;
  end if;

  if subject_row.link_state <> 'LINKED' then
    raise exception 'research subject is not linkable';
  end if;

  select pp.* into open_period
    from research_private.participation_periods pp
   where pp.research_subject_id = subject_row.research_subject_id
     and pp.ended_at is null
   for update;

  if open_period.participation_id is not null
     and open_period.consent_version = active_policy.research_consent_version then
    return query select * from research_private.get_participation_status_for(caller_id);
    return;
  end if;

  if open_period.participation_id is not null then
    update research_private.participation_periods
       set ended_at = now()
     where participation_id = open_period.participation_id;
  end if;

  insert into research_private.participation_periods(
    research_subject_id,
    policy_version_at_start,
    consent_version
  ) values (
    subject_row.research_subject_id,
    active_policy.policy_version,
    active_policy.research_consent_version
  );

  return query select * from research_private.get_participation_status_for(caller_id);
end;
$$;

revoke all on function research_private.get_participation_status_for(uuid) from public, anon, authenticated;
revoke all on function public.get_research_participation_status() from public, anon;
revoke all on function public.set_research_participation(boolean, integer) from public, anon;

grant execute on function public.get_research_participation_status() to authenticated;
grant execute on function public.set_research_participation(boolean, integer) to authenticated;

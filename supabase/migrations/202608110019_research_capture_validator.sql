-- Research stage 2B: O(1) CONFIRMED capture and service-only validation jobs.
-- Validation remains off the online finalization path and collection remains disabled
-- until an operator explicitly activates a later policy version.

create table research_private.games (
  research_game_id bigint generated always as identity primary key,
  source_match_key bytea not null unique check (octet_length(source_match_key) = 32),
  source_kind text not null default 'ONLINE' check (source_kind = 'ONLINE'),
  canonical_moves text not null check (
    char_length(canonical_moves) <= 240
    and (
      canonical_moves ~ '^((--|[a-h][1-8])+)$'
      or (canonical_moves = '' and finish_reason in ('RESIGNATION', 'TIMEOUT', 'DISCONNECT'))
    )
  ),
  result text not null check (result in ('BLACK_WIN', 'WHITE_WIN', 'DRAW')),
  finish_reason text not null check (finish_reason in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT')),
  final_position_hash text not null check (final_position_hash ~ '^[0-9a-f]{16}:[0-2]:[0-2]:[0-9]{1,3}$'),
  time_control text not null check (char_length(time_control) between 1 and 40),
  confirmed_at timestamptz not null,
  ruleset_version integer not null check (ruleset_version > 0),
  validation_status text not null default 'PENDING'
    check (validation_status in ('PENDING', 'PROCESSING', 'ACCEPTED', 'REJECTED')),
  validator_version integer check (validator_version is null or validator_version > 0),
  attempt_count integer not null default 0 check (attempt_count >= 0),
  lease_token uuid,
  lease_expires_at timestamptz,
  last_attempt_at timestamptz,
  processed_at timestamptz,
  rejection_code text check (rejection_code is null or rejection_code ~ '^[A-Z0-9_]{1,64}$'),
  created_at timestamptz not null default now(),
  constraint research_game_validation_state check (
    (validation_status = 'PENDING' and validator_version is null and lease_token is null
      and lease_expires_at is null and processed_at is null and rejection_code is null)
    or (validation_status = 'PROCESSING' and lease_token is not null
      and lease_expires_at is not null and processed_at is null and rejection_code is null)
    or (validation_status = 'ACCEPTED' and validator_version is not null and lease_token is not null
      and lease_expires_at is null and processed_at is not null and rejection_code is null)
    or (validation_status = 'REJECTED' and validator_version is not null and lease_token is not null
      and lease_expires_at is null and processed_at is not null and rejection_code is not null)
  )
);

create index research_games_validation_queue_idx
  on research_private.games (validation_status, lease_expires_at, confirmed_at, research_game_id);
create index research_games_confirmed_idx
  on research_private.games (confirmed_at desc);

create table research_private.game_contributors (
  research_game_id bigint not null references research_private.games(research_game_id) on delete cascade,
  research_subject_id uuid not null references research_private.research_subjects(research_subject_id),
  participation_id uuid not null,
  disc text not null check (disc in ('BLACK', 'WHITE')),
  rating_before integer not null,
  rating_algorithm_version text not null check (char_length(rating_algorithm_version) between 1 and 40),
  outcome_from_subject_perspective text not null check (outcome_from_subject_perspective in ('WIN', 'DRAW', 'LOSS')),
  confirmed_at timestamptz not null,
  contribution_status text not null default 'PENDING'
    check (contribution_status in ('PENDING', 'ACCEPTED', 'REJECTED')),
  decision_count integer check (decision_count is null or decision_count >= 0),
  accepted_at timestamptz,
  created_at timestamptz not null default now(),
  primary key (research_game_id, research_subject_id),
  foreign key (participation_id, research_subject_id)
    references research_private.participation_periods(participation_id, research_subject_id),
  constraint research_contribution_validation_state check (
    (contribution_status = 'PENDING' and decision_count is null and accepted_at is null)
    or (contribution_status = 'ACCEPTED' and decision_count is not null and accepted_at is not null)
    or (contribution_status = 'REJECTED' and decision_count is null and accepted_at is null)
  )
);

create index research_contributors_period_confirmed_idx
  on research_private.game_contributors (participation_id, confirmed_at desc);
create index research_contributors_subject_status_idx
  on research_private.game_contributors (research_subject_id, contribution_status);

alter table research_private.games enable row level security;
alter table research_private.game_contributors enable row level security;

revoke all on table research_private.games, research_private.game_contributors
  from public, anon, authenticated;
grant select, insert, update, delete on table
  research_private.games, research_private.game_contributors
  to service_role;
revoke all on sequence research_private.games_research_game_id_seq from public, anon, authenticated;
grant usage, select on sequence research_private.games_research_game_id_seq to service_role;

create or replace function research_private.capture_confirmed_match(p_match_id uuid)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  active_policy research_private.policy_versions%rowtype;
  match_row public.matches%rowtype;
  record_row public.game_records%rowtype;
  black_subject_id uuid;
  black_participation_id uuid;
  black_rating_before integer;
  black_rating_algorithm text;
  white_subject_id uuid;
  white_participation_id uuid;
  white_rating_before integer;
  white_rating_algorithm text;
  source_key bytea;
  captured_game_id bigint;
begin
  select p.* into active_policy
    from research_private.policy_versions p
   where p.is_active
   for share;
  if not found or not active_policy.collection_enabled then return null; end if;

  select m.* into match_row
    from public.matches m
   where m.id = p_match_id and m.server_status = 'CONFIRMED';
  if not found then return null; end if;

  select g.* into record_row
    from public.game_records g
   where g.match_id = p_match_id and g.final_position_hash is not null;
  if not found then return null; end if;

  -- Capture, Opt-out/re-consent, and account deletion all serialize on these
  -- subject rows. UUID order prevents two-participant lock inversion.
  perform s.research_subject_id
    from research_private.research_subjects s
   where s.account_user_id in (match_row.black_player, match_row.white_player)
   order by s.research_subject_id
   for update;

  select s.research_subject_id, pp.participation_id,
         rh.rating - rh.delta, rh.algorithm_version
    into black_subject_id, black_participation_id, black_rating_before, black_rating_algorithm
    from research_private.research_subjects s
    join research_private.participation_periods pp
      on pp.research_subject_id = s.research_subject_id and pp.ended_at is null
    join public.rating_history rh
      on rh.user_id = match_row.black_player and rh.match_id = p_match_id
   where s.account_user_id = match_row.black_player
     and s.link_state = 'LINKED'
     and pp.consent_version = active_policy.research_consent_version
     and not exists (
       select 1 from public.account_deletion_requests d
        where d.user_id = match_row.black_player and d.status in ('REQUESTED', 'PROCESSING')
     );

  select s.research_subject_id, pp.participation_id,
         rh.rating - rh.delta, rh.algorithm_version
    into white_subject_id, white_participation_id, white_rating_before, white_rating_algorithm
    from research_private.research_subjects s
    join research_private.participation_periods pp
      on pp.research_subject_id = s.research_subject_id and pp.ended_at is null
    join public.rating_history rh
      on rh.user_id = match_row.white_player and rh.match_id = p_match_id
   where s.account_user_id = match_row.white_player
     and s.link_state = 'LINKED'
     and pp.consent_version = active_policy.research_consent_version
     and not exists (
       select 1 from public.account_deletion_requests d
        where d.user_id = match_row.white_player and d.status in ('REQUESTED', 'PROCESSING')
     );

  if black_subject_id is null and white_subject_id is null then return null; end if;

  source_key := extensions.digest('chanriba:research:online:v1:' || p_match_id::text, 'sha256');
  insert into research_private.games(
    source_match_key, source_kind, canonical_moves, result, finish_reason,
    final_position_hash, time_control, confirmed_at, ruleset_version
  ) values (
    source_key, 'ONLINE', record_row.canonical_moves, record_row.result,
    record_row.finish_reason, record_row.final_position_hash, record_row.time_control,
    match_row.confirmed_at, active_policy.ruleset_version
  )
  on conflict (source_match_key) do nothing
  returning research_game_id into captured_game_id;

  if captured_game_id is null then
    select g.research_game_id into captured_game_id
      from research_private.games g where g.source_match_key = source_key;
  end if;

  if black_subject_id is not null then
    insert into research_private.game_contributors(
      research_game_id, research_subject_id, participation_id, disc,
      rating_before, rating_algorithm_version, outcome_from_subject_perspective, confirmed_at
    ) values (
      captured_game_id, black_subject_id, black_participation_id, 'BLACK',
      black_rating_before, black_rating_algorithm,
      case record_row.result when 'BLACK_WIN' then 'WIN' when 'WHITE_WIN' then 'LOSS' else 'DRAW' end,
      match_row.confirmed_at
    ) on conflict (research_game_id, research_subject_id) do nothing;
  end if;

  if white_subject_id is not null then
    insert into research_private.game_contributors(
      research_game_id, research_subject_id, participation_id, disc,
      rating_before, rating_algorithm_version, outcome_from_subject_perspective, confirmed_at
    ) values (
      captured_game_id, white_subject_id, white_participation_id, 'WHITE',
      white_rating_before, white_rating_algorithm,
      case record_row.result when 'WHITE_WIN' then 'WIN' when 'BLACK_WIN' then 'LOSS' else 'DRAW' end,
      match_row.confirmed_at
    ) on conflict (research_game_id, research_subject_id) do nothing;
  end if;

  return captured_game_id;
end;
$$;

create or replace function research_private.capture_confirmed_match_trigger()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.server_status = 'CONFIRMED'
     and old.server_status is distinct from new.server_status then
    perform research_private.capture_confirmed_match(new.id);
  end if;
  return new;
end;
$$;

create trigger capture_confirmed_match_for_research
after update of server_status on public.matches
for each row execute function research_private.capture_confirmed_match_trigger();

create or replace function public.claim_research_validation_batch(
  p_limit integer default 10,
  p_lease_seconds integer default 300
)
returns table (
  research_game_id bigint,
  lease_token uuid,
  canonical_moves text,
  result text,
  finish_reason text,
  final_position_hash text,
  ruleset_version integer
)
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if p_limit not between 1 and 50 then raise exception 'invalid validation batch size'; end if;
  if p_lease_seconds not between 30 and 900 then raise exception 'invalid validation lease'; end if;

  return query
  with candidates as (
    select g.research_game_id
      from research_private.games g
     where g.validation_status = 'PENDING'
        or (g.validation_status = 'PROCESSING' and g.lease_expires_at <= now())
     order by g.confirmed_at, g.research_game_id
     for update skip locked
     limit p_limit
  ), claimed as (
    update research_private.games g
       set validation_status = 'PROCESSING',
           lease_token = gen_random_uuid(),
           lease_expires_at = now() + make_interval(secs => p_lease_seconds),
           attempt_count = g.attempt_count + 1,
           last_attempt_at = now()
      from candidates c
     where g.research_game_id = c.research_game_id
    returning g.*
  )
  select c.research_game_id, c.lease_token, c.canonical_moves, c.result,
         c.finish_reason, c.final_position_hash, c.ruleset_version
    from claimed c
   order by c.confirmed_at, c.research_game_id;
end;
$$;

create or replace function public.complete_research_validation(
  p_research_game_id bigint,
  p_lease_token uuid,
  p_validator_version integer,
  p_accepted boolean,
  p_rejection_code text default null,
  p_black_decision_count integer default null,
  p_white_decision_count integer default null
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare game_row research_private.games%rowtype;
declare terminal_status text := case when p_accepted then 'ACCEPTED' else 'REJECTED' end;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if p_validator_version is null or p_validator_version <= 0 then raise exception 'invalid validator version'; end if;
  if p_accepted and (
      p_rejection_code is not null
      or p_black_decision_count is null or p_black_decision_count < 0
      or p_white_decision_count is null or p_white_decision_count < 0
    ) then raise exception 'accepted validation requires decision counts'; end if;
  if not p_accepted and (
      p_rejection_code is null or p_rejection_code !~ '^[A-Z0-9_]{1,64}$'
      or p_black_decision_count is not null or p_white_decision_count is not null
    ) then raise exception 'rejected validation requires a safe rejection code'; end if;

  select g.* into game_row
    from research_private.games g
   where g.research_game_id = p_research_game_id
   for update;
  if not found then raise exception 'research game not found'; end if;

  if game_row.validation_status in ('ACCEPTED', 'REJECTED') then
    if game_row.lease_token = p_lease_token
       and game_row.validation_status = terminal_status
       and game_row.validator_version = p_validator_version
       and coalesce(game_row.rejection_code, '') = coalesce(p_rejection_code, '')
       and (
         not p_accepted
         or not exists (
           select 1 from research_private.game_contributors c
            where c.research_game_id = p_research_game_id
              and c.decision_count is distinct from case c.disc
                when 'BLACK' then p_black_decision_count
                when 'WHITE' then p_white_decision_count
              end
         )
       ) then
      return game_row.validation_status;
    end if;
    raise exception 'validation completion conflict';
  end if;
  if game_row.validation_status <> 'PROCESSING' or game_row.lease_token <> p_lease_token then
    raise exception 'validation lease mismatch';
  end if;

  update research_private.games
     set validation_status = terminal_status,
         validator_version = p_validator_version,
         lease_expires_at = null,
         processed_at = now(),
         rejection_code = p_rejection_code
   where research_game_id = p_research_game_id;

  if p_accepted then
    update research_private.game_contributors c
       set contribution_status = 'ACCEPTED',
           decision_count = case c.disc
             when 'BLACK' then p_black_decision_count
             when 'WHITE' then p_white_decision_count
           end,
           accepted_at = now()
     where c.research_game_id = p_research_game_id;
  else
    update research_private.game_contributors c
       set contribution_status = 'REJECTED', decision_count = null, accepted_at = null
     where c.research_game_id = p_research_game_id;
  end if;

  return terminal_status;
end;
$$;

-- The deletion request does not unlink the subject in 2B. It only participates in
-- the same subject-row serialization protocol so capture is deterministically before
-- or after the request. The 2E worker will close/unlink it later.
create or replace function public.request_account_deletion()
returns timestamptz language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare request_time timestamptz;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('othello.enqueue_or_match', 0));
  if exists (select 1 from public.active_match_participants a where a.user_id = caller_id) then
    raise exception 'active match must finish before account deletion';
  end if;
  perform s.research_subject_id
    from research_private.research_subjects s
   where s.account_user_id = caller_id
   for update;
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

revoke all on function research_private.capture_confirmed_match(uuid) from public, anon, authenticated;
revoke all on function research_private.capture_confirmed_match_trigger() from public, anon, authenticated;
revoke all on function public.claim_research_validation_batch(integer, integer) from public, anon, authenticated;
revoke all on function public.complete_research_validation(bigint, uuid, integer, boolean, text, integer, integer) from public, anon, authenticated;
grant execute on function public.claim_research_validation_batch(integer, integer) to service_role;
grant execute on function public.complete_research_validation(bigint, uuid, integer, boolean, text, integer, integer) to service_role;

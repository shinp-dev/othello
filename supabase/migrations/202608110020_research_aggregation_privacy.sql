-- Research stage 2C: rebuildable subject-normalized aggregates, atomic publication,
-- Give-to-Get eligibility, and a threshold-enforcing client RPC.

create table research_private.positions (
  position_id bigint generated always as identity primary key,
  ruleset_version integer not null check (ruleset_version > 0),
  normalization_version integer not null check (normalization_version > 0),
  black_hex text not null check (black_hex ~ '^[0-9a-f]{16}$'),
  white_hex text not null check (white_hex ~ '^[0-9a-f]{16}$'),
  side_to_move text not null check (side_to_move in ('BLACK', 'WHITE')),
  position_token text generated always as (
    'r8v' || ruleset_version || 'n' || normalization_version || ':'
      || black_hex || ':' || white_hex || ':' || left(side_to_move, 1)
  ) stored unique,
  legal_move_mask_hex text not null check (legal_move_mask_hex ~ '^[0-9a-f]{16}$'),
  created_at timestamptz not null default now(),
  unique (ruleset_version, normalization_version, black_hex, white_hex, side_to_move)
);

create table research_private.aggregation_generations (
  generation_id bigint generated always as identity primary key,
  policy_version bigint not null references research_private.policy_versions(policy_version),
  ruleset_version integer not null check (ruleset_version > 0),
  normalization_version integer not null check (normalization_version > 0),
  status text not null check (status in ('BUILDING', 'READY', 'PUBLISHED', 'FAILED')),
  source_watermark bigint not null check (source_watermark >= 0),
  source_cutoff_at timestamptz not null,
  lease_token uuid,
  lease_expires_at timestamptz,
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  published_at timestamptz,
  failure_code text check (failure_code is null or failure_code ~ '^[A-Z0-9_]{1,64}$'),
  constraint research_generation_state check (
    (status = 'BUILDING' and lease_token is not null and lease_expires_at is not null
      and completed_at is null and published_at is null and failure_code is null)
    or (status = 'READY' and completed_at is not null and failure_code is null)
    or (status = 'PUBLISHED' and completed_at is not null and published_at is not null and failure_code is null)
    or (status = 'FAILED' and completed_at is not null and failure_code is not null)
  )
);

create unique index research_generation_one_build_idx
  on research_private.aggregation_generations ((true)) where status = 'BUILDING';
create unique index research_generation_one_published_idx
  on research_private.aggregation_generations ((true)) where status = 'PUBLISHED';
create index research_generation_status_started_idx
  on research_private.aggregation_generations (status, started_at desc);

create table research_private.aggregation_segments (
  generation_id bigint not null references research_private.aggregation_generations(generation_id) on delete cascade,
  segment_key text not null check (segment_key ~ '^[A-Z0-9_]{1,32}$'),
  segment_type text not null check (segment_type in ('ALL', 'RATING', 'PERIOD')),
  definition_version integer not null check (definition_version > 0),
  rating_min_inclusive integer,
  rating_max_exclusive integer,
  period_start timestamptz,
  period_end timestamptz,
  primary key (generation_id, segment_key),
  constraint research_segment_definition check (
    (segment_type = 'ALL' and rating_min_inclusive is null and rating_max_exclusive is null
      and period_start is null and period_end is null)
    or (segment_type = 'RATING' and rating_min_inclusive is not null and rating_max_exclusive is not null
      and rating_min_inclusive < rating_max_exclusive and period_start is null and period_end is null)
    or (segment_type = 'PERIOD' and period_start is not null and period_end is not null
      and period_start < period_end and rating_min_inclusive is null and rating_max_exclusive is null)
  )
);

create table research_private.generation_processed_games (
  generation_id bigint not null references research_private.aggregation_generations(generation_id) on delete cascade,
  research_game_id bigint not null references research_private.games(research_game_id),
  processed_at timestamptz not null default now(),
  primary key (generation_id, research_game_id)
);

create table research_private.subject_position_totals (
  generation_id bigint not null,
  segment_key text not null,
  position_id bigint not null references research_private.positions(position_id),
  research_subject_id uuid not null references research_private.research_subjects(research_subject_id),
  occurrence_count bigint not null check (occurrence_count > 0),
  primary key (generation_id, segment_key, position_id, research_subject_id),
  foreign key (generation_id, segment_key)
    references research_private.aggregation_segments(generation_id, segment_key) on delete cascade
);

create index research_subject_position_lookup_idx
  on research_private.subject_position_totals (generation_id, segment_key, position_id);
create index research_subject_generation_idx
  on research_private.subject_position_totals (research_subject_id, generation_id);

create table research_private.subject_position_moves (
  generation_id bigint not null,
  segment_key text not null,
  position_id bigint not null,
  research_subject_id uuid not null,
  move_index integer not null check (move_index between 0 and 63),
  choice_count bigint not null check (choice_count > 0),
  win_count bigint not null default 0 check (win_count >= 0),
  draw_count bigint not null default 0 check (draw_count >= 0),
  loss_count bigint not null default 0 check (loss_count >= 0),
  child_position_id bigint references research_private.positions(position_id),
  primary key (generation_id, segment_key, position_id, research_subject_id, move_index),
  foreign key (generation_id, segment_key, position_id, research_subject_id)
    references research_private.subject_position_totals(generation_id, segment_key, position_id, research_subject_id)
    on delete cascade,
  check (win_count + draw_count + loss_count = choice_count)
);

create index research_subject_move_lookup_idx
  on research_private.subject_position_moves (generation_id, segment_key, position_id, move_index);

create table research_private.position_aggregates (
  generation_id bigint not null,
  segment_key text not null,
  position_id bigint not null references research_private.positions(position_id),
  unique_contributors integer not null check (unique_contributors > 0),
  generated_at timestamptz not null default now(),
  primary key (generation_id, segment_key, position_id),
  foreign key (generation_id, segment_key)
    references research_private.aggregation_segments(generation_id, segment_key) on delete cascade
);

create table research_private.move_aggregates (
  generation_id bigint not null,
  segment_key text not null,
  position_id bigint not null,
  move_index integer not null check (move_index between 0 and 63),
  unique_contributors integer not null check (unique_contributors > 0),
  choice_weight_sum numeric not null check (choice_weight_sum > 0),
  win_weight_sum numeric not null check (win_weight_sum >= 0),
  draw_weight_sum numeric not null check (draw_weight_sum >= 0),
  loss_weight_sum numeric not null check (loss_weight_sum >= 0),
  child_position_id bigint references research_private.positions(position_id),
  primary key (generation_id, segment_key, position_id, move_index),
  foreign key (generation_id, segment_key, position_id)
    references research_private.position_aggregates(generation_id, segment_key, position_id) on delete cascade,
  check (win_weight_sum + draw_weight_sum + loss_weight_sum = choice_weight_sum)
);

create table research_private.published_generation (
  singleton boolean primary key default true check (singleton),
  generation_id bigint not null unique references research_private.aggregation_generations(generation_id),
  updated_at timestamptz not null default now()
);

do $$
declare table_name text;
begin
  foreach table_name in array array[
    'positions', 'aggregation_generations', 'aggregation_segments', 'generation_processed_games',
    'subject_position_totals', 'subject_position_moves', 'position_aggregates', 'move_aggregates',
    'published_generation'
  ] loop
    execute format('alter table research_private.%I enable row level security', table_name);
    execute format('revoke all on table research_private.%I from public, anon, authenticated', table_name);
    execute format('grant select, insert, update, delete on table research_private.%I to service_role', table_name);
  end loop;
end;
$$;

create or replace function public.append_research_aggregation_game(
  p_generation_id bigint,
  p_lease_token uuid,
  p_research_game_id bigint,
  p_decisions jsonb
)
returns boolean
language plpgsql security definer set search_path = '' as $$
declare
  generation_row research_private.aggregation_generations%rowtype;
  game_row research_private.games%rowtype;
  decision jsonb;
  position_id_value bigint;
  child_position_id_value bigint;
  subject_id_value uuid;
  side_value text;
  outcome_value text;
  move_index_value integer;
  child_fields integer;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if jsonb_typeof(p_decisions) <> 'array' then raise exception 'research decisions must be an array'; end if;

  select g.* into generation_row
    from research_private.aggregation_generations g
   where g.generation_id = p_generation_id
   for update;
  if not found or generation_row.status <> 'BUILDING'
     or generation_row.lease_token <> p_lease_token
     or generation_row.lease_expires_at <= now() then
    raise exception 'aggregation lease mismatch';
  end if;

  select g.* into game_row
    from research_private.games g
   where g.research_game_id = p_research_game_id
     and g.research_game_id <= generation_row.source_watermark
     and g.validation_status = 'ACCEPTED'
     and g.ruleset_version = generation_row.ruleset_version
     and g.processed_at <= generation_row.source_cutoff_at;
  if not found then raise exception 'accepted research game required'; end if;

  if exists (
    select 1
      from jsonb_array_elements(p_decisions) d
      left join research_private.game_contributors c
       on c.research_game_id = p_research_game_id
       and c.contribution_status = 'ACCEPTED'
       and c.accepted_at <= generation_row.source_cutoff_at
       and c.research_subject_id::text = d.value ->> 'research_subject_id'
     where c.research_subject_id is null
        or d.value ->> 'side' is distinct from c.disc
        or d.value ->> 'outcome' is distinct from c.outcome_from_subject_perspective
  ) then raise exception 'decision contributor mismatch'; end if;

  if exists (
    select 1
      from research_private.game_contributors c
     where c.research_game_id = p_research_game_id
       and c.contribution_status = 'ACCEPTED'
       and c.accepted_at <= generation_row.source_cutoff_at
       and c.decision_count <> (
         select count(*)
           from jsonb_array_elements(p_decisions) d
          where d.value ->> 'research_subject_id' = c.research_subject_id::text
       )
  ) then raise exception 'decision count mismatch'; end if;

  insert into research_private.generation_processed_games(generation_id, research_game_id)
  values (p_generation_id, p_research_game_id)
  on conflict do nothing;
  if not found then
    update research_private.aggregation_generations
       set lease_expires_at = now() + interval '15 minutes'
     where generation_id = p_generation_id;
    return false;
  end if;

  for decision in select value from jsonb_array_elements(p_decisions) loop
    if coalesce(decision ->> 'research_subject_id', '') !~
         '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       or coalesce(decision ->> 'black_hex', '') !~ '^[0-9a-f]{16}$'
       or coalesce(decision ->> 'white_hex', '') !~ '^[0-9a-f]{16}$'
       or coalesce(decision ->> 'legal_move_mask_hex', '') !~ '^[0-9a-f]{16}$'
       or coalesce(decision ->> 'side', '') not in ('BLACK', 'WHITE')
       or coalesce(decision ->> 'outcome', '') not in ('WIN', 'DRAW', 'LOSS')
       or coalesce(decision ->> 'move_index', '') !~ '^[0-9]{1,2}$'
       or (decision ->> 'move_index')::integer not between 0 and 63 then
      raise exception 'invalid research decision';
    end if;

    child_fields := (decision ? 'child_black_hex')::integer
      + (decision ? 'child_white_hex')::integer
      + (decision ? 'child_side')::integer
      + (decision ? 'child_legal_move_mask_hex')::integer;
    if child_fields not in (0, 4) then raise exception 'incomplete research child position'; end if;
    if child_fields = 4 and (
      coalesce(decision ->> 'child_black_hex', '') !~ '^[0-9a-f]{16}$'
      or coalesce(decision ->> 'child_white_hex', '') !~ '^[0-9a-f]{16}$'
      or coalesce(decision ->> 'child_side', '') not in ('BLACK', 'WHITE')
      or coalesce(decision ->> 'child_legal_move_mask_hex', '') !~ '^[0-9a-f]{16}$'
    ) then raise exception 'invalid research child position'; end if;

    subject_id_value := (decision ->> 'research_subject_id')::uuid;
    side_value := decision ->> 'side';
    outcome_value := decision ->> 'outcome';
    move_index_value := (decision ->> 'move_index')::integer;

    position_id_value := research_private.upsert_position(
      generation_row.ruleset_version, generation_row.normalization_version,
      decision ->> 'black_hex', decision ->> 'white_hex', side_value,
      decision ->> 'legal_move_mask_hex'
    );
    child_position_id_value := null;
    if child_fields = 4 then
      child_position_id_value := research_private.upsert_position(
        generation_row.ruleset_version, generation_row.normalization_version,
        decision ->> 'child_black_hex', decision ->> 'child_white_hex',
        decision ->> 'child_side', decision ->> 'child_legal_move_mask_hex'
      );
    end if;

    insert into research_private.subject_position_totals(
      generation_id, segment_key, position_id, research_subject_id, occurrence_count
    ) values (p_generation_id, 'ALL', position_id_value, subject_id_value, 1)
    on conflict (generation_id, segment_key, position_id, research_subject_id)
    do update set occurrence_count = research_private.subject_position_totals.occurrence_count + 1;

    insert into research_private.subject_position_moves(
      generation_id, segment_key, position_id, research_subject_id, move_index,
      choice_count, win_count, draw_count, loss_count, child_position_id
    ) values (
      p_generation_id, 'ALL', position_id_value, subject_id_value, move_index_value,
      1, (outcome_value = 'WIN')::integer, (outcome_value = 'DRAW')::integer,
      (outcome_value = 'LOSS')::integer, child_position_id_value
    )
    on conflict (generation_id, segment_key, position_id, research_subject_id, move_index)
    do update set
      choice_count = research_private.subject_position_moves.choice_count + 1,
      win_count = research_private.subject_position_moves.win_count + excluded.win_count,
      draw_count = research_private.subject_position_moves.draw_count + excluded.draw_count,
      loss_count = research_private.subject_position_moves.loss_count + excluded.loss_count,
      child_position_id = excluded.child_position_id
    where research_private.subject_position_moves.child_position_id
      is not distinct from excluded.child_position_id;
    if not found then raise exception 'research child position conflict'; end if;
  end loop;

  update research_private.aggregation_generations
     set lease_expires_at = now() + interval '15 minutes'
   where generation_id = p_generation_id;
  return true;
end;
$$;

create or replace function public.publish_research_aggregation(
  p_generation_id bigint,
  p_lease_token uuid
)
returns text
language plpgsql security definer set search_path = '' as $$
declare generation_row research_private.aggregation_generations%rowtype;
declare active_policy_version bigint;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('chanriba.research.aggregate.publish', 0));
  select g.* into generation_row from research_private.aggregation_generations g
   where g.generation_id = p_generation_id for update;
  if not found then raise exception 'aggregation generation not found'; end if;
  if generation_row.status = 'PUBLISHED' and generation_row.lease_token = p_lease_token then
    return 'PUBLISHED';
  end if;
  if generation_row.status <> 'BUILDING' or generation_row.lease_token <> p_lease_token
     or generation_row.lease_expires_at <= now() then raise exception 'aggregation lease mismatch'; end if;

  select p.policy_version into active_policy_version
    from research_private.policy_versions p where p.is_active for share;
  if active_policy_version is distinct from generation_row.policy_version then
    update research_private.aggregation_generations
       set status = 'FAILED', completed_at = now(), lease_expires_at = null,
           failure_code = 'POLICY_CHANGED'
     where generation_id = p_generation_id;
    return 'FAILED_POLICY_CHANGED';
  end if;

  if exists (
    select 1 from research_private.games g
     where g.validation_status = 'ACCEPTED'
       and g.ruleset_version = generation_row.ruleset_version
       and g.research_game_id <= generation_row.source_watermark
       and g.processed_at <= generation_row.source_cutoff_at
       and exists (select 1 from research_private.game_contributors c
         where c.research_game_id = g.research_game_id and c.contribution_status = 'ACCEPTED'
           and c.accepted_at <= generation_row.source_cutoff_at)
       and not exists (select 1 from research_private.generation_processed_games x
         where x.generation_id = p_generation_id and x.research_game_id = g.research_game_id)
  ) then raise exception 'aggregation source processing incomplete'; end if;

  if exists (
    select 1 from research_private.subject_position_totals t
     where t.generation_id = p_generation_id
       and t.occurrence_count <> (select coalesce(sum(m.choice_count), 0)
         from research_private.subject_position_moves m
        where m.generation_id = t.generation_id and m.segment_key = t.segment_key
          and m.position_id = t.position_id and m.research_subject_id = t.research_subject_id)
  ) then raise exception 'subject position normalization source mismatch'; end if;

  if exists (
    select 1 from research_private.subject_position_moves m
     where m.generation_id = p_generation_id
     group by m.segment_key, m.position_id, m.move_index
    having count(distinct m.child_position_id) > 1
       or (count(m.child_position_id) > 0 and count(m.child_position_id) < count(*))
  ) then raise exception 'aggregate child position mismatch'; end if;

  delete from research_private.move_aggregates where generation_id = p_generation_id;
  delete from research_private.position_aggregates where generation_id = p_generation_id;

  insert into research_private.position_aggregates(
    generation_id, segment_key, position_id, unique_contributors
  )
  select t.generation_id, t.segment_key, t.position_id, count(*)::integer
    from research_private.subject_position_totals t
   where t.generation_id = p_generation_id
   group by t.generation_id, t.segment_key, t.position_id;

  insert into research_private.move_aggregates(
    generation_id, segment_key, position_id, move_index, unique_contributors,
    choice_weight_sum, win_weight_sum, draw_weight_sum, loss_weight_sum, child_position_id
  )
  select m.generation_id, m.segment_key, m.position_id, m.move_index, count(*)::integer,
         sum(m.choice_count::numeric / t.occurrence_count),
         sum(m.win_count::numeric / t.occurrence_count),
         sum(m.draw_count::numeric / t.occurrence_count),
         sum(m.loss_count::numeric / t.occurrence_count),
         min(m.child_position_id)
    from research_private.subject_position_moves m
    join research_private.subject_position_totals t
      on t.generation_id = m.generation_id and t.segment_key = m.segment_key
     and t.position_id = m.position_id and t.research_subject_id = m.research_subject_id
   where m.generation_id = p_generation_id
   group by m.generation_id, m.segment_key, m.position_id, m.move_index;

  update research_private.aggregation_generations
     set status = 'READY', completed_at = now(), lease_expires_at = null
   where generation_id = p_generation_id;
  update research_private.aggregation_generations
     set status = 'READY'
   where status = 'PUBLISHED' and generation_id <> p_generation_id;
  update research_private.aggregation_generations
     set status = 'PUBLISHED', published_at = now()
   where generation_id = p_generation_id;
  insert into research_private.published_generation(singleton, generation_id, updated_at)
  values (true, p_generation_id, now())
  on conflict (singleton) do update
    set generation_id = excluded.generation_id, updated_at = excluded.updated_at;
  return 'PUBLISHED';
end;
$$;

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
language sql stable security definer set search_path = '' as $$
  with active_policy as (
    select p.* from research_private.policy_versions p where p.is_active limit 1
  ), linked_subject as (
    select s.* from research_private.research_subjects s
     where s.account_user_id = p_user_id order by s.created_at desc limit 1
  ), open_period as (
    select pp.* from research_private.participation_periods pp
     where pp.research_subject_id = (select research_subject_id from linked_subject)
       and pp.ended_at is null limit 1
  ), latest_period as (
    select pp.* from research_private.participation_periods pp
     where pp.research_subject_id = (select research_subject_id from linked_subject)
     order by pp.started_at desc limit 1
  ), qualifying as (
    select count(distinct c.research_game_id)::integer as game_count
      from active_policy p
      join linked_subject s on s.link_state = 'LINKED'
      join open_period op on op.research_subject_id = s.research_subject_id
       and op.consent_version = p.research_consent_version
      join research_private.game_contributors c
        on c.research_subject_id = s.research_subject_id
       and c.participation_id = op.participation_id
       and c.contribution_status = 'ACCEPTED'
       and c.decision_count >= p.min_decisions_per_qualifying_game
       and c.confirmed_at >= greatest(op.started_at, now() - make_interval(days => p.eligibility_window_days))
       and c.confirmed_at <= now()
      join research_private.games g on g.research_game_id = c.research_game_id
       and g.source_kind = 'ONLINE' and g.validation_status = 'ACCEPTED'
  ), state as (
    select p.*, s.research_subject_id, s.link_state, op.participation_id,
           op.started_at, op.consent_version as open_consent_version,
           lp.consent_version as latest_consent_version,
           coalesce(q.game_count, 0) as game_count
      from active_policy p
      left join linked_subject s on true
      left join open_period op on true
      left join latest_period lp on true
      left join qualifying q on true
  )
  select
    coalesce(link_state = 'LINKED' and participation_id is not null
      and open_consent_version = research_consent_version, false),
    research_consent_version,
    coalesce(open_consent_version, latest_consent_version),
    coalesce(link_state = 'LINKED' and participation_id is not null
      and open_consent_version <> research_consent_version, false),
    coalesce(link_state = 'LINKED', false),
    participation_id is not null,
    participation_id,
    started_at,
    coalesce(link_state = 'LINKED' and participation_id is not null
      and open_consent_version = research_consent_version
      and game_count >= eligibility_min_games, false),
    coalesce(collection_enabled and link_state = 'LINKED' and participation_id is not null
      and open_consent_version = research_consent_version
      and game_count >= eligibility_min_games
      and exists (
        select 1 from research_private.published_generation pg
        join research_private.aggregation_generations g on g.generation_id = pg.generation_id
         where pg.singleton and g.status = 'PUBLISHED'
           and g.policy_version = (select p2.policy_version from active_policy p2)
      ), false),
    game_count,
    eligibility_min_games,
    eligibility_window_days,
    collection_enabled,
    coalesce(collection_enabled and link_state = 'LINKED' and participation_id is not null
      and open_consent_version = research_consent_version, false)
  from state;
$$;

create or replace function research_private.position_token(p research_private.positions)
returns text language sql immutable security definer set search_path = '' as $$
  select p.position_token
$$;

create or replace function public.get_research_position(
  p_position_token text,
  p_segment_key text default 'ALL'
)
returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  caller_id uuid := auth.uid();
  status_row record;
  policy_row research_private.policy_versions%rowtype;
  generation_row research_private.aggregation_generations%rowtype;
  position_row research_private.positions%rowtype;
  position_aggregate_row research_private.position_aggregates%rowtype;
  moves_value jsonb := '[]'::jsonb;
  other_value jsonb := null;
  other_unique integer;
  other_choice numeric;
  other_win numeric;
  other_draw numeric;
  other_loss numeric;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into status_row from research_private.get_participation_status_for(caller_id);
  if not found or not status_row.can_view_research_data then
    return jsonb_build_object('available', false, 'reason', 'NOT_ELIGIBLE');
  end if;
  select p.* into policy_row from research_private.policy_versions p where p.is_active;
  select g.* into generation_row
    from research_private.published_generation pg
    join research_private.aggregation_generations g on g.generation_id = pg.generation_id
   where pg.singleton and g.status = 'PUBLISHED';
  if not found or generation_row.policy_version <> policy_row.policy_version then
    return jsonb_build_object('available', false, 'reason', 'NO_PUBLISHED_GENERATION');
  end if;
  if p_segment_key <> 'ALL' or not exists (
    select 1 from research_private.aggregation_segments s
     where s.generation_id = generation_row.generation_id
       and s.segment_key = p_segment_key and s.segment_type = 'ALL'
  ) then return jsonb_build_object('available', false, 'reason', 'UNSUPPORTED_SEGMENT'); end if;

  select p.* into position_row from research_private.positions p
   where p.position_token = p_position_token
     and p.ruleset_version = generation_row.ruleset_version
     and p.normalization_version = generation_row.normalization_version;
  if not found then return jsonb_build_object('available', false, 'reason', 'INSUFFICIENT_SAMPLE'); end if;
  select a.* into position_aggregate_row from research_private.position_aggregates a
   where a.generation_id = generation_row.generation_id and a.segment_key = p_segment_key
     and a.position_id = position_row.position_id;
  if not found or position_aggregate_row.unique_contributors < policy_row.position_min_users then
    return jsonb_build_object('available', false, 'reason', 'INSUFFICIENT_SAMPLE');
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
      'kind', 'MOVE',
      'coordinate', chr(97 + (m.move_index % 8)) || ((m.move_index / 8) + 1)::integer::text,
      'choice_rate', m.choice_weight_sum / position_aggregate_row.unique_contributors,
      'win_rate', m.win_weight_sum / m.choice_weight_sum,
      'draw_rate', m.draw_weight_sum / m.choice_weight_sum,
      'loss_rate', m.loss_weight_sum / m.choice_weight_sum,
      'unique_contributors', m.unique_contributors,
      'can_explore', coalesce(ca.unique_contributors >= policy_row.position_min_users, false),
      'child_position_token', case when ca.unique_contributors >= policy_row.position_min_users
        then research_private.position_token(cp) else null end
    ) order by m.move_index), '[]'::jsonb)
    into moves_value
    from research_private.move_aggregates m
    left join research_private.position_aggregates ca
      on ca.generation_id = m.generation_id and ca.segment_key = m.segment_key
     and ca.position_id = m.child_position_id
    left join research_private.positions cp on cp.position_id = m.child_position_id
   where m.generation_id = generation_row.generation_id and m.segment_key = p_segment_key
     and m.position_id = position_row.position_id
     and m.unique_contributors >= policy_row.move_min_users;

  with suppressed as (
    select ma.* from research_private.move_aggregates ma
     where ma.generation_id = generation_row.generation_id and ma.segment_key = p_segment_key
       and ma.position_id = position_row.position_id
       and ma.unique_contributors < policy_row.move_min_users
  )
  select (
           select count(distinct sm.research_subject_id)::integer
             from research_private.subject_position_moves sm
             join suppressed x on x.generation_id = sm.generation_id and x.segment_key = sm.segment_key
              and x.position_id = sm.position_id and x.move_index = sm.move_index
         ),
         sum(s.choice_weight_sum), sum(s.win_weight_sum), sum(s.draw_weight_sum), sum(s.loss_weight_sum)
    into other_unique, other_choice, other_win, other_draw, other_loss
    from suppressed s;
  if other_choice is not null and other_choice > 0 then
    other_value := jsonb_build_object(
      'kind', 'OTHER',
      'choice_rate', other_choice / position_aggregate_row.unique_contributors,
      'win_rate', other_win / other_choice,
      'draw_rate', other_draw / other_choice,
      'loss_rate', other_loss / other_choice,
      'unique_contributors', case when other_unique >= policy_row.move_min_users then other_unique else null end,
      'can_explore', false,
      'child_position_token', null
    );
  end if;

  return jsonb_build_object(
    'available', true,
    'position_token', p_position_token,
    'generation_id', generation_row.generation_id,
    'segment_key', p_segment_key,
    'published_at', generation_row.published_at,
    'unique_contributors', position_aggregate_row.unique_contributors,
    'moves', moves_value,
    'other', other_value
  );
end;
$$;

revoke all on sequence
  research_private.positions_position_id_seq,
  research_private.aggregation_generations_generation_id_seq
from public, anon, authenticated;
grant usage, select on sequence
  research_private.positions_position_id_seq,
  research_private.aggregation_generations_generation_id_seq
to service_role;

create or replace function research_private.upsert_position(
  p_ruleset_version integer,
  p_normalization_version integer,
  p_black_hex text,
  p_white_hex text,
  p_side_to_move text,
  p_legal_move_mask_hex text
)
returns bigint language plpgsql security definer set search_path = '' as $$
declare result_id bigint;
declare existing_mask text;
begin
  if p_ruleset_version <= 0 or p_normalization_version <= 0
     or p_black_hex !~ '^[0-9a-f]{16}$' or p_white_hex !~ '^[0-9a-f]{16}$'
     or p_legal_move_mask_hex !~ '^[0-9a-f]{16}$'
     or p_side_to_move not in ('BLACK', 'WHITE') then
    raise exception 'invalid research position';
  end if;
  insert into research_private.positions(
    ruleset_version, normalization_version, black_hex, white_hex, side_to_move, legal_move_mask_hex
  ) values (
    p_ruleset_version, p_normalization_version, p_black_hex, p_white_hex, p_side_to_move, p_legal_move_mask_hex
  ) on conflict (ruleset_version, normalization_version, black_hex, white_hex, side_to_move) do nothing
  returning position_id into result_id;
  if result_id is null then
    select position_id, legal_move_mask_hex into result_id, existing_mask
      from research_private.positions
     where ruleset_version = p_ruleset_version and normalization_version = p_normalization_version
       and black_hex = p_black_hex and white_hex = p_white_hex and side_to_move = p_side_to_move;
    if existing_mask <> p_legal_move_mask_hex then raise exception 'research legal move mask conflict'; end if;
  end if;
  return result_id;
end;
$$;

revoke all on function research_private.upsert_position(integer, integer, text, text, text, text)
  from public, anon, authenticated;
revoke all on function research_private.position_token(research_private.positions)
  from public, anon, authenticated;

revoke all on function public.get_research_position(text, text) from public, anon;
grant execute on function public.get_research_position(text, text) to authenticated;

create or replace function public.claim_research_aggregation_build(p_lease_seconds integer default 900)
returns table (
  generation_id bigint,
  lease_token uuid,
  source_watermark bigint,
  ruleset_version integer,
  normalization_version integer
)
language plpgsql security definer set search_path = '' as $$
declare policy_row research_private.policy_versions%rowtype;
declare generation_row research_private.aggregation_generations%rowtype;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if p_lease_seconds not between 60 and 3600 then raise exception 'invalid aggregation lease'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('chanriba.research.aggregate', 0));
  select g.* into generation_row from research_private.aggregation_generations g
   where g.status = 'BUILDING' for update;
  if found and generation_row.lease_expires_at > now() then return; end if;
  if found then
    update research_private.aggregation_generations g
       set lease_token = gen_random_uuid(), lease_expires_at = now() + make_interval(secs => p_lease_seconds)
     where g.generation_id = generation_row.generation_id returning g.* into generation_row;
  else
    select p.* into policy_row from research_private.policy_versions p where p.is_active for share;
    if not found then raise exception 'active research policy required'; end if;
    insert into research_private.aggregation_generations(
      policy_version, ruleset_version, normalization_version, status, source_watermark, source_cutoff_at,
      lease_token, lease_expires_at
    ) values (
      policy_row.policy_version, policy_row.ruleset_version, policy_row.normalization_version, 'BUILDING',
      coalesce((select max(g.research_game_id) from research_private.games g
        where g.validation_status = 'ACCEPTED'
          and g.processed_at <= now()
          and g.ruleset_version = policy_row.ruleset_version and exists (
          select 1 from research_private.game_contributors c
           where c.research_game_id = g.research_game_id and c.contribution_status = 'ACCEPTED'
             and c.accepted_at <= now()
        )), 0),
      now(),
      gen_random_uuid(), now() + make_interval(secs => p_lease_seconds)
    ) returning * into generation_row;
    insert into research_private.aggregation_segments(generation_id, segment_key, segment_type, definition_version)
    values (generation_row.generation_id, 'ALL', 'ALL', 1);
  end if;
  return query select generation_row.generation_id, generation_row.lease_token,
    generation_row.source_watermark, generation_row.ruleset_version, generation_row.normalization_version;
end;
$$;

create or replace function public.get_research_aggregation_sources(
  p_generation_id bigint,
  p_lease_token uuid,
  p_after_game_id bigint default 0,
  p_limit integer default 50
)
returns table (
  research_game_id bigint,
  canonical_moves text,
  result text,
  finish_reason text,
  final_position_hash text,
  ruleset_version integer,
  contributors jsonb
)
language plpgsql security definer set search_path = '' as $$
declare generation_row research_private.aggregation_generations%rowtype;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if p_limit not between 1 and 100 or p_after_game_id < 0 then raise exception 'invalid aggregation page'; end if;
  select g.* into generation_row from research_private.aggregation_generations g
   where g.generation_id = p_generation_id for update;
  if not found or generation_row.status <> 'BUILDING' or generation_row.lease_token <> p_lease_token
     or generation_row.lease_expires_at <= now() then raise exception 'aggregation lease mismatch'; end if;
  update research_private.aggregation_generations set lease_expires_at = now() + interval '15 minutes'
   where generation_id = p_generation_id;
  return query
  select g.research_game_id, g.canonical_moves, g.result, g.finish_reason, g.final_position_hash,
         g.ruleset_version,
         jsonb_agg(jsonb_build_object(
           'research_subject_id', c.research_subject_id,
           'disc', c.disc,
           'outcome', c.outcome_from_subject_perspective,
           'rating_before', c.rating_before,
           'confirmed_at', c.confirmed_at
         ) order by c.disc) as contributors
    from research_private.games g
    join research_private.game_contributors c on c.research_game_id = g.research_game_id
     and c.contribution_status = 'ACCEPTED'
     and c.accepted_at <= generation_row.source_cutoff_at
   where g.validation_status = 'ACCEPTED'
     and g.ruleset_version = generation_row.ruleset_version
     and g.processed_at <= generation_row.source_cutoff_at
     and g.research_game_id > p_after_game_id
     and g.research_game_id <= generation_row.source_watermark
   group by g.research_game_id
   order by g.research_game_id
  limit p_limit;
end;
$$;

create or replace function public.fail_research_aggregation(
  p_generation_id bigint,
  p_lease_token uuid,
  p_failure_code text
)
returns text
language plpgsql security definer set search_path = '' as $$
declare generation_row research_private.aggregation_generations%rowtype;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if p_failure_code is null or p_failure_code !~ '^[A-Z0-9_]{1,64}$' then
    raise exception 'safe aggregation failure code required';
  end if;
  select g.* into generation_row from research_private.aggregation_generations g
   where g.generation_id = p_generation_id for update;
  if not found then raise exception 'aggregation generation not found'; end if;
  if generation_row.status = 'FAILED' and generation_row.lease_token = p_lease_token
     and generation_row.failure_code = p_failure_code then return 'FAILED'; end if;
  if generation_row.status <> 'BUILDING' or generation_row.lease_token <> p_lease_token then
    raise exception 'aggregation lease mismatch';
  end if;
  update research_private.aggregation_generations
     set status = 'FAILED', completed_at = now(), lease_expires_at = null,
         failure_code = p_failure_code
   where generation_id = p_generation_id;
  return 'FAILED';
end;
$$;

revoke all on function public.claim_research_aggregation_build(integer) from public, anon, authenticated;
revoke all on function public.get_research_aggregation_sources(bigint, uuid, bigint, integer)
  from public, anon, authenticated;
revoke all on function public.append_research_aggregation_game(bigint, uuid, bigint, jsonb)
  from public, anon, authenticated;
revoke all on function public.publish_research_aggregation(bigint, uuid)
  from public, anon, authenticated;
revoke all on function public.fail_research_aggregation(bigint, uuid, text)
  from public, anon, authenticated;
grant execute on function public.claim_research_aggregation_build(integer) to service_role;
grant execute on function public.get_research_aggregation_sources(bigint, uuid, bigint, integer) to service_role;
grant execute on function public.append_research_aggregation_game(bigint, uuid, bigint, jsonb) to service_role;
grant execute on function public.publish_research_aggregation(bigint, uuid) to service_role;
grant execute on function public.fail_research_aggregation(bigint, uuid, text) to service_role;

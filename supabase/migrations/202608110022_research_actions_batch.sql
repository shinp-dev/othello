-- Research stage 2F: a least-privilege database executor for bounded GitHub
-- Actions batches. The role is intentionally NOLOGIN in source control; the
-- production operator enables LOGIN with a generated secret outside Git.

do $$
begin
  if not exists (select 1 from pg_catalog.pg_roles where rolname = 'research_batch') then
    create role research_batch
      nologin noinherit nosuperuser nocreatedb nocreaterole noreplication;
  end if;
end;
$$;

-- Supabase's managed `postgres` role can create least-privilege roles but is
-- intentionally not a true superuser, so do not issue ALTER ... NOSUPERUSER.
-- CREATE ROLE already defaults every elevated attribute to false.
alter role research_batch nologin noinherit;
alter role research_batch set statement_timeout = '30min';
alter role research_batch set lock_timeout = '5s';
alter role research_batch set idle_in_transaction_session_timeout = '60s';

grant connect on database postgres to research_batch;
grant research_batch to postgres;
grant usage on schema research_private to research_batch;
revoke all on all tables in schema public, research_private from research_batch;
revoke all on table auth.users, storage.objects from research_batch;
revoke all on all sequences in schema public, research_private from research_batch;

-- These legacy helper/trigger functions still inherited PostgreSQL's default
-- PUBLIC EXECUTE. They are not client APIs; remove that ambient surface so the
-- batch login's application-schema capability is exactly the wrappers below.
revoke execute on function public.initial_rating() from public;
revoke execute on function public.handle_new_user() from public;
revoke execute on function public.release_active_match_reservations() from public;
revoke execute on function public.set_match_retention() from public;
revoke execute on function public.set_pending_result_short_lease() from public;

create or replace function research_private.assert_batch_executor()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if session_user <> 'research_batch'
     and pg_catalog.current_setting('role', true) <> 'research_batch' then
    raise exception 'research batch role required';
  end if;
end;
$$;

create or replace function research_private.batch_claim_validation(
  p_limit integer default 25,
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
  perform research_private.assert_batch_executor();
  if not coalesce((
    select p.collection_enabled
      from research_private.policy_versions p
     where p.is_active
  ), false) then
    return;
  end if;
  perform pg_catalog.set_config('request.jwt.claim.role', 'service_role', true);
  return query
    select * from public.claim_research_validation_batch(p_limit, p_lease_seconds);
end;
$$;

create or replace function research_private.batch_complete_validation(
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
begin
  perform research_private.assert_batch_executor();
  perform pg_catalog.set_config('request.jwt.claim.role', 'service_role', true);
  return public.complete_research_validation(
    p_research_game_id, p_lease_token, p_validator_version, p_accepted,
    p_rejection_code, p_black_decision_count, p_white_decision_count
  );
end;
$$;

create or replace function research_private.batch_claim_aggregation(
  p_lease_seconds integer default 1800
)
returns table (
  generation_id bigint,
  lease_token uuid,
  source_watermark bigint,
  ruleset_version integer,
  normalization_version integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  policy_row research_private.policy_versions%rowtype;
  watermark_value bigint;
begin
  perform research_private.assert_batch_executor();
  if p_lease_seconds not between 60 and 3600 then
    raise exception 'invalid aggregation lease';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('chanriba.research.aggregate', 0)
  );
  select p.* into policy_row
    from research_private.policy_versions p
   where p.is_active
   for share;
  if not found or not policy_row.collection_enabled then
    return;
  end if;

  if not exists (
    select 1 from research_private.aggregation_generations g
     where g.status = 'BUILDING'
  ) then
    select coalesce(max(g.research_game_id), 0)
      into watermark_value
      from research_private.games g
     where g.validation_status = 'ACCEPTED'
       and g.processed_at <= now()
       and g.ruleset_version = policy_row.ruleset_version
       and exists (
         select 1 from research_private.game_contributors c
          where c.research_game_id = g.research_game_id
            and c.contribution_status = 'ACCEPTED'
            and c.accepted_at <= now()
       );
    if watermark_value = 0 or exists (
      select 1
        from research_private.published_generation pg
        join research_private.aggregation_generations g
          on g.generation_id = pg.generation_id
       where pg.singleton
         and g.status = 'PUBLISHED'
         and g.policy_version = policy_row.policy_version
         and g.ruleset_version = policy_row.ruleset_version
         and g.normalization_version = policy_row.normalization_version
         and g.source_watermark >= watermark_value
    ) then
      return;
    end if;
  end if;

  perform pg_catalog.set_config('request.jwt.claim.role', 'service_role', true);
  return query select * from public.claim_research_aggregation_build(p_lease_seconds);
end;
$$;

create or replace function research_private.batch_get_aggregation_sources(
  p_generation_id bigint,
  p_lease_token uuid,
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
language plpgsql
security definer
set search_path = ''
as $$
declare generation_row research_private.aggregation_generations%rowtype;
begin
  perform research_private.assert_batch_executor();
  if p_limit not between 1 and 100 then raise exception 'invalid aggregation page'; end if;
  select g.* into generation_row
    from research_private.aggregation_generations g
   where g.generation_id = p_generation_id
   for update;
  if not found or generation_row.status <> 'BUILDING'
     or generation_row.lease_token <> p_lease_token
     or generation_row.lease_expires_at <= now() then
    raise exception 'aggregation lease mismatch';
  end if;
  update research_private.aggregation_generations
     set lease_expires_at = greatest(
       lease_expires_at,
       now() + interval '15 minutes'
     )
   where generation_id = p_generation_id;

  return query
  select g.research_game_id, g.canonical_moves, g.result, g.finish_reason,
         g.final_position_hash, g.ruleset_version,
         jsonb_agg(jsonb_build_object(
           'research_subject_id', c.research_subject_id,
           'disc', c.disc,
           'outcome', c.outcome_from_subject_perspective,
           'rating_before', c.rating_before,
           'confirmed_at', c.confirmed_at
         ) order by c.disc) as contributors
    from research_private.games g
    join research_private.game_contributors c
      on c.research_game_id = g.research_game_id
     and c.contribution_status = 'ACCEPTED'
     and c.accepted_at <= generation_row.source_cutoff_at
   where g.validation_status = 'ACCEPTED'
     and g.ruleset_version = generation_row.ruleset_version
     and g.processed_at <= generation_row.source_cutoff_at
     and g.research_game_id <= generation_row.source_watermark
     and not exists (
       select 1 from research_private.generation_processed_games done
        where done.generation_id = p_generation_id
          and done.research_game_id = g.research_game_id
     )
   group by g.research_game_id
   order by g.research_game_id
   limit p_limit;
end;
$$;

create or replace function research_private.batch_append_aggregation_game(
  p_generation_id bigint,
  p_lease_token uuid,
  p_research_game_id bigint,
  p_decisions jsonb
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform research_private.assert_batch_executor();
  perform pg_catalog.set_config('request.jwt.claim.role', 'service_role', true);
  return public.append_research_aggregation_game(
    p_generation_id, p_lease_token, p_research_game_id, p_decisions
  );
end;
$$;

create or replace function research_private.batch_checkpoint_aggregation(
  p_generation_id bigint,
  p_lease_token uuid
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare generation_row research_private.aggregation_generations%rowtype;
begin
  perform research_private.assert_batch_executor();
  select g.* into generation_row
    from research_private.aggregation_generations g
   where g.generation_id = p_generation_id
   for update;
  if not found then raise exception 'aggregation generation not found'; end if;
  if generation_row.status <> 'BUILDING' then
    if generation_row.lease_token = p_lease_token then return generation_row.status; end if;
    raise exception 'aggregation lease mismatch';
  end if;
  if generation_row.lease_token <> p_lease_token then
    raise exception 'aggregation lease mismatch';
  end if;
  update research_private.aggregation_generations
     set lease_expires_at = now()
   where generation_id = p_generation_id;
  return 'CHECKPOINTED';
end;
$$;

create or replace function research_private.batch_publish_aggregation(
  p_generation_id bigint,
  p_lease_token uuid
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform research_private.assert_batch_executor();
  perform pg_catalog.set_config('request.jwt.claim.role', 'service_role', true);
  return public.publish_research_aggregation(p_generation_id, p_lease_token);
end;
$$;

create or replace function research_private.batch_fail_aggregation(
  p_generation_id bigint,
  p_lease_token uuid,
  p_failure_code text
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform research_private.assert_batch_executor();
  perform pg_catalog.set_config('request.jwt.claim.role', 'service_role', true);
  return public.fail_research_aggregation(p_generation_id, p_lease_token, p_failure_code);
end;
$$;

revoke all on function research_private.assert_batch_executor()
  from public, anon, authenticated, service_role, research_batch;
revoke all on function research_private.batch_claim_validation(integer, integer)
  from public, anon, authenticated, service_role, research_batch;
revoke all on function research_private.batch_complete_validation(bigint, uuid, integer, boolean, text, integer, integer)
  from public, anon, authenticated, service_role, research_batch;
revoke all on function research_private.batch_claim_aggregation(integer)
  from public, anon, authenticated, service_role, research_batch;
revoke all on function research_private.batch_get_aggregation_sources(bigint, uuid, integer)
  from public, anon, authenticated, service_role, research_batch;
revoke all on function research_private.batch_append_aggregation_game(bigint, uuid, bigint, jsonb)
  from public, anon, authenticated, service_role, research_batch;
revoke all on function research_private.batch_checkpoint_aggregation(bigint, uuid)
  from public, anon, authenticated, service_role, research_batch;
revoke all on function research_private.batch_publish_aggregation(bigint, uuid)
  from public, anon, authenticated, service_role, research_batch;
revoke all on function research_private.batch_fail_aggregation(bigint, uuid, text)
  from public, anon, authenticated, service_role, research_batch;

grant execute on function research_private.batch_claim_validation(integer, integer)
  to research_batch;
grant execute on function research_private.batch_complete_validation(bigint, uuid, integer, boolean, text, integer, integer)
  to research_batch;
grant execute on function research_private.batch_claim_aggregation(integer)
  to research_batch;
grant execute on function research_private.batch_get_aggregation_sources(bigint, uuid, integer)
  to research_batch;
grant execute on function research_private.batch_append_aggregation_game(bigint, uuid, bigint, jsonb)
  to research_batch;
grant execute on function research_private.batch_checkpoint_aggregation(bigint, uuid)
  to research_batch;
grant execute on function research_private.batch_publish_aggregation(bigint, uuid)
  to research_batch;
grant execute on function research_private.batch_fail_aggregation(bigint, uuid, text)
  to research_batch;

revoke all on function public.claim_research_validation_batch(integer, integer) from research_batch;
revoke all on function public.complete_research_validation(bigint, uuid, integer, boolean, text, integer, integer)
  from research_batch;
revoke all on function public.claim_research_aggregation_build(integer) from research_batch;
revoke all on function public.get_research_aggregation_sources(bigint, uuid, bigint, integer)
  from research_batch;
revoke all on function public.append_research_aggregation_game(bigint, uuid, bigint, jsonb)
  from research_batch;
revoke all on function public.publish_research_aggregation(bigint, uuid) from research_batch;
revoke all on function public.fail_research_aggregation(bigint, uuid, text) from research_batch;

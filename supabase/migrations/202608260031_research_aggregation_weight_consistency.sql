-- Keep the exact move aggregate weight invariant when subject normalization
-- produces repeating decimals. Each outcome component is rounded by PostgreSQL
-- once, then choice_weight_sum is derived from those same stored components.

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

  with weighted_moves as (
    select m.generation_id, m.segment_key, m.position_id, m.move_index,
           count(*)::integer as unique_contributors,
           sum(m.win_count::numeric / t.occurrence_count) as win_weight_sum,
           sum(m.draw_count::numeric / t.occurrence_count) as draw_weight_sum,
           sum(m.loss_count::numeric / t.occurrence_count) as loss_weight_sum,
           min(m.child_position_id) as child_position_id
      from research_private.subject_position_moves m
      join research_private.subject_position_totals t
        on t.generation_id = m.generation_id and t.segment_key = m.segment_key
       and t.position_id = m.position_id and t.research_subject_id = m.research_subject_id
     where m.generation_id = p_generation_id
     group by m.generation_id, m.segment_key, m.position_id, m.move_index
  )
  insert into research_private.move_aggregates(
    generation_id, segment_key, position_id, move_index, unique_contributors,
    choice_weight_sum, win_weight_sum, draw_weight_sum, loss_weight_sum, child_position_id
  )
  select generation_id, segment_key, position_id, move_index, unique_contributors,
         win_weight_sum + draw_weight_sum + loss_weight_sum,
         win_weight_sum, draw_weight_sum, loss_weight_sum, child_position_id
    from weighted_moves;

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

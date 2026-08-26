-- Regression coverage for repeating-decimal subject normalization during publish.
begin;
select plan(30);

select set_config('request.jwt.claim.role', 'service_role', false);

create temporary table rounding_policy on commit drop as
select policy_version, ruleset_version, normalization_version
  from research_private.policy_versions
 where is_active;

select is((select count(*)::integer from rounding_policy), 1,
  'the fixture uses the single active Research policy');

create temporary table rounding_subjects(
  subject_number integer primary key,
  research_subject_id uuid not null unique,
  participation_id uuid not null unique
) on commit drop;

insert into rounding_subjects values
  (1, '31000000-0000-0000-0000-000000000001', '32000000-0000-0000-0000-000000000001'),
  (2, '31000000-0000-0000-0000-000000000002', '32000000-0000-0000-0000-000000000002'),
  (3, '31000000-0000-0000-0000-000000000003', '32000000-0000-0000-0000-000000000003'),
  (4, '31000000-0000-0000-0000-000000000004', '32000000-0000-0000-0000-000000000004');

insert into research_private.research_subjects(
  research_subject_id, account_user_id, link_state, unlinked_at
)
select research_subject_id, null, 'UNLINKED', now()
  from rounding_subjects;

insert into research_private.participation_periods(
  participation_id, research_subject_id, started_at, ended_at,
  policy_version_at_start, consent_version
)
select s.participation_id, s.research_subject_id,
       now() - interval '10 days', now() - interval '1 day',
       p.policy_version, 1
  from rounding_subjects s cross join rounding_policy p;

create temporary table rounding_games(
  game_number integer primary key,
  research_game_id bigint not null unique
) on commit drop;

with inserted as (
  insert into research_private.games(
    source_match_key, canonical_moves, result, finish_reason,
    final_position_hash, time_control, confirmed_at, ruleset_version,
    validation_status, validator_version, attempt_count, lease_token,
    lease_expires_at, last_attempt_at, processed_at, rejection_code
  )
  select decode(repeat(to_hex(48 + n), 32), 'hex'), '',
         case n when 1 then 'BLACK_WIN' when 2 then 'DRAW' else 'WHITE_WIN' end,
         'RESIGNATION', '0000000000000000:1:0:0', 'regression',
         now() - interval '2 days', 1, 'ACCEPTED', 1, 1,
         ('34000000-0000-0000-0000-00000000000' || n)::uuid,
         null, now() - interval '1 hour', now() - interval '1 hour', null
    from generate_series(1, 3) n
  returning research_game_id, source_match_key
)
insert into rounding_games(game_number, research_game_id)
select row_number() over (order by source_match_key)::integer, research_game_id
  from inserted;

insert into research_private.game_contributors(
  research_game_id, research_subject_id, participation_id, disc,
  rating_before, rating_algorithm_version, outcome_from_subject_perspective,
  confirmed_at, contribution_status, decision_count, accepted_at
)
select g.research_game_id, s.research_subject_id, s.participation_id,
       case side.side_number when 1 then 'BLACK' else 'WHITE' end,
       1500, 'regression-v1',
       case
         when g.game_number = 1 and side.side_number = 1 then 'WIN'
         when g.game_number = 1 and side.side_number = 2 then 'LOSS'
         when g.game_number = 2 then 'DRAW'
         when g.game_number = 3 and side.side_number = 1 then 'LOSS'
         else 'WIN'
       end,
       now() - interval '2 days', 'ACCEPTED', 3, now() - interval '1 hour'
  from rounding_games g
  cross join (values (1), (2)) side(side_number)
  join rounding_subjects s
    on s.subject_number = case
      when g.game_number = 1 then side.side_number
      when g.game_number = 2 then side.side_number + 1
      when side.side_number = 1 then 3
      else 4
    end;

select is((select count(*)::integer from rounding_games), 3,
  'the regression fixture contains multiple accepted games');
select is((select count(*)::integer from research_private.game_contributors c
  join rounding_games g using (research_game_id)), 6,
  'the regression fixture contains multiple accepted contributors');

create temporary table rounding_generations(
  generation_kind text primary key,
  generation_id bigint not null unique,
  lease_token uuid not null
) on commit drop;

with inserted as (
  insert into research_private.aggregation_generations(
    policy_version, ruleset_version, normalization_version, status,
    source_watermark, source_cutoff_at, lease_token, lease_expires_at,
    completed_at, published_at
  )
  select p.policy_version, p.ruleset_version, p.normalization_version, 'PUBLISHED',
         0, now() - interval '3 hours',
         '35000000-0000-0000-0000-000000000001'::uuid, null,
         now() - interval '3 hours', now() - interval '3 hours'
    from rounding_policy p
  returning generation_id, lease_token
)
insert into rounding_generations
select 'old', generation_id, lease_token from inserted;

insert into research_private.published_generation(singleton, generation_id)
select true, generation_id from rounding_generations where generation_kind = 'old';

with inserted as (
  insert into research_private.aggregation_generations(
    policy_version, ruleset_version, normalization_version, status,
    source_watermark, source_cutoff_at, lease_token, lease_expires_at
  )
  select p.policy_version, p.ruleset_version, p.normalization_version, 'BUILDING',
         (select max(research_game_id) from rounding_games), now(),
         '35000000-0000-0000-0000-000000000002'::uuid,
         now() + interval '30 minutes'
    from rounding_policy p
  returning generation_id, lease_token
)
insert into rounding_generations
select 'new', generation_id, lease_token from inserted;

insert into research_private.aggregation_segments(
  generation_id, segment_key, segment_type, definition_version
)
select generation_id, 'ALL', 'ALL', 1
  from rounding_generations where generation_kind = 'new';

insert into research_private.generation_processed_games(generation_id, research_game_id)
select generation_id, research_game_id
  from rounding_generations cross join rounding_games
 where generation_kind = 'new';

create temporary table rounding_positions(
  position_kind text primary key,
  position_id bigint not null unique
) on commit drop;

insert into rounding_positions values
  ('parent', research_private.upsert_position(
    1, 1, '0000000810000000', '0000001008000000', 'BLACK', '0000102004080000'
  )),
  ('child', research_private.upsert_position(
    1, 1, '0000000818080000', '0000001000000000', 'WHITE', '0000000000000001'
  ));

insert into research_private.subject_position_totals(
  generation_id, segment_key, position_id, research_subject_id, occurrence_count
)
select g.generation_id, 'ALL', p.position_id, s.research_subject_id, 3
  from rounding_generations g
  cross join rounding_positions p
  cross join rounding_subjects s
 where g.generation_kind = 'new' and p.position_kind = 'parent';

insert into research_private.subject_position_moves(
  generation_id, segment_key, position_id, research_subject_id, move_index,
  choice_count, win_count, draw_count, loss_count, child_position_id
)
select g.generation_id, 'ALL', parent.position_id, s.research_subject_id, 19,
       3,
       case s.subject_number when 1 then 3 when 4 then 1 else 0 end,
       case s.subject_number when 2 then 3 when 4 then 1 else 0 end,
       case s.subject_number when 3 then 3 when 4 then 1 else 0 end,
       child.position_id
  from rounding_generations g
  cross join rounding_subjects s
  cross join rounding_positions parent
  cross join rounding_positions child
 where g.generation_kind = 'new'
   and parent.position_kind = 'parent'
   and child.position_kind = 'child';

select is((select count(*)::integer from research_private.subject_position_moves m
  join rounding_generations g using (generation_id)
  where g.generation_kind = 'new'), 4,
  'four contributors are staged for one normalized move');
select is((select count(*)::integer from research_private.subject_position_moves m
  join rounding_subjects s using (research_subject_id)
  where s.subject_number = 1 and m.win_count = 3 and m.draw_count = 0 and m.loss_count = 0), 1,
  'the fixture includes a WIN-only contribution');
select is((select count(*)::integer from research_private.subject_position_moves m
  join rounding_subjects s using (research_subject_id)
  where s.subject_number = 2 and m.win_count = 0 and m.draw_count = 3 and m.loss_count = 0), 1,
  'the fixture includes a DRAW-only contribution');
select is((select count(*)::integer from research_private.subject_position_moves m
  join rounding_subjects s using (research_subject_id)
  where s.subject_number = 3 and m.win_count = 0 and m.draw_count = 0 and m.loss_count = 3), 1,
  'the fixture includes a LOSS-only contribution');
select is((select count(*)::integer from research_private.subject_position_moves m
  join rounding_subjects s using (research_subject_id)
  where s.subject_number = 4 and m.win_count = 1 and m.draw_count = 1 and m.loss_count = 1), 1,
  'the fixture includes a mixed WIN/DRAW/LOSS contribution');

select isnt(
  (select sum(m.choice_count::numeric / t.occurrence_count)
     from research_private.subject_position_moves m
     join research_private.subject_position_totals t
       using (generation_id, segment_key, position_id, research_subject_id)),
  (select sum(m.win_count::numeric / t.occurrence_count)
        + sum(m.draw_count::numeric / t.occurrence_count)
        + sum(m.loss_count::numeric / t.occurrence_count)
     from research_private.subject_position_moves m
     join research_private.subject_position_totals t
       using (generation_id, segment_key, position_id, research_subject_id)),
  'the fixture reproduces the independent repeating-decimal rounding mismatch');

select is((select generation_id from research_private.published_generation),
  (select generation_id from rounding_generations where generation_kind = 'old'),
  'the old PUBLISHED pointer remains active before retrying the failed BUILDING generation');
select is((select status from research_private.aggregation_generations a
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'), 'BUILDING',
  'the failed publish is recoverable from its BUILDING generation');
select is((select count(*)::integer from research_private.move_aggregates m
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'), 0,
  'the BUILDING generation has no partially published move aggregates');

grant select on rounding_generations to research_batch;
set role research_batch;
create temporary table rounding_publish_result on commit drop as
select research_private.batch_publish_aggregation(generation_id, lease_token) as result
  from rounding_generations where generation_kind = 'new';
reset role;

select is((select result from rounding_publish_result), 'PUBLISHED',
  'the production batch wrapper publishes the recovered generation');
select is((select status from research_private.aggregation_generations a
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'), 'PUBLISHED',
  'the recovered generation becomes PUBLISHED');
select is((select status from research_private.aggregation_generations a
  join rounding_generations g using (generation_id) where g.generation_kind = 'old'), 'READY',
  'the previous PUBLISHED generation becomes READY');
select is((select generation_id from research_private.published_generation),
  (select generation_id from rounding_generations where generation_kind = 'new'),
  'the public pointer switches atomically to the recovered generation');
select is((select count(*)::integer from research_private.move_aggregates m
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'), 1,
  'publish creates exactly one move aggregate row');
select ok(not exists (
  select 1 from research_private.move_aggregates m
  join rounding_generations g using (generation_id)
  where g.generation_kind = 'new'
    and m.win_weight_sum + m.draw_weight_sum + m.loss_weight_sum <> m.choice_weight_sum
), 'every published move aggregate satisfies the exact weight invariant');
select is((select unique_contributors from research_private.move_aggregates m
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'), 4,
  'publish preserves the unique contributor count');
select is((select child_position_id from research_private.move_aggregates m
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'),
  (select position_id from rounding_positions where position_kind = 'child'),
  'publish preserves the child position');
select is((select win_weight_sum from research_private.move_aggregates m
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'),
  1::numeric + 1::numeric / 3::numeric,
  'WIN weight keeps the rounded WIN-only plus one-third mixed contribution');
select is((select draw_weight_sum from research_private.move_aggregates m
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'),
  1::numeric + 1::numeric / 3::numeric,
  'DRAW weight keeps the rounded DRAW-only plus one-third mixed contribution');
select is((select loss_weight_sum from research_private.move_aggregates m
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'),
  1::numeric + 1::numeric / 3::numeric,
  'LOSS weight keeps the rounded LOSS-only plus one-third mixed contribution');
select is((select choice_weight_sum from research_private.move_aggregates m
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'),
  (select win_weight_sum + draw_weight_sum + loss_weight_sum
     from research_private.move_aggregates m
     join rounding_generations g using (generation_id) where g.generation_kind = 'new'),
  'choice weight is derived from the same rounded outcome components');
select is((select unique_contributors from research_private.position_aggregates p
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'), 4,
  'publish preserves the position contributor count');
select is((select count(*)::integer from research_private.generation_processed_games x
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'), 3,
  'all multiple-game processing checkpoints remain attached to the generation');
select is((select count(*)::integer from research_private.games x
  join rounding_games g using (research_game_id)), 3,
  'publishing does not alter the accepted Research source games');

set role research_batch;
create temporary table rounding_publish_retry on commit drop as
select research_private.batch_publish_aggregation(generation_id, lease_token) as result
  from rounding_generations where generation_kind = 'new';
reset role;

select is((select result from rounding_publish_retry), 'PUBLISHED',
  'publishing the same generation and lease is idempotent');
select is((select status from research_private.aggregation_generations a
  join rounding_generations g using (generation_id) where g.generation_kind = 'new'), 'PUBLISHED',
  'an idempotent retry leaves the new generation PUBLISHED');
select ok(exists (
  select 1 from pg_constraint
   where conrelid = 'research_private.move_aggregates'::regclass
     and conname = 'move_aggregates_check'
     and contype = 'c'
     and convalidated
     and pg_get_constraintdef(oid)
       = 'CHECK ((((win_weight_sum + draw_weight_sum) + loss_weight_sum) = choice_weight_sum))'
), 'the validated strict equality constraint remains in place');

select * from finish();
rollback;

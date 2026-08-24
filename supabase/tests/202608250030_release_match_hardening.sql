-- Release-match protocol 2: contracts, authority, lifecycle, and abuse boundaries.
begin;
select no_plan();

-- Schema and RPC contract ----------------------------------------------------

select is(
  array(select value::text
    from unnest(enum_range(null::public.release_match_status)) values_list(value)),
  array[
    'MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING', 'CONFIRMED',
    'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED'
  ]::text[],
  'release lifecycle enum has the complete ordered state set'
);
select is((
  select count(*)::integer
    from information_schema.columns
   where table_schema = 'public' and table_name = 'matches'
     and column_name in (
       'protocol_version', 'release_status', 'release_deadline',
       'reconnect_deadline', 'release_started_at', 'release_terminal_at',
       'release_updated_at', 'release_terminal_reason', 'negotiation_epoch',
       'black_queue_request_id', 'white_queue_request_id',
       'black_disconnect_claimed_at', 'white_disconnect_claimed_at'
     )
), 13, 'matches carries the additive protocol-2 lifecycle contract');
select is((
  select count(*)::integer
    from information_schema.columns
   where table_schema = 'public' and table_name = 'match_queue'
     and column_name in ('protocol_version', 'request_id')
), 2, 'queue carries protocol and idempotency request identity');
select ok(exists (
  select 1 from pg_constraint
   where conrelid = 'public.matches'::regclass
     and conname = 'matches_players_distinct'
), 'a release match cannot assign both discs to one account');
select is((
  select count(*)::integer
    from pg_constraint
   where conname in (
     'matches_negotiation_epoch_budget',
     'match_start_acks_v2_negotiation_epoch_budget',
     'match_result_claims_v2_negotiation_epoch_budget',
     'match_signals_v2_negotiation_epoch_budget'
   )
     and position('negotiation_epoch <= 3' in pg_get_constraintdef(oid)) > 0
), 4, 'match, ACK, claim, and signal rows share the finite epoch-3 budget');
select is((
  select is_nullable
    from information_schema.columns
   where table_schema = 'public' and table_name = 'game_records'
     and column_name = 'canonical_moves'
), 'YES', 'legacy game-record canonical moves remain nullable during coexistence');
select is((
  select is_nullable
    from information_schema.columns
   where table_schema = 'public' and table_name = 'match_result_claims_v2'
     and column_name = 'canonical_moves'
), 'NO', 'protocol-2 result claims require canonical moves');
select ok(to_regclass('public.match_results_v2') is not null,
  'one authoritative protocol-2 result table exists');
select ok(to_regclass('public.match_signals_v2') is not null,
  'separate protocol-2 signaling table exists');
select ok(exists (
  select 1 from pg_constraint
   where conrelid = 'public.match_results_v2'::regclass and contype = 'p'
), 'authoritative results have one primary-key fact per match');

select ok(to_regprocedure('public.enqueue_or_match_v2(uuid)') is not null,
  'enqueue_or_match_v2 exists');
select ok(to_regprocedure('public.claim_active_match_v2()') is not null,
  'claim_active_match_v2 exists');
select ok(to_regprocedure('public.cancel_waiting_v2(uuid)') is not null,
  'cancel_waiting_v2 exists');
select ok(to_regprocedure('public.ack_match_started_v2(uuid)') is not null,
  'ack_match_started_v2 exists');
select ok(to_regprocedure('public.get_release_match_state_v2(uuid)') is not null,
  'get_release_match_state_v2 exists');
select ok(to_regprocedure('public.abandon_match_v2(uuid)') is not null,
  'abandon_match_v2 exists');
select ok(to_regprocedure('public.resume_match_v2(uuid)') is not null,
  'resume_match_v2 exists');
select ok(to_regprocedure('public.reconcile_match_v2(uuid)') is not null,
  'reconcile_match_v2 exists');
select ok(to_regprocedure('public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)') is not null,
  'disc-based result RPC exists');
select ok(to_regprocedure('public.publish_match_signal_v2(uuid,text,text,integer,integer)') is not null,
  'bounded signaling publish RPC exists');
select ok(position('p_negotiation_epoch integer' in
  pg_get_function_arguments(
    'public.publish_match_signal_v2(uuid,text,text,integer,integer)'::regprocedure)) > 0,
  'signaling publish requires the client expected negotiation epoch');
select ok(to_regprocedure('public.run_match_maintenance_v2(integer)') is not null,
  'bounded maintenance RPC exists');
select ok(to_regprocedure('public.run_legacy_match_maintenance_v1(integer)') is not null,
  'bounded protocol-1 coexistence maintenance RPC exists');
select ok(position('negotiation_epoch integer' in
  pg_get_function_result('public.enqueue_or_match_v2(uuid)'::regprocedure)) > 0,
  'enqueue assignment includes negotiation epoch');
select ok(position('negotiation_epoch integer' in
  pg_get_function_result('public.claim_active_match_v2()'::regprocedure)) > 0,
  'claim assignment includes negotiation epoch');
select ok(position('negotiation_epoch integer' in
  pg_get_function_result('public.cancel_waiting_v2(uuid)'::regprocedure)) > 0,
  'cancel race assignment includes negotiation epoch');
select ok(position('p_loser_disc text' in
  pg_get_function_arguments('public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)'::regprocedure)) > 0,
  'result RPC accepts a disc, never a client-selected loser UUID');
select ok(position('p_result' in
  pg_get_function_arguments('public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)'::regprocedure)) = 0
  and position('final_position_hash' in
  pg_get_function_arguments('public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)'::regprocedure)) = 0,
  'result and final hash are server-derived rather than RPC inputs');
select ok(position('for update skip locked' in lower(
  pg_get_functiondef('public.enqueue_or_match_v2(uuid)'::regprocedure))) > 0,
  'protocol-2 matchmaking claims candidates with SKIP LOCKED');
select ok(position('othello.enqueue_or_match' in
  pg_get_functiondef('public.enqueue_or_match_v2(uuid)'::regprocedure)) = 0,
  'protocol-2 matchmaking has no pool-wide advisory lock');
select ok(position('q.protocol_version = 2' in
  pg_get_functiondef('public.enqueue_or_match_v2(uuid)'::regprocedure)) > 0,
  'protocol-2 matchmaking filters its queue pool');
select ok(to_regprocedure('public.enqueue_or_match()') is not null
  and to_regprocedure('public.claim_waiting_match()') is not null
  and to_regprocedure('public.submit_match_result(uuid,text,text,text,text,jsonb)') is not null,
  'protocol-1 RPC signatures remain available for closed-test clients');

-- Definer path and ACL boundaries -------------------------------------------

select ok(not exists (
  select 1 from pg_proc p
   where p.pronamespace = 'public'::regnamespace
     and p.proname in (
       'enforce_release_match_transition_v2', 'release_is_legal_move_v2',
       'enforce_match_signaling_v1_budget',
       'release_has_legal_move_v2', 'release_apply_move_v2',
       'release_replay_game_v2', 'release_assignment_row_v2',
       'release_match_state_row_v2', 'release_result_response_row_v2',
       'release_expire_match_v2', 'release_finalize_result_v2',
       'release_reconcile_match_internal_v2', 'enqueue_or_match_v2',
       'claim_active_match_v2', 'cancel_waiting_v2',
       'get_release_match_state_v2', 'ack_match_started_v2',
       'abandon_match_v2', 'resume_match_v2', 'reconcile_match_v2',
       'submit_match_result_v2', 'publish_match_signal_v2',
       'run_match_maintenance_v2', 'run_legacy_match_maintenance_v1'
     )
     and (not p.prosecdef or not coalesce(p.proconfig, '{}') @> array['search_path=""'])
), 'every release helper and RPC is SECURITY DEFINER with an empty search path');
select ok(has_function_privilege('authenticated',
  'public.enqueue_or_match_v2(uuid)', 'execute')
  and has_function_privilege('authenticated',
  'public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)', 'execute')
  and has_function_privilege('authenticated',
  'public.publish_match_signal_v2(uuid,text,text,integer,integer)', 'execute'),
  'authenticated clients can execute only the intended release RPC surface');
select ok(not has_function_privilege('anon',
  'public.enqueue_or_match_v2(uuid)', 'execute')
  and not has_function_privilege('anon',
  'public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)', 'execute')
  and not has_function_privilege('anon',
  'public.publish_match_signal_v2(uuid,text,text,integer,integer)', 'execute'),
  'anonymous callers cannot invoke release RPCs');
select ok(not has_function_privilege('authenticated',
  'public.release_replay_game_v2(text)', 'execute')
  and not has_function_privilege('authenticated',
  'public.release_finalize_result_v2(uuid,public.release_match_status,text,text,text,text,text,integer,integer,text)',
  'execute'), 'authenticated clients cannot invoke authority helpers');
select ok(has_function_privilege('service_role',
  'public.run_match_maintenance_v2(integer)', 'execute')
  and has_function_privilege('service_role',
  'public.run_legacy_match_maintenance_v1(integer)', 'execute')
  and not has_function_privilege('authenticated',
  'public.run_match_maintenance_v2(integer)', 'execute')
  and not has_function_privilege('authenticated',
  'public.run_legacy_match_maintenance_v1(integer)', 'execute'),
  'only service_role can execute release and coexistence maintenance');
select ok(not has_function_privilege('authenticated',
  'public.finalize_match_v2(uuid)', 'execute')
  and not has_function_privilege('authenticated',
  'public.enforce_match_signaling_v1_budget()', 'execute'),
  'legacy authority and direct-INSERT trigger helpers are not client-callable');
select ok(not has_table_privilege('authenticated',
  'public.match_signals_v2', 'insert')
  and not has_table_privilege('authenticated',
  'public.match_signals_v2', 'update')
  and not has_table_privilege('authenticated',
  'public.match_signals_v2', 'delete'),
  'signaling direct writes are revoked');
select ok(has_table_privilege('authenticated',
  'public.match_signals_v2', 'select')
  and has_table_privilege('authenticated',
  'public.match_results_v2', 'select'),
  'participants have RLS-scoped reads for signaling and final results');
select ok(not has_table_privilege('authenticated',
  'public.match_result_claims_v2', 'select')
  and not has_table_privilege('authenticated',
  'public.match_result_claims_v2', 'insert'),
  'raw result evidence is RPC-only');
select ok((select relrowsecurity from pg_class
  where oid = 'public.match_signals_v2'::regclass),
  'release signaling has RLS enabled');
select ok(exists (
  select 1 from pg_publication_tables
   where pubname = 'supabase_realtime'
     and schemaname = 'public' and tablename = 'match_signals_v2'
), 'release signaling is registered for Realtime');

-- Deterministic replay -------------------------------------------------------

create temporary table release_fixture(
  canonical_moves text not null,
  final_position_hash text not null
) on commit drop;
insert into release_fixture values (
  'd3c3b3b2b1a1c4c1c2d2d1e1a2a3f5e2f1g1--f2--e3--b5b4a5a4c5a6f4f3g3g2h2h1h3h4g4c6g5h5b6c7d6e6f6g6h6h7a7--b7a8d7e7f7g7g8b8c8d8e8f8h8',
  '712ca7384132c0b4:1:0:64'
);
select ok((select accepted from public.release_replay_game_v2(
  (select canonical_moves from release_fixture))),
  'known legal fixture replays successfully');
select is((select final_position_hash from public.release_replay_game_v2(
  (select canonical_moves from release_fixture))),
  (select final_position_hash from release_fixture),
  'server replay derives the fixed FNV position hash');
select is((select final_result from public.release_replay_game_v2(
  (select canonical_moves from release_fixture))),
  'WHITE_WIN', 'server replay derives WHITE_WIN');
select ok((select terminal and ply = 64 and black_count + white_count = 64
  from public.release_replay_game_v2((select canonical_moves from release_fixture))),
  'fixture is server-valid terminal play including legal passes');
select is((select rejection_code from public.release_replay_game_v2('--')),
  'UNNECESSARY_PASS', 'an unnecessary opening pass is rejected');
select is((select rejection_code from public.release_replay_game_v2('a1')),
  'ILLEGAL_MOVE', 'an illegal opening move is rejected');
select ok((select accepted and not terminal
  from public.release_replay_game_v2('d3c3')),
  'a legal truncated transcript is parsed but remains nonterminal');
select is((select rejection_code from public.release_replay_game_v2(
  (select canonical_moves || 'a1' from release_fixture))),
  'MOVE_AFTER_TERMINAL', 'moves after a terminal position are rejected');
select is((select rejection_code from public.release_replay_game_v2(
  (select regexp_replace(canonical_moves, '--', '') from release_fixture))),
  'MISSING_PASS', 'omitting a forced pass is rejected');
select ok((select accepted and terminal and final_result = 'DRAW'
    and black_count = 32 and white_count = 32
  from public.release_replay_game_v2(
    'e6d6c7f3d3c6c5c2f4d7e8b6b5g4a7a6e3c8b8d2a5a4d1b7b2a8g3h2f5e7h4e2h3b1f6g6h1b4f2g2c3b3e1g5g7f1h5g8h7h6f7c1a3f8h8a2g1c4d8--a1'
  )), 'server replay derives a 32-32 draw without client winner or stone counts');
select ok((select accepted and terminal and consecutive_passes = 2
    and black_count = 15 and white_count = 48
  from public.release_replay_game_v2(
    'f5d6c3g5e6f7e7f6h5g4g6f3e8f4h4d3c2e3d2g8g7b3f2h8c4g3h6e2d7d8f1c1h3h7d1g1c8f8c6e1a3b6g2h2b5a6b4b8a4a2a1c5b2h1a7b7a5c7--b1----'
  )), 'two required terminal passes end a non-full board deterministically');

-- Fixtures ------------------------------------------------------------------

insert into auth.users (
  id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data
)
select ('00000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid,
       'authenticated', 'authenticated',
       'release-' || i::text || '@example.test', '', now(), '{}', '{}'
  from generate_series(301, 342) i;

create function pg_temp.create_release_match(
  p_match_id uuid,
  p_black uuid,
  p_white uuid,
  p_active boolean default true
)
returns void
language plpgsql
set search_path = ''
as $$
declare
  initial_deadline timestamptz := now() + interval '2 minutes';
  active_deadline timestamptz := now() + interval '15 minutes';
begin
  insert into public.matches(
    id, black_player, white_player, status, server_status, protocol_version,
    release_status, release_deadline, black_rating_at_start, white_rating_at_start
  ) values (
    p_match_id, p_black, p_white, 'PLAYING', 'CREATED', 2,
    'MATCHED', initial_deadline,
    (select current_rating from public.ratings where user_id = p_black),
    (select current_rating from public.ratings where user_id = p_white)
  );
  insert into public.active_match_participants(user_id, match_id, expires_at)
  values (p_black, p_match_id, initial_deadline),
         (p_white, p_match_id, initial_deadline);
  if p_active then
    update public.matches
       set release_status = 'ACTIVE',
           release_started_at = now(),
           release_deadline = active_deadline,
           p2p_started_at = now(),
           play_lease_expires_at = active_deadline
     where id = p_match_id;
    update public.active_match_participants
       set expires_at = active_deadline where match_id = p_match_id;
  end if;
end;
$$;

create function pg_temp.create_legacy_match(
  p_match_id uuid,
  p_black uuid,
  p_white uuid,
  p_started boolean default true
)
returns void
language plpgsql
set search_path = ''
as $$
declare
  initial_deadline timestamptz := now() + interval '2 minutes';
  active_deadline timestamptz := now() + interval '24 hours';
begin
  insert into public.matches(
    id, black_player, white_player, status, server_status, protocol_version,
    black_rating_at_start, white_rating_at_start, created_expires_at
  ) values (
    p_match_id, p_black, p_white, 'PLAYING', 'CREATED', 1,
    (select current_rating from public.ratings where user_id = p_black),
    (select current_rating from public.ratings where user_id = p_white),
    initial_deadline
  );
  insert into public.active_match_participants(user_id, match_id, expires_at)
  values (p_black, p_match_id, initial_deadline),
         (p_white, p_match_id, initial_deadline);
  if p_started then
    insert into public.match_start_acks(match_id, user_id)
    values (p_match_id, p_black), (p_match_id, p_white);
    update public.matches
       set p2p_started_at = now(), play_lease_expires_at = active_deadline
     where id = p_match_id;
    update public.active_match_participants
       set expires_at = active_deadline where match_id = p_match_id;
  end if;
end;
$$;

-- Protocol-1 compatibility is no longer an authority downgrade ----------------

select pg_temp.create_legacy_match(
  '10000000-0000-4000-8000-000000000331',
  '00000000-0000-4000-8000-000000000331',
  '00000000-0000-4000-8000-000000000332');
select set_config('request.jwt.claim.role', 'authenticated', false);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000331', false);
select throws_ok(
  $$select public.submit_match_result(
    '10000000-0000-4000-8000-000000000331', 'a1', 'BLACK_WIN',
    '0000000000000000:1:0:1', 'NORMAL')$$,
  'P0001', 'invalid canonical moves: ILLEGAL_MOVE',
  'protocol-1 BLACK cannot submit an illegal canonical line for rating');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000332', false);
select throws_ok(
  $$select public.submit_match_result(
    '10000000-0000-4000-8000-000000000331', 'a1', 'BLACK_WIN',
    '0000000000000000:1:0:1', 'NORMAL')$$,
  'P0001', 'invalid canonical moves: ILLEGAL_MOVE',
  'protocol-1 WHITE matching the same illegal canonical line is also rejected');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000331', false);
select throws_ok(
  format(
    $$select public.submit_match_result(
      '10000000-0000-4000-8000-000000000331', %L, 'BLACK_WIN', %L, 'NORMAL')$$,
    (select canonical_moves from release_fixture),
    (select final_position_hash from release_fixture)
  ),
  'P0001', 'result does not match server replay',
  'protocol-1 cannot replace the server-derived NORMAL winner');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000332', false);
select throws_ok(
  format(
    $$select public.submit_match_result(
      '10000000-0000-4000-8000-000000000331', %L, 'WHITE_WIN',
      '0000000000000000:1:0:64', 'NORMAL')$$,
    (select canonical_moves from release_fixture)
  ),
  'P0001', 'final position hash does not match server replay',
  'protocol-1 cannot replace the server-derived final hash');
select is((select count(*)::integer from public.game_records
  where match_id = '10000000-0000-4000-8000-000000000331'), 0,
  'matching malicious protocol-1 attempts create no GameRecord');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000331'), 0,
  'matching malicious protocol-1 attempts create no rating history');

select pg_temp.create_legacy_match(
  '10000000-0000-4000-8000-000000000333',
  '00000000-0000-4000-8000-000000000333',
  '00000000-0000-4000-8000-000000000334');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000333', false);
select is((select public.submit_match_result(
  '10000000-0000-4000-8000-000000000333', '', 'BLACK_WIN',
  (select final_position_hash from public.release_replay_game_v2('')),
  'RESIGNATION')::text), 'PENDING_RESULT',
  'protocol-1 winner claim alone remains pending and unrated');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000333'), 0,
  'winner-only non-normal evidence cannot rate');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000334', false);
select is((select public.submit_match_result(
  '10000000-0000-4000-8000-000000000333', '', 'BLACK_WIN',
  (select final_position_hash from public.release_replay_game_v2('')),
  'RESIGNATION')::text), 'CONFIRMED',
  'the losing WHITE participant can self-confirm its adverse non-normal result');
select ok((select result = 'BLACK_WIN' and final_position_hash =
    (select final_position_hash from public.release_replay_game_v2(''))
    and result_contract_version = 2
  from public.game_records
  where match_id = '10000000-0000-4000-8000-000000000333'),
  'rated protocol-1 GameRecord stores the authoritative replay contract');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000333'), 2,
  'self-adverse protocol-1 evidence rates exactly once');

select pg_temp.create_legacy_match(
  '10000000-0000-4000-8000-000000000335',
  '00000000-0000-4000-8000-000000000335',
  '00000000-0000-4000-8000-000000000336');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000335', false);
select is((select public.submit_match_result(
  '10000000-0000-4000-8000-000000000335',
  (select canonical_moves from release_fixture), 'WHITE_WIN',
  (select final_position_hash from release_fixture), 'NORMAL')::text),
  'PENDING_RESULT', 'one authoritative protocol-1 NORMAL result remains pending');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000336', false);
select is((select public.submit_match_result(
  '10000000-0000-4000-8000-000000000335',
  (select canonical_moves from release_fixture), 'WHITE_WIN',
  (select final_position_hash from release_fixture), 'NORMAL')::text),
  'CONFIRMED', 'matching authoritative protocol-1 NORMAL evidence confirms');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000335'), 2,
  'legal terminal protocol-1 NORMAL evidence rates once');

-- Protocol-1 direct INSERT signaling remains compatible but finite ------------

select pg_temp.create_legacy_match(
  '10000000-0000-4000-8000-000000000337',
  '00000000-0000-4000-8000-000000000337',
  '00000000-0000-4000-8000-000000000338', false);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000337', false);
select throws_ok(
  $$insert into public.match_signaling(
    match_id, sender_id, signal_type, sdp, protocol_version
  ) values (
    '10000000-0000-4000-8000-000000000337',
    '00000000-0000-4000-8000-000000000337', 'ANSWER', 'wrong-role', 1)$$,
  'P0001', 'legacy signal role does not match assigned disc',
  'protocol-1 BLACK cannot publish ANSWER');
insert into public.match_signaling(
  match_id, sender_id, signal_type, sdp, protocol_version
)
select '10000000-0000-4000-8000-000000000337',
       '00000000-0000-4000-8000-000000000337', 'OFFER',
       'legacy-offer-' || i::text, 1
  from generate_series(1, 4) i;
select throws_ok(
  $$insert into public.match_signaling(
    match_id, sender_id, signal_type, sdp, protocol_version
  ) values (
    '10000000-0000-4000-8000-000000000337',
    '00000000-0000-4000-8000-000000000337', 'OFFER', 'legacy-offer-5', 1)$$,
  'P0001', 'legacy signaling sender limit exceeded',
  'protocol-1 direct INSERT rejects signaling beyond the sender budget');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000338', false);
insert into public.match_signaling(
  match_id, sender_id, signal_type, sdp, protocol_version
)
select '10000000-0000-4000-8000-000000000337',
       '00000000-0000-4000-8000-000000000338', 'ANSWER',
       'legacy-answer-' || i::text, 1
  from generate_series(1, 4) i;
select is((select count(*)::integer from public.match_signaling
  where match_id = '10000000-0000-4000-8000-000000000337'), 8,
  'protocol-1 match signaling has a finite eight-row total budget');
update public.matches set server_status = 'ABANDONED'
 where id = '10000000-0000-4000-8000-000000000337';
select throws_ok(
  $$insert into public.match_signaling(
    match_id, sender_id, signal_type, sdp, protocol_version
  ) values (
    '10000000-0000-4000-8000-000000000337',
    '00000000-0000-4000-8000-000000000338', 'ANSWER', 'terminal-answer', 1)$$,
  'P0001', 'legacy match does not accept signaling',
  'protocol-1 terminal match rejects later direct signaling INSERT');
select set_config('request.jwt.claim.role', 'service_role', false);
select public.cleanup_stale_created_matches();
select is((select count(*)::integer from public.match_signaling
  where match_id = '10000000-0000-4000-8000-000000000337'), 0,
  'existing protocol-1 maintenance removes disposable signaling rows');
select set_config('request.jwt.claim.role', 'authenticated', false);

insert into public.matches(
  id, black_player, white_player, status, server_status, protocol_version,
  created_expires_at
) values
  ('10000000-0000-4000-8000-000000000343',
   '00000000-0000-4000-8000-000000000337',
   '00000000-0000-4000-8000-000000000338', 'PLAYING', 'CREATED', 1,
   now() + interval '2 minutes'),
  ('10000000-0000-4000-8000-000000000344',
   '00000000-0000-4000-8000-000000000337',
   '00000000-0000-4000-8000-000000000338', 'PLAYING', 'CREATED', 1,
   now() + interval '2 minutes');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000337', false);
insert into public.match_signaling(
  match_id, sender_id, signal_type, sdp, protocol_version
) values
  ('10000000-0000-4000-8000-000000000343',
   '00000000-0000-4000-8000-000000000337', 'OFFER', 'bounded-cleanup-1', 1),
  ('10000000-0000-4000-8000-000000000344',
   '00000000-0000-4000-8000-000000000337', 'OFFER', 'bounded-cleanup-2', 1);
update public.matches set created_expires_at = now() - interval '1 second'
 where id in (
   '10000000-0000-4000-8000-000000000343',
   '10000000-0000-4000-8000-000000000344'
 );
insert into public.match_queue(
  user_id, current_rating, queued_at, expires_at, protocol_version
) values
  ('00000000-0000-4000-8000-000000000337', 1500,
   now() - interval '2 minutes', now() - interval '1 second', 1),
  ('00000000-0000-4000-8000-000000000338', 1500,
   now() - interval '2 minutes', now() - interval '1 second', 1);
select set_config('request.jwt.claim.role', 'service_role', false);
create temporary table legacy_maintenance_first on commit drop as
select * from public.run_legacy_match_maintenance_v1(1);
select ok((select terminalized_matches = 1 and deleted_signals = 1
  and deleted_queue_rows = 1 from legacy_maintenance_first)
  and (select count(*) = 1 from public.matches
    where id in (
      '10000000-0000-4000-8000-000000000343',
      '10000000-0000-4000-8000-000000000344'
    ) and server_status = 'CREATED')
  and (select count(*) = 1 from public.match_signaling
    where match_id in (
      '10000000-0000-4000-8000-000000000343',
      '10000000-0000-4000-8000-000000000344'
    ))
  and (select count(*) = 1 from public.match_queue
    where user_id in (
      '00000000-0000-4000-8000-000000000337',
      '00000000-0000-4000-8000-000000000338'
    )),
  'protocol-1 coexistence maintenance honors its one-row batch limit');
create temporary table legacy_maintenance_second on commit drop as
select * from public.run_legacy_match_maintenance_v1(1);
select ok((select terminalized_matches = 1 and deleted_signals = 1
  and deleted_queue_rows = 1 from legacy_maintenance_second)
  and (select count(*) = 2 from public.matches
    where id in (
      '10000000-0000-4000-8000-000000000343',
      '10000000-0000-4000-8000-000000000344'
    ) and server_status = 'ABANDONED')
  and (select count(*) = 0 from public.match_signaling
    where match_id in (
      '10000000-0000-4000-8000-000000000343',
      '10000000-0000-4000-8000-000000000344'
    ))
  and (select count(*) = 0 from public.match_queue
    where user_id in (
      '00000000-0000-4000-8000-000000000337',
      '00000000-0000-4000-8000-000000000338'
    )),
  'a second bounded coexistence batch drains the remaining legacy rows');
select set_config('request.jwt.claim.role', 'authenticated', false);

-- Matchmaking response loss, queue cancellation, and pool isolation ---------

select set_config('request.jwt.claim.role', 'authenticated', false);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000301', false);
create temporary table mm_wait on commit drop as
select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000301');
select ok((select not matched and lifecycle_status = 'WAITING'
  and match_id is null from mm_wait),
  'first protocol-2 caller receives exactly one WAITING row');
select is((select count(*)::integer from mm_wait), 1,
  'enqueue WAITING response is always one row');
create temporary table mm_heartbeat on commit drop as
select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000301');
select ok((select count(*) = 1 and bool_and(not matched) from mm_heartbeat),
  'same request id is an idempotent one-row queue heartbeat');
select is((select count(*)::integer from public.match_queue
  where user_id = '00000000-0000-4000-8000-000000000301'
    and protocol_version = 2), 1,
  'queue heartbeat never duplicates the caller row');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000302', false);
create temporary table mm_matched on commit drop as
select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000302');
grant select on mm_matched to authenticated;
select ok((select matched and lifecycle_status = 'MATCHED'
  and negotiation_epoch = 0 and match_id is not null from mm_matched),
  'second protocol-2 caller atomically receives its assignment');
select ok((select count(*) = 1
  and bool_and(user_id = '00000000-0000-4000-8000-000000000301')
  from public.match_notifications
  where match_id = (select match_id from mm_matched)),
  'protocol-2 matchmaking emits one durable wake-up for the waiting peer');
select is((select count(*)::integer from mm_matched), 1,
  'enqueue MATCHED response is exactly one row');
create temporary table mm_retry on commit drop as
select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000302');
select is((select match_id from mm_retry), (select match_id from mm_matched),
  'lost enqueue response retry recovers the same assignment');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000301', false);
create temporary table mm_claim on commit drop as
select * from public.claim_active_match_v2();
select is((select match_id from mm_claim), (select match_id from mm_matched),
  'waiting peer recovers the assignment through claim');
select is((select negotiation_epoch from mm_claim), 0,
  'claimed assignment includes the current negotiation epoch');
select is((select count(*)::integer from public.match_notifications
  where match_id = (select match_id from mm_matched)
    and user_id = '00000000-0000-4000-8000-000000000301'), 0,
  'claim consumes the wake-up row after recovering authority state');
create temporary table mm_reclaim_after_loss on commit drop as
select * from public.claim_active_match_v2();
select is((select match_id from mm_reclaim_after_loss),
  (select match_id from mm_matched),
  'lost claim response remains recoverable after notification consumption');

select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000303', false);
select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000303');
create temporary table cancel_success on commit drop as
select * from public.cancel_waiting_v2(
  '20000000-0000-4000-8000-000000000303');
select is((select count(*)::integer from cancel_success), 0,
  'successful queue cancellation returns zero assignment rows');
select ok(not exists (select 1 from public.match_queue
  where user_id = '00000000-0000-4000-8000-000000000303'),
  'successful cancellation removes the queue row');

select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000313');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000304', false);
create temporary table cancel_race_match on commit drop as
select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000304');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000303', false);
create temporary table cancel_race_recovery on commit drop as
select * from public.cancel_waiting_v2(
  '20000000-0000-4000-8000-000000000313');
select is((select match_id from cancel_race_recovery),
  (select match_id from cancel_race_match),
  'cancel-versus-match race returns the consumed-request assignment');
select is((select negotiation_epoch from cancel_race_recovery), 0,
  'cancel race response carries negotiation epoch');
select is(public.abandon_match_v2((select match_id from cancel_race_match))::text,
  'ABANDONED', 'a MATCHED cancel-race assignment can be abandoned');

-- An already queued heartbeat must still look for a concurrently visible peer.
insert into public.match_queue(
  user_id, current_rating, queued_at, expires_at, protocol_version, request_id
) values
 ('00000000-0000-4000-8000-000000000326', 1500, now(), now() + interval '2 minutes', 2,
  '20000000-0000-4000-8000-000000000326'),
 ('00000000-0000-4000-8000-000000000327', 1500, now(), now() + interval '2 minutes', 2,
  '20000000-0000-4000-8000-000000000327');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000326', false);
create temporary table queued_heartbeat_match on commit drop as
select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000326');
select ok((select matched and match_id is not null from queued_heartbeat_match),
  'heartbeat of an existing queue row can claim a concurrently visible peer');
select is((select count(*)::integer from public.matches
  where protocol_version = 2
    and black_player in (
      '00000000-0000-4000-8000-000000000326',
      '00000000-0000-4000-8000-000000000327')
    and white_player in (
      '00000000-0000-4000-8000-000000000326',
      '00000000-0000-4000-8000-000000000327')),
  1, 'queued heartbeat concurrency recovery creates exactly one match');
select is(public.abandon_match_v2(
  (select match_id from queued_heartbeat_match))::text,
  'ABANDONED', 'concurrency recovery reservation is released cleanly');

select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000324', false);
select * from public.enqueue_or_match();
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000325', false);
create temporary table isolated_v2_wait on commit drop as
select * from public.enqueue_or_match_v2(
  '20000000-0000-4000-8000-000000000325');
select ok((select not matched from isolated_v2_wait),
  'protocol-2 client does not consume a protocol-1 queue row');
select is((select count(*)::integer from public.match_queue
  where user_id in (
    '00000000-0000-4000-8000-000000000324',
    '00000000-0000-4000-8000-000000000325')
    and protocol_version in (1, 2)), 2,
  'both queue protocols coexist without crossover');
select * from public.cancel_waiting_v2(
  '20000000-0000-4000-8000-000000000325');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000324', false);
select ok(public.cancel_waiting(), 'protocol-1 cancellation remains functional');

-- Signaling role, RESUME, TTL, dedup, slots, RLS -----------------------------

select set_config('request.jwt.claim.sub', (select black_player::text
  from public.matches where id = (select match_id from mm_matched)), false);
create temporary table offer_one on commit drop as
select * from public.publish_match_signal_v2(
  (select match_id from mm_matched), 'OFFER', 'offer-one', 2, 0);
create temporary table offer_duplicate on commit drop as
select * from public.publish_match_signal_v2(
  (select match_id from mm_matched), 'OFFER', 'offer-one', 2, 0);
select ok((select not duplicate from offer_one)
  and (select duplicate from offer_duplicate),
  'identical signaling retries are idempotently deduplicated');
select is((select signal_id from offer_duplicate),
  (select signal_id from offer_one), 'dedup returns the original signal identity');
select * from public.publish_match_signal_v2(
  (select match_id from mm_matched), 'OFFER', 'offer-two', 2, 0);
select * from public.publish_match_signal_v2(
  (select match_id from mm_matched), 'OFFER', 'offer-three', 2, 0);
select * from public.publish_match_signal_v2(
  (select match_id from mm_matched), 'OFFER', 'offer-four', 2, 0);
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    (select match_id from mm_matched), 'OFFER', 'offer-five', 2, 0)$$,
  'P0001', 'signaling slot limit exceeded',
  'a fifth OFFER in one sender epoch is rate-limited'
);
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    (select match_id from mm_matched), 'RESUME', 'black-resume', 2, 0)$$,
  'P0001', 'signal role does not match assigned disc',
  'BLACK cannot publish the WHITE-only RESUME wake-up'
);
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    (select match_id from mm_matched), 'ANSWER', 'spoof-answer', 2, 0)$$,
  'P0001', 'signal role does not match assigned disc',
  'BLACK cannot spoof the ANSWER role'
);
select set_config('request.jwt.claim.sub', (select white_player::text
  from public.matches where id = (select match_id from mm_matched)), false);
select * from public.publish_match_signal_v2(
  (select match_id from mm_matched), 'ANSWER', 'answer-one', 2, 0);
create temporary table white_resume on commit drop as
select * from public.publish_match_signal_v2(
  (select match_id from mm_matched), 'RESUME', 'white-resume', 2, 0);
create temporary table white_resume_retry on commit drop as
select * from public.publish_match_signal_v2(
  (select match_id from mm_matched), 'RESUME', 'white-resume', 2, 0);
select ok((select not duplicate from white_resume)
  and (select duplicate from white_resume_retry),
  'WHITE may publish an idempotent RESUME in the current epoch');
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    (select match_id from mm_matched), 'RESUME', 'white-resume-two', 2, 0)$$,
  'P0001', 'signaling slot limit exceeded',
  'a second distinct WHITE RESUME slot is rejected'
);
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    (select match_id from mm_matched), 'OFFER', 'spoof-offer', 2, 0)$$,
  'P0001', 'signal role does not match assigned disc',
  'WHITE cannot spoof the OFFER role'
);
select ok(not exists (
  select 1 from public.match_signals_v2
   where match_id = (select match_id from mm_matched)
     and (expires_at > created_at + interval '2 minutes'
       or expires_at > (select release_deadline from public.matches
                         where id = (select match_id from mm_matched)))
), 'all signaling rows are bounded by both TTL and match lease');

select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000319', false);
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    (select match_id from mm_matched), 'RESUME', 'intruder-resume', 2, 0)$$,
  'P0001', 'release match participant required',
  'nonparticipant RESUME is rejected'
);
set local role authenticated;
select is((select count(*)::integer from public.match_signals_v2
  where match_id = (select match_id from mm_matched)), 0,
  'signal RLS hides every row from a nonparticipant');
reset role;
select set_config('request.jwt.claim.sub', (select black_player::text
  from public.matches where id = (select match_id from mm_matched)), false);
set local role authenticated;
select ok((select count(*) > 0 from public.match_signals_v2
  where match_id = (select match_id from mm_matched)),
  'signal RLS exposes current-epoch rows to a participant');
select throws_ok(
  $$insert into public.match_signaling(
      match_id, sender_id, signal_type, sdp, protocol_version
    ) values (
      (select match_id from mm_matched), auth.uid(), 'OFFER',
      'legacy-bypass', 1
    )$$,
  'P0001', 'legacy signaling participant required',
  'protocol-2 participant cannot bypass publish limits through legacy signaling'
);
reset role;

-- Both data-channel acknowledgements activate; NORMAL replay finalizes once --

create temporary table first_ack on commit drop as
select * from public.ack_match_started_v2((select match_id from mm_matched));
select ok((select lifecycle_status = 'MATCHED' and local_acked and not both_acked from first_ack),
  'one authenticated client cannot activate a match without its peer ACK');
select set_config('request.jwt.claim.sub', (select white_player::text
  from public.matches where id = (select match_id from mm_matched)), false);
create temporary table second_ack on commit drop as
select * from public.ack_match_started_v2((select match_id from mm_matched));
select ok((select lifecycle_status = 'ACTIVE' and both_acked from second_ack),
  'the second participant acknowledgement atomically activates the match');
select throws_ok(
  $$select public.abandon_match_v2((select match_id from mm_matched))$$,
  'P0001', 'active release match cannot be abandoned',
  'ACTIVE match cannot use pre-start abandonment'
);
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    (select match_id from mm_matched), 'RESUME', 'active-resume', 2, 0)$$,
  'P0001', 'release match does not accept signaling',
  'ACTIVE state rejects RESUME signaling'
);
select set_config('request.jwt.claim.sub', (select black_player::text
  from public.matches where id = (select match_id from mm_matched)), false);
create temporary table normal_first_claim on commit drop as
select * from public.submit_match_result_v2(
  (select match_id from mm_matched),
  '30000000-0000-4000-8000-000000000301',
  (select canonical_moves from release_fixture), 'NORMAL', null, null);
select ok((select server_status = 'RESULT_PENDING'
  and rating_before is null and rating_after is null and rating_delta is null
  and final_result is null and final_position_hash is null
  from normal_first_claim),
  'one legal terminal transcript remains unilateral and unrated');
select is((select count(*)::integer from public.match_results_v2
  where match_id = (select match_id from mm_matched)), 0,
  'unilateral NORMAL claim creates no authoritative result fact');
select is((select count(*)::integer from public.rating_history
  where match_id = (select match_id from mm_matched)), 0,
  'unilateral legal transcript cannot rate either participant');
create temporary table pending_retry on commit drop as
select * from public.submit_match_result_v2(
  (select match_id from mm_matched),
  '30000000-0000-4000-8000-000000000301',
  (select canonical_moves from release_fixture), 'NORMAL', null, null);
select is((select server_status from pending_retry), 'RESULT_PENDING',
  'lost first-claim response retry remains idempotently pending');
select set_config('request.jwt.claim.sub', (select white_player::text
  from public.matches where id = (select match_id from mm_matched)), false);
create temporary table normal_confirmation on commit drop as
select * from public.submit_match_result_v2(
  (select match_id from mm_matched),
  '30000000-0000-4000-8000-000000000302',
  (select canonical_moves from release_fixture), 'NORMAL', null, null);
select ok((select server_status = 'CONFIRMED'
  and rating_before = 1500 and rating_after = 1516 and rating_delta = 16
  and current_rating = 1516 and peak_rating = 1516
  and final_result = 'WHITE_WIN'
  and final_position_hash = (select final_position_hash from release_fixture)
  from normal_confirmation),
  'matching NORMAL claims from both participants confirm the server replay');
select is((select count(*)::integer from public.match_results_v2
  where match_id = (select match_id from mm_matched)), 1,
  'bilateral NORMAL confirmation writes one authoritative result fact');
select ok((select result_contract_version = 2
  and canonical_moves = (select canonical_moves from release_fixture)
  and result = 'WHITE_WIN'
  and final_position_hash = (select final_position_hash from release_fixture)
  from public.game_records where match_id = (select match_id from mm_matched)),
  'authoritative result and canonical record commit together');
select is((select count(*)::integer from public.rating_history
  where match_id = (select match_id from mm_matched)), 2,
  'both ratings are recorded exactly once');
select is((select count(*)::integer from public.user_game_records
  where match_id = (select match_id from mm_matched)), 2,
  'both bounded history references commit with the result');
select ok((select server_status = 'CONFIRMED'
  and release_status = 'CONFIRMED' and confirmed_at is not null
  from public.matches where id = (select match_id from mm_matched)),
  'legacy confirmed transition (and Research trigger point) is in the same transaction');
select set_config('request.jwt.claim.sub', (select black_player::text
  from public.matches where id = (select match_id from mm_matched)), false);
create temporary table normal_retry on commit drop as
select * from public.submit_match_result_v2(
  (select match_id from mm_matched),
  '30000000-0000-4000-8000-000000000301',
  (select canonical_moves from release_fixture), 'NORMAL', null, null);
select ok((select rating_before = 1500 and rating_after = 1484
  and rating_delta = -16 and final_result = 'WHITE_WIN' from normal_retry),
  'original caller retry recovers the bilateral final rating result');
select is((select count(*)::integer from public.rating_history
  where match_id = (select match_id from mm_matched)), 2,
  'duplicate NORMAL request never rates twice');
select throws_ok(
  $$update public.matches set release_status = 'ACTIVE'
     where id = (select match_id from mm_matched)$$,
  'P0001', 'terminal release match is immutable',
  'terminal lifecycle status cannot be reopened'
);
select throws_ok(
  $$update public.matches set release_deadline = release_deadline + interval '1 minute'
     where id = (select match_id from mm_matched)$$,
  'P0001', 'terminal release match metadata is immutable',
  'terminal lifecycle metadata cannot be rewritten'
);
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    (select match_id from mm_matched), 'RESUME', 'terminal-resume', 2, 0)$$,
  'P0001', 'release match does not accept signaling',
  'terminal match rejects RESUME'
);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000319', false);
set local role authenticated;
select is((select count(*)::integer from public.match_results_v2
  where match_id = (select match_id from mm_matched)), 0,
  'result RLS hides final fact from a nonparticipant');
reset role;
select set_config('request.jwt.claim.sub', (select black_player::text
  from public.matches where id = (select match_id from mm_matched)), false);
set local role authenticated;
select is((select count(*)::integer from public.match_results_v2
  where match_id = (select match_id from mm_matched)), 1,
  'result RLS exposes final fact to a participant');
reset role;

-- Server-owned forfeit/evidence/grace state machine --------------------------

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000305',
  '00000000-0000-4000-8000-000000000305',
  '00000000-0000-4000-8000-000000000306');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000305', false);
create temporary table self_resign on commit drop as
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000305',
  '30000000-0000-4000-8000-000000000305', '', 'RESIGNATION', 'BLACK', null);
select ok((select server_status = 'FORFEIT' and final_result = 'WHITE_WIN'
  from self_resign),
  'caller may immediately forfeit only its own BLACK side');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000305'), 2,
  'self-resignation rates once');

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000307',
  '00000000-0000-4000-8000-000000000307',
  '00000000-0000-4000-8000-000000000308');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000307', false);
create temporary table unsupported_timeout on commit drop as
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000307',
  '30000000-0000-4000-8000-000000000307', 'd3', 'TIMEOUT', 'WHITE', null);
select is((select server_status from unsupported_timeout), 'RESULT_PENDING',
  'opponent TIMEOUT allegation is evidence, not immediate authority');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000307'), 0,
  'unsupported timeout never changes ratings');
update public.matches set release_deadline = now() - interval '1 second'
 where id = '10000000-0000-4000-8000-000000000307';
select * from public.reconcile_match_v2(
  '10000000-0000-4000-8000-000000000307');
select is((select release_status::text from public.matches
  where id = '10000000-0000-4000-8000-000000000307'), 'EXPIRED',
  'unsupported timeout evidence expires without deciding a winner');

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000309',
  '00000000-0000-4000-8000-000000000309',
  '00000000-0000-4000-8000-000000000310');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000309',
  '30000000-0000-4000-8000-000000000309', 'd3', 'DISCONNECT', 'WHITE', null);
create temporary table report_first_observed_state on commit drop as
select * from public.get_release_match_state_v2(
  '10000000-0000-4000-8000-000000000309');
select ok((select lifecycle_status = 'RECONNECTING' and negotiation_epoch = 1
  from report_first_observed_state),
  'coordinator reading after the disconnect report adopts the authoritative reconnect epoch');
select ok((select release_status = 'RECONNECTING'
  and reconnect_deadline <= now() + interval '45 seconds'
  and negotiation_epoch = 1
  from public.matches where id = '10000000-0000-4000-8000-000000000309'),
  'opponent disconnect enters a fresh 45-second negotiation epoch');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000310', false);
create temporary table returned_peer on commit drop as
select * from public.resume_match_v2(
  '10000000-0000-4000-8000-000000000309');
select ok((select lifecycle_status = 'RECONNECTING'
  and negotiation_epoch = 1 from returned_peer),
  'accused peer return clears stale evidence without double-bumping the reconnect epoch');
select throws_ok(
  $$select * from public.publish_match_signal_v2(
    '10000000-0000-4000-8000-000000000309', 'RESUME',
    'delayed-old-epoch-resume', 2, 0)$$,
  'P0001', 'stale signaling negotiation epoch',
  'a delayed signal generated for an old epoch cannot enter the current epoch'
);
create temporary table reconnect_resume on commit drop as
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000309', 'RESUME', 'white-process-return', 2, 1);
create temporary table reconnect_resume_retry on commit drop as
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000309', 'RESUME', 'white-process-return', 2, 1);
select ok((select not duplicate from reconnect_resume)
  and (select duplicate from reconnect_resume_retry),
  'returning WHITE publishes one idempotent RESUME in the new epoch');
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000309');
select is((select release_status::text from public.matches
  where id = '10000000-0000-4000-8000-000000000309'), 'RECONNECTING',
  'one reconnect ACK cannot reactivate a match by itself');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000309');
select ok((select release_status = 'ACTIVE'
  and black_disconnect_claimed_at is null
  and white_disconnect_claimed_at is null
  from public.matches where id = '10000000-0000-4000-8000-000000000309'),
  'returned peer DataChannel ack restores ACTIVE and clears allegations');
update public.matches set release_deadline = now() - interval '1 second'
 where id = '10000000-0000-4000-8000-000000000309';
select * from public.reconcile_match_v2(
  '10000000-0000-4000-8000-000000000309');

-- The coordinator can read ACTIVE immediately before the Controller's disconnect
-- report commits. Its subsequent resume must join that same epoch and complete it.
select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000339',
  '00000000-0000-4000-8000-000000000339',
  '00000000-0000-4000-8000-000000000340');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000339', false);
create temporary table read_before_disconnect_report on commit drop as
select * from public.get_release_match_state_v2(
  '10000000-0000-4000-8000-000000000339');
select ok((select lifecycle_status = 'ACTIVE' and negotiation_epoch = 0
  from read_before_disconnect_report),
  'race fixture observes ACTIVE at the old epoch before disconnect evidence commits');
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000339',
  '30000000-0000-4000-8000-000000000339', 'd3', 'DISCONNECT', 'WHITE', null);
create temporary table resume_after_disconnect_report on commit drop as
select * from public.resume_match_v2(
  '10000000-0000-4000-8000-000000000339');
select ok((select lifecycle_status = 'RECONNECTING' and negotiation_epoch = 1
  from resume_after_disconnect_report),
  'resume after the raced report joins epoch one without a second increment');
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000339', 'OFFER', 'race-report-first-offer', 2, 1);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000339');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000340', false);
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000339', 'ANSWER', 'race-report-first-answer', 2, 1);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000339');
select ok((select release_status = 'ACTIVE' and negotiation_epoch = 1
  from public.matches where id = '10000000-0000-4000-8000-000000000339')
  and (select count(*) = 2 from public.match_start_acks_v2
    where match_id = '10000000-0000-4000-8000-000000000339'
      and negotiation_epoch = 1),
  'read-before-report ordering completes fresh signaling and bilateral ACK at ACTIVE epoch one');

-- The inverse arrival order is also one epoch: resume locks ACTIVE first, and the
-- delayed disconnect report observes RECONNECTING rather than incrementing again.
select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000341',
  '00000000-0000-4000-8000-000000000341',
  '00000000-0000-4000-8000-000000000342');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000341', false);
create temporary table read_before_resume on commit drop as
select * from public.get_release_match_state_v2(
  '10000000-0000-4000-8000-000000000341');
select * from public.resume_match_v2(
  '10000000-0000-4000-8000-000000000341');
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000341',
  '30000000-0000-4000-8000-000000000341', 'd3', 'DISCONNECT', 'WHITE', null);
select ok((select lifecycle_status = 'ACTIVE' and negotiation_epoch = 0
  from read_before_resume)
  and (select release_status = 'RECONNECTING' and negotiation_epoch = 1
    from public.matches where id = '10000000-0000-4000-8000-000000000341'),
  'resume-before-report ordering also creates exactly one reconnect epoch');
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000341', 'OFFER', 'race-resume-first-offer', 2, 1);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000341');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000342', false);
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000341', 'ANSWER', 'race-resume-first-answer', 2, 1);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000341');
select ok((select release_status = 'ACTIVE' and negotiation_epoch = 1
  from public.matches where id = '10000000-0000-4000-8000-000000000341')
  and (select count(*) = 2 from public.match_start_acks_v2
    where match_id = '10000000-0000-4000-8000-000000000341'
      and negotiation_epoch = 1),
  'resume-before-report ordering returns to ACTIVE after bilateral current-epoch ACK');

-- A match owns only epochs 0..3, even when both participants coordinate retries.
select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000330',
  '00000000-0000-4000-8000-000000000309',
  '00000000-0000-4000-8000-000000000310');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000310', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');

select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.resume_match_v2(
  '10000000-0000-4000-8000-000000000330');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000310', false);
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000330', 'RESUME', 'budget-resume-1', 2, 1);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000310', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');

select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.resume_match_v2(
  '10000000-0000-4000-8000-000000000330');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000310', false);
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000330', 'RESUME', 'budget-resume-2', 2, 2);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000310', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');

select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.resume_match_v2(
  '10000000-0000-4000-8000-000000000330');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000310', false);
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000330', 'RESUME', 'budget-resume-3', 2, 3);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000310', false);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');

select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
create temporary table reconnect_budget_exhausted on commit drop as
select * from public.resume_match_v2(
  '10000000-0000-4000-8000-000000000330');
select ok((select lifecycle_status = 'EXPIRED'
  and negotiation_epoch = 3
  and terminal_reason = 'RECONNECT_BUDGET_EXHAUSTED_UNRATED'
  from reconnect_budget_exhausted),
  'a fourth ACTIVE reconnect expires unrated without creating epoch 4');
select is((select count(*)::integer from public.match_start_acks_v2
  where match_id = '10000000-0000-4000-8000-000000000330'), 8,
  'initial plus three reconnect epochs bound start ACK rows to eight');
select ok((select count(*) = 3 and max(negotiation_epoch) = 3
  from public.match_signals_v2
  where match_id = '10000000-0000-4000-8000-000000000330'),
  'WHITE-only RESUME rows remain bounded to the three reconnect epochs');
select is((select count(*)::integer from public.match_result_claims_v2
  where match_id = '10000000-0000-4000-8000-000000000330'), 0,
  'reconnect budget exhaustion fabricates no result claim');
select is((select count(*)::integer from public.match_results_v2
  where match_id = '10000000-0000-4000-8000-000000000330'), 0,
  'reconnect budget exhaustion creates no authoritative result');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000330'), 0,
  'reconnect budget exhaustion remains unrated');
select is((select count(*)::integer from public.game_records
  where match_id = '10000000-0000-4000-8000-000000000330'), 0,
  'reconnect budget exhaustion creates no GameRecord or Research source');
select is((select count(*)::integer from public.active_match_participants
  where match_id = '10000000-0000-4000-8000-000000000330'), 0,
  'reconnect budget expiry releases both active reservations');
select throws_ok(
  $$select * from public.resume_match_v2(
    '10000000-0000-4000-8000-000000000330')$$,
  'P0001', 'release match is not resumable',
  'a terminalized budget match cannot be resumed again'
);
select * from public.ack_match_started_v2(
  '10000000-0000-4000-8000-000000000330');
select is((select count(*)::integer from public.match_start_acks_v2
  where match_id = '10000000-0000-4000-8000-000000000330'), 8,
  'a terminal ACK cannot create another persistent row');

-- The opponent-DISCONNECT submission path shares the same epoch budget guard.
select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000332',
  '00000000-0000-4000-8000-000000000309',
  '00000000-0000-4000-8000-000000000310');
update public.matches set negotiation_epoch = 3
 where id = '10000000-0000-4000-8000-000000000332';
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000309', false);
create temporary table disconnect_budget_exhausted on commit drop as
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000332',
  '30000000-0000-4000-8000-000000000332', 'd3', 'DISCONNECT', 'WHITE', null);
select is((select server_status from disconnect_budget_exhausted), 'EXPIRED',
  'opponent DISCONNECT at epoch 3 expires instead of creating epoch 4');
select ok((select negotiation_epoch = 3
  and release_terminal_reason = 'RECONNECT_BUDGET_EXHAUSTED_UNRATED'
  from public.matches where id = '10000000-0000-4000-8000-000000000332'),
  'DISCONNECT budget exhaustion preserves the final allowed epoch and reason');
select is((select count(*)::integer from public.match_result_claims_v2
  where match_id = '10000000-0000-4000-8000-000000000332'), 0,
  'DISCONNECT budget exhaustion creates no claim row');
select is((select count(*)::integer from public.match_results_v2
  where match_id = '10000000-0000-4000-8000-000000000332'), 0,
  'DISCONNECT budget exhaustion creates no result');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000332'), 0,
  'DISCONNECT budget exhaustion leaves ratings untouched');
select is((select count(*)::integer from public.game_records
  where match_id = '10000000-0000-4000-8000-000000000332'), 0,
  'DISCONNECT budget exhaustion creates no GameRecord or Research source');

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000311',
  '00000000-0000-4000-8000-000000000311',
  '00000000-0000-4000-8000-000000000312');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000311', false);
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000311',
  '30000000-0000-4000-8000-000000000311', 'd3', 'DISCONNECT', 'WHITE', null);
update public.matches
   set release_deadline = now() - interval '1 second',
       reconnect_deadline = now() - interval '1 second'
 where id = '10000000-0000-4000-8000-000000000311';
select * from public.reconcile_match_v2(
  '10000000-0000-4000-8000-000000000311');
select ok((select release_status = 'EXPIRED'
  and release_terminal_reason = 'DISCONNECT_GRACE_EXPIRED_UNRATED'
  from public.matches where id = '10000000-0000-4000-8000-000000000311'),
  'spoofable opponent disconnect expires unrated after recovery grace');
select is((select count(*)::integer from public.match_results_v2
  where match_id = '10000000-0000-4000-8000-000000000311'), 0,
  'opponent disconnect allegation creates no authoritative winner');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000311'), 0,
  'spoofed opponent disconnect cannot rate either participant');

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000313',
  '00000000-0000-4000-8000-000000000313',
  '00000000-0000-4000-8000-000000000314');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000313', false);
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000313',
  '30000000-0000-4000-8000-000000000313', 'd3', 'DISCONNECT', 'WHITE', null);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000314', false);
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000313',
  '30000000-0000-4000-8000-000000000314', 'd3', 'DISCONNECT', 'BLACK', null);
update public.matches
   set release_deadline = now() - interval '1 second',
       reconnect_deadline = now() - interval '1 second'
 where id = '10000000-0000-4000-8000-000000000313';
select * from public.reconcile_match_v2(
  '10000000-0000-4000-8000-000000000313');
select is((select release_status::text from public.matches
  where id = '10000000-0000-4000-8000-000000000313'), 'EXPIRED',
  'mutual disconnect allegations expire without rating either side');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000313'), 0,
  'mutual disconnect expiry leaves ratings untouched');

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000315',
  '00000000-0000-4000-8000-000000000315',
  '00000000-0000-4000-8000-000000000316');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000315', false);
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000315',
  '30000000-0000-4000-8000-000000000315', 'd3', 'RESIGNATION', 'WHITE', null);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000316', false);
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000315',
  '30000000-0000-4000-8000-000000000316', 'd3', 'RESIGNATION', 'WHITE', null);
select is((select release_status::text from public.matches
  where id = '10000000-0000-4000-8000-000000000315'), 'FORFEIT',
  'alleged loser can later self-concede within evidence grace');

-- NORMAL requires bilateral agreement; unilateral or divergent claims do not rate.
select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000328',
  '00000000-0000-4000-8000-000000000328',
  '00000000-0000-4000-8000-000000000329');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000328', false);
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000328',
  '30000000-0000-4000-8000-000000000328',
  (select canonical_moves from release_fixture), 'NORMAL', null, null);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000329', false);
create temporary table disputed_normal on commit drop as
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000328',
  '30000000-0000-4000-8000-000000000329',
  'e6f6g6g7g8h8f5f8f7e7e8d8h7h6c4d7c8b8--c7--d6--g4g5h4h5f4h3c5c6b6b7a7a8a6a5b5f3b4a4g3f2e3d3c3b3a3a2h2--g2h1e2d2c2b2b1g1f1e1d1c1a1',
  'NORMAL', null, null);
select is((select server_status from disputed_normal), 'DISPUTED',
  'two different server-valid terminal transcripts become DISPUTED');
select is((select count(*)::integer from public.match_results_v2
  where match_id = '10000000-0000-4000-8000-000000000328'), 0,
  'NORMAL disagreement creates no authoritative result');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000328'), 0,
  'NORMAL disagreement is unrated');
select is((select count(*)::integer from public.game_records
  where match_id = '10000000-0000-4000-8000-000000000328'), 0,
  'NORMAL disagreement creates no GameRecord or Research trigger source');

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000329',
  '00000000-0000-4000-8000-000000000329',
  '00000000-0000-4000-8000-000000000330');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000329', false);
select * from public.submit_match_result_v2(
  '10000000-0000-4000-8000-000000000329',
  '30000000-0000-4000-8000-000000000339',
  (select canonical_moves from release_fixture), 'NORMAL', null, null);
update public.matches set release_deadline = now() - interval '1 second'
 where id = '10000000-0000-4000-8000-000000000329';
select * from public.reconcile_match_v2(
  '10000000-0000-4000-8000-000000000329');
select is((select release_status::text from public.matches
  where id = '10000000-0000-4000-8000-000000000329'), 'EXPIRED',
  'one-sided NORMAL claim expires after 45 seconds');
select is((select count(*)::integer from public.rating_history
  where match_id = '10000000-0000-4000-8000-000000000329'), 0,
  'one-sided NORMAL expiry remains unrated');
select is((select count(*)::integer from public.game_records
  where match_id = '10000000-0000-4000-8000-000000000329'), 0,
  'one-sided NORMAL expiry creates no GameRecord or Research source');

-- Illegal/truncated/spoof attempts never acquire authority -------------------

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000317',
  '00000000-0000-4000-8000-000000000317',
  '00000000-0000-4000-8000-000000000318');
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000317', false);
select throws_ok(
  $$select * from public.submit_match_result_v2(
    '10000000-0000-4000-8000-000000000317',
    '30000000-0000-4000-8000-000000000317', 'a1', 'NORMAL', null, null)$$,
  'P0001', 'invalid canonical moves: ILLEGAL_MOVE',
  'participant cannot submit an illegal transcript'
);
select throws_ok(
  $$select * from public.submit_match_result_v2(
    '10000000-0000-4000-8000-000000000317',
    '30000000-0000-4000-8000-000000000327', 'd3', 'NORMAL', null, null)$$,
  'P0001', 'NORMAL result is not terminal',
  'participant cannot finalize a truncated NORMAL transcript'
);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000319', false);
select throws_ok(
  $$select * from public.submit_match_result_v2(
    '10000000-0000-4000-8000-000000000317',
    '30000000-0000-4000-8000-000000000319', 'a1', 'NORMAL', null, null)$$,
  'P0001', 'release match participant required',
  'participant authorization rejects an intruder before illegal transcript replay'
);
select throws_ok(
  $$select * from public.get_release_match_state_v2(
    '10000000-0000-4000-8000-000000000317')$$,
  'P0001', 'release match participant required',
  'nonparticipant cannot read release state through RPC'
);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000317', false);
update public.matches set release_deadline = now() - interval '1 second'
 where id = '10000000-0000-4000-8000-000000000317';
select * from public.reconcile_match_v2(
  '10000000-0000-4000-8000-000000000317');

-- Maintenance terminalizes business state before transport cleanup ----------

select pg_temp.create_release_match(
  '10000000-0000-4000-8000-000000000320',
  '00000000-0000-4000-8000-000000000320',
  '00000000-0000-4000-8000-000000000321', false);
select set_config('request.jwt.claim.sub',
  '00000000-0000-4000-8000-000000000320', false);
select * from public.publish_match_signal_v2(
  '10000000-0000-4000-8000-000000000320', 'OFFER', 'maintenance-offer', 2, 0);
update public.matches set release_deadline = now() - interval '1 second'
 where id = '10000000-0000-4000-8000-000000000320';
select throws_ok(
  $$select * from public.run_match_maintenance_v2(100)$$,
  'P0001', 'service role required',
  'authenticated caller cannot run maintenance even through owner test session'
);
select set_config('request.jwt.claim.role', 'service_role', false);
create temporary table maintenance_result on commit drop as
select * from public.run_match_maintenance_v2(100);
select ok((select terminalized_matches >= 1 and deleted_signals >= 1
  from maintenance_result),
  'service maintenance terminalizes expired matches then removes signaling');
select ok((select release_status = 'EXPIRED'
  and server_status = 'ABANDONED'
  from public.matches where id = '10000000-0000-4000-8000-000000000320'),
  'maintenance persists terminal business state');
select is((select count(*)::integer from public.match_signals_v2
  where match_id = '10000000-0000-4000-8000-000000000320'), 0,
  'terminalized match signaling is cleaned after state persistence');
select is((select count(*)::integer from public.match_notifications
  where match_id = '10000000-0000-4000-8000-000000000320'), 0,
  'terminalized match leaves no stale Realtime wake-up rows');
select set_config('request.jwt.claim.role', 'authenticated', false);

select * from finish();
rollback;

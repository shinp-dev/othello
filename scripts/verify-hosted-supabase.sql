-- Run in the hosted project's SQL Editor after every migration through 030 has been applied.
-- Every value in hosted_contract must be true. protocol1_compatibility is informational:
-- legacy objects may remain during coexistence, but they are not Protocol 2 success criteria.
select
jsonb_build_object(
  'protocol2_release_status_enum', coalesce((
    select array_agg(e.enumlabel::text order by e.enumsortorder) = array[
      'MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING', 'CONFIRMED',
      'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED'
    ]::text[]
      from pg_enum e
      join pg_type t on t.oid = e.enumtypid
      join pg_namespace n on n.oid = t.typnamespace
     where n.nspname = 'public' and t.typname = 'release_match_status'
  ), false),
  'protocol2_matches_columns', exists (
    select 1
      from information_schema.columns
     where table_schema = 'public' and table_name = 'matches'
       and column_name in (
         'protocol_version', 'release_status', 'release_deadline',
         'reconnect_deadline', 'release_started_at', 'release_terminal_at',
         'release_updated_at', 'release_terminal_reason', 'negotiation_epoch',
         'black_queue_request_id', 'white_queue_request_id',
         'black_disconnect_claimed_at', 'white_disconnect_claimed_at'
       )
    having count(*) = 13
  ),
  'protocol2_queue_columns', exists (
    select 1
      from information_schema.columns
     where table_schema = 'public' and table_name = 'match_queue'
       and column_name in ('protocol_version', 'request_id')
    having count(*) = 2
  ),
  'protocol2_ack_table', to_regclass('public.match_start_acks_v2') is not null,
  'protocol2_result_claims_table', to_regclass('public.match_result_claims_v2') is not null,
  'protocol2_results_table', to_regclass('public.match_results_v2') is not null,
  'protocol2_signals_table', to_regclass('public.match_signals_v2') is not null,
  'protocol2_ack_columns', exists (
    select 1
      from information_schema.columns
     where table_schema = 'public' and table_name = 'match_start_acks_v2'
       and column_name in ('match_id', 'user_id', 'negotiation_epoch', 'acked_at')
    having count(*) = 4
  ),
  'protocol2_result_claim_columns', exists (
    select 1
      from information_schema.columns
     where table_schema = 'public' and table_name = 'match_result_claims_v2'
       and column_name in (
         'match_id', 'player_id', 'request_id', 'negotiation_epoch',
         'canonical_moves', 'finish_reason', 'loser_disc', 'clock',
         'derived_position_hash', 'derived_board_result',
         'derived_black_count', 'derived_white_count', 'submitted_at'
       )
    having count(*) = 13
  ),
  'protocol2_result_columns', exists (
    select 1
      from information_schema.columns
     where table_schema = 'public' and table_name = 'match_results_v2'
       and column_name in (
         'match_id', 'terminal_status', 'canonical_moves', 'final_result',
         'finish_reason', 'loser_disc', 'final_position_hash', 'black_count',
         'white_count', 'ruleset_version', 'result_digest', 'finalized_at'
       )
    having count(*) = 12
  ),
  'protocol2_signal_columns', exists (
    select 1
      from information_schema.columns
     where table_schema = 'public' and table_name = 'match_signals_v2'
       and column_name in (
         'id', 'match_id', 'negotiation_epoch', 'sender_id', 'signal_type',
         'sdp', 'protocol_version', 'signal_slot', 'payload_digest',
         'created_at', 'expires_at'
       )
    having count(*) = 11
  ),
  'protocol2_epoch_constraints', (
    select count(*) = 4
      from pg_constraint
     where conname in (
       'matches_negotiation_epoch_budget',
       'match_start_acks_v2_negotiation_epoch_budget',
       'match_result_claims_v2_negotiation_epoch_budget',
       'match_signals_v2_negotiation_epoch_budget'
     )
       and position('negotiation_epoch <= 3' in pg_get_constraintdef(oid)) > 0
  ),
  'protocol2_state_shape_constraints', (
    select count(*) = 5
      from pg_constraint
     where conrelid = to_regclass('public.matches')
       and conname in (
         'matches_protocol_version_allowed',
         'matches_release_contract_shape',
         'matches_release_reconnect_shape',
         'matches_release_terminal_shape',
         'matches_release_terminal_reason_format'
       )
  ),
  'protocol2_game_record_contract', exists (
    select 1
      from information_schema.columns
     where table_schema = 'public' and table_name = 'game_records'
       and column_name = 'result_contract_version'
  ) and exists (
    select 1
      from pg_constraint
     where conrelid = to_regclass('public.game_records')
       and conname = 'game_records_v2_canonical_required'
  ),
  'protocol2_enqueue_rpc',
    to_regprocedure('public.enqueue_or_match_v2(uuid)') is not null,
  'protocol2_claim_rpc',
    to_regprocedure('public.claim_active_match_v2()') is not null,
  'protocol2_cancel_rpc',
    to_regprocedure('public.cancel_waiting_v2(uuid)') is not null,
  'protocol2_ack_rpc',
    to_regprocedure('public.ack_match_started_v2(uuid,integer)') is not null,
  'protocol2_state_rpc',
    to_regprocedure('public.get_release_match_state_v2(uuid)') is not null,
  'protocol2_abandon_rpc',
    to_regprocedure('public.abandon_match_v2(uuid)') is not null,
  'protocol2_resume_rpc',
    to_regprocedure('public.resume_match_v2(uuid,integer)') is not null,
  'protocol2_reconcile_rpc',
    to_regprocedure('public.reconcile_match_v2(uuid)') is not null,
  'protocol2_submit_result_rpc',
    to_regprocedure('public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)') is not null,
  'protocol2_publish_signal_rpc',
    to_regprocedure('public.publish_match_signal_v2(uuid,text,text,integer,integer)') is not null,
  'protocol2_rpc_security_definer', coalesce((
    select count(*) = 11
       and bool_and(p.prosecdef)
       and bool_and(coalesce(p.proconfig, '{}') @> array['search_path=""'])
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proname in (
         'enqueue_or_match_v2', 'claim_active_match_v2', 'cancel_waiting_v2',
         'get_release_match_state_v2', 'ack_match_started_v2', 'abandon_match_v2',
         'resume_match_v2', 'reconcile_match_v2', 'submit_match_result_v2',
         'publish_match_signal_v2', 'run_match_maintenance_v2'
       )
  ), false),
  'protocol2_authenticated_execute',
    coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.enqueue_or_match_v2(uuid)'), 'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.claim_active_match_v2()'), 'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.cancel_waiting_v2(uuid)'), 'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.get_release_match_state_v2(uuid)'), 'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.ack_match_started_v2(uuid,integer)'), 'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.abandon_match_v2(uuid)'), 'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.resume_match_v2(uuid,integer)'), 'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.reconcile_match_v2(uuid)'), 'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)'),
      'EXECUTE'), false)
    and coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.publish_match_signal_v2(uuid,text,text,integer,integer)'),
      'EXECUTE'), false),
  'protocol2_anon_execute_blocked',
    not coalesce(has_function_privilege('anon',
      to_regprocedure('public.enqueue_or_match_v2(uuid)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.claim_active_match_v2()'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.cancel_waiting_v2(uuid)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.get_release_match_state_v2(uuid)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.ack_match_started_v2(uuid,integer)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.abandon_match_v2(uuid)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.resume_match_v2(uuid,integer)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.reconcile_match_v2(uuid)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.submit_match_result_v2(uuid,uuid,text,text,text,jsonb)'),
      'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.publish_match_signal_v2(uuid,text,text,integer,integer)'),
      'EXECUTE'), true),
  'protocol2_maintenance_service_only',
    coalesce(has_function_privilege('service_role',
      to_regprocedure('public.run_match_maintenance_v2(integer)'), 'EXECUTE'), false)
    and not coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.run_match_maintenance_v2(integer)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('anon',
      to_regprocedure('public.run_match_maintenance_v2(integer)'), 'EXECUTE'), true),
  'protocol2_authority_helpers_blocked',
    not coalesce(has_function_privilege('authenticated',
      to_regprocedure('public.release_replay_game_v2(text)'), 'EXECUTE'), true)
    and not coalesce(has_function_privilege('authenticated',
      to_regprocedure(
        'public.release_finalize_result_v2(uuid,public.release_match_status,text,text,text,text,text,integer,integer,text)'
      ), 'EXECUTE'), true),
  'protocol2_tables_rls', coalesce((
    select count(*) = 4 and bool_and(c.relrowsecurity)
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public'
       and c.relname in (
         'match_start_acks_v2', 'match_result_claims_v2',
         'match_results_v2', 'match_signals_v2'
       )
  ), false),
  'protocol2_matches_rls', exists (
    select 1
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname = 'matches' and c.relrowsecurity
  ),
  'protocol2_matches_state_write_blocked',
    not coalesce(has_table_privilege('authenticated',
      to_regclass('public.matches'), 'INSERT'), true)
    and not coalesce(has_table_privilege('authenticated',
      to_regclass('public.matches'), 'UPDATE'), true)
    and not coalesce(has_table_privilege('authenticated',
      to_regclass('public.matches'), 'DELETE'), true),
  'protocol2_queue_direct_access_blocked',
    not coalesce(has_table_privilege('authenticated',
      to_regclass('public.match_queue'), 'SELECT'), true)
    and not coalesce(has_table_privilege('authenticated',
      to_regclass('public.match_queue'), 'INSERT'), true)
    and not coalesce(has_table_privilege('authenticated',
      to_regclass('public.match_queue'), 'UPDATE'), true)
    and not coalesce(has_table_privilege('authenticated',
      to_regclass('public.match_queue'), 'DELETE'), true),
  'protocol2_direct_writes_blocked',
    not exists (
      select 1
        from (values
          ('match_start_acks_v2'),
          ('match_result_claims_v2'),
          ('match_results_v2'),
          ('match_signals_v2')
        ) as table_list(table_name)
        cross join (values ('INSERT'), ('UPDATE'), ('DELETE')) as access_list(privilege)
       where coalesce(has_table_privilege(
         'authenticated',
         to_regclass('public.' || table_list.table_name),
         access_list.privilege
       ), true)
    ),
  'protocol2_anon_table_access_blocked',
    not exists (
      select 1
        from (values
          ('match_start_acks_v2'),
          ('match_result_claims_v2'),
          ('match_results_v2'),
          ('match_signals_v2')
        ) as table_list(table_name)
        cross join (values
          ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')
        ) as access_list(privilege)
       where coalesce(has_table_privilege(
         'anon',
         to_regclass('public.' || table_list.table_name),
         access_list.privilege
       ), true)
    ),
  'protocol2_participant_reads',
    coalesce(has_table_privilege('authenticated',
      to_regclass('public.match_results_v2'), 'SELECT'), false)
    and coalesce(has_table_privilege('authenticated',
      to_regclass('public.match_signals_v2'), 'SELECT'), false),
  'protocol2_raw_evidence_private',
    not coalesce(has_table_privilege('authenticated',
      to_regclass('public.match_start_acks_v2'), 'SELECT'), true)
    and not coalesce(has_table_privilege('authenticated',
      to_regclass('public.match_result_claims_v2'), 'SELECT'), true),
  'protocol2_realtime_read_policies', (
    select count(*) = 3
      from pg_policies
     where schemaname = 'public' and cmd = 'SELECT'
       and (
         (tablename = 'match_notifications'
           and policyname = 'participants receive own match notifications')
         or (tablename = 'match_results_v2'
           and policyname = 'participants read release match results')
         or (tablename = 'match_signals_v2'
           and policyname = 'participants read live release signaling')
       )
  ),
  'protocol2_notifications_rls', exists (
    select 1
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname = 'match_notifications'
       and c.relrowsecurity
  ),
  'protocol2_signal_sequence_private',
    not coalesce(has_sequence_privilege('authenticated',
      to_regclass('public.match_signals_v2_id_seq'), 'USAGE'), true),
  'protocol2_notifications_realtime', exists (
    select 1
      from pg_publication_tables
     where pubname = 'supabase_realtime'
       and schemaname = 'public' and tablename = 'match_notifications'
  ),
  'protocol2_signals_realtime', exists (
    select 1
      from pg_publication_tables
     where pubname = 'supabase_realtime'
       and schemaname = 'public' and tablename = 'match_signals_v2'
  )
) || jsonb_build_object(
  'verification_retired',
    to_regclass('public.federation_credentials') is null
    and to_regclass('public.verification_submissions') is null
    and to_regtype('public.credential_status') is null
    and not exists (select 1 from storage.objects where bucket_id = 'verification'),
  'record_final_hash', exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'game_records' and column_name = 'final_position_hash'
  ),
  'deletion_request_rpc', to_regprocedure('public.request_account_deletion()') is not null,
  'deletion_prepare_rpc', to_regprocedure('public.prepare_account_deletion(uuid)') is not null,
  'deletion_complete_rpc', to_regprocedure('public.complete_account_deletion(uuid)') is not null,
  'research_private_schema', to_regnamespace('research_private') is not null,
  'research_status_rpc', to_regprocedure('public.get_research_participation_status()') is not null,
  'research_participation_rpc', to_regprocedure('public.set_research_participation(boolean,integer)') is not null,
  'research_compact_source',
    to_regclass('research_private.games') is not null
    and to_regclass('research_private.game_contributors') is not null,
  'research_validator_rpc',
    to_regprocedure('public.claim_research_validation_batch(integer,integer)') is not null
    and to_regprocedure('public.complete_research_validation(bigint,uuid,integer,boolean,text,integer,integer)') is not null,
  'research_validator_service_only',
    not has_function_privilege('authenticated', 'public.claim_research_validation_batch(integer,integer)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.complete_research_validation(bigint,uuid,integer,boolean,text,integer,integer)', 'EXECUTE')
    and has_function_privilege('service_role', 'public.claim_research_validation_batch(integer,integer)', 'EXECUTE')
    and has_function_privilege('service_role', 'public.complete_research_validation(bigint,uuid,integer,boolean,text,integer,integer)', 'EXECUTE'),
  'research_aggregate_tables',
    to_regclass('research_private.positions') is not null
    and to_regclass('research_private.aggregation_generations') is not null
    and to_regclass('research_private.subject_position_totals') is not null
    and to_regclass('research_private.subject_position_moves') is not null
    and to_regclass('research_private.position_aggregates') is not null
    and to_regclass('research_private.move_aggregates') is not null
    and to_regclass('research_private.published_generation') is not null,
  'research_position_rpc', to_regprocedure('public.get_research_position(text,text)') is not null,
  'research_aggregation_service_only',
    not has_function_privilege('authenticated', 'public.claim_research_aggregation_build(integer)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.get_research_aggregation_sources(bigint,uuid,bigint,integer)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.append_research_aggregation_game(bigint,uuid,bigint,jsonb)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.publish_research_aggregation(bigint,uuid)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.fail_research_aggregation(bigint,uuid,text)', 'EXECUTE')
    and has_function_privilege('service_role', 'public.claim_research_aggregation_build(integer)', 'EXECUTE')
    and has_function_privilege('service_role', 'public.publish_research_aggregation(bigint,uuid)', 'EXECUTE'),
  'research_batch_role_limited', exists (
    select 1 from pg_roles
     where rolname = 'research_batch'
       and not rolsuper and not rolcreatedb and not rolcreaterole
  )
    and has_schema_privilege('research_batch', 'research_private', 'USAGE')
    and not has_table_privilege('research_batch', 'research_private.games', 'SELECT')
    and not has_table_privilege('research_batch', 'research_private.game_contributors', 'SELECT')
    and not has_table_privilege('research_batch', 'auth.users', 'SELECT')
    and has_function_privilege('research_batch', 'research_private.batch_claim_validation(integer,integer)', 'EXECUTE')
    and has_function_privilege('research_batch', 'research_private.batch_claim_aggregation(integer)', 'EXECUTE')
    and not has_function_privilege('research_batch', 'public.prepare_account_deletion(uuid)', 'EXECUTE')
    and not has_function_privilege('research_batch', 'public.unlink_research_subject(uuid)', 'EXECUTE')
    and not exists (
      select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
       where n.nspname in ('public', 'research_private')
         and has_function_privilege('research_batch', p.oid, 'EXECUTE')
         and p.oid <> all(array[
           'research_private.batch_claim_validation(integer,integer)'::regprocedure,
           'research_private.batch_complete_validation(bigint,uuid,integer,boolean,text,integer,integer)'::regprocedure,
           'research_private.batch_claim_aggregation(integer)'::regprocedure,
           'research_private.batch_get_aggregation_sources(bigint,uuid,integer)'::regprocedure,
           'research_private.batch_append_aggregation_game(bigint,uuid,bigint,jsonb)'::regprocedure,
           'research_private.batch_checkpoint_aggregation(bigint,uuid)'::regprocedure,
           'research_private.batch_publish_aggregation(bigint,uuid)'::regprocedure,
           'research_private.batch_fail_aggregation(bigint,uuid,text)'::regprocedure
         ])
    ),
  'research_collection_disabled', exists (
    select 1 from research_private.policy_versions where is_active and not collection_enabled
  ),
  'research_private_acl',
    not has_schema_privilege('authenticated', 'research_private', 'USAGE')
    and not has_schema_privilege('anon', 'research_private', 'USAGE')
    and not has_table_privilege('authenticated', 'research_private.games', 'SELECT')
    and not has_table_privilege('authenticated', 'research_private.game_contributors', 'SELECT')
    and not has_table_privilege('authenticated', 'research_private.subject_position_totals', 'SELECT')
    and not has_table_privilege('authenticated', 'research_private.move_aggregates', 'SELECT'),
  'profiles_private',
    not has_table_privilege('anon', 'public.profiles', 'SELECT')
    and not has_table_privilege('authenticated', 'public.profiles', 'SELECT')
    and not has_table_privilege('authenticated', 'public.profiles', 'INSERT')
    and not has_table_privilege('authenticated', 'public.profiles', 'UPDATE')
    and not has_table_privilege('authenticated', 'public.profiles', 'DELETE'),
  'public_profiles_retired', to_regclass('public.public_profiles') is null,
  'match_rating_snapshots', exists (
    select 1
      from information_schema.columns
     where table_schema = 'public'
       and table_name = 'matches'
       and column_name in ('black_rating_at_start', 'white_rating_at_start')
    having count(*) = 2
  ),
  'ratings_select', has_table_privilege('authenticated', 'public.ratings', 'SELECT'),
  'history_select', has_table_privilege('authenticated', 'public.rating_history', 'SELECT'),
  'records_select', has_table_privilege('authenticated', 'public.game_records', 'SELECT'),
  'profile_free_text_retired', not exists (
    select 1 from information_schema.columns
     where table_schema = 'public' and table_name = 'profiles'
       and column_name in ('display_name', 'created_at', 'updated_at')
  )
) as hosted_contract,
jsonb_build_object(
  'match_signaling_table', to_regclass('public.match_signaling') is not null,
  'match_signaling_realtime', exists (
    select 1
      from pg_publication_tables
     where pubname = 'supabase_realtime'
       and schemaname = 'public' and tablename = 'match_signaling'
  ),
  'enqueue_or_match_rpc', to_regprocedure('public.enqueue_or_match()') is not null,
  'ack_match_started_rpc',
    to_regprocedure('public.ack_match_started(uuid)') is not null,
  'submit_match_result_rpc',
    to_regprocedure('public.submit_match_result(uuid,text,text,text,text,jsonb)') is not null,
  'run_legacy_match_maintenance_rpc',
    to_regprocedure('public.run_legacy_match_maintenance_v1(integer)') is not null,
  'match_signaling_select', coalesce(has_table_privilege('authenticated',
    to_regclass('public.match_signaling'), 'SELECT'), false),
  'match_signaling_insert', coalesce(has_table_privilege('authenticated',
    to_regclass('public.match_signaling'), 'INSERT'), false),
  'match_signaling_sequence', coalesce(has_sequence_privilege('authenticated',
    to_regclass('public.match_signaling_id_seq'), 'USAGE'), false)
) as protocol1_compatibility;

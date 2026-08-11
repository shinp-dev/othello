-- Run in the hosted project's SQL Editor after every migration has been applied.
-- Every value in hosted_contract must be true, except realtime_tables which must be 2.
select jsonb_build_object(
  'matches_table', to_regclass('public.matches') is not null,
  'signaling_table', to_regclass('public.match_signaling') is not null,
  'verification_bucket', exists (
    select 1
    from storage.buckets
    where id = 'verification'
      and public = false
      and file_size_limit = 5242880
      and allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp']::text[]
  ),
  'realtime_tables', (
    select count(*)
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename in ('match_notifications', 'match_signaling')
  ),
  'ack_rpc', to_regprocedure('public.ack_match_started(uuid)') is not null,
  'result_rpc', to_regprocedure('public.submit_match_result(uuid,text,text,text,text,jsonb)') is not null,
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
  'research_collection_disabled', exists (
    select 1 from research_private.policy_versions where is_active and not collection_enabled
  ),
  'research_private_acl',
    not has_schema_privilege('authenticated', 'research_private', 'USAGE')
    and not has_schema_privilege('anon', 'research_private', 'USAGE'),
  'profiles_select', has_table_privilege('authenticated', 'public.profiles', 'SELECT'),
  'profile_name_update', has_column_privilege('authenticated', 'public.profiles', 'display_name', 'UPDATE'),
  'ratings_select', has_table_privilege('authenticated', 'public.ratings', 'SELECT'),
  'history_select', has_table_privilege('authenticated', 'public.rating_history', 'SELECT'),
  'records_select', has_table_privilege('authenticated', 'public.game_records', 'SELECT'),
  'credentials_insert', has_table_privilege('authenticated', 'public.federation_credentials', 'INSERT'),
  'signaling_select', has_table_privilege('authenticated', 'public.match_signaling', 'SELECT'),
  'signaling_insert', has_table_privilege('authenticated', 'public.match_signaling', 'INSERT'),
  'signaling_sequence', has_sequence_privilege('authenticated', 'public.match_signaling_id_seq', 'USAGE')
) as hosted_contract;

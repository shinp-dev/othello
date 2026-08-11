-- Run with `supabase test db` against local Supabase/Postgres + pgTAP.
begin;
select plan(276);

select ok(not has_function_privilege('anon', 'public.prune_user_game_records(uuid)', 'execute'), 'anon cannot execute prune_user_game_records');
select ok(not has_function_privilege('authenticated', 'public.prune_user_game_records(uuid)', 'execute'), 'authenticated cannot execute prune_user_game_records');
select ok(not exists (
  select 1 from pg_proc p
   cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
   where p.oid = 'public.prune_user_game_records(uuid)'::regprocedure and acl.grantee = 0 and acl.privilege_type = 'EXECUTE'
), 'PUBLIC has no execute ACL for prune_user_game_records');
select ok(exists (select 1 from pg_constraint where conrelid = 'public.active_match_participants'::regclass and contype = 'p'), 'active reservations have a user primary key');
select ok(to_regprocedure('public.abandon_match(uuid)') is not null, 'abandon_match RPC exists');
select ok(exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'matches' and column_name = 'created_expires_at'), 'CREATED matches have a lease column');
select ok(to_regprocedure('public.get_verification_evidence_cleanup(uuid)') is not null, 'evidence cleanup retry RPC exists');
select ok(exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'game_records' and column_name = 'final_position_hash'), 'game records persist the verified final position hash');
select ok(to_regprocedure('public.prepare_account_deletion(uuid)') is not null, 'trusted account deletion preparation RPC exists');
select ok(to_regprocedure('public.complete_account_deletion(uuid)') is not null, 'trusted account deletion completion RPC exists');
select ok(not has_function_privilege('authenticated', 'public.prepare_account_deletion(uuid)', 'execute'), 'authenticated users cannot run account deletion preparation');
select ok(not has_function_privilege('authenticated', 'public.complete_account_deletion(uuid)', 'execute'), 'authenticated users cannot complete account deletion');
select ok(exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'profiles' and column_name = 'deleted_at'), 'profiles support anonymous deletion tombstones');
select ok(not exists (
  select 1 from pg_constraint
   where conrelid = 'public.profiles'::regclass and confrelid = 'auth.users'::regclass and contype = 'f'
), 'shared profile tombstones do not cascade with Auth deletion');
select ok(exists (select 1 from storage.buckets where id = 'verification' and public = false), 'verification bucket is private and migration-managed');
select ok((select file_size_limit from storage.buckets where id = 'verification') = 5242880, 'verification bucket limits objects to 5 MiB');
select ok((select allowed_mime_types from storage.buckets where id = 'verification') = array['image/jpeg', 'image/png', 'image/webp']::text[], 'verification bucket allows only image MIME types');
select ok(exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'verification objects owner insert' and 'authenticated' = any(roles)), 'verification upload policy is authenticated-only');
select ok(exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'verification objects owner read' and 'authenticated' = any(roles)), 'verification read policy is owner-scoped, not public');
select ok(has_table_privilege('authenticated', 'public.profiles', 'select'), 'authenticated can read RLS-scoped profiles');
select ok(has_column_privilege('authenticated', 'public.profiles', 'display_name', 'update'), 'authenticated can update only the profile display name column');
select ok(not has_table_privilege('authenticated', 'public.profiles', 'update'), 'authenticated has no table-wide profile update privilege');
select ok(has_table_privilege('authenticated', 'public.ratings', 'select'), 'authenticated can read RLS-scoped ratings');
select ok(not has_table_privilege('authenticated', 'public.ratings', 'update'), 'authenticated cannot update ratings');
select ok(has_table_privilege('authenticated', 'public.rating_history', 'select'), 'authenticated can read RLS-scoped rating history');
select ok(has_table_privilege('authenticated', 'public.game_records', 'select'), 'authenticated can read RLS-scoped game records');
select ok(has_table_privilege('authenticated', 'public.federation_credentials', 'select'), 'authenticated can read own credentials');
select ok(has_table_privilege('authenticated', 'public.federation_credentials', 'insert'), 'authenticated can self-declare credentials');
select ok(has_table_privilege('authenticated', 'public.match_signaling', 'select'), 'authenticated participants can read signaling');
select ok(has_table_privilege('authenticated', 'public.match_signaling', 'insert'), 'authenticated participants can publish signaling');
select ok(not has_table_privilege('authenticated', 'public.match_signaling', 'update'), 'authenticated cannot rewrite signaling');
select ok(not has_table_privilege('authenticated', 'public.match_signaling', 'delete'), 'authenticated cannot delete signaling');
select ok(has_table_privilege('authenticated', 'public.match_notifications', 'select'), 'authenticated can receive own match notifications');
select ok(has_sequence_privilege('authenticated', 'public.match_signaling_id_seq', 'usage'), 'authenticated can allocate signaling identity values');
select ok(has_table_privilege('anon', 'public.public_profiles', 'select'), 'anon can read the sanitized public profile view');
select ok(not has_table_privilege('anon', 'public.profiles', 'select'), 'anon cannot read the base profile table');
select ok(not has_table_privilege('authenticated', 'public.active_match_participants', 'select'), 'active reservations remain RPC-only');
select ok(to_regprocedure('public.ack_match_started(uuid)') is not null, 'start ack RPC exists');
select ok(to_regprocedure('public.get_match_start_state(uuid)') is not null, 'participant start state RPC exists');
select ok(position('delete from public.active_match_participants' in pg_get_functiondef('public.cleanup_stale_created_matches()'::regprocedure)) = 0, 'signaling cleanup never deletes reservations directly');
select ok(position('cleanup_terminal_matches' in pg_get_functiondef('public.enqueue_or_match()'::regprocedure)) = 0, 'terminal cleanup is outside matchmaking hot path');
select ok(position('cleanup_stale_created_matches' in pg_get_functiondef('public.enqueue_or_match()'::regprocedure)) = 0, 'global signaling cleanup is outside matchmaking hot path');
select ok(not exists (
  select 1 from pg_proc where prosecdef and pronamespace = 'public'::regnamespace
    and proconfig @> array['search_path=public']
), 'public SECURITY DEFINER functions do not use search_path=public');
select ok(to_regnamespace('research_private') is not null, 'private research schema exists');
select ok(to_regclass('research_private.consent_versions') is not null, 'research consent versions exist');
select ok(to_regclass('research_private.policy_versions') is not null, 'research policy versions exist');
select ok(to_regclass('research_private.research_subjects') is not null, 'research subjects exist');
select ok(to_regclass('research_private.participation_periods') is not null, 'research participation periods exist');
select ok(to_regclass('research_private.games') is not null, 'private compact research games exist');
select ok(to_regclass('research_private.game_contributors') is not null, 'private research contributors exist');
select ok(to_regclass('research_private.positions') is not null, 'research position dictionary exists');
select ok(to_regclass('research_private.aggregation_generations') is not null, 'research aggregate generations exist');
select ok(to_regclass('research_private.subject_position_totals') is not null, 'private subject-position totals exist');
select ok(to_regclass('research_private.move_aggregates') is not null, 'private published move aggregates exist');
select ok(to_regclass('research_private.published_generation') is not null, 'atomic published generation pointer exists');
select ok(to_regprocedure('public.get_research_position(text,text)') is not null, 'privacy-safe research position RPC exists');
select ok(not exists (
  select 1 from pg_constraint
   where conrelid = 'research_private.research_subjects'::regclass
     and confrelid in ('auth.users'::regclass, 'public.profiles'::regclass)
), 'research subjects have no Auth or profile foreign key');
select is((select consent_version from research_private.consent_versions where consent_version = 1), 1, 'research consent version 1 is seeded');
select ok((select not collection_enabled and eligibility_min_games = 10 and eligibility_window_days = 90
  from research_private.policy_versions where is_active), 'active research policy is seeded with collection disabled');
select ok(not has_schema_privilege('authenticated', 'research_private', 'usage'), 'authenticated cannot use the private research schema');
select ok(not has_schema_privilege('anon', 'research_private', 'usage'), 'anon cannot use the private research schema');
select ok(
  (select bool_and(not has_table_privilege('authenticated', table_name, 'select')) from (values
    ('research_private.consent_versions'), ('research_private.policy_versions'),
    ('research_private.research_subjects'), ('research_private.participation_periods'),
    ('research_private.games'), ('research_private.game_contributors'),
    ('research_private.positions'), ('research_private.aggregation_generations'),
    ('research_private.aggregation_segments'), ('research_private.generation_processed_games'),
    ('research_private.subject_position_totals'), ('research_private.subject_position_moves'),
    ('research_private.position_aggregates'), ('research_private.move_aggregates'),
    ('research_private.published_generation')
  ) private_tables(table_name)),
  'authenticated cannot directly read any private research table'
);
select ok(
  (select bool_and(
    not has_table_privilege('authenticated', table_name, 'insert')
    and not has_table_privilege('authenticated', table_name, 'update')
    and not has_table_privilege('authenticated', table_name, 'delete')
  ) from (values
    ('research_private.consent_versions'), ('research_private.policy_versions'),
    ('research_private.research_subjects'), ('research_private.participation_periods'),
    ('research_private.games'), ('research_private.game_contributors'),
    ('research_private.positions'), ('research_private.aggregation_generations'),
    ('research_private.aggregation_segments'), ('research_private.generation_processed_games'),
    ('research_private.subject_position_totals'), ('research_private.subject_position_moves'),
    ('research_private.position_aggregates'), ('research_private.move_aggregates'),
    ('research_private.published_generation')
  ) private_tables(table_name)),
  'authenticated cannot directly write any private research table'
);
select ok(
  (select bool_and(
    not has_table_privilege('anon', table_name, 'select')
    and not has_table_privilege('anon', table_name, 'insert')
    and not has_table_privilege('anon', table_name, 'update')
    and not has_table_privilege('anon', table_name, 'delete')
  ) from (values
    ('research_private.consent_versions'), ('research_private.policy_versions'),
    ('research_private.research_subjects'), ('research_private.participation_periods'),
    ('research_private.games'), ('research_private.game_contributors'),
    ('research_private.positions'), ('research_private.aggregation_generations'),
    ('research_private.aggregation_segments'), ('research_private.generation_processed_games'),
    ('research_private.subject_position_totals'), ('research_private.subject_position_moves'),
    ('research_private.position_aggregates'), ('research_private.move_aggregates'),
    ('research_private.published_generation')
  ) private_tables(table_name)),
  'anon cannot directly read or write any private research table'
);
select ok(
  (select bool_and(c.relrowsecurity) from pg_class c where c.oid in (
    'research_private.consent_versions'::regclass,
    'research_private.policy_versions'::regclass,
    'research_private.research_subjects'::regclass,
    'research_private.participation_periods'::regclass,
    'research_private.games'::regclass,
    'research_private.game_contributors'::regclass,
    'research_private.positions'::regclass,
    'research_private.aggregation_generations'::regclass,
    'research_private.aggregation_segments'::regclass,
    'research_private.generation_processed_games'::regclass,
    'research_private.subject_position_totals'::regclass,
    'research_private.subject_position_moves'::regclass,
    'research_private.position_aggregates'::regclass,
    'research_private.move_aggregates'::regclass,
    'research_private.published_generation'::regclass
  )),
  'RLS is enabled on every private research table'
);
select ok(has_function_privilege('authenticated', 'public.get_research_participation_status()', 'execute'), 'authenticated can read research status through RPC');
select ok(has_function_privilege('authenticated', 'public.set_research_participation(boolean,integer)', 'execute'), 'authenticated can change research participation through RPC');
select ok(not has_function_privilege('anon', 'public.get_research_participation_status()', 'execute'), 'anon cannot read research status');
select ok(not has_function_privilege('anon', 'public.set_research_participation(boolean,integer)', 'execute'), 'anon cannot change research participation');
select ok(not has_function_privilege('authenticated', 'public.claim_research_validation_batch(integer,integer)', 'execute'), 'authenticated cannot claim research validation jobs');
select ok(not has_function_privilege('authenticated', 'public.complete_research_validation(bigint,uuid,integer,boolean,text,integer,integer)', 'execute'), 'authenticated cannot complete research validation jobs');
select ok(not has_function_privilege('anon', 'public.claim_research_validation_batch(integer,integer)', 'execute'), 'anon cannot claim research validation jobs');
select ok(has_function_privilege('service_role', 'public.claim_research_validation_batch(integer,integer)', 'execute'), 'service role can claim research validation jobs');
select ok(has_function_privilege('service_role', 'public.complete_research_validation(bigint,uuid,integer,boolean,text,integer,integer)', 'execute'), 'service role can complete research validation jobs');
select ok(has_function_privilege('authenticated', 'public.get_research_position(text,text)', 'execute'), 'authenticated can call the privacy-safe research position RPC');
select ok(not has_function_privilege('anon', 'public.get_research_position(text,text)', 'execute'), 'anon cannot call the research position RPC');
select ok(not has_function_privilege('authenticated', 'public.claim_research_aggregation_build(integer)', 'execute'), 'authenticated cannot claim aggregate builds');
select ok(not has_function_privilege('authenticated', 'public.get_research_aggregation_sources(bigint,uuid,bigint,integer)', 'execute'), 'authenticated cannot read aggregate source pages');
select ok(not has_function_privilege('authenticated', 'public.append_research_aggregation_game(bigint,uuid,bigint,jsonb)', 'execute'), 'authenticated cannot append aggregate decisions');
select ok(not has_function_privilege('authenticated', 'public.publish_research_aggregation(bigint,uuid)', 'execute'), 'authenticated cannot publish aggregate generations');
select ok(not has_function_privilege('authenticated', 'public.fail_research_aggregation(bigint,uuid,text)', 'execute'), 'authenticated cannot fail aggregate generations');
select ok(exists (
  select 1 from pg_roles where rolname = 'research_batch' and not rolcanlogin
    and not rolsuper and not rolcreatedb and not rolcreaterole
), 'research batch executor is a least-privilege NOLOGIN role by default');
select ok(has_schema_privilege('research_batch', 'research_private', 'usage')
  and not has_table_privilege('research_batch', 'research_private.games', 'select')
  and not has_table_privilege('research_batch', 'auth.users', 'select'),
  'research batch executor can reach wrappers but cannot read research or Auth tables');
select ok(has_function_privilege('research_batch', 'research_private.batch_claim_validation(integer,integer)', 'execute')
  and has_function_privilege('research_batch', 'research_private.batch_claim_aggregation(integer)', 'execute')
  and has_function_privilege('research_batch', 'research_private.batch_checkpoint_aggregation(bigint,uuid)', 'execute'),
  'research batch executor can invoke only the bounded batch surface');
select ok(not has_function_privilege('research_batch', 'public.claim_research_validation_batch(integer,integer)', 'execute')
  and not has_function_privilege('research_batch', 'public.prepare_account_deletion(uuid)', 'execute')
  and not has_function_privilege('research_batch', 'public.unlink_research_subject(uuid)', 'execute'),
  'research batch executor cannot invoke service-role or account deletion operations directly');
select is((
  select count(*)::integer
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
   where n.nspname in ('public', 'research_private')
     and has_function_privilege('research_batch', p.oid, 'execute')
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
), 0, 'research batch executor has no ambient application-function access');
select ok(not has_function_privilege('authenticated', 'research_private.batch_claim_validation(integer,integer)', 'execute'),
  'authenticated users cannot invoke the Actions batch surface');
set role research_batch;
create temporary table disabled_batch_claim on commit drop as
select count(*)::int as claimed_count from research_private.batch_claim_validation(1, 300);
reset role;
select is((select claimed_count from disabled_batch_claim), 0,
  'collection disabled makes the Actions validation claim a no-op');
select ok(not exists (
  select 1 from pg_constraint
   where conrelid = 'research_private.games'::regclass
     and confrelid in ('public.matches'::regclass, 'public.game_records'::regclass, 'public.profiles'::regclass, 'auth.users'::regclass)
), 'compact research games do not depend on match, GameRecord, profile, or Auth retention');
select ok(not exists (
  select 1 from information_schema.columns
   where table_schema = 'research_private'
     and table_name in ('games', 'game_contributors')
     and column_name in ('account_user_id', 'user_id', 'player_id', 'opponent_id', 'match_id')
), 'long-lived research source and contributors store no account or match identity column');
select ok(exists (
  select 1 from pg_trigger
   where tgrelid = 'public.matches'::regclass
     and tgname = 'capture_confirmed_match_for_research'
     and not tgisinternal
), 'CONFIRMED research capture trigger exists');

-- Auth trigger fixtures. These are rolled back by Supabase's pgTAP runner.
insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data)
values
 ('00000000-0000-0000-0000-000000000001', 'authenticated', 'authenticated', 'a@example.test', '', now(), '{}', '{"display_name":"a"}'),
 ('00000000-0000-0000-0000-000000000002', 'authenticated', 'authenticated', 'b@example.test', '', now(), '{}', '{"display_name":"b"}'),
 ('00000000-0000-0000-0000-000000000003', 'authenticated', 'authenticated', 'c@example.test', '', now(), '{}', '{"display_name":"c"}'),
 ('00000000-0000-0000-0000-000000000004', 'authenticated', 'authenticated', 'd@example.test', '', now(), '{}', '{"display_name":"d"}'),
 ('00000000-0000-0000-0000-000000000007', 'authenticated', 'authenticated', 'private-name@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000008', 'authenticated', 'authenticated', 'research-black@example.test', '', now(), '{}', '{"display_name":"research-black"}'),
 ('00000000-0000-0000-0000-000000000009', 'authenticated', 'authenticated', 'research-white@example.test', '', now(), '{}', '{"display_name":"research-white"}')
on conflict (id) do nothing;
select is((select display_name from public.profiles where id = '00000000-0000-0000-0000-000000000007'), 'プレイヤー', 'default public display name never derives from email');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000007', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
select ok(not (select participation_on from public.get_research_participation_status()), 'research participation starts OFF');
select ok((select participation_on from public.set_research_participation(true, 1)), 'current consent opt-in turns participation ON');
select is((select count(*)::int from research_private.research_subjects where account_user_id = '00000000-0000-0000-0000-000000000007'), 1, 'opt-in links exactly one research subject');
select ok((select research_subject_id <> account_user_id from research_private.research_subjects where account_user_id = '00000000-0000-0000-0000-000000000007'), 'research subject ID is independent from account identity');
select is((select count(*)::int from research_private.participation_periods where ended_at is null), 1, 'opt-in creates one open participation period');
select public.set_research_participation(true, 1);
select is((select count(*)::int from research_private.participation_periods), 1, 'repeated valid opt-in is idempotent');
select throws_ok(
  $$insert into research_private.research_subjects(account_user_id) values ('00000000-0000-0000-0000-000000000007')$$,
  '23505', null, 'one account cannot have multiple linked research subjects'
);
select throws_ok(
  $$insert into research_private.participation_periods(research_subject_id, policy_version_at_start, consent_version)
    select s.research_subject_id, p.policy_version, p.research_consent_version
      from research_private.research_subjects s cross join research_private.policy_versions p
     where s.account_user_id = '00000000-0000-0000-0000-000000000007' and p.is_active$$,
  '23505', null, 'one research subject cannot have multiple open participation periods'
);
select ok(not (select participation_on from public.set_research_participation(false)), 'opt-out turns participation OFF');
select ok(not (select can_view_research_data from public.get_research_participation_status()), 'opt-out cannot retain research viewing eligibility');
select is((select count(*)::int from research_private.participation_periods where ended_at is null), 0, 'opt-out closes the current period');
select ok((select participation_on from public.set_research_participation(true, 1)), 're-opt-in starts participation again');
select is((select count(*)::int from research_private.participation_periods), 2, 're-opt-in creates a new period instead of reopening the old one');

insert into research_private.consent_versions(consent_version, effective_at, document_sha256, summary)
values (2, now(), repeat('2', 64), 'test-only consent version 2');
update research_private.policy_versions set is_active = false where is_active;
insert into research_private.policy_versions(
  effective_at, research_consent_version, eligibility_min_games, eligibility_window_days,
  position_min_users, move_min_users, min_decisions_per_qualifying_game,
  ruleset_version, normalization_version, collection_enabled, is_active
) values (now(), 2, 10, 90, 100, 20, 10, 1, 1, false, true);
select ok(not (select participation_on from public.get_research_participation_status()), 'consent mismatch is not active participation');
select ok((select reconsent_required from public.get_research_participation_status()), 'consent mismatch requires re-consent');
select ok(not (select collection_allowed from public.get_research_participation_status()), 'consent mismatch cannot collect research data');
select ok((select participation_on from public.set_research_participation(true, 2)), 're-consent starts a current-version period');
select is((select count(*)::int from research_private.participation_periods), 3, 're-consent creates another new period');
select is((select count(*)::int from research_private.participation_periods where ended_at is null and consent_version = 2), 1, 'only one current-consent period remains open');

create function pg_temp.create_research_match(p_match_id uuid)
returns void language plpgsql as $$
begin
  insert into public.matches(id, black_player, white_player, status, server_status)
  values (
    p_match_id,
    '00000000-0000-0000-0000-000000000008',
    '00000000-0000-0000-0000-000000000009',
    'PLAYING', 'CREATED'
  );
  insert into public.game_records(
    match_id, players, moves, canonical_moves, result, started_at, finished_at,
    time_control, finish_reason, final_position_hash, expires_at
  ) values (
    p_match_id,
    array['00000000-0000-0000-0000-000000000008'::uuid, '00000000-0000-0000-0000-000000000009'::uuid],
    '""'::jsonb, '', 'BLACK_WIN', now() - interval '1 minute', now(),
    '5m', 'RESIGNATION', '793fe16fd644d2b3:1:0:0', now() + interval '7 days'
  );
  insert into public.rating_history(user_id, match_id, rating, delta, algorithm_version)
  values
    ('00000000-0000-0000-0000-000000000008', p_match_id, 1510, 10, 'elo-v1'),
    ('00000000-0000-0000-0000-000000000009', p_match_id, 1490, -10, 'elo-v1');
  update public.matches
     set server_status = 'CONFIRMED', confirmed_at = now()
   where id = p_match_id;
end;
$$;

select set_config('request.jwt.claim.role', 'authenticated', false);
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000008', false);
select public.set_research_participation(true, 2);
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000009', false);
select public.set_research_participation(true, 2);
select pg_temp.create_research_match('00000000-0000-0000-0000-000000000201');
select is((select count(*)::int from research_private.games), 0, 'collection disabled captures no CONFIRMED research game');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000008', false);
select public.set_research_participation(false);
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000009', false);
select public.set_research_participation(false);
update research_private.policy_versions set collection_enabled = true where is_active;
select pg_temp.create_research_match('00000000-0000-0000-0000-000000000202');
select is((select count(*)::int from research_private.games), 0, 'both OFF creates no research source or contributor');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000008', false);
select public.set_research_participation(true, 2);
select pg_temp.create_research_match('00000000-0000-0000-0000-000000000203');
select is((select count(*)::int from research_private.games), 1, 'Black ON and White OFF creates one compact source');
select is((select count(*)::int from research_private.game_contributors), 1, 'Black ON and White OFF creates one contributor');
select is((select disc from research_private.game_contributors), 'BLACK', 'only the opted-in Black subject is captured');
select ok((select rating_before = 1500 and rating_algorithm_version = 'elo-v1' from research_private.game_contributors), 'rating before is copied from server rating history minus delta');
select ok((select source_kind = 'ONLINE' and canonical_moves = '' and result = 'BLACK_WIN'
  and finish_reason = 'RESIGNATION' and final_position_hash = '793fe16fd644d2b3:1:0:0'
  and time_control = '5m' and ruleset_version = 1 and octet_length(source_match_key) = 32
  from research_private.games), 'compact source snapshots all validator inputs without a match identity');
select is((select outcome_from_subject_perspective from research_private.game_contributors), 'WIN', 'contributor outcome is stored from the subject perspective');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000008', false);
select public.set_research_participation(false);
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000009', false);
select public.set_research_participation(true, 2);
select pg_temp.create_research_match('00000000-0000-0000-0000-000000000204');
select is((select count(*)::int from research_private.games), 2, 'Black OFF and White ON creates another compact source');
select ok((select count(*) = 1 and bool_and(disc = 'WHITE')
  from research_private.game_contributors where research_game_id = (select max(research_game_id) from research_private.games)), 'only the opted-in White subject is captured');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000008', false);
select public.set_research_participation(true, 2);
select pg_temp.create_research_match('00000000-0000-0000-0000-000000000205');
select is((select count(*)::int from research_private.games), 3, 'both ON creates one additional compact source');
select is((select count(*)::int from research_private.game_contributors
  where research_game_id = (select max(research_game_id) from research_private.games)), 2, 'both ON captures both research subjects');
select research_private.capture_confirmed_match('00000000-0000-0000-0000-000000000205');
select research_private.capture_confirmed_match('00000000-0000-0000-0000-000000000205');
select is((select count(*)::int from research_private.games), 3, 'repeated capture cannot duplicate a source match');
select is((select count(*)::int from research_private.game_contributors), 4, 'repeated capture cannot duplicate a subject contribution');

select public.request_account_deletion();
select is((select link_state from research_private.research_subjects where account_user_id = '00000000-0000-0000-0000-000000000008'), 'DELETION_PENDING', 'deletion request marks the linked research subject pending');
select is((select count(*)::int from research_private.participation_periods pp join research_private.research_subjects s using (research_subject_id) where s.account_user_id = '00000000-0000-0000-0000-000000000008' and pp.ended_at is null), 0, 'deletion request closes the open research period');
select pg_temp.create_research_match('00000000-0000-0000-0000-000000000206');
select is((select count(*)::int from research_private.games), 4, 'deletion-requested Black does not prevent opted-in White capture');
select ok((select count(*) = 1 and bool_and(disc = 'WHITE')
  from research_private.game_contributors where research_game_id = (select max(research_game_id) from research_private.games)), 'deletion-requested subject is excluded from new capture');

insert into research_private.consent_versions(consent_version, effective_at, document_sha256, summary)
values (3, now(), repeat('3', 64), 'test-only consent version 3');
update research_private.policy_versions set is_active = false where is_active;
insert into research_private.policy_versions(
  effective_at, research_consent_version, eligibility_min_games, eligibility_window_days,
  position_min_users, move_min_users, min_decisions_per_qualifying_game,
  ruleset_version, normalization_version, collection_enabled, is_active
) values (now(), 3, 10, 90, 100, 20, 10, 1, 1, true, true);
select pg_temp.create_research_match('00000000-0000-0000-0000-000000000207');
select is((select count(*)::int from research_private.games), 4, 'consent-version mismatch captures neither participant');

select set_config('request.jwt.claim.role', 'service_role', false);
create temporary table claimed_research_games on commit drop as
select * from public.claim_research_validation_batch(10, 300);
select is((select count(*)::int from claimed_research_games), 4, 'service validator claims every pending compact source once');
select is((select count(distinct research_game_id)::int from claimed_research_games), 4, 'validation batch contains no duplicate game');
select ok((select bool_and(validation_status = 'PROCESSING' and attempt_count = 1)
  from research_private.games), 'claim marks every game processing with one attempt');

select is((
  select public.complete_research_validation(c.research_game_id, c.lease_token, 1, true, null, 28, 32)
    from claimed_research_games c
   where c.research_game_id = (
     select research_game_id from research_private.game_contributors
      group by research_game_id having count(*) = 2 limit 1
   )
), 'ACCEPTED', 'trusted validator accepts a claimed game');
select is((select decision_count from research_private.game_contributors
  where disc = 'BLACK' and research_game_id = (select research_game_id from research_private.game_contributors group by research_game_id having count(*) = 2 limit 1)), 28, 'accepted Black contributor receives only Black decision count');
select is((select decision_count from research_private.game_contributors
  where disc = 'WHITE' and research_game_id = (select research_game_id from research_private.game_contributors group by research_game_id having count(*) = 2 limit 1)), 32, 'accepted White contributor receives only White decision count');
select is((
  select public.complete_research_validation(c.research_game_id, c.lease_token, 1, true, null, 28, 32)
    from claimed_research_games c
   where c.research_game_id = (
     select research_game_id from research_private.game_contributors
      group by research_game_id having count(*) = 2 limit 1
   )
), 'ACCEPTED', 'same validator completion is idempotent');
select ok((select bool_and(contribution_status = 'ACCEPTED' and accepted_at is not null)
  from research_private.game_contributors
  where research_game_id = (select research_game_id from research_private.game_contributors group by research_game_id having count(*) = 2 limit 1)), 'accepted contributors become terminal together');

select is((
  select public.complete_research_validation(c.research_game_id, c.lease_token, 1, false, 'FINAL_HASH_MISMATCH', null, null)
    from claimed_research_games c
   where c.research_game_id = (
     select research_game_id from research_private.game_contributors
      group by research_game_id having count(*) = 1 and bool_and(disc = 'BLACK') limit 1
   )
), 'REJECTED', 'trusted validator rejects an inconsistent claimed game');
select is((select rejection_code from research_private.games
  where research_game_id = (select research_game_id from research_private.game_contributors group by research_game_id having count(*) = 1 and bool_and(disc = 'BLACK') limit 1)), 'FINAL_HASH_MISMATCH', 'safe rejection code is retained for operations');
select ok((select contribution_status = 'REJECTED' and decision_count is null and accepted_at is null
  from research_private.game_contributors
  where research_game_id = (select research_game_id from research_private.game_contributors group by research_game_id having count(*) = 1 and bool_and(disc = 'BLACK') limit 1)), 'rejected contributor cannot enter later aggregate or eligibility');

create temporary table reclaim_target on commit drop as
select c.* from claimed_research_games c
join research_private.games g using (research_game_id)
where g.validation_status = 'PROCESSING'
order by c.research_game_id limit 1;
update research_private.games set lease_expires_at = now() - interval '1 second'
where research_game_id = (select research_game_id from reclaim_target);
create temporary table reclaimed_research_game on commit drop as
select * from public.claim_research_validation_batch(1, 300);
select is((select research_game_id from reclaimed_research_game), (select research_game_id from reclaim_target), 'expired validation lease is reclaimable');
select ok((select r.lease_token <> o.lease_token and g.attempt_count = 2
  from reclaimed_research_game r cross join reclaim_target o
  join research_private.games g on g.research_game_id = r.research_game_id), 'reclaim rotates the lease token and increments attempts');
select throws_ok(
  $$select public.complete_research_validation(
      (select research_game_id from reclaim_target), (select lease_token from reclaim_target),
      1, true, null, 0, 0)$$,
  'P0001', 'validation lease mismatch', 'stale worker cannot complete after another worker reclaims the game'
);
select is((select public.complete_research_validation(
  research_game_id, lease_token, 1, true, null, 0, 0
) from reclaimed_research_game), 'ACCEPTED', 'current lease holder can complete the reclaimed game');

-- Research 2C: eligibility, subject-normalized aggregation, generation publication,
-- and threshold-safe client output. The large subject fixture exists only in this
-- rolled-back pgTAP transaction and does not enable collection in migration seed data.
insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data)
values ('00000000-0000-0000-0000-000000000010', 'authenticated', 'authenticated',
  'research-reader@example.test', '', now(), '{}', '{"display_name":"research-reader"}');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000010', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
select public.set_research_participation(true, 3);
update research_private.participation_periods
   set started_at = now() - interval '100 days'
 where participation_id = (select current_participation_id from public.get_research_participation_status());

create function pg_temp.add_research_eligibility_game(
  p_label text,
  p_validation_status text,
  p_decision_count integer,
  p_confirmed_at timestamptz
) returns bigint language plpgsql as $$
declare new_game_id bigint;
declare subject_id uuid;
declare period_id uuid;
begin
  select s.research_subject_id, pp.participation_id into subject_id, period_id
    from research_private.research_subjects s
    join research_private.participation_periods pp on pp.research_subject_id = s.research_subject_id
   where s.account_user_id = '00000000-0000-0000-0000-000000000010' and pp.ended_at is null;
  insert into research_private.games(
    source_match_key, canonical_moves, result, finish_reason, final_position_hash,
    time_control, confirmed_at, ruleset_version, validation_status, validator_version,
    lease_token, lease_expires_at, processed_at, rejection_code
  ) values (
    digest(p_label, 'sha256'), '', 'BLACK_WIN', 'RESIGNATION', '0000000000000000:1:0:0',
    'RAPID_10M', p_confirmed_at, 1, p_validation_status,
    case when p_validation_status in ('ACCEPTED', 'REJECTED') then 1 end,
    case when p_validation_status in ('ACCEPTED', 'REJECTED') then gen_random_uuid() end,
    null,
    case when p_validation_status in ('ACCEPTED', 'REJECTED') then now() end,
    case when p_validation_status = 'REJECTED' then 'TEST_REJECTED' end
  ) returning research_game_id into new_game_id;
  insert into research_private.game_contributors(
    research_game_id, research_subject_id, participation_id, disc, rating_before,
    rating_algorithm_version, outcome_from_subject_perspective, confirmed_at,
    contribution_status, decision_count, accepted_at
  ) values (
    new_game_id, subject_id, period_id, 'BLACK', 1500, 'elo-v1', 'WIN', p_confirmed_at,
    p_validation_status,
    case when p_validation_status = 'ACCEPTED' then p_decision_count end,
    case when p_validation_status = 'ACCEPTED' then now() end
  );
  return new_game_id;
end;
$$;

select pg_temp.add_research_eligibility_game('eligible-' || i, 'ACCEPTED', 10, now() - interval '1 day')
  from generate_series(1, 9) i;
select is((select qualifying_game_count from public.get_research_participation_status()), 9,
  'nine current-period accepted qualifying games are not yet eligible');
select pg_temp.add_research_eligibility_game('short-accepted', 'ACCEPTED', 9, now() - interval '1 day');
select is((select qualifying_game_count from public.get_research_participation_status()), 9,
  'accepted decision_count 9 contributes no qualifying game');
select pg_temp.add_research_eligibility_game('validator-rejected', 'REJECTED', null, now() - interval '1 day');
select is((select qualifying_game_count from public.get_research_participation_status()), 9,
  'validator rejected contribution contributes no qualifying game');
select pg_temp.add_research_eligibility_game('validator-pending', 'PENDING', null, now() - interval '1 day');
select is((select qualifying_game_count from public.get_research_participation_status()), 9,
  'pending contribution contributes no qualifying game');
select pg_temp.add_research_eligibility_game('window-inclusive', 'ACCEPTED', 10, now() - interval '90 days');
select is((select qualifying_game_count from public.get_research_participation_status()), 10,
  'the exact 90-day lower boundary is inclusive');
select ok((select eligible from public.get_research_participation_status()),
  'ten current-period accepted qualifying games make the caller eligible');
select pg_temp.add_research_eligibility_game('window-too-old', 'ACCEPTED', 10,
  now() - interval '90 days' - interval '1 microsecond');
select is((select qualifying_game_count from public.get_research_participation_status()), 10,
  'a contribution older than the 90-day boundary is excluded');
create temporary table append_source on commit drop as
select pg_temp.add_research_eligibility_game(
  'aggregate-append-source', 'ACCEPTED', 1, now() - interval '1 day'
) as research_game_id;

select set_config('request.jwt.claim.role', 'service_role', false);
create temporary table aggregation_claim on commit drop as
select * from public.claim_research_aggregation_build(900);
select is((select count(*)::int from aggregation_claim), 1, 'service worker claims one aggregate generation');
select is((select status from research_private.aggregation_generations
  where generation_id = (select generation_id from aggregation_claim)), 'BUILDING',
  'claimed aggregate generation remains private while building');
select is((select count(*)::int from research_private.published_generation), 0,
  'a building generation does not create the public generation pointer');
create temporary table aggregation_sources on commit drop as
select * from public.get_research_aggregation_sources(
  (select generation_id from aggregation_claim), (select lease_token from aggregation_claim), 0, 100
);
select ok(not exists (
  select 1 from aggregation_sources s join research_private.games g using (research_game_id)
   where g.validation_status <> 'ACCEPTED'
), 'aggregate source page includes only trusted ACCEPTED games');
select ok((select public.append_research_aggregation_game(
  c.generation_id, c.lease_token, a.research_game_id,
  jsonb_build_array(jsonb_build_object(
    'research_subject_id', s.research_subject_id,
    'black_hex', '0000000000000001', 'white_hex', '0000000000000002',
    'side', 'BLACK', 'legal_move_mask_hex', '0000000000000004',
    'move_index', 2, 'outcome', 'WIN'
  )))
  from aggregation_claim c cross join append_source a
  cross join research_private.research_subjects s
  where s.account_user_id = '00000000-0000-0000-0000-000000000010'),
  'service append records one accepted game exactly once');
select ok(not (select public.append_research_aggregation_game(
  c.generation_id, c.lease_token, a.research_game_id,
  jsonb_build_array(jsonb_build_object(
    'research_subject_id', s.research_subject_id,
    'black_hex', '0000000000000001', 'white_hex', '0000000000000002',
    'side', 'BLACK', 'legal_move_mask_hex', '0000000000000004',
    'move_index', 2, 'outcome', 'WIN'
  )))
  from aggregation_claim c cross join append_source a
  cross join research_private.research_subjects s
  where s.account_user_id = '00000000-0000-0000-0000-000000000010'),
  'service append retry does not duplicate weight');
grant select on aggregation_claim, append_source to research_batch;
set role research_batch;
create temporary table resumed_source_page on commit drop as
select * from research_private.batch_get_aggregation_sources(
  (select generation_id from aggregation_claim), (select lease_token from aggregation_claim), 100
);
reset role;
select ok(not exists (
  select 1 from resumed_source_page source
   where source.research_game_id = (select research_game_id from append_source)
), 'resumable source pages omit games already processed by the generation');

create temporary table aggregate_subjects(n integer primary key, research_subject_id uuid not null unique) on commit drop;
insert into aggregate_subjects select i, gen_random_uuid() from generate_series(1, 100) i;
insert into research_private.research_subjects(research_subject_id, account_user_id, link_state, unlinked_at)
select research_subject_id, null, 'UNLINKED', now() from aggregate_subjects;

create temporary table aggregate_positions(label text primary key, position_id bigint not null) on commit drop;
insert into aggregate_positions values
  ('parent', research_private.upsert_position(1, 1, '0000000810000000', '0000001008000000', 'BLACK', '0000102004080000')),
  ('child99', research_private.upsert_position(1, 1, '0000000818080000', '0000001000000000', 'WHITE', '0000000000000001')),
  ('child100', research_private.upsert_position(1, 1, '000000001c000000', '0000001c00000000', 'WHITE', '0000000000000001'));

insert into research_private.subject_position_totals(
  generation_id, segment_key, position_id, research_subject_id, occurrence_count
)
select c.generation_id, 'ALL', p.position_id, s.research_subject_id,
       case when s.n = 1 then 100 when s.n in (2, 21) then 2 else 1 end
  from aggregation_claim c cross join aggregate_subjects s
  cross join aggregate_positions p where p.label = 'parent';
insert into research_private.subject_position_moves(
  generation_id, segment_key, position_id, research_subject_id, move_index,
  choice_count, win_count, draw_count, loss_count, child_position_id
)
select c.generation_id, 'ALL', parent.position_id, s.research_subject_id, 26,
       case when s.n = 1 then 80 else 1 end,
       case when s.n = 1 then 80 else 1 end, 0, 0, child99.position_id
  from aggregation_claim c cross join aggregate_subjects s
  cross join aggregate_positions parent cross join aggregate_positions child99
 where parent.label = 'parent' and child99.label = 'child99' and (s.n = 1 or s.n between 40 and 100);
insert into research_private.subject_position_moves(
  generation_id, segment_key, position_id, research_subject_id, move_index,
  choice_count, win_count, draw_count, loss_count, child_position_id
)
select c.generation_id, 'ALL', parent.position_id, s.research_subject_id, 19,
       case when s.n = 1 then 20 else 1 end,
       case when s.n between 3 and 20 then 1 else 0 end,
       case when s.n = 2 then 1 else 0 end,
       case when s.n = 1 then 20 else 0 end,
       child100.position_id
  from aggregation_claim c cross join aggregate_subjects s
  cross join aggregate_positions parent cross join aggregate_positions child100
 where parent.label = 'parent' and child100.label = 'child100' and s.n between 1 and 20;
insert into research_private.subject_position_moves(
  generation_id, segment_key, position_id, research_subject_id, move_index,
  choice_count, win_count, draw_count, loss_count
)
select c.generation_id, 'ALL', parent.position_id, s.research_subject_id, 44, 1, 0, 0, 1
  from aggregation_claim c cross join aggregate_subjects s cross join aggregate_positions parent
 where parent.label = 'parent' and s.n between 21 and 39;
insert into research_private.subject_position_moves(
  generation_id, segment_key, position_id, research_subject_id, move_index,
  choice_count, win_count, draw_count, loss_count
)
select c.generation_id, 'ALL', parent.position_id, s.research_subject_id, 37, 1,
       case when s.n = 21 then 1 else 0 end,
       case when s.n = 2 then 1 else 0 end, 0
  from aggregation_claim c cross join aggregate_subjects s cross join aggregate_positions parent
 where parent.label = 'parent' and s.n in (2, 21);

insert into research_private.subject_position_totals(
  generation_id, segment_key, position_id, research_subject_id, occurrence_count
)
select c.generation_id, 'ALL', p.position_id, s.research_subject_id, 1
  from aggregation_claim c cross join aggregate_subjects s cross join aggregate_positions p
 where (p.label = 'child99' and s.n <= 99) or p.label = 'child100';
insert into research_private.subject_position_moves(
  generation_id, segment_key, position_id, research_subject_id, move_index,
  choice_count, win_count, draw_count, loss_count
)
select t.generation_id, t.segment_key, t.position_id, t.research_subject_id, 0, 1, 0, 1, 0
  from research_private.subject_position_totals t join aggregate_positions p using (position_id)
 where t.generation_id = (select generation_id from aggregation_claim) and p.label in ('child99', 'child100');

insert into research_private.generation_processed_games(generation_id, research_game_id)
select (select generation_id from aggregation_claim), g.research_game_id
  from research_private.games g
 where g.validation_status = 'ACCEPTED'
   and g.research_game_id <= (select source_watermark from aggregation_claim)
on conflict do nothing;
select ok(not exists (
  select 1 from research_private.generation_processed_games x
  join research_private.games g using (research_game_id)
  where g.validation_status in ('PENDING', 'REJECTED')
), 'pending and rejected games have no aggregate processing marker');
select is((select occurrence_count::int from research_private.subject_position_totals t
  join aggregate_subjects s using (research_subject_id) join aggregate_positions p using (position_id)
  where s.n = 1 and p.label = 'parent'), 100, 'one subject may have one hundred occurrences at a position');
select is((select sum(m.choice_count::numeric / t.occurrence_count)
  from research_private.subject_position_moves m join research_private.subject_position_totals t
    using (generation_id, segment_key, position_id, research_subject_id)
  join aggregate_subjects s using (research_subject_id) join aggregate_positions p using (position_id)
  where s.n = 1 and p.label = 'parent'), 1::numeric, 'a high-volume subject still has total position weight one');
select is((select m.choice_count::numeric / t.occurrence_count
  from research_private.subject_position_moves m join research_private.subject_position_totals t
    using (generation_id, segment_key, position_id, research_subject_id)
  join aggregate_subjects s using (research_subject_id) join aggregate_positions p using (position_id)
  where s.n = 1 and p.label = 'parent' and m.move_index = 26), 0.8::numeric,
  'subject A contributes C4 weight 0.8 after normalization');
select is((select m.choice_count::numeric / t.occurrence_count
  from research_private.subject_position_moves m join research_private.subject_position_totals t
    using (generation_id, segment_key, position_id, research_subject_id)
  join aggregate_subjects s using (research_subject_id) join aggregate_positions p using (position_id)
  where s.n = 1 and p.label = 'parent' and m.move_index = 19), 0.2::numeric,
  'subject A contributes D3 weight 0.2 after normalization');
select is((select sum(m.choice_count::numeric / t.occurrence_count)
  from research_private.subject_position_moves m join research_private.subject_position_totals t
    using (generation_id, segment_key, position_id, research_subject_id)
  join aggregate_subjects s using (research_subject_id) join aggregate_positions p using (position_id)
  where s.n = 2 and p.label = 'parent'), 1::numeric, 'single-game subject B also has total position weight one');
select is((select count(*)::int from research_private.position_aggregates
  where generation_id = (select generation_id from aggregation_claim)), 0,
  'a building generation exposes no completed aggregate rows');

select is((select public.publish_research_aggregation(generation_id, lease_token) from aggregation_claim),
  'PUBLISHED', 'complete aggregate generation publishes atomically');
select is((select generation_id from research_private.published_generation),
  (select generation_id from aggregation_claim), 'published pointer switches to the completed generation');
select is((select public.publish_research_aggregation(generation_id, lease_token) from aggregation_claim),
  'PUBLISHED', 'aggregate publish retry is idempotent');
select ok(not exists (
  select 1 from research_private.subject_position_totals t
   where t.generation_id = (select generation_id from aggregation_claim)
     and (select sum(m.choice_count::numeric / t.occurrence_count)
       from research_private.subject_position_moves m
      where m.generation_id = t.generation_id and m.segment_key = t.segment_key
        and m.position_id = t.position_id and m.research_subject_id = t.research_subject_id) <> 1
), 'every subject has total weight one in every generation position');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000010', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
create temporary table parent_research_response on commit drop as
select public.get_research_position(
  'r8v1n1:0000000810000000:0000001008000000:B', 'ALL'
) as body;
select ok((select (body ->> 'available')::boolean from parent_research_response),
  'position with one hundred unique subjects is publicly available to an eligible caller');
select is((select (body ->> 'unique_contributors')::int from parent_research_response), 100,
  'published position reports the threshold-passing unique subject count');
create temporary table child99_research_response on commit drop as
select public.get_research_position(
  'r8v1n1:0000000818080000:0000001000000000:W', 'ALL'
) as body;
select ok(not (select (body ->> 'available')::boolean from child99_research_response),
  'position with ninety-nine unique subjects is not public');
select is((select body ->> 'reason' from child99_research_response), 'INSUFFICIENT_SAMPLE',
  'sub-threshold position returns only an insufficient sample state');
select ok(not (select body ? 'unique_contributors' from child99_research_response),
  'sub-threshold position does not leak the exact ninety-nine count');
select ok((select exists (select 1 from jsonb_array_elements(body -> 'moves') m
  where m ->> 'coordinate' = 'd3') from parent_research_response),
  'move with twenty unique subjects is individually published');
select ok(not (select exists (select 1 from jsonb_array_elements(body -> 'moves') m
  where m ->> 'coordinate' = 'e6') from parent_research_response),
  'move with nineteen unique subjects is suppressed');
select ok((select exists (select 1 from jsonb_array_elements(body -> 'moves') m
  where m ->> 'coordinate' = 'c4') from parent_research_response),
  'another threshold-passing move remains individually published');
select is((select round(((body -> 'other' ->> 'choice_rate')::numeric), 3)
  from parent_research_response), 0.195::numeric,
  'OTHER combines every suppressed move weight');
select is((select (body -> 'other' ->> 'unique_contributors')::int
  from parent_research_response), 20,
  'OTHER unique contributors use subject union instead of summing per-move counts');
select ok(not (select (body -> 'other') ? 'coordinate' from parent_research_response),
  'OTHER reveals no suppressed move coordinate');
select ok((select (body -> 'other' ->> 'can_explore')::boolean = false
  and body -> 'other' -> 'child_position_token' = 'null'::jsonb from parent_research_response),
  'OTHER cannot be used for child drill-down');
select ok((select exists (select 1 from jsonb_array_elements(body -> 'moves') m
  where m ->> 'coordinate' = 'c4' and not (m ->> 'can_explore')::boolean)
  from parent_research_response), 'child with ninety-nine subjects cannot be explored');
select ok((select exists (select 1 from jsonb_array_elements(body -> 'moves') m
  where m ->> 'coordinate' = 'd3' and (m ->> 'can_explore')::boolean
    and m ->> 'child_position_token' is not null) from parent_research_response),
  'child with one hundred subjects can be explored');
select ok((select body::text !~* '(research_subject|account|user_id|match_id|game_record|canonical)'
  from parent_research_response), 'public response contains no subject, account, match, record, or canonical-line identifier');

select set_config('request.jwt.claim.role', 'service_role', false);
create temporary table failed_aggregation_claim on commit drop as
select * from public.claim_research_aggregation_build(900);
grant select on failed_aggregation_claim to research_batch;
set role research_batch;
create temporary table checkpoint_result on commit drop as
select research_private.batch_checkpoint_aggregation(generation_id, lease_token) as status
  from failed_aggregation_claim;
reset role;
select is((select status from checkpoint_result), 'CHECKPOINTED',
  'bounded Actions run checkpoints without failing its generation');
create temporary table resumed_aggregation_claim on commit drop as
select * from public.claim_research_aggregation_build(900);
select ok((select old.lease_token <> resumed.lease_token
  from failed_aggregation_claim old cross join resumed_aggregation_claim resumed),
  'the next Actions run rotates the expired checkpoint lease');
select is((select public.fail_research_aggregation(generation_id, lease_token, 'WORKER_BUILD_FAILED')
  from resumed_aggregation_claim), 'FAILED', 'worker can terminalize a poisoned build without leaving a BUILDING lease');
select is((select generation_id from research_private.published_generation),
  (select generation_id from aggregation_claim), 'failed rebuild leaves the previous published generation active');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000010', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
select public.set_research_participation(false);
select ok(not (select can_view_research_data from public.get_research_participation_status()),
  'opt-out immediately revokes aggregate viewing');
select ok((select count(*) > 0 from research_private.position_aggregates
  where generation_id = (select generation_id from aggregation_claim)),
  'opt-out leaves previously accepted aggregate weight intact');
select public.set_research_participation(true, 3);
select ok((select qualifying_game_count = 0 and not eligible
  from public.get_research_participation_status()),
  're-opt-in starts a new participation period with zero qualifying games');
insert into research_private.consent_versions(consent_version, effective_at, document_sha256, summary)
values (4, now(), repeat('d', 64), 'Research consent v4 test');
update research_private.policy_versions set is_active = false where is_active;
insert into research_private.policy_versions(
  effective_at, research_consent_version, eligibility_min_games, eligibility_window_days,
  position_min_users, move_min_users, min_decisions_per_qualifying_game,
  ruleset_version, normalization_version, collection_enabled, is_active
) values (now(), 4, 10, 90, 100, 20, 10, 1, 1, true, true);
select ok((select not eligible and not can_view_research_data and reconsent_required
  from public.get_research_participation_status()),
  'consent mismatch disables eligibility and viewing until a new period is started');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
select is((select matched from public.enqueue_or_match()), false, 'first user enters the queue');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
select is((select matched from public.enqueue_or_match()), true, 'second user creates a match');
select is((select count(*)::int from public.active_match_participants), 2, 'both players receive one active reservation');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select is(
  (select match_id::text from public.claim_waiting_match()),
  (select id::text from public.matches where '00000000-0000-0000-0000-000000000001'::uuid in (black_player, white_player) limit 1),
  'waiting participant can claim the created match'
);
select is(
  (select count(*)::int from public.match_notifications where user_id = '00000000-0000-0000-0000-000000000001'),
  0,
  'claim consumes only the caller notification'
);
select throws_ok(
  $$insert into public.active_match_participants(user_id, match_id, expires_at)
    values ('00000000-0000-0000-0000-000000000002', (select id from public.matches limit 1), now() + interval '5 minutes')$$,
  '23505', null, 'a user cannot reserve a second active match');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select is((select public.abandon_match((select id from public.matches where '00000000-0000-0000-0000-000000000001'::uuid in (black_player, white_player) limit 1))::text), 'ABANDONED', 'participant can abandon a CREATED match');
select is((select count(*)::int from public.active_match_participants), 0, 'abandon releases both reservations');

insert into public.matches(id, black_player, white_player, status, server_status, created_expires_at)
values ('00000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'PLAYING', 'CREATED', now() - interval '1 minute');
insert into public.active_match_participants(user_id, match_id, expires_at)
values ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000103', now() - interval '1 minute'),
       ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000103', now() - interval '1 minute');
select is((select public.cleanup_stale_created_matches()), 1, 'stale CREATED lease is abandoned');
select is((select count(*)::int from public.active_match_participants), 0, 'stale lease releases active reservations');

insert into public.matches(id, black_player, white_player, status, server_status)
values ('00000000-0000-0000-0000-000000000106', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'PLAYING', 'CREATED');
insert into public.active_match_participants(user_id, match_id, expires_at)
values ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000106', now() + interval '5 minutes'),
       ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000106', now() + interval '5 minutes');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select is((select public.ack_match_started('00000000-0000-0000-0000-000000000106')::text), 'CREATED', 'first start ack is participant-scoped and idempotent');
select is((select public.ack_match_started('00000000-0000-0000-0000-000000000106')::text), 'CREATED', 'repeated start ack is idempotent');
select ok((select local_acked from public.get_match_start_state('00000000-0000-0000-0000-000000000106')), 'caller can observe its own start ack');
select ok(not (select both_acked from public.get_match_start_state('00000000-0000-0000-0000-000000000106')), 'one start ack cannot begin play');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
select is((select public.ack_match_started('00000000-0000-0000-0000-000000000106')::text), 'CREATED', 'second start ack is accepted');
select ok((select both_acked from public.get_match_start_state('00000000-0000-0000-0000-000000000106')), 'both participants can observe confirmed start');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select throws_ok($$select public.ack_match_started('00000000-0000-0000-0000-000000000106')$$, 'P0001', 'match participant required', 'non-participant cannot ack match start');
select throws_ok($$select * from public.get_match_start_state('00000000-0000-0000-0000-000000000106')$$, 'P0001', 'match participant required', 'non-participant cannot inspect start state');
select ok((select p2p_started_at is not null and play_lease_expires_at > now() + interval '23 hours' from public.matches where id = '00000000-0000-0000-0000-000000000106'), 'both acks switch to the long bounded play lease');
update public.matches set created_expires_at = now() - interval '1 minute' where id = '00000000-0000-0000-0000-000000000106';
select is((select public.cleanup_stale_created_matches()), 0, 'signaling cleanup ignores an acknowledged match');
select is((select server_status::text from public.matches where id = '00000000-0000-0000-0000-000000000106'), 'CREATED', 'acknowledged match remains active after five minutes');
select is((select count(*)::int from public.active_match_participants where match_id = '00000000-0000-0000-0000-000000000106'), 2, 'acknowledged match keeps both reservations');
update public.active_match_participants set expires_at = now() - interval '1 minute'
 where match_id = '00000000-0000-0000-0000-000000000106' and user_id = '00000000-0000-0000-0000-000000000001';
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select is((select matched from public.enqueue_or_match()), false, 'enqueue reconciles only the caller expired active match');
select is((select server_status::text from public.matches where id = '00000000-0000-0000-0000-000000000106'), 'ABANDONED', 'caller reconciliation terminalizes the expired match');
select is((select count(*)::int from public.active_match_participants where match_id = '00000000-0000-0000-0000-000000000106'), 0, 'caller reconciliation releases reservations through the trigger');
delete from public.match_queue where user_id = '00000000-0000-0000-0000-000000000001';

insert into public.matches(id, black_player, white_player, status, server_status)
values ('00000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004', 'PLAYING', 'CREATED');
insert into public.active_match_participants(user_id, match_id, expires_at)
values ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000104', now() + interval '30 days'),
       ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000104', now() + interval '30 days');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select throws_ok($$select public.submit_match_result('00000000-0000-0000-0000-000000000104', 'd3', 'BLACK_WIN', '0000000000000000:0:0:1', 'NORMAL', null)$$, 'P0001', 'match P2P not started', 'result submit is rejected before both start acks');
select public.ack_match_started('00000000-0000-0000-0000-000000000104');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000004', false);
select public.ack_match_started('00000000-0000-0000-0000-000000000104');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000104', 'd3', 'BLACK_WIN', '0000000000000000:0:0:1', 'NORMAL', null)::text), 'PENDING_RESULT', 'result submit succeeds after both start acks');
select ok((select result_expires_at > now() + interval '4 minutes' and result_expires_at <= now() + interval '5 minutes' from public.matches where id = '00000000-0000-0000-0000-000000000104'), 'PENDING_RESULT uses a five-minute result lease');
select ok((select min(expires_at) > now() + interval '4 minutes' and max(expires_at) <= now() + interval '5 minutes' from public.active_match_participants where match_id = '00000000-0000-0000-0000-000000000104'), 'PENDING_RESULT active reservations use the same five-minute lease');
select is((select count(*)::int from public.active_match_participants where match_id = '00000000-0000-0000-0000-000000000104'), 2, 'PENDING_RESULT keeps both active reservations');
update public.matches set result_expires_at = now() - interval '1 minute' where id = '00000000-0000-0000-0000-000000000104';
select is((select public.cleanup_expired_pending_results()), 1, 'expired PENDING_RESULT becomes terminal');
select is((select server_status::text from public.matches where id = '00000000-0000-0000-0000-000000000104'), 'ABANDONED', 'pending expiry does not change rating or confirm a result');
select is((select count(*)::int from public.rating_history where match_id = '00000000-0000-0000-0000-000000000104'), 0, 'pending expiry does not change rating');
select is((select count(*)::int from public.active_match_participants where match_id = '00000000-0000-0000-0000-000000000104'), 0, 'pending expiry releases reservations through lifecycle trigger');

insert into public.matches(id, black_player, white_player, status, server_status)
values ('00000000-0000-0000-0000-000000000105', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004', 'PLAYING', 'CREATED');
select public.ack_match_started('00000000-0000-0000-0000-000000000105');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000004', false);
select public.ack_match_started('00000000-0000-0000-0000-000000000105');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000105', 'd3', 'BLACK_WIN', '0000000000000000:0:0:1', 'NORMAL', null)::text), 'PENDING_RESULT', 'first result is pending for normal finalization');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000004', false);
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000105', 'd3', 'BLACK_WIN', '0000000000000000:0:0:1', 'NORMAL', null)::text), 'CONFIRMED', 'second result auto-finalizes');
select is((select count(*)::int from public.rating_history where match_id = '00000000-0000-0000-0000-000000000105'), 2, 'finalization writes two rating history rows');
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000105', 'd3', 'BLACK_WIN', '0000000000000000:0:0:1', 'NORMAL', null)::text), 'CONFIRMED', 'duplicate terminal submit is idempotent');
select is((select count(*)::int from public.rating_history where match_id = '00000000-0000-0000-0000-000000000105'), 2, 'duplicate submit does not update rating twice');
select is((select final_position_hash from public.game_records where match_id = '00000000-0000-0000-0000-000000000105'), '0000000000000000:0:0:1', 'confirmed record retains the verified final position hash');
select is((select time_control from public.game_records where match_id = '00000000-0000-0000-0000-000000000105'), '5m', 'confirmed record stores the product time control');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select is((select count(*)::int from public.game_records where players @> array['00000000-0000-0000-0000-000000000003'::uuid]), 1, 'array containment finds a record where caller is black or white');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
set role authenticated;
select is((select count(*)::int from public.game_records), 0, 'record RLS hides other users games');
reset role;

insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data)
values
 ('00000000-0000-0000-0000-000000000005', 'authenticated', 'authenticated', 'e@example.test', '', now(), '{}', '{"display_name":"e"}'),
 ('00000000-0000-0000-0000-000000000006', 'authenticated', 'authenticated', 'f@example.test', '', now(), '{}', '{"display_name":"f"}')
on conflict (id) do nothing;
insert into public.matches(id, black_player, white_player, status, server_status)
values ('00000000-0000-0000-0000-000000000107', '00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000006', 'PLAYING', 'CREATED');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000005', false);
select throws_ok($$select public.submit_match_result('00000000-0000-0000-0000-000000000107', '', 'BLACK_WIN', '0000000000000000:0:0:0', 'NORMAL', null)$$, 'P0001', 'invalid canonical moves', 'NORMAL cannot submit zero-ply canonical history');
select public.ack_match_started('00000000-0000-0000-0000-000000000107');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000006', false);
select public.ack_match_started('00000000-0000-0000-0000-000000000107');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000005', false);
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000107', '', 'BLACK_WIN', '0000000000000000:0:0:0', 'RESIGNATION', null)::text), 'PENDING_RESULT', 'zero-ply resignation is accepted');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000006', false);
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000107', '', 'BLACK_WIN', '0000000000000000:0:0:0', 'RESIGNATION', null)::text), 'CONFIRMED', 'matching zero-ply resignation finalizes');
select is((select canonical_moves from public.game_records where match_id = '00000000-0000-0000-0000-000000000107'), '', 'zero-ply canonical history is stored as empty text');

insert into public.federation_credentials(user_id, organization, credential_type, value)
values ('00000000-0000-0000-0000-000000000003', 'test', 'dan-bad', 'C1'),
       ('00000000-0000-0000-0000-000000000003', 'test', 'dan-good', 'C2');
insert into storage.objects(bucket_id, name, owner_id, metadata)
values ('verification', '00000000-0000-0000-0000-000000000003/proof.png', '00000000-0000-0000-0000-000000000003', '{}'::jsonb),
       ('verification', '00000000-0000-0000-0000-000000000003/not-owned.png', '00000000-0000-0000-0000-000000000004', '{}'::jsonb);
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select throws_ok(
  $$select public.submit_verification_submission((select id from public.federation_credentials where credential_type = 'dan-bad'), '00000000-0000-0000-0000-000000000003/not-owned.png')$$,
  'P0001', 'evidence object ownership required', 'unowned evidence object is rejected by ownership validation');
select ok(public.submit_verification_submission((select id from public.federation_credentials where credential_type = 'dan-good'), '00000000-0000-0000-0000-000000000003/proof.png') is not null, 'owned evidence path is accepted');
select set_config('request.jwt.claim.role', 'service_role', false);
select is((select public.review_verification_submission((select id from public.verification_submissions where credential_id = (select id from public.federation_credentials where credential_type = 'dan-good')), 'VERIFIED'::public.credential_status)), 'VERIFIED', 'review returns actual DB status');
select is((select public.review_verification_submission((select id from public.verification_submissions where credential_id = (select id from public.federation_credentials where credential_type = 'dan-good')), 'VERIFIED'::public.credential_status)), 'VERIFIED', 'same review decision is idempotent');
select throws_ok($$select public.review_verification_submission((select id from public.verification_submissions where credential_id = (select id from public.federation_credentials where credential_type = 'dan-good')), 'REJECTED'::public.credential_status)$$, 'P0001', 'review decision conflict', 'conflicting terminal review is rejected');
select is((select public.get_verification_evidence_cleanup((select id from public.verification_submissions where credential_id = (select id from public.federation_credentials where credential_type = 'dan-good')))), '00000000-0000-0000-0000-000000000003/proof.png', 'cleanup retry returns DB-owned path');
select public.mark_verification_evidence_deleted((select id from public.verification_submissions where credential_id = (select id from public.federation_credentials where credential_type = 'dan-good')));
select is((select public.get_verification_evidence_cleanup((select id from public.verification_submissions where credential_id = (select id from public.federation_credentials where credential_type = 'dan-good')))), null, 'successful cleanup removes the path reference');

select ok(to_regclass('public.account_deletion_requests') is not null, 'account deletion request table exists');
select ok(to_regprocedure('public.request_account_deletion()') is not null, 'account deletion request RPC exists');
select ok(has_table_privilege('authenticated', 'public.account_deletion_requests', 'select'), 'authenticated users can read their deletion request');
select ok(not has_table_privilege('authenticated', 'public.account_deletion_requests', 'insert'), 'authenticated users cannot forge deletion request rows');
select ok(has_function_privilege('authenticated', 'public.request_account_deletion()', 'execute'), 'authenticated users can request deletion through RPC');
select ok(not has_function_privilege('anon', 'public.request_account_deletion()', 'execute'), 'anonymous users cannot request account deletion');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
select is(public.request_account_deletion(), public.request_account_deletion(), 'account deletion request is idempotent');
select throws_ok(
  $$select * from public.enqueue_or_match()$$,
  'P0001', 'account deletion is pending', 'deletion-requested users cannot re-enter matchmaking'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select public.request_account_deletion();
select set_config('request.jwt.claim.role', 'service_role', false);
select is(
  cardinality(public.get_account_deletion_evidence('00000000-0000-0000-0000-000000000003')),
  2,
  'trusted deletion worker receives every verification object under the user prefix'
);

-- User 5 has a shared record but no Storage objects, so the DB phase can be tested
-- independently without bypassing Storage API deletion protection.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000005', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
select public.request_account_deletion();
select set_config('request.jwt.claim.role', 'service_role', false);
select is(public.prepare_account_deletion('00000000-0000-0000-0000-000000000005'), 'PROCESSING', 'trusted deletion preparation is retryable');
select is((select count(*)::int from public.ratings where user_id = '00000000-0000-0000-0000-000000000005'), 0, 'account deletion removes private rating state');
select ok((select display_name = '退会済みユーザー' and deleted_at is not null from public.profiles where id = '00000000-0000-0000-0000-000000000005'), 'account deletion anonymizes the shared profile tombstone');
select is((select count(*)::int from public.game_records where match_id = '00000000-0000-0000-0000-000000000107'), 1, 'account deletion preserves the opponents shared immutable record');
select is((select count(*)::int from public.user_game_records where user_id = '00000000-0000-0000-0000-000000000006' and match_id = '00000000-0000-0000-0000-000000000107'), 1, 'opponent keeps the bounded record reference');
select is((select count(*)::int from public.user_game_records where user_id = '00000000-0000-0000-0000-000000000005'), 0, 'deleted user record references are removed');
select is(public.complete_account_deletion('00000000-0000-0000-0000-000000000005'), 'COMPLETED', 'trusted worker completes deletion after Auth removal');
select is((select status from public.account_deletion_requests where user_id = '00000000-0000-0000-0000-000000000005'), 'COMPLETED', 'completed deletion status is retained for audit');

-- Research 2E: account deletion unlinks identity without touching research data.
select ok(to_regprocedure('public.unlink_research_subject(uuid)') is not null, 'trusted research subject unlink RPC exists');
select ok(not has_function_privilege('authenticated', 'public.unlink_research_subject(uuid)', 'execute'), 'authenticated users cannot unlink research subjects');
select ok(has_function_privilege('service_role', 'public.unlink_research_subject(uuid)', 'execute'), 'only the trusted service role can unlink research subjects');
create temporary table unlink_snapshot on commit drop as
select s.research_subject_id,
       (select count(*) from research_private.game_contributors c where c.research_subject_id = s.research_subject_id and c.contribution_status = 'ACCEPTED')::int as accepted_count
  from research_private.research_subjects s
 where s.account_user_id = '00000000-0000-0000-0000-000000000008';
select is((select public.unlink_research_subject('00000000-0000-0000-0000-000000000008')), 'UNLINKED', 'trusted unlink moves a deletion-pending subject to UNLINKED');
select is((select count(*)::int from research_private.research_subjects where research_subject_id = (select research_subject_id from unlink_snapshot)), 1, 'unlink retains the research subject row');
select ok((select account_user_id is null and link_state = 'UNLINKED' and unlinked_at is not null from research_private.research_subjects where research_subject_id = (select research_subject_id from unlink_snapshot)), 'unlink removes the account link without leaving a tombstone UUID');
select is((select count(*)::int from research_private.participation_periods where research_subject_id = (select research_subject_id from unlink_snapshot) and ended_at is null), 0, 'unlink leaves no open participation period');
select is((select count(*)::int from research_private.game_contributors where research_subject_id = (select research_subject_id from unlink_snapshot) and contribution_status = 'ACCEPTED'), (select accepted_count from unlink_snapshot), 'unlink preserves accepted research contributions');
select is((select public.unlink_research_subject('00000000-0000-0000-0000-000000000008')), 'ALREADY_UNLINKED', 'repeated trusted unlink is idempotent');
select is((select count(*)::int from research_private.research_subjects where account_user_id = '00000000-0000-0000-0000-000000000008'), 0, 'unlinked subject cannot be resolved back to the deleted account');
select ok(not exists (select 1 from information_schema.columns where table_schema = 'research_private' and table_name in ('games', 'game_contributors') and column_name = 'account_user_id'), 'research source and contributors have no account UUID column');
select set_config('request.jwt.claim.role', 'authenticated', false);
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000008', false);
select throws_ok($$select public.unlink_research_subject('00000000-0000-0000-0000-000000000008')$$, 'P0001', 'admin service role required', 'authenticated callers cannot invoke the service-only unlink RPC');
select set_config('request.jwt.claim.role', 'service_role', false);

select * from finish();
rollback;

-- Run with `supabase test db` against local Supabase/Postgres + pgTAP.
begin;
select plan(194);

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
    ('research_private.games'), ('research_private.game_contributors')
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
    ('research_private.games'), ('research_private.game_contributors')
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
    ('research_private.games'), ('research_private.game_contributors')
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
    'research_private.game_contributors'::regclass
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

select * from finish();
rollback;

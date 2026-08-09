-- Run with `supabase test db` against local Supabase/Postgres + pgTAP.
select plan(28);

select ok(not has_function_privilege('anon', 'public.prune_user_game_records(uuid)', 'execute'), 'anon cannot execute prune_user_game_records');
select ok(not has_function_privilege('authenticated', 'public.prune_user_game_records(uuid)', 'execute'), 'authenticated cannot execute prune_user_game_records');
select ok(not exists (
  select 1 from aclexplode(p.proacl) acl
   where p.oid = 'public.prune_user_game_records(uuid)'::regprocedure and acl.grantee = 0 and acl.privilege_type = 'EXECUTE'
), 'PUBLIC has no execute ACL for prune_user_game_records');
select ok(exists (select 1 from pg_constraint where conrelid = 'public.active_match_participants'::regclass and contype = 'p'), 'active reservations have a user primary key');
select ok(to_regprocedure('public.abandon_match(uuid)') is not null, 'abandon_match RPC exists');
select ok(exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'matches' and column_name = 'created_expires_at'), 'CREATED matches have a lease column');
select ok(to_regprocedure('public.get_verification_evidence_cleanup(uuid)') is not null, 'evidence cleanup retry RPC exists');
select ok(not exists (
  select 1 from pg_proc where prosecdef and pronamespace = 'public'::regnamespace
    and proconfig @> array['search_path=public']
), 'public SECURITY DEFINER functions do not use search_path=public');

-- Auth trigger fixtures. These are rolled back by Supabase's pgTAP runner.
insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data)
values
 ('00000000-0000-0000-0000-000000000001', 'authenticated', 'authenticated', 'a@example.test', '', now(), '{}', '{"display_name":"a"}'),
 ('00000000-0000-0000-0000-000000000002', 'authenticated', 'authenticated', 'b@example.test', '', now(), '{}', '{"display_name":"b"}'),
 ('00000000-0000-0000-0000-000000000003', 'authenticated', 'authenticated', 'c@example.test', '', now(), '{}', '{"display_name":"c"}'),
 ('00000000-0000-0000-0000-000000000004', 'authenticated', 'authenticated', 'd@example.test', '', now(), '{}', '{"display_name":"d"}')
on conflict (id) do nothing;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
select is((select matched from public.enqueue_or_match()), false, 'first user enters the queue');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
select is((select matched from public.enqueue_or_match()), true, 'second user creates a match');
select is((select count(*)::int from public.active_match_participants), 2, 'both players receive one active reservation');
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
values ('00000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004', 'PLAYING', 'CREATED');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000104', 'd3', 'BLACK_WIN', '0000000000000000:0:0:1', 'NORMAL', null)::text), 'PENDING_RESULT', 'first result is pending');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000004', false);
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000104', 'd3', 'BLACK_WIN', '0000000000000000:0:0:1', 'NORMAL', null)::text), 'CONFIRMED', 'second result auto-finalizes');
select is((select count(*)::int from public.rating_history where match_id = '00000000-0000-0000-0000-000000000104'), 2, 'finalization writes two rating history rows');
select is((select public.submit_match_result('00000000-0000-0000-0000-000000000104', 'd3', 'BLACK_WIN', '0000000000000000:0:0:1', 'NORMAL', null)::text), 'CONFIRMED', 'duplicate terminal submit is idempotent');
select is((select count(*)::int from public.rating_history where match_id = '00000000-0000-0000-0000-000000000104'), 2, 'duplicate submit does not update rating twice');

insert into public.federation_credentials(user_id, organization, credential_type, value)
values ('00000000-0000-0000-0000-000000000003', 'test', 'dan', 'C1');
insert into storage.objects(bucket_id, name, owner_id, metadata)
values ('verification', '00000000-0000-0000-0000-000000000003/proof.png', '00000000-0000-0000-0000-000000000003', '{}'::jsonb);
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
select ok(public.submit_verification_submission((select id from public.federation_credentials where user_id = '00000000-0000-0000-0000-000000000003'), '00000000-0000-0000-0000-000000000003/proof.png') is not null, 'owned evidence path is accepted');
select throws_ok(
  $$select public.submit_verification_submission((select id from public.federation_credentials where user_id = '00000000-0000-0000-0000-000000000003'), '00000000-0000-0000-0000-000000000004/proof.png')$$,
  'P0001', null, 'another user evidence path is rejected');
select set_config('request.jwt.claim.role', 'service_role', false);
select is((select public.review_verification_submission((select id from public.verification_submissions limit 1), 'VERIFIED'::public.credential_status)), 'VERIFIED', 'review returns actual DB status');
select is((select public.review_verification_submission((select id from public.verification_submissions limit 1), 'VERIFIED'::public.credential_status)), 'VERIFIED', 'same review decision is idempotent');
select throws_ok($$select public.review_verification_submission((select id from public.verification_submissions limit 1), 'REJECTED'::public.credential_status)$$, 'P0001', null, 'conflicting terminal review is rejected');
select is((select public.get_verification_evidence_cleanup((select id from public.verification_submissions limit 1))), '00000000-0000-0000-0000-000000000003/proof.png', 'cleanup retry returns DB-owned path');
select public.mark_verification_evidence_deleted((select id from public.verification_submissions limit 1));
select is((select public.get_verification_evidence_cleanup((select id from public.verification_submissions limit 1))), null, 'successful cleanup removes the path reference');

select * from finish();

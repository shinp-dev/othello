-- Run with `supabase test db` against local Supabase/Postgres + pgTAP.
select plan(68);

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
select ok(exists (select 1 from storage.buckets where id = 'verification' and public = false), 'verification bucket is private and migration-managed');
select ok((select file_size_limit from storage.buckets where id = 'verification') = 5242880, 'verification bucket limits objects to 5 MiB');
select ok((select allowed_mime_types from storage.buckets where id = 'verification') = array['image/jpeg', 'image/png', 'image/webp']::text[], 'verification bucket allows only image MIME types');
select ok(exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'verification objects owner insert' and 'authenticated' = any(roles)), 'verification upload policy is authenticated-only');
select ok(exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'verification objects owner read' and 'authenticated' = any(roles)), 'verification read policy is owner-scoped, not public');
select ok(to_regprocedure('public.ack_match_started(uuid)') is not null, 'start ack RPC exists');
select ok(to_regprocedure('public.get_match_start_state(uuid)') is not null, 'participant start state RPC exists');
select ok(position('delete from public.active_match_participants' in pg_get_functiondef('public.cleanup_stale_created_matches()'::regprocedure)) = 0, 'signaling cleanup never deletes reservations directly');
select ok(position('cleanup_terminal_matches' in pg_get_functiondef('public.enqueue_or_match()'::regprocedure)) = 0, 'terminal cleanup is outside matchmaking hot path');
select ok(position('cleanup_stale_created_matches' in pg_get_functiondef('public.enqueue_or_match()'::regprocedure)) = 0, 'global signaling cleanup is outside matchmaking hot path');
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

select * from finish();

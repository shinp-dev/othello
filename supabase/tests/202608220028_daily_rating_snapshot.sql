-- Daily rating snapshot contract, security, cutoff, and ranking tests.
begin;
select plan(40);

select ok(to_regclass('public.rating_daily_snapshot') is not null, 'daily rating snapshot table exists');
select is((select count(*)::int from information_schema.columns
  where table_schema = 'public' and table_name = 'rating_daily_snapshot'
    and column_name in ('user_id', 'snapshot_date', 'rank', 'active_user_count', 'top_percentile')), 5,
  'snapshot exposes only the required user ranking fields');
select ok(exists (select 1 from pg_indexes where schemaname = 'public' and indexname = 'rating_daily_snapshot_date_idx'), 'snapshot date is indexed');
select ok(exists (select 1 from pg_class where oid = 'public.rating_daily_snapshot'::regclass and relrowsecurity), 'snapshot uses RLS');
select ok(exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'rating_daily_snapshot'
  and cmd = 'SELECT' and position('auth.uid() = user_id' in qual) > 0), 'snapshot select policy is owner scoped');
select ok(has_table_privilege('authenticated', 'public.rating_daily_snapshot', 'select'), 'authenticated can select snapshots through RLS');
select ok(not has_table_privilege('authenticated', 'public.rating_daily_snapshot', 'insert')
  and not has_table_privilege('authenticated', 'public.rating_daily_snapshot', 'update')
  and not has_table_privilege('authenticated', 'public.rating_daily_snapshot', 'delete'), 'authenticated cannot write snapshots');
select ok(to_regprocedure('public.refresh_rating_daily_snapshot(date)') is not null, 'service refresh function exists');
select ok((select prosecdef from pg_proc where oid = 'public.refresh_rating_daily_snapshot(date)'::regprocedure), 'refresh is security definer');
select ok((select coalesce(proconfig, '{}') @> array['search_path=""'] from pg_proc
  where oid = 'public.refresh_rating_daily_snapshot(date)'::regprocedure), 'refresh uses an empty search path');
select ok(has_function_privilege('service_role', 'public.refresh_rating_daily_snapshot(date)', 'execute'), 'service role can refresh snapshots');
select ok(not has_function_privilege('authenticated', 'public.refresh_rating_daily_snapshot(date)', 'execute'), 'authenticated cannot refresh snapshots');
select ok(not has_function_privilege('anon', 'public.refresh_rating_daily_snapshot(date)', 'execute'), 'anonymous users cannot refresh snapshots');
select ok(position('Asia/Tokyo' in pg_get_functiondef('public.refresh_rating_daily_snapshot(date)'::regprocedure)) > 0, 'refresh uses Tokyo date boundaries');
select ok(position('rank() over' in lower(pg_get_functiondef('public.refresh_rating_daily_snapshot(date)'::regprocedure))) > 0, 'refresh uses tied rank semantics');
select ok(position('rating_history' in pg_get_functiondef('public.refresh_rating_daily_snapshot(date)'::regprocedure)) > 0, 'refresh uses confirmed rating history');
select ok(position('30 days' in pg_get_functiondef('public.refresh_rating_daily_snapshot(date)'::regprocedure)) > 0, 'refresh uses the requested 30-day activity window');
select ok(position('current_rating' in pg_get_functiondef('public.refresh_rating_daily_snapshot(date)'::regprocedure)) = 0, 'refresh never ranks by current rating');
select ok(to_regprocedure('public.enqueue_or_match()') is not null, 'existing matchmaking RPC remains present');
select ok(to_regprocedure('public.submit_match_result(uuid,text,text,text,text,jsonb)') is not null, 'existing result RPC remains present');

insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data)
values
 ('00000000-0000-0000-0000-000000000101', 'authenticated', 'authenticated', 'rank-a@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000102', 'authenticated', 'authenticated', 'rank-b@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000103', 'authenticated', 'authenticated', 'rank-lower-bound@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000104', 'authenticated', 'authenticated', 'rank-stale-login@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000105', 'authenticated', 'authenticated', 'rank-deleted@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000106', 'authenticated', 'authenticated', 'rank-no-games@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000107', 'authenticated', 'authenticated', 'rank-at-cutoff@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000108', 'authenticated', 'authenticated', 'rank-post-cutoff-change@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000109', 'authenticated', 'authenticated', 'rank-1610@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000110', 'authenticated', 'authenticated', 'rank-before-window@example.test', '', now(), '{}', '{}');

update public.ratings set current_rating = case user_id
  when '00000000-0000-0000-0000-000000000101'::uuid then 1600
  when '00000000-0000-0000-0000-000000000102'::uuid then 1600
  when '00000000-0000-0000-0000-000000000103'::uuid then 1500
  when '00000000-0000-0000-0000-000000000104'::uuid then 1400
  when '00000000-0000-0000-0000-000000000105'::uuid then 1900
  when '00000000-0000-0000-0000-000000000107'::uuid then 2100
  when '00000000-0000-0000-0000-000000000108'::uuid then 1620
  when '00000000-0000-0000-0000-000000000109'::uuid then 1610
  when '00000000-0000-0000-0000-000000000110'::uuid then 2200
  else 2000 end;

-- Ranking activity is deliberately independent from account-lifecycle activity.
update public.profiles set last_active_at = '2025-08-20 03:00:00+09'::timestamptz
 where id = '00000000-0000-0000-0000-000000000104';
update public.profiles set deleted_at = '2026-08-21 05:00:00+09'::timestamptz
 where id = '00000000-0000-0000-0000-000000000105';

insert into public.rating_history(user_id, match_id, rating, delta, algorithm_version, created_at)
values
 ('00000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000101', 1600, 10, 'elo-v1', '2026-08-01 00:00:00+09'),
 ('00000000-0000-0000-0000-000000000102', '10000000-0000-0000-0000-000000000102', 1600, 0, 'elo-v1', '2026-08-21 00:00:00+09'),
 ('00000000-0000-0000-0000-000000000103', '10000000-0000-0000-0000-000000000103', 1500, 10, 'elo-v1', '2026-07-23 00:00:00+09'),
 ('00000000-0000-0000-0000-000000000104', '10000000-0000-0000-0000-000000000104', 1400, 10, 'elo-v1', '2026-08-10 00:00:00+09'),
 ('00000000-0000-0000-0000-000000000105', '10000000-0000-0000-0000-000000000105', 1900, 10, 'elo-v1', '2026-08-10 00:00:00+09'),
 ('00000000-0000-0000-0000-000000000107', '10000000-0000-0000-0000-000000000107', 2100, 10, 'elo-v1', '2026-08-22 00:00:00+09'),
 ('00000000-0000-0000-0000-000000000108', '10000000-0000-0000-0000-000000000108', 1600, 10, 'elo-v1', '2026-08-21 23:59:59+09'),
 ('00000000-0000-0000-0000-000000000108', '10000000-0000-0000-0000-000000000118', 1620, 20, 'elo-v1', '2026-08-22 00:00:30+09'),
 ('00000000-0000-0000-0000-000000000109', '10000000-0000-0000-0000-000000000109', 1610, 10, 'elo-v1', '2026-08-20 00:00:00+09'),
 ('00000000-0000-0000-0000-000000000110', '10000000-0000-0000-0000-000000000110', 2200, 10, 'elo-v1', '2026-07-22 23:59:59+09');

set local role service_role;
select is(public.refresh_rating_daily_snapshot('2026-08-21'::date), 6, 'service role refreshes the six active users');
reset role;

select is((select count(*)::int from public.rating_daily_snapshot), 6, 'only users with a history row in the half-open window are stored');
select is((select count(*)::int from public.rating_daily_snapshot where snapshot_date = '2026-08-21'), 6, 'all rows use the requested Tokyo snapshot date');
select is((select rank from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000109'), 1, 'the highest cutoff rating is rank one');
select is((select rank from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000101'), 2, 'equal cutoff ratings share rank two');
select is((select rank from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000103'), 5, 'rank uses gaps after the three-way tie');
select is((select top_percentile from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000101'), 33.3333::numeric, 'top percentile is rank divided by active count');
select is((select rank from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000108'), 2, 'cutoff rating 1600 is used instead of post-cutoff current rating 1620');
select ok(exists (select 1 from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000103'), 'the exact 30-day lower boundary is included');
select ok(exists (select 1 from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000108'), 'the row immediately before cutoff is included');
select ok(not exists (select 1 from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000107'), 'a row exactly at cutoff is excluded');
select ok(not exists (select 1 from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000110'), 'a row immediately before the 30-day window is excluded');
select ok(not exists (select 1 from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000105'), 'deleted users are excluded');
select ok(exists (select 1 from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000104'), 'stale account last_active_at does not affect ranking activity');

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000101', false);
select set_config('request.jwt.claim.role', 'authenticated', false);
set local role authenticated;
select is((select count(*)::int from public.rating_daily_snapshot), 1, 'authenticated sees exactly the own snapshot row');
select is((select count(*)::int from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000102'), 0, 'RLS hides another users snapshot row');
reset role;

update public.ratings set current_rating = 2500 where user_id = '00000000-0000-0000-0000-000000000108';
set local role service_role;
select is(public.refresh_rating_daily_snapshot('2026-08-21'::date), 6, 'same-date retry is idempotent in row count');
reset role;
select is((select rank from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000108'), 2, 'retry does not rewrite a published snapshot after current rating changes');
select throws_ok(
  $$select public.refresh_rating_daily_snapshot('2026-08-20'::date)$$,
  'P0001', 'cannot replace a newer rating snapshot', 'an older refresh cannot replace the latest snapshot'
);
select is((select count(distinct snapshot_date)::int from public.rating_daily_snapshot), 1, 'only the latest snapshot date is retained');

select * from finish();
rollback;

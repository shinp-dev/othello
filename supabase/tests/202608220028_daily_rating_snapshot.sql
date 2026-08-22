-- Daily rating snapshot contract and ranking calculation tests.
begin;
select plan(23);

select ok(to_regclass('public.rating_daily_snapshot') is not null, 'daily rating snapshot table exists');
select is((select count(*)::int from information_schema.columns
  where table_schema = 'public' and table_name = 'rating_daily_snapshot'
    and column_name in ('user_id', 'snapshot_date', 'rank', 'active_user_count', 'top_percentile')), 5,
  'snapshot exposes only the required user ranking fields');
select ok(exists (select 1 from pg_indexes where schemaname = 'public' and indexname = 'rating_daily_snapshot_date_idx'), 'snapshot date is indexed');
select ok(exists (select 1 from pg_class where oid = 'public.rating_daily_snapshot'::regclass and relrowsecurity), 'snapshot uses RLS');
select ok(has_table_privilege('authenticated', 'public.rating_daily_snapshot', 'select'), 'authenticated can read the own snapshot row');
select ok(not has_table_privilege('authenticated', 'public.rating_daily_snapshot', 'insert'), 'authenticated cannot write snapshots');
select ok(to_regprocedure('public.refresh_rating_daily_snapshot(date)') is not null, 'service refresh function exists');
select ok(has_function_privilege('service_role', 'public.refresh_rating_daily_snapshot(date)', 'execute'), 'only service role can refresh snapshots');
select ok(not has_function_privilege('authenticated', 'public.refresh_rating_daily_snapshot(date)', 'execute'), 'authenticated cannot refresh snapshots');
select ok(position('Asia/Tokyo' in pg_get_functiondef(to_regprocedure('public.refresh_rating_daily_snapshot(date)'))) > 0, 'refresh uses Tokyo date boundaries');
select ok(position('rank() over' in lower(pg_get_functiondef(to_regprocedure('public.refresh_rating_daily_snapshot(date)')))) > 0, 'refresh uses tied rank semantics');
select ok(position('365 days' in pg_get_functiondef(to_regprocedure('public.refresh_rating_daily_snapshot(date)'))) > 0, 'refresh reuses the existing active-account lifetime');
select ok(to_regprocedure('public.enqueue_or_match()') is not null, 'existing matchmaking RPC remains present');
select ok(to_regprocedure('public.submit_match_result(uuid,text,text,text,text,jsonb)') is not null, 'existing result RPC remains present');

insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data)
values
 ('00000000-0000-0000-0000-000000000101', 'authenticated', 'authenticated', 'rank-a@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000102', 'authenticated', 'authenticated', 'rank-b@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000103', 'authenticated', 'authenticated', 'rank-c@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000104', 'authenticated', 'authenticated', 'rank-expired@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000105', 'authenticated', 'authenticated', 'rank-deleted@example.test', '', now(), '{}', '{}'),
 ('00000000-0000-0000-0000-000000000106', 'authenticated', 'authenticated', 'rank-unconfirmed@example.test', '', null, '{}', '{}'),
 ('00000000-0000-0000-0000-000000000107', 'authenticated', 'authenticated', 'rank-after-boundary@example.test', '', now(), '{}', '{}');

update public.ratings set current_rating = case user_id
  when '00000000-0000-0000-0000-000000000101'::uuid then 1600
  when '00000000-0000-0000-0000-000000000102'::uuid then 1600
  when '00000000-0000-0000-0000-000000000103'::uuid then 1500
  when '00000000-0000-0000-0000-000000000104'::uuid then 1800
  when '00000000-0000-0000-0000-000000000105'::uuid then 1900
  when '00000000-0000-0000-0000-000000000107'::uuid then 2100
  else 2000 end;
update public.profiles set last_active_at = case id
  when '00000000-0000-0000-0000-000000000101'::uuid then '2026-08-21 01:00:00+09'::timestamptz
  when '00000000-0000-0000-0000-000000000102'::uuid then '2026-08-21 02:00:00+09'::timestamptz
  when '00000000-0000-0000-0000-000000000103'::uuid then '2026-08-21 03:00:00+09'::timestamptz
  when '00000000-0000-0000-0000-000000000104'::uuid then '2025-08-20 03:00:00+09'::timestamptz
  when '00000000-0000-0000-0000-000000000107'::uuid then '2026-08-22 00:01:00+09'::timestamptz
  else '2026-08-21 04:00:00+09'::timestamptz end;
update public.profiles set deleted_at = '2026-08-21 05:00:00+09'::timestamptz
 where id = '00000000-0000-0000-0000-000000000105';

select set_config('request.jwt.claim.role', 'service_role', false);
select is(public.refresh_rating_daily_snapshot('2026-08-21'::date), 3, 'refresh returns the active confirmed user count');
select is((select count(*)::int from public.rating_daily_snapshot), 3, 'only active users are stored');
select is((select count(*)::int from public.rating_daily_snapshot where snapshot_date = '2026-08-21'), 3, 'all rows use the requested Tokyo snapshot date');
select is((select rank from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000101'), 1, 'equal top ratings share rank one');
select is((select rank from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000103'), 3, 'rank uses gaps after ties');
select is((select top_percentile from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000101'), 33.3333::numeric, 'top percentile is rank divided by active count');
update public.ratings set current_rating = 1900 where user_id = '00000000-0000-0000-0000-000000000103';
select is(public.refresh_rating_daily_snapshot('2026-08-21'::date), 3, 'repeated refresh is idempotent in row count');
select is((select rank from public.rating_daily_snapshot where user_id = '00000000-0000-0000-0000-000000000101'), 1, 'repeated refresh does not rewrite an already published date');
select is((select count(distinct snapshot_date)::int from public.rating_daily_snapshot), 1, 'only the latest snapshot date is retained');

select * from finish();
rollback;

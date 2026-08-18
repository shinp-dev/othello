-- Account lifecycle contract tests for the initial release.
begin;
select plan(17);

select ok(exists (
  select 1 from information_schema.columns
   where table_schema = 'public'
     and table_name = 'profiles'
     and column_name = 'last_active_at'
     and is_nullable = 'NO'
), 'profiles store a required last_active_at timestamp');
select ok(exists (
  select 1 from information_schema.columns
   where table_schema = 'public'
     and table_name = 'profiles'
     and column_name = 'last_active_at'
     and column_default like 'now()%'
), 'new profiles receive a current last_active_at default');
select ok(exists (
  select 1 from pg_indexes
   where schemaname = 'public'
     and tablename = 'profiles'
     and indexname = 'profiles_last_active_at_idx'
), 'last_active_at has a maintenance index');

select ok(to_regprocedure('public.touch_last_active()') is not null, 'authenticated activity touch RPC exists');
select ok(has_function_privilege('authenticated', 'public.touch_last_active()', 'execute'), 'authenticated users can touch their own activity timestamp');
select ok(not has_function_privilege('anon', 'public.touch_last_active()', 'execute'), 'anonymous users cannot touch activity timestamps');
select ok(not exists (
  select 1 from pg_proc p
   cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
   where p.oid = 'public.touch_last_active()'::regprocedure
     and acl.grantee = 0
     and acl.privilege_type = 'EXECUTE'
), 'PUBLIC has no activity touch privilege');
select ok(position('last_active_at < now() - interval ''1 day''' in pg_get_functiondef(to_regprocedure('public.touch_last_active()'))) > 0,
  'activity touch is throttled to at most once per day');
select ok(position('deleted_at is null' in pg_get_functiondef(to_regprocedure('public.touch_last_active()'))) > 0,
  'activity touch ignores deletion tombstones');

select ok(to_regprocedure('public.queue_expired_account_deletions()') is not null, 'expired account queue RPC exists');
select ok(has_function_privilege('service_role', 'public.queue_expired_account_deletions()', 'execute'), 'only the trusted service role can queue expired accounts');
select ok(not has_function_privilege('authenticated', 'public.queue_expired_account_deletions()', 'execute'), 'authenticated users cannot queue expired accounts');
select ok(not has_function_privilege('anon', 'public.queue_expired_account_deletions()', 'execute'), 'anonymous users cannot queue expired accounts');
select ok(position('email_confirmed_at is null' in pg_get_functiondef(to_regprocedure('public.queue_expired_account_deletions()'))) > 0
  and position('interval ''7 days''' in pg_get_functiondef(to_regprocedure('public.queue_expired_account_deletions()'))) > 0,
  'unconfirmed registrations use the seven-day expiry rule');
select ok(position('email_confirmed_at is not null' in pg_get_functiondef(to_regprocedure('public.queue_expired_account_deletions()'))) > 0
  and position('interval ''365 days''' in pg_get_functiondef(to_regprocedure('public.queue_expired_account_deletions()'))) > 0,
  'confirmed inactive accounts use the 365-day expiry rule');
select ok(position('request_account_deletion' in lower(pg_get_functiondef(to_regprocedure('public.queue_expired_account_deletions()')))) = 0,
  'expiry queue does not duplicate or bypass the existing deletion request pipeline');
select ok(position('delete ' in lower(pg_get_functiondef(to_regprocedure('public.queue_expired_account_deletions()')))) = 0,
  'expiry queue does not delete data directly');

select * from finish();
rollback;

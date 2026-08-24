begin;
select plan(2);

select ok(
  (select is_nullable = 'NO'
     from information_schema.columns
    where table_schema = 'public'
      and table_name = 'game_records'
      and column_name = 'canonical_moves'),
  'game_records canonical_moves is required'
);

select ok(
  not exists (
    select 1
      from public.game_records
     where canonical_moves is null
  ),
  'game_records has no NULL canonical_moves rows'
);

select * from finish();
rollback;

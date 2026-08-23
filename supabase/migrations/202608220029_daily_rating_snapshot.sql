-- Migration 028 is intentionally unused. This additive daily ranking snapshot
-- is the canonical migration numbered 029 after the final production audit.
-- Only the latest snapshot is retained;
-- no existing rating table, RPC, RLS policy, or client contract is changed.

create table public.rating_daily_snapshot (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  snapshot_date date not null,
  rank integer not null check (rank > 0),
  active_user_count integer not null check (active_user_count > 0),
  top_percentile numeric(8, 4) not null check (top_percentile > 0 and top_percentile <= 100)
);

create index rating_daily_snapshot_date_idx
  on public.rating_daily_snapshot(snapshot_date);

alter table public.rating_daily_snapshot enable row level security;
create policy "users read own daily rating snapshot"
  on public.rating_daily_snapshot for select
  using (auth.uid() = user_id);

revoke all on table public.rating_daily_snapshot from public, anon, authenticated;
grant select on table public.rating_daily_snapshot to authenticated;

create or replace function public.refresh_rating_daily_snapshot(
  p_snapshot_date date default ((now() at time zone 'Asia/Tokyo')::date - 1)
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  inserted_count integer;
begin
  if p_snapshot_date is null then
    raise exception 'snapshot date is required';
  end if;

  -- PostgreSQL EXECUTE grants below are the caller boundary. This keeps both
  -- service-role API calls and a privileged DB-owned pg_cron job explicit;
  -- JWT claims are not available to an in-database cron execution.
  -- Serialize a refresh so retries cannot interleave delete/insert phases.
  perform pg_advisory_xact_lock(hashtextextended('rating_daily_snapshot_refresh', 0));
  select count(*)::integer into inserted_count
    from public.rating_daily_snapshot
   where snapshot_date = p_snapshot_date;
  if inserted_count > 0 then
    return inserted_count;
  end if;
  if exists (
    select 1 from public.rating_daily_snapshot where snapshot_date > p_snapshot_date
  ) then
    raise exception 'cannot replace a newer rating snapshot';
  end if;
  delete from public.rating_daily_snapshot;

  with bounds as (
    select (((p_snapshot_date + 1)::timestamp) at time zone 'Asia/Tokyo') as cutoff
  ), snapshot_ratings as (
    select distinct on (h.user_id)
      h.user_id,
      h.rating
    from public.rating_history h
    join public.profiles p on p.id = h.user_id
    cross join bounds b
    where p.deleted_at is null
      and h.created_at >= b.cutoff - interval '30 days'
      and h.created_at < b.cutoff
    order by h.user_id, h.created_at desc, h.id desc
  ), ranked as (
    select
      s.user_id,
      rank() over (order by s.rating desc) as user_rank,
      count(*) over () as users
    from snapshot_ratings s
  )
  insert into public.rating_daily_snapshot(user_id, snapshot_date, rank, active_user_count, top_percentile)
  select
    user_id,
    p_snapshot_date,
    user_rank::integer,
    users::integer,
    round((user_rank::numeric / users::numeric) * 100, 4)
  from ranked;

  get diagnostics inserted_count = row_count;
  return inserted_count;
end;
$$;

revoke all on function public.refresh_rating_daily_snapshot(date) from public, anon, authenticated;
grant execute on function public.refresh_rating_daily_snapshot(date) to service_role;

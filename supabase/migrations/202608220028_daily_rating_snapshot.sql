-- Additive daily ranking snapshot.  Only the latest snapshot is retained;
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
  if auth.role() <> 'service_role' then
    raise exception 'admin service role required';
  end if;

  -- Serialize a refresh so retries cannot interleave delete/insert phases.
  perform pg_advisory_xact_lock(hashtextextended('rating_daily_snapshot_refresh', 0));
  select count(*)::integer into inserted_count
    from public.rating_daily_snapshot
   where snapshot_date = p_snapshot_date;
  if inserted_count > 0 then
    return inserted_count;
  end if;
  delete from public.rating_daily_snapshot;

  with ranked as (
    select
      r.user_id,
      rank() over (order by r.current_rating desc) as user_rank,
      count(*) over () as users
    from public.ratings r
    join public.profiles p on p.id = r.user_id
    join auth.users u on u.id = r.user_id
    where u.email_confirmed_at is not null
      and p.deleted_at is null
      and p.last_active_at >= ((p_snapshot_date::timestamp at time zone 'Asia/Tokyo') - interval '365 days')
      and p.last_active_at < (((p_snapshot_date + 1)::timestamp) at time zone 'Asia/Tokyo')
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

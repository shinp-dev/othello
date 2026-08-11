-- Lifecycle correction: CREATED is the signaling lease only. A one-time start ack
-- transitions both participants to a longer-lived play lease without heartbeats.

alter table public.matches add column if not exists p2p_started_at timestamptz;
alter table public.matches add column if not exists play_lease_expires_at timestamptz;
alter table public.matches add column if not exists result_expires_at timestamptz;

create table if not exists public.match_start_acks (
  match_id uuid not null references public.matches(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  acked_at timestamptz not null default now(),
  primary key (match_id, user_id)
);
create index if not exists match_start_acks_user_idx on public.match_start_acks(user_id);
alter table public.match_start_acks enable row level security;
drop policy if exists "participants read own start ack" on public.match_start_acks;
create policy "participants read own start ack" on public.match_start_acks
  for select using (auth.uid() = user_id);

create index if not exists matches_created_lease_idx on public.matches(created_expires_at)
  where server_status = 'CREATED' and p2p_started_at is null;
create index if not exists matches_play_lease_idx on public.matches(play_lease_expires_at)
  where server_status = 'CREATED' and p2p_started_at is not null;
create index if not exists matches_result_expiry_idx on public.matches(result_expires_at)
  where server_status = 'PENDING_RESULT';
create index if not exists matches_retention_idx on public.matches(retention_until);

create or replace function public.ack_match_started(p_match_id uuid)
returns public.server_match_status
language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
declare ack_count integer;
declare play_expiry timestamptz;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if match_row.id is null or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'match participant required';
  end if;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then return match_row.server_status; end if;
  if match_row.server_status <> 'CREATED' then return match_row.server_status; end if;
  if match_row.p2p_started_at is null and match_row.created_expires_at <= now() then
    update public.matches set server_status = 'ABANDONED' where id = p_match_id;
    return 'ABANDONED';
  end if;
  insert into public.match_start_acks(match_id, user_id) values (p_match_id, caller_id) on conflict do nothing;
  select count(*)::int into ack_count
    from public.match_start_acks
   where match_id = p_match_id
     and user_id in (match_row.black_player, match_row.white_player);
  if ack_count = 2 and match_row.p2p_started_at is null then
    play_expiry := now() + interval '24 hours';
    update public.matches set p2p_started_at = now(), play_lease_expires_at = play_expiry where id = p_match_id;
    update public.active_match_participants set expires_at = play_expiry where match_id = p_match_id;
  end if;
  return 'CREATED';
end;
$$;

create or replace function public.cleanup_stale_created_matches()
returns integer language plpgsql security definer set search_path = '' as $$
declare changed_count integer;
begin
  -- Queue expiry and signaling expiry are safe matchmaking maintenance. Never delete an
  -- active reservation directly: terminal status transitions release it through the trigger.
  delete from public.match_queue where expires_at <= now();
  update public.matches set server_status = 'ABANDONED'
   where server_status = 'CREATED'
     and p2p_started_at is null
     and created_expires_at <= now();
  get diagnostics changed_count = row_count;
  return changed_count;
end;
$$;

create or replace function public.cleanup_expired_pending_results()
returns integer language plpgsql security definer set search_path = '' as $$
declare changed_count integer;
begin
  update public.matches set server_status = 'ABANDONED'
   where server_status = 'PENDING_RESULT' and result_expires_at <= now();
  get diagnostics changed_count = row_count;
  return changed_count;
end;
$$;

create or replace function public.enqueue_or_match()
returns table(match_id uuid, matched boolean, opponent_id uuid, assigned_disc text)
language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare caller_rating integer;
declare candidate public.match_queue%rowtype;
declare created_match public.matches%rowtype;
declare caller_is_black boolean;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('othello.enqueue_or_match', 0));
  perform public.cleanup_stale_created_matches();
  if exists (select 1 from public.active_match_participants where user_id = caller_id) then
    raise exception 'user already has an active match';
  end if;
  select current_rating into caller_rating from public.ratings where user_id = caller_id;
  if caller_rating is null then raise exception 'rating bootstrap missing'; end if;
  delete from public.match_queue where user_id = caller_id;
  select waiting.* into candidate from public.match_queue waiting
   where waiting.user_id <> caller_id and waiting.expires_at > now()
     and not exists (select 1 from public.active_match_participants active where active.user_id = waiting.user_id)
   order by abs(waiting.current_rating - caller_rating), waiting.queued_at for update skip locked limit 1;
  if candidate.user_id is null then
    insert into public.match_queue(user_id, current_rating, queued_at, expires_at)
    values (caller_id, caller_rating, now(), now() + interval '2 minutes');
    return query select null::uuid, false, null::uuid, null::text;
    return;
  end if;
  delete from public.match_queue where user_id = candidate.user_id;
  caller_is_black := random() < 0.5;
  if caller_is_black then
    insert into public.matches(black_player, white_player, status, server_status)
    values (caller_id, candidate.user_id, 'PLAYING', 'CREATED') returning * into created_match;
  else
    insert into public.matches(black_player, white_player, status, server_status)
    values (candidate.user_id, caller_id, 'PLAYING', 'CREATED') returning * into created_match;
  end if;
  begin
    insert into public.active_match_participants(user_id, match_id, expires_at)
    values (created_match.black_player, created_match.id, created_match.created_expires_at),
           (created_match.white_player, created_match.id, created_match.created_expires_at);
  exception when unique_violation then
    raise exception 'user already has an active match';
  end;
  return query select created_match.id, true, candidate.user_id,
    case when created_match.black_player = caller_id then 'BLACK' else 'WHITE' end;
end;
$$;

-- The 24-hour play lease is still bounded maintenance, but it is not a signaling cleanup.
create or replace function public.cleanup_expired_started_matches()
returns integer language plpgsql security definer set search_path = '' as $$
declare changed_count integer;
begin
  update public.matches set server_status = 'ABANDONED'
   where server_status = 'CREATED' and p2p_started_at is not null
     and play_lease_expires_at <= now();
  get diagnostics changed_count = row_count;
  return changed_count;
end;
$$;

revoke all on function public.ack_match_started(uuid) from public;
revoke all on function public.cleanup_expired_pending_results() from public;
revoke all on function public.cleanup_expired_started_matches() from public;
grant execute on function public.ack_match_started(uuid) to authenticated;
grant execute on function public.cleanup_expired_pending_results() to service_role;
grant execute on function public.cleanup_expired_started_matches() to service_role;

-- Narrow repairs discovered by the pre-E2E lifecycle review.

create or replace function public.get_match_start_state(p_match_id uuid)
returns table(server_status text, local_acked boolean, both_acked boolean)
language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into match_row from public.matches where id = p_match_id;
  if match_row.id is null or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'match participant required';
  end if;
  return query select
    match_row.server_status::text,
    exists (
      select 1 from public.match_start_acks
       where match_id = p_match_id and user_id = caller_id
    ),
    match_row.p2p_started_at is not null and (
      select count(*) from public.match_start_acks
       where match_id = p_match_id
         and user_id in (match_row.black_player, match_row.white_player)
    ) = 2;
end;
$$;

-- Matchmaking performs only caller reconciliation plus queue expiry. Global stale
-- match cleanup remains a maintenance RPC and is not run under this advisory lock.
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
  perform public.reconcile_expired_active_match_for_user();
  delete from public.match_queue where expires_at <= now();
  if exists (select 1 from public.active_match_participants where user_id = caller_id) then
    raise exception 'user already has an active match';
  end if;
  select current_rating into caller_rating from public.ratings where user_id = caller_id;
  if caller_rating is null then raise exception 'rating bootstrap missing'; end if;
  delete from public.match_queue where user_id = caller_id;
  select waiting.* into candidate from public.match_queue waiting
   where waiting.user_id <> caller_id and waiting.expires_at > now()
     and not exists (select 1 from public.active_match_participants active where active.user_id = waiting.user_id)
   order by abs(waiting.current_rating - caller_rating), waiting.queued_at
   for update skip locked limit 1;
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

create or replace function public.claim_waiting_match()
returns table(match_id uuid, opponent_id uuid, assigned_disc text)
language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare row_value public.matches%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  perform public.reconcile_expired_active_match_for_user();
  select m.* into row_value from public.matches m
  join public.match_notifications n on n.match_id = m.id and n.user_id = caller_id
  where m.server_status = 'CREATED'
    and m.p2p_started_at is null
    and m.created_expires_at > now()
  order by m.created_at for update skip locked limit 1;
  if row_value.id is null then return; end if;
  delete from public.match_notifications where user_id = caller_id and match_id = row_value.id;
  return query select row_value.id,
    case when row_value.black_player = caller_id then row_value.white_player else row_value.black_player end,
    case when row_value.black_player = caller_id then 'BLACK' else 'WHITE' end;
end;
$$;

revoke all on function public.get_match_start_state(uuid) from public;
revoke all on function public.enqueue_or_match() from public;
revoke all on function public.claim_waiting_match() from public;
grant execute on function public.get_match_start_state(uuid) to authenticated;
grant execute on function public.enqueue_or_match() to authenticated;
grant execute on function public.claim_waiting_match() to authenticated;

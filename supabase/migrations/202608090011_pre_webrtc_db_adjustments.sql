-- Final pre-WebRTC DB adjustments: keep result locks short, require P2P start,
-- and restrict verification uploads at the Storage bucket level.

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values (
  'verification',
  'verification',
  false,
  5242880,
  array['image/jpeg', 'image/png', 'image/webp']::text[]
)
on conflict (id) do update set
  name = excluded.name,
  public = false,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

-- Result submissions remain available for audit/diagnostics for their existing
-- 30-day period, but the one-user active reservation is only five minutes.
create or replace function public.set_pending_result_short_lease()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.server_status = 'PENDING_RESULT'
     and old.server_status is distinct from new.server_status then
    new.result_expires_at := now() + interval '5 minutes';
    update public.active_match_participants
       set expires_at = new.result_expires_at
     where match_id = new.id;
  end if;
  return new;
end;
$$;
drop trigger if exists set_pending_result_short_lease on public.matches;
create trigger set_pending_result_short_lease
before update of server_status on public.matches
for each row execute function public.set_pending_result_short_lease();

-- Bring already-pending rows to the same short reservation policy without
-- extending a row that is already expired.
update public.matches
   set result_expires_at = least(coalesce(result_expires_at, now() + interval '5 minutes'), now() + interval '5 minutes')
 where server_status = 'PENDING_RESULT';
update public.active_match_participants a
   set expires_at = m.result_expires_at
  from public.matches m
 where a.match_id = m.id and m.server_status = 'PENDING_RESULT';

create or replace function public.reconcile_expired_active_match_for_user()
returns integer language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare changed_count integer;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  update public.matches m
     set server_status = 'ABANDONED'
   where m.server_status in ('CREATED', 'PENDING_RESULT')
     and exists (
       select 1
         from public.active_match_participants a
        where a.match_id = m.id
          and a.user_id = caller_id
          and a.expires_at <= now()
     );
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
  perform public.reconcile_expired_active_match_for_user();
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

-- Keep the result RPC surface unchanged while making its insert boundary P2P-only.
create or replace function public.require_p2p_started_for_result()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if not exists (
    select 1 from public.matches
     where id = new.match_id and p2p_started_at is not null
  ) then
    raise exception 'match P2P not started';
  end if;
  return new;
end;
$$;
drop trigger if exists require_p2p_started_for_result on public.match_submissions;
create trigger require_p2p_started_for_result
before insert on public.match_submissions
for each row execute function public.require_p2p_started_for_result();

revoke all on function public.reconcile_expired_active_match_for_user() from public;
revoke all on function public.require_p2p_started_for_result() from public;
grant execute on function public.reconcile_expired_active_match_for_user() to authenticated;

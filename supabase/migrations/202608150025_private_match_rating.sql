-- Initial Play release privacy boundary: no client-readable profiles, no
-- user-authored display names, and only a server-owned opponent rating snapshot
-- returned to the two participants when a match is established.

alter table public.matches
  add column if not exists black_rating_at_start integer
    check (black_rating_at_start is null or black_rating_at_start > 0),
  add column if not exists white_rating_at_start integer
    check (white_rating_at_start is null or white_rating_at_start > 0);

-- The legacy display_name column remains for account-deletion tombstone
-- compatibility. It is no longer client-readable or client-writable, and Auth
-- metadata is deliberately ignored for new accounts.
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles(id, display_name)
  values (new.id, '非公開')
  on conflict (id) do nothing;
  insert into public.ratings(user_id, current_rating, peak_rating)
  values (new.id, public.initial_rating(), public.initial_rating())
  on conflict (user_id) do nothing;
  return new;
end;
$$;

revoke all on table public.public_profiles from public, anon, authenticated;
revoke all on table public.profiles from public, anon, authenticated;
drop policy if exists "profiles are public, owner updates" on public.profiles;
drop policy if exists "owner updates own profile" on public.profiles;

-- Federation credentials and evidence are not part of the initial release.
-- Existing rows remain for deletion compatibility, but normal clients cannot
-- read, create, or submit new values.
revoke all on table public.federation_credentials from public, anon, authenticated;
revoke all on table public.verification_submissions from public, anon, authenticated;
revoke execute on function public.submit_verification_submission(uuid, text) from public, anon, authenticated;
drop policy if exists "owner reads own credential" on public.federation_credentials;
drop policy if exists "owner self declares" on public.federation_credentials;
drop policy if exists "owner reads own submissions" on public.verification_submissions;
drop policy if exists "owner creates submission" on public.verification_submissions;
drop policy if exists "verification objects owner insert" on storage.objects;
drop policy if exists "verification objects owner read" on storage.objects;

drop function if exists public.enqueue_or_match();
create function public.enqueue_or_match()
returns table(
  match_id uuid,
  matched boolean,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer
)
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
    return query select null::uuid, false, null::uuid, null::text, null::integer;
    return;
  end if;
  delete from public.match_queue where user_id = candidate.user_id;
  caller_is_black := random() < 0.5;
  if caller_is_black then
    insert into public.matches(
      black_player, white_player, status, server_status,
      black_rating_at_start, white_rating_at_start
    ) values (
      caller_id, candidate.user_id, 'PLAYING', 'CREATED',
      caller_rating, candidate.current_rating
    ) returning * into created_match;
  else
    insert into public.matches(
      black_player, white_player, status, server_status,
      black_rating_at_start, white_rating_at_start
    ) values (
      candidate.user_id, caller_id, 'PLAYING', 'CREATED',
      candidate.current_rating, caller_rating
    ) returning * into created_match;
  end if;
  begin
    insert into public.active_match_participants(user_id, match_id, expires_at)
    values (created_match.black_player, created_match.id, created_match.created_expires_at),
           (created_match.white_player, created_match.id, created_match.created_expires_at);
  exception when unique_violation then
    raise exception 'user already has an active match';
  end;
  return query select created_match.id, true, candidate.user_id,
    case when created_match.black_player = caller_id then 'BLACK' else 'WHITE' end,
    candidate.current_rating;
end;
$$;

drop function if exists public.claim_waiting_match();
create function public.claim_waiting_match()
returns table(
  match_id uuid,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer
)
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
  delete from public.match_notifications n
  where n.user_id = caller_id and n.match_id = row_value.id;
  return query select row_value.id,
    case when row_value.black_player = caller_id then row_value.white_player else row_value.black_player end,
    case when row_value.black_player = caller_id then 'BLACK' else 'WHITE' end,
    case when row_value.black_player = caller_id
      then row_value.white_rating_at_start
      else row_value.black_rating_at_start
    end;
end;
$$;

revoke all on function public.enqueue_or_match() from public;
revoke all on function public.claim_waiting_match() from public;
grant execute on function public.enqueue_or_match() to authenticated;
grant execute on function public.claim_waiting_match() to authenticated;

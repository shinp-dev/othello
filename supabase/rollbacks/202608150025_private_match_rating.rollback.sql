-- EMERGENCY ROLLBACK ONLY for 202608150025_private_match_rating.sql.
--
-- This file is deliberately outside supabase/migrations so it cannot be applied
-- by a normal forward migration run. It restores the pre-025 client contract,
-- including public display-name/profile access, and therefore weakens the initial
-- Play release privacy boundary. Run only after OWNER approval.
--
-- The two rating snapshot columns are intentionally retained. Dropping them could
-- destroy snapshots written after cutover. The legacy RPCs below ignore them.

begin;

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
declare display_name_value text;
begin
  display_name_value := left(
    coalesce(nullif(btrim(new.raw_user_meta_data ->> 'display_name'), ''), 'プレイヤー'),
    40
  );
  insert into public.profiles(id, display_name)
  values (new.id, display_name_value)
  on conflict (id) do nothing;
  insert into public.ratings(user_id, current_rating, peak_rating)
  values (new.id, public.initial_rating(), public.initial_rating())
  on conflict (user_id) do nothing;
  return new;
end;
$$;

drop function if exists public.enqueue_or_match();
create function public.enqueue_or_match()
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

drop function if exists public.claim_waiting_match();
create function public.claim_waiting_match()
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
  delete from public.match_notifications n
  where n.user_id = caller_id and n.match_id = row_value.id;
  return query select row_value.id,
    case when row_value.black_player = caller_id then row_value.white_player else row_value.black_player end,
    case when row_value.black_player = caller_id then 'BLACK' else 'WHITE' end;
end;
$$;

revoke all on function public.enqueue_or_match() from public;
revoke all on function public.claim_waiting_match() from public;
grant execute on function public.enqueue_or_match() to authenticated;
grant execute on function public.claim_waiting_match() to authenticated;

grant select on table public.profiles to authenticated;
grant update (display_name) on table public.profiles to authenticated;
grant select on table public.public_profiles to anon, authenticated;

drop policy if exists "profiles are public, owner updates" on public.profiles;
create policy "profiles are public, owner updates"
  on public.profiles for select using (true);
drop policy if exists "owner updates own profile" on public.profiles;
create policy "owner updates own profile"
  on public.profiles for update
  using (auth.uid() = id) with check (auth.uid() = id);

grant select on table public.federation_credentials to authenticated;
grant insert on table public.federation_credentials to authenticated;
grant select on table public.verification_submissions to authenticated;
grant execute on function public.submit_verification_submission(uuid, text) to authenticated;

drop policy if exists "owner reads own credential" on public.federation_credentials;
create policy "owner reads own credential"
  on public.federation_credentials for select using (auth.uid() = user_id);
drop policy if exists "owner self declares" on public.federation_credentials;
create policy "owner self declares"
  on public.federation_credentials for insert
  with check (auth.uid() = user_id and status = 'SELF_DECLARED');
drop policy if exists "owner reads own submissions" on public.verification_submissions;
create policy "owner reads own submissions"
  on public.verification_submissions for select using (auth.uid() = user_id);
drop policy if exists "owner creates submission" on public.verification_submissions;
create policy "owner creates submission"
  on public.verification_submissions for insert
  with check (auth.uid() = user_id and status = 'PENDING');

drop policy if exists "verification objects owner insert" on storage.objects;
create policy "verification objects owner insert" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'verification'
    and name ~ ('^' || auth.uid()::text || '/[^/]+$')
    and name !~ '(^|/)\.\.(\/|$)'
  );
drop policy if exists "verification objects owner read" on storage.objects;
create policy "verification objects owner read" on storage.objects
  for select to authenticated
  using (
    bucket_id = 'verification'
    and name ~ ('^' || auth.uid()::text || '/[^/]+$')
  );

commit;

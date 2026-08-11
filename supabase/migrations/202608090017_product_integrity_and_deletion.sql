-- Final product-integrity pass: privacy-safe profile bootstrap, immutable final
-- position hashes, and a retryable trusted account-deletion workflow.

alter table public.profiles add column if not exists deleted_at timestamptz;

-- Shared matches and records retain a pseudonymous profile tombstone after the Auth
-- identity is removed. The trusted deletion worker anonymizes this row first.
alter table public.profiles drop constraint if exists profiles_id_fkey;

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

-- Older bootstrap code used the email local-part when no explicit display name was
-- supplied. Remove that accidental public disclosure without changing explicit names.
update public.profiles p
   set display_name = 'プレイヤー', updated_at = now()
  from auth.users u
 where p.id = u.id
   and nullif(btrim(u.raw_user_meta_data ->> 'display_name'), '') is null
   and u.email is not null
   and p.display_name = left(split_part(u.email, '@', 1), 40);

alter table public.game_records add column if not exists final_position_hash text;
alter table public.game_records drop constraint if exists game_records_final_position_hash_format;
alter table public.game_records add constraint game_records_final_position_hash_format
  check (final_position_hash is null or final_position_hash ~ '^[0-9a-f]{16}:[0-2]:[0-2]:[0-9]{1,3}$') not valid;

create or replace function public.populate_game_record_integrity()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.final_position_hash is null then
    select s.final_position_hash into new.final_position_hash
      from public.match_submissions s
     where s.match_id = new.match_id
       and s.player_id = new.players[1];
  end if;
  if new.final_position_hash is null then
    raise exception 'final position hash required';
  end if;
  if new.time_control = 'unknown' then new.time_control := '5m'; end if;
  return new;
end;
$$;
drop trigger if exists populate_game_record_integrity on public.game_records;
create trigger populate_game_record_integrity
before insert on public.game_records
for each row execute function public.populate_game_record_integrity();

-- Existing records predate final-hash persistence and remain nullable. Their fixed
-- production time control is nevertheless known.
update public.game_records set time_control = '5m' where time_control = 'unknown';

create or replace function public.prevent_deletion_requested_matchmaking()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if exists (
    select 1 from public.account_deletion_requests r
     where r.user_id = new.user_id and r.status in ('REQUESTED', 'PROCESSING')
  ) then
    raise exception 'account deletion is pending';
  end if;
  return new;
end;
$$;
drop trigger if exists prevent_deletion_requested_matchmaking on public.match_queue;
create trigger prevent_deletion_requested_matchmaking
before insert or update on public.match_queue
for each row execute function public.prevent_deletion_requested_matchmaking();

create or replace function public.request_account_deletion()
returns timestamptz language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare request_time timestamptz;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('othello.enqueue_or_match', 0));
  if exists (select 1 from public.active_match_participants a where a.user_id = caller_id) then
    raise exception 'active match must finish before account deletion';
  end if;
  delete from public.match_queue where user_id = caller_id;
  insert into public.account_deletion_requests(user_id, requested_at, status, completed_at)
  values (caller_id, now(), 'REQUESTED', null)
  on conflict (user_id) do update
    set requested_at = case
      when account_deletion_requests.status in ('COMPLETED', 'REJECTED') then now()
      else account_deletion_requests.requested_at
    end,
    status = case
      when account_deletion_requests.status in ('COMPLETED', 'REJECTED') then 'REQUESTED'
      else account_deletion_requests.status
    end,
    completed_at = case
      when account_deletion_requests.status in ('COMPLETED', 'REJECTED') then null
      else account_deletion_requests.completed_at
    end
  returning requested_at into request_time;
  return request_time;
end;
$$;

create or replace function public.get_account_deletion_evidence(p_user_id uuid)
returns text[] language plpgsql security definer set search_path = '' as $$
declare paths text[];
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if not exists (
    select 1 from public.account_deletion_requests
     where user_id = p_user_id and status in ('REQUESTED', 'PROCESSING')
  ) then raise exception 'account deletion request required'; end if;
  select coalesce(array_agg(o.name order by o.name), array[]::text[]) into paths
    from storage.objects o
   where o.bucket_id = 'verification'
     and o.name like p_user_id::text || '/%';
  return paths;
end;
$$;

create or replace function public.prepare_account_deletion(p_user_id uuid)
returns text language plpgsql security definer set search_path = '' as $$
declare request_status text;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  select status into request_status
    from public.account_deletion_requests
   where user_id = p_user_id for update;
  if request_status is null then raise exception 'account deletion request required'; end if;
  if request_status = 'COMPLETED' then return request_status; end if;
  if request_status not in ('REQUESTED', 'PROCESSING') then raise exception 'account deletion is not processable'; end if;
  if exists (select 1 from public.active_match_participants where user_id = p_user_id) then
    raise exception 'active match must finish before account deletion';
  end if;
  if exists (
    select 1 from storage.objects
     where bucket_id = 'verification' and name like p_user_id::text || '/%'
  ) then raise exception 'verification evidence cleanup required'; end if;

  update public.account_deletion_requests set status = 'PROCESSING' where user_id = p_user_id;
  delete from public.match_queue where user_id = p_user_id;
  delete from public.match_notifications where user_id = p_user_id;
  delete from public.match_signaling where sender_id = p_user_id;
  delete from public.match_start_acks where user_id = p_user_id;
  delete from public.match_submissions where player_id = p_user_id;
  delete from public.federation_credentials where user_id = p_user_id;
  delete from public.rating_history where user_id = p_user_id;
  delete from public.ratings where user_id = p_user_id;
  delete from public.user_game_records where user_id = p_user_id;
  delete from public.game_records g
   where not exists (select 1 from public.user_game_records r where r.match_id = g.match_id);
  update public.profiles
     set display_name = '退会済みユーザー', deleted_at = coalesce(deleted_at, now()), updated_at = now()
   where id = p_user_id;
  return 'PROCESSING';
end;
$$;

create or replace function public.complete_account_deletion(p_user_id uuid)
returns text language plpgsql security definer set search_path = '' as $$
declare request_status text;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  select status into request_status
    from public.account_deletion_requests
   where user_id = p_user_id for update;
  if request_status is null then raise exception 'account deletion request required'; end if;
  if request_status = 'COMPLETED' then return request_status; end if;
  if request_status <> 'PROCESSING' then raise exception 'account deletion is not prepared'; end if;
  update public.account_deletion_requests
     set status = 'COMPLETED', completed_at = coalesce(completed_at, now())
   where user_id = p_user_id;
  return 'COMPLETED';
end;
$$;

revoke all on function public.populate_game_record_integrity() from public, anon, authenticated;
revoke all on function public.prevent_deletion_requested_matchmaking() from public, anon, authenticated;
revoke all on function public.get_account_deletion_evidence(uuid) from public, anon, authenticated;
revoke all on function public.prepare_account_deletion(uuid) from public, anon, authenticated;
revoke all on function public.complete_account_deletion(uuid) from public, anon, authenticated;
revoke all on function public.request_account_deletion() from public, anon;
grant execute on function public.get_account_deletion_evidence(uuid) to service_role;
grant execute on function public.prepare_account_deletion(uuid) to service_role;
grant execute on function public.complete_account_deletion(uuid) to service_role;
grant execute on function public.request_account_deletion() to authenticated;

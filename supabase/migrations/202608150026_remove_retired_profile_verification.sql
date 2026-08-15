-- Remove pre-release profile and federation-verification surfaces that are not
-- part of CHANRIVA's initial product. Rating and Research history are kept.

drop view if exists public.public_profiles;

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles(id)
  values (new.id)
  on conflict (id) do nothing;
  insert into public.ratings(user_id, current_rating, peak_rating)
  values (new.id, public.initial_rating(), public.initial_rating())
  on conflict (user_id) do nothing;
  return new;
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

  update public.account_deletion_requests set status = 'PROCESSING' where user_id = p_user_id;
  delete from public.match_queue where user_id = p_user_id;
  delete from public.match_notifications where user_id = p_user_id;
  delete from public.match_signaling where sender_id = p_user_id;
  delete from public.match_start_acks where user_id = p_user_id;
  delete from public.match_submissions where player_id = p_user_id;
  delete from public.rating_history where user_id = p_user_id;
  delete from public.ratings where user_id = p_user_id;
  delete from public.user_game_records where user_id = p_user_id;
  delete from public.game_records g
   where not exists (select 1 from public.user_game_records r where r.match_id = g.match_id);
  update public.profiles
     set deleted_at = coalesce(deleted_at, now())
   where id = p_user_id;
  return 'PROCESSING';
end;
$$;

drop function if exists public.get_account_deletion_evidence(uuid);
drop function if exists public.submit_verification_submission(uuid, text);
drop function if exists public.review_verification_submission(uuid, public.credential_status);
drop function if exists public.get_verification_evidence_cleanup(uuid);
drop function if exists public.mark_verification_evidence_deleted(uuid);

drop table if exists public.verification_submissions;
drop table if exists public.federation_credentials;
drop type if exists public.credential_status;

alter table public.profiles
  drop column if exists display_name,
  drop column if exists created_at,
  drop column if exists updated_at;

revoke all on function public.prepare_account_deletion(uuid) from public, anon, authenticated;
grant execute on function public.prepare_account_deletion(uuid) to service_role;

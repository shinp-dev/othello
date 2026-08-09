-- Additive hardening migration.
-- Existing columns, enum values, and legacy RPCs remain for safe rollback. New clients use
-- the server_status column and the RPCs below. No client can write authoritative tables directly.

create type public.server_match_status as enum ('CREATED', 'PENDING_RESULT', 'CONFIRMED', 'DISPUTED', 'ABANDONED');

alter table public.matches add column server_status public.server_match_status not null default 'CREATED';
update public.matches set server_status = case status::text when 'CONFIRMED' then 'CONFIRMED' when 'PENDING_RESULT' then 'PENDING_RESULT' when 'DISPUTED' then 'DISPUTED' else 'CREATED' end::public.server_match_status;
alter table public.match_queue add column expires_at timestamptz not null default (now() + interval '2 minutes');
alter table public.match_submissions add column canonical_moves text;
alter table public.match_submissions add column expires_at timestamptz not null default (now() + interval '30 days');
alter table public.game_records add column canonical_moves text;
alter table public.verification_submissions add column evidence_deleted_at timestamptz;

alter table public.match_submissions add constraint match_submissions_canonical_moves_size check (canonical_moves is null or char_length(canonical_moves) <= 240);
alter table public.game_records add constraint game_records_canonical_moves_size check (canonical_moves is null or char_length(canonical_moves) <= 240);
create unique index if not exists rating_history_user_match_unique on public.rating_history(user_id, match_id) where match_id is not null;
alter table public.rating_history add constraint rating_history_match_required check (match_id is not null) not valid;
create index if not exists match_queue_expires_at_idx on public.match_queue(expires_at);
create index if not exists match_submissions_expires_at_idx on public.match_submissions(expires_at);

create table public.user_game_records (
  user_id uuid not null references public.profiles(id) on delete cascade,
  match_id uuid not null references public.game_records(match_id) on delete cascade,
  primary key (user_id, match_id)
);
alter table public.user_game_records enable row level security;
create policy "users read own record references" on public.user_game_records for select using (auth.uid() = user_id);

-- The legacy RPCs accept client-supplied ratings or arbitrary submissions. They remain in the
-- schema for rollback, but are no longer callable by browser/mobile roles.
revoke execute on function public.match_nearest_waiting(integer) from public, anon, authenticated;
revoke execute on function public.finalize_match(uuid) from public, anon, authenticated;
revoke all on public.match_queue from public, anon, authenticated;
revoke all on public.match_submissions from public, anon, authenticated;
revoke all on public.user_game_records from public, anon, authenticated;
revoke insert, update, delete on public.ratings from public, anon, authenticated;
revoke insert, update, delete on public.rating_history from public, anon, authenticated;
revoke insert, update, delete on public.game_records from public, anon, authenticated;
revoke insert, update, delete on public.verification_submissions from public, anon, authenticated;

create or replace function public.initial_rating()
returns integer language sql immutable as $$ select 1500 $$;

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
declare display_name_value text;
begin
  display_name_value := left(coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), split_part(coalesce(new.email, 'player'), '@', 1), 'player'), 40);
  insert into public.profiles(id, display_name) values (new.id, coalesce(nullif(display_name_value, ''), 'player')) on conflict (id) do nothing;
  insert into public.ratings(user_id, current_rating, peak_rating) values (new.id, public.initial_rating(), public.initial_rating()) on conflict (user_id) do nothing;
  return new;
end; $$;

drop trigger if exists othello_bootstrap_user on auth.users;
create trigger othello_bootstrap_user after insert on auth.users for each row execute procedure public.handle_new_user();

create or replace function public.enqueue_or_match()
returns table(match_id uuid, matched boolean, opponent_id uuid, assigned_disc text)
language plpgsql security definer set search_path = public as $$
declare caller_id uuid := auth.uid();
declare caller_rating integer;
declare candidate match_queue%rowtype;
declare created_match matches%rowtype;
declare caller_is_black boolean;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  perform pg_advisory_xact_lock(hashtextextended(caller_id::text, 0));
  if exists (select 1 from public.matches where caller_id in (black_player, white_player) and server_status in ('CREATED', 'PENDING_RESULT')) then
    raise exception 'user already has an active match';
  end if;
  select current_rating into caller_rating from public.ratings where user_id = caller_id;
  if caller_rating is null then raise exception 'rating bootstrap missing'; end if;
  delete from public.match_queue where user_id = caller_id;
  select waiting.* into candidate from public.match_queue waiting
   where waiting.user_id <> caller_id and waiting.expires_at > now()
     and not exists (select 1 from public.matches active_match where waiting.user_id in (active_match.black_player, active_match.white_player) and active_match.server_status in ('CREATED', 'PENDING_RESULT'))
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
    insert into public.matches(black_player, white_player, status, server_status) values (caller_id, candidate.user_id, 'PLAYING', 'CREATED') returning * into created_match;
  else
    insert into public.matches(black_player, white_player, status, server_status) values (candidate.user_id, caller_id, 'PLAYING', 'CREATED') returning * into created_match;
  end if;
  return query select created_match.id, true, candidate.user_id, case when created_match.black_player = caller_id then 'BLACK' else 'WHITE' end;
end; $$;

create or replace function public.cancel_waiting()
returns boolean language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  delete from public.match_queue where user_id = auth.uid();
  return found;
end; $$;

create or replace function public.heartbeat_waiting()
returns boolean language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  update public.match_queue set expires_at = now() + interval '2 minutes' where user_id = auth.uid() and expires_at > now();
  return found;
end; $$;

create or replace function public.submit_match_result(
  p_match_id uuid,
  p_canonical_moves text,
  p_result text,
  p_final_position_hash text,
  p_finish_reason text,
  p_clock jsonb default null
)
returns void language plpgsql security definer set search_path = public as $$
declare caller_id uuid := auth.uid();
declare match_row matches%rowtype;
declare existing match_submissions%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if char_length(p_canonical_moves) > 240 then raise exception 'move history is too large'; end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if match_row.id is null or caller_id not in (match_row.black_player, match_row.white_player) then raise exception 'match participant required'; end if;
  if match_row.server_status not in ('CREATED', 'PENDING_RESULT') then raise exception 'match no longer accepts a result'; end if;
  select * into existing from public.match_submissions where match_id = p_match_id and player_id = caller_id;
  if existing.match_id is not null then
    if existing.canonical_moves = p_canonical_moves and existing.result = p_result and existing.final_position_hash = p_final_position_hash and existing.finish_reason = p_finish_reason then return; end if;
    raise exception 'submission conflict for player';
  end if;
  insert into public.match_submissions(match_id, player_id, moves, canonical_moves, result, final_position_hash, finish_reason, clock)
  values (p_match_id, caller_id, to_jsonb(p_canonical_moves), p_canonical_moves, p_result, p_final_position_hash, p_finish_reason, p_clock);
end; $$;

create or replace function public.prune_user_game_records(p_user_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  delete from public.user_game_records r where r.user_id = p_user_id and r.match_id in (
    select match_id from (
      select r2.match_id, row_number() over (order by g.finished_at desc) as row_number
      from public.user_game_records r2 join public.game_records g using (match_id) where r2.user_id = p_user_id
    ) ranked where row_number > 50
  );
  delete from public.game_records g where not exists (select 1 from public.user_game_records r where r.match_id = g.match_id);
end; $$;

create or replace function public.prune_rating_history(p_user_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  delete from public.rating_history h where h.id in (
    select id from (
      select id, row_number() over (order by created_at desc) as row_number from public.rating_history where user_id = p_user_id
    ) ranked where row_number > 100
  );
end; $$;

create or replace function public.finalize_match_v2(p_match_id uuid)
returns public.server_match_status language plpgsql security definer set search_path = public as $$
declare match_row matches%rowtype;
declare black_submission match_submissions%rowtype;
declare white_submission match_submissions%rowtype;
declare record_inserted integer;
declare black_rating integer;
declare white_rating integer;
declare black_new_rating integer;
declare white_new_rating integer;
declare black_expected numeric;
declare white_expected numeric;
declare black_actual numeric;
declare white_actual numeric;
begin
  if auth.uid() is null or not exists (select 1 from public.matches where id = p_match_id and auth.uid() in (black_player, white_player)) then raise exception 'match access denied'; end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then return match_row.server_status; end if;
  select * into black_submission from public.match_submissions where match_id = p_match_id and player_id = match_row.black_player;
  select * into white_submission from public.match_submissions where match_id = p_match_id and player_id = match_row.white_player;
  if black_submission.match_id is null or white_submission.match_id is null then
    update public.matches set server_status = 'PENDING_RESULT' where id = p_match_id and server_status <> 'PENDING_RESULT';
    return 'PENDING_RESULT';
  end if;
  if black_submission.canonical_moves <> white_submission.canonical_moves or black_submission.result <> white_submission.result or black_submission.final_position_hash <> white_submission.final_position_hash or black_submission.finish_reason <> white_submission.finish_reason then
    update public.matches set server_status = 'DISPUTED' where id = p_match_id;
    return 'DISPUTED';
  end if;
  insert into public.game_records(match_id, players, moves, canonical_moves, result, started_at, finished_at, time_control, finish_reason)
  values (p_match_id, array[match_row.black_player, match_row.white_player], to_jsonb(black_submission.canonical_moves), black_submission.canonical_moves, black_submission.result, match_row.created_at, now(), 'unknown', black_submission.finish_reason)
  on conflict (match_id) do nothing;
  get diagnostics record_inserted = row_count;
  if record_inserted = 1 then
    perform 1 from public.ratings where user_id in (match_row.black_player, match_row.white_player) order by user_id for update;
    select current_rating into black_rating from public.ratings where user_id = match_row.black_player;
    select current_rating into white_rating from public.ratings where user_id = match_row.white_player;
    black_expected := 1.0 / (1.0 + power(10.0, (white_rating - black_rating) / 400.0));
    white_expected := 1.0 - black_expected;
    black_actual := case black_submission.result when 'BLACK_WIN' then 1.0 when 'WHITE_WIN' then 0.0 when 'DRAW' then 0.5 else null end;
    if black_actual is null then raise exception 'invalid canonical result'; end if;
    white_actual := 1.0 - black_actual;
    black_new_rating := round(black_rating + 32 * (black_actual - black_expected));
    white_new_rating := round(white_rating + 32 * (white_actual - white_expected));
    insert into public.rating_history(user_id, match_id, rating, delta, algorithm_version) values
      (match_row.black_player, p_match_id, black_new_rating, black_new_rating - black_rating, 'elo-v1'),
      (match_row.white_player, p_match_id, white_new_rating, white_new_rating - white_rating, 'elo-v1')
      on conflict (user_id, match_id) where match_id is not null do nothing;
    update public.ratings set current_rating = black_new_rating, peak_rating = greatest(peak_rating, black_new_rating), updated_at = now() where user_id = match_row.black_player;
    update public.ratings set current_rating = white_new_rating, peak_rating = greatest(peak_rating, white_new_rating), updated_at = now() where user_id = match_row.white_player;
    insert into public.user_game_records(user_id, match_id) values (match_row.black_player, p_match_id), (match_row.white_player, p_match_id) on conflict do nothing;
    perform public.prune_user_game_records(match_row.black_player);
    perform public.prune_user_game_records(match_row.white_player);
    perform public.prune_rating_history(match_row.black_player);
    perform public.prune_rating_history(match_row.white_player);
    delete from public.match_submissions where match_id = p_match_id;
  end if;
  update public.matches set server_status = 'CONFIRMED', confirmed_at = coalesce(confirmed_at, now()) where id = p_match_id and server_status <> 'CONFIRMED';
  return 'CONFIRMED';
end; $$;

create or replace function public.submit_verification_submission(p_credential_id uuid, p_evidence_path text)
returns uuid language plpgsql security definer set search_path = public as $$
declare credential_row federation_credentials%rowtype;
declare submission_id uuid;
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  select * into credential_row from public.federation_credentials where id = p_credential_id and user_id = auth.uid() for update;
  if credential_row.id is null then raise exception 'credential ownership required'; end if;
  if credential_row.status not in ('SELF_DECLARED', 'REJECTED') then raise exception 'credential is not submittable'; end if;
  insert into public.verification_submissions(credential_id, user_id, evidence_path, status)
  values (p_credential_id, auth.uid(), p_evidence_path, 'PENDING') returning id into submission_id;
  update public.federation_credentials set status = 'PENDING' where id = p_credential_id;
  return submission_id;
end; $$;

create or replace function public.review_verification_submission(p_submission_id uuid, p_decision public.credential_status)
returns text language plpgsql security definer set search_path = public as $$
declare submission verification_submissions%rowtype;
declare old_evidence_path text;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if p_decision not in ('VERIFIED', 'REJECTED') then raise exception 'invalid review decision'; end if;
  select * into submission from public.verification_submissions where id = p_submission_id for update;
  if submission.id is null then raise exception 'submission not found'; end if;
  if submission.status in ('VERIFIED', 'REJECTED') then return null; end if;
  old_evidence_path := submission.evidence_path;
  update public.verification_submissions set status = p_decision, reviewed_at = now() where id = p_submission_id;
  update public.federation_credentials set status = p_decision, verified_at = case when p_decision = 'VERIFIED' then now() else null end where id = submission.credential_id and user_id = submission.user_id;
  return old_evidence_path;
end; $$;

create view public.public_profiles as
select p.id, p.display_name, r.current_rating, r.peak_rating,
       coalesce(stable.stable_rating_band, 'CALCULATING') as stable_rating_band,
       credential.value as federation_grade, credential.status as federation_verification_status
from public.profiles p join public.ratings r on r.user_id = p.id
left join lateral (
  select case when count(*) < 5 then 'CALCULATING' else round(percentile_cont(0.2) within group (order by rating))::text || '-' || round(percentile_cont(0.8) within group (order by rating))::text end as stable_rating_band
  from (select rating from public.rating_history h where h.user_id = p.id order by created_at desc limit 50) recent
) stable on true
left join lateral (
  select value, status from public.federation_credentials c where c.user_id = p.id and c.organization = '日本オセロ連盟'
  order by case c.status when 'VERIFIED' then 0 when 'PENDING' then 1 when 'SELF_DECLARED' then 2 else 3 end, c.verified_at desc nulls last limit 1
) credential on true;
grant select on public.public_profiles to anon, authenticated;

create or replace function public.cleanup_expired_match_submissions()
returns integer language plpgsql security definer set search_path = public as $$
declare deleted_count integer;
begin
  delete from public.match_submissions where expires_at < now() and exists (select 1 from public.matches m where m.id = match_id and m.server_status in ('DISPUTED', 'ABANDONED'));
  get diagnostics deleted_count = row_count;
  return deleted_count;
end; $$;

create or replace function public.mark_verification_evidence_deleted(p_submission_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  update public.verification_submissions set evidence_deleted_at = coalesce(evidence_deleted_at, now()) where id = p_submission_id;
end; $$;

revoke all on function public.enqueue_or_match() from public;
revoke all on function public.cancel_waiting() from public;
revoke all on function public.heartbeat_waiting() from public;
revoke all on function public.submit_match_result(uuid, text, text, text, text, jsonb) from public;
revoke all on function public.finalize_match_v2(uuid) from public;
revoke all on function public.submit_verification_submission(uuid, text) from public;
revoke all on function public.review_verification_submission(uuid, public.credential_status) from public;
revoke all on function public.cleanup_expired_match_submissions() from public;
revoke all on function public.mark_verification_evidence_deleted(uuid) from public;
grant execute on function public.enqueue_or_match() to authenticated;
grant execute on function public.cancel_waiting() to authenticated;
grant execute on function public.heartbeat_waiting() to authenticated;
grant execute on function public.submit_match_result(uuid, text, text, text, text, jsonb) to authenticated;
grant execute on function public.finalize_match_v2(uuid) to authenticated;
grant execute on function public.submit_verification_submission(uuid, text) to authenticated;
grant execute on function public.review_verification_submission(uuid, public.credential_status) to service_role;
grant execute on function public.cleanup_expired_match_submissions() to service_role;
grant execute on function public.mark_verification_evidence_deleted(uuid) to service_role;

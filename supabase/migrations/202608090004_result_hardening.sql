-- Result submission follow-up: bounded payloads and atomic submit/finalize.

alter table public.game_records add column if not exists expires_at timestamptz not null default (now() + interval '365 days');
alter table public.match_submissions add constraint match_submissions_result_allowed
  check (result in ('BLACK_WIN', 'WHITE_WIN', 'DRAW')) not valid;
alter table public.match_submissions add constraint match_submissions_finish_reason_allowed
  check (finish_reason in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT')) not valid;
alter table public.match_submissions add constraint match_submissions_final_hash_format
  check (final_position_hash ~ '^[0-9a-f]{16}:[0-2]:[0-2]:[0-9]{1,3}$') not valid;
alter table public.match_submissions add constraint match_submissions_clock_size
  check (clock is null or pg_column_size(clock) <= 4096) not valid;
alter table public.match_submissions add constraint match_submissions_canonical_moves_format
  check (canonical_moves ~ '^((--|[a-h][1-8])+)$') not valid;
alter table public.game_records add constraint game_records_result_allowed
  check (result in ('BLACK_WIN', 'WHITE_WIN', 'DRAW')) not valid;
alter table public.game_records add constraint game_records_finish_reason_allowed
  check (finish_reason in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT')) not valid;
alter table public.game_records add constraint game_records_canonical_moves_format
  check (canonical_moves ~ '^((--|[a-h][1-8])+)$') not valid;

drop function public.submit_match_result(uuid, text, text, text, text, jsonb);
create function public.submit_match_result(
  p_match_id uuid,
  p_canonical_moves text,
  p_result text,
  p_final_position_hash text,
  p_finish_reason text,
  p_clock jsonb default null
)
returns public.server_match_status
language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
declare existing public.match_submissions%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if p_canonical_moves is null or char_length(p_canonical_moves) > 240
     or p_canonical_moves !~ '^((--|[a-h][1-8])+)$' then raise exception 'invalid canonical moves'; end if;
  if p_result not in ('BLACK_WIN', 'WHITE_WIN', 'DRAW') then raise exception 'invalid result'; end if;
  if p_finish_reason not in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT') then raise exception 'invalid finish reason'; end if;
  if p_final_position_hash is null or p_final_position_hash !~ '^[0-9a-f]{16}:[0-2]:[0-2]:[0-9]{1,3}$' then raise exception 'invalid final position hash'; end if;
  if p_clock is not null and pg_column_size(p_clock) > 4096 then raise exception 'clock payload is too large'; end if;

  select * into match_row from public.matches where id = p_match_id for update;
  if match_row.id is null or caller_id not in (match_row.black_player, match_row.white_player) then raise exception 'match participant required'; end if;
  select * into existing from public.match_submissions where match_id = p_match_id and player_id = caller_id;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then return match_row.server_status; end if;
  if existing.match_id is not null then
    if existing.canonical_moves <> p_canonical_moves or existing.result <> p_result
       or existing.final_position_hash <> p_final_position_hash or existing.finish_reason <> p_finish_reason then
      raise exception 'submission conflict for player';
    end if;
  else
    insert into public.match_submissions(match_id, player_id, moves, canonical_moves, result, final_position_hash, finish_reason, clock)
    values (p_match_id, caller_id, to_jsonb(p_canonical_moves), p_canonical_moves, p_result, p_final_position_hash, p_finish_reason, p_clock);
  end if;
  -- The second submission and finalization are one transaction. Retrying a terminal match
  -- returns the DB status without applying a second rating update.
  return public.finalize_match_v2(p_match_id);
end;
$$;

create or replace function public.finalize_match_v2(p_match_id uuid)
returns public.server_match_status
language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
declare black_submission public.match_submissions%rowtype;
declare white_submission public.match_submissions%rowtype;
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
  if caller_id is null or not exists (select 1 from public.matches where id = p_match_id and caller_id in (black_player, white_player)) then raise exception 'match access denied'; end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then return match_row.server_status; end if;
  select * into black_submission from public.match_submissions where match_id = p_match_id and player_id = match_row.black_player;
  select * into white_submission from public.match_submissions where match_id = p_match_id and player_id = match_row.white_player;
  if black_submission.match_id is null or white_submission.match_id is null then
    update public.matches set server_status = 'PENDING_RESULT' where id = p_match_id;
    return 'PENDING_RESULT';
  end if;
  if black_submission.canonical_moves <> white_submission.canonical_moves
     or black_submission.result <> white_submission.result
     or black_submission.final_position_hash <> white_submission.final_position_hash
     or black_submission.finish_reason <> white_submission.finish_reason then
    update public.matches set server_status = 'DISPUTED' where id = p_match_id;
    return 'DISPUTED';
  end if;

  insert into public.game_records(match_id, players, moves, canonical_moves, result, started_at, finished_at, time_control, finish_reason, expires_at)
  values (p_match_id, array[match_row.black_player, match_row.white_player], to_jsonb(black_submission.canonical_moves), black_submission.canonical_moves,
          black_submission.result, match_row.created_at, now(), 'unknown', black_submission.finish_reason, now() + interval '365 days')
  on conflict (match_id) do nothing;
  get diagnostics record_inserted = row_count;
  if record_inserted = 1 then
    perform 1 from public.ratings where user_id in (match_row.black_player, match_row.white_player) order by user_id for update;
    select current_rating into black_rating from public.ratings where user_id = match_row.black_player;
    select current_rating into white_rating from public.ratings where user_id = match_row.white_player;
    black_expected := 1.0 / (1.0 + power(10.0, (white_rating - black_rating) / 400.0));
    white_expected := 1.0 - black_expected;
    black_actual := case black_submission.result when 'BLACK_WIN' then 1.0 when 'WHITE_WIN' then 0.0 when 'DRAW' then 0.5 end;
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
  update public.matches set server_status = 'CONFIRMED', confirmed_at = coalesce(confirmed_at, now()) where id = p_match_id;
  return 'CONFIRMED';
end;
$$;

revoke all on function public.submit_match_result(uuid, text, text, text, text, jsonb) from public;
revoke all on function public.finalize_match_v2(uuid) from public;
revoke execute on function public.prune_user_game_records(uuid) from public, anon, authenticated;
revoke execute on function public.prune_rating_history(uuid) from public, anon, authenticated;
grant execute on function public.submit_match_result(uuid, text, text, text, text, jsonb) to authenticated;
grant execute on function public.finalize_match_v2(uuid) to authenticated;

-- Release match hardening, additive coexistence stage.
--
-- Protocol 1 remains available to the already distributed closed-test APK. Protocol 2
-- uses a separate lifecycle, result authority, and signaling write surface. Nothing in
-- this migration enables a production scheduler or removes a protocol-1 column/RPC.

create type public.release_match_status as enum (
  'MATCHED',
  'ACTIVE',
  'RECONNECTING',
  'RESULT_PENDING',
  'CONFIRMED',
  'DISPUTED',
  'FORFEIT',
  'EXPIRED',
  'ABANDONED'
);

alter table public.matches
  add column protocol_version integer not null default 1,
  add column release_status public.release_match_status,
  add column release_deadline timestamptz,
  add column reconnect_deadline timestamptz,
  add column release_started_at timestamptz,
  add column release_terminal_at timestamptz,
  add column release_updated_at timestamptz not null default now(),
  add column release_terminal_reason text,
  add column negotiation_epoch integer not null default 0,
  add column black_queue_request_id uuid,
  add column white_queue_request_id uuid,
  add column black_disconnect_claimed_at timestamptz,
  add column white_disconnect_claimed_at timestamptz;

alter table public.matches
  add constraint matches_protocol_version_allowed
    check (protocol_version in (1, 2)) not valid,
  add constraint matches_release_contract_shape
    check (
      (protocol_version = 1 and release_status is null)
      or
      (protocol_version = 2 and release_status is not null and release_deadline is not null)
    ) not valid,
  add constraint matches_negotiation_epoch_budget
    check (negotiation_epoch between 0 and 3) not valid,
  add constraint matches_players_distinct
    check (black_player <> white_player) not valid,
  add constraint matches_release_reconnect_shape
    check (
      protocol_version = 1
      or (
        (release_status = 'RECONNECTING' and reconnect_deadline is not null)
        or (release_status <> 'RECONNECTING' and reconnect_deadline is null)
      )
    ) not valid,
  add constraint matches_release_terminal_shape
    check (
      protocol_version = 1
      or (
        (release_status in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED')
          and release_terminal_at is not null and release_terminal_reason is not null)
        or
        (release_status not in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED')
          and release_terminal_at is null and release_terminal_reason is null)
      )
    ) not valid,
  add constraint matches_release_terminal_reason_format
    check (
      release_terminal_reason is null
      or release_terminal_reason ~ '^[A-Z0-9_]{1,64}$'
    ) not valid;

create index matches_release_deadline_idx
  on public.matches(release_deadline, id)
  where protocol_version = 2
    and release_status in ('MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING');
create index matches_release_participant_idx
  on public.matches(black_player, white_player, release_updated_at desc)
  where protocol_version = 2;

alter table public.match_queue
  add column protocol_version integer not null default 1,
  add column request_id uuid;
alter table public.match_queue
  add constraint match_queue_protocol_version_allowed
    check (protocol_version in (1, 2)) not valid,
  add constraint match_queue_v2_request_required
    check (protocol_version = 1 or request_id is not null) not valid;
create unique index match_queue_v2_request_unique
  on public.match_queue(user_id, request_id)
  where protocol_version = 2;
create index match_queue_v2_candidate_idx
  on public.match_queue(current_rating, queued_at, user_id)
  where protocol_version = 2;

-- Existing records may legitimately predate canonical serialization. Protocol-2 records
-- cannot: the final contract is NOT NULL without fabricating a legacy line.
alter table public.game_records
  add column result_contract_version integer not null default 1;
alter table public.game_records
  add constraint game_records_v2_canonical_required
    check (result_contract_version <> 2 or canonical_moves is not null) not valid;

create table public.match_start_acks_v2 (
  match_id uuid not null references public.matches(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  negotiation_epoch integer not null
    constraint match_start_acks_v2_negotiation_epoch_budget
    check (negotiation_epoch between 0 and 3),
  acked_at timestamptz not null default now(),
  primary key (match_id, user_id, negotiation_epoch)
);

create table public.match_result_claims_v2 (
  match_id uuid not null references public.matches(id) on delete cascade,
  player_id uuid not null references public.profiles(id) on delete cascade,
  request_id uuid not null,
  negotiation_epoch integer not null
    constraint match_result_claims_v2_negotiation_epoch_budget
    check (negotiation_epoch between 0 and 3),
  canonical_moves text not null check (
    char_length(canonical_moves) <= 240
    and canonical_moves ~ '^((--|[a-h][1-8])*)$'
  ),
  finish_reason text not null check (
    finish_reason in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT')
  ),
  loser_disc text check (loser_disc is null or loser_disc in ('BLACK', 'WHITE')),
  clock jsonb check (clock is null or pg_column_size(clock) <= 4096),
  derived_position_hash text not null check (
    derived_position_hash ~ '^[0-9a-f]{16}:[0-2]:[0-2]:[0-9]{1,3}$'
  ),
  derived_board_result text not null check (
    derived_board_result in ('BLACK_WIN', 'WHITE_WIN', 'DRAW')
  ),
  derived_black_count integer not null check (derived_black_count between 0 and 64),
  derived_white_count integer not null check (derived_white_count between 0 and 64),
  submitted_at timestamptz not null default now(),
  check (
    (finish_reason = 'NORMAL' and loser_disc is null)
    or (finish_reason <> 'NORMAL' and loser_disc is not null)
  ),
  primary key (match_id, player_id, request_id),
  unique (player_id, request_id)
);
create index match_result_claims_v2_request_idx
  on public.match_result_claims_v2(player_id, request_id);
create unique index match_result_claims_v2_normal_vote_unique
  on public.match_result_claims_v2(match_id, player_id, negotiation_epoch)
  where finish_reason = 'NORMAL';
create unique index match_result_claims_v2_nonnormal_report_unique
  on public.match_result_claims_v2(
    match_id, player_id, negotiation_epoch, finish_reason, loser_disc
  ) where finish_reason <> 'NORMAL';

create table public.match_results_v2 (
  match_id uuid primary key references public.matches(id) on delete cascade,
  terminal_status public.release_match_status not null check (
    terminal_status in ('CONFIRMED', 'FORFEIT')
  ),
  canonical_moves text not null check (
    char_length(canonical_moves) <= 240
    and canonical_moves ~ '^((--|[a-h][1-8])*)$'
  ),
  final_result text not null check (final_result in ('BLACK_WIN', 'WHITE_WIN', 'DRAW')),
  finish_reason text not null check (
    finish_reason in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT')
  ),
  loser_disc text check (loser_disc is null or loser_disc in ('BLACK', 'WHITE')),
  final_position_hash text not null check (
    final_position_hash ~ '^[0-9a-f]{16}:[0-2]:[0-2]:[0-9]{1,3}$'
  ),
  black_count integer not null check (black_count between 0 and 64),
  white_count integer not null check (white_count between 0 and 64),
  ruleset_version integer not null default 1 check (ruleset_version = 1),
  result_digest bytea not null unique check (octet_length(result_digest) = 32),
  finalized_at timestamptz not null default now(),
  check (
    (terminal_status = 'CONFIRMED' and finish_reason = 'NORMAL' and loser_disc is null)
    or
    (terminal_status = 'FORFEIT' and finish_reason <> 'NORMAL' and loser_disc is not null)
  )
);

create table public.match_signals_v2 (
  id bigint generated always as identity primary key,
  match_id uuid not null references public.matches(id) on delete cascade,
  negotiation_epoch integer not null
    constraint match_signals_v2_negotiation_epoch_budget
    check (negotiation_epoch between 0 and 3),
  sender_id uuid not null references public.profiles(id) on delete cascade,
  signal_type text not null check (signal_type in ('OFFER', 'ANSWER', 'RESUME')),
  sdp text not null,
  protocol_version integer not null check (protocol_version = 2),
  signal_slot integer not null check (signal_slot between 1 and 4),
  payload_digest bytea not null check (octet_length(payload_digest) = 32),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  unique (match_id, negotiation_epoch, sender_id, signal_type, signal_slot),
  unique (match_id, negotiation_epoch, sender_id, signal_type, payload_digest),
  check (expires_at > created_at),
  check (octet_length(convert_to(sdp, 'UTF8')) between 1 and 16384)
);
create index match_signals_v2_match_id_idx
  on public.match_signals_v2(match_id, negotiation_epoch, id);
create index match_signals_v2_expiry_idx
  on public.match_signals_v2(expires_at, id);

alter table public.match_start_acks_v2 enable row level security;
alter table public.match_result_claims_v2 enable row level security;
alter table public.match_results_v2 enable row level security;
alter table public.match_signals_v2 enable row level security;

create policy "participants read release match results"
  on public.match_results_v2 for select to authenticated
  using (exists (
    select 1 from public.matches m
     where m.id = match_id and auth.uid() in (m.black_player, m.white_player)
  ));
create policy "participants read live release signaling"
  on public.match_signals_v2 for select to authenticated
  using (
    expires_at > now()
    and exists (
      select 1 from public.matches m
       where m.id = match_id
         and m.protocol_version = 2
         and m.release_status in ('MATCHED', 'RECONNECTING')
         and public.match_signals_v2.negotiation_epoch = m.negotiation_epoch
         and auth.uid() in (m.black_player, m.white_player)
    )
  );

-- The legacy signaling table remains writable for protocol-1 APKs only. A protocol-2
-- participant cannot relabel a legacy row as version 1 to bypass the v2 publish RPC.
drop policy if exists "participants read signaling" on public.match_signaling;
create policy "participants read signaling"
  on public.match_signaling for select to authenticated
  using (exists (
    select 1 from public.matches m
     where m.id = match_id and m.protocol_version = 1
       and auth.uid() in (m.black_player, m.white_player)
  ));
drop policy if exists "participants write signaling" on public.match_signaling;
create policy "participants write signaling"
  on public.match_signaling for insert to authenticated
  with check (
    auth.uid() = sender_id
    and protocol_version = 1
    and exists (
      select 1 from public.matches m
       where m.id = match_id and m.protocol_version = 1
         and auth.uid() in (m.black_player, m.white_player)
    )
  );

-- Protocol-1 APKs write directly to match_signaling, so the compatibility boundary
-- must be enforced by a trigger rather than by replacing that INSERT with an RPC.
-- A match-wide advisory lock makes both per-sender and whole-match counts exact even
-- when retrying peers publish concurrently after a lost HTTP response.
create function public.enforce_match_signaling_v1_budget()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  match_row public.matches%rowtype;
  match_signal_count integer;
  sender_signal_count integer;
begin
  if auth.uid() is null or auth.uid() is distinct from new.sender_id then
    raise exception 'legacy signaling participant required';
  end if;
  if new.protocol_version is distinct from 1
     or new.signal_type not in ('OFFER', 'ANSWER') then
    raise exception 'invalid legacy signaling contract';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('legacy-signal-match:' || new.match_id::text, 0)
  );
  select * into match_row
    from public.matches m
   where m.id = new.match_id
   for share;
  if not found or match_row.protocol_version <> 1
     or new.sender_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'legacy signaling participant required';
  end if;
  if match_row.server_status <> 'CREATED'
     or match_row.p2p_started_at is not null
     or match_row.created_expires_at <= now() then
    raise exception 'legacy match does not accept signaling';
  end if;
  if (new.signal_type = 'OFFER' and new.sender_id <> match_row.black_player)
     or (new.signal_type = 'ANSWER' and new.sender_id <> match_row.white_player) then
    raise exception 'legacy signal role does not match assigned disc';
  end if;

  select count(*)::integer into match_signal_count
    from public.match_signaling s
   where s.match_id = new.match_id;
  select count(*)::integer into sender_signal_count
    from public.match_signaling s
   where s.match_id = new.match_id and s.sender_id = new.sender_id;
  if sender_signal_count >= 4 then
    raise exception 'legacy signaling sender limit exceeded';
  end if;
  if match_signal_count >= 8 then
    raise exception 'legacy signaling match limit exceeded';
  end if;
  return new;
end;
$$;

drop trigger if exists enforce_match_signaling_v1_budget on public.match_signaling;
create trigger enforce_match_signaling_v1_budget
before insert on public.match_signaling
for each row execute function public.enforce_match_signaling_v1_budget();

-- A database invariant, rather than each individual RPC, owns the state graph.
create function public.enforce_release_match_transition_v2()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  old_terminal boolean;
  new_terminal boolean;
begin
  if tg_op = 'INSERT' then
    if new.protocol_version = 1 and new.release_status is not null then
      raise exception 'protocol 1 cannot use release lifecycle';
    end if;
    if new.protocol_version = 2 and new.release_status <> 'MATCHED' then
      raise exception 'protocol 2 must begin MATCHED';
    end if;
    new.release_updated_at := now();
    return new;
  end if;

  if old.protocol_version <> new.protocol_version then
    raise exception 'match protocol version is immutable';
  end if;
  if new.protocol_version = 1 then
    if new.release_status is not null then
      raise exception 'protocol 1 cannot use release lifecycle';
    end if;
    return new;
  end if;
  if new.release_status is null then raise exception 'release status required'; end if;

  old_terminal := old.release_status in (
    'CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED'
  );
  new_terminal := new.release_status in (
    'CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED'
  );
  if old_terminal and new.release_status is distinct from old.release_status then
    raise exception 'terminal release match is immutable';
  end if;
  if old_terminal and row(
    new.black_player,
    new.white_player,
    new.black_rating_at_start,
    new.white_rating_at_start,
    new.server_status,
    new.confirmed_at,
    new.release_deadline,
    new.reconnect_deadline,
    new.release_started_at,
    new.release_terminal_at,
    new.release_updated_at,
    new.release_terminal_reason,
    new.negotiation_epoch,
    new.black_queue_request_id,
    new.white_queue_request_id,
    new.black_disconnect_claimed_at,
    new.white_disconnect_claimed_at
  ) is distinct from row(
    old.black_player,
    old.white_player,
    old.black_rating_at_start,
    old.white_rating_at_start,
    old.server_status,
    old.confirmed_at,
    old.release_deadline,
    old.reconnect_deadline,
    old.release_started_at,
    old.release_terminal_at,
    old.release_updated_at,
    old.release_terminal_reason,
    old.negotiation_epoch,
    old.black_queue_request_id,
    old.white_queue_request_id,
    old.black_disconnect_claimed_at,
    old.white_disconnect_claimed_at
  ) then
    raise exception 'terminal release match metadata is immutable';
  end if;
  if new.release_status is distinct from old.release_status and not (
    (old.release_status = 'MATCHED' and new.release_status in ('ACTIVE', 'EXPIRED', 'ABANDONED'))
    or (old.release_status = 'ACTIVE' and new.release_status in (
      'RECONNECTING', 'RESULT_PENDING', 'CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED'
    ))
    or (old.release_status = 'RECONNECTING' and new.release_status in (
      'ACTIVE', 'RESULT_PENDING', 'CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED'
    ))
    or (old.release_status = 'RESULT_PENDING' and new.release_status in (
      'CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED'
    ))
  ) then
    raise exception 'invalid release match transition: % -> %', old.release_status, new.release_status;
  end if;

  new.release_updated_at := case when row(
    new.release_status,
    new.release_deadline,
    new.reconnect_deadline,
    new.release_started_at,
    new.release_terminal_at,
    new.release_terminal_reason,
    new.negotiation_epoch,
    new.black_disconnect_claimed_at,
    new.white_disconnect_claimed_at
  ) is distinct from row(
    old.release_status,
    old.release_deadline,
    old.reconnect_deadline,
    old.release_started_at,
    old.release_terminal_at,
    old.release_terminal_reason,
    old.negotiation_epoch,
    old.black_disconnect_claimed_at,
    old.white_disconnect_claimed_at
  ) then now() else old.release_updated_at end;
  if new_terminal then
    new.release_terminal_at := coalesce(old.release_terminal_at, new.release_terminal_at, now());
    new.release_terminal_reason := coalesce(
      new.release_terminal_reason,
      case new.release_status
        when 'CONFIRMED' then 'NORMAL_CONFIRMED'
        when 'FORFEIT' then 'FORFEIT_CONFIRMED'
        else new.release_status::text
      end
    );
    new.release_deadline := coalesce(new.release_deadline, now());
    new.reconnect_deadline := null;
  elsif new.release_terminal_reason is not null or new.release_terminal_at is not null then
    raise exception 'nonterminal match cannot have terminal metadata';
  end if;
  return new;
end;
$$;

create trigger enforce_release_match_transition_v2
before insert or update on public.matches
for each row execute function public.enforce_release_match_transition_v2();

create function public.cleanup_release_match_notifications_v2()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.protocol_version = 2
     and new.release_status in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED')
     and new.release_status is distinct from old.release_status then
    delete from public.match_notifications n where n.match_id = new.id;
  end if;
  return new;
end;
$$;

create trigger cleanup_release_match_notifications_v2
after update of release_status on public.matches
for each row execute function public.cleanup_release_match_notifications_v2();

-- Small deterministic Othello helpers. Board cells are 1-based PostgreSQL arrays;
-- public coordinates remain zero-based internally and canonical text remains a1-h8/--.
create function public.release_is_legal_move_v2(
  p_board smallint[], p_move integer, p_player integer
)
returns boolean
language plpgsql
immutable
security definer
set search_path = ''
as $$
declare
  dr integer[] := array[-1,-1,-1,0,0,1,1,1];
  dc integer[] := array[-1,0,1,-1,1,-1,0,1];
  direction_index integer;
  row_value integer;
  column_value integer;
  seen_opponent boolean;
  opponent integer := case p_player when 1 then 2 else 1 end;
begin
  if p_player not in (1, 2) or p_move not between 0 and 63
     or p_board[p_move + 1] <> 0 then return false; end if;
  for direction_index in 1..8 loop
    row_value := p_move / 8 + dr[direction_index];
    column_value := p_move % 8 + dc[direction_index];
    seen_opponent := false;
    while row_value between 0 and 7 and column_value between 0 and 7
      and p_board[row_value * 8 + column_value + 1] = opponent
    loop
      seen_opponent := true;
      row_value := row_value + dr[direction_index];
      column_value := column_value + dc[direction_index];
    end loop;
    if seen_opponent and row_value between 0 and 7 and column_value between 0 and 7
       and p_board[row_value * 8 + column_value + 1] = p_player then
      return true;
    end if;
  end loop;
  return false;
end;
$$;

create function public.release_has_legal_move_v2(p_board smallint[], p_player integer)
returns boolean
language plpgsql
immutable
security definer
set search_path = ''
as $$
declare move_index integer;
begin
  for move_index in 0..63 loop
    if public.release_is_legal_move_v2(p_board, move_index, p_player) then return true; end if;
  end loop;
  return false;
end;
$$;

create function public.release_apply_move_v2(
  p_board smallint[], p_move integer, p_player integer
)
returns smallint[]
language plpgsql
immutable
security definer
set search_path = ''
as $$
declare
  result_board smallint[] := p_board;
  dr integer[] := array[-1,-1,-1,0,0,1,1,1];
  dc integer[] := array[-1,0,1,-1,1,-1,0,1];
  direction_index integer;
  start_row integer := p_move / 8;
  start_column integer := p_move % 8;
  row_value integer;
  column_value integer;
  captured_count integer;
  captured_index integer;
  opponent integer := case p_player when 1 then 2 else 1 end;
begin
  if not public.release_is_legal_move_v2(p_board, p_move, p_player) then
    raise exception 'illegal release replay move';
  end if;
  result_board[p_move + 1] := p_player;
  for direction_index in 1..8 loop
    row_value := start_row + dr[direction_index];
    column_value := start_column + dc[direction_index];
    captured_count := 0;
    while row_value between 0 and 7 and column_value between 0 and 7
      and result_board[row_value * 8 + column_value + 1] = opponent
    loop
      captured_count := captured_count + 1;
      row_value := row_value + dr[direction_index];
      column_value := column_value + dc[direction_index];
    end loop;
    if captured_count > 0 and row_value between 0 and 7 and column_value between 0 and 7
       and result_board[row_value * 8 + column_value + 1] = p_player then
      for captured_index in 1..captured_count loop
        result_board[(start_row + dr[direction_index] * captured_index) * 8
          + start_column + dc[direction_index] * captured_index + 1] := p_player;
      end loop;
    end if;
  end loop;
  return result_board;
end;
$$;

create function public.release_replay_game_v2(p_canonical_moves text)
returns table(
  accepted boolean,
  rejection_code text,
  final_position_hash text,
  final_result text,
  black_count integer,
  white_count integer,
  ply integer,
  current_player integer,
  consecutive_passes integer,
  terminal boolean
)
language plpgsql
immutable
security definer
set search_path = ''
as $$
declare
  board_state smallint[] := array_fill(0::smallint, array[64]);
  player_value integer := 1;
  pass_count integer := 0;
  ply_value integer := 0;
  offset_value integer;
  token_value text;
  move_value integer;
  black_value integer;
  white_value integer;
  terminal_value boolean;
  hash_signed bigint := -3750763034362895579;
  hash_unsigned numeric;
  modulus numeric := 18446744073709551616;
  signed_boundary numeric := 9223372036854775808;
  cell_index integer;
  hash_text text;
begin
  if p_canonical_moves is null or char_length(p_canonical_moves) > 240
     or char_length(p_canonical_moves) % 2 <> 0
     or p_canonical_moves !~ '^((--|[a-h][1-8])*)$' then
    return query select false, 'INVALID_CANONICAL_FORMAT', null::text, null::text,
      null::integer, null::integer, null::integer, null::integer, null::integer, null::boolean;
    return;
  end if;

  board_state[28] := 2; -- d4
  board_state[29] := 1; -- e4
  board_state[36] := 1; -- d5
  board_state[37] := 2; -- e5

  if char_length(p_canonical_moves) > 0 then
    for offset_value in 0..(char_length(p_canonical_moves) / 2 - 1) loop
      if pass_count >= 2 or array_position(board_state, 0::smallint) is null then
        return query select false, 'MOVE_AFTER_TERMINAL', null::text, null::text,
          null::integer, null::integer, null::integer, null::integer, null::integer, null::boolean;
        return;
      end if;
      token_value := substr(p_canonical_moves, offset_value * 2 + 1, 2);
      if token_value = '--' then
        if public.release_has_legal_move_v2(board_state, player_value) then
          return query select false, 'UNNECESSARY_PASS', null::text, null::text,
            null::integer, null::integer, null::integer, null::integer, null::integer, null::boolean;
          return;
        end if;
        player_value := case player_value when 1 then 2 else 1 end;
        pass_count := pass_count + 1;
        ply_value := ply_value + 1;
      else
        if not public.release_has_legal_move_v2(board_state, player_value) then
          return query select false, 'MISSING_PASS', null::text, null::text,
            null::integer, null::integer, null::integer, null::integer, null::integer, null::boolean;
          return;
        end if;
        move_value := (ascii(substr(token_value, 2, 1)) - ascii('1')) * 8
          + ascii(substr(token_value, 1, 1)) - ascii('a');
        if not public.release_is_legal_move_v2(board_state, move_value, player_value) then
          return query select false, 'ILLEGAL_MOVE', null::text, null::text,
            null::integer, null::integer, null::integer, null::integer, null::integer, null::boolean;
          return;
        end if;
        board_state := public.release_apply_move_v2(board_state, move_value, player_value);
        player_value := case player_value when 1 then 2 else 1 end;
        pass_count := 0;
        ply_value := ply_value + 1;
      end if;
    end loop;
  end if;

  select count(*) filter (where value = 1)::integer,
         count(*) filter (where value = 2)::integer
    into black_value, white_value
    from unnest(board_state) cells(value);
  terminal_value := pass_count >= 2 or array_position(board_state, 0::smallint) is null;

  for cell_index in 1..64 loop
    hash_unsigned := mod(
      ((hash_signed # board_state[cell_index]::bigint)::numeric * 1099511628211::numeric),
      modulus
    );
    if hash_unsigned < 0 then hash_unsigned := hash_unsigned + modulus; end if;
    hash_signed := case
      when hash_unsigned >= signed_boundary then (hash_unsigned - modulus)::bigint
      else hash_unsigned::bigint
    end;
  end loop;
  hash_text := lpad(to_hex(hash_signed), 16, '0') || ':' || player_value::text
    || ':' || pass_count::text || ':' || ply_value::text;

  return query select true, null::text, hash_text,
    case when black_value > white_value then 'BLACK_WIN'
         when white_value > black_value then 'WHITE_WIN'
         else 'DRAW' end,
    black_value, white_value, ply_value, player_value, pass_count, terminal_value;
end;
$$;

-- Keep the protocol-1 RPC signature used by the closed-test APK, but route every
-- result through the same deterministic rules replay used by protocol 2. Client
-- result/hash fields are compatibility assertions, never result authority.
create or replace function public.submit_match_result(
  p_match_id uuid,
  p_canonical_moves text,
  p_result text,
  p_final_position_hash text,
  p_finish_reason text,
  p_clock jsonb default null
)
returns public.server_match_status
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  match_row public.matches%rowtype;
  existing public.match_submissions%rowtype;
  replay_row record;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if p_result is null or p_result not in ('BLACK_WIN', 'WHITE_WIN', 'DRAW') then
    raise exception 'invalid result';
  end if;
  if p_finish_reason is null
     or p_finish_reason not in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT') then
    raise exception 'invalid finish reason';
  end if;
  if p_clock is not null and pg_column_size(p_clock) > 4096 then
    raise exception 'clock payload is too large';
  end if;

  -- Participant authorization intentionally precedes the bounded replay CPU work.
  select * into match_row
    from public.matches m
   where m.id = p_match_id
     and m.protocol_version = 1
     and caller_id in (m.black_player, m.white_player)
   for update;
  if not found then raise exception 'match participant required'; end if;
  if match_row.p2p_started_at is null then raise exception 'match P2P not started'; end if;

  select * into existing
    from public.match_submissions s
   where s.match_id = p_match_id and s.player_id = caller_id;
  if found and (
       existing.canonical_moves is distinct from p_canonical_moves
       or existing.result is distinct from p_result
       or existing.final_position_hash is distinct from p_final_position_hash
       or existing.finish_reason is distinct from p_finish_reason
       or existing.clock is distinct from p_clock
     ) then
    raise exception 'submission conflict for player';
  end if;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then
    return match_row.server_status;
  end if;

  select * into replay_row from public.release_replay_game_v2(p_canonical_moves);
  if not replay_row.accepted then
    raise exception 'invalid canonical moves: %', replay_row.rejection_code;
  end if;
  if p_final_position_hash is distinct from replay_row.final_position_hash then
    raise exception 'final position hash does not match server replay';
  end if;
  if p_finish_reason = 'NORMAL' then
    if not replay_row.terminal then raise exception 'NORMAL result is not terminal'; end if;
    if p_result is distinct from replay_row.final_result then
      raise exception 'result does not match server replay';
    end if;
  elsif p_result = 'DRAW' then
    raise exception 'non-normal result requires a losing side';
  end if;

  if existing.match_id is null then
    insert into public.match_submissions(
      match_id, player_id, moves, canonical_moves, result,
      final_position_hash, finish_reason, clock
    ) values (
      p_match_id, caller_id, to_jsonb(p_canonical_moves), p_canonical_moves, p_result,
      replay_row.final_position_hash, p_finish_reason, p_clock
    );
  end if;
  return public.finalize_match_v2(p_match_id);
end;
$$;

create or replace function public.finalize_match_v2(p_match_id uuid)
returns public.server_match_status
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  match_row public.matches%rowtype;
  black_submission public.match_submissions%rowtype;
  white_submission public.match_submissions%rowtype;
  replay_row record;
  loser_id uuid;
  record_inserted integer;
  black_rating integer;
  white_rating integer;
  black_new_rating integer;
  white_new_rating integer;
  black_expected numeric;
  white_expected numeric;
  black_actual numeric;
  white_actual numeric;
begin
  if caller_id is null then raise exception 'match access denied'; end if;
  select * into match_row
    from public.matches m
   where m.id = p_match_id
     and m.protocol_version = 1
     and caller_id in (m.black_player, m.white_player)
   for update;
  if not found then raise exception 'match access denied'; end if;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then
    return match_row.server_status;
  end if;

  select * into black_submission from public.match_submissions s
   where s.match_id = p_match_id and s.player_id = match_row.black_player;
  select * into white_submission from public.match_submissions s
   where s.match_id = p_match_id and s.player_id = match_row.white_player;
  if black_submission.match_id is null or white_submission.match_id is null then
    update public.matches
       set server_status = 'PENDING_RESULT',
           result_expires_at = coalesce(result_expires_at, now() + interval '30 days')
     where id = p_match_id;
    update public.active_match_participants as participant
       set expires_at = (select result_expires_at from public.matches where id = p_match_id)
     where participant.match_id = p_match_id;
    return 'PENDING_RESULT';
  end if;

  if black_submission.canonical_moves is distinct from white_submission.canonical_moves
     or black_submission.result is distinct from white_submission.result
     or black_submission.final_position_hash is distinct from white_submission.final_position_hash
     or black_submission.finish_reason is distinct from white_submission.finish_reason then
    update public.matches set server_status = 'DISPUTED' where id = p_match_id;
    return 'DISPUTED';
  end if;

  select * into replay_row
    from public.release_replay_game_v2(black_submission.canonical_moves);
  if not replay_row.accepted
     or black_submission.final_position_hash is distinct from replay_row.final_position_hash
     or (
       black_submission.finish_reason = 'NORMAL'
       and (
         not replay_row.terminal
         or black_submission.result is distinct from replay_row.final_result
       )
     )
     or (
       black_submission.finish_reason <> 'NORMAL'
       and black_submission.result = 'DRAW'
     ) then
    update public.matches set server_status = 'DISPUTED' where id = p_match_id;
    return 'DISPUTED';
  end if;

  if black_submission.finish_reason <> 'NORMAL' then
    loser_id := case black_submission.result
      when 'BLACK_WIN' then match_row.white_player
      when 'WHITE_WIN' then match_row.black_player
      else null
    end;
    -- The loser must personally submit the same evidence. The winner's claim alone
    -- can never authorize a rated resignation, timeout, or disconnect result.
    if loser_id is null or not exists (
      select 1 from public.match_submissions s
       where s.match_id = p_match_id
         and s.player_id = loser_id
         and s.canonical_moves is not distinct from black_submission.canonical_moves
         and s.result is not distinct from black_submission.result
         and s.final_position_hash is not distinct from replay_row.final_position_hash
         and s.finish_reason is not distinct from black_submission.finish_reason
    ) then
      update public.matches set server_status = 'DISPUTED' where id = p_match_id;
      return 'DISPUTED';
    end if;
  end if;

  insert into public.game_records(
    match_id, players, moves, canonical_moves, result, started_at, finished_at,
    time_control, finish_reason, expires_at, final_position_hash, result_contract_version
  ) values (
    p_match_id, array[match_row.black_player, match_row.white_player],
    to_jsonb(black_submission.canonical_moves), black_submission.canonical_moves,
    black_submission.result, match_row.created_at, now(), '5m',
    black_submission.finish_reason, now() + interval '365 days',
    replay_row.final_position_hash, 2
  ) on conflict (match_id) do nothing;
  get diagnostics record_inserted = row_count;
  if record_inserted = 1 then
    perform 1 from public.ratings
     where user_id in (match_row.black_player, match_row.white_player)
     order by user_id for update;
    select current_rating into black_rating from public.ratings
     where user_id = match_row.black_player;
    select current_rating into white_rating from public.ratings
     where user_id = match_row.white_player;
    if black_rating is null or white_rating is null then
      raise exception 'rating bootstrap missing';
    end if;
    black_expected := 1.0 / (1.0 + power(10.0, (white_rating - black_rating) / 400.0));
    white_expected := 1.0 - black_expected;
    black_actual := case black_submission.result
      when 'BLACK_WIN' then 1.0 when 'WHITE_WIN' then 0.0 else 0.5 end;
    white_actual := 1.0 - black_actual;
    black_new_rating := round(black_rating + 32 * (black_actual - black_expected));
    white_new_rating := round(white_rating + 32 * (white_actual - white_expected));
    insert into public.rating_history(user_id, match_id, rating, delta, algorithm_version)
    values
      (match_row.black_player, p_match_id, black_new_rating,
        black_new_rating - black_rating, 'elo-v1'),
      (match_row.white_player, p_match_id, white_new_rating,
        white_new_rating - white_rating, 'elo-v1')
    on conflict (user_id, match_id) where match_id is not null do nothing;
    update public.ratings
       set current_rating = black_new_rating,
           peak_rating = greatest(peak_rating, black_new_rating), updated_at = now()
     where user_id = match_row.black_player;
    update public.ratings
       set current_rating = white_new_rating,
           peak_rating = greatest(peak_rating, white_new_rating), updated_at = now()
     where user_id = match_row.white_player;
    insert into public.user_game_records(user_id, match_id)
    values (match_row.black_player, p_match_id), (match_row.white_player, p_match_id)
    on conflict do nothing;
    perform public.prune_user_game_records(match_row.black_player);
    perform public.prune_user_game_records(match_row.white_player);
    perform public.prune_rating_history(match_row.black_player);
    perform public.prune_rating_history(match_row.white_player);
    delete from public.match_submissions where match_id = p_match_id;
  end if;
  -- Research capture is triggered only after the authoritative GameRecord and both
  -- rating rows exist, in this same transaction.
  update public.matches
     set server_status = 'CONFIRMED', confirmed_at = coalesce(confirmed_at, now())
   where id = p_match_id;
  return 'CONFIRMED';
end;
$$;

create function public.release_assignment_row_v2(p_match_id uuid, p_user_id uuid)
returns table(
  match_id uuid,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  lifecycle_status text,
  negotiation_epoch integer
)
language sql
stable
security definer
set search_path = ''
as $$
  select m.id,
         case when m.black_player = p_user_id then m.white_player else m.black_player end,
         case when m.black_player = p_user_id then 'BLACK' else 'WHITE' end,
         case when m.black_player = p_user_id
              then m.white_rating_at_start else m.black_rating_at_start end,
         m.release_status::text,
         m.negotiation_epoch
    from public.matches m
   where m.id = p_match_id
     and m.protocol_version = 2
     and p_user_id in (m.black_player, m.white_player)
$$;

create function public.release_match_state_row_v2(p_match_id uuid, p_user_id uuid)
returns table(
  match_id uuid,
  lifecycle_status text,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  release_deadline timestamptz,
  reconnect_deadline timestamptz,
  negotiation_epoch integer,
  local_acked boolean,
  both_acked boolean,
  terminal_reason text,
  final_result text,
  final_position_hash text
)
language sql
stable
security definer
set search_path = ''
as $$
  select m.id,
         m.release_status::text,
         case when m.black_player = p_user_id then m.white_player else m.black_player end,
         case when m.black_player = p_user_id then 'BLACK' else 'WHITE' end,
         case when m.black_player = p_user_id
              then m.white_rating_at_start else m.black_rating_at_start end,
         m.release_deadline,
         m.reconnect_deadline,
         m.negotiation_epoch,
         exists (
           select 1 from public.match_start_acks_v2 a
            where a.match_id = m.id and a.user_id = p_user_id
              and a.negotiation_epoch = m.negotiation_epoch
         ),
         (
           select count(*)
             from public.match_start_acks_v2 a
            where a.match_id = m.id
              and a.negotiation_epoch = m.negotiation_epoch
              and a.user_id in (m.black_player, m.white_player)
         ) = 2,
         m.release_terminal_reason,
         r.final_result,
         r.final_position_hash
    from public.matches m
    left join public.match_results_v2 r on r.match_id = m.id
   where m.id = p_match_id
     and m.protocol_version = 2
     and p_user_id in (m.black_player, m.white_player)
$$;

create function public.release_expire_match_v2(p_match_id uuid, p_reason text)
returns public.release_match_status
language plpgsql
security definer
set search_path = ''
as $$
declare match_row public.matches%rowtype;
begin
  if p_reason is null or p_reason !~ '^[A-Z0-9_]{1,64}$' then
    raise exception 'invalid terminal reason';
  end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 2 then raise exception 'release match required'; end if;
  if match_row.release_status in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED') then
    return match_row.release_status;
  end if;
  update public.matches
     set release_status = 'EXPIRED',
         release_terminal_reason = p_reason,
         server_status = 'ABANDONED'
   where id = p_match_id;
  return 'EXPIRED';
end;
$$;

create function public.release_finalize_result_v2(
  p_match_id uuid,
  p_terminal_status public.release_match_status,
  p_canonical_moves text,
  p_final_result text,
  p_finish_reason text,
  p_loser_disc text,
  p_final_position_hash text,
  p_black_count integer,
  p_white_count integer,
  p_terminal_reason text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  match_row public.matches%rowtype;
  inserted_count integer;
  black_rating integer;
  white_rating integer;
  black_new_rating integer;
  white_new_rating integer;
  black_expected numeric;
  black_actual numeric;
  result_digest_value bytea;
begin
  if p_terminal_status is null or p_terminal_status not in ('CONFIRMED', 'FORFEIT') then
    raise exception 'invalid rated terminal status';
  end if;
  if p_canonical_moves is null
     or p_final_result is null
     or p_final_result not in ('BLACK_WIN', 'WHITE_WIN', 'DRAW')
     or p_finish_reason is null
     or p_finish_reason not in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT')
     or p_final_position_hash is null
     or p_final_position_hash !~ '^[0-9a-f]{16}:[0-2]:[0-2]:[0-9]{1,3}$'
     or p_black_count is null or p_white_count is null
     or p_black_count not between 0 and 64 or p_white_count not between 0 and 64
     or p_black_count + p_white_count > 64 then
    raise exception 'invalid authoritative result';
  end if;
  if p_terminal_reason is null or p_terminal_reason !~ '^[A-Z0-9_]{1,64}$'
     or (
       p_terminal_status = 'CONFIRMED'
       and (p_finish_reason <> 'NORMAL' or p_loser_disc is not null)
     )
     or (
       p_terminal_status = 'FORFEIT'
       and (
         p_finish_reason = 'NORMAL'
         or p_loser_disc is null
         or p_loser_disc not in ('BLACK', 'WHITE')
       )
     ) then
    raise exception 'invalid authoritative terminal contract';
  end if;

  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 2 then raise exception 'release match required'; end if;
  if match_row.release_status in ('CONFIRMED', 'FORFEIT') then return; end if;
  if match_row.release_status in ('DISPUTED', 'EXPIRED', 'ABANDONED') then
    raise exception 'release match is already terminal';
  end if;

  result_digest_value := extensions.digest(
    'release-result-v2|' || p_match_id::text || '|' || match_row.black_player::text
      || '|' || match_row.white_player::text || '|1|' || p_canonical_moves || '|'
      || p_final_result || '|' || p_finish_reason || '|'
      || coalesce(p_loser_disc, '-') || '|' || p_final_position_hash || '|'
      || p_black_count::text || '|' || p_white_count::text,
    'sha256'
  );

  insert into public.match_results_v2(
    match_id, terminal_status, canonical_moves, final_result, finish_reason,
    loser_disc, final_position_hash, black_count, white_count, result_digest
  ) values (
    p_match_id, p_terminal_status, p_canonical_moves, p_final_result, p_finish_reason,
    p_loser_disc, p_final_position_hash, p_black_count, p_white_count, result_digest_value
  ) on conflict (match_id) do nothing;
  get diagnostics inserted_count = row_count;
  if inserted_count = 0 then return; end if;

  insert into public.game_records(
    match_id, players, moves, canonical_moves, result, started_at, finished_at,
    time_control, finish_reason, expires_at, final_position_hash, result_contract_version
  ) values (
    p_match_id, array[match_row.black_player, match_row.white_player],
    to_jsonb(p_canonical_moves), p_canonical_moves, p_final_result,
    coalesce(match_row.release_started_at, match_row.created_at), now(),
    '5m', p_finish_reason, now() + interval '365 days', p_final_position_hash, 2
  );

  perform 1 from public.ratings
   where user_id in (match_row.black_player, match_row.white_player)
   order by user_id for update;
  select current_rating into black_rating from public.ratings
   where user_id = match_row.black_player;
  select current_rating into white_rating from public.ratings
   where user_id = match_row.white_player;
  if black_rating is null or white_rating is null then raise exception 'rating bootstrap missing'; end if;

  black_expected := 1.0 / (1.0 + power(10.0, (white_rating - black_rating) / 400.0));
  black_actual := case p_final_result
    when 'BLACK_WIN' then 1.0 when 'WHITE_WIN' then 0.0 else 0.5 end;
  black_new_rating := round(black_rating + 32 * (black_actual - black_expected));
  white_new_rating := round(white_rating + 32 * ((1.0 - black_actual) - (1.0 - black_expected)));

  insert into public.rating_history(user_id, match_id, rating, delta, algorithm_version)
  values
    (match_row.black_player, p_match_id, black_new_rating,
      black_new_rating - black_rating, 'elo-v1'),
    (match_row.white_player, p_match_id, white_new_rating,
      white_new_rating - white_rating, 'elo-v1');
  update public.ratings
     set current_rating = black_new_rating,
         peak_rating = greatest(peak_rating, black_new_rating), updated_at = now()
   where user_id = match_row.black_player;
  update public.ratings
     set current_rating = white_new_rating,
         peak_rating = greatest(peak_rating, white_new_rating), updated_at = now()
   where user_id = match_row.white_player;

  insert into public.user_game_records(user_id, match_id)
  values (match_row.black_player, p_match_id), (match_row.white_player, p_match_id);
  perform public.prune_user_game_records(match_row.black_player);
  perform public.prune_user_game_records(match_row.white_player);
  perform public.prune_rating_history(match_row.black_player);
  perform public.prune_rating_history(match_row.white_player);

  -- The legacy server_status update deliberately happens last. Its existing trigger captures
  -- Research only after the canonical record and both rating-history rows exist.
  update public.matches
     set release_status = p_terminal_status,
         release_terminal_reason = p_terminal_reason,
         server_status = 'CONFIRMED',
         confirmed_at = coalesce(confirmed_at, now())
   where id = p_match_id;
end;
$$;

create function public.release_reconcile_match_internal_v2(p_match_id uuid)
returns public.release_match_status
language plpgsql
security definer
set search_path = ''
as $$
declare
  match_row public.matches%rowtype;
begin
  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 2 then raise exception 'release match required'; end if;
  if match_row.release_status in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED') then
    return match_row.release_status;
  end if;
  if match_row.release_deadline > now() then return match_row.release_status; end if;

  if match_row.release_status = 'MATCHED' then
    return public.release_expire_match_v2(p_match_id, 'MATCH_START_TIMEOUT');
  elsif match_row.release_status = 'ACTIVE' then
    return public.release_expire_match_v2(p_match_id, 'ACTIVE_LEASE_EXPIRED');
  elsif match_row.release_status = 'RESULT_PENDING' then
    return public.release_expire_match_v2(p_match_id, 'RESULT_EVIDENCE_TIMEOUT');
  elsif match_row.release_status = 'RECONNECTING' then
    if match_row.black_disconnect_claimed_at is not null
       and match_row.white_disconnect_claimed_at is not null then
      return public.release_expire_match_v2(p_match_id, 'BOTH_PEERS_REPORTED_DISCONNECT');
    elsif match_row.black_disconnect_claimed_at is not null
       or match_row.white_disconnect_claimed_at is not null then
      return public.release_expire_match_v2(p_match_id, 'DISCONNECT_GRACE_EXPIRED_UNRATED');
    else
      return public.release_expire_match_v2(p_match_id, 'RECONNECT_GRACE_EXPIRED');
    end if;
  end if;
  return match_row.release_status;
end;
$$;

create function public.enqueue_or_match_v2(p_request_id uuid)
returns table(
  matched boolean,
  match_id uuid,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  lifecycle_status text,
  negotiation_epoch integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  caller_rating integer;
  active_match_id uuid;
  own_queue public.match_queue%rowtype;
  candidate public.match_queue%rowtype;
  created_match public.matches%rowtype;
  caller_is_black boolean;
  candidate_lock_acquired boolean := false;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if p_request_id is null then raise exception 'request id required'; end if;
  -- This lock is caller-scoped. Candidate rows remain independently claimable with
  -- FOR UPDATE SKIP LOCKED; there is no matchmaking-wide serialization point.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('release-match-user:' || caller_id::text, 0)
  );

  select m.id into active_match_id
    from public.matches m
   where m.protocol_version = 2
     and caller_id in (m.black_player, m.white_player)
     and m.release_status in ('MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING')
   order by m.created_at desc
   limit 1;
  if active_match_id is not null then
    perform public.release_reconcile_match_internal_v2(active_match_id);
    if exists (
      select 1 from public.matches m where m.id = active_match_id
        and m.release_status in ('MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING')
    ) then
      return query
      select true, a.match_id, a.opponent_id, a.assigned_disc,
             a.opponent_rating, a.lifecycle_status, a.negotiation_epoch
        from public.release_assignment_row_v2(active_match_id, caller_id) a;
      return;
    end if;
  end if;

  if exists (select 1 from public.active_match_participants a where a.user_id = caller_id) then
    raise exception 'user already has an active match';
  end if;
  select r.current_rating into caller_rating from public.ratings r where r.user_id = caller_id;
  if caller_rating is null then raise exception 'rating bootstrap missing'; end if;

  select q.* into own_queue from public.match_queue q where q.user_id = caller_id for update;
  if found then
    if own_queue.expires_at <= now() then
      delete from public.match_queue q where q.user_id = caller_id;
      own_queue.user_id := null;
    else
      if own_queue.protocol_version <> 2 then raise exception 'legacy queue request is active'; end if;
      if own_queue.request_id <> p_request_id then raise exception 'queue request conflict'; end if;
      update public.match_queue
         set current_rating = caller_rating,
             expires_at = now() + interval '2 minutes'
       where user_id = caller_id and protocol_version = 2 and request_id = p_request_id;
    end if;
  end if;

  -- A candidate's user-scoped lock closes enqueue/heartbeat/cancel races. A try-lock
  -- avoids cycles when two already-queued callers heartbeat at the same time.
  for candidate in
    select q.*
      from public.match_queue q
     where q.protocol_version = 2
       and q.user_id <> caller_id
       and q.expires_at > now()
       and not exists (
         select 1 from public.active_match_participants a where a.user_id = q.user_id
       )
     order by abs(q.current_rating - caller_rating), q.queued_at, q.user_id
     for update skip locked
     limit 16
  loop
    candidate_lock_acquired := pg_catalog.pg_try_advisory_xact_lock(
      pg_catalog.hashtextextended('release-match-user:' || candidate.user_id::text, 0)
    );
    exit when candidate_lock_acquired;
  end loop;

  if not candidate_lock_acquired then
    if own_queue.user_id is null then
      insert into public.match_queue(
        user_id, current_rating, queued_at, expires_at, protocol_version, request_id
      ) values (
        caller_id, caller_rating, now(), now() + interval '2 minutes', 2, p_request_id
      );
    end if;
    return query select false, null::uuid, null::uuid, null::text,
      null::integer, 'WAITING'::text, null::integer;
    return;
  end if;

  delete from public.match_queue q
   where q.user_id = candidate.user_id
     and q.protocol_version = 2
     and q.request_id = candidate.request_id;
  delete from public.match_queue q
   where q.user_id = caller_id
     and q.protocol_version = 2
     and q.request_id = p_request_id;
  caller_is_black := random() < 0.5;
  if caller_is_black then
    insert into public.matches(
      black_player, white_player, status, server_status, protocol_version,
      release_status, release_deadline, black_rating_at_start, white_rating_at_start,
      black_queue_request_id, white_queue_request_id
    ) values (
      caller_id, candidate.user_id, 'PLAYING', 'CREATED', 2,
      'MATCHED', now() + interval '2 minutes', caller_rating, candidate.current_rating,
      p_request_id, candidate.request_id
    ) returning * into created_match;
  else
    insert into public.matches(
      black_player, white_player, status, server_status, protocol_version,
      release_status, release_deadline, black_rating_at_start, white_rating_at_start,
      black_queue_request_id, white_queue_request_id
    ) values (
      candidate.user_id, caller_id, 'PLAYING', 'CREATED', 2,
      'MATCHED', now() + interval '2 minutes', candidate.current_rating, caller_rating,
      candidate.request_id, p_request_id
    ) returning * into created_match;
  end if;

  begin
    insert into public.active_match_participants(user_id, match_id, expires_at)
    values
      (created_match.black_player, created_match.id, created_match.release_deadline),
      (created_match.white_player, created_match.id, created_match.release_deadline);
  exception when unique_violation then
    raise exception 'user already has an active match';
  end;
  -- The trigger publishes a durable wake-up row to both participants. The caller already
  -- owns this response; retain only the waiting peer's row for Realtime/recovery.
  delete from public.match_notifications n
   where n.user_id = caller_id and n.match_id = created_match.id;

  return query
  select true, a.match_id, a.opponent_id, a.assigned_disc,
         a.opponent_rating, a.lifecycle_status, a.negotiation_epoch
    from public.release_assignment_row_v2(created_match.id, caller_id) a;
end;
$$;

create function public.claim_active_match_v2()
returns table(
  match_id uuid,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  lifecycle_status text,
  negotiation_epoch integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  active_match_id uuid;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select m.id into active_match_id
    from public.matches m
   where m.protocol_version = 2
     and caller_id in (m.black_player, m.white_player)
     and m.release_status in ('MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING')
   order by m.created_at desc
   limit 1;
  if active_match_id is null then return; end if;
  perform public.release_reconcile_match_internal_v2(active_match_id);
  if not exists (
    select 1 from public.matches m where m.id = active_match_id
      and m.release_status in ('MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING')
  ) then return; end if;
  delete from public.match_notifications n
   where n.user_id = caller_id and n.match_id = active_match_id;
  return query select * from public.release_assignment_row_v2(active_match_id, caller_id);
end;
$$;

create function public.cancel_waiting_v2(p_request_id uuid)
returns table(
  match_id uuid,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  lifecycle_status text,
  negotiation_epoch integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  deleted_count integer;
  active_match_id uuid;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if p_request_id is null then raise exception 'request id required'; end if;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('release-match-user:' || caller_id::text, 0)
  );
  delete from public.match_queue q
   where q.user_id = caller_id and q.protocol_version = 2 and q.request_id = p_request_id;
  get diagnostics deleted_count = row_count;
  if deleted_count = 1 then return; end if;

  -- A matcher may have consumed the row while the cancel request was in flight. Returning
  -- that assignment makes cancel-vs-match recovery a single RPC and never discards a match.
  select m.id into active_match_id
    from public.matches m
   where m.protocol_version = 2
     and caller_id in (m.black_player, m.white_player)
     and (
       (m.black_player = caller_id and m.black_queue_request_id = p_request_id)
       or (m.white_player = caller_id and m.white_queue_request_id = p_request_id)
     )
     and m.release_status in ('MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING')
   order by m.created_at desc
   limit 1;
  if active_match_id is not null then
    return query select * from public.release_assignment_row_v2(active_match_id, caller_id);
  end if;
end;
$$;

create function public.get_release_match_state_v2(p_match_id uuid)
returns table(
  match_id uuid,
  lifecycle_status text,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  release_deadline timestamptz,
  reconnect_deadline timestamptz,
  negotiation_epoch integer,
  local_acked boolean,
  both_acked boolean,
  terminal_reason text,
  final_result text,
  final_position_hash text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if not exists (
    select 1 from public.matches m where m.id = p_match_id and m.protocol_version = 2
      and caller_id in (m.black_player, m.white_player)
  ) then raise exception 'release match participant required'; end if;
  return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
end;
$$;

create function public.ack_match_started_v2(
  p_match_id uuid,
  p_expected_epoch integer default null
)
returns table(
  match_id uuid,
  lifecycle_status text,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  release_deadline timestamptz,
  reconnect_deadline timestamptz,
  negotiation_epoch integer,
  local_acked boolean,
  both_acked boolean,
  terminal_reason text,
  final_result text,
  final_position_hash text
)
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
declare play_deadline timestamptz;
declare both_acked_now boolean;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if p_expected_epoch is not null and p_expected_epoch not between 0 and 3 then
    raise exception 'invalid expected negotiation epoch';
  end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 2
     or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'release match participant required';
  end if;
  if match_row.release_status in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED') then
    return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
    return;
  end if;
  if match_row.release_deadline <= now() then
    perform public.release_reconcile_match_internal_v2(p_match_id);
    return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
    return;
  end if;
  if match_row.release_status not in ('MATCHED', 'RECONNECTING', 'ACTIVE') then
    raise exception 'match does not accept a data channel acknowledgement';
  end if;
  if p_expected_epoch is not null and p_expected_epoch > match_row.negotiation_epoch then
    raise exception 'future match acknowledgement epoch';
  end if;
  if p_expected_epoch is not null and p_expected_epoch < match_row.negotiation_epoch then
    -- A delayed DataChannel OPEN from an older generation must not ACK the
    -- authoritative newer epoch. Return its state so the client can adopt it.
    return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
    return;
  end if;

  insert into public.match_start_acks_v2(match_id, user_id, negotiation_epoch)
  values (p_match_id, caller_id, match_row.negotiation_epoch)
  on conflict do nothing;
  select count(distinct a.user_id) = 2 into both_acked_now
    from public.match_start_acks_v2 a
   where a.match_id = p_match_id
     and a.negotiation_epoch = match_row.negotiation_epoch
     and a.user_id in (match_row.black_player, match_row.white_player);
  if both_acked_now and match_row.release_status in ('MATCHED', 'RECONNECTING') then
    play_deadline := now() + interval '15 minutes';
    update public.matches
       set release_status = 'ACTIVE',
           release_started_at = coalesce(release_started_at, now()),
           release_deadline = play_deadline,
           reconnect_deadline = null,
           black_disconnect_claimed_at = null,
           white_disconnect_claimed_at = null,
           p2p_started_at = coalesce(p2p_started_at, now()),
           play_lease_expires_at = play_deadline
     where id = p_match_id;
    update public.active_match_participants as participant
       set expires_at = play_deadline where participant.match_id = p_match_id;
  end if;
  return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
end;
$$;

create function public.abandon_match_v2(p_match_id uuid)
returns public.release_match_status
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 2
     or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'release match participant required';
  end if;
  if match_row.release_status = 'MATCHED' then
    if exists (select 1 from public.match_result_claims_v2 c where c.match_id = p_match_id) then
      raise exception 'match with result evidence cannot be abandoned';
    end if;
    update public.matches
       set release_status = 'ABANDONED',
           release_terminal_reason = 'CANCELLED_BEFORE_START',
           server_status = 'ABANDONED'
     where id = p_match_id;
    return 'ABANDONED';
  end if;
  if match_row.release_status in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED') then
    return match_row.release_status;
  end if;
  raise exception 'active release match cannot be abandoned';
end;
$$;

create function public.resume_match_v2(
  p_match_id uuid,
  p_expected_epoch integer default null
)
returns table(
  match_id uuid,
  lifecycle_status text,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  release_deadline timestamptz,
  reconnect_deadline timestamptz,
  negotiation_epoch integer,
  local_acked boolean,
  both_acked boolean,
  terminal_reason text,
  final_result text,
  final_position_hash text
)
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
declare caller_was_claimed boolean;
declare new_deadline timestamptz;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if p_expected_epoch is not null and p_expected_epoch not between 0 and 3 then
    raise exception 'invalid expected negotiation epoch';
  end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 2
     or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'release match participant required';
  end if;
  if match_row.release_status not in ('ACTIVE', 'RECONNECTING') then
    raise exception 'release match is not resumable';
  end if;
  if match_row.release_deadline <= now() then
    perform public.release_reconcile_match_internal_v2(p_match_id);
    return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
    return;
  end if;
  if p_expected_epoch is not null and p_expected_epoch > match_row.negotiation_epoch then
    raise exception 'future resume negotiation epoch';
  end if;
  if match_row.release_status = 'ACTIVE' and p_expected_epoch is not null
     and p_expected_epoch < match_row.negotiation_epoch then
    -- The epoch observed before this row lock already completed. This request
    -- is stale, not evidence that another reconnect is required, so it must not
    -- increment or consume the finite epoch budget.
    return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
    return;
  end if;
  if match_row.release_status = 'ACTIVE' and match_row.negotiation_epoch >= 3 then
    perform public.release_expire_match_v2(
      p_match_id, 'RECONNECT_BUDGET_EXHAUSTED_UNRATED'
    );
    return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
    return;
  end if;
  caller_was_claimed := case
    when caller_id = match_row.black_player then match_row.black_disconnect_claimed_at is not null
    else match_row.white_disconnect_claimed_at is not null end;
  new_deadline := case
    when match_row.release_status = 'ACTIVE' or caller_was_claimed
      then now() + interval '45 seconds'
    else match_row.release_deadline end;
  update public.matches
     set release_status = 'RECONNECTING',
         negotiation_epoch = match_row.negotiation_epoch + case
           when match_row.release_status = 'ACTIVE' then 1 else 0 end,
         release_deadline = new_deadline,
         reconnect_deadline = new_deadline,
         black_disconnect_claimed_at = case
           when caller_id = match_row.black_player then null
           else match_row.black_disconnect_claimed_at end,
         white_disconnect_claimed_at = case
           when caller_id = match_row.white_player then null
           else match_row.white_disconnect_claimed_at end
   where id = p_match_id;
  update public.active_match_participants as participant
     set expires_at = new_deadline where participant.match_id = p_match_id;
  return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
end;
$$;

create function public.reconcile_match_v2(p_match_id uuid)
returns table(
  match_id uuid,
  lifecycle_status text,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer,
  release_deadline timestamptz,
  reconnect_deadline timestamptz,
  negotiation_epoch integer,
  local_acked boolean,
  both_acked boolean,
  terminal_reason text,
  final_result text,
  final_position_hash text
)
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if not exists (
    select 1 from public.matches m where m.id = p_match_id and m.protocol_version = 2
      and caller_id in (m.black_player, m.white_player)
  ) then raise exception 'release match participant required'; end if;
  perform public.release_reconcile_match_internal_v2(p_match_id);
  return query select * from public.release_match_state_row_v2(p_match_id, caller_id);
end;
$$;

create function public.release_result_response_row_v2(p_match_id uuid, p_user_id uuid)
returns table(
  server_status text,
  rating_before integer,
  rating_after integer,
  rating_delta integer,
  current_rating integer,
  peak_rating integer,
  final_result text,
  final_position_hash text
)
language sql
stable
security definer
set search_path = ''
as $$
  select m.release_status::text,
         case when h.match_id is null then null else h.rating - h.delta end,
         h.rating,
         h.delta,
         current_value.current_rating,
         current_value.peak_rating,
         result_row.final_result,
         result_row.final_position_hash
    from public.matches m
    left join public.rating_history h
      on h.match_id = m.id and h.user_id = p_user_id
    left join public.ratings current_value on current_value.user_id = p_user_id
    left join public.match_results_v2 result_row on result_row.match_id = m.id
   where m.id = p_match_id
     and m.protocol_version = 2
     and p_user_id in (m.black_player, m.white_player)
$$;

create function public.submit_match_result_v2(
  p_match_id uuid,
  p_request_id uuid,
  p_canonical_moves text,
  p_finish_reason text,
  p_loser_disc text default null,
  p_clock jsonb default null
)
returns table(
  server_status text,
  rating_before integer,
  rating_after integer,
  rating_delta integer,
  current_rating integer,
  peak_rating integer,
  final_result text,
  final_position_hash text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  match_row public.matches%rowtype;
  existing_claim public.match_result_claims_v2%rowtype;
  other_claim public.match_result_claims_v2%rowtype;
  replay_row record;
  loser_id uuid;
  final_result_value text;
  evidence_deadline timestamptz;
  caller_claim_count integer;
  claim_epoch integer;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select m.* into match_row
    from public.matches m
   where m.id = p_match_id
     and m.protocol_version = 2
     and caller_id in (m.black_player, m.white_player)
   for update;
  if not found then raise exception 'release match participant required'; end if;

  if p_request_id is null then raise exception 'request id required'; end if;
  if p_finish_reason is null
     or p_finish_reason not in ('NORMAL', 'RESIGNATION', 'TIMEOUT', 'DISCONNECT') then
    raise exception 'invalid finish reason';
  end if;
  if p_loser_disc is not null and p_loser_disc not in ('BLACK', 'WHITE') then
    raise exception 'invalid loser disc';
  end if;
  if p_clock is not null and pg_column_size(p_clock) > 4096 then
    raise exception 'clock payload is too large';
  end if;
  select * into replay_row from public.release_replay_game_v2(p_canonical_moves);
  if not replay_row.accepted then
    raise exception 'invalid canonical moves: %', replay_row.rejection_code;
  end if;

  select * into existing_claim
    from public.match_result_claims_v2 c
   where c.player_id = caller_id and c.request_id = p_request_id;
  if found then
    if existing_claim.match_id = p_match_id
       and existing_claim.canonical_moves is not distinct from p_canonical_moves
       and existing_claim.finish_reason is not distinct from p_finish_reason
       and existing_claim.loser_disc is not distinct from p_loser_disc
       and existing_claim.clock is not distinct from p_clock then
      return query select * from public.release_result_response_row_v2(p_match_id, caller_id);
      return;
    end if;
    raise exception 'release result submission conflict';
  end if;

  if match_row.release_status in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED') then
    return query select * from public.release_result_response_row_v2(p_match_id, caller_id);
    return;
  end if;
  if match_row.release_status = 'MATCHED' then
    raise exception 'release match has not started';
  end if;
  if match_row.release_deadline <= now() then
    perform public.release_reconcile_match_internal_v2(p_match_id);
    return query select * from public.release_result_response_row_v2(p_match_id, caller_id);
    return;
  end if;

  if p_finish_reason = 'NORMAL' then
    if p_loser_disc is not null then raise exception 'NORMAL result cannot name a loser'; end if;
    if not replay_row.terminal then raise exception 'NORMAL result is not terminal'; end if;
  elsif p_loser_disc is null then
    raise exception 'non-normal result requires loser disc';
  end if;

  loser_id := case p_loser_disc
    when 'BLACK' then match_row.black_player
    when 'WHITE' then match_row.white_player
    else null
  end;
  final_result_value := case p_loser_disc
    when 'BLACK' then 'WHITE_WIN'
    when 'WHITE' then 'BLACK_WIN'
    else null
  end;
  if p_finish_reason = 'DISCONNECT'
     and loser_id is distinct from caller_id
     and match_row.release_status = 'ACTIVE'
     and match_row.negotiation_epoch >= 3 then
    perform public.release_expire_match_v2(
      p_match_id, 'RECONNECT_BUDGET_EXHAUSTED_UNRATED'
    );
    return query select * from public.release_result_response_row_v2(p_match_id, caller_id);
    return;
  end if;
  claim_epoch := match_row.negotiation_epoch + case
    when p_finish_reason = 'DISCONNECT'
      and loser_id is distinct from caller_id
      and match_row.release_status = 'ACTIVE'
    then 1 else 0 end;

  if exists (
    select 1 from public.match_result_claims_v2 c
     where c.match_id = p_match_id
       and c.player_id = caller_id
       and c.negotiation_epoch = claim_epoch
       and (
         (p_finish_reason = 'NORMAL' and c.finish_reason = 'NORMAL')
         or (
           p_finish_reason <> 'NORMAL'
           and c.finish_reason = p_finish_reason
           and c.loser_disc = p_loser_disc
         )
       )
  ) then
    raise exception 'release result submission conflict';
  end if;

  select count(*)::integer into caller_claim_count
    from public.match_result_claims_v2 c
   where c.match_id = p_match_id and c.player_id = caller_id;
  if caller_claim_count >= 8 then raise exception 'release result claim limit exceeded'; end if;

  insert into public.match_result_claims_v2(
    match_id, player_id, request_id, negotiation_epoch,
    canonical_moves, finish_reason, loser_disc,
    clock, derived_position_hash, derived_board_result,
    derived_black_count, derived_white_count
  ) values (
    p_match_id, caller_id, p_request_id, claim_epoch,
    p_canonical_moves, p_finish_reason, p_loser_disc,
    p_clock, replay_row.final_position_hash, replay_row.final_result,
    replay_row.black_count, replay_row.white_count
  );

  if p_finish_reason = 'NORMAL' then
    select * into other_claim
      from public.match_result_claims_v2 c
     where c.match_id = p_match_id
       and c.player_id <> caller_id
       and c.negotiation_epoch = claim_epoch
       and c.finish_reason = 'NORMAL'
     limit 1;
    if found then
      if other_claim.canonical_moves = p_canonical_moves then
        perform public.release_finalize_result_v2(
          p_match_id, 'CONFIRMED', p_canonical_moves, replay_row.final_result,
          'NORMAL', null, replay_row.final_position_hash,
          replay_row.black_count, replay_row.white_count,
          'NORMAL_BOTH_REPLAY_CONFIRMED'
        );
      else
        update public.matches
           set release_status = 'DISPUTED',
               release_terminal_reason = 'NORMAL_CLAIM_MISMATCH',
               server_status = 'DISPUTED'
         where id = p_match_id;
      end if;
    else
      evidence_deadline := now() + interval '45 seconds';
      update public.matches
         set release_status = 'RESULT_PENDING',
             release_deadline = case when release_status = 'RESULT_PENDING'
               then least(release_deadline, evidence_deadline) else evidence_deadline end,
             reconnect_deadline = null
       where id = p_match_id;
      select release_deadline into evidence_deadline
        from public.matches where id = p_match_id;
      update public.active_match_participants as participant
         set expires_at = evidence_deadline where participant.match_id = p_match_id;
    end if;
    return query select * from public.release_result_response_row_v2(p_match_id, caller_id);
    return;
  end if;

  -- An authenticated caller may immediately concede only its own side. Naming the
  -- opponent can start bounded recovery/expiry, but can never create a rated forfeit.
  if loser_id = caller_id then
    perform public.release_finalize_result_v2(
      p_match_id, 'FORFEIT', p_canonical_moves, final_result_value,
      p_finish_reason, p_loser_disc, replay_row.final_position_hash,
      replay_row.black_count, replay_row.white_count,
      'SELF_' || p_finish_reason
    );
    return query select * from public.release_result_response_row_v2(p_match_id, caller_id);
    return;
  end if;

  evidence_deadline := now() + interval '45 seconds';
  if p_finish_reason = 'DISCONNECT' then
    if match_row.release_status not in ('ACTIVE', 'RECONNECTING') then
      raise exception 'disconnect evidence conflicts with pending result';
    end if;
    update public.matches
       set release_status = 'RECONNECTING',
           negotiation_epoch = negotiation_epoch + case
             when release_status = 'ACTIVE' then 1 else 0 end,
           release_deadline = case when release_status = 'RECONNECTING'
             then least(release_deadline, evidence_deadline) else evidence_deadline end,
           reconnect_deadline = case when release_status = 'RECONNECTING'
             then least(release_deadline, evidence_deadline) else evidence_deadline end,
           black_disconnect_claimed_at = case
             when p_loser_disc = 'BLACK' then coalesce(black_disconnect_claimed_at, now())
             else black_disconnect_claimed_at end,
           white_disconnect_claimed_at = case
             when p_loser_disc = 'WHITE' then coalesce(white_disconnect_claimed_at, now())
             else white_disconnect_claimed_at end
     where id = p_match_id;
  else
    update public.matches
       set release_status = 'RESULT_PENDING',
           release_deadline = case when release_status = 'RESULT_PENDING'
             then least(release_deadline, evidence_deadline) else evidence_deadline end,
           reconnect_deadline = null
     where id = p_match_id;
  end if;
  select release_deadline into evidence_deadline from public.matches where id = p_match_id;
  update public.active_match_participants as participant
     set expires_at = evidence_deadline where participant.match_id = p_match_id;
  return query select * from public.release_result_response_row_v2(p_match_id, caller_id);
end;
$$;

create function public.publish_match_signal_v2(
  p_match_id uuid,
  p_signal_type text,
  p_sdp text,
  p_protocol_version integer,
  p_negotiation_epoch integer
)
returns table(
  signal_id bigint,
  duplicate boolean,
  expires_at timestamptz,
  negotiation_epoch integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  match_row public.matches%rowtype;
  digest_value bytea;
  existing_row public.match_signals_v2%rowtype;
  signal_count integer;
  expiry_value timestamptz;
  inserted_id bigint;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  if p_protocol_version is distinct from 2 then
    raise exception 'unsupported signaling protocol';
  end if;
  if p_signal_type is null or p_signal_type not in ('OFFER', 'ANSWER', 'RESUME') then
    raise exception 'invalid signal type';
  end if;
  if p_sdp is null or octet_length(convert_to(p_sdp, 'UTF8')) not between 1 and 16384 then
    raise exception 'invalid SDP payload';
  end if;

  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 2
     or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'release match participant required';
  end if;
  if p_negotiation_epoch is distinct from match_row.negotiation_epoch then
    raise exception 'stale signaling negotiation epoch';
  end if;
  if match_row.release_status not in ('MATCHED', 'RECONNECTING')
     or match_row.release_deadline <= now() then
    raise exception 'release match does not accept signaling';
  end if;
  if (p_signal_type = 'OFFER' and caller_id <> match_row.black_player)
     or (p_signal_type = 'ANSWER' and caller_id <> match_row.white_player)
     or (p_signal_type = 'RESUME' and caller_id <> match_row.white_player) then
    raise exception 'signal role does not match assigned disc';
  end if;

  digest_value := extensions.digest(p_sdp, 'sha256');
  select s.* into existing_row
    from public.match_signals_v2 s
   where s.match_id = p_match_id
     and s.negotiation_epoch = match_row.negotiation_epoch
     and s.sender_id = caller_id
     and s.signal_type = p_signal_type
     and s.payload_digest = digest_value;
  if found then
    return query select existing_row.id, true, existing_row.expires_at,
      existing_row.negotiation_epoch;
    return;
  end if;

  select count(*)::integer into signal_count
    from public.match_signals_v2 s
   where s.match_id = p_match_id
     and s.negotiation_epoch = match_row.negotiation_epoch
     and s.sender_id = caller_id
     and s.signal_type = p_signal_type;
  if signal_count >= (case when p_signal_type = 'RESUME' then 1 else 4 end) then
    raise exception 'signaling slot limit exceeded';
  end if;
  expiry_value := least(match_row.release_deadline, now() + interval '2 minutes');
  if expiry_value <= now() then raise exception 'signaling lease expired'; end if;

  insert into public.match_signals_v2(
    match_id, negotiation_epoch, sender_id, signal_type, sdp, protocol_version,
    signal_slot, payload_digest, expires_at
  ) values (
    p_match_id, match_row.negotiation_epoch, caller_id, p_signal_type, p_sdp, 2,
    signal_count + 1, digest_value, expiry_value
  ) returning id into inserted_id;
  return query select inserted_id, false, expiry_value, match_row.negotiation_epoch;
end;
$$;

create function public.run_match_maintenance_v2(p_limit integer default 100)
returns table(
  terminalized_matches integer,
  deleted_signals integer,
  deleted_queue_rows integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  candidate record;
  status_before public.release_match_status;
  status_after public.release_match_status;
  terminalized_count integer := 0;
  signal_count integer := 0;
  queue_count integer := 0;
begin
  if auth.role() <> 'service_role' then raise exception 'service role required'; end if;
  if p_limit is null or p_limit not between 1 and 1000 then
    raise exception 'maintenance limit out of range';
  end if;

  for candidate in
    select m.id, m.release_status
      from public.matches m
     where m.protocol_version = 2
       and m.release_status in ('MATCHED', 'ACTIVE', 'RECONNECTING', 'RESULT_PENDING')
       and m.release_deadline <= now()
     order by m.release_deadline, m.id
     for update skip locked
     limit p_limit
  loop
    status_before := candidate.release_status;
    status_after := public.release_reconcile_match_internal_v2(candidate.id);
    if status_after in ('CONFIRMED', 'DISPUTED', 'FORFEIT', 'EXPIRED', 'ABANDONED')
       and status_after is distinct from status_before then
      terminalized_count := terminalized_count + 1;
    end if;
  end loop;

  -- Business state is terminalized first. Signaling is then disposable transport data.
  with cleanup_candidates as (
    select s.id
      from public.match_signals_v2 s
     where s.expires_at <= now()
        or not exists (
          select 1 from public.matches m
           where m.id = s.match_id and m.protocol_version = 2
             and m.release_status in ('MATCHED', 'RECONNECTING')
             and m.negotiation_epoch = s.negotiation_epoch
        )
     order by s.expires_at, s.id
     for update skip locked
     limit p_limit
  )
  delete from public.match_signals_v2 s
   using cleanup_candidates c
   where s.id = c.id;
  get diagnostics signal_count = row_count;
  with cleanup_candidates as (
    select q.user_id
      from public.match_queue q
     where q.protocol_version = 2 and q.expires_at <= now()
     order by q.expires_at, q.user_id
     for update skip locked
     limit p_limit
  )
  delete from public.match_queue q
   using cleanup_candidates c
   where q.user_id = c.user_id and q.protocol_version = 2;
  get diagnostics queue_count = row_count;
  return query select terminalized_count, signal_count, queue_count;
end;
$$;

-- The existing Worker also owns the protocol-1 coexistence sweep. Keep the old
-- no-argument cleanup functions available for operational compatibility, but give
-- the scheduled path one explicitly bounded transaction with the same result shape
-- as v2 maintenance.
create function public.run_legacy_match_maintenance_v1(p_limit integer default 100)
returns table(
  terminalized_matches integer,
  deleted_signals integer,
  deleted_queue_rows integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  terminalized_count integer := 0;
  signal_count integer := 0;
  queue_count integer := 0;
begin
  if auth.role() <> 'service_role' then raise exception 'service role required'; end if;
  if p_limit is null or p_limit not between 1 and 1000 then
    raise exception 'maintenance limit out of range';
  end if;

  -- Persist terminal business state first. One ordered candidate set covers all
  -- legacy lease classes without changing the public no-argument cleanup RPCs.
  with cleanup_candidates as (
    select m.id
      from public.matches m
     where m.protocol_version = 1
       and (
         (m.server_status = 'CREATED' and m.p2p_started_at is null
           and m.created_expires_at <= now())
         or (m.server_status = 'CREATED' and m.p2p_started_at is not null
           and m.play_lease_expires_at <= now())
         or (m.server_status = 'PENDING_RESULT' and m.result_expires_at <= now())
       )
     order by case
       when m.server_status = 'PENDING_RESULT' then m.result_expires_at
       when m.p2p_started_at is null then m.created_expires_at
       else m.play_lease_expires_at
     end, m.id
     for update skip locked
     limit p_limit
  )
  update public.matches m
     set server_status = 'ABANDONED'
    from cleanup_candidates c
   where m.id = c.id;
  get diagnostics terminalized_count = row_count;

  with cleanup_candidates as (
    select s.id
      from public.match_signaling s
      join public.matches m on m.id = s.match_id
     where m.protocol_version = 1
       and (
         m.server_status <> 'CREATED'
         or m.p2p_started_at is not null
         or m.created_expires_at <= now()
       )
     order by s.created_at, s.id
     for update of s skip locked
     limit p_limit
  )
  delete from public.match_signaling s
   using cleanup_candidates c
   where s.id = c.id;
  get diagnostics signal_count = row_count;

  with cleanup_candidates as (
    select q.user_id
      from public.match_queue q
     where q.protocol_version = 1 and q.expires_at <= now()
     order by q.expires_at, q.user_id
     for update skip locked
     limit p_limit
  )
  delete from public.match_queue q
   using cleanup_candidates c
   where q.user_id = c.user_id and q.protocol_version = 1;
  get diagnostics queue_count = row_count;

  return query select terminalized_count, signal_count, queue_count;
end;
$$;

do $$
begin
  alter publication supabase_realtime add table public.match_signals_v2;
exception when duplicate_object then null;
end
$$;

-- The shared notification trigger wakes either APK, while the protocol-1 authority RPCs
-- below keep their signatures and explicitly ignore protocol-2 queue and match rows.
-- This preserves wake-up delivery without allowing pool crossover during coexistence.
create or replace function public.notify_match_participants()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.match_notifications(user_id, match_id)
  values (new.black_player, new.id), (new.white_player, new.id)
  on conflict do nothing;
  return new;
end;
$$;

create or replace function public.get_match_start_state(p_match_id uuid)
returns table(server_status text, local_acked boolean, both_acked boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into match_row
    from public.matches m where m.id = p_match_id and m.protocol_version = 1;
  if not found or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'match participant required';
  end if;
  return query select
    match_row.server_status::text,
    exists (
      select 1 from public.match_start_acks a
       where a.match_id = p_match_id and a.user_id = caller_id
    ),
    match_row.p2p_started_at is not null and (
      select count(*) from public.match_start_acks a
       where a.match_id = p_match_id
         and a.user_id in (match_row.black_player, match_row.white_player)
    ) = 2;
end;
$$;

create or replace function public.cancel_waiting()
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  delete from public.match_queue
   where user_id = auth.uid() and protocol_version = 1;
  return found;
end;
$$;

create or replace function public.heartbeat_waiting()
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  update public.match_queue set expires_at = now() + interval '2 minutes'
   where user_id = auth.uid() and protocol_version = 1 and expires_at > now();
  return found;
end;
$$;

create or replace function public.reconcile_expired_active_match_for_user()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare changed_count integer;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  update public.matches m
     set server_status = 'ABANDONED'
   where m.protocol_version = 1
     and m.server_status in ('CREATED', 'PENDING_RESULT')
     and exists (
       select 1 from public.active_match_participants a
        where a.match_id = m.id and a.user_id = caller_id and a.expires_at <= now()
     );
  get diagnostics changed_count = row_count;
  return changed_count;
end;
$$;

create or replace function public.enqueue_or_match()
returns table(
  match_id uuid,
  matched boolean,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare caller_rating integer;
declare candidate public.match_queue%rowtype;
declare created_match public.matches%rowtype;
declare caller_is_black boolean;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('othello.enqueue_or_match', 0)
  );
  perform public.reconcile_expired_active_match_for_user();
  delete from public.match_queue q where q.protocol_version = 1 and q.expires_at <= now();
  if exists (select 1 from public.active_match_participants a where a.user_id = caller_id) then
    raise exception 'user already has an active match';
  end if;
  select r.current_rating into caller_rating from public.ratings r where r.user_id = caller_id;
  if caller_rating is null then raise exception 'rating bootstrap missing'; end if;
  delete from public.match_queue q where q.user_id = caller_id and q.protocol_version = 1;
  select q.* into candidate
    from public.match_queue q
   where q.protocol_version = 1 and q.user_id <> caller_id and q.expires_at > now()
     and not exists (
       select 1 from public.active_match_participants a where a.user_id = q.user_id
     )
   order by abs(q.current_rating - caller_rating), q.queued_at
   for update skip locked limit 1;
  if candidate.user_id is null then
    insert into public.match_queue(
      user_id, current_rating, queued_at, expires_at, protocol_version, request_id
    ) values (
      caller_id, caller_rating, now(), now() + interval '2 minutes', 1, null
    );
    return query select null::uuid, false, null::uuid, null::text, null::integer;
    return;
  end if;
  delete from public.match_queue q
   where q.user_id = candidate.user_id and q.protocol_version = 1;
  caller_is_black := random() < 0.5;
  if caller_is_black then
    insert into public.matches(
      black_player, white_player, status, server_status,
      black_rating_at_start, white_rating_at_start, protocol_version
    ) values (
      caller_id, candidate.user_id, 'PLAYING', 'CREATED',
      caller_rating, candidate.current_rating, 1
    ) returning * into created_match;
  else
    insert into public.matches(
      black_player, white_player, status, server_status,
      black_rating_at_start, white_rating_at_start, protocol_version
    ) values (
      candidate.user_id, caller_id, 'PLAYING', 'CREATED',
      candidate.current_rating, caller_rating, 1
    ) returning * into created_match;
  end if;
  begin
    insert into public.active_match_participants(user_id, match_id, expires_at)
    values
      (created_match.black_player, created_match.id, created_match.created_expires_at),
      (created_match.white_player, created_match.id, created_match.created_expires_at);
  exception when unique_violation then
    raise exception 'user already has an active match';
  end;
  return query select created_match.id, true, candidate.user_id,
    case when created_match.black_player = caller_id then 'BLACK' else 'WHITE' end,
    candidate.current_rating;
end;
$$;

create or replace function public.claim_waiting_match()
returns table(
  match_id uuid,
  opponent_id uuid,
  assigned_disc text,
  opponent_rating integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare row_value public.matches%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  perform public.reconcile_expired_active_match_for_user();
  select m.* into row_value
    from public.matches m
    join public.match_notifications n on n.match_id = m.id and n.user_id = caller_id
   where m.protocol_version = 1
     and m.server_status = 'CREATED'
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
      then row_value.white_rating_at_start else row_value.black_rating_at_start end;
end;
$$;

create or replace function public.ack_match_started(p_match_id uuid)
returns public.server_match_status
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
declare ack_count integer;
declare play_expiry timestamptz;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 1
     or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'match participant required';
  end if;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then
    return match_row.server_status;
  end if;
  if match_row.server_status <> 'CREATED' then return match_row.server_status; end if;
  if match_row.p2p_started_at is null and match_row.created_expires_at <= now() then
    update public.matches set server_status = 'ABANDONED' where id = p_match_id;
    return 'ABANDONED';
  end if;
  insert into public.match_start_acks(match_id, user_id)
  values (p_match_id, caller_id) on conflict do nothing;
  select count(*)::integer into ack_count from public.match_start_acks a
   where a.match_id = p_match_id
     and a.user_id in (match_row.black_player, match_row.white_player);
  if ack_count = 2 and match_row.p2p_started_at is null then
    play_expiry := now() + interval '24 hours';
    update public.matches
       set p2p_started_at = now(), play_lease_expires_at = play_expiry
     where id = p_match_id;
    update public.active_match_participants as participant
       set expires_at = play_expiry where participant.match_id = p_match_id;
  end if;
  return 'CREATED';
end;
$$;

create or replace function public.abandon_match(p_match_id uuid)
returns public.server_match_status
language plpgsql
security definer
set search_path = ''
as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if not found or match_row.protocol_version <> 1
     or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'match participant required';
  end if;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then
    return match_row.server_status;
  end if;
  update public.matches set server_status = 'ABANDONED' where id = p_match_id;
  return 'ABANDONED';
end;
$$;

create or replace function public.cleanup_stale_created_matches()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare changed_count integer;
begin
  delete from public.match_queue q where q.protocol_version = 1 and q.expires_at <= now();
  update public.matches
     set server_status = 'ABANDONED'
   where protocol_version = 1 and server_status = 'CREATED'
     and p2p_started_at is null and created_expires_at <= now();
  get diagnostics changed_count = row_count;
  delete from public.match_signaling s
   using public.matches m
   where m.id = s.match_id and m.protocol_version = 1
     and (
       m.server_status <> 'CREATED'
       or m.p2p_started_at is not null
       or m.created_expires_at <= now()
     );
  return changed_count;
end;
$$;

create or replace function public.cleanup_expired_started_matches()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare changed_count integer;
begin
  update public.matches set server_status = 'ABANDONED'
   where protocol_version = 1 and server_status = 'CREATED'
     and p2p_started_at is not null and play_lease_expires_at <= now();
  get diagnostics changed_count = row_count;
  delete from public.match_signaling s
   using public.matches m
   where m.id = s.match_id and m.protocol_version = 1
     and (
       m.server_status <> 'CREATED'
       or m.p2p_started_at is not null
       or m.created_expires_at <= now()
     );
  return changed_count;
end;
$$;

create or replace function public.cleanup_expired_pending_results()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare changed_count integer;
begin
  update public.matches set server_status = 'ABANDONED'
   where protocol_version = 1 and server_status = 'PENDING_RESULT'
     and result_expires_at <= now();
  get diagnostics changed_count = row_count;
  delete from public.match_signaling s
   using public.matches m
   where m.id = s.match_id and m.protocol_version = 1
     and (
       m.server_status <> 'CREATED'
       or m.p2p_started_at is not null
       or m.created_expires_at <= now()
     );
  return changed_count;
end;
$$;

create or replace function public.require_p2p_started_for_result()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not exists (
    select 1 from public.matches m
     where m.id = new.match_id and m.protocol_version = 1 and m.p2p_started_at is not null
  ) then raise exception 'match P2P not started'; end if;
  return new;
end;
$$;

-- The old submit RPC calls this helper as its definer; clients never need direct access.
revoke execute on function public.finalize_match_v2(uuid) from authenticated;

revoke all on table
  public.match_start_acks_v2,
  public.match_result_claims_v2,
  public.match_results_v2,
  public.match_signals_v2
from public, anon, authenticated;
grant select on table public.match_results_v2, public.match_signals_v2 to authenticated;
revoke all on sequence public.match_signals_v2_id_seq from public, anon, authenticated;

revoke all on function public.enforce_release_match_transition_v2()
  from public, anon, authenticated, service_role;
revoke all on function public.cleanup_release_match_notifications_v2()
  from public, anon, authenticated, service_role;
revoke all on function public.enforce_match_signaling_v1_budget()
  from public, anon, authenticated, service_role;
revoke all on function public.release_is_legal_move_v2(smallint[], integer, integer)
  from public, anon, authenticated, service_role;
revoke all on function public.release_has_legal_move_v2(smallint[], integer)
  from public, anon, authenticated, service_role;
revoke all on function public.release_apply_move_v2(smallint[], integer, integer)
  from public, anon, authenticated, service_role;
revoke all on function public.release_replay_game_v2(text)
  from public, anon, authenticated, service_role;
revoke all on function public.release_assignment_row_v2(uuid, uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.release_match_state_row_v2(uuid, uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.release_result_response_row_v2(uuid, uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.release_expire_match_v2(uuid, text)
  from public, anon, authenticated, service_role;
revoke all on function public.release_finalize_result_v2(
  uuid, public.release_match_status, text, text, text, text, text, integer, integer, text
) from public, anon, authenticated, service_role;
revoke all on function public.release_reconcile_match_internal_v2(uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.notify_match_participants()
  from public, anon, authenticated, service_role;

revoke all on function public.enqueue_or_match_v2(uuid) from public, anon;
revoke all on function public.claim_active_match_v2() from public, anon;
revoke all on function public.cancel_waiting_v2(uuid) from public, anon;
revoke all on function public.get_release_match_state_v2(uuid) from public, anon;
revoke all on function public.ack_match_started_v2(uuid, integer) from public, anon;
revoke all on function public.abandon_match_v2(uuid) from public, anon;
revoke all on function public.resume_match_v2(uuid, integer) from public, anon;
revoke all on function public.reconcile_match_v2(uuid) from public, anon;
revoke all on function public.submit_match_result_v2(uuid, uuid, text, text, text, jsonb)
  from public, anon;
revoke all on function public.publish_match_signal_v2(uuid, text, text, integer, integer)
  from public, anon;
revoke all on function public.run_match_maintenance_v2(integer)
  from public, anon, authenticated;
revoke all on function public.run_legacy_match_maintenance_v1(integer)
  from public, anon, authenticated;
revoke all on function public.get_match_start_state(uuid) from public, anon;

grant execute on function public.enqueue_or_match_v2(uuid) to authenticated;
grant execute on function public.claim_active_match_v2() to authenticated;
grant execute on function public.cancel_waiting_v2(uuid) to authenticated;
grant execute on function public.get_release_match_state_v2(uuid) to authenticated;
grant execute on function public.ack_match_started_v2(uuid, integer) to authenticated;
grant execute on function public.abandon_match_v2(uuid) to authenticated;
grant execute on function public.resume_match_v2(uuid, integer) to authenticated;
grant execute on function public.reconcile_match_v2(uuid) to authenticated;
grant execute on function public.submit_match_result_v2(uuid, uuid, text, text, text, jsonb)
  to authenticated;
grant execute on function public.publish_match_signal_v2(uuid, text, text, integer, integer)
  to authenticated;
grant execute on function public.run_match_maintenance_v2(integer) to service_role;
grant execute on function public.run_legacy_match_maintenance_v1(integer) to service_role;
grant execute on function public.get_match_start_state(uuid) to authenticated;

-- Reassert the unchanged protocol-1 grants after replacing the compatibility bodies.
revoke all on function public.submit_match_result(uuid, text, text, text, text, jsonb)
  from public, anon;
grant execute on function public.submit_match_result(uuid, text, text, text, text, jsonb)
  to authenticated;
grant execute on function public.enqueue_or_match() to authenticated;
grant execute on function public.claim_waiting_match() to authenticated;
grant execute on function public.cancel_waiting() to authenticated;
grant execute on function public.heartbeat_waiting() to authenticated;
grant execute on function public.reconcile_expired_active_match_for_user() to authenticated;
grant execute on function public.ack_match_started(uuid) to authenticated;
grant execute on function public.abandon_match(uuid) to authenticated;
grant execute on function public.cleanup_stale_created_matches() to service_role;
grant execute on function public.cleanup_expired_started_matches() to service_role;
grant execute on function public.cleanup_expired_pending_results() to service_role;

comment on function public.run_match_maintenance_v2(integer) is
  'Bounded service-role safety sweep. Deploy its caller separately; this migration creates no production schedule.';
comment on function public.run_legacy_match_maintenance_v1(integer) is
  'Bounded service-role coexistence sweep for protocol-1 leases, signaling, and queue rows.';

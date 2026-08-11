-- Match lifecycle follow-up: one active reservation per user and an expiring CREATED lease.

alter table public.matches add column if not exists created_expires_at timestamptz not null default (now() + interval '5 minutes');
alter table public.matches add column if not exists retention_until timestamptz;

create table if not exists public.active_match_participants (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  match_id uuid not null references public.matches(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index if not exists active_match_participants_match_idx on public.active_match_participants(match_id);
create index if not exists active_match_participants_expires_idx on public.active_match_participants(expires_at);
alter table public.active_match_participants enable row level security;
drop policy if exists "participants read own active reservation" on public.active_match_participants;
create policy "participants read own active reservation" on public.active_match_participants
  for select using (auth.uid() = user_id);

insert into public.active_match_participants(user_id, match_id, created_at, expires_at)
select player_id, id, created_at, created_expires_at
from public.matches
cross join lateral unnest(array[black_player, white_player]) as players(player_id)
where server_status in ('CREATED', 'PENDING_RESULT')
on conflict (user_id) do nothing;

create or replace function public.release_active_match_reservations()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED')
     and old.server_status is distinct from new.server_status then
    delete from public.active_match_participants where match_id = new.id;
  end if;
  return new;
end;
$$;
drop trigger if exists release_active_match_reservations on public.matches;
create trigger release_active_match_reservations after update of server_status on public.matches
for each row execute function public.release_active_match_reservations();

create or replace function public.set_match_retention()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.server_status = 'ABANDONED' and old.server_status is distinct from new.server_status then
    new.retention_until := coalesce(new.retention_until, now() + interval '7 days');
  elsif new.server_status = 'DISPUTED' and old.server_status is distinct from new.server_status then
    new.retention_until := coalesce(new.retention_until, now() + interval '30 days');
  elsif new.server_status = 'CONFIRMED' and old.server_status is distinct from new.server_status then
    new.retention_until := coalesce(new.retention_until, now() + interval '365 days');
  end if;
  return new;
end;
$$;
drop trigger if exists set_match_retention on public.matches;
create trigger set_match_retention before update of server_status on public.matches
for each row execute function public.set_match_retention();

create or replace function public.cleanup_stale_created_matches()
returns integer language plpgsql security definer set search_path = '' as $$
declare changed_count integer;
begin
  delete from public.match_queue where expires_at <= now();
  update public.matches set server_status = 'ABANDONED'
   where server_status = 'CREATED' and created_expires_at <= now();
  get diagnostics changed_count = row_count;
  delete from public.active_match_participants where expires_at <= now();
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
  -- Serialize candidate selection while the primary key below remains the invariant.
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('othello.enqueue_or_match', 0));
  perform public.cleanup_stale_created_matches();
  perform public.cleanup_terminal_matches();
  delete from public.active_match_participants where expires_at <= now();
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

create or replace function public.abandon_match(p_match_id uuid)
returns public.server_match_status language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare match_row public.matches%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into match_row from public.matches where id = p_match_id for update;
  if match_row.id is null or caller_id not in (match_row.black_player, match_row.white_player) then
    raise exception 'match participant required';
  end if;
  if match_row.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED') then return match_row.server_status; end if;
  update public.matches set server_status = 'ABANDONED' where id = p_match_id;
  return 'ABANDONED';
end;
$$;

revoke execute on function public.cleanup_stale_created_matches() from public, anon, authenticated;
revoke all on function public.enqueue_or_match() from public;
revoke all on function public.abandon_match(uuid) from public;
grant execute on function public.enqueue_or_match() to authenticated;
grant execute on function public.abandon_match(uuid) to authenticated;
grant execute on function public.cleanup_stale_created_matches() to service_role;

comment on table public.active_match_participants is 'The user_id primary key makes two active matches for one user impossible.';

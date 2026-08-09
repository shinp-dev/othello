-- Minimal pre-WebRTC signaling contract. This is not an authoritative game state:
-- moves, clocks and results remain outside Realtime.
create table if not exists public.match_notifications (
  user_id uuid not null references public.profiles(id) on delete cascade,
  match_id uuid not null references public.matches(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, match_id)
);

create table if not exists public.match_signaling (
  id bigint generated always as identity primary key,
  match_id uuid not null references public.matches(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  signal_type text not null check (signal_type in ('OFFER', 'ANSWER')),
  sdp text not null check (char_length(sdp) between 1 and 16384),
  protocol_version integer not null check (protocol_version = 1),
  created_at timestamptz not null default now()
);
create index if not exists match_signaling_match_created_idx on public.match_signaling(match_id, created_at);

alter table public.match_notifications enable row level security;
alter table public.match_signaling enable row level security;
create policy "participants receive own match notifications" on public.match_notifications
  for select using (auth.uid() = user_id);
create policy "participants read signaling" on public.match_signaling
  for select using (exists (
    select 1 from public.matches m where m.id = match_id and auth.uid() in (m.black_player, m.white_player)
  ));
create policy "participants write signaling" on public.match_signaling
  for insert with check (auth.uid() = sender_id and exists (
    select 1 from public.matches m where m.id = match_id and auth.uid() in (m.black_player, m.white_player)
  ));

create or replace function public.notify_match_participants()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.match_notifications(user_id, match_id)
  values (new.black_player, new.id), (new.white_player, new.id)
  on conflict do nothing;
  return new;
end;
$$;
drop trigger if exists notify_match_participants on public.matches;
create trigger notify_match_participants after insert on public.matches
for each row execute function public.notify_match_participants();

create or replace function public.claim_waiting_match()
returns table(match_id uuid, opponent_id uuid, assigned_disc text)
language plpgsql security definer set search_path = '' as $$
declare caller_id uuid := auth.uid();
declare row_value public.matches%rowtype;
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select m.* into row_value from public.matches m
  join public.match_notifications n on n.match_id = m.id and n.user_id = caller_id
  where m.server_status = 'CREATED' order by m.created_at for update skip locked limit 1;
  if row_value.id is null then return; end if;
  delete from public.match_notifications where user_id = caller_id and match_id = row_value.id;
  return query select row_value.id, case when row_value.black_player = caller_id then row_value.white_player else row_value.black_player end,
    case when row_value.black_player = caller_id then 'BLACK' else 'WHITE' end;
end;
$$;

revoke all on function public.claim_waiting_match() from public;
grant execute on function public.claim_waiting_match() to authenticated;
revoke all on function public.notify_match_participants() from public, anon, authenticated;

do $$ begin
  alter publication supabase_realtime add table public.match_notifications;
  alter publication supabase_realtime add table public.match_signaling;
exception when duplicate_object then null;
end $$;

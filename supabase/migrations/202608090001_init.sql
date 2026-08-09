-- Authoritative writes go through narrow server-side functions. RLS is enabled on every table.
create extension if not exists pgcrypto;
create type public.match_status as enum ('WAITING', 'SIGNALING', 'PLAYING', 'FINISHING', 'CONFIRMED', 'PENDING_RESULT', 'DISPUTED');
create type public.credential_status as enum ('SELF_DECLARED', 'PENDING', 'VERIFIED', 'REJECTED');
create table public.profiles (id uuid primary key references auth.users(id) on delete cascade, display_name text not null check (char_length(display_name) between 1 and 40), created_at timestamptz not null default now(), updated_at timestamptz not null default now());
create table public.ratings (user_id uuid primary key references public.profiles(id) on delete cascade, current_rating integer not null default 1500, peak_rating integer not null default 1500, algorithm_version text not null default 'elo-v1', updated_at timestamptz not null default now());
create table public.rating_history (id bigint generated always as identity primary key, user_id uuid not null references public.profiles(id) on delete cascade, match_id uuid, rating integer not null, delta integer not null, algorithm_version text not null, created_at timestamptz not null default now());
create table public.match_queue (user_id uuid primary key references public.profiles(id) on delete cascade, current_rating integer not null, queued_at timestamptz not null default now());
create table public.matches (id uuid primary key default gen_random_uuid(), black_player uuid not null references public.profiles(id), white_player uuid not null references public.profiles(id), status public.match_status not null default 'PLAYING', created_at timestamptz not null default now(), confirmed_at timestamptz);
create table public.match_submissions (match_id uuid not null references public.matches(id) on delete cascade, player_id uuid not null references public.profiles(id), moves jsonb not null, result text not null, final_position_hash text not null, finish_reason text not null, clock jsonb, submitted_at timestamptz not null default now(), primary key (match_id, player_id));
create table public.game_records (match_id uuid primary key references public.matches(id), players uuid[] not null check (array_length(players, 1) = 2), moves jsonb not null, result text not null, started_at timestamptz not null, finished_at timestamptz not null, time_control text not null, finish_reason text not null);
create table public.federation_credentials (id uuid primary key default gen_random_uuid(), user_id uuid not null references public.profiles(id) on delete cascade, organization text not null, credential_type text not null, value text not null, status public.credential_status not null default 'SELF_DECLARED', verified_at timestamptz, unique (user_id, organization, credential_type));
create table public.verification_submissions (id uuid primary key default gen_random_uuid(), credential_id uuid not null references public.federation_credentials(id) on delete cascade, user_id uuid not null references public.profiles(id) on delete cascade, evidence_path text not null, status public.credential_status not null default 'PENDING', reviewed_at timestamptz, created_at timestamptz not null default now());

do $$ declare table_name text; begin foreach table_name in array array['profiles','ratings','rating_history','match_queue','matches','match_submissions','game_records','federation_credentials','verification_submissions'] loop execute format('alter table public.%I enable row level security', table_name); end loop; end $$;
create policy "profiles are public, owner updates" on public.profiles for select using (true);
create policy "owner updates own profile" on public.profiles for update using (auth.uid() = id) with check (auth.uid() = id);
create policy "user reads own rating" on public.ratings for select using (auth.uid() = user_id);
create policy "rating history is private" on public.rating_history for select using (auth.uid() = user_id);
create policy "queue owner manages own row" on public.match_queue for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "players read their matches" on public.matches for select using (auth.uid() in (black_player, white_player));
create policy "players submit own result" on public.match_submissions for insert with check (auth.uid() = player_id);
create policy "players read their records" on public.game_records for select using (auth.uid() = any(players));
create policy "owner reads own credential" on public.federation_credentials for select using (auth.uid() = user_id);
create policy "owner self declares" on public.federation_credentials for insert with check (auth.uid() = user_id and status = 'SELF_DECLARED');
create policy "owner reads own submissions" on public.verification_submissions for select using (auth.uid() = user_id);
create policy "owner creates submission" on public.verification_submissions for insert with check (auth.uid() = user_id and status = 'PENDING');

-- The queue operation is atomic and deliberately validates an authenticated caller before using elevated table access.
create or replace function public.match_nearest_waiting(p_rating integer)
returns table(match_id uuid, opponent_id uuid) language plpgsql security definer set search_path = public as $$
declare candidate match_queue%rowtype;
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  select * into candidate from match_queue where user_id <> auth.uid() order by abs(current_rating - p_rating), queued_at for update skip locked limit 1;
  if candidate.user_id is null then insert into match_queue(user_id, current_rating) values (auth.uid(), p_rating) on conflict (user_id) do update set current_rating = excluded.current_rating, queued_at = now(); return; end if;
  delete from match_queue where user_id = candidate.user_id;
  insert into matches(black_player, white_player) values (candidate.user_id, auth.uid()) returning id, candidate.user_id into match_id, opponent_id;
  return next;
end; $$;

create or replace function public.finalize_match(p_match_id uuid)
returns public.match_status language plpgsql security definer set search_path = public as $$
declare first_submission match_submissions%rowtype; second_submission match_submissions%rowtype; next_status public.match_status;
begin
  if auth.uid() is null or not exists (select 1 from matches where id = p_match_id and auth.uid() in (black_player, white_player)) then raise exception 'match access denied'; end if;
  select * into first_submission from match_submissions where match_id = p_match_id order by submitted_at limit 1;
  select * into second_submission from match_submissions where match_id = p_match_id and player_id <> first_submission.player_id limit 1;
  if second_submission.player_id is null then next_status := 'PENDING_RESULT';
  elsif first_submission.moves = second_submission.moves and first_submission.result = second_submission.result and first_submission.final_position_hash = second_submission.final_position_hash then next_status := 'CONFIRMED';
  else next_status := 'DISPUTED'; end if;
  update matches set status = next_status, confirmed_at = case when next_status = 'CONFIRMED' then now() else confirmed_at end where id = p_match_id;
  return next_status;
end; $$;

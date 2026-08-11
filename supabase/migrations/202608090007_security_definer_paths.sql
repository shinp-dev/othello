-- Revisit legacy and internal SECURITY DEFINER bodies after the additive migrations.
-- Every relation/function outside pg_catalog is schema-qualified with an empty search_path.

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
declare display_name_value text;
begin
  display_name_value := left(coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), split_part(coalesce(new.email, 'player'), '@', 1), 'player'), 40);
  insert into public.profiles(id, display_name) values (new.id, coalesce(nullif(display_name_value, ''), 'player')) on conflict (id) do nothing;
  insert into public.ratings(user_id, current_rating, peak_rating) values (new.id, public.initial_rating(), public.initial_rating()) on conflict (user_id) do nothing;
  return new;
end;
$$;

create or replace function public.cancel_waiting()
returns boolean language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  delete from public.match_queue where user_id = auth.uid();
  return found;
end;
$$;

create or replace function public.heartbeat_waiting()
returns boolean language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  update public.match_queue set expires_at = now() + interval '2 minutes'
   where user_id = auth.uid() and expires_at > now();
  return found;
end;
$$;

create or replace function public.prune_user_game_records(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  delete from public.user_game_records r where r.user_id = p_user_id and r.match_id in (
    select match_id from (
      select r2.match_id, row_number() over (order by g.finished_at desc) as row_number
       from public.user_game_records r2 join public.game_records g using (match_id) where r2.user_id = p_user_id
    ) ranked where row_number > 50
  );
  delete from public.game_records g where not exists (select 1 from public.user_game_records r where r.match_id = g.match_id);
end;
$$;

create or replace function public.prune_rating_history(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  delete from public.rating_history h where h.id in (
    select id from (
      select id, row_number() over (order by created_at desc) as row_number
       from public.rating_history where user_id = p_user_id
    ) ranked where row_number > 100
  );
end;
$$;

create or replace function public.match_nearest_waiting(p_rating integer)
returns table(match_id uuid, opponent_id uuid)
language plpgsql security definer set search_path = '' as $$
declare candidate public.match_queue%rowtype;
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  select * into candidate from public.match_queue
   where user_id <> auth.uid() order by abs(current_rating - p_rating), queued_at for update skip locked limit 1;
  if candidate.user_id is null then
    insert into public.match_queue(user_id, current_rating) values (auth.uid(), p_rating)
      on conflict (user_id) do update set current_rating = excluded.current_rating, queued_at = now();
    return;
  end if;
  delete from public.match_queue where user_id = candidate.user_id;
  insert into public.matches(black_player, white_player)
   values (candidate.user_id, auth.uid()) returning id, candidate.user_id into match_id, opponent_id;
  return next;
end;
$$;

create or replace function public.finalize_match(p_match_id uuid)
returns public.match_status language plpgsql security definer set search_path = '' as $$
declare first_submission public.match_submissions%rowtype;
declare second_submission public.match_submissions%rowtype;
declare next_status public.match_status;
begin
  if auth.uid() is null or not exists (select 1 from public.matches where id = p_match_id and auth.uid() in (black_player, white_player)) then raise exception 'match access denied'; end if;
  select * into first_submission from public.match_submissions where match_id = p_match_id order by submitted_at limit 1;
  select * into second_submission from public.match_submissions where match_id = p_match_id and player_id <> first_submission.player_id limit 1;
  if second_submission.player_id is null then next_status := 'PENDING_RESULT';
  elsif first_submission.moves = second_submission.moves and first_submission.result = second_submission.result and first_submission.final_position_hash = second_submission.final_position_hash then next_status := 'CONFIRMED';
  else next_status := 'DISPUTED'; end if;
  update public.matches set status = next_status, confirmed_at = case when next_status = 'CONFIRMED' then now() else confirmed_at end where id = p_match_id;
  return next_status;
end;
$$;

revoke execute on function public.prune_user_game_records(uuid) from public, anon, authenticated;
revoke execute on function public.prune_rating_history(uuid) from public, anon, authenticated;
revoke execute on function public.match_nearest_waiting(integer) from public, anon, authenticated;
revoke execute on function public.finalize_match(uuid) from public, anon, authenticated;
grant execute on function public.cancel_waiting() to authenticated;
grant execute on function public.heartbeat_waiting() to authenticated;

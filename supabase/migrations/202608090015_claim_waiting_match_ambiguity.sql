-- Repair the hosted claim path: the table-returning output parameter `match_id`
-- otherwise conflicts with the notification column of the same name.
create or replace function public.claim_waiting_match()
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

revoke all on function public.claim_waiting_match() from public;
grant execute on function public.claim_waiting_match() to authenticated;

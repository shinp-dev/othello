-- Keep hosted Data API access deterministic even when "Automatically expose new
-- tables" is disabled. RLS remains the row-level authority; these are only the
-- minimum table/column privileges required by the Android client.
grant usage on schema public to anon, authenticated;

revoke all on table
  public.profiles,
  public.ratings,
  public.rating_history,
  public.match_queue,
  public.matches,
  public.match_submissions,
  public.game_records,
  public.federation_credentials,
  public.verification_submissions,
  public.user_game_records,
  public.active_match_participants,
  public.match_start_acks,
  public.match_notifications,
  public.match_signaling
from public, anon, authenticated;

revoke all on table public.public_profiles from public, anon, authenticated;

grant select on table
  public.profiles,
  public.ratings,
  public.rating_history,
  public.matches,
  public.game_records,
  public.federation_credentials,
  public.verification_submissions,
  public.match_notifications,
  public.match_signaling
to authenticated;

grant update (display_name) on table public.profiles to authenticated;
grant insert on table public.federation_credentials to authenticated;
grant insert on table public.match_signaling to authenticated;
grant select on table public.public_profiles to anon, authenticated;

revoke all on sequence public.rating_history_id_seq from public, anon, authenticated;
revoke all on sequence public.match_signaling_id_seq from public, anon, authenticated;
grant usage, select on sequence public.match_signaling_id_seq to authenticated;

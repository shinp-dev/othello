-- Replace the pre-release non-trickle SDP-only wire contract with typed JSON
-- payloads for SDP descriptions and trickled ICE candidates. Signaling remains
-- connection-establishment-only; game state continues to use DataChannel.

alter table public.match_signaling
  add column payload jsonb;

-- Preserve any in-flight/historical SDP rows while moving them to the new shape.
update public.match_signaling
   set payload = jsonb_build_object('sdp', sdp);

alter table public.match_signaling
  alter column payload set not null,
  drop constraint match_signaling_signal_type_check,
  drop constraint match_signaling_sdp_check,
  drop constraint match_signaling_protocol_version_check;

-- Signaling has its own version. DataChannel game commands remain at version 1.
update public.match_signaling
   set protocol_version = 2;

alter table public.match_signaling
  drop column sdp,
  add constraint match_signaling_signal_type_check
    check (signal_type in ('OFFER', 'ANSWER', 'ICE_CANDIDATE')),
  add constraint match_signaling_protocol_version_check
    check (protocol_version = 2),
  add constraint match_signaling_payload_check
    check (
      jsonb_typeof(payload) = 'object'
      and case signal_type
        when 'OFFER' then
          payload ? 'sdp'
          and jsonb_typeof(payload -> 'sdp') = 'string'
          and char_length(payload ->> 'sdp') between 1 and 16384
        when 'ANSWER' then
          payload ? 'sdp'
          and jsonb_typeof(payload -> 'sdp') = 'string'
          and char_length(payload ->> 'sdp') between 1 and 16384
        when 'ICE_CANDIDATE' then
          payload ? 'candidate'
          and jsonb_typeof(payload -> 'candidate') = 'string'
          and char_length(payload ->> 'candidate') between 1 and 4096
          and payload ? 'sdpMid'
          and (
            jsonb_typeof(payload -> 'sdpMid') = 'null'
            or (
              jsonb_typeof(payload -> 'sdpMid') = 'string'
              and char_length(payload ->> 'sdpMid') <= 256
            )
          )
          and payload ? 'sdpMLineIndex'
          and jsonb_typeof(payload -> 'sdpMLineIndex') = 'number'
          and (payload ->> 'sdpMLineIndex') ~ '^[0-9]{1,6}$'
        else false
      end
    );

drop index if exists public.match_signaling_match_created_idx;
create index match_signaling_match_id_idx on public.match_signaling(match_id, id);

-- Make verification storage private and make terminal match retention follow bounded records.

insert into storage.buckets(id, name, public)
values ('verification', 'verification', false)
on conflict (id) do update set name = excluded.name, public = false;

drop policy if exists "verification objects owner insert" on storage.objects;
create policy "verification objects owner insert" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'verification'
    and name ~ ('^' || auth.uid()::text || '/[^/]+$')
    and name !~ '(^|/)\.\.(\/|$)'
  );
drop policy if exists "verification objects owner read" on storage.objects;
create policy "verification objects owner read" on storage.objects
  for select to authenticated
  using (
    bucket_id = 'verification'
    and name ~ ('^' || auth.uid()::text || '/[^/]+$')
  );

create or replace function public.submit_verification_submission(p_credential_id uuid, p_evidence_path text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare credential_row public.federation_credentials%rowtype;
declare submission_id uuid;
begin
  if auth.uid() is null then raise exception 'authentication required'; end if;
  select * into credential_row
    from public.federation_credentials
   where id = p_credential_id and user_id = auth.uid()
   for update;
  if credential_row.id is null then raise exception 'credential ownership required'; end if;
  if credential_row.status not in ('SELF_DECLARED', 'REJECTED') then raise exception 'credential is not submittable'; end if;
  if p_evidence_path is null
     or char_length(p_evidence_path) > 512
     or p_evidence_path !~ ('^' || auth.uid()::text || '/[^/]+$')
     or p_evidence_path ~ '(^|/)\.\.(\/|$)'
     or not exists (
       select 1 from storage.objects
        where bucket_id = 'verification'
          and name = p_evidence_path
          and owner_id = auth.uid()::text
     ) then
    raise exception 'evidence object ownership required';
  end if;
  insert into public.verification_submissions(credential_id, user_id, evidence_path, status)
  values (p_credential_id, auth.uid(), p_evidence_path, 'PENDING') returning id into submission_id;
  update public.federation_credentials set status = 'PENDING' where id = p_credential_id;
  return submission_id;
end;
$$;

-- Game records are retained while referenced by the bounded per-user index. Once the record
-- is gone, a confirmed match can disappear after a short grace period; disputed matches keep
-- the longer investigation window.
create or replace function public.set_match_retention()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.server_status = 'ABANDONED' and old.server_status is distinct from new.server_status then
    new.retention_until := coalesce(new.retention_until, now() + interval '7 days');
  elsif new.server_status = 'DISPUTED' and old.server_status is distinct from new.server_status then
    new.retention_until := coalesce(new.retention_until, now() + interval '30 days');
  elsif new.server_status = 'CONFIRMED' and old.server_status is distinct from new.server_status then
    new.retention_until := coalesce(new.retention_until, now() + interval '7 days');
  end if;
  return new;
end;
$$;

update public.matches
   set result_expires_at = coalesce(result_expires_at, created_at + interval '30 days')
 where server_status = 'PENDING_RESULT';

update public.matches
   set retention_until = now() + interval '7 days'
 where server_status in ('CONFIRMED', 'ABANDONED')
   and (retention_until is null or retention_until > now() + interval '7 days');
update public.matches
   set retention_until = now() + interval '30 days'
 where server_status = 'DISPUTED'
   and (retention_until is null or retention_until > now() + interval '30 days');

create or replace function public.cleanup_terminal_matches()
returns integer language plpgsql security definer set search_path = '' as $$
declare deleted_count integer;
begin
  perform public.cleanup_expired_match_submissions();
  -- Keep confirmed matches while their game record is referenced by bounded history.
  delete from public.game_records g using public.matches m
   where g.match_id = m.id
     and m.server_status = 'CONFIRMED'
     and not exists (select 1 from public.user_game_records r where r.match_id = g.match_id);
  delete from public.matches m
   where m.server_status in ('CONFIRMED', 'DISPUTED', 'ABANDONED')
     and m.retention_until is not null and m.retention_until <= now()
     and not exists (select 1 from public.game_records g where g.match_id = m.id)
     and not exists (select 1 from public.match_submissions s where s.match_id = m.id)
     and not exists (select 1 from public.active_match_participants a where a.match_id = m.id);
  get diagnostics deleted_count = row_count;
  return deleted_count;
end;
$$;

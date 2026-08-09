-- Verification evidence ownership, idempotent review, and retryable cleanup.

alter table public.verification_submissions alter column evidence_path drop not null;

create or replace function public.submit_verification_submission(p_credential_id uuid, p_evidence_path text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare credential_row public.federation_credentials%rowtype;
declare submission_id uuid;
declare caller_id uuid := auth.uid();
begin
  if caller_id is null then raise exception 'authentication required'; end if;
  select * into credential_row from public.federation_credentials where id = p_credential_id and user_id = caller_id for update;
  if credential_row.id is null then raise exception 'credential ownership required'; end if;
  if credential_row.status not in ('SELF_DECLARED', 'REJECTED') then raise exception 'credential is not submittable'; end if;
  if p_evidence_path is null or char_length(p_evidence_path) > 512
     or p_evidence_path !~ ('^' || caller_id::text || '/[^/].*$')
     or p_evidence_path ~ '(^/|(^|/)\.\.(\/|$))' then
    raise exception 'evidence path must be an owned verification object';
  end if;
  -- The fixed bucket and Storage owner are checked in the database, not trusted from the client.
  if not exists (
    select 1 from storage.objects
     where bucket_id = 'verification' and name = p_evidence_path and owner_id = caller_id
  ) then raise exception 'evidence object ownership required'; end if;
  insert into public.verification_submissions(credential_id, user_id, evidence_path, status)
  values (p_credential_id, caller_id, p_evidence_path, 'PENDING') returning id into submission_id;
  update public.federation_credentials set status = 'PENDING' where id = p_credential_id;
  return submission_id;
end;
$$;

create or replace function public.review_verification_submission(p_submission_id uuid, p_decision public.credential_status)
returns text language plpgsql security definer set search_path = '' as $$
declare submission public.verification_submissions%rowtype;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  if p_decision not in ('VERIFIED', 'REJECTED') then raise exception 'invalid review decision'; end if;
  select * into submission from public.verification_submissions where id = p_submission_id for update;
  if submission.id is null then raise exception 'submission not found'; end if;
  if submission.status in ('VERIFIED', 'REJECTED') then
    if submission.status <> p_decision then raise exception 'review decision conflict'; end if;
    return submission.status::text;
  end if;
  if submission.status <> 'PENDING' then raise exception 'submission is not reviewable'; end if;
  update public.verification_submissions set status = p_decision, reviewed_at = now() where id = p_submission_id;
  update public.federation_credentials set status = p_decision,
    verified_at = case when p_decision = 'VERIFIED' then now() else null end
   where id = submission.credential_id and user_id = submission.user_id;
  return p_decision::text;
end;
$$;

create or replace function public.get_verification_evidence_cleanup(p_submission_id uuid)
returns text language plpgsql security definer set search_path = '' as $$
declare submission public.verification_submissions%rowtype;
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  select * into submission from public.verification_submissions where id = p_submission_id for update;
  if submission.id is null then raise exception 'submission not found'; end if;
  if submission.status not in ('VERIFIED', 'REJECTED') or submission.evidence_deleted_at is not null or submission.evidence_path is null then return null; end if;
  if submission.evidence_path !~ ('^' || submission.user_id::text || '/[^/].*$') then return null; end if;
  return submission.evidence_path;
end;
$$;

create or replace function public.mark_verification_evidence_deleted(p_submission_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.role() <> 'service_role' then raise exception 'admin service role required'; end if;
  update public.verification_submissions
     set evidence_deleted_at = coalesce(evidence_deleted_at, now()), evidence_path = null
   where id = p_submission_id and status in ('VERIFIED', 'REJECTED');
end;
$$;

revoke all on function public.submit_verification_submission(uuid, text) from public;
revoke all on function public.review_verification_submission(uuid, public.credential_status) from public;
revoke all on function public.get_verification_evidence_cleanup(uuid) from public;
revoke all on function public.mark_verification_evidence_deleted(uuid) from public;
grant execute on function public.submit_verification_submission(uuid, text) to authenticated;
grant execute on function public.review_verification_submission(uuid, public.credential_status) to service_role;
grant execute on function public.get_verification_evidence_cleanup(uuid) to service_role;
grant execute on function public.mark_verification_evidence_deleted(uuid) to service_role;

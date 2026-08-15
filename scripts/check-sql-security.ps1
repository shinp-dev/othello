$sql = (Get-ChildItem 'supabase/migrations/*.sql' | Sort-Object Name | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
$requiredPatterns = @(
    'create type public.server_match_status',
    'alter table public.matches add column server_status',
    'select current_rating into caller_rating from public.ratings',
    'expires_at > now\(\)',
    'for update skip locked',
    'create or replace function public.submit_match_result',
    'caller_id not in \(match_row.black_player, match_row.white_player\)',
    'create or replace function public.finalize_match_v2',
    'player_id = match_row.black_player',
    'player_id = match_row.white_player',
    'rating_history_user_match_unique',
    'create or replace function public.handle_new_user',
    'drop view if exists public.public_profiles',
    'auth.role\(\) <> ''service_role''',
    'grant execute on function public.enqueue_or_match\(\) to authenticated',
    'revoke execute on function public.prune_user_game_records\(uuid\) from public, anon, authenticated',
    'revoke execute on function public.prune_rating_history\(uuid\) from public, anon, authenticated',
    'create table if not exists public.active_match_participants',
    'user_id uuid primary key',
    'create or replace function public.abandon_match',
    'create or replace function public.ack_match_started',
    'match_start_acks',
    'get_match_start_state',
    'cleanup_expired_pending_results',
    'p2p_started_at',
    'play_lease_expires_at',
    'matches_created_lease_idx',
    'matches_retention_idx',
    'drop policy if exists "verification objects owner insert" on storage.objects',
    'drop policy if exists "verification objects owner insert" on storage.objects',
    'file_size_limit',
    'allowed_mime_types',
    'reconcile_expired_active_match_for_user',
    'require_p2p_started_for_result',
    'match P2P not started',
    '5 minutes',
    'created_expires_at',
    'returns public.server_match_status',
    "p_result not in \('BLACK_WIN', 'WHITE_WIN', 'DRAW'\)",
    'p_clock.*pg_column_size',
    'cleanup_terminal_matches',
    'alter default privileges in schema public revoke execute on functions from public',
    'revoke all on table[\s\S]*public\.match_signaling[\s\S]*from public, anon, authenticated',
    'revoke all on table public\.profiles from public, anon, authenticated',
    'drop table if exists public\.federation_credentials',
    'drop table if exists public\.verification_submissions',
    'drop type if exists public\.credential_status',
    'drop column if exists display_name',
    'drop column if exists created_at',
    'black_rating_at_start integer',
    'white_rating_at_start integer',
    'opponent_rating integer',
    'grant insert on table public\.match_signaling to authenticated',
    'grant usage, select on sequence public\.match_signaling_id_seq to authenticated',
    'delete from public\.match_notifications n[\s\S]*n\.match_id = row_value\.id',
    'alter table public\.game_records add column if not exists final_position_hash',
    'final position hash required',
    'create or replace function public\.prepare_account_deletion',
    'create or replace function public\.complete_account_deletion',
    'drop function if exists public\.get_account_deletion_evidence\(uuid\)',
    'account deletion is pending',
    'create schema if not exists research_private',
    'create table research_private\.consent_versions',
    'create table research_private\.policy_versions',
    'create table research_private\.research_subjects',
    'create table research_private\.participation_periods',
    'research_subject_one_link_per_account_idx',
    'research_participation_one_open_idx',
    'collection_enabled boolean not null default false',
    'create or replace function public\.get_research_participation_status',
    'create or replace function public\.set_research_participation',
    'set search_path = ''''',
    'revoke all on table[\s\S]*research_private\.consent_versions[\s\S]*from public, anon, authenticated',
    'grant execute on function public\.get_research_participation_status\(\) to authenticated',
    'grant execute on function public\.set_research_participation\(boolean, integer\) to authenticated',
    'create table research_private\.games',
    'create table research_private\.game_contributors',
    'capture_confirmed_match_for_research',
    'source_match_key bytea not null unique',
    'rating - rh\.delta',
    'for update skip locked',
    'create or replace function public\.claim_research_validation_batch',
    'create or replace function public\.complete_research_validation',
    'grant execute on function public\.claim_research_validation_batch\(integer, integer\) to service_role',
    'grant execute on function public\.complete_research_validation\(bigint, uuid, integer, boolean, text, integer, integer\) to service_role',
    'revoke all on table research_private\.games, research_private\.game_contributors[\s\S]*from public, anon, authenticated',
    'create table research_private\.positions',
    'create table research_private\.aggregation_generations',
    'create table research_private\.subject_position_totals',
    'create table research_private\.subject_position_moves',
    'create table research_private\.position_aggregates',
    'create table research_private\.move_aggregates',
    'create table research_private\.published_generation',
    'create or replace function public\.claim_research_aggregation_build',
    'create or replace function public\.append_research_aggregation_game',
    'create or replace function public\.publish_research_aggregation',
    'create or replace function public\.fail_research_aggregation',
    'create or replace function public\.get_research_position',
    'grant execute on function public\.get_research_position\(text, text\) to authenticated',
    'grant execute on function public\.publish_research_aggregation\(bigint, uuid\) to service_role',
    'count\(distinct sm\.research_subject_id\)',
    'create role research_batch',
    'alter role research_batch nologin noinherit',
    'create or replace function research_private\.batch_claim_validation',
    'create or replace function research_private\.batch_claim_aggregation',
    'create or replace function research_private\.batch_checkpoint_aggregation',
    'grant execute on function research_private\.batch_claim_validation\(integer, integer\)[\s\S]*to research_batch',
    'insert into public\.profiles\(id\)'
)
$missing = $requiredPatterns | Where-Object { $sql -notmatch $_ }
if ($missing) { $missing | ForEach-Object { Write-Error "Missing SQL security contract: $_" }; exit 1 }
if (Test-Path 'analysis/edax/src/main/kotlin/com/example/othello/analysis/edax/HeuristicTestAnalysisEngine.kt') {
    Write-Error 'HeuristicTestAnalysisEngine must not be part of the production source set'; exit 1
}
if (-not (Test-Path 'supabase/tests/202608090003_hardening.sql')) {
    Write-Error 'pgTAP hardening test is missing'; exit 1
}
$testSql = Get-Content 'supabase/tests/202608090003_hardening.sql' -Raw
if ($testSql -notmatch 'from pg_proc p') {
    Write-Error 'pgTAP privilege query must include FROM pg_proc p'; exit 1
}
$onlineRepair = Get-Content 'supabase/migrations/202608090013_online_contract_repairs.sql' -Raw
if ($onlineRepair -match '(?s)create or replace function public\.enqueue_or_match\(\).*?cleanup_stale_created_matches') {
    Write-Error 'enqueue_or_match must not run global stale-match cleanup'; exit 1
}
$researchFoundation = Get-Content 'supabase/migrations/202608110018_research_foundation_consent.sql' -Raw
if ($researchFoundation -match 'create\s+table\s+research_private\.(games|game_contributors|subject_position)' -or
    $researchFoundation -match 'create\s+trigger') {
    Write-Error 'research foundation migration must not implement match capture, contribution, aggregation, or triggers'; exit 1
}
$researchCapture = Get-Content 'supabase/migrations/202608110019_research_capture_validator.sql' -Raw
if ($researchCapture -match 'subject_position|position_aggregates|move_aggregates|aggregation_generations|get_research_position') {
    Write-Error 'research capture migration must not implement aggregation, publication, or Review APIs'; exit 1
}
if ($researchCapture -match 'grant execute on function research_private\.capture_confirmed_match\(uuid\) to service_role') {
    Write-Error 'confirmed capture must not expose a backfill-capable service entry point'; exit 1
}
$researchAggregation = Get-Content 'supabase/migrations/202608110020_research_aggregation_privacy.sql' -Raw
if ($researchAggregation -match 'update\s+research_private\.policy_versions\s+set\s+collection_enabled\s*=\s*true' -or
    $researchAggregation -match 'create\s+trigger') {
    Write-Error 'research aggregation migration must not enable collection or add live-match triggers'; exit 1
}
$researchActions = Get-Content 'supabase/migrations/202608110022_research_actions_batch.sql' -Raw
if ($researchActions -match 'password\s+''' -or
    $researchActions -match 'alter\s+role\s+research_batch\s+login' -or
    $researchActions -match 'collection_enabled\s*=\s*true') {
    Write-Error 'research Actions migration must not contain credentials, enable login, or activate collection'; exit 1
}
$researchPlatformAcl = Get-Content 'supabase/migrations/202608110023_research_batch_platform_acl.sql' -Raw
if ($researchPlatformAcl -notmatch "to_regprocedure\('public\.rls_auto_enable\(\)'\)" -or
    $researchPlatformAcl -notmatch 'revoke execute on function public\.rls_auto_enable\(\) from public') {
    Write-Error 'Hosted platform event-trigger helper must not remain ambiently executable'; exit 1
}
$researchWorkflow = Get-Content '.github/workflows/research-batch.yml' -Raw
if ($researchWorkflow -match 'SERVICE_ROLE' -or $researchWorkflow -match 'service_role') {
    Write-Error 'research Actions workflow must use the limited database credential, never service_role'; exit 1
}
$workerSource = Get-Content 'cloudflare-admin/src/index.ts' -Raw
if ($workerSource -match 'processResearchValidationBatch' -or
    $workerSource -match 'processResearchAggregation' -or
    $workerSource -match '/admin/research/') {
    Write-Error 'Cloudflare Worker must not execute Research validation or aggregation'; exit 1
}
Write-Output 'SQL security contract check passed'

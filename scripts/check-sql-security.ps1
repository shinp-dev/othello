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
    'create (or replace )?view public.public_profiles',
    'credential ownership required',
    'auth.role\(\) <> ''service_role''',
    'grant execute on function public.enqueue_or_match\(\) to authenticated',
    'revoke execute on function public.prune_user_game_records\(uuid\) from public, anon, authenticated',
    'revoke execute on function public.prune_rating_history\(uuid\) from public, anon, authenticated',
    'create table if not exists public.active_match_participants',
    'user_id uuid primary key',
    'create or replace function public.abandon_match',
    'create or replace function public.ack_match_started',
    'match_start_acks',
    'cleanup_expired_pending_results',
    'p2p_started_at',
    'play_lease_expires_at',
    'matches_created_lease_idx',
    'matches_retention_idx',
    'insert into storage.buckets',
    'verification objects owner insert',
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
    'get_verification_evidence_cleanup',
    'review decision conflict',
    'cleanup_terminal_matches',
    'alter default privileges in schema public revoke execute on functions from public'
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
Write-Output 'SQL security contract check passed'

$sql = Get-Content 'supabase/migrations/202608090002_hardening_additive.sql' -Raw
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
    'grant execute on function public.enqueue_or_match\(\) to authenticated'
)
$missing = $requiredPatterns | Where-Object { $sql -notmatch $_ }
if ($missing) { $missing | ForEach-Object { Write-Error "Missing SQL security contract: $_" }; exit 1 }
Write-Output 'SQL security contract check passed'

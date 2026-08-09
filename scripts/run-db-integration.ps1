$supabase = Get-Command supabase -ErrorAction SilentlyContinue
if (-not $supabase) {
    Write-Error 'Supabase CLI is required for DB integration tests. Install it, then run: supabase test db'
    exit 1
}
supabase test db
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$matchSources = Get-ChildItem -Path 'feature/match/src' -Recurse -File -ErrorAction SilentlyContinue
$violations = $matchSources | Select-String -Pattern 'analysis|edax' -CaseSensitive
if ($violations) { $violations | ForEach-Object { Write-Error $_.ToString() }; exit 1 }
Write-Output 'dependency boundary check passed: feature/match does not reference analysis'

$matchSources = Get-ChildItem -Path 'feature/match/src' -Recurse -File -ErrorAction SilentlyContinue
$violations = $matchSources | Select-String -Pattern 'analysis|edax' -CaseSensitive
if ($violations) { $violations | ForEach-Object { Write-Error $_.ToString() }; exit 1 }
$matchBuild = Get-Content 'feature/match/build.gradle.kts' -Raw
if ($matchBuild -match 'analysis|edax') { Write-Error 'feature/match Gradle dependencies must not reference analysis/edax'; exit 1 }
$gameSources = Get-ChildItem -Path 'core/game/src' -Recurse -File -ErrorAction SilentlyContinue
$gameViolations = $gameSources | Select-String -Pattern 'androidx|android\.content|supabase|webrtc|com\.google\.android' -CaseSensitive
if ($gameViolations) { $gameViolations | ForEach-Object { Write-Error $_.ToString() }; exit 1 }
Write-Output 'dependency boundary check passed: match does not reference analysis and game is pure Kotlin'

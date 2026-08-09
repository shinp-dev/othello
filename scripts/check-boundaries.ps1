$matchSources = Get-ChildItem -Path 'feature/match/src' -Recurse -File -ErrorAction SilentlyContinue
$violations = $matchSources | Select-String -Pattern 'analysis|edax' -CaseSensitive
if ($violations) { $violations | ForEach-Object { Write-Error $_.ToString() }; exit 1 }
$matchBuild = Get-Content 'feature/match/build.gradle.kts' -Raw
if ($matchBuild -match 'analysis|edax') { Write-Error 'feature/match Gradle dependencies must not reference analysis/edax'; exit 1 }
$gameSources = Get-ChildItem -Path 'core/game/src' -Recurse -File -ErrorAction SilentlyContinue
$gameViolations = $gameSources | Select-String -Pattern 'androidx|android\.content|supabase|webrtc|com\.google\.android' -CaseSensitive
if ($gameViolations) { $gameViolations | ForEach-Object { Write-Error $_.ToString() }; exit 1 }
$allSources = Get-ChildItem -Path . -Recurse -File -Include *.kt,*.java -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '[\\/]data[\\/]supabase[\\/]src[\\/]' -and $_.FullName -notmatch '[\\/]transport[\\/]webrtc[\\/]src[\\/]' -and $_.FullName -notmatch '[\\/]build[\\/]' }
$supabaseLeaks = $allSources | Select-String -Pattern 'io\.github\.jan\.supabase' -CaseSensitive
if ($supabaseLeaks) { $supabaseLeaks | ForEach-Object { Write-Error "Supabase SDK leaked outside data:supabase: $_" }; exit 1 }
$webrtcLeaks = $allSources | Select-String -Pattern 'org\.webrtc' -CaseSensitive
if ($webrtcLeaks) { $webrtcLeaks | ForEach-Object { Write-Error "WebRTC SDK leaked outside transport:webrtc: $_" }; exit 1 }
$supabaseBuild = Get-Content 'data/supabase/build.gradle.kts' -Raw
if ($supabaseBuild -match '(?m)^\s*api\((platform\(|"io\.github\.jan\.supabase|"io\.ktor)') {
    Write-Error 'Supabase SDK and Ktor dependencies must remain implementation-only'; exit 1
}
$designSystemBuild = Get-Content 'core/designsystem/build.gradle.kts' -Raw
if ($designSystemBuild -notmatch 'org\.jetbrains\.kotlin\.plugin\.compose') {
    Write-Error 'core:designsystem must apply the Compose compiler plugin to keep its composable ABI compatible'; exit 1
}
Write-Output 'dependency boundary check passed: match does not reference analysis and game is pure Kotlin'

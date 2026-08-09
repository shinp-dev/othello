$liveModules = @('feature/match', 'feature/matchmaking')
foreach ($liveModule in $liveModules) {
    $liveSources = Get-ChildItem -Path "$liveModule/src" -Recurse -File -ErrorAction SilentlyContinue
    $violations = $liveSources | Select-String -Pattern 'analysis|edax' -CaseSensitive
    if ($violations) { $violations | ForEach-Object { Write-Error $_.ToString() }; exit 1 }
    $liveBuild = Get-Content "$liveModule/build.gradle.kts" -Raw
    if ($liveBuild -match 'analysis|edax') { Write-Error "$liveModule Gradle dependencies must not reference analysis/edax"; exit 1 }
}
$gameSources = Get-ChildItem -Path 'core/game/src' -Recurse -File -ErrorAction SilentlyContinue
$gameViolations = $gameSources | Select-String -Pattern 'androidx|android\.content|supabase|webrtc|com\.google\.android' -CaseSensitive
if ($gameViolations) { $gameViolations | ForEach-Object { Write-Error $_.ToString() }; exit 1 }
$gameBuild = Get-Content 'core/game/build.gradle.kts' -Raw
if ($gameBuild -match 'com\.android|analysis|edax|jni') { Write-Error 'core:game must remain pure Kotlin and independent of analysis/native code'; exit 1 }
$analysisApiSources = Get-ChildItem -Path 'analysis/api/src' -Recurse -File -ErrorAction SilentlyContinue
$analysisApiViolations = $analysisApiSources | Select-String -Pattern 'androidx|android\.|analysis\.edax|NativeEdax|jni' -CaseSensitive
if ($analysisApiViolations) { $analysisApiViolations | ForEach-Object { Write-Error "analysis:api leaked Android/Edax/JNI implementation: $_" }; exit 1 }
$reviewSources = Get-ChildItem -Path 'feature/review/src' -Recurse -File -ErrorAction SilentlyContinue
$reviewViolations = $reviewSources | Select-String -Pattern 'analysis\.edax|NativeEdax|org\.webrtc|io\.github\.jan\.supabase' -CaseSensitive
if ($reviewViolations) { $reviewViolations | ForEach-Object { Write-Error "feature:review leaked an implementation SDK: $_" }; exit 1 }
$reviewBuild = Get-Content 'feature/review/build.gradle.kts' -Raw
if ($reviewBuild -match 'analysis:edax' -or $reviewBuild -notmatch 'analysis:api') { Write-Error 'feature:review must depend on analysis:api, never analysis:edax'; exit 1 }
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
if ($supabaseBuild -notmatch 'io\.ktor:ktor-client-okhttp' -or $supabaseBuild -match 'io\.ktor:ktor-client-android') {
    Write-Error 'Supabase Realtime requires a WebSocket-capable Ktor engine (OkHttp)'; exit 1
}
$supabaseSource = Get-Content 'data/supabase/src/main/kotlin/com/example/othello/data/supabase/SupabaseContracts.kt' -Raw
$scalarRpcPatterns = @(
    'rpc\("cancel_waiting"\)\.decodeAs<Boolean>\(\)',
    'rpc\("heartbeat_waiting"\)\.decodeAs<Boolean>\(\)',
    'rpc\("reconcile_expired_active_match_for_user"\)\.decodeAs<Int>\(\)',
    'rpc\("ack_match_started", AckParams\(matchId\)\)\.decodeAs<String>\(\)',
    'rpc\("abandon_match", AckParams\(matchId\)\)\.decodeAs<String>\(\)',
    '(?s)rpc\(\s*"submit_match_result".{0,1000}?\)\.decodeAs<String>\(\)'
)
$missingScalarDecoders = $scalarRpcPatterns | Where-Object { $supabaseSource -notmatch $_ }
if ($missingScalarDecoders) {
    $missingScalarDecoders | ForEach-Object { Write-Error "Scalar PostgREST RPC must use decodeAs, not decodeSingle: $_" }
    exit 1
}
$appManifest = Get-Content 'app/src/main/AndroidManifest.xml' -Raw
if ($appManifest -notmatch 'android\.permission\.ACCESS_NETWORK_STATE') {
    Write-Error 'WebRTC NetworkMonitor requires ACCESS_NETWORK_STATE'; exit 1
}
$designSystemBuild = Get-Content 'core/designsystem/build.gradle.kts' -Raw
if ($designSystemBuild -notmatch 'org\.jetbrains\.kotlin\.plugin\.compose') {
    Write-Error 'core:designsystem must apply the Compose compiler plugin to keep its composable ABI compatible'; exit 1
}
Write-Output 'dependency boundary check passed: live match is analysis-free; game/API/review and SDK boundaries are intact'

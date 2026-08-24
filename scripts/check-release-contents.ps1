param(
    [string]$ArtifactPath = "app/build/outputs/apk/release/app-release-unsigned.apk",
    [string]$ExpectedPackageId = "com.shinpstudio.chanriva",
    [string]$ExpectedSupabaseProjectRef = "zgzllmaoyymoeiqtybck",
    [string]$ExpectedSupabaseUrl = "https://zgzllmaoyymoeiqtybck.supabase.co"
)

$ErrorActionPreference = 'Stop'
$artifact = (Resolve-Path $ArtifactPath).Path
$extension = [IO.Path]::GetExtension($artifact).ToLowerInvariant()
if ($extension -notin @('.apk', '.aab')) { throw "Expected an APK or AAB: $artifact" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($artifact)
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ("othello-release-scan-" + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null
try {
    $entries = @($archive.Entries)

    function Get-ArchiveEntryText([string]$suffix) {
        $entry = $entries | Where-Object { $_.FullName -match "(^|/)$([regex]::Escape($suffix))$" } | Select-Object -First 1
        if (-not $entry) { throw "Release metadata entry is missing: $suffix" }
        $memory = [IO.MemoryStream]::new()
        try {
            $stream = $entry.Open()
            try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
            return [Text.Encoding]::UTF8.GetString($memory.ToArray())
        } finally {
            $memory.Dispose()
        }
    }

    function Get-MetadataValue([string]$content, [string]$name, [string]$entryName) {
        $line = @($content -split "`r?`n" | Where-Object { $_ -match "^$([regex]::Escape($name))=(.*)$" })
        if ($line.Count -ne 1) { throw "Release metadata '$entryName' must contain exactly one $name value" }
        return ([regex]::Match($line[0], "^$([regex]::Escape($name))=(.*)$")).Groups[2].Value
    }

    $releaseMetadataEntryName = 'assets/chanriva-release-metadata.properties'
    $releaseMetadata = Get-ArchiveEntryText $releaseMetadataEntryName
    if ((Get-MetadataValue $releaseMetadata 'application_id' $releaseMetadataEntryName) -ne $ExpectedPackageId) {
        throw "Release artifact application ID does not match the expected package."
    }
    if ((Get-MetadataValue $releaseMetadata 'variant' $releaseMetadataEntryName) -ne 'release') {
        throw 'Release artifact metadata does not identify a release variant.'
    }

    $supabaseMetadataEntryName = $releaseMetadataEntryName
    $supabaseMetadata = $releaseMetadata
    if ((Get-MetadataValue $supabaseMetadata 'supabase_project_ref' $supabaseMetadataEntryName) -ne $ExpectedSupabaseProjectRef) {
        throw 'Release artifact targets an unexpected Supabase project.'
    }
    if ((Get-MetadataValue $supabaseMetadata 'supabase_environment' $supabaseMetadataEntryName) -ne 'production') {
        throw 'Release artifact is not marked for the production Supabase environment.'
    }
    $artifactSupabaseUrl = (Get-MetadataValue $supabaseMetadata 'supabase_url' $supabaseMetadataEntryName).TrimEnd('/')
    if ($artifactSupabaseUrl -ne $ExpectedSupabaseUrl.TrimEnd('/')) {
        throw 'Release artifact Supabase URL does not match the expected production project.'
    }
    if ($supabaseMetadata -match '(?im)^\s*(?:anon_key|service_role_key|anon_key_configured)=') {
        throw 'Release metadata must not contain Supabase credentials.'
    }

    $manifestEntry = $entries | Where-Object { $_.FullName -in @('base/manifest/AndroidManifest.xml', 'AndroidManifest.xml') } | Select-Object -First 1
    if (-not $manifestEntry) { throw 'Release manifest is missing.' }
    $manifestMemory = [IO.MemoryStream]::new()
    try {
        $manifestStream = $manifestEntry.Open()
        try { $manifestStream.CopyTo($manifestMemory) } finally { $manifestStream.Dispose() }
        $manifestText = [Text.Encoding]::UTF8.GetString($manifestMemory.ToArray())
        if ($manifestText.IndexOf($ExpectedPackageId, [StringComparison]::Ordinal) -lt 0) {
            throw 'Release manifest package ID does not match the expected application ID.'
        }
    } finally {
        $manifestMemory.Dispose()
    }

    $forbiddenEntry = $entries | Where-Object {
        $_.FullName -match '(?i)(^|/)(eval(?:uation)?\.dat|[^/]*opening[^/]*book[^/]*|[^/]*\.book|synthetic[^/]*)$'
    }
    if ($forbiddenEntry) {
        throw "Release contains evaluation/book/test data: $($forbiddenEntry.FullName -join ', ')"
    }

    $nativeEntries = @($entries | Where-Object { $_.FullName -match '^(?:base/)?lib/([^/]+)/[^/]+\.so$' })
    $abis = @($nativeEntries | ForEach-Object {
        [regex]::Match($_.FullName, '^(?:base/)?lib/([^/]+)/').Groups[1].Value
    } | Sort-Object -Unique)
    $expectedAbis = @('arm64-v8a', 'x86_64')
    if (@($abis | Where-Object { $_ -notin $expectedAbis }).Count -ne 0 -or
        @($expectedAbis | Where-Object { $_ -notin $abis }).Count -ne 0) {
        throw "Release ABI set must be exactly arm64-v8a,x86_64; found: $($abis -join ',')"
    }
    foreach ($abi in $expectedAbis) {
        if (-not ($nativeEntries.FullName -contains "lib/$abi/libedax_jni.so") -and
            -not ($nativeEntries.FullName -contains "base/lib/$abi/libedax_jni.so")) {
            throw "Edax JNI is missing for $abi"
        }
    }

    $forbiddenStrings = @(
        'othello.e2e.',
        'synthetic-eval',
        'synthetic-empty-book',
        'HeuristicTestAnalysisEngine',
        'SUPABASE_SERVICE_ROLE_KEY',
        'player-a@example.test',
        'player-b@example.test',
        'http://10.0.2.2',
        'http://127.0.0.1',
        'packet injection'
    )
    foreach ($entry in $entries | Where-Object {
        $_.FullName -match '(?i)(\.dex$|resources\.arsc$|AndroidManifest\.xml$|^assets/|^base/assets/)'
    }) {
        $memory = [IO.MemoryStream]::new()
        try {
            $stream = $entry.Open()
            try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
            $text = [Text.Encoding]::ASCII.GetString($memory.ToArray())
            foreach ($forbidden in $forbiddenStrings) {
                if ($text.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    throw "Release contains forbidden debug/test marker '$forbidden' in $($entry.FullName)"
                }
            }
        } finally {
            $memory.Dispose()
        }
    }

    $sdk = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $hostTag = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
    $readelfName = if ($hostTag -eq 'windows-x86_64') { 'llvm-readelf.exe' } else { 'llvm-readelf' }
    $readelf = Join-Path $sdk "ndk/27.3.13750724/toolchains/llvm/prebuilt/$hostTag/bin/$readelfName"
    if (-not (Test-Path $readelf)) { throw "Pinned NDK readelf not found: $readelf" }

    foreach ($entry in $nativeEntries) {
        $destination = Join-Path $temporaryDirectory ($entry.FullName -replace '/', [IO.Path]::DirectorySeparatorChar)
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
        $input = $entry.Open()
        $output = [IO.File]::Create($destination)
        try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
        $programHeaders = & $readelf -lW $destination
        if ($LASTEXITCODE -ne 0) { throw "llvm-readelf failed for $($entry.FullName)" }
        foreach ($line in $programHeaders | Where-Object { $_ -match '^\s*LOAD\s' }) {
            $alignmentToken = (($line -split '\s+') | Where-Object { $_ })[-1]
            $alignment = [Convert]::ToInt64($alignmentToken.Substring(2), 16)
            if ($alignment -lt 16384) { throw "$($entry.FullName) has ELF LOAD alignment $alignmentToken, below 0x4000" }
        }
    }
} finally {
    $archive.Dispose()
    if (Test-Path -LiteralPath $temporaryDirectory) { Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force }
}

if ($extension -eq '.apk') {
    $buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    $zipalignName = if ($hostTag -eq 'windows-x86_64') { 'zipalign.exe' } else { 'zipalign' }
    $aaptName = if ($hostTag -eq 'windows-x86_64') { 'aapt.exe' } else { 'aapt' }
    $zipalign = Join-Path $buildTools.FullName $zipalignName
    $aapt = Join-Path $buildTools.FullName $aaptName
    & $zipalign -c -P 16 -v 4 $artifact | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'APK is not 16 KB ZIP aligned' }
    $permissions = & $aapt dump permissions $artifact
    if ($LASTEXITCODE -ne 0) { throw 'aapt permission inspection failed' }
    foreach ($permission in @(
        'READ_EXTERNAL_STORAGE',
        'WRITE_EXTERNAL_STORAGE',
        'MANAGE_EXTERNAL_STORAGE',
        'READ_MEDIA_IMAGES',
        'READ_MEDIA_VIDEO',
        'READ_MEDIA_AUDIO',
        'ACCESS_MEDIA_LOCATION',
        'QUERY_ALL_PACKAGES',
        'REQUEST_INSTALL_PACKAGES'
    )) {
        if (($permissions -join "`n") -match [regex]::Escape($permission)) {
            throw "Release requests forbidden broad or unrelated permission: $permission"
        }
    }
    $badging = & $aapt dump badging $artifact
    if ($LASTEXITCODE -ne 0) { throw 'aapt badging inspection failed' }
    if (($badging -join "`n") -match 'application-debuggable') { throw 'Release APK is debuggable' }
    $manifest = & $aapt dump xmltree $artifact AndroidManifest.xml
    if ($LASTEXITCODE -ne 0) { throw 'aapt manifest inspection failed' }
    if ($manifest | Where-Object { $_ -match 'android:usesCleartextTraffic.*(?:0xffffffff|0x1)\s*$' }) {
        throw 'Release APK enables cleartext traffic'
    }
}

Write-Output "release contents passed: no bundled eval/book/test data, exact ABI set, 16 KB native alignment, no debug hooks, and least-privilege manifest"

# Adapted for Wrist Lynk from zjs215/geely-band-control (Apache-2.0). See NOTICE.
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'prepare.ps1')

Push-Location (Join-Path $project 'android')
try {
    & .\gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :watch:assembleRelease :watch:testDebugUnitTest :watch:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build or tests failed.' }
} finally {
    Pop-Location
}

Push-Location (Join-Path $project 'band')
try {
    & npm.cmd ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Vela dependency installation failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Vela build failed.' }
    & node --test test/garage.test.cjs
    if ($LASTEXITCODE -ne 0) { throw 'Vela protocol or control tests failed.' }
} finally {
    Pop-Location
}

$artifacts = Join-Path $project 'artifacts'
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$manifest = Get-Content -LiteralPath (Join-Path $project 'band\src\manifest.json') -Raw | ConvertFrom-Json

function Export-ReleaseApk([string]$Module, [string]$Name) {
    $directory = Join-Path $project "android\$Module\build\outputs\apk\release"
    $metadata = Get-Content -LiteralPath (Join-Path $directory 'output-metadata.json') -Raw | ConvertFrom-Json
    $outputs = @($metadata.elements)
    if ($outputs.Count -ne 1 -or $outputs[0].versionName -notmatch '^\d+\.\d+\.\d+$') {
        throw "Expected one versioned release APK for $Module."
    }
    $versioned = "$Name-$($outputs[0].versionName).apk"
    $apk = Join-Path $directory $outputs[0].outputFile
    Copy-Item -LiteralPath $apk -Destination (Join-Path $artifacts $versioned) -Force
    # Installed companion apps use these stable names for online updates.
    Copy-Item -LiteralPath $apk -Destination (Join-Path $artifacts "$Name.apk") -Force
    return $versioned
}

$phoneApk = Export-ReleaseApk -Module 'app' -Name 'lynk-companion-phone'
$watchApk = Export-ReleaseApk -Module 'watch' -Name 'lynk-watch'
foreach ($profile in 'pill','standard') {
    & (Join-Path $PSScriptRoot 'build-band-pro.ps1') -Profile $profile -OutputDir $artifacts
}
Get-ChildItem -LiteralPath $artifacts -File | Select-Object Name,Length
$checksums = $phoneApk,$watchApk,'lynk-companion-phone.apk','lynk-watch.apk',"lynk-band-pill-$($manifest.versionName).rpk","lynk-band-standard-$($manifest.versionName).rpk" | ForEach-Object {
    '{0}  {1}' -f (Get-FileHash -LiteralPath (Join-Path $artifacts $_) -Algorithm SHA256).Hash.ToLowerInvariant(), $_
}
Set-Content -LiteralPath (Join-Path $artifacts 'SHA256SUMS.txt') -Value $checksums -Encoding Ascii

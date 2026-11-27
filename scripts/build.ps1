param([switch]$IncludeVelaIconVariants)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'prepare.ps1')

Push-Location (Join-Path $project 'android')
try {
    & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :watch:assembleDebug :watch:testDebugUnitTest :watch:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build or tests failed.' }
} finally {
    Pop-Location
}

$bandDir = Join-Path $project 'band'
Push-Location $bandDir
try {
    & npm.cmd ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Vela dependency installation failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Vela build failed.' }
    & npm.cmd run build:lite
    if ($LASTEXITCODE -ne 0) { throw 'Vela lite build failed.' }
    & node --test test/garage.test.cjs test/garage-lite.test.cjs test/lynkco-regression.test.cjs
    if ($LASTEXITCODE -ne 0) { throw 'Vela protocol or control tests failed.' }
} finally {
    Pop-Location
}

$manifest = Get-Content -LiteralPath (Join-Path $project 'band\src\manifest.json') -Raw | ConvertFrom-Json
$artifacts = Join-Path $project ('artifacts\v' + $manifest.versionName)
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$rpk = '{0}.debug.{1}.rpk' -f $manifest.package,$manifest.versionName
$liteRpk = '{0}.lite.{1}.rpk' -f $manifest.package,$manifest.versionName
$packages = [ordered]@{
    'pocket-car-control.apk' = Join-Path $project 'android\app\build\outputs\apk\debug\app-debug.apk'
    'wrist-car-control-watch.apk' = Join-Path $project 'android\watch\build\outputs\apk\debug\watch-debug.apk'
    'wrist-car-control.rpk' = Join-Path $bandDir "dist\$rpk"
    'wrist-car-control-lite.rpk' = Join-Path $bandDir "dist\$liteRpk"
}
$checksums = foreach ($name in $packages.Keys) {
    $target = Join-Path $artifacts $name
    Copy-Item -LiteralPath $packages[$name] -Destination $target -Force
    '{0}  {1}' -f (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant(), $name
}
Set-Content -LiteralPath (Join-Path $artifacts 'SHA256SUMS.txt') -Value $checksums -Encoding Ascii
if ($IncludeVelaIconVariants) {
    & node (Join-Path $bandDir 'scripts\build-icon-variants.cjs') (Join-Path $artifacts 'icons')
    if ($LASTEXITCODE -ne 0) { throw 'Vela icon variant builds failed.' }
}
Get-ChildItem -LiteralPath $artifacts -File | Select-Object Name,Length

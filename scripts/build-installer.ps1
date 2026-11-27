param(
    [string]$PackageDirectory,
    [string]$GradleUserHome
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$installer = Join-Path $project 'android\installer'
$manifest = Get-Content -LiteralPath (Join-Path $project 'band\src\manifest.json') -Raw | ConvertFrom-Json
$artifacts = Join-Path $project ('artifacts\v' + $manifest.versionName)
if (!$PackageDirectory) { $PackageDirectory = $artifacts }
$source = (Resolve-Path -LiteralPath $PackageDirectory).Path
$gradleArguments = @('-p', $installer, "-PwearablePackageDir=$source",
    ':testDebugUnitTest', ':lintDebug', ':assembleDebug')
if ($GradleUserHome) {
    $gradleArguments = @('--gradle-user-home', $GradleUserHome) + $gradleArguments
}

& (Join-Path $project 'android\gradlew.bat') @gradleArguments
if ($LASTEXITCODE -ne 0) { throw 'Wearable installer build or checks failed.' }

$apkDirectory = Join-Path $installer 'build\outputs\apk\debug'
$metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
$apkName = $metadata.elements[0].outputFile
if (!$apkName -or [IO.Path]::GetFileName($apkName) -ne $apkName) { throw 'Invalid installer APK output name.' }
New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
$outputName = 'wrist-car-control-installer.apk'
$output = Join-Path $artifacts $outputName
Copy-Item -LiteralPath (Join-Path $apkDirectory $apkName) -Destination $output -Force
$outputHash = Get-FileHash -LiteralPath $output -Algorithm SHA256
$checksumFile = Join-Path $artifacts 'SHA256SUMS.txt'
if (Test-Path -LiteralPath $checksumFile -PathType Leaf) {
    $checksums = @(Get-Content -LiteralPath $checksumFile | Where-Object {
        ($_ -split '\s+', 2)[1] -ne $outputName
    })
    $checksums += ('{0}  {1}' -f $outputHash.Hash.ToLowerInvariant(), $outputName)
    Set-Content -LiteralPath $checksumFile -Value $checksums -Encoding Ascii
}
$outputHash

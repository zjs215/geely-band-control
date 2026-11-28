param(
    [ValidateSet('standard','pill')][string]$Profile = 'standard',
    [string]$BandRoot = (Join-Path $PSScriptRoot '..\band'),
    [string]$OutputDir = (Join-Path $PSScriptRoot '..\artifacts')
)
$ErrorActionPreference = 'Stop'
$band = (Resolve-Path -LiteralPath $BandRoot).Path
$output = (New-Item -ItemType Directory -Force -Path $OutputDir).FullName
$stage = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ('lynk-pro-' + [guid]::NewGuid()))
try {
    foreach ($name in 'src','package.json','sign') {
        Copy-Item -LiteralPath (Join-Path $band $name) -Destination $stage.FullName -Recurse
    }
    # Pin the selected shape in the compiled program even if the firmware's getInfo is missing or stale.
    [IO.File]::WriteAllText((Join-Path $stage 'src\common\screen-profile.js'), "export const screenProfile = '$Profile'`n")
    $manifest = Join-Path $stage 'src\manifest.json'
    $config = [IO.File]::ReadAllText($manifest) | ConvertFrom-Json
    if ($Profile -eq 'standard') {
        $icon = Join-Path $PSScriptRoot '..\band\profiles\10-pro\launcher-pro.png'
        Copy-Item -LiteralPath $icon -Destination (Join-Path $stage 'src\common\launcher-pro.png')
        $config.icon = '/common/launcher-pro.png'
    } else {
        foreach ($name in 'sky\day.png','sky\night.png','default-vehicle.png') {
            $asset = Join-Path $stage "src\common\$name"
            Remove-Item -LiteralPath $asset
        }
        $motion = Join-Path $stage 'src\common\motion'
        Get-ChildItem -LiteralPath $motion -File | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
        Remove-Item -LiteralPath $motion
    }
    [IO.File]::WriteAllText($manifest, ($config | ConvertTo-Json -Depth 10))
    $modules = New-Item -ItemType Junction -Path (Join-Path $stage 'node_modules') -Target (Join-Path $band 'node_modules')
    Push-Location $stage.FullName
    try {
        & .\node_modules\.bin\aiot.cmd release
        if ($LASTEXITCODE -ne 0) { throw "$Profile release failed" }
        & node (Join-Path $band 'test\release-profile.cjs') (Join-Path $stage 'build') $Profile
        if ($LASTEXITCODE -ne 0) { throw "$Profile screen capability checks failed" }
    } finally { Pop-Location }
    $rpk = '{0}.release.{1}.rpk' -f $config.package,$config.versionName
    Copy-Item -LiteralPath (Join-Path $stage "dist\$rpk") -Destination (Join-Path $output "lynk-band-$Profile-$($config.versionName).rpk") -Force
} finally {
    if ($modules -and (Test-Path -LiteralPath $modules.FullName)) { [IO.Directory]::Delete($modules.FullName) }
    if ($stage.Parent.FullName -eq ([IO.Path]::GetTempPath()).TrimEnd('\') -and $stage.Name -like 'lynk-pro-*') {
        Remove-Item -LiteralPath $stage.FullName -Recurse -Force
    }
}

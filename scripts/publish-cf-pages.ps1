param(
    [string]$BaseUrl = 'https://car.otrong.com',
    [string]$OutputDir
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$manifest = Get-Content -LiteralPath (Join-Path $project 'band\src\manifest.json') -Raw | ConvertFrom-Json
$version = $manifest.versionName
$tag = 'v' + $version
$artifacts = Join-Path $project ('artifacts\' + $tag)
if (!(Test-Path -LiteralPath $artifacts -PathType Container)) {
    throw "Artifacts directory not found: $artifacts"
}

if (!$OutputDir) {
    $OutputDir = Join-Path $project 'artifacts\cf-pages'
}
if (Test-Path -LiteralPath $OutputDir) {
    Remove-Item -LiteralPath $OutputDir -Recurse -Force
}

$versionDir = Join-Path $OutputDir $tag
New-Item -ItemType Directory -Path $versionDir -Force | Out-Null
Copy-Item -Path (Join-Path $artifacts '*') -Destination $versionDir -Force

$base = $BaseUrl.TrimEnd('/')
$assets = @()
$checksumFile = Join-Path $artifacts 'SHA256SUMS.txt'
if (!(Test-Path -LiteralPath $checksumFile -PathType Leaf)) {
    throw "SHA256SUMS.txt not found in $artifacts"
}
foreach ($line in Get-Content -LiteralPath $checksumFile) {
    if ($line -match '^([a-fA-F0-9]{64})\s+(\S+)$') {
        $digest = $Matches[1].ToLowerInvariant()
        $name = $Matches[2]
        $file = Get-Item -LiteralPath (Join-Path $artifacts $name)
        $assets += [ordered]@{
            name = $name
            size = [int64]$file.Length
            state = 'uploaded'
            digest = 'sha256:' + $digest
            browser_download_url = "$base/$tag/$name"
        }
    }
}

$notes = ''
$changelogPath = Join-Path $project 'CHANGELOG.md'
if (Test-Path -LiteralPath $changelogPath -PathType Leaf) {
    $changelog = Get-Content -LiteralPath $changelogPath -Raw
    $pattern = "(?ms)^## $([regex]::Escape($version))\r?\n\r?\n(.*?)(?=\r?\n## |\z)"
    if ($changelog -match $pattern) {
        $notes = $Matches[1].Trim()
    }
}

$release = [ordered]@{
    tag_name = $tag
    name = $tag
    draft = $false
    prerelease = $false
    body = $notes
    assets = $assets
}
$latestJson = $release | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $OutputDir 'latest.json'), $latestJson, (New-Object System.Text.UTF8Encoding($false)))

$indexHtml = @"
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>腕上车控 · 分发镜像 ($tag)</title>
  <style>
    body { font-family: system-ui, -apple-system, sans-serif; background: #101318; color: #e8edf2; max-width: 640px; margin: 40px auto; padding: 0 20px; line-height: 1.6; }
    a { color: #75dcc2; text-decoration: none; }
    a:hover { text-decoration: underline; }
    .card { background: #181d24; border: 1px solid #27303c; border-radius: 14px; padding: 18px 20px; margin-top: 16px; }
    li { margin: 8px 0; }
  </style>
</head>
<body>
  <h1>腕上车控 · $tag</h1>
  <p>Cloudflare Pages 国内加速分发节点。</p>
  <div class="card">
    <ul>
      <li><a href="./$tag/wrist-car-control-installer.apk">车控安装助手 (wrist-car-control-installer.apk)</a></li>
      <li><a href="./$tag/pocket-car-control.apk">口袋车控手机端 (pocket-car-control.apk)</a></li>
      <li><a href="./$tag/wrist-car-control-watch.apk">小米 Watch 5 APK (wrist-car-control-watch.apk)</a></li>
      <li><a href="./$tag/wrist-car-control.rpk">Vela 完整版 RPK (wrist-car-control.rpk)</a></li>
      <li><a href="./$tag/wrist-car-control-lite.rpk">Vela Lite RPK (wrist-car-control-lite.rpk)</a></li>
      <li><a href="./latest.json">latest.json</a></li>
    </ul>
  </div>
</body>
</html>
"@
[IO.File]::WriteAllText((Join-Path $OutputDir 'index.html'), $indexHtml, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "Prepared Cloudflare Pages bundle at $OutputDir ($tag, $($assets.Count) assets)"

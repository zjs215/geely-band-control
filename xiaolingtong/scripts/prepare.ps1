# Adapted for Wrist Lynk from zjs215/geely-band-control (Apache-2.0). See NOTICE.
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$local = Join-Path $project '.local'
$sign = Join-Path $project 'band\sign\debug'
New-Item -ItemType Directory -Force -Path $local,$sign | Out-Null

function Invoke-Checked($program, $arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "$program exited with code $LASTEXITCODE" }
}

function Get-Checksum($path) {
    $file = [System.IO.File]::OpenRead($path)
    $digest = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($digest.ComputeHash($file)).Replace('-', '')
    } finally {
        $file.Dispose()
        $digest.Dispose()
    }
}

$sdkHash = '9C40FD1C5409BB948523D503AF71E2978AE522C35636AFE7D64F474C0F6BC195'
$sdkFile = Join-Path $local 'xms-wearable-lib_1.4_release.aar'
if (!(Test-Path -LiteralPath $sdkFile)) {
    $archive = Join-Path $local 'interconnect_demo.zip'
    $archiveEntry = 'interconnect_dev_test_demo/libs/xms-wearable-lib_1.4_release.aar'
    $sdkUrl = 'https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip'
    Invoke-Checked 'curl.exe' @('--ssl-revoke-best-effort','--fail','--silent','--show-error','--location','--max-time','60','--output',$archive,$sdkUrl)
    Invoke-Checked 'tar.exe' @('-xf',$archive,'-C',$local,$archiveEntry)
    $extracted = Join-Path $local $archiveEntry
    if ((Get-Checksum $extracted) -ne $sdkHash) { throw 'Xiaomi SDK checksum mismatch.' }
    Move-Item -LiteralPath $extracted -Destination $sdkFile
}
if ((Get-Checksum $sdkFile) -ne $sdkHash) { throw 'Local Xiaomi SDK checksum mismatch.' }

# Keep the certificate already trusted by installed APKs and the Vela companion.
$identity = Join-Path $local 'mario'
$certificate = Join-Path $identity 'certificate.pem'
$privateKey = Join-Path $identity 'private.pem'
$store = Join-Path $identity 'mario.p12'
$properties = Join-Path $identity 'signing.properties'
if (!(Test-Path -LiteralPath $identity)) {
    $opensslCommand = Get-Command openssl -ErrorAction SilentlyContinue
    $openssl = if ($opensslCommand) { $opensslCommand.Source } else { 'C:\Program Files\Git\usr\bin\openssl.exe' }
    if (!(Test-Path -LiteralPath $openssl)) { throw 'OpenSSL is required to create the signing store.' }
    if (!$env:LYNK_SIGNING_PASSWORD) { throw 'Set LYNK_SIGNING_PASSWORD for the first build, or restore .local/mario.' }
    $stage = Join-Path $local ('mario-stage-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    $stageCert = Join-Path $stage 'certificate.pem'
    $stageKey = Join-Path $stage 'private.pem'
    $oldCert = Join-Path $local 'certificate.pem'
    $oldKey = Join-Path $local 'private.pem'
    if ((Test-Path -LiteralPath $oldCert) -and (Test-Path -LiteralPath $oldKey)) {
        Copy-Item -LiteralPath $oldCert,$oldKey -Destination $stage
    } elseif (@('certificate.pem','private.pem','companion.p12','signing.properties') | Where-Object { Test-Path -LiteralPath (Join-Path $local $_) }) {
        throw 'Restore the original PEM files before migrating the existing signing identity.'
    } else {
        Invoke-Checked $openssl @('req','-newkey','rsa:3072','-nodes','-x509','-sha256','-days','10000','-subj','/CN=mario','-keyout',$stageKey,'-out',$stageCert)
    }
    Invoke-Checked $openssl @('pkcs12','-export','-name','mario','-inkey',$stageKey,'-in',$stageCert,'-out',(Join-Path $stage 'mario.p12'),'-passout','env:LYNK_SIGNING_PASSWORD')
    Set-Content -LiteralPath (Join-Path $stage 'signing.properties') -Value ('password=' + $env:LYNK_SIGNING_PASSWORD) -Encoding Ascii
    [IO.Directory]::Move($stage,$identity)
}
foreach ($file in @($certificate,$privateKey,$store,$properties)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Restore the missing signing file: $file" }
}
$releaseSign = Join-Path $project 'band\sign\release'
New-Item -ItemType Directory -Force -Path $releaseSign | Out-Null
foreach ($path in @($sign,$releaseSign)) { Copy-Item -LiteralPath $certificate,$privateKey -Destination $path -Force }

<#
.SYNOPSIS
  Builds, signs and checks the Windows direct-download packages (Control and Drive) and leaves them in
  build\windows-release\ for scripts/windows-direct-publish.sh (docs/how-to/windows-release.md §2).

.DESCRIPTION
  One Gradle invocation for both apps, because both peer gates are compiled from the same
  tenshu.windowsPublisherIds in that build (gradle/desktop-msix.gradle.kts, Direct mode): Control must
  admit Drive and Drive must admit Control, so they must agree on exactly one publisher.

  Refuses (before anything is copied out): a dirty tree, a certificate that is not in
  Cert:\CurrentUser\My with that subject and a private key, an expired one, a package that is not
  signed by THAT certificate or carries no timestamp, and a manifest whose Identity is not the one
  the build was asked for.

  Output, build\windows-release\:
    Tenshu<App>-<version>.msix   one per app, signed + timestamped
    <Cert>.cer                   the public certificate testers import (from -Subject: CN=Tenshu Beta -> TenshuBeta.cer)
    release.json                 what was built: version, publisher, publisher id, thumbprint, commit
    SHA256SUMS                   every file above but release.json

  ⚠ Publishing is NOT done here: copy the folder to the Mac's checkout (same path) and run
  scripts/windows-direct-publish.sh there — the release bucket's key lives in that Mac's Keychain only.

.PARAMETER Thumbprint
  The release certificate (scripts/windows/msix-release-cert.ps1 prints it).

.PARAMETER Publisher
  Its subject = the MSIX Publisher. Default 'CN=Tenshu Beta'.

.PARAMETER AllowDirty
  Build from uncommitted changes. For trying the pipeline only: windows-direct-publish.sh refuses the result.
#>
param(
    [Parameter(Mandatory = $true)] [string] $Thumbprint,
    [string] $Publisher = 'CN=Tenshu Beta',
    [switch] $AllowDirty
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = (git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
Set-Location $root

# app key -> Gradle module, launcher/package base name, MSIX Identity/@Name (= the module's tenshu.msix.identityName)
$apps = [ordered]@{
    control = @{ module = ':control-desktop'; exe = 'TenshuControl'; identity = 'Tenshu.Control' }
    drive   = @{ module = ':drive-desktop';   exe = 'TenshuDrive';   identity = 'Tenshu.Drive' }
}

# ── The publisher id: the half of the package family name after '_', what the peer gates pin ──
# Same derivation as MsixPublisherId (:windows-helpers) and the Gradle script, self-checked the same way.
function Get-PublisherId([string] $value) {
    $alphabet = '0123456789abcdefghjkmnpqrstvwxyz'
    $digest = [Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::Unicode.GetBytes($value))
    [UInt64] $bits = 0
    for ($i = 0; $i -lt 8; $i++) { $bits = ($bits -shl 8) -bor [UInt64] $digest[$i] }
    $id = ''
    for ($g = 0; $g -lt 13; $g++) {
        $shift = 59 - 5 * $g
        $v = if ($shift -ge 0) { ($bits -shr $shift) -band [UInt64] 0x1F } else { ($bits -shl (-$shift)) -band [UInt64] 0x1F }
        $id += $alphabet[[int] $v]
    }
    $id
}
if ((Get-PublisherId 'CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US') -ne '8wekyb3d8bbwe') {
    throw 'publisher-id derivation is broken: it no longer reproduces Microsoft''s known id'
}

# ── Preconditions ──
$dirty = git status --porcelain
if ($dirty -and -not $AllowDirty) { throw "The tree is dirty; commit first (or -AllowDirty to try the pipeline - never published):`n$dirty" }
$commit = (git rev-parse HEAD).Trim()

$Thumbprint = ($Thumbprint -replace '\s', '').ToUpperInvariant()
$cert = Get-Item -ErrorAction SilentlyContinue "Cert:\CurrentUser\My\$Thumbprint"
if (-not $cert) { throw "No certificate $Thumbprint in Cert:\CurrentUser\My (scripts/windows/msix-release-cert.ps1)." }
if ($cert.Subject -ne $Publisher) { throw "Certificate $Thumbprint is '$($cert.Subject)', not '$Publisher'. The MSIX Publisher must equal the signer's subject." }
if (-not $cert.HasPrivateKey) { throw "Certificate $Thumbprint has no private key on this PC." }
if ($cert.NotAfter -lt (Get-Date)) { throw "Certificate $Thumbprint expired $($cert.NotAfter.ToString('yyyy-MM-dd'))." }
$publisherId = Get-PublisherId $Publisher
Write-Host "Publisher $Publisher -> publisher id $publisherId (compiled into both peer gates)"

# ── Build both apps in one invocation ──
# From empty: packageMsix is UP-TO-DATE on an unchanged re-run and would not rewrite the .msix, and an older
# version's file must not be mistaken for this run's.
foreach ($a in $apps.Values) {
    $direct = Join-Path $root ("$($a.module.TrimStart(':'))\build\msix\direct")
    if (Test-Path $direct) { Remove-Item -Recurse -Force $direct }
}
$tasks = $apps.Values | ForEach-Object { "$($_.module):packageMsix" }
# Strings in an array, so PowerShell never splits them at a dot (the trap of an unquoted -Ptenshu.msix.direct=true
# typed at the prompt), and quotes the one with a space itself.
$gradleArgs = @($tasks) + @(
    '-Ptenshu.msix.direct=true',
    "-Ptenshu.msix.publisher=$Publisher",
    "-Ptenshu.msix.certThumbprint=$Thumbprint",
    "-Ptenshu.windowsPublisherIds=$publisherId"
)
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE); nothing was copied out." }

# ── Check every package ──
$out = Join-Path $root 'build\windows-release'
$found = @{}
$version = $null
foreach ($key in $apps.Keys) {
    $a = $apps[$key]
    $dir = Join-Path $root ("$($a.module.TrimStart(':'))\build\msix\direct")
    $files = @(Get-ChildItem -Path $dir -Filter "$($a.exe)_*_x64.msix" -File -ErrorAction SilentlyContinue)
    if ($files.Count -ne 1) { throw "Expected one built $($a.exe)_<version>_x64.msix in $dir, found $($files.Count)." }
    $msix = $files[0]
    $packageVersion = ($msix.Name -replace "^$($a.exe)_", '' -replace '_x64\.msix$', '')
    if ($version -and $version -ne $packageVersion) { throw "Control and Drive disagree on the version ($version vs $packageVersion): HR #9 lockstep." }
    $version = $packageVersion

    $sig = Get-AuthenticodeSignature -FilePath $msix.FullName
    if (-not $sig.SignerCertificate -or $sig.SignerCertificate.Thumbprint -ne $Thumbprint) {
        throw "$($msix.Name) is not signed by $Thumbprint (signer: $($sig.SignerCertificate.Subject))."
    }
    # UnknownError is expected (a self-signed root); a broken hash is not.
    if ($sig.Status -notin 'Valid', 'UnknownError') { throw "$($msix.Name): signature status $($sig.Status): $($sig.StatusMessage)" }
    if (-not $sig.TimeStamperCertificate) { throw "$($msix.Name) has no timestamp: its signature would die with the certificate." }

    $zip = [IO.Compression.ZipFile]::OpenRead($msix.FullName)
    try {
        $entry = $zip.GetEntry('AppxManifest.xml')
        $reader = New-Object IO.StreamReader($entry.Open())
        [xml] $manifest = $reader.ReadToEnd()
        $reader.Dispose()
    } finally { $zip.Dispose() }
    $identity = $manifest.Package.Identity
    if ($identity.Name -ne $a.identity) { throw "$($msix.Name): Identity/@Name is '$($identity.Name)', expected '$($a.identity)'." }
    if ($identity.Publisher -ne $Publisher) { throw "$($msix.Name): Identity/@Publisher is '$($identity.Publisher)', expected '$Publisher'." }
    if ($identity.Version -ne $version) { throw "$($msix.Name): Identity/@Version is '$($identity.Version)', the file name says $version." }
    if ($identity.ProcessorArchitecture -ne 'x64') { throw "$($msix.Name): not x64." }
    $found[$key] = $msix
    Write-Host "  ok  $($msix.Name)  signed by $Thumbprint, timestamped by $($sig.TimeStamperCertificate.Subject)"
}

# ── Copy out ──
$marketing = $version -replace '\.0$', ''
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
$names = @()
foreach ($key in $apps.Keys) {
    $name = "$($apps[$key].exe)-$marketing.msix"
    Copy-Item $found[$key].FullName (Join-Path $out $name)
    $names += $name
}
$cerName = (($Publisher -replace '^CN=', '') -replace '[^A-Za-z0-9]', '') + '.cer'
Export-Certificate -Cert $cert -FilePath (Join-Path $out $cerName) | Out-Null
$names += $cerName

$sums = foreach ($n in $names) { "$((Get-FileHash -Algorithm SHA256 (Join-Path $out $n)).Hash.ToLowerInvariant())  $n" }
# LF, no BOM: read on the Mac by scripts/lib/windows_publish.py.
[IO.File]::WriteAllText((Join-Path $out 'SHA256SUMS'), (($sums -join "`n") + "`n"))
$info = [ordered]@{
    schema         = 1
    version        = $marketing
    packageVersion = $version
    publisher      = $Publisher
    publisherId    = $publisherId
    thumbprint     = $Thumbprint
    certificate    = $cerName
    commit         = $commit
    dirty          = [bool] $dirty
    built          = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
}
[IO.File]::WriteAllText((Join-Path $out 'release.json'), (($info | ConvertTo-Json) -replace "`r`n", "`n") + "`n")

Write-Host ''
Write-Host "Built Tenshu $marketing for Windows in $out"
Write-Host '  Next, on the Mac: copy this folder to build/windows-release/ in the checkout at the same commit, then'
Write-Host '    scripts/windows-direct-publish.sh --dry-run'

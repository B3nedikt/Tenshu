<#
.SYNOPSIS
  Mints the throwaway code-signing certificate that local, side-loaded MSIX builds are signed with
  (docs/proposals/desktop-windows-port.md §5.2; gradle/desktop-msix.gradle.kts).

.DESCRIPTION
  - Creates a self-signed code-signing certificate, subject "CN=Tenshu Local Test" (the default MSIX
    Publisher `packageMsix` writes — the two must be identical), in Cert:\CurrentUser\My. No admin.
  - Exports it to %USERPROFILE%\.tenshu-dev\msix-test.pfx, where `packageMsix` finds it by default.
  - Trusts it for side-loading by importing the public certificate into Cert:\LocalMachine\TrustedPeople.
    That one step needs an ELEVATED shell; without elevation the script prints the command instead.

  ⚠ A LOCAL TEST certificate only. It is never a release signer: the Store re-signs (D1) and the side
  channel is signed by SignPath (D2). Remove it with -Remove when done.

.PARAMETER Remove
  Deletes the certificate from both stores and the exported files.
#>
param([switch] $Remove)

$ErrorActionPreference = 'Stop'
$subject = 'CN=Tenshu Local Test'
$dir = Join-Path $env:USERPROFILE '.tenshu-dev'
$pfx = Join-Path $dir 'msix-test.pfx'
$cer = Join-Path $dir 'msix-test.cer'
# Must match the default in gradle/desktop-msix.gradle.kts. A throwaway local cert; not a secret.
$password = ConvertTo-SecureString -String 'tenshu-local-test' -Force -AsPlainText
$elevated = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($Remove) {
    Get-ChildItem Cert:\CurrentUser\My | Where-Object Subject -eq $subject | Remove-Item
    if ($elevated) {
        Get-ChildItem Cert:\LocalMachine\TrustedPeople | Where-Object Subject -eq $subject | Remove-Item
    } else {
        Write-Host "Run elevated to also remove it from LocalMachine\TrustedPeople."
    }
    Remove-Item -ErrorAction SilentlyContinue $pfx, $cer
    Write-Host 'Removed.'
    return
}

New-Item -ItemType Directory -Force -Path $dir | Out-Null
$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object Subject -eq $subject | Select-Object -First 1
if (-not $cert) {
    $cert = New-SelfSignedCertificate -Type Custom -Subject $subject -KeyUsage DigitalSignature `
        -FriendlyName 'Tenshu local MSIX test' -CertStoreLocation Cert:\CurrentUser\My `
        -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}') -NotAfter (Get-Date).AddYears(1)
    Write-Host "Created $subject ($($cert.Thumbprint))."
}
Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $password | Out-Null
Export-Certificate -Cert $cert -FilePath $cer | Out-Null
Write-Host "Exported to $pfx"

if ($elevated) {
    Import-Certificate -FilePath $cer -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null
    Write-Host 'Trusted for side-loading (LocalMachine\TrustedPeople).'
} else {
    Write-Host ''
    Write-Host 'One step left, in an ELEVATED PowerShell:'
    Write-Host "  Import-Certificate -FilePath '$cer' -CertStoreLocation Cert:\LocalMachine\TrustedPeople"
}

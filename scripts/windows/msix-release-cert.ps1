<#
.SYNOPSIS
  Mints the certificate the tenshu.app Windows download is signed with, and keeps its private key
  NON-EXPORTABLE in this user's certificate store (docs/how-to/windows-release.md §1;
  desktop-windows-port.md §5.1 D6).

.DESCRIPTION
  ⚠⚠ This is a release key, not the throwaway one msix-test-cert.ps1 mints. Every tester who imports its
  public half trusts, for MSIX installs on that PC, whatever it signs (threat model A45).

  First run (no certificate with -Subject in Cert:\CurrentUser\My yet):
    1. Creates a self-signed RSA-3072 code-signing certificate, valid three years.
    2. Exports ONE password-protected backup to -BackupPfx. Put that file somewhere offline (a USB stick in a
       drawer, a password manager attachment) — never in the repo, never in a synced folder.
    3. Deletes the key from the store and re-imports it from the backup WITHOUT the exportable flag, then proves
       the store copy cannot be exported. From here on, signing (`signtool /sha1 <thumbprint>`) uses the store
       copy and no .pfx needs to exist on this machine.
    4. Writes the public certificate to -CerOut (published beside the packages by windows-release.ps1).
  Later runs find the certificate, check it, and print the same facts again. Nothing is re-created.

  -TrustHere also imports the public certificate into Cert:\LocalMachine\TrustedPeople, so this PC installs the
  packages it signs (needs an ELEVATED shell; testers do the same with the downloaded .cer).

  ⚠ Renewal: run again with the SAME -Subject after deleting the expired certificate. The publisher id — what
  the peer gate pins — is derived from the subject string alone, so a renewed certificate keeps it, and so does
  an MSIX upgrade. Testers must import the new .cer once.

.PARAMETER Subject
  The certificate subject = the MSIX Publisher. Default 'CN=Tenshu Beta'.

.PARAMETER BackupPfx
  Where the one exportable copy of the private key goes. Required on the first run; refused inside the repo.

.PARAMETER CerOut
  Where the public certificate goes. Default %USERPROFILE%\.tenshu-release\TenshuBeta.cer.

.PARAMETER TrustHere
  Trust it on this PC (LocalMachine\TrustedPeople; elevated).
#>
param(
    [string] $Subject = 'CN=Tenshu Beta',
    [string] $BackupPfx,
    [string] $CerOut = (Join-Path $env:USERPROFILE '.tenshu-release\TenshuBeta.cer'),
    [switch] $TrustHere
)

$ErrorActionPreference = 'Stop'
$codeSigning = '1.3.6.1.5.5.7.3.3'
if ($Subject -eq 'CN=Tenshu Local Test') { throw "That is msix-test-cert.ps1's throwaway subject; a release needs its own." }
if (-not $Subject.StartsWith('CN=')) { throw "-Subject must start with CN= (it becomes the MSIX Publisher)." }

function Find-ReleaseCert {
    Get-ChildItem Cert:\CurrentUser\My |
        Where-Object { $_.Subject -eq $Subject -and $_.HasPrivateKey -and ($_.EnhancedKeyUsageList.ObjectId -contains $codeSigning) } |
        Sort-Object NotAfter -Descending | Select-Object -First 1
}

function Test-Exportable($cert) {
    # The proof the key is non-exportable is that exporting it fails.
    $probe = Join-Path ([IO.Path]::GetTempPath()) ("tenshu-export-probe-" + [Guid]::NewGuid() + '.pfx')
    $pw = ConvertTo-SecureString -String ([Guid]::NewGuid().ToString()) -Force -AsPlainText
    try {
        Export-PfxCertificate -Cert $cert -FilePath $probe -Password $pw -ErrorAction Stop | Out-Null
        return $true
    } catch {
        return $false
    } finally {
        Remove-Item -ErrorAction SilentlyContinue $probe
    }
}

$cert = Find-ReleaseCert
if (-not $cert) {
    if (-not $BackupPfx) { throw "No '$Subject' certificate yet. Pass -BackupPfx <path outside the repo> to mint one." }
    $BackupPfx = [IO.Path]::GetFullPath($BackupPfx)
    $repo = (git -C $PSScriptRoot rev-parse --show-toplevel 2>$null)
    if ($repo -and $BackupPfx.StartsWith([IO.Path]::GetFullPath($repo), [StringComparison]::OrdinalIgnoreCase)) {
        throw "-BackupPfx $BackupPfx is inside the repository. The release key's backup never goes there."
    }
    if (Test-Path $BackupPfx) { throw "$BackupPfx exists already; refusing to overwrite a key backup." }

    $pw = Read-Host -AsSecureString 'Password for the key backup (store it with the backup)'
    $pw2 = Read-Host -AsSecureString 'Same password again'
    $plain = { param($s) [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)) }
    if ((& $plain $pw) -ne (& $plain $pw2)) { throw 'The passwords differ.' }
    if ((& $plain $pw).Length -lt 12) { throw 'Use at least 12 characters: this password is all that protects the backup.' }

    $minted = New-SelfSignedCertificate -Type Custom -Subject $Subject -KeyUsage DigitalSignature `
        -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -KeyExportPolicy Exportable `
        -FriendlyName 'Tenshu Windows release signing' -CertStoreLocation Cert:\CurrentUser\My `
        -TextExtension @("2.5.29.37={text}$codeSigning", '2.5.29.19={text}') -NotAfter (Get-Date).AddYears(3)
    New-Item -ItemType Directory -Force -Path (Split-Path $BackupPfx) | Out-Null
    Export-PfxCertificate -Cert $minted -FilePath $BackupPfx -Password $pw | Out-Null
    # Re-import without -Exportable: the store copy can sign but never leave the machine as a file.
    Remove-Item -Path "Cert:\CurrentUser\My\$($minted.Thumbprint)" -DeleteKey
    Import-PfxCertificate -FilePath $BackupPfx -CertStoreLocation Cert:\CurrentUser\My -Password $pw | Out-Null
    $cert = Find-ReleaseCert
    if (-not $cert) { throw "Re-import failed. The backup is at $BackupPfx; import it by hand without the exportable flag." }
    Write-Host "Minted $Subject. Backup: $BackupPfx - move it offline now."
}

if (Test-Exportable $cert) {
    throw "The private key of $($cert.Thumbprint) is EXPORTABLE from this store. Delete it (Remove-Item Cert:\CurrentUser\My\$($cert.Thumbprint) -DeleteKey) and re-import the backup without -Exportable, or mint a new one."
}
if ($cert.NotAfter -lt (Get-Date).AddDays(60)) {
    Write-Warning "$Subject expires $($cert.NotAfter.ToString('yyyy-MM-dd')). Renew it (see -? ) before the next release."
}

New-Item -ItemType Directory -Force -Path (Split-Path $CerOut) | Out-Null
Export-Certificate -Cert $cert -FilePath $CerOut | Out-Null

if ($TrustHere) {
    $elevated = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
        IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($elevated) {
        Import-Certificate -FilePath $CerOut -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null
        Write-Host 'Trusted on this PC (LocalMachine\TrustedPeople).'
    } else {
        Write-Warning "Not elevated: run this in an elevated PowerShell to trust it here:`n  Import-Certificate -FilePath '$CerOut' -CertStoreLocation Cert:\LocalMachine\TrustedPeople"
    }
}

Write-Host ''
Write-Host "Subject (MSIX Publisher): $($cert.Subject)"
Write-Host "Thumbprint:               $($cert.Thumbprint)"
Write-Host "Valid until:              $($cert.NotAfter.ToString('yyyy-MM-dd'))"
Write-Host "Private key:              in Cert:\CurrentUser\My, not exportable"
Write-Host "Public certificate:       $CerOut"
Write-Host ''
Write-Host 'Next: scripts\windows\windows-release.ps1 -Thumbprint' $cert.Thumbprint

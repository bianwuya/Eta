#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PublicKeyPath,
    [string]$UserName = $env:USERNAME,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$sshBin = Join-Path $env:WINDIR 'System32\OpenSSH'
$keyPath = (Resolve-Path -LiteralPath $PublicKeyPath).Path
$keyLines = @(Get-Content -LiteralPath $keyPath | Where-Object { $_.Trim() })
if ($keyLines.Count -ne 1 -or $keyLines[0] -notmatch '^ssh-ed25519 [A-Za-z0-9+/]+={0,3}(?: .*)?$') {
    throw 'Expected one OpenSSH Ed25519 public key, not a private key.'
}
& (Join-Path $sshBin 'ssh-keygen.exe') -lf $keyPath
if ($LASTEXITCODE -ne 0) { throw 'Invalid SSH public key.' }

$user = Get-LocalUser -Name $UserName
$adminGroup = Get-LocalGroup -SID 'S-1-5-32-544'
$isAdminAccount = @(Get-LocalGroupMember -Group $adminGroup.Name |
    Where-Object { $_.SID.Value -eq $user.SID.Value }).Count -gt 0
$target = if ($isAdminAccount) {
    Join-Path $env:ProgramData 'ssh\administrators_authorized_keys'
} else {
    $profile = Get-CimInstance Win32_UserProfile | Where-Object SID -EQ $user.SID.Value
    if (-not $profile) { throw 'No Windows profile found for the target user.' }
    Join-Path $profile.LocalPath '.ssh\authorized_keys'
}
if ($CheckOnly) {
    [pscustomobject]@{ User = $UserName; AuthorizedKeysFile = $target; WouldAddKey = $true }
    exit 0
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([Security.Principal.WindowsPrincipal]$identity).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this one-time key authorization in an elevated PowerShell 7 window.'
}

$lines = if (Test-Path -LiteralPath $target) { @(Get-Content -LiteralPath $target) } else { @() }
$key = $keyLines[0].Trim()
$keyFields = $key.Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
$exists = @($lines | Where-Object {
    $fields = $_.Trim().Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
    $fields.Count -ge 2 -and $fields[0] -eq $keyFields[0] -and $fields[1] -eq $keyFields[1]
}).Count -gt 0
if (-not $exists) { $lines += $key }

$directory = Split-Path -Parent $target
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$temporary = Join-Path $directory ('.eta-phone-key-' + [guid]::NewGuid().ToString('N'))
$acl = [Security.AccessControl.FileSecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$admins = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$acl.SetOwner($admins)
foreach ($sid in @($admins, $system)) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
}
if (-not $isAdminAccount) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($user.SID, 'FullControl', 'Allow'))
}
try {
    [IO.File]::WriteAllText($temporary, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    Set-Acl -LiteralPath $temporary -AclObject $acl
    [IO.File]::Move($temporary, $target, $true)
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
& (Join-Path $sshBin 'sshd.exe') -t
if ($LASTEXITCODE -ne 0) { throw 'OpenSSH configuration validation failed.' }
Set-Service -Name sshd -StartupType Automatic
if ((Get-Service sshd).Status -ne 'Running') { Start-Service sshd }
[pscustomobject]@{ Status = 'authorized'; User = $UserName; AuthorizedKeysFile = $target }

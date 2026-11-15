param([string]$ReportPath = 'F:\Apps\CodexPhoneSSH\ssh-diagnostics.json')

$ErrorActionPreference = 'Stop'
$profileDirectory = [Environment]::GetFolderPath('UserProfile')
$relative = '.codex\packages\app-server-daemon\current\bin\codex.exe'
$paths = @(
    (Join-Path $env:USERPROFILE $relative),
    (Join-Path $profileDirectory $relative),
    'C:\Users\bian\.codex',
    'F:\FromC\.codex',
    'F:\FromC\.codex\packages\app-server-daemon\current',
    'F:\FromC\.codex\packages\app-server-daemon\current\bin\codex.exe',
    'F:\Apps\CodexPhoneSSH\cli\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe'
) | Select-Object -Unique
$current = Get-Item -LiteralPath 'F:\FromC\.codex\packages\app-server-daemon\current'
$paths += Join-Path $current.Target 'bin\codex.exe'
$probes = foreach ($path in $paths) {
    $item = $null
    $failure = $null
    try { $item = Get-Item -LiteralPath $path -Force } catch { $failure = $_.Exception.Message }
    [pscustomobject]@{
        Path = $path
        Exists = Test-Path -LiteralPath $path
        NativeFileExists = [IO.File]::Exists($path)
        NativeDirectoryExists = [IO.Directory]::Exists($path)
        ItemType = if ($item) { $item.GetType().Name } else { $null }
        LinkType = if ($item) { $item.LinkType } else { $null }
        Target = if ($item) { $item.Target } else { $null }
        Error = $failure
    }
}
$package = 'F:\Apps\CodexPhoneSSH\cli\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe'
$packageVersion = @(& $package --version 2>&1) -join "`n"
$packageExitCode = $LASTEXITCODE
$checkError = $null
try { & (Join-Path $PSScriptRoot 'phone-codex.ps1') -Check } catch { $checkError = $_.Exception.Message }
$report = [pscustomobject]@{
    Identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    UserProfileEnvironment = $env:USERPROFILE
    UserProfileApi = $profileDirectory
    Home = $HOME
    CodexHome = $env:CODEX_HOME
    Is64BitProcess = [Environment]::Is64BitProcess
    PowerShellVersion = $PSVersionTable.PSVersion.ToString()
    PackageVersion = $packageVersion
    PackageExitCode = $packageExitCode
    Paths = @($probes)
    LauncherError = $checkError
}
$json = $report | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText($ReportPath, $json, [Text.UTF8Encoding]::new($false))
$json

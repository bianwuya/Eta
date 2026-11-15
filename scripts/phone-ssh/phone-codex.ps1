param(
    [switch]$DaemonOnly,
    [switch]$Check,
    [switch]$New,
    [string]$Session,
    [string]$Workspace = 'F:\eta'
)

$ErrorActionPreference = 'Stop'
$codexHomePath = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $env:USERPROFILE '.codex' }
$codexHomeItem = Get-Item -LiteralPath $codexHomePath -Force
$codexHomeDirectory = if ($codexHomeItem.LinkType -eq 'Junction') { $codexHomeItem.Target } else { $codexHomeItem.FullName }
$currentPath = Join-Path $codexHomeDirectory 'packages\app-server-daemon\current'
$current = Get-Item -LiteralPath $currentPath -Force
# Resolve both junctions before adding child paths: the SSH logon can read their
# targets, but cannot traverse the links. Codex also needs the physical home path.
$packageDirectory = if ($current.LinkType -eq 'Junction') { $current.Target } else { $current.FullName }
$codex = Join-Path $packageDirectory 'bin\codex.exe'
if (-not (Test-Path -LiteralPath $codex)) {
    throw 'The packaged Codex daemon is not installed for this Windows user.'
}

$env:PATH = "$(Join-Path $env:WINDIR 'System32\OpenSSH');C:\Program Files\PowerShell\7;C:\Program Files\nodejs;$env:PATH"
$env:CODEX_HOME = $codexHomeDirectory
$daemonState = $null
$daemonStateError = $null
try {
    $daemonState = (& $codex app-server daemon version | ConvertFrom-Json -ErrorAction Stop)
} catch {
    $daemonStateError = $_.Exception.Message
}
$daemonRunning = $daemonState.status -eq 'running'

if ($Check) {
    if (-not $daemonRunning) {
        if ($daemonStateError) { throw "Unable to query the Codex daemon: $daemonStateError" }
        throw 'The Codex daemon is not running. Sign in to Windows to start its session daemon.'
    }
    $daemonState | ConvertTo-Json -Compress
} elseif ($DaemonOnly) {
    if (-not $daemonRunning) {
        & $codex app-server daemon start
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    exit 0
} else {
    if (-not $daemonRunning) {
        if ($daemonStateError) { throw "Unable to query the Codex daemon: $daemonStateError" }
        throw 'The Codex daemon is not running. Sign in to Windows to start its session daemon.'
    }
    if ($New) {
        & $codex -C $Workspace --no-alt-screen
    } elseif ($Session) {
        & $codex resume $Session -C $Workspace --no-alt-screen
    } else {
        & $codex agents -C $Workspace --no-alt-screen
    }
    exit $LASTEXITCODE
}

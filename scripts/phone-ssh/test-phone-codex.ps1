param([string]$ReportPath = 'F:\Apps\CodexPhoneSSH\ssh-launcher-test.json')

$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot 'phone-codex.ps1'
$hostExecutable = Join-Path $PSHOME 'pwsh.exe'
if (-not (Test-Path -LiteralPath $hostExecutable)) {
    $hostExecutable = Join-Path $PSHOME 'powershell.exe'
}
$checks = foreach ($attempt in 1..2) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $hostExecutable
    $start.Arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "' + $launcher + '" -Check'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    $output = $process.StandardOutput.ReadToEndAsync()
    $errorOutput = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(20000)) {
        $process.Kill()
        throw 'Launcher check timed out.'
    }
    $stdout = $output.GetAwaiter().GetResult()
    $stderr = $errorOutput.GetAwaiter().GetResult()
    $status = $null
    if ($process.ExitCode -eq 0) {
        $status = ($stdout | ConvertFrom-Json).status
    }
    [pscustomobject]@{
        Attempt = $attempt
        ExitCode = $process.ExitCode
        Status = $status
        Stderr = $stderr
        Passed = $process.ExitCode -eq 0 -and $status -eq 'running'
    }
    $process.Dispose()
}
$report = [pscustomobject]@{
    Identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    Checks = @($checks)
    Passed = @($checks | Where-Object { -not $_.Passed }).Count -eq 0
}
$json = $report | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText($ReportPath, $json, [Text.UTF8Encoding]::new($false))
$json
if (-not $report.Passed) { exit 1 }

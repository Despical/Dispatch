param([string]$ClamDirectory = (Join-Path $env:LOCALAPPDATA 'Dispatch\clamav-1.5.4\clamav-1.5.4.win.x64'))
$ErrorActionPreference = 'Stop'
$config = Join-Path $ClamDirectory 'clamd.conf'
if (!(Test-Path -LiteralPath $config)) { throw "Local ClamAV is not configured in $ClamDirectory." }
& (Join-Path $ClamDirectory 'freshclam.exe') ('--config-file=' + (Join-Path $ClamDirectory 'freshclam.conf')) --quiet
if ($LASTEXITCODE -ne 0) { throw 'Could not update scanner definitions. Check freshclam before starting the scanner.' }
if (Get-NetTCPConnection -LocalPort 3310 -State Listen -ErrorAction SilentlyContinue) {
    Write-Output 'Definitions updated; attachment scanner is already listening on port 3310.'
    exit
}
$scanner = Start-Process -FilePath (Join-Path $ClamDirectory 'clamd.exe') -ArgumentList ('--config-file="' + $config + '"') -WorkingDirectory $ClamDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $ClamDirectory 'stdout.log') -RedirectStandardError (Join-Path $ClamDirectory 'stderr.log')
Write-Output "Attachment scanner starting in the background (PID $($scanner.Id))."

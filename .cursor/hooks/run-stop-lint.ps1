# Cursor stop hook — :app:lintDebug (Windows, optional; hooks.json에 별도 항목으로만 연결)
# compile 훅 다음에 두면 느려질 수 있음.

$ErrorActionPreference = 'Continue'

try {
    $stdin = New-Object System.IO.StreamReader([System.Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $null = $stdin.ReadToEnd()
    $stdin.Dispose()
} catch { }

$repoRoot = (Get-Item $PSScriptRoot).Parent.Parent.FullName
Set-Location -LiteralPath $repoRoot

$gradleBat = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradleBat)) {
    Write-Output '{}'
    exit 0
}

$log = Join-Path ([System.IO.Path]::GetTempPath()) ("happy-cursor-hook-lint-{0}.log" -f [guid]::NewGuid().ToString('N'))
cmd /c "`"$gradleBat`" :app:lintDebug --no-daemon > `"$log`" 2>&1"
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "Gradle lintDebug failed (exit $code). Tail:" -ForegroundColor Red
    Get-Content -LiteralPath $log -Tail 80 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_ }
}
Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
Write-Output '{}'
exit $code

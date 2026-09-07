# Cursor stop hook — :app:compileDebugKotlin (Windows)
# stdin: hook JSON (consume only). stdout: minimal JSON for Cursor. exit: Gradle exit code.

$ErrorActionPreference = 'Continue'

try {
    $stdin = New-Object System.IO.StreamReader([System.Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $null = $stdin.ReadToEnd()
    $stdin.Dispose()
} catch {
    # stdin may be empty or unavailable; hook should still run
}

$repoRoot = (Get-Item $PSScriptRoot).Parent.Parent.FullName
Set-Location -LiteralPath $repoRoot

$gradleBat = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradleBat)) {
    Write-Output '{}'
    exit 0
}

# Gradle 출력은 stdout에 섞이면 훅 JSON 파싱이 깨질 수 있어 파일로만 받는다.
$log = Join-Path ([System.IO.Path]::GetTempPath()) ("happy-cursor-hook-compile-{0}.log" -f [guid]::NewGuid().ToString('N'))
cmd /c "`"$gradleBat`" :app:compileDebugKotlin --no-daemon > `"$log`" 2>&1"
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "Gradle compileDebugKotlin failed (exit $code). Tail:" -ForegroundColor Red
    Get-Content -LiteralPath $log -Tail 80 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_ }
}
Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
Write-Output '{}'
exit $code

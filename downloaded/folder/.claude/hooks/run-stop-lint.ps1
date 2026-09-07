# Claude Code Stop hook — :app:lintDebug (Windows, 선택). settings.json 의 Stop 배열에 두 번째 항목으로 연결.

$ErrorActionPreference = 'Continue'

try {
    $stdin = New-Object System.IO.StreamReader([System.Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $null = $stdin.ReadToEnd()
    $stdin.Dispose()
} catch { }

$repoRoot = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR.TrimEnd('\', '/') } else { (Get-Item $PSScriptRoot).Parent.Parent.FullName }
Set-Location -LiteralPath $repoRoot

$gradleBat = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradleBat)) {
    exit 0
}

$log = Join-Path ([System.IO.Path]::GetTempPath()) ("happy-claude-hook-lint-{0}.log" -f [guid]::NewGuid().ToString('N'))
cmd /c "`"$gradleBat`" :app:lintDebug --no-daemon > `"$log`" 2>&1"
$code = $LASTEXITCODE
if ($code -ne 0) {
    [Console]::Error.WriteLine("Gradle lintDebug failed (exit $code). Tail:")
    Get-Content -LiteralPath $log -Tail 80 -ErrorAction SilentlyContinue | ForEach-Object { [Console]::Error.WriteLine($_) }
    Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    exit 2
}
Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
exit 0

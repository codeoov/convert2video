# Claude Code Stop hook — :app:compileDebugKotlin (Windows)
# stdin: hook JSON (consume only). 성공 시 exit 0. 실패 시 stderr + exit 2 → Claude가 멈추지 않고 수정 유도.

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

$log = Join-Path ([System.IO.Path]::GetTempPath()) ("convert2video-claude-hook-compile-{0}.log" -f [guid]::NewGuid().ToString('N'))
cmd /c "`"$gradleBat`" :app:compileDebugKotlin --no-daemon > `"$log`" 2>&1"
$code = $LASTEXITCODE
if ($code -ne 0) {
    [Console]::Error.WriteLine("Gradle compileDebugKotlin failed (exit $code). Tail:")
    Get-Content -LiteralPath $log -Tail 80 -ErrorAction SilentlyContinue | ForEach-Object { [Console]::Error.WriteLine($_) }
    Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    exit 2
}
Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
exit 0

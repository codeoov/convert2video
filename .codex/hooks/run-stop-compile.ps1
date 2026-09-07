# Delegates to canonical .cursor/hooks/run-stop-compile.ps1 (single source)
$repoRoot = if ($env:CLAUDE_PROJECT_DIR) {
    $env:CLAUDE_PROJECT_DIR
} else {
    (Get-Item $PSScriptRoot).Parent.Parent.FullName
}
$canonical = Join-Path $repoRoot '.cursor\hooks\run-stop-compile.ps1'
if (-not (Test-Path -LiteralPath $canonical)) {
    Write-Error "Missing canonical script: $canonical"
    exit 2
}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $canonical
exit $LASTEXITCODE

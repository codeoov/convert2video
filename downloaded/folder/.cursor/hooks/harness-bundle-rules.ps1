# harness-bundle-rules.ps1
# 역할별 .mdc 규칙 파일을 번들링하여 cache에 저장하고 stdout으로 출력.
# 사용: harness-bundle-rules.ps1 -Role planner [-Type B] [-Round 2] [-Contract "..."] [-ContractFiles "..."]
param(
    [Parameter(Mandatory=$true)][string]$Role,
    [string]$Type = "",
    [int]$Round = 0,
    [string]$Contract = "",
    [string]$ContractFiles = ""
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot   = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$manifestPath = Join-Path $repoRoot ".cursor\harness\rules-manifest.json"
$cacheDir     = Join-Path $repoRoot ".cursor\harness\cache"

if (-not (Test-Path $manifestPath)) {
    Write-Error "rules-manifest.json not found: $manifestPath"
    exit 1
}

$manifest  = Get-Content $manifestPath -Raw | ConvertFrom-Json
$rulesDir  = Join-Path $repoRoot $manifest.rules_dir
$budget    = [int]$manifest.token_budget_chars

$roleDef = $manifest.roles.$Role
if ($null -eq $roleDef) {
    Write-Error "Unknown role: $Role"
    exit 1
}

# --- 파일 목록 수집 ---
$fileList = [System.Collections.Generic.List[string]]::new()

foreach ($f in $roleDef.always) { if (-not $fileList.Contains($f)) { $fileList.Add($f) } }

if ($Type -and $roleDef.type_extra -and $roleDef.type_extra.$Type) {
    foreach ($f in $roleDef.type_extra.$Type) {
        if (-not $fileList.Contains($f)) { $fileList.Add($f) }
    }
}

if ($Round -gt 0 -and $roleDef.round_extra_from -and $Round -ge [int]$roleDef.round_extra_from -and $roleDef.round_extra) {
    foreach ($f in $roleDef.round_extra) {
        if (-not $fileList.Contains($f)) { $fileList.Add($f) }
    }
}

$blob = $Contract + " " + $ContractFiles
if ($roleDef.contract_path_extra) {
    foreach ($pattern in $roleDef.contract_path_extra.PSObject.Properties.Name) {
        if ($blob -match [regex]::Escape($pattern)) {
            foreach ($f in $roleDef.contract_path_extra.$pattern) {
                if (-not $fileList.Contains($f)) { $fileList.Add($f) }
            }
        }
    }
}

# --- 내용 수집 (frontmatter 제거) ---
$parts = [System.Collections.Generic.List[string]]::new()
$loaded = [System.Collections.Generic.List[string]]::new()

foreach ($fname in $fileList) {
    $fpath = Join-Path $rulesDir $fname
    if (-not (Test-Path $fpath)) { continue }
    $raw = Get-Content $fpath -Raw -Encoding UTF8
    if ($raw -match "(?s)^---\s*\n.*?\n---\s*\n(.*)$") {
        $body = $Matches[1].Trim()
    } else {
        $body = $raw.Trim()
    }
    $parts.Add("### [$fname]`n$body")
    $loaded.Add($fname)
}

# --- 번들 조합 ---
$roundLabel = if ($Round -gt 0) { "Round $Round" } else { "N/A" }
$typeLabel  = if ($Type) { $Type } else { "?" }
$timestamp  = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")

$header = @"
═══ HARNESS RULE CONTEXT (auto-injected) ═══
Role: $Role | Type: $typeLabel | Round: $roundLabel | Files: $($loaded.Count) | Generated: $timestamp
Files: $($loaded -join ", ")
════════════════════════════════════════════

"@

$body   = $parts -join "`n`n---`n`n"
$bundle = $header + $body

# truncate
if ($bundle.Length -gt $budget) {
    $bundle = $bundle.Substring(0, $budget) + "`n`n...[TRUNCATED — token budget $budget chars exceeded]"
}

# --- 캐시 저장 ---
if (-not (Test-Path $cacheDir)) { New-Item -ItemType Directory -Force $cacheDir | Out-Null }

$latestPath = Join-Path $cacheDir "latest-$Role.md"
Set-Content $latestPath $bundle -Encoding UTF8

$roundSuffix = if ($Round -gt 0) { "-r$Round" } else { "-r0" }
$stampedPath = Join-Path $cacheDir "rule-context-$Role$roundSuffix.md"
Set-Content $stampedPath $bundle -Encoding UTF8

$meta = [PSCustomObject]@{
    role      = $Role
    type      = $typeLabel
    round     = $Round
    files     = $loaded
    chars     = $bundle.Length
    generated = $timestamp
}
$metaPath = Join-Path $cacheDir "meta-$Role$roundSuffix.json"
$meta | ConvertTo-Json -Depth 3 | Set-Content $metaPath -Encoding UTF8

# stdout으로 출력 (주입 훅이 캡처)
Write-Output $bundle

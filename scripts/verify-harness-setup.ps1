# Smoke-check convert2video Cursor Harness layout
# exit 0: ok  /  exit 1: missing pieces or regression fail

$ErrorActionPreference = 'Continue'
$repoRoot = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR } else {
    (Get-Item $PSScriptRoot).Parent.FullName
}
Set-Location -LiteralPath $repoRoot

$required = @(
    '.cursor\rules\convert2video-core.mdc',
    '.cursor\rules\sprint-harness-gate.mdc',
    '.cursor\rules\harness-loop.mdc',
    '.cursor\rules\harness-subagents.mdc',
    '.cursor\rules\structure-kotlin.mdc',
    '.cursor\rules\do-not-repeat-kotlin.mdc',
    '.cursor\rules\terminology-glossary.mdc',
    '.cursor\rules\project-scope-kotlin.mdc',
    '.cursor\rules\rules-index-kotlin.mdc',
    '.cursor\harness\rules-manifest.json',
    '.cursor\hooks.json',
    '.cursor\hooks\run-stop-checks.ps1',
    '.cursor\hooks\run-stop-compile.ps1',
    '.cursor\hooks\harness-inject-rules.ps1',
    '.cursor\hooks\harness-subagent-start.ps1',
    '.cursor\hooks\harness-subagent-stop.ps1',
    '.cursor\hooks\harness-lib.ps1',
    '.cursor\agents\planner.md',
    '.cursor\agents\generator.md',
    '.cursor\agents\evaluator.md',
    '.cursor\agents\plan_evaluator.md',
    '.cursor\commands\sprint-run.md',
    'scripts\check-forbidden-imports.ps1',
    'scripts\check-logging-forbidden.ps1',
    'scripts\check-terminology-forbidden.ps1',
    'scripts\check-callbackflow-close-handlers.ps1',
    'scripts\fixtures\callbackflow-close-handlers\pass-comments-and-strings.kt',
    'scripts\fixtures\callbackflow-close-handlers\pass-nested-adjacent.kt',
    'scripts\fixtures\callbackflow-close-handlers\pass-outside-callbackflow.kt',
    'scripts\fixtures\callbackflow-close-handlers\fail-await-then-invoke.kt',
    'scripts\fixtures\callbackflow-close-handlers\fail-invoke-then-await.kt'
)

$missing = @()
foreach ($rel in $required) {
    $full = Join-Path $repoRoot $rel
    if (-not (Test-Path -LiteralPath $full)) {
        $missing += $rel
    }
}

# Manifest must reference convert2video-core, not happy-core
$manifestPath = Join-Path $repoRoot '.cursor\harness\rules-manifest.json'
$manifestOk = $true
if (Test-Path -LiteralPath $manifestPath) {
    $manifestText = Get-Content -LiteralPath $manifestPath -Raw
    if ($manifestText -notmatch 'convert2video-core\.mdc') {
        Write-Host '[FAIL] rules-manifest.json missing convert2video-core.mdc'
        $manifestOk = $false
    }
    if ($manifestText -match 'happy-core-kotlin\.mdc|atomic-design-kotlin\.mdc') {
        Write-Host '[FAIL] rules-manifest.json still references happy_v12-only rules'
        $manifestOk = $false
    }
}

# Stale happy_v12 phase rules should be gone
$stale = @(
    '.cursor\rules\happy-core-kotlin.mdc',
    '.cursor\rules\atomic-design-kotlin.mdc',
    '.cursor\rules\phase1-kotlin.mdc'
)
$staleFound = @()
foreach ($rel in $stale) {
    if (Test-Path -LiteralPath (Join-Path $repoRoot $rel)) {
        $staleFound += $rel
    }
}

$failed = $false
if ($missing.Count -gt 0) {
    Write-Host '[FAIL] missing required files:'
    $missing | ForEach-Object { Write-Host "  $_" }
    $failed = $true
}
if ($staleFound.Count -gt 0) {
    Write-Host '[FAIL] stale happy_v12 files still present:'
    $staleFound | ForEach-Object { Write-Host "  $_" }
    $failed = $true
}
if (-not $manifestOk) { $failed = $true }

if ($failed) { exit 1 }

# ---------------------------------------------------------------------------
# callbackFlow lexical-checker contract: exact inventory and isolated neutral copies.
# ---------------------------------------------------------------------------
$callbackFixtureDir = Join-Path $repoRoot 'scripts\fixtures\callbackflow-close-handlers'
$callbackCheckerRel = 'scripts\check-callbackflow-close-handlers.ps1'
$callbackCheckerPath = Join-Path $repoRoot $callbackCheckerRel
$callbackExpectedCodes = @{
    'pass-comments-and-strings.kt' = 0
    'pass-nested-adjacent.kt' = 0
    'pass-outside-callbackflow.kt' = 0
    'fail-await-then-invoke.kt' = 2
    'fail-invoke-then-await.kt' = 2
}
$callbackSmokeCases = @(
    [PSCustomObject]@{ SourceName = 'pass-comments-and-strings.kt'; ExpectedCode = 0 }
    [PSCustomObject]@{ SourceName = 'pass-nested-adjacent.kt'; ExpectedCode = 0 }
    [PSCustomObject]@{ SourceName = 'pass-outside-callbackflow.kt'; ExpectedCode = 0 }
    [PSCustomObject]@{ SourceName = 'fail-await-then-invoke.kt'; ExpectedCode = 2 }
    [PSCustomObject]@{ SourceName = 'fail-invoke-then-await.kt'; ExpectedCode = 2 }
)
$callbackContractFailed = $false
if (-not (Test-Path -LiteralPath $callbackCheckerPath -PathType Leaf)) {
    Write-Host "[FAIL] callbackFlow checker missing: $callbackCheckerRel"
    $callbackContractFailed = $true
} elseif (-not (Test-Path -LiteralPath $callbackFixtureDir -PathType Container)) {
    Write-Host '[FAIL] callbackFlow fixture directory missing'
    $callbackContractFailed = $true
} else {
    $callbackFixtures = @(Get-ChildItem -LiteralPath $callbackFixtureDir -File -Filter '*.kt')
    $actualNames = @($callbackFixtures | Select-Object -ExpandProperty Name)
    if ($callbackFixtures.Count -ne $callbackExpectedCodes.Count -or
        @($actualNames | Where-Object { -not $callbackExpectedCodes.ContainsKey($_) }).Count -ne 0 -or
        @($callbackExpectedCodes.Keys | Where-Object { $_ -notin $actualNames }).Count -ne 0) {
        Write-Host '[FAIL] callbackFlow fixture inventory must contain exactly the three pass and two fail fixtures'
        $callbackContractFailed = $true
    } else {
        $neutralRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('convert2video-callbackflow-' + [Guid]::NewGuid().ToString('N'))
        try {
            foreach ($smokeCase in $callbackSmokeCases) {
                $fixture = Join-Path $callbackFixtureDir $smokeCase.SourceName
                if (-not (Test-Path -LiteralPath $fixture -PathType Leaf)) {
                    Write-Host ("[FAIL] callbackFlow fixture missing: {0}" -f $smokeCase.SourceName)
                    $callbackContractFailed = $true
                    continue
                }
                $fixtureRoot = Join-Path $neutralRoot ('case-' + [Guid]::NewGuid().ToString('N'))
                $neutralSourceRoot = Join-Path $fixtureRoot 'app\src'
                New-Item -ItemType Directory -Path $neutralSourceRoot -Force | Out-Null
                $neutralFile = Join-Path $neutralSourceRoot 'Sample.kt'
                Copy-Item -LiteralPath $fixture -Destination $neutralFile
                $psi = New-Object System.Diagnostics.ProcessStartInfo
                $psi.FileName = 'powershell.exe'
                $psi.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $callbackCheckerPath + '" -RepoRoot "' + $fixtureRoot + '" -SourceRoot "' + $neutralSourceRoot + '"'
                $psi.WorkingDirectory = $repoRoot
                $psi.UseShellExecute = $false
                $psi.CreateNoWindow = $true
                $psi.RedirectStandardOutput = $true
                $psi.RedirectStandardError = $true
                $process = [System.Diagnostics.Process]::Start($psi)
                $stdout = $process.StandardOutput.ReadToEnd()
                $stderr = $process.StandardError.ReadToEnd()
                $process.WaitForExit()
                if (-not [string]::IsNullOrWhiteSpace($stdout)) { Write-Host $stdout.TrimEnd() }
                if (-not [string]::IsNullOrWhiteSpace($stderr)) { Write-Host $stderr.TrimEnd() }
                $expected = [int]$smokeCase.ExpectedCode
                if ($process.ExitCode -ne $expected) {
                    Write-Host ("[FAIL] callbackFlow neutral sample expected exit {0}, got {1}" -f $expected, $process.ExitCode)
                    $callbackContractFailed = $true
                } else {
                    Write-Host ("[OK] callbackFlow neutral sample exit {0}" -f $process.ExitCode)
                }
            }
        } catch {
            Write-Host "[FAIL] callbackFlow neutral-copy smoke failed: $($_.Exception.Message)"
            $callbackContractFailed = $true
        } finally {
            if (Test-Path -LiteralPath $neutralRoot) {
                Remove-Item -LiteralPath $neutralRoot -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }
}
if ($callbackContractFailed) { exit 1 }

# ---------------------------------------------------------------------------
# Regression smokes (stdin -> hook scripts). Backup/restore harness-state.json.
# ---------------------------------------------------------------------------
$statePath = Join-Path $repoRoot '.cursor\harness-state.json'
$backupPath = Join-Path $repoRoot '.cursor\harness-state.json.verify-bak'
$hadState = Test-Path -LiteralPath $statePath
if ($hadState) {
    Copy-Item -LiteralPath $statePath -Destination $backupPath -Force
}

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Restore-HarnessStateBackup {
    if ($hadState) {
        if (Test-Path -LiteralPath $backupPath) {
            Copy-Item -LiteralPath $backupPath -Destination $statePath -Force
            Remove-Item -LiteralPath $backupPath -Force -ErrorAction SilentlyContinue
        }
    } else {
        # Tests may have created state; original had none - remove test artifact only
        if (Test-Path -LiteralPath $statePath) {
            Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $backupPath -Force -ErrorAction SilentlyContinue
    }
}

function Write-TestArmedState {
    param([int]$Round = 0, [bool]$Pass = $false)
    $obj = [PSCustomObject]@{
        armed            = $true
        armed_at         = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")
        prompt_snippet   = "verify-harness-setup regression"
        generator_active = $false
        evaluator_round  = $Round
        evaluator_pass   = $Pass
    }
    $json = $obj | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($statePath, $json, $utf8NoBom)
}

function Test-BytesNoBom {
    param(
        [AllowNull()]$Bytes,
        [Parameter(Mandatory)][string]$Label
    )
    if ($null -eq $Bytes) { return $true }
    # Normalize: PS may unwrap byte[] / return ArrayList from property access
    $arr = @($Bytes) | ForEach-Object { [byte]$_ }
    if ($arr.Count -eq 0) { return $true }
    if ($arr.Count -ge 3 -and $arr[0] -eq 0xEF -and $arr[1] -eq 0xBB -and $arr[2] -eq 0xBF) {
        Write-Host "[FAIL] $Label - stdout/file starts with UTF-8 BOM (EF BB BF)"
        return $false
    }
    return $true
}

function Test-FileNoBom {
    param(
        [Parameter(Mandatory)][string]$Rel,
        [Parameter(Mandatory)][string]$Label
    )
    $path = Join-Path $repoRoot $Rel
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "[FAIL] $Label - file missing: $Rel"
        return $false
    }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if (-not (Test-BytesNoBom -Bytes $bytes -Label $Label)) {
        return $false
    }
    Write-Host "[OK] $Label - file UTF-8 no-BOM"
    return $true
}

function Invoke-HookStdin {
    param(
        [Parameter(Mandatory)][string]$ScriptRel,
        [Parameter(Mandatory)][string]$Json
    )
    $scriptPath = Join-Path $repoRoot $ScriptRel
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "powershell.exe"
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`""
    $psi.WorkingDirectory = $repoRoot
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $psi.StandardOutputEncoding = $utf8
    $psi.StandardErrorEncoding = $utf8
    $p = [System.Diagnostics.Process]::Start($psi)
    # UTF-8 no-BOM stdin (Korean panjeong etc. must round-trip)
    $stdinBytes = $utf8.GetBytes($Json)
    $p.StandardInput.BaseStream.Write($stdinBytes, 0, $stdinBytes.Length)
    $p.StandardInput.BaseStream.Flush()
    $p.StandardInput.Close()
    # Read raw stdout bytes (BOM assert) before decoding
    $ms = New-Object System.IO.MemoryStream
    $buf = New-Object byte[] 8192
    $outStream = $p.StandardOutput.BaseStream
    while (($n = $outStream.Read($buf, 0, $buf.Length)) -gt 0) {
        $ms.Write($buf, 0, $n)
    }
    $null = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    $stdoutBytes = $ms.ToArray()
    $stdoutText = $utf8.GetString($stdoutBytes)
    return [PSCustomObject]@{
        Text  = $stdoutText
        Bytes = $stdoutBytes
    }
}

function Read-HarnessStateNow {
    if (-not (Test-Path -LiteralPath $statePath)) { return $null }
    try {
        return (Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json)
    } catch { return $null }
}

$regFailed = $false
$MARKER = "=== HARNESS RULE CONTEXT (auto-injected) ==="

try {
    # (f0) hook script files UTF-8 no-BOM (inject/stop/start/lib)
    # start: no stdout by design -> encoding N/A for stdout smoke (file still no-BOM)
    if (-not (Test-FileNoBom -Rel '.cursor\hooks\harness-inject-rules.ps1' -Label '(f0) inject.ps1')) {
        $regFailed = $true
    }
    if (-not (Test-FileNoBom -Rel '.cursor\hooks\harness-subagent-stop.ps1' -Label '(f0) stop.ps1')) {
        $regFailed = $true
    }
    if (-not (Test-FileNoBom -Rel '.cursor\hooks\harness-subagent-start.ps1' -Label '(f0) start.ps1 (stdout N/A)')) {
        $regFailed = $true
    } else {
        Write-Host '[OK] (f0) start.ps1 - stdout encoding N/A (no stdout); file no-BOM checked'
    }
    if (-not (Test-FileNoBom -Rel '.cursor\hooks\harness-lib.ps1' -Label '(f0) lib.ps1')) {
        $regFailed = $true
    }

    Write-TestArmedState -Round 0 -Pass $false

    # (f1) verify state write is BOM-free
    $stateBytes = [System.IO.File]::ReadAllBytes($statePath)
    if (-not (Test-BytesNoBom -Bytes $stateBytes -Label '(f1) Write-TestArmedState harness-state.json')) {
        $regFailed = $true
    } else {
        Write-Host '[OK] (f1) Write-TestArmedState -> state UTF-8 no-BOM'
    }

    # Korean fragments via [char] so verify.ps1 source stays ASCII-safe under PS 5.1 no-BOM
    $panjeong = -join [char[]](0xD310, 0xC815)
    $miwan = -join [char[]](0xBBF8, 0xC644, 0xB8CC)
    $ttoneun = -join [char[]](0xB610, 0xB294)
    $anim = -join [char[]](0xC544, 0xB2D8)
    $phraseOrPass = "$ttoneun PASS"
    $phraseFull = "$miwan $ttoneun PASS $anim"

    # (h) panjeong \u regex <-> literal Hangul equivalence (PASS/FAIL each >=1)
    $uPass = '(?im)^\s*\*\*?\uD310\uC815:\s*PASS\b'
    $uFail = '(?im)^\s*\*\*?\uD310\uC815:\s*FAIL\b'
    $litPass = "**${panjeong}: PASS**"
    $litFail = "**${panjeong}: FAIL**"
    if ($litPass -notmatch $uPass) {
        Write-Host '[FAIL] (h) \u panjeong PASS pattern must match literal Hangul PASS'
        $regFailed = $true
    } elseif ($litFail -notmatch $uFail) {
        Write-Host '[FAIL] (h) \u panjeong FAIL pattern must match literal Hangul FAIL'
        $regFailed = $true
    } else {
        Write-Host '[OK] (h) panjeong \u <-> literal Hangul equivalence (PASS+FAIL)'
    }

    # (g) sprint/exempt \u golden == literal Hangul samples (same patterns as harness-lib.ps1)
    # Do not dot-source lib here (PSScriptRoot / state path side effects).
    $sprintRe = "\uC2A4\uD504\uB9B0\uD2B8|\uB8E8\uD504\s*\uC2DC\uC791|\uD558\uB124\uC2A4|sprint\s*run|/sprint-run|\uC2A4\uD504\uB9B0\uD2B8\s*\uB3CC|\uD50C\uB79C.*\uC2E4\uD589|\uAE30\uD68D.*\uC2E4\uD589"
    $exemptRe = "\uD558\uB124\uC2A4\s*\uC0DD\uB7B5|\uC9C1\uC811\s*\uACE0\uCCD0|\uBE60\uB974\uAC8C\uB9CC|\uC9C8\uBB38\uB9CC|\uC6D0\uC778\s*\uBD84\uC11D|\uC65C\s*\uC548"
    $sprintSample = (-join [char[]](0xC2A4, 0xD504, 0xB9B0, 0xD2B8)) + ' ' + [char]0xB3CC
    $exemptSample = (-join [char[]](0xD558, 0xB124, 0xC2A4)) + ' ' + (-join [char[]](0xC0DD, 0xB7B5))
    $libText = [System.IO.File]::ReadAllText((Join-Path $repoRoot '.cursor\hooks\harness-lib.ps1'), $utf8NoBom)
    if ($libText -notmatch [regex]::Escape('\uC2A4\uD504\uB9B0\uD2B8') -or $libText -notmatch [regex]::Escape('\uD558\uB124\uC2A4\s*\uC0DD\uB7B5')) {
        Write-Host '[FAIL] (g) harness-lib.ps1 missing golden \u sprint/exempt patterns'
        $regFailed = $true
    } elseif ($sprintSample -notmatch $sprintRe) {
        Write-Host '[FAIL] (g) sprint \u pattern must match golden Hangul sprint sample'
        $regFailed = $true
    } elseif ($exemptSample -notmatch $exemptRe) {
        Write-Host '[FAIL] (g) exempt \u pattern must match golden Hangul exempt sample'
        $regFailed = $true
    } else {
        Write-Host '[OK] (g) sprint/exempt \u golden matches literal Hangul samples'
    }

    # (a) explore (or empty harness type) + prompt containing "generator" -> allow only, no inject
    $exploreJson = @'
{"tool_name":"Task","tool_input":{"subagent_type":"explore","description":"explore codebase","prompt":"Look for generator patterns in hooks"}}
'@
    $exploreRes = Invoke-HookStdin -ScriptRel '.cursor\hooks\harness-inject-rules.ps1' -Json $exploreJson
    $exploreOut = $exploreRes.Text
    if (-not (Test-BytesNoBom -Bytes $exploreRes.Bytes -Label '(a) inject stdout')) {
        $regFailed = $true
    } elseif ($exploreOut -match [regex]::Escape($MARKER)) {
        Write-Host '[FAIL] (a) explore+prompt"generator" must NOT inject rules'
        $regFailed = $true
    } elseif ($exploreOut -notmatch '"permission"\s*:\s*"allow"') {
        Write-Host '[FAIL] (a) explore path must return permission allow'
        $regFailed = $true
    } else {
        Write-Host '[OK] (a) explore + "generator" in prompt -> allow only (no inject); stdout no-BOM'
    }

    # (b) subagent_type=generator -> inject + description/subagent_type preserved
    $genJson = @'
{"tool_name":"Task","tool_input":{"subagent_type":"generator","description":"generator: Round 2 impl","prompt":"Implement contract fixes for harness hooks."}}
'@
    $genRes = Invoke-HookStdin -ScriptRel '.cursor\hooks\harness-inject-rules.ps1' -Json $genJson
    $genOut = $genRes.Text
    if (-not (Test-BytesNoBom -Bytes $genRes.Bytes -Label '(b) inject stdout')) {
        $regFailed = $true
    } elseif ($genOut -notmatch [regex]::Escape($MARKER)) {
        Write-Host '[FAIL] (b) generator must inject MARKER'
        $regFailed = $true
    } elseif ($genOut -notmatch '"subagent_type"\s*:\s*"generator"') {
        Write-Host '[FAIL] (b) updated_input must preserve subagent_type=generator'
        $regFailed = $true
    } elseif ($genOut -notmatch 'generator:\s*Round\s*2') {
        Write-Host '[FAIL] (b) updated_input must preserve description'
        $regFailed = $true
    } else {
        Write-Host '[OK] (b) generator -> inject + description/subagent_type preserved; stdout no-BOM'
    }

    # (c) FAIL phrase + round>=3 simulate -> evaluator_pass stays false
    # Seed round=2 so stop increments to 3; payload has FAIL pollution (and bare PASS prose)
    Write-TestArmedState -Round 2 -Pass $false
    $failJson = '{"subagent_type":"evaluator","result":"Looks like PASS overall but Grill-me still has issues.\n**' + $panjeong + ': FAIL**\nCritical: remaining gaps.","output":""}'
    $failRes = Invoke-HookStdin -ScriptRel '.cursor\hooks\harness-subagent-stop.ps1' -Json $failJson
    if (-not (Test-BytesNoBom -Bytes $failRes.Bytes -Label '(c) stop followup stdout')) {
        $regFailed = $true
    }
    if ($failRes.Text -notmatch 'followup_message') {
        Write-Host '[FAIL] (c) stop FAIL path must emit followup_message JSON'
        $regFailed = $true
    }
    # Decode JSON so \uXXXX Hangul becomes literal for Round1 phrase assert
    $fuMsg = $null
    try { $fuMsg = [string](($failRes.Text | ConvertFrom-Json).followup_message) } catch { $fuMsg = $failRes.Text }
    if ([string]::IsNullOrEmpty($fuMsg) -or ($fuMsg -notlike "*$phraseOrPass*")) {
        Write-Host '[FAIL] (c) followup must contain "ttoneun PASS" / "or PASS" Round1 fragment'
        $regFailed = $true
    }
    if ([string]::IsNullOrEmpty($fuMsg) -or ($fuMsg -notlike "*$phraseFull*")) {
        Write-Host '[FAIL] (c) followup must contain full "miwanryo ttoneun PASS anim" (not truncated)'
        $regFailed = $true
    }
    $stC = Read-HarnessStateNow
    if ($null -eq $stC) {
        Write-Host '[FAIL] (c) state missing after evaluator stop'
        $regFailed = $true
    } elseif ([bool]$stC.evaluator_pass -eq $true) {
        Write-Host '[FAIL] (c) evaluator_pass must stay false when FAIL pollution present (even at round>=3)'
        $regFailed = $true
    } elseif ([int]$stC.evaluator_round -lt 3) {
        Write-Host '[FAIL] (c) evaluator_round should be >= 3 after stop from seed 2'
        $regFailed = $true
    } elseif ($fuMsg -like "*$phraseFull*") {
        Write-Host '[OK] (c) FAIL pollution + round>=3 -> evaluator_pass false; followup Round1 phrase + no-BOM'
    } else {
        Write-Host '[OK] (c) FAIL pollution + round>=3 -> evaluator_pass false; followup stdout no-BOM'
    }

    # Also: bare PASS word without explicit panjeong/VERDICT must not pass
    Write-TestArmedState -Round 2 -Pass $false
    $barePassJson = @'
{"subagent_type":"evaluator","result":"This run should PASS because compile is green.","output":""}
'@
    $bareRes = Invoke-HookStdin -ScriptRel '.cursor\hooks\harness-subagent-stop.ps1' -Json $barePassJson
    if (-not (Test-BytesNoBom -Bytes $bareRes.Bytes -Label '(c2) stop followup stdout')) {
        $regFailed = $true
    }
    $stC2 = Read-HarnessStateNow
    if ($null -ne $stC2 -and [bool]$stC2.evaluator_pass -eq $true) {
        Write-Host '[FAIL] (c2) bare \\bPASS\\b must NOT set evaluator_pass'
        $regFailed = $true
    } else {
        Write-Host '[OK] (c2) bare PASS word alone -> evaluator_pass false; followup stdout no-BOM'
    }

    # (f2) Save-HarnessState (lib) wrote state without BOM
    $libStateBytes = [System.IO.File]::ReadAllBytes($statePath)
    if (-not (Test-BytesNoBom -Bytes $libStateBytes -Label '(f2) Save-HarnessState harness-state.json')) {
        $regFailed = $true
    } else {
        Write-Host '[OK] (f2) Save-HarnessState -> state UTF-8 no-BOM'
    }

    # (d) plan_evaluator stop -> evaluator_round unchanged
    Write-TestArmedState -Round 5 -Pass $false
    $peJson = '{"subagent_type":"plan_evaluator","result":"**' + $panjeong + ': PASS**\nApproved.","output":""}'
    $null = Invoke-HookStdin -ScriptRel '.cursor\hooks\harness-subagent-stop.ps1' -Json $peJson
    $stD = Read-HarnessStateNow
    if ($null -eq $stD) {
        Write-Host '[FAIL] (d) state missing after plan_evaluator stop'
        $regFailed = $true
    } elseif ([int]$stD.evaluator_round -ne 5) {
        Write-Host ("[FAIL] (d) plan_evaluator must not change evaluator_round (got {0}, want 5)" -f $stD.evaluator_round)
        $regFailed = $true
    } elseif ([bool]$stD.evaluator_pass -eq $true) {
        Write-Host '[FAIL] (d) plan_evaluator must not set evaluator_pass'
        $regFailed = $true
    } else {
        Write-Host '[OK] (d) plan_evaluator stop -> evaluator_round unchanged'
    }

    # (e) positive: ASCII VERDICT: PASS + round>=3; narrative "FAIL" words must NOT block pass
    Write-TestArmedState -Round 2 -Pass $false
    $passJson = @'
{"subagent_type":"evaluator","result":"Round1 FAIL fixed. Grill-me addressed.\nVERDICT: PASS\nAll critical items resolved.","output":""}
'@
    $null = Invoke-HookStdin -ScriptRel '.cursor\hooks\harness-subagent-stop.ps1' -Json $passJson
    $stE = Read-HarnessStateNow
    if ($null -eq $stE) {
        Write-Host '[FAIL] (e) state missing after positive PASS stop'
        $regFailed = $true
    } elseif ([bool]$stE.evaluator_pass -ne $true) {
        Write-Host '[FAIL] (e) evaluator_pass must be true for explicit VERDICT: PASS at round>=3 (narrative FAIL ignored)'
        $regFailed = $true
    } elseif ([int]$stE.evaluator_round -lt 3) {
        Write-Host '[FAIL] (e) evaluator_round should be >= 3 after stop from seed 2'
        $regFailed = $true
    } else {
        Write-Host '[OK] (e) VERDICT: PASS + round>=3 (narrative FAIL ignored) -> evaluator_pass true'
    }

    # (e2) positive: Korean **panjeong: PASS** + round>=3; narrative "FAIL" words must NOT block pass
    Write-TestArmedState -Round 2 -Pass $false
    $passKoJson = '{"subagent_type":"evaluator","result":"Round1 FAIL fixed. Grill-me addressed.\n**' + $panjeong + ': PASS**\nAll critical items resolved.","output":""}'
    $null = Invoke-HookStdin -ScriptRel '.cursor\hooks\harness-subagent-stop.ps1' -Json $passKoJson
    $stE2 = Read-HarnessStateNow
    if ($null -eq $stE2) {
        Write-Host '[FAIL] (e2) state missing after Korean panjeong PASS stop'
        $regFailed = $true
    } elseif ([bool]$stE2.evaluator_pass -ne $true) {
        Write-Host '[FAIL] (e2) evaluator_pass must be true for explicit panjeong PASS at round>=3 (narrative FAIL ignored)'
        $regFailed = $true
    } elseif ([int]$stE2.evaluator_round -lt 3) {
        Write-Host '[FAIL] (e2) evaluator_round should be >= 3 after stop from seed 2'
        $regFailed = $true
    } else {
        Write-Host '[OK] (e2) panjeong PASS + round>=3 (narrative FAIL ignored) -> evaluator_pass true'
    }
}
catch {
    Write-Host "[FAIL] regression smoke exception: $($_.Exception.Message)"
    $regFailed = $true
}
finally {
    Restore-HarnessStateBackup
}

if ($regFailed) {
    Write-Host '[FAIL] verify-harness-setup.ps1 - regression smokes failed'
    exit 1
}

Write-Host '[OK] verify-harness-setup.ps1 - convert2video harness layout looks good'
exit 0

# Check that one callbackFlow block does not register both close-handler styles.
# exit 0 = clean, exit 1 = unreadable/malformed input, exit 2 = policy violation.

param(
    [string]$RepoRoot,
    [string]$SourceRoot
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Get-Item $PSScriptRoot).Parent.FullName
}
$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot)

$sourceRoot = if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
    Join-Path $RepoRoot 'app\src'
} else {
    [System.IO.Path]::GetFullPath($SourceRoot)
}
if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
    Write-Error "[FAIL] callbackFlow checker source root is missing: $sourceRoot"
    exit 1
}

function Format-SourceLocation {
    param(
        [string]$Path,
        [int]$Index,
        [int[]]$LineStarts
    )
    $location = Get-SourceLocation -Index $Index -LineStarts $LineStarts
    return ('{0}:{1}:{2}' -f $Path, $location.Line, $location.Column)
}

function Get-SourceLocation {
    param(
        [int]$Index,
        [int[]]$LineStarts
    )
    $line = [Array]::BinarySearch($LineStarts, $Index)
    if ($line -lt 0) { $line = (-$line) - 2 }
    return [PSCustomObject]@{
        Line = $line + 1
        Column = $Index - $LineStarts[$line] + 1
    }
}

function Get-KotlinStructure {
    param(
        [string]$Text,
        [string]$Path
    )

    $lineStarts = New-Object System.Collections.Generic.List[int]
    [void]$lineStarts.Add(0)
    for ($i = 0; $i -lt $Text.Length; $i++) {
        if ($Text[$i] -eq "`n") { [void]$lineStarts.Add($i + 1) }
    }
    $lineStartArray = $lineStarts.ToArray()
    $scopes = New-Object System.Collections.Stack
    $violation = $null
    $pendingCallbackFlow = $false
    $i = 0
    $length = $Text.Length

    while ($i -lt $length) {
        $ch = $Text[$i]

        if ([char]::IsWhiteSpace($ch)) { $i++; continue }

        if ($ch -eq '/' -and $i + 1 -lt $length -and $Text[$i + 1] -eq '/') {
            $i += 2
            while ($i -lt $length -and $Text[$i] -ne "`n") { $i++ }
            continue
        }

        if ($ch -eq '/' -and $i + 1 -lt $length -and $Text[$i + 1] -eq '*') {
            $commentStart = $i
            $i += 2
            $commentDepth = 1
            while ($i -lt $length -and $commentDepth -gt 0) {
                if ($i + 1 -lt $length -and $Text[$i] -eq '/' -and $Text[$i + 1] -eq '*') {
                    $commentDepth++
                    $i += 2
                } elseif ($i + 1 -lt $length -and $Text[$i] -eq '*' -and $Text[$i + 1] -eq '/') {
                    $commentDepth--
                    $i += 2
                } else {
                    $i++
                }
            }
            if ($commentDepth -ne 0) {
                throw "$(Format-SourceLocation -Path $Path -Index $commentStart -LineStarts $lineStartArray): unterminated block comment"
            }
            continue
        }

        if ($ch -eq '"') {
            $stringStart = $i
            if ($i + 2 -lt $length -and
                $Text[$i + 1] -eq '"' -and
                $Text[$i + 2] -eq '"') {
                $i += 3
                $closed = $false
                while ($i -lt $length) {
                    if ($i + 2 -lt $length -and
                        $Text[$i] -eq '"' -and
                        $Text[$i + 1] -eq '"' -and
                        $Text[$i + 2] -eq '"') {
                        $i += 3
                        $closed = $true
                        break
                    }
                    $i++
                }
            } else {
                $i++
                $closed = $false
                while ($i -lt $length) {
                    if ($Text[$i] -eq [char]92) {
                        $i += 2
                    } elseif ($i -lt $length -and $Text[$i] -eq '"') {
                        $i++
                        $closed = $true
                        break
                    } else {
                        $i++
                    }
                }
            }
            if (-not $closed) {
                throw "$(Format-SourceLocation -Path $Path -Index $stringStart -LineStarts $lineStartArray): unterminated string literal"
            }
            continue
        }

        if ($ch -eq "'") {
            $charStart = $i
            $i++
            $closed = $false
            while ($i -lt $length) {
                if ($Text[$i] -eq [char]92) {
                    $i += 2
                } elseif ($i -lt $length -and $Text[$i] -eq "'") {
                    $i++
                    $closed = $true
                    break
                } elseif ($Text[$i] -eq "`n" -or $Text[$i] -eq "`r") {
                    break
                } else {
                    $i++
                }
            }
            if (-not $closed) {
                throw "$(Format-SourceLocation -Path $Path -Index $charStart -LineStarts $lineStartArray): unterminated character literal"
            }
            continue
        }

        if ($pendingCallbackFlow -and $ch -ne '{') {
            $pendingCallbackFlow = $false
        }

        if ($ch -eq '`') {
            $identifierStart = $i
            $i++
            while ($i -lt $length -and $Text[$i] -ne '`') { $i++ }
            if ($i -ge $length) {
                throw "$(Format-SourceLocation -Path $Path -Index $identifierStart -LineStarts $lineStartArray): unterminated backtick identifier"
            }
            $value = $Text.Substring($identifierStart + 1, $i - $identifierStart - 1)
            $i++
            if ($value -eq 'callbackFlow') {
                $pendingCallbackFlow = $true
            } elseif ($value -eq 'invokeOnClose' -or $value -eq 'awaitClose') {
                foreach ($scope in $scopes) {
                    if ($scope.IsCallbackFlow) {
                        if ($value -eq 'invokeOnClose' -and $null -eq $scope.InvokeOnClose) {
                            $scope.InvokeOnClose = [PSCustomObject]@{ Start = $identifierStart }
                        } elseif ($value -eq 'awaitClose' -and $null -eq $scope.AwaitClose) {
                            $scope.AwaitClose = [PSCustomObject]@{ Start = $identifierStart }
                        }
                        break
                    }
                }
            }
            continue
        }

        if ([char]::IsLetterOrDigit($ch) -or $ch -eq '_') {
            $identifierStart = $i
            $i++
            while ($i -lt $length -and ([char]::IsLetterOrDigit($Text[$i]) -or $Text[$i] -eq '_')) { $i++ }
            $value = $Text.Substring($identifierStart, $i - $identifierStart)
            if ($value -eq 'callbackFlow') {
                $pendingCallbackFlow = $true
            } elseif ($value -eq 'invokeOnClose' -or $value -eq 'awaitClose') {
                foreach ($scope in $scopes) {
                    if ($scope.IsCallbackFlow) {
                        if ($value -eq 'invokeOnClose' -and $null -eq $scope.InvokeOnClose) {
                            $scope.InvokeOnClose = [PSCustomObject]@{ Start = $identifierStart }
                        } elseif ($value -eq 'awaitClose' -and $null -eq $scope.AwaitClose) {
                            $scope.AwaitClose = [PSCustomObject]@{ Start = $identifierStart }
                        }
                        break
                    }
                }
            }
            continue
        }

        if ($ch -eq '{') {
            [void]$scopes.Push([PSCustomObject]@{
                IsCallbackFlow = $pendingCallbackFlow
                InvokeOnClose = $null
                AwaitClose = $null
                Start = $i
            })
            $pendingCallbackFlow = $false
            $i++
            continue
        }
        if ($ch -eq '}') {
            if ($scopes.Count -eq 0) {
                throw "$(Format-SourceLocation -Path $Path -Index $i -LineStarts $lineStartArray): unmatched closing brace"
            }
            $scope = $scopes.Pop()
            if ($scope.IsCallbackFlow -and
                $null -ne $scope.InvokeOnClose -and
                $null -ne $scope.AwaitClose -and
                $null -eq $violation) {
                $first = Get-SourceLocation -Index $scope.InvokeOnClose.Start -LineStarts $lineStartArray
                $second = Get-SourceLocation -Index $scope.AwaitClose.Start -LineStarts $lineStartArray
                $violation = [PSCustomObject]@{
                    First = $first
                    Second = $second
                }
            }
            $i++
            continue
        }

        $i++
    }

    if ($scopes.Count -ne 0) {
        throw "$(Format-SourceLocation -Path $Path -Index $scopes.Peek().Start -LineStarts $lineStartArray): unmatched opening brace"
    }

    return [PSCustomObject]@{
        Violation = $violation
    }
}

function Test-KotlinFile {
    param(
        [string]$Path,
        [string]$RelativePath
    )

    try {
        $text = [System.IO.File]::ReadAllText($Path, [System.Text.UTF8Encoding]::new($false, $true))
    } catch {
        throw "${RelativePath}: unreadable file ($($_.Exception.Message))"
    }

    $structure = Get-KotlinStructure -Text $text -Path $RelativePath
    if ($null -ne $structure.Violation) {
        $first = $structure.Violation.First
        $second = $structure.Violation.Second
        Write-Host ("[FAIL] {0}:{1}:{2} callbackFlow block contains both invokeOnClose " -f $RelativePath, $first.Line, $first.Column) -NoNewline
        Write-Host ("and awaitClose at {0}:{1}:{2}." -f $RelativePath, $second.Line, $second.Column)
        return $false
    }
    return $true
}

$files = @()
try {
    $files = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.kt' -ErrorAction Stop)
} catch {
    Write-Error "[FAIL] callbackFlow checker could not enumerate Kotlin files: $($_.Exception.Message)"
    exit 1
}
$malformed = $false
$violations = $false
foreach ($file in $files) {
    $relative = $file.FullName.Substring($RepoRoot.TrimEnd('\').Length + 1)
    try {
        if (-not (Test-KotlinFile -Path $file.FullName -RelativePath $relative)) { $violations = $true }
    } catch {
        Write-Error "[FAIL] callbackFlow checker malformed/unreadable: $($_.Exception.Message)"
        $malformed = $true
    }
}

if ($malformed) { exit 1 }
if ($violations) { exit 2 }
Write-Host ("[OK] callbackFlow close-handler checker scanned {0} Kotlin files." -f $files.Count)
exit 0

# C2V Desktop

C2V Desktop is the Windows companion for synchronizing recordings over the Android app's local network (LAN) pairing flow.

## Prerequisites

- Windows
- Rust stable
- Node.js and npm
- Windows MSVC Build Tools and the Windows SDK

The `@tauri-apps/cli` package is a local dependency of this app. A global Tauri CLI installation is not required.

## Repository context

This README documents `desktop/` in the current monorepo checkout. The desktop files may be untracked in a given checkout, so review local Git status before staging or publishing. This document does not establish Git patch provenance or perform a repository split.

## Development and build

Run these commands from the repository root in PowerShell:

```powershell
cd desktop
npm install
npm run dev
npm run build
```

For the current `desktop/src-tauri/tauri.conf.json`, the release artifact is deterministic:

- `releaseVersion`: `0.0.1`
- installer filename: `C2V Desktop_0.0.1_x64-setup.exe`
- installer path from `desktop`: `src-tauri/target/release/bundle/nsis/C2V Desktop_0.0.1_x64-setup.exe`

When already in `desktop`, resolve the final artifact by its explicit path:

```text
src-tauri/target/release/bundle/nsis/C2V Desktop_0.0.1_x64-setup.exe
```

If the version, `productName`, or architecture changes, update both `releaseVersion` and the exact installer filename in this README, then update the release tag and checksum asset names to match.

The documented installer path assumes that Session 2 bundling and icon setup has been completed. Any existing bundle or icon settings in `desktop/src-tauri/tauri.conf.json` are pre-existing and outside this Session 3 change. This README documents the release procedure only; it does not edit `tauri.conf.json` or perform Session 2. Do not remove or edit those settings as part of this README change.

From `desktop`, bind the final artifact explicitly before hashing or publishing:

```powershell
$releaseVersion = '0.0.1'
$installerFileName = 'C2V Desktop_0.0.1_x64-setup.exe'
$nsisDirectory = Join-Path (Get-Location) 'src-tauri\target\release\bundle\nsis'
$installerPath = Join-Path $nsisDirectory $installerFileName

if (-not (Test-Path -LiteralPath $installerPath -PathType Leaf)) {
    throw "Expected installer was not found: $installerPath"
}

Write-Host "Release $releaseVersion artifact: $installerPath"
```

Do not replace `$installerPath` with a wildcard. The final artifact must always be selected with `Join-Path` and checked with `-LiteralPath`.

## Installation and safety

Run the NSIS setup executable and use its current-user installation. No machine-wide installation is needed. After installation, launch C2V Desktop from the installed Start menu entry or shortcut.

The installer and other packaged binaries are currently **unsigned binaries**. Before running a published binary, verify its published SHA-256 checksum. Windows SmartScreen may show this warning:

> Windows에서 알 수 없는 게시자 경고가 뜨면 '추가 정보' → '실행'을 눌러주세요 — 코드서명 인증서 발급 전까지의 임시 상태입니다.

## Manual GitHub Releases

CI automation is out of scope. To publish a release manually:

1. From `desktop`, run `npm run build`.
2. Resolve the exact current artifact using the explicit `LiteralPath` flow above.
3. Generate the checksum file for this installer. The file is exactly one line in this format: `<64-hex SHA-256 hash> *C2V Desktop_0.0.1_x64-setup.exe`. The asterisk marks the exact installer filename; it is not a wildcard for selection.

   ```powershell
   $checksumPath = Join-Path (Get-Location) 'C2V Desktop_0.0.1_x64-setup.exe.sha256'
   $installerHash = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash.ToLowerInvariant()
   "$installerHash *$installerFileName" | Set-Content -LiteralPath $checksumPath -Encoding ascii
   
   Write-Host "Wrote checksum: $checksumPath"
   ```

4. After the checksum has been published (or downloaded from the release draft), verify the downloaded/published installer and checksum together. This validates both the SHA-256 value and the exact installer filename:

   ```powershell
   $downloadDirectory = Get-Location
   $installerFileName = 'C2V Desktop_0.0.1_x64-setup.exe'
   $installerPath = Join-Path $downloadDirectory $installerFileName
   $checksumPath = Join-Path $downloadDirectory 'C2V Desktop_0.0.1_x64-setup.exe.sha256'

   if (-not (Test-Path -LiteralPath $installerPath -PathType Leaf)) {
       throw "Downloaded installer was not found: $installerPath"
   }
   if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
       throw "Published checksum file was not found: $checksumPath"
   }

   $checksumLines = @(Get-Content -LiteralPath $checksumPath)
   if ($checksumLines.Count -ne 1) {
       throw "Checksum file must contain exactly one line."
   }
   $checksumMatch = [regex]::Match($checksumLines[0], '^(?<hash>[0-9A-Fa-f]{64}) \*(?<fileName>.+)$')
   if (-not $checksumMatch.Success) {
       throw "Checksum file must contain '<64-hex SHA-256 hash> *<exact installer filename>'."
   }

   $publishedFileName = $checksumMatch.Groups['fileName'].Value
   if ($publishedFileName -cne $installerFileName) {
       throw "Checksum filename mismatch. Expected '$installerFileName'; found '$publishedFileName'."
   }

   $localHash = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash
   $publishedHash = $checksumMatch.Groups['hash'].Value
   if ($localHash -ine $publishedHash) {
       throw "SHA-256 mismatch for $installerFileName. Local: $localHash; published: $publishedHash"
   }

   Write-Host "SHA-256 and filename verified for $installerFileName"
   ```

5. Upload the installer, its checksum, and release notes manually to GitHub Releases.

Before publishing, complete this release tag and artifact naming checklist:

- [ ] Release tag is `v0.0.1`, matching `releaseVersion`.
- [ ] Installer asset is exactly `C2V Desktop_0.0.1_x64-setup.exe`.
- [ ] Checksum asset is exactly `C2V Desktop_0.0.1_x64-setup.exe.sha256`.
- [ ] Checksum contains one line with the installer filename exactly, and was generated from that exact installer.
- [ ] Remove or replace stale checksum, installer, and release-note assets from another version or architecture; do not publish them alongside this release.

For an informational signature check, run:

```powershell
Get-AuthenticodeSignature -LiteralPath $installerPath | Select-Object Status, StatusMessage, Path, SignerCertificate
```

The expected `Status` for this unsigned scope is `NotSigned`. This check is informational; no code signing has been performed.

## Android pairing protocol

The pairing protocol SSOT is Android's [`PairingProtocol.kt`](../app/src/main/java/com/example/convert2video/desktopsync/PairingProtocol.kt). After the repositories are split, update this link to point to the Android repository. Do not change Android code as part of desktop work.

Compatibility is governed by the Android app's current protocol. Any protocol change requires checking both repositories.

Before publishing, manually verify compatibility against a matching Android build:

- [ ] Compare the Android `PairingProtocol.kt` route and field constants with the desktop client.
- [ ] Run recording → discovery → approval pairing → upload end to end.
- [ ] No automated cross-repo fixture is added in Session 3; this compatibility check is manual.

## Out of scope

This document does not complete or imply completion of:

- Session 1 repository split, move, or delete work
- Session 2 icon generation and bundle activation
- Code signing, updater, or CI/CD
- macOS or Linux support
- Android download or Options UI

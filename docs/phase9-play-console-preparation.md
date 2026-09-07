# Phase 9 — Manifest/Play 정책 정리 및 Console 준비

## Status/Scope

- 상태: Console 제출 준비 초안. 실제 Play Console 제출, 스테이징, 커밋, 스테이징 영역 등록은 수행하지 않는다.
- 범위: Phase 9의 Manifest 주석 정리, Android backup 정책 parity, Play Console 준비 정보 기록.
- 사용자 가정: Phase 1~8이 완료되어 기존 녹음·변환·업로드·분석·crash 기능이 현재 작업 트리에 존재한다.
- 확인한 사실: 이 문서 작성 시점의 소스, 리소스, `app/build.gradle.kts`, 현재 dirty worktree를 읽어 확인한 내용이다. 사용자 가정과 확인한 사실이 충돌하면 확인한 사실을 우선 release blocker/risk로 기록한다.
- 실기기/Console 상태: ADB 연결 기기 없음. 실제 런타임과 Console 입력은 검증하지 않았다.
- 비범위: Kotlin, Gradle, Room, Worker, billing/entitlement, telemetry 구현, strings, namespace/applicationId, 기타 파일 변경.
- 기준선: `2026-09-05` Generator Round 1 직전의 dirty worktree snapshot이다. 기준선 SHA256은 `AndroidManifest.xml=9C02E974692FB332E31BF0B13860CC67EDD98A3C5398EFF7D6293CA8CD125CD2`, `backup_rules.xml=4F383DE7BF090298EECA0B76993CDF555EA7355CFDE81F2A8BF7D01081CCA7F1`, `data_extraction_rules.xml=EA294DE83BAB8593CD217638920D6A15B931D36E04733EC6A8880678CBD1E54D`이다. `HEAD` 기준 diff는 baseline diff가 아니며, 기존 dirty 변경을 보존한 상태 위에 Phase 9 변경을 적용했다.
- baseline snapshot은 Harness 실행 기록에 보존한다. Contract가 정확히 4개 파일이므로 repository snapshot 파일은 추가하지 않는다. baseline file list는 `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `docs/phase9-play-console-preparation.md`이다.

## Current code inventory

### 확인한 앱 식별자와 빌드 사실

- `app/build.gradle.kts`의 `namespace`: `com.example.convert2video`.
- `applicationId`: `com.convert2video`.
- `compileSdk`: `36`; `targetSdk`: `36`; `minSdk`: `26`.
- `google` flavor의 `STORE_ID`: `google_play`; `huawei` flavor의 `STORE_ID`: `huawei`.
- 앱 표시명은 `@string/app_name`을 사용한다. 최종 Play listing 명칭은 미확정이다.
- 현재 `BACKEND_BASE_URL`은 `https://TODO.example`이다. Phase 1~8 완료라는 사용자 가정과 충돌하는 확인 사실이며, 구현 변경 없이 release blocker/risk로 남긴다.

### Normalized baseline semantic projection snapshot

다음은 보존된 baseline의 normalized semantic projection snapshot이다. 이는 원본 파일의 byte-for-byte snapshot이라고 주장하지 않으며, Harness 실행 기록의 baseline과 현재 Manifest를 비교하기 위한 의미적 기준이다.

- `namespace=com.example.convert2video`
- `applicationId=com.convert2video`

| Permission declaration | maxSdkVersion |
|---|---|
| `android.permission.WRITE_EXTERNAL_STORAGE` | `28` |
| `android.permission.READ_MEDIA_AUDIO` | 없음 |
| `android.permission.READ_EXTERNAL_STORAGE` | `32` |
| `android.permission.RECORD_AUDIO` | 없음 |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | 없음 |
| `android.permission.MODIFY_AUDIO_SETTINGS` | 없음 |
| `android.permission.SCHEDULE_EXACT_ALARM` | 없음 |
| `android.permission.RECEIVE_BOOT_COMPLETED` | 없음 |
| `android.permission.POST_NOTIFICATIONS` | 없음 |
| `android.permission.FOREGROUND_SERVICE` | 없음 |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | 없음 |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING` | 없음 |
| `android.permission.INTERNET` | 없음 |
| `android.permission.ACCESS_NETWORK_STATE` | 없음 |
| `android.permission.ACCESS_WIFI_STATE` | 없음 |
| `android.permission.CHANGE_WIFI_MULTICAST_STATE` | 없음 |

정규화된 service/component projection은 `SystemForegroundService=mediaProcessing|dataSync`, `RecordingService=microphone|dataSync`, `PairingServerService=dataSync`이다. `USE_EXACT_ALARM`은 absent다. 이것이 baseline normalized semantic projection assertion의 expected 결과이며, 현재 projection이 동일할 때에만 Manifest의 comments-only delta를 허용한다. 원본 baseline의 byte-for-byte 동일성을 주장하지 않고, comments-only 여부는 baseline hash/audit와 함께 판정한다.

### Manifest permission inventory

| 선언 | 현재 용도/플랫폼 경계 |
|---|---|
| `WRITE_EXTERNAL_STORAGE` | API 28 이하 legacy 외부 저장소 쓰기, `maxSdkVersion="28"` |
| `READ_MEDIA_AUDIO` | API 33+ MediaStore audio 조회 |
| `READ_EXTERNAL_STORAGE` | API 32 이하 audio 조회, `maxSdkVersion="32"` |
| `RECORD_AUDIO` | 마이크 녹음 |
| `FOREGROUND_SERVICE_MICROPHONE` | microphone FGS 선언 |
| `MODIFY_AUDIO_SETTINGS` | Bluetooth SCO/communication-device 오디오 라우팅, runtime dialog 없음 |
| `SCHEDULE_EXACT_ALARM` | 사용자가 허용하는 정확한 scheduled recording/Quick Timer 알람 |
| `RECEIVE_BOOT_COMPLETED` | 부팅 뒤 활성 scheduled recording START 알람 재등록 |
| `POST_NOTIFICATIONS` | 변환·업로드·녹음 진행 알림 |
| `FOREGROUND_SERVICE` | WorkManager/서비스 FGS 기본 권한 |
| `FOREGROUND_SERVICE_DATA_SYNC` | recording Keep IO 및 dataSync 성격의 WorkManager 작업 |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` | 로컬 conversion의 mediaProcessing FGS |
| `INTERNET` | Google YouTube/Drive REST 및 analytics/crash HTTPS 전송 |
| `ACCESS_NETWORK_STATE` | 네트워크 상태 확인 |
| `ACCESS_WIFI_STATE` | Wi-Fi 상태 확인 |
| `CHANGE_WIFI_MULTICAST_STATE` | desktopsync mDNS 구현 보존 영역의 multicast lock 지원 |

### FGS/alarm 관련 컴포넌트

- `androidx.work.impl.foreground.SystemForegroundService`: `mediaProcessing|dataSync`; ConversionWorker는 API 34+에서 `mediaProcessing`, API 29~33에서 `dataSync`, YouTube/Drive upload worker는 `dataSync`를 사용한다.
- API 34 `mediaProcessing` 선택은 Kotlin 변경 대상이 아니다. 공식 `ServiceInfo` 근거에 따라 이전 platform version에서도 이 type을 사용할 수 있으며, `Service.onTimeout` callback은 API 35 이전에는 호출되지 않는다. API 34 이하에서 `onTimeout`을 기대하지 않는다는 점은 문서화 리스크로 남긴다.
- `.record.RecordingService`: `microphone|dataSync`; capture에는 microphone, Keep 후속 IO에는 dataSync 경로가 사용된다. microphone FGS는 `RECORD_AUDIO` 허용과 visible/user-eligible start 조건을 함께 확인해야 한다.
- `.desktopsync.PairingServerService`: `dataSync`로 선언되어 있으나 1.0에서는 Options에 노출하지 않고 production caller도 두지 않는 dormant 보존 구현이다. 활성 기능이나 demo 대상처럼 제시하지 않는다.
- `.record.ScheduledRecordingReceiver`: explicit PendingIntent로 scheduled recording 및 Quick Timer START/STOP을 받는다.
- `.record.BootRecordingScheduleReceiver`: `BOOT_COMPLETED` 뒤 활성 schedule START 알람을 재등록한다.
- `.record.RecordingTileService`와 `.record.QuickRecordWidgetReceiver`: 사용자 quick-record 진입점이다. FGS 선언 자체는 없고 RecordingController를 통해 RecordingService를 호출한다.
- `SCHEDULE_EXACT_ALARM`은 Manifest에 유지되며, scheduler는 권한 상태에 따라 exact alarm 등록 성공 여부를 판단한다.

### Backup policy inventory

현재 유지해야 하는 제외 항목은 아래 6개이며, legacy entitlement 항목도 보존한다.

| domain | path |
|---|---|
| `file` | `datastore/pairing_token.preferences_pb` |
| `file` | `datastore/entitlement_token.preferences_pb` |
| `sharedpref` | `crash_reporter.xml` |
| `sharedpref` | `youtube_auth.xml` |
| `sharedpref` | `drive_auth.xml` |
| `sharedpref` | `install_id.xml` |

`backup_rules.xml`과 `data_extraction_rules.xml`의 `cloud-backup` 및 `device-transfer`가 위 parity를 가져야 한다. Auth state와 install identifier를 기기 복원으로 재사용하지 않고, 새 설치에서 새 상태/새 install ID를 만들 수 있게 하는 정책이다.

### Baseline and static assertion commands

다음 baseline SHA256은 Round 1 직전 snapshot 기록이다. 아래 명령은 PowerShell에서 실행하며 `&&`를 사용하지 않는다.

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath "app/src/main/AndroidManifest.xml"
Get-FileHash -Algorithm SHA256 -LiteralPath "app/src/main/res/xml/backup_rules.xml"
Get-FileHash -Algorithm SHA256 -LiteralPath "app/src/main/res/xml/data_extraction_rules.xml"
```

6개 `(domain,path)` set과 `backup_rules.xml` 및 `data_extraction_rules.xml`의 cloud/device-transfer parity를 확인하는 정적 assertion이다.

```powershell
$expected = @(
    "file|datastore/pairing_token.preferences_pb",
    "file|datastore/entitlement_token.preferences_pb",
    "sharedpref|crash_reporter.xml",
    "sharedpref|youtube_auth.xml",
    "sharedpref|drive_auth.xml",
    "sharedpref|install_id.xml"
) | Sort-Object
if ($expected.Count -ne 6 -or @($expected | Select-Object -Unique).Count -ne 6) { throw "expected set must contain exactly six unique entries" }
if (@($expected | Where-Object { $_ -match '[*?]' }).Count -ne 0) { throw "wildcards are not allowed in expected exclusion set" }

function Get-ExcludeSet($xml, $xpath) {
    @(Select-Xml -Xml $xml -XPath $xpath | ForEach-Object {
        "$($_.Node.domain)|$($_.Node.path)"
    }) | Sort-Object
}

$backup = [xml](Get-Content -Raw -LiteralPath "app/src/main/res/xml/backup_rules.xml")
$backupActual = Get-ExcludeSet $backup "/full-backup-content/exclude"
if (($backupActual -join "`n") -ne ($expected -join "`n")) { throw "backup_rules exclusion mismatch" }

$extraction = [xml](Get-Content -Raw -LiteralPath "app/src/main/res/xml/data_extraction_rules.xml")
foreach ($section in @("cloud-backup", "device-transfer")) {
    $actual = Get-ExcludeSet $extraction "/data-extraction-rules/$section/exclude"
    if (($actual -join "`n") -ne ($expected -join "`n")) { throw "$section exclusion mismatch" }
}
"PASS: six exclusions and cloud/device-transfer parity"
```

Merged Manifest는 ADB runtime 검사가 아닌 Gradle static assertion으로 확인한다. 실행된 release merge 산출물에서 expected permission과 FGS type을 검사하는 명령은 다음과 같다.

```powershell
$expectedPermissions = @(
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE"
) | Sort-Object
if ($expectedPermissions.Count -ne 16 -or @($expectedPermissions | Select-Object -Unique).Count -ne 16) { throw "expected Manifest permission set must contain 16 unique entries" }
if (@($expectedPermissions | Where-Object { $_ -match '[*?]' }).Count -ne 0) { throw "Manifest permission set cannot contain wildcards" }

$sourcePath = "app/src/main/AndroidManifest.xml"
$source = [xml](Get-Content -Raw -LiteralPath $sourcePath)
$sourcePermissionNodes = @($source.manifest.'uses-permission')
$sourcePermissions = @($sourcePermissionNodes | ForEach-Object { $_.name }) | Sort-Object
if (($sourcePermissions -join "`n") -ne ($expectedPermissions -join "`n")) { throw "source Manifest permission set mismatch" }

function Assert-PermissionMaxSdk {
    param([array]$Nodes, [string]$Label)
    $write = @($Nodes | Where-Object { $_.name -eq "android.permission.WRITE_EXTERNAL_STORAGE" })
    $read = @($Nodes | Where-Object { $_.name -eq "android.permission.READ_EXTERNAL_STORAGE" })
    if ($write.Count -ne 1 -or "$($write[0].maxSdkVersion)" -ne "28") { throw "${Label}: WRITE_EXTERNAL_STORAGE maxSdkVersion must be 28" }
    if ($read.Count -ne 1 -or "$($read[0].maxSdkVersion)" -ne "32") { throw "${Label}: READ_EXTERNAL_STORAGE maxSdkVersion must be 32" }
    foreach ($node in $Nodes) {
        if ($node.name -notin @("android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE") -and "$($node.maxSdkVersion)" -ne "") {
            throw "${Label}: $($node.name) must not declare maxSdkVersion"
        }
    }
}

if ($sourcePermissions -contains "android.permission.USE_EXACT_ALARM") { throw "USE_EXACT_ALARM must be absent" }
Assert-PermissionMaxSdk -Nodes $sourcePermissionNodes -Label "source Manifest"

$gradle = Get-Content -Raw -LiteralPath "app/build.gradle.kts"
if ($gradle -notmatch 'namespace\s*=\s*"com\.example\.convert2video"') { throw "namespace mismatch" }
if ($gradle -notmatch 'applicationId\s*=\s*"com\.convert2video"') { throw "applicationId mismatch" }

$expectedServices = @{
    "androidx.work.impl.foreground.SystemForegroundService" = @("mediaProcessing", "dataSync")
    ".record.RecordingService" = @("microphone", "dataSync")
    ".desktopsync.PairingServerService" = @("dataSync")
}
$sourceServices = @($source.manifest.application.service)
foreach ($entry in $expectedServices.GetEnumerator()) {
    $service = $sourceServices | Where-Object { $_.name -eq $entry.Key }
    if (-not $service) { throw "source Manifest missing service $($entry.Key)" }
    $types = @($service.foregroundServiceType -split "\|")
    foreach ($type in $entry.Value) { if ($type -notin $types) { throw "$($entry.Key): missing $type" } }
}

$merged = @(Get-ChildItem -LiteralPath "app/build/intermediates" -Filter "AndroidManifest.xml" -Recurse |
    Where-Object { $_.FullName -match "googleRelease.*processGoogleReleaseMainManifest|huaweiRelease.*processHuaweiReleaseMainManifest" })
if ($merged.Count -ne 2) { throw "expected two merged release manifests" }
foreach ($file in $merged) {
    $manifest = [xml](Get-Content -Raw -LiteralPath $file.FullName)
    $permissionNodes = @($manifest.manifest.'uses-permission')
    $permissions = @($permissionNodes | ForEach-Object { $_.name })
    if ($permissions -contains "android.permission.USE_EXACT_ALARM") { throw "$($file.Name): USE_EXACT_ALARM must be absent" }
    foreach ($permission in $expectedPermissions) {
        if ($permission -notin $permissions) { throw "$($file.Name): missing $permission" }
    }
    Assert-PermissionMaxSdk -Nodes $permissionNodes -Label $file.Name
    $services = @($manifest.manifest.application.service)
    foreach ($entry in $expectedServices.GetEnumerator()) {
        $service = $services | Where-Object { $_.name -eq $entry.Key -or $_.name -eq ("com.example.convert2video" + $entry.Key) }
        if (-not $service) { throw "$($file.Name): missing service $($entry.Key)" }
        $types = @($service.foregroundServiceType -split "\|")
        $actualTypes = @($types | Sort-Object -Unique)
        $expectedTypes = @($entry.Value | Sort-Object -Unique)
        if (($actualTypes -join "|") -ne ($expectedTypes -join "|")) { throw "$($file.Name): $($entry.Key) FGS type projection mismatch" }
    }
}
"PASS: full 16-permission semantic projection, identifiers, service types, and USE_EXACT_ALARM absence"
```

Contract scope는 다음 pre/post PowerShell audit 명령으로 확인한다. `$baselineScope`는 Harness execution record에서 옮긴 pre 배열이며, post는 그 기존 dirty 상태를 보존한 네 개의 Phase 9 경로여야 한다. 기존 dirty 변경은 baseline 목록과 대조해 보존 여부를 확인한다. `HEAD` 전체 diff만으로 baseline을 판정하지 않으며, docs가 untracked여도 `--untracked-files=all`로 포함한다.

```powershell
$contractPaths = @(
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/xml/backup_rules.xml",
    "app/src/main/res/xml/data_extraction_rules.xml",
    "docs/phase9-play-console-preparation.md"
)

# This exact pre array is transcribed from the Harness execution record;
# it is not reconstructed from HEAD and is retained in the document audit.
$baselineScope = @(
    " M app/src/main/AndroidManifest.xml",
    " M app/src/main/res/xml/backup_rules.xml",
    " M app/src/main/res/xml/data_extraction_rules.xml"
)
$preScope = @($baselineScope)
Write-Output "PRE (Harness baseline execution record)"
$preScope

# Apply only the Contract changes, then run the post command.
$postScope = @(git status --short --untracked-files=all -- $contractPaths)
Write-Output "POST (expected baseline statuses plus new docs)"
$postScope

$expectedPostScope = @($baselineScope + "?? docs/phase9-play-console-preparation.md") | Sort-Object
$actualPostScope = @($postScope | Sort-Object)
if (($actualPostScope -join "`n") -ne ($expectedPostScope -join "`n")) {
    throw "scope status differs from Harness baseline plus the required untracked docs path"
}

$phase9Paths = $contractPaths
$scopePaths = @($postScope | ForEach-Object { $_.Substring(3).Trim() } | Sort-Object -Unique)
if (($scopePaths -join "`n") -ne (($phase9Paths | Sort-Object) -join "`n")) {
    throw "scope path set must equal the exact four Contract paths"
}
if ($scopePaths -notcontains "docs/phase9-play-console-preparation.md") {
    throw "untracked docs path must be included in the post scope"
}
```

Expected post scope is the exact four paths in `$contractPaths`: the three pre-existing dirty entries from `$baselineScope` plus the newly created untracked docs path. The status-array comparison throws on any status/path drift, and the normalized path-set comparison independently proves that no contract path is missing or extra; known pre-existing dirty paths outside this four-path command remain compared against the Harness baseline record, not discarded. This audit does not use `HEAD` as the baseline.

## Data safety draft

Play Data safety 입력은 실제 배포 artifact의 모든 variant와 backend/privacy policy 확인을 반영해 최종 작성해야 한다. 아래는 현재 코드에서 확인한 동작과 미확정 값을 분리한 초안이다.

| 데이터/처리 | 수집·처리 및 목적 | 공유/recipient | 저장·보관·삭제 | 전송 암호화 | 선택성/동의 | 확인 필요 |
|---|---|---|---|---|---|---|
| 오디오, 배경 이미지, 결과 video | core conversion을 위해 기기에서 처리하고 결과를 기기 저장소/MediaStore에 저장 | core conversion에서는 외부 recipient 없음 | 앱 private files와 MediaStore에 저장. 사용자가 제공된 삭제 기능으로 삭제; OS/MediaStore 보관·완전 삭제 semantics는 최종 policy 확인 | 외부 전송 없음 | conversion에 사용자가 선택한 입력; 기능 핵심 | Play form에서 local-only 처리를 collected data로 오인하지 않되, 선택적 upload가 있는 실제 practice는 별도 신고 |
| YouTube upload 대상 video와 metadata | 사용자가 선택한 video를 수동 upload하거나, Google 인증과 설정 토글이 허용한 recording 후 자동 경로에서 upload | Google YouTube Data API/Google 계정. Google이 최종 video를 보관 | upload 결과는 Google 계정/YouTube 정책에 따라 보관. 앱의 local upload history와 결과 삭제는 앱 기능에 따름 | Google API는 HTTPS 사용 | Google OAuth와 upload action/설정 토글에 따른 선택 기능 | 공개 범위, Google retention, 앱 privacy policy 문구 확인 |
| Drive upload 대상 recording과 metadata | Drive 자동 upload가 authorized이고 설정된 경우 recording을 Google Drive에 업로드 | Google Drive API/사용자 Google 계정 | Google Drive의 사용자가 관리하는 파일 보관; 앱 local upload history 삭제와 Drive 원격 파일 삭제의 관계는 확인 필요 | Google API는 HTTPS 사용 | Google OAuth 및 Drive auto-upload 설정에 따른 선택 기능 | 실제 배포 flavor와 Drive retention/delete 문구 확인 |
| analytics event와 `install_id` | `app_open`, conversion started/completed 등 event와 설치별 익명 UUID로 사용량·기능 상태 파악 | `POST /events`의 `BACKEND_BASE_URL` 수신 서버. 현재 주소가 `https://TODO.example`라 실제 recipient는 미확정 | install ID는 `install_id.xml`에 local 저장되며 backup/device-transfer에서 제외. backend event retention/delete는 미확정 | 구현은 HTTPS endpoint를 사용하도록 되어 있으나 실제 endpoint 운영·인증은 확인 필요 | 별도 opt-in UI는 현재 확인되지 않음; language prompt 이후 app-open eligibility 등 코드 동작을 최종 consent 문구와 대조 | backend owner, retention, deletion request, privacy policy, consent/legal basis 확인 |
| crash report와 crash stack trace | 처리되지 않은 예외의 다음 실행 flush를 위해 stack trace를 최대 16KiB 범위로 pending 저장 | `POST /crash`의 backend 수신 서버. 현재 recipient와 운영 retention은 미확정 | `crash_reporter.xml` pending 값은 backup/device-transfer에서 제외; 성공 flush/blank 처리 후 local pending 제거. backend retention/delete는 미확정 | 구현은 HTTPS endpoint를 사용하도록 되어 있으나 endpoint 운영 확인 필요 | crash 후 자동 보고 경로이며 별도 per-event opt-in은 현재 확인되지 않음 | stack trace의 개인정보 포함 가능성, backend 접근·보관·삭제·privacy policy 확인 |
| YouTube/Drive account email 및 OAuth auth state | Options 표시와 authorized/channel/folder 상태 복원에 account email, channel title, authorized flag, Drive folder state를 사용 | OAuth는 Google 인증 서비스와 API recipient를 사용; 앱은 local auth preferences를 사용 | `youtube_auth.xml`/`drive_auth.xml`에 local auth state를 저장하고 backup/device-transfer에서 제외. 로그아웃 시 앱 local state를 clear하는 동작을 최종 확인 | Google OAuth/API는 HTTPS; access token의 실제 lifetime·storage는 Google Identity와 코드 계약을 확인 | 사용자가 해당 Google 기능을 선택하고 동의한 경우에만 사용; Huawei gateway는 no-op 정책 | token이 메모리 외에 어디까지 저장되는지, Google retention, account deletion/privacy policy 확인 |
| `install_id` 단독 식별자 | 계정 ID가 아닌 설치별 익명 UUID; analytics/crash payload correlation 용도 | `/events` 및 `/crash` backend recipient | `install_id.xml` local 저장; backup/device-transfer 제외. 앱 데이터 삭제/재설치 시 재생성 예정 semantics 확인 | payload 전송은 HTTPS 전제; 현재 TODO endpoint 때문에 운영 확인 필요 | analytics/crash 자동 경로와 함께 동작; 사용자 consent/opt-out은 미확정 | 익명성 평가, backend retention/deletion, privacy policy와 Data safety 분류 확인 |

Local-only processing 자체는 Play form에서 “collected”로 신고할 필요가 없을 수 있으나, 선택적 Google upload와 analytics/crash 전송이 동시에 존재하므로 전체 data practice를 정확히 신고해야 한다. 이 표의 `확인 필요` 항목은 backend/privacy-policy owner의 확인 없이는 제출하지 않는다.

## Permissions and FGS declarations

### Permission/사용자 영향 초안

- `RECORD_AUDIO`: 녹음 시작 전에 runtime grant가 필요하다. 거부하면 일반 녹음과 scheduled/background capture를 시작하지 않고 사용자에게 짧은 실패 안내를 제공한다.
- `READ_MEDIA_AUDIO`/`READ_EXTERNAL_STORAGE`: Listen/AudioPick의 MediaStore 조회에만 사용한다. 시스템 picker 폴백 경로와 API 경계를 listing 설명에 맞춘다.
- `WRITE_EXTERNAL_STORAGE`: API 28 이하 compatibility declaration이며 API 29+에는 적용되지 않는다.
- `POST_NOTIFICATIONS`: 진행 알림 표시를 위해 사용한다. 알림 거부 시 작업 자체와 알림 가시성의 사용자 영향을 별도로 확인한다.
- `INTERNET` 및 network/Wi-Fi 권한: Google API와 analytics/crash outbound 및 네트워크 상태 처리에 사용한다. 일반 Android permission dialog 대상 여부와 별개로 Data safety 설명과 일치시킨다.
- `MODIFY_AUDIO_SETTINGS`: Bluetooth SCO 라우팅용 normal install-time permission이다. `BLUETOOTH_CONNECT`와 `READ_PHONE_STATE`는 선언하지 않는다.
- `SCHEDULE_EXACT_ALARM`: 아래 Exact alarm decision을 따른다.
- FGS type permissions와 base `FOREGROUND_SERVICE`: 아래 FGS row와 merged manifest를 함께 확인한다.
- `RECEIVE_BOOT_COMPLETED`: 부팅 후 enabled scheduled recording의 START 재등록에 사용한다.

### FGS Console rows

| 기능 | Manifest permission/type | 사용자 trigger | 지연·중단 시 영향 | demo video 시나리오 |
|---|---|---|---|---|
| microphone recording | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE`; `RecordingService` `microphone` | Record 화면 Start, Quick Settings tile/widget, 사용자가 미리 설정한 scheduled recording | 녹음이 시작되지 않거나 중단되고 저장 가능한 audio가 생성되지 않는다. `RECORD_AUDIO` 미허용이면 시작을 거부한다. visible/user-eligible start 조건은 Android 버전별로 검증 필요 | Record 화면에서 권한 허용 → Start → 화면 잠금/알림 확인 → Pause/Stop → 저장 결과 확인; 별도 scheduled recording은 user grant 후 locked 상태에서 재현 |
| dataSync recording Keep/backup/import/export/network transfer | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`; `RecordingService`의 Keep IO와 WorkManager upload worker `dataSync` | 녹음 Stop 후 Keep, 설정된 recording backup, 사용자가 시작한 YouTube/Drive upload 또는 설정된 auto-upload | Keep/index/upload가 중단되면 저장·이력·원격 전송이 완료되지 않거나 재시도/실패 상태가 된다. backup worker 자체는 별도 FGS로 승격하지 않는 것으로 확인 | 녹음 Stop → Keep 완료 확인; Google flavor에서 인증 후 YouTube/Drive upload 시작 → progress notification → 완료/취소/timeout 결과 확인; backup 연결/상태 전이는 별도 기록 |
| mediaProcessing local conversion | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROCESSING`; `SystemForegroundService` merged type | Convert 화면에서 audio와 background를 선택하고 Start conversion | 결과 video가 생성되지 않거나 conversion state가 실패/취소로 끝난다. API 34+에서는 mediaProcessing, API 29~33에서는 현재 코드의 dataSync fallback을 확인한다. `Service.onTimeout`은 API 35+에만 사용 가능하다 | audio/background 선택 → Convert 시작 → progress notification 확인 → 백그라운드 이동 → 완료 video 및 목록 확인 → 취소와 장시간 timeout을 각각 재현 |

`PairingServerService`는 Manifest에 `dataSync`로 선언되어 있지만 1.0 dormant 보존 구현이다. Play listing, FGS demo, 사용자-facing active feature로 표현하지 않는다.

## Exact alarm decision

- `SCHEDULE_EXACT_ALARM`을 유지한다. `USE_EXACT_ALARM`으로 변경하지 않는다.
- 이 앱의 exact alarm 사용처는 사용자가 설정하는 scheduled recording과 Quick Timer이다. 정확한 시각의 녹음 시작/종료라는 사용자-facing 기능은 있지만, 알람/캘린더가 앱의 core product가 아니므로 자동 부여되는 restricted `USE_EXACT_ALARM` 선택은 적절하지 않다.
- `SCHEDULE_EXACT_ALARM`은 user-granted special access이며 Options 배너/시스템 설정에서 사용자가 허용 또는 거부한다. 거부 시 scheduler는 stale exact alarm을 정리하고 새 등록을 성공으로 가장하지 않아야 한다.
- Deny 상태에서 scheduled recording/Quick Timer가 실행되지 않는 사용자 영향, 권한 허용 후 재등록, locked/background capture는 실기기에서 별도 확인한다.
- 이 문서는 정책 적합성 승인이나 Play 심사 결과를 주장하지 않는다.

## Console prerequisites

현재 값이 미확정이거나 외부 확인이 필요한 항목은 임의 값을 채우지 않는다. 모든 미해결 항목은 `TBD | TBD | TBD` placeholder로 남긴다.

| 항목 | 상태 | Owner | Due date |
|---|---|---|---|
| 최종 product name / Play listing 명칭 | TBD | TBD | TBD |
| 최종 Play 등록 applicationId 확인 | TBD | TBD | TBD |
| privacy policy URL 및 실제 공개 페이지 | TBD | TBD | TBD |
| Play Console 앱 생성 및 package 연결 | TBD | TBD | TBD |
| Data safety form 최종 입력 및 backend/privacy 확인 | TBD | TBD | TBD |
| Content rating questionnaire | TBD | TBD | TBD |
| Target audience/아동 대상 여부 | TBD | TBD | TBD |
| Ads declaration | TBD | TBD | TBD |
| app access instructions (인증/기능 제한이 있는 경우) | TBD | TBD | TBD |
| Google flavor OAuth debug SHA-1 | TBD | TBD | TBD |
| Google flavor release/upload/Play App Signing SHA-1 | TBD | TBD | TBD |
| Google OAuth package name 및 SHA-1 client 등록 | TBD | TBD | TBD |
| release/upload keystore와 안전한 보관 | TBD | TBD | TBD |
| production `BACKEND_BASE_URL` 및 `/events`·`/crash` 운영 endpoint | TBD | TBD | TBD |
| backend retention, deletion request, crash privacy 문구 | TBD | TBD | TBD |
| FGS별 demo video 녹화·업로드 | TBD | TBD | TBD |
| 내부 테스트 트랙 준비 및 tester 목록 | TBD | TBD | TBD |
| store listing screenshots/icon/feature graphic | TBD | TBD | TBD |

확인된 값인 `namespace`, 현재 `applicationId`, `compileSdk/targetSdk 36`은 위 unresolved placeholder와 별개로 Current code inventory의 inspected facts다. Google OAuth는 debug, local release/upload key, Play App Signing certificate의 package/SHA-1을 각각 확보한 뒤 등록해야 한다.

## Runtime verification matrix

Merged Manifest static assertion은 Gradle 산출물을 대상으로 실행하며 PASS로 기록한다. ADB가 필요한 runtime 행만 `Not run because adb devices empty`로 표시한다. 아래 expected는 코드/정책 acceptance target이며 실행 결과가 아니다.

| Precondition | Action | Expected | Evidence | Status |
|---|---|---|---|---|
| Google/Huawei merged manifest task 실행 가능 | `processGoogleReleaseMainManifest` 및 `processHuaweiReleaseMainManifest` 실행, merged XML static assertion | Manifest permissions와 SystemForegroundService/RecordingService FGS types가 expected set과 일치 | Gradle task output와 merged manifest artifact | PASS (static assertion executed) |
| Cold start, no pending language/permission blocker | 앱 cold start 후 Home 진입 | crash 없이 시작되고 current applicationId/label/3-tab shell이 표시됨 | adb logcat + screen recording | Not run because adb devices empty |
| API 31+ exact alarm settings, `SCHEDULE_EXACT_ALARM` denied | Options에서 exact alarm CTA를 열고 거부/뒤로 이동 | 등록 성공을 가장하지 않고 scheduled recording/Quick Timer는 pending/실패 안내를 유지 | logcat, UI state, alarm inspection | Not run because adb devices empty |
| API 31+ exact alarm settings, user grant | 권한 허용 후 schedule/Quick Timer 등록 및 reboot 재등록 | exact start/stop이 등록되고 grant 뒤 재시도 가능하며 boot receiver가 enabled START를 재등록 | alarm dump, notification, recording output | Not run because adb devices empty |
| `RECORD_AUDIO` granted, user-visible Record start | Start → 화면 잠금 → Stop/Keep | microphone FGS가 user-eligible 경로에서 유지되고 audio/Review/Keep 결과가 생성됨 | notification, logcat, MediaStore/file output | Not run because adb devices empty |
| `RECORD_AUDIO` granted, scheduled alarm accepted, device locked | scheduled START 시각까지 잠금 유지 | background microphone start 정책에 맞게 실행되거나 짧은 오류로 실패하며 silent false success가 없음 | alarm/FGS notification, logcat, recording row | Not run because adb devices empty |
| audio/background selected, valid local files | conversion 시작 후 완료 대기 | WorkManager conversion이 mediaProcessing/dataSync 경계를 지키고 result video와 history를 저장 | WorkInfo, notification, MediaStore, history row | Not run because adb devices empty |
| conversion running | cancel action 또는 WorkManager cancel | conversion이 취소되고 사용자에게 stack 없이 상태가 표시되며 partial output 정리가 정책대로 동작 | WorkInfo cancelled, UI message, file list | Not run because adb devices empty |
| API 35+ long-running conversion | mediaProcessing timeout 조건까지 실행 | timeout/stop 후 작업이 정리되고 completion을 가장하지 않으며 사용자 fallback이 표시됨; API 34 이하에서는 `Service.onTimeout`을 전제하지 않음 | WorkInfo, logcat, notification, output | Not run because adb devices empty |
| Google OAuth authorized, valid YouTube selection | YouTube upload 시작 후 완료 | HTTPS upload와 upload history가 성공하고 결과 UI가 완료 상태가 됨 | WorkInfo, API result, history row, notification | Not run because adb devices empty |
| Google OAuth authorized, valid YouTube/Drive upload running | cancel 또는 network timeout 유도 | upload가 완료로 오인되지 않고 cancel/failed fallback과 history 상태가 일치함 | WorkInfo, notification, logcat, history row | Not run because adb devices empty |
| Google auth state exists before app data restore | backup/restore 또는 reinstall 시나리오 확인 | excluded `youtube_auth.xml`/`drive_auth.xml`은 자동 복원되지 않고 re-auth가 필요함 | backup/restore artifact, prefs inspection, UI | Not run because adb devices empty |
| `install_id.xml` exists, then app data clear/reinstall | app data clear 또는 reinstall 후 analytics path 확인 | old install ID가 backup/device-transfer로 이어지지 않고 새 install ID가 생성됨 | prefs value comparison, `/events` payload inspection | Not run because adb devices empty |
| PairingServerService declared but no 1.0 caller | cold start/Options 진입 | service가 자동 시작되지 않고 Options에 active desktop sync feature가 노출되지 않음 | dumpsys service, screen recording, logcat | Not run because adb devices empty |

## Release gates

- [x] Contract 변경 파일은 정확히 `AndroidManifest.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `phase9-play-console-preparation.md` 네 개뿐이다 (Round 3 verification record).
- [x] Manifest는 기준선 대비 XML element/attribute/permission/service/applicationId/namespace 변경 없이 comments-only delta다 (Round 3 verification record).
- [x] 두 backup rule 파일은 6개 exclusion parity를 만족한다 (Round 3 verification record).
- [x] `:app:compileGoogleDebugKotlin` — PASS (Round 3 verification record).
- [x] `:app:compileHuaweiDebugKotlin` — PASS (Round 3 verification record).
- [x] `:app:compileDebugKotlin` — PASS (Round 3 verification record).
- [x] `processGoogleReleaseMainManifest` — PASS (Round 3 verification record).
- [x] `processHuaweiReleaseMainManifest` — PASS (Round 3 verification record).
- [x] `.cursor/hooks/run-stop-checks.ps1` — PASS, exit 0 (Round 3 verification record).
- [x] XML parity assertion — PASS (Round 3 verification record).
- [x] docs structure/static assertions — PASS (Round 3 verification record).
- [x] Manifest semantic projection assertion — PASS (Round 3 verification record).
- [ ] ADB runtime verification — Not run because adb devices empty.
- [ ] Play Console submission/staging — Not run; this Phase 9 does not submit or stage.
- [ ] OAuth package/SHA-1 registration — TBD; Google debug/release/Play signing fingerprints unresolved.
- [ ] Privacy policy/backend confirmation — TBD; URL, retention, deletion, consent and production endpoint unresolved.
- [ ] Release/upload keystore gate — TBD; keystore ownership and secure storage unresolved.
- [ ] targeted diff에서 non-contract 파일 변경, Kotlin/Gradle/Room/Worker/billing/telemetry/strings 변경, target/application identifier 변경이 없다.
- [ ] 문서의 9개 section, permission inventory, FGS rows, exact alarm rationale, unresolved `TBD | TBD | TBD`, 공식 URL과 checked date가 모두 존재한다.
- [ ] 실기기 matrix가 실제 실행되기 전까지 release sign-off를 하지 않는다. ADB-dependent runtime status는 `Not run because adb devices empty`다.
- [ ] `BACKEND_BASE_URL=https://TODO.example`, privacy policy URL, OAuth fingerprints, keystore가 해결되기 전 Play Console 제출을 하지 않는다.
- [ ] 이 Phase 9 작업에서는 Play Console 제출, 스테이징, commit, stage를 수행하지 않는다.

## Official sources

Checked date: **2026-09-05**. 아래는 정책/플랫폼 확인용 공식 문서이며, 제출 직전에 최신 내용을 다시 확인한다.

- [Android exact alarms](https://developer.android.com/develop/background-work/services/alarms) — exact alarm의 user-facing requirement와 `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` 구분.
- [Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission) — `SCHEDULE_EXACT_ALARM`, FGS 및 permission protection level 확인.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) — microphone, data sync, media processing type과 사용자 영향 확인.
- [ServiceInfo API reference](https://developer.android.com/reference/android/content/pm/ServiceInfo) — `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`이 이전 platform version에서도 사용 가능하다는 점과 API 35 `Service.onTimeout` 경계 확인.
- [Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup) — `full-backup-content`와 backup exclusion 형식.
- [Changes to backup and restore](https://developer.android.com/about/versions/12/backup-restore) — `data-extraction-rules`, cloud backup, device transfer 구분.
- [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en) — Data safety form과 정확한 collection/sharing 선언 책임.
- [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) — target API 36 제출 기준 확인.
- [Google Play target API level policy](https://support.google.com/googleplay/android-developer/answer/16561298?hl=en) — target API 정책 원문 확인.
- [Google Play services client authentication](https://developers.google.com/android/guides/client-auth) — Android package name 및 signing certificate SHA-1 선행조건.

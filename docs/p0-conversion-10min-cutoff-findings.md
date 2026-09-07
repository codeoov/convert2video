# P0 — 10분 변환 컷오프 진단 결과

**계획 문서:** `plan-blueprint-pro-billing.md` § P0
**분석일:** 2026-09-08 (Claude Code, 기존 캡처 로그 재분석)
**결론: 재현 실패 — 로그상 미확정. `ConversionWorker`/`VideoConverter` 수정 금지 조건 유지.**

---

## 1. 분석한 캡처

| 파일 | 내용 | 판정 |
|---|---|---|
| `p0-conversion-repro.log` (56,020줄) | 09-05 15~17시대 전체 시스템 logcat (`-b all`) | **변환 활동 0건** |
| `p0-short-conversion.log` (6,091줄) | 09-05 17:45 짧은 오디오 변환 1회 | 정상 성공 (대조군) |
| `p0-window.xml` | 변환 화면 `uiautomator dump` | 참고용 |

### `p0-short-conversion.log` — 대조군 (정상)
```
17:45:05.197  WM-WorkerWrapper: Starting work for ...video.ConversionWorker
17:45:05.235  WM-SystemFgDispatcher: Started foreground service ACTION_START_FOREGROUND
17:45:05.238  notify(1001, channel=conversion_progress, flags=ONGOING_EVENT|FOREGROUND_SERVICE)
17:45:06.696  WM-WorkerWrapper: Worker result SUCCESS   ← 약 1.5초
```
짧은 변환은 FGS 승격 → 진행 알림 → SUCCESS까지 정상. 워터마크 게이트/저장 경로 문제 없음.

### `p0-conversion-repro.log` — 재현 실패
- `ConversionWorker` / `conversion_progress` / `SystemForegroundService`(convert2video) / `Transformer` / `MediaCodec` / `ExportException` **전부 0건**. 이 로그에는 변환이 아예 없다.
- convert2video 관련 이벤트는 GMS `datatransport` 분석 잡(`JobInfoSchedulerService`)과 캐시 프로세스 사망뿐.
- 16:29:49~55 구간에 `am_mem_factor`가 `[1,2]↔[2,1]↔[3,1]`로 급진동(임계 메모리 압박), `killinfo` 200건, `am_proc_died` 40여 개 앱 동시 사망(binance/gms/gmail/calendar/…).
- 그 와중에 convert2video도 **adj 945(= CACHED/empty 프로세스)** 상태로 `lmkd`(pid 994)에 함께 수거됨:
  ```
  16:29:54.522  am_mem_factor: [3,1]
  16:29:54.574  killinfo: [18414,10679, ... ]      ← lmkd가 convert2video(uid 10679) 선정
  16:29:54.603  am_proc_died: [0,18414,com.example.convert2video,0,8,241,4119]
  ```
- 이후 17:40 `adb shell am force-stop` + Android Studio 재배포. → 사용자가 세션을 수동 종료한 흔적.

**즉, 이 캡처는 "백그라운드 유휴 상태의 앱이 기기 메모리 압박으로 LMK 수거된" 장면이며 변환 컷오프와 무관하다.**

---

## 2. 미해결 — 재현 조건 재수집 필요

계획 P0의 종료 조건("OS/OEM kill · Media3 오류 · 앱 예외 중 하나로 좁히거나 '로그상 미확정' 명시") 중 **"로그상 미확정"** 에 해당. 결제 기능과 독립적으로 남긴다.

### 재수집 레시피 (S22 Ultra, 무선 ADB)
```powershell
$log = Join-Path $env:TEMP 'c2v-10min-repro.log'
adb logcat -c
# 별도 창에서 계속 실행:
adb logcat -b all -v threadtime `
  ActivityManager:I lowmemorykiller:I WM-WorkerWrapper:D WM-SystemFgDispatcher:I `
  Transformer:V MediaCodec:W ExoPlayerImpl:W ConversionWorker:V `
  ActivityManager:W ActivityThread:W *:E > $log

# 그 다음 앱에서 10분 넘는 오디오로 변환 시작.
# 화면 끄고 방치. 실패(약 10분 지점)까지 기다렸다가 Ctrl+C.

rg -n 'ExportException|MediaCodec|OutOfMemory|lmkd|lowmemorykiller|killinfo.*10679|am_kill|ANR in com.example.convert2video|Foreground service .* timed out|JobScheduler|stopReason|onStopJob|Reason=TIMEOUT|SEM_CLIST|PowerGenie|ProcessManager|deep sleep|App Standby' $log
```
캡처 시 함께 기록: 실패 시각(초 단위), 화면 on/off, 배터리 최적화 설정(설정 > 앱 > convert2video > 배터리 = 제한 없음 여부), 진행 알림 생존 여부, 마지막 진행 %.

---

## 3. 가설 우선순위 (재수집 로그로 검증 대상)

1. **WorkManager/JobScheduler 10분 실행 상한 (최유력).**
   `ConversionWorker`는 `doWork()` 시작 시 `setForeground()`로 FGS 승격을 시도한다. 이 승격이 어떤 이유로든 유지되지 않으면(OEM의 FGS 타입 거부, `mediaProcessing` 런타임 타입과 매니페스트 `mediaProcessing|dataSync` 불일치, Android 14+ `FOREGROUND_SERVICE_MEDIA_PROCESSING` 시간 제한, 승격 예외 무시) 잡이 일반 JobScheduler 잡으로 강등되어 **정확히 10분** 후 `onStopJob(TIMEOUT)`으로 강제 종료된다. "10분"이라는 딱 떨어지는 경계가 이 가설을 강하게 지지.
   - 검증 신호: `WM-WorkerWrapper: Worker ... RESULT ... stopReason` / `JobServiceContext ... TIMEOUT` / `Foreground service did not finish` / `ActivityManager: Stopping service ... SystemForegroundService`.
   - Android 15(API 35) 타깃 + `FOREGROUND_SERVICE_MEDIA_PROCESSING`은 24시간당 6시간 상한이지 10분이 아님 → 이쪽이면 10분과 안 맞음. 단 `dataSync` 폴백 시 One UI가 더 짧게 자를 수 있음.

2. **Samsung One UI 백그라운드 제한 / PowerGenie / App Standby.**
   배터리 최적화가 켜져 있거나 앱이 "미사용" 버킷이면 화면 off 후 One UI가 FGS를 정리. 진행 알림이 있어도 OEM 정책으로 종료 가능.
   - 검증 신호: `PowerGenie` / `ProcessManager` / `SEM_CLIST` / `App Standby bucket` / `force stopping ... due to ...`.

3. **Media3 `Transformer` 정지/행 (long/VBR MP3).**
   긴 MP3의 EOF 처리(`AUDIO_CLIP_SAFETY_MARGIN_US`가 방어하려는 `Mp3Extractor` 문제) 또는 단일 세그먼트 장시간 export 중 코덱 자원 경합. 이 경우 경계가 "정확히 10분"으로 딱 떨어지지 않고 입력마다 달라야 함 → 우선순위 낮음.
   - 검증 신호: `ExportException` / `MediaCodec` `timed out` / `ExoPlayerImplInternal`.

4. **`am_mem_factor` 압박 하의 LMK (이번 캡처가 보여준 것).**
   변환 중 FGS면 adj가 낮아(≈200) LMK 대상에서 사실상 제외되므로, FGS가 유지된다면 이 경로는 아님. FGS 강등(가설 1)이 선행하면 그때 LMK 대상이 됨.

---

## 4. 조치

- **코드 변경 없음.** 계획 P0: "로그 없이 `ConversionWorker`나 `VideoConverter`를 수정하지 않는다."
- 원시 logcat(`p0-*.log`, `p0-window.xml`)은 `.gitignore` 처리(대용량 기기 로그는 저장소에 두지 않음).
- 다음 세션: 위 §2 레시피로 실패 로그 확보 → 가설 1부터 검증 → 필요 시 `ConversionWorker`의 FGS 타입/`setForeground` 재확인.

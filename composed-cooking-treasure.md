# Desktop Companion: mDNS Discovery + Pairing — Sprint Plan

> 이 문서는 Tauri 데스크톱 컴패니언 앱의 mDNS 장치 검색 및 페어링 스프린트 기획서입니다.

---

## 아키텍처 참고 (Architecture Notes)

### A-1. Ktor 3 Multipart 파일 업로드 한계

**기본값**: Ktor 3(CIO 엔진)의 `receiveMultipart()` 기본 폼 필드 한계는 **50MiB**다. 대용량 WAV 업로드를 지원하려면 반드시 `formFieldLimit`을 명시적으로 설정해야 한다.

**계약**: `receiveMultipart(formFieldLimit = 500L * 1024 * 1024)` — WAV 최대 500MiB 허용.

**구현 지침**:
- 파일 콘텐츠 읽기는 `ByteReadChannel` + `provider()` 패턴을 사용한다.
- 채널 사용 후 `dispose()` 호출로 리소스를 해제한다.
- 스트리밍 상한(`MAX_UPLOAD_BYTES = 500L * 1024 * 1024`)을 초과하면 즉시 연결을 끊는다.
- 임시 파일은 처리 완료 후 또는 오류 시 삭제한다.
- `streamProvider()`는 Ktor 3에서 deprecated — `provider()` + `ByteReadChannel`을 사용한다.

### A-2. Service 재시작과 페어링 상태 복구

**명확화**: Tauri/Android 앱 UI recreate(Activity 재생성, 언어 변경 등)는 `PendingPairingRequest`를 메모리에 유지한다. 그러나 Service 프로세스 종료(강제 종료, 배터리 최적화, OOM)는 **진행 중인 페어링을 취소**한다.

**복구 가능 상태**: 프로세스 재시작 후에는 DataStore/SharedPrefs에 저장된 **토큰 및 paired 상태만** 복구된다. 진행 중이던 PairingRequest는 복구되지 않으며, 상대방(데스크톱)이 재시도해야 한다.

**요약**:
- UI recreate → `PendingPairingRequest` 메모리 유지 ✅
- Service 프로세스 종료 → `PendingPairingRequest` 취소 (재시도 필요)
- 프로세스 재시작 후 복구 가능: token, paired peer 정보 (DataStore 영속)

---

## 스프린트 범위

### Phase 1 — mDNS 장치 검색 (Tauri Desktop)

**목표**: 동일 LAN의 Android 기기를 mDNS로 탐색해 데스크톱 UI에 표시.

**수정 파일**:
| 파일 | 변경 |
|------|------|
| `desktop/src-tauri/src/discovery.rs` | `ServiceDaemon` + blocking `recv()` + `Drop` lifecycle |
| `desktop/src-tauri/src/main.rs` | `use tauri::Manager` + window lifecycle cleanup |
| `desktop/src-tauri/Cargo.toml` | `mdns-sd = "=0.11.5"` 정확한 버전 핀 |
| `desktop/ui/app.js` | `instanceName()` suffix-strip 수정 + browser-error UX + pagehide stop |

**완료 기준**:
- `cargo check` exit 0 (E0308 / E0599 에러 없음)
- `ServiceDaemon` 호출 스레드에서 생성, 실패 시 orphan Some 없음
- 워커 스레드: blocking `recv()`, 100ms poll 루프 없음
- `BrowserHandle::drop` → `stop_browse` + `shutdown` → 워커 자연 종료
- mDNS fullname이 found/lost 이벤트 양쪽에서 동일 map key
- 인스턴스 이름 표시: `._c2vsync._tcp` suffix strip (`.split('.')[0]` 금지)
- `browser-error` → `setStatus(..., 'error')` (콘솔만이 아닌 UI 표시)
- `pagehide`: unlisten + `stop_discovery` invoke
- 윈도우 `Destroyed`/`CloseRequested`: `BrowserState::stop_all()`

### Phase 2 — 페어링 UI (미구현, 후속 스프린트)

Android 기기와 데스크톱 간 토큰 기반 페어링 UI.

### Phase 3 — 파일 업로드 수신 (Ktor, 후속 스프린트)

Ktor CIO 서버에서 인증된 WAV multipart 업로드 수신. A-1 참조.

**서비스 재시작 시 주의**: Service 프로세스가 종료되면 진행 중인 페어링이 취소된다 (A-2). UI recreate는 메모리 내 pending 상태를 유지한다. 복구되는 상태는 DataStore에 저장된 토큰/paired 정보뿐이다.

---

## 검증 (Phase 1)

| 종류 | 명령 |
|------|------|
| Rust compile | `cargo check` (desktop/src-tauri 디렉터리) |
| UI smoke | Tauri dev 실행 후 Android 기기 발견/소실 이벤트 확인 |
| 종료 안전성 | 앱 종료 후 워커 스레드가 고아 상태로 남지 않는지 확인 |

---

## 미구현 범위 (Do not)

- Phase 7 pair/upload 구현
- `app/**` Android Kotlin 코드 수정
- `audio.rs` 수정
- 최종 완료 선언 (evaluator PASS 전)

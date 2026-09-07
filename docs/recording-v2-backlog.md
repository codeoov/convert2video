# Recording v2 backlog

Phase 5(MVP 마무리) 범위 밖 기능·테스트 부채. 코드 TODO/주석으로 남기지 않고 이 문서에만 기록한다.

## MVP 제외 기능

### 무음 자동 스킵
- **왜 제외**: 캡처 파이프라인·오프라인 분석 복잡도. Phase 1 엔진은 AAC/WAV 캡처·인덱싱에 집중.
- **재검토 시**: 실시간 vs 후처리, 임계값 UX, 배터리·CPU.

### 녹음 전용 트림 에디터
- **왜 제외**: 변환 세그먼트 분할과 별도 UI/편집 스택. Home 세그먼트 플래너로 일부 대체 가능.
- **재검토 시**: Media3 편집 그래프, 미리듣기, 저장 시 재인코딩 여부.

### 위젯 / 잠금화면 녹음
- **왜 제외**: 포그라운드 Service·알림으로 MVP 충족. 위젯/잠금화면은 OEM·권한 이슈 큼.
- **재검토 시**: Glance 위젯, 미디어 세션 연동, 잠금화면 컨트롤.

### 다중 동시 녹음
- **왜 제외**: `RecordingController` 싱글톤·단일 Engine 세션 전제. 동시 캡처는 마이크 라우팅·상태 머신 전면 개편.
- **재검토 시**: 세션 ID, 동시성 모델, UI 다중 타이머.

## 테스트 부채

Phase 5에서 추가한 Compose 계측 테스트는 Options 녹음 포맷 세그먼트(`OptionsRecordingFormatContentTest`) 최소 1종뿐이다. 아래는 의도적 범위 밖.

| 화면 | 부채 |
|------|------|
| `HomeScreen` | 변환 CTA·다이얼로그·세그먼트 UI Compose 테스트 부재 |
| `AudioPickScreen` | 필터/선택 모드 Compose 테스트 부재 |
| `OptionsScreen` | 테마·Wi-Fi·YouTube 계정 전체 플로우 테스트 부재 (포맷 세그먼트만 커버) |
| `RecordingsListScreen` | 재생·rename·delete 다이얼로그 계측 테스트 보강 여지 |
| `ErrorLogScreen` | testTag는 추가했으나 전용 androidTest 없음 |

재검토 시 `BackgroundPickContentTest` / `OptionsRecordingFormatContentTest` 패턴(`onNodeWithTag`)을 재사용한다.

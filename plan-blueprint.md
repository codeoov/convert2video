# 계획: Audio "All" = "My recordings 제외 전부" 재정의 + Converted 탭 동일 적용

## Context

첨부된 `plan-blueprint.md`(S-03/S-04)는 "통화 녹음만 키워드로 걸러내고 나머지 일반 음성파일을 전부 노출"하는 방향으로 이미 구현되어 있다(`GeneralAudioScanPolicy.kt`의 `isGeneralAudioCandidate`/`isExplicitCallRecordingContext`). 그런데 실기기(S22 Ultra)에서 확인한 결과 Audio 탭의 "All"에는 음악 몇 개와 앱 자체 녹음(`Music/C2V`) 하나만 노출되고, 기기에 실제로 많은 일반 음성파일(WhatsApp 음성메모 등)은 하나도 뜨지 않는다 — 즉 MIME/확장자 화이트리스트 + 통화녹음 키워드 휴리스틱이 실제로는 파일을 과도하게 걸러내고 있다.

사용자는 이 문제를 다른 방식으로 풀기로 결정했다: 휴리스틱(키워드로 통화녹음 배제)을 완전히 버리고, 규칙을 "My recordings에 들어가는 것만 제외하고 나머지는 전부(All)"로 단순화한다. 이렇게 하면:
- MediaStore가 audio로 분류한 모든 항목이 그대로 노출되어 "왜 안 뜨나" 문제가 근본적으로 해결된다.
- 정의가 폴더 출처(내 녹음 여부) 기준이라 훨씬 예측 가능하다.
- 부작용으로 통화 녹음도 All에 다시 노출될 수 있음 — 사용자가 이 트레이드오프를 명시적으로 수용함(확인 완료).

추가로 Converted 탭에도 동일한 "My recordings vs All(제외 방식)" 구분을 적용해 달라는 요청이 있었다. 조사 결과 Converted 탭은 이미 `ConvertedVideoSourceFilter{MyRecordings, All}` + `SegmentedControl` UI가 구현돼 있으나, 현재 `All`은 "전체 합집합"(My recordings 포함)으로 동작한다 — Listen/Audio 탭과 동일하게 "My recordings 제외"로 바꿔야 사용자가 원하는 일관된 의미가 된다.

이 작업물의 최종 산출물은 **코드 변경이 아니라**, 첨부 `plan-blueprint.md`와 동일한 스타일의 한국어 스프린트 계획 문서를 새로 작성하는 것이다(Cursor가 이어서 구현). 파일명: `plan-blueprint-audio-all-scope.md` (프로젝트 루트, 기존 `plan-blueprint.md`와 별도 파일 — 기존 파일 보존).

## 조사로 확정된 사실 (문서에 그대로 반영)

1. **Listen/Audio "All" 현재 동작** (`RecordingsListViewModel.kt:358-359`): `filterDisplayedItems(m + imported, r, f)` 호출 — `AudioSourceFilter.All`은 `(media + recordings).sortedByDescending{dateAdded}` (합집합, 내 녹음 포함). 새 요구사항은 `All = media(+imported)만, recordings 제외`.
2. **일반 음성파일 스캔 필터** — `AudioRepository.kt:135` `isGeneralAudioCandidate(mimeType, displayName, relativePath)` (`GeneralAudioScanPolicy.kt`)가 MIME/확장자 화이트리스트 + 통화녹음 키워드 휴리스틱으로 행을 스킵한다. 이 필터를 완전히 제거하고, MediaStore 쿼리(API29+: `MEDIA_TYPE = AUDIO`, API<29: `Audio.Media` 무필터)가 반환하는 모든 행을 그대로 `AudioItem`으로 변환한다.
3. **"My recordings"는 구조적으로 MediaStore에 안 잡힘** — 녹음 파일은 `getExternalFilesDir(Music)/C2V`(앱 전용 외부 저장소)에 저장돼 MediaStore 스캔 대상이 아니다(`C2vRecordingNames.kt`). 따라서 media 쪽에서 recordings와의 중복을 걱정할 필요는 없고, "All에서 My recordings 제외"는 사실상 "media(+imported) 리스트만 쓴다"로 구현하면 된다.
4. **Import된 파일도 별도 앱 저장소**(`Music/C2VImported`)라 MediaStore와 중복 없음 — All에는 media + imported를 그대로 합쳐 넣는다(기존과 동일).
5. **Converted 탭 현재 동작** (`ConvertedVideosFilterSortMapping.kt:17-23`): `filterConvertedVideosListRowsBySource` — `All -> rows`(전부, 필터링 없음), `MyRecordings -> rows.filter { isRecordingSourced() }`. `isRecordingSourced()`는 `ConvertedVideoListItem.audioUri`가 녹음 FileProvider(`c2v_music`) 출처인지로 판정(`isRecordingSourcedAudioUri`, `data/RecordingAudioMapping.kt:14`). 기존 단위테스트 `ConvertedVideosFilterSortMappingTest.success_filterAll_returnsAllRows`(라인 87-99)가 현재 합집합 동작을 고정하고 있어 갱신 필요.
6. **Converted 기본 정렬**은 이미 `ConvertedVideosSortOrder.Time`이 최신순(`Time -> compareByDescending{rowSortTimeKey}`)이라 별도 변경 불필요. Listen도 기본 정렬 `RecordingsListSortOrder.Time` 유지(변경 불필요) — 사용자가 요청한 "디폴트 정렬 Time·최신순"은 이미 두 화면 모두 충족.
7. **AudioPick 화면(변환 조립용 오디오 재선택)은 건드리지 않는다** — `AudioPickViewModel`/`AudioPickScreen`은 `ui/screens/audio_pick/filterDisplayedItems`(3-way: All/MyRecordings/Files)를 그대로 쓰고, 이 의미는 이번 요청과 무관하게 유지(CLAUDE.md S-06 메모가 이미 Listen vs AudioPick의 "All" 의미 분리를 경고하고 있음). Listen 전용으로 새 로컬 필터 함수를 하나 추가해 공유 함수를 건드리지 않는 것이 핵심 설계 결정.
8. **통화녹음 필터는 완전 제거**로 확정(사용자 확인) — `GeneralAudioScanPolicy.kt` + `GeneralAudioScanPolicyTest.kt` 전체 삭제, `AudioRepository.kt`의 호출부/불필요 import 제거.

## 새 블루프린트 문서에 담을 스프린트 구성 (Cursor 실행용)

### S-01. 일반 오디오 스캔 화이트리스트/통화녹음 휴리스틱 완전 제거
- 대상: `app/src/main/java/com/example/convert2video/data/AudioRepository.kt:135`(`isGeneralAudioCandidate` 호출부 제거, cursor loop은 무조건 add), `data/GeneralAudioScanPolicy.kt`(파일 삭제), `app/src/test/java/com/example/convert2video/data/GeneralAudioScanPolicyTest.kt`(파일 삭제).
- 결과 계약: `queryAudioFiles()`는 MediaStore가 `MEDIA_TYPE_AUDIO`(API29+)/`Audio.Media`(API<29)로 분류한 모든 행을 예외 없이 `AudioItem`으로 반환. `mimeType` 컬럼은 더 이상 필터링에 안 쓰이므로 read는 유지하되(추후 활용 여지) 사용처 없으면 남겨둬도 무방.
- 검증 포인트: 실기기에서 WhatsApp 음성메모 등 이전에 안 뜨던 파일이 Audio 탭에 나타나는지, 통화 녹음 폴더 파일도 (의도대로) 다시 노출되는지.

### S-02. Listen(Audio) 탭 "All" = My recordings 제외로 재정의
- 대상: `RecordingsListViewModel.kt:358-359`(combine 호출부를 새 Listen 전용 함수로 교체), 새 pure 함수 추가 위치는 기존 패턴(`RecordingsListConversionFilterMapping.kt` 옆)에 맞춰 예: `RecordingsListSourceFilterMapping.kt` 신규 — `filterListenAudioItems(media: List<AudioItem>, recordings: List<AudioItem>, filter: AudioSourceFilter): List<AudioItem>`:
  - `MyRecordings -> recordings.sortedByDescending{dateAdded}` (기존과 동일, 변경 없음)
  - `All -> media.sortedByDescending{dateAdded}` (recordings 제외 — 신규)
  - `Files -> media.sortedByDescending{dateAdded}` (화면에서 도달 안 하지만 exhaustive 방어)
  - AudioPick 쪽 `ui/screens/audio_pick/filterDisplayedItems`/`AudioSourceFilter`는 그대로 두고 import만 교체(`RecordingsListViewModel.kt:20-21`의 `filterDisplayedItems` import를 신규 함수로).
- `RecordingsListScreen.kt:165-169`, `297-303`의 Files 방어 로직은 그대로 유지(변경 불필요, Listen은 여전히 Files 상태에 진입하지 않음).
- 신규/갱신 단위테스트: All이 media만 반환하고 recordings를 포함하지 않는지, MyRecordings는 기존과 동일한지.

### S-03. Converted 탭 "All" = My recordings 제외로 재정의
- 대상: `ConvertedVideosFilterSortMapping.kt:20-23`의 `filterConvertedVideosListRowsBySource` — `All -> rows`를 `All -> rows.filterNot { it.isRecordingSourced() }`로 변경. `MyRecordings` 분기는 변경 없음.
- 테스트: `ConvertedVideosFilterSortMappingTest.kt:86-99` `success_filterAll_returnsAllRows`를 새 의미에 맞게 갱신(All에서 recording-sourced row가 빠지는지 검증하도록 재작성 — 예: 이름을 `success_filterAll_excludesRecordingSourced`로 바꾸고 `assertEquals(1, filtered.size)` + 남은 행이 imported 쪽인지 확인).
- UI 문구(`ConvertedVideosScreen.kt` 관련 empty-state 문자열)가 "전체"라는 뉘앙스와 어긋나지 않는지 확인, 필요 시만 `strings.xml`/`strings-ko.xml` 문구 리뷰(새 문자열 추가는 지양, 기존 `audio_pick_filter_all` 재사용 유지).

### S-04. 통합 검증 및 문서 동기화
- Listen "All"과 Converted "All"이 동일한 규칙(My recordings만 제외)으로 일관되는지 교차 확인.
- `CLAUDE.md` Terminology Glossary의 `filterByConversion`/`RecordingsListConversionFilter` 인접 항목에 새 Listen 전용 필터 함수 SSOT 한 줄 등록(신규 공개 함수이므로 §5 등록 규칙 대상).
- 실기기(S22 Ultra, wireless ADB) 골든패스 확인: Audio 탭 All에 일반 음성파일 다수 노출 → 길게 눌러 선택 → 취소 버튼 정상 동작(기존 회귀 없는지) → Convert 진입 정상.

## 검증 방법 (블루프린트 문서 내 각 스프린트에 포함)

- 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`
- 유닛테스트: `.\gradlew.bat :app:testDebugUnitTest --no-daemon` (Robolectric 포함이므로 `ConvertedVideosFilterSortMappingTest` 실행 확인)
- 구조/훅 체크: `.cursor\hooks\run-stop-checks.ps1`
- 금지 용어/하드코딩 체크: 기존 `plan-blueprint.md` 패턴 그대로(`rg` 명령어 재사용)
- 실기기 수동 확인: Audio 탭 All 노출 파일 수, Converted 탭 All/My recordings 세그먼트 전환.

## 산출물

1. 새 파일 `plan-blueprint-audio-all-scope.md` (프로젝트 루트) — 위 S-01~S-04를 `plan-blueprint.md`와 동일한 형식(아키텍처 영향 분석 → 스프린트 로드맵 표 → 스프린트별 상세 가이드 → 마무리 지침)으로 작성.
2. 코드 변경 없음 — 실제 구현은 Cursor가 새 블루프린트 문서를 보고 진행.

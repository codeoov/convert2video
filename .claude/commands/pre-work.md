# /pre-work — 작업 전 계획 수립 (convert2video)

이 커맨드를 실행하면 아래 순서로 진행한다. 코딩 시작 전에 반드시 실행.

---

## 1. Rules 파일 확인

`CLAUDE.md`(단일 소스)에서 이번 작업에 해당하는 섹션을 연다:

- 핵심 → `CLAUDE.md` § Core Conventions
- 폴더·역할 → `CLAUDE.md` § Folder Structure
- 제품 플로우 → `CLAUDE.md` § Product Scope
- 단일 소스·금지 → `CLAUDE.md` § Anti-Repeat / Single-Source Rules
- 용어 → `CLAUDE.md` § Terminology Glossary

## 2. 기존 코드 읽기

수정 예정 파일과 주변 관련 파일을 코드 작성 전에 읽는다.

## 3. 중복 방지

- 배경 CRUD → `BackgroundRepository`
- 변환 → `ConversionWorker` / `VideoConverter`
- UI 상태 → `ConversionUiState` (WorkInfo 매핑 복제 금지)

## 4. 계획 출력

```
수정 파일 목록:
  -

생성 파일 목록: (필요 시 사용자 확인 후)
  -

구현 순서: data → video → ViewModel → Screen

재사용할 기존 유틸/상수:
  -

Worker 키·Work 이름 변경: yes / no
신규 용어: yes / no (yes면 terminology-glossary 등록)
예상 이슈:
  -
```

## 5. 검증 체크리스트

1. `.\gradlew.bat :app:compileDebugKotlin`
2. `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".cursor\hooks\run-stop-checks.ps1"`
3. 셀프체크: background/conversion/video 용어 · 단일 소스 · stringResource 위치 · 파일 생성/삭제 사전 확인

## 6. 계획 확인 후 코딩 시작

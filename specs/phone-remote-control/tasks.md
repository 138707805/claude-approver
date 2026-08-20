# 작업 목록

- [x] 0. 스펙 문서 작성 (`requirements.md`, `design.md`, `tasks.md`)

## 기능 A — 알림 전체 내용 인앱 열람
- [x] A1. `notify-common.js`에 `truncateForNtfy` 추가
- [x] A2. `task-complete-notify.js` / `stop-failure-notify.js` 적용
- [x] A3. `permission-relay.js`에 동일 헬퍼 복사 + 적용
- [x] A4. `Prefs.kt`에 `lastStatusTitle`/`lastStatusBody` 추가
- [x] A5. `RelayService.handleStatusMessage`에서 저장
- [x] A6. `MainActivity`에 히스토리 항목 탭 → 상세 다이얼로그
- [x] A7. 상태 카드에 "전체 보기" 추가
- [x] A8. `node --check`로 훅 3개 문법 검증

## 기능 C — 설정 화면 분리
- [x] C1. `activity_settings.xml` 작성(페어링+사용량 카드 이동)
- [x] C2. `SettingsActivity.kt` 작성
- [x] C3. `activity_main.xml`에서 두 카드 제거, 톱니바퀴 버튼/배너 추가
- [x] C4. `MainActivity.kt`에서 이동한 로직 제거, 설정 진입/배너 로직 추가
- [x] C5. `AndroidManifest.xml`에 액티비티 등록
- [x] C6. `strings.xml` 정리

## 기능 B — 데몬 + 유휴 감지
- [x] B1. `claude-approver-state.json`에 `daemon` 섹션 스키마 추가(코드상 lazy init)
- [x] B2. `notify-common.js`에 busy/waiting/lastCwd 헬퍼 추가
- [x] B3. `permission-relay.js`에 헬퍼 복사 + `touchBusy`/`touchLastCwd` 호출
- [x] B4. `task-complete-notify.js`에 waiting/busy/lastCwd 마킹 배치 (동시성 문제로
      공용 헬퍼 대신 이미 들고 있는 `state` 객체를 직접 수정하는 방식으로 변경 —
      design.md와 다른 부분, STATUS.md v1.11 상세에 이유 기록)
- [x] B5. `stop-failure-notify.js`에 `clearBusy`/`clearWaiting` 호출
- [x] B6. `hook/daemon.js` 작성(스트리밍 재연결 + 유휴 판단 + spawn)
- [x] B7. `--continue` 실패 케이스 — **실제 재현 불가**(중첩 Claude Code 세션에서
      `claude -p` 실행이 행업됨, 이 개발 환경의 제약). 문구 매칭 대신 "0이 아닌
      코드면 이유 불문 한 번만 재시도" 방식으로 단순화. 사용자가 실기기에서
      한 번 확인 필요.
- [x] B8. 데몬 하트비트 publish
- [x] B9. `NtfyClient.fetchLatestFieldsContaining` 추가(설계 문서의 `fetchLatestJson`
      에서 이름/동작 변경 — 여러 필드가 섞인 토픽이라 "가장 최근 메시지 1개"가
      아니라 "특정 필드를 담은 가장 최근 메시지"를 찾아야 해서)
- [x] B10. `RelayService`에서 데몬 하트비트 폴링 → `Prefs.daemonAliveAt`
- [x] B11. `MainActivity` 프롬프트 카드에 데몬 상태 안내 문구
- [x] B12. README/STATUS.md에 데몬 실행법 추가
- [x] B13. 격리된 테스트 페어링 코드로 데몬 실행 → ntfy 스트림 연결 확인.
      busy-path/wake-path 전체 왕복은 (1) ntfy.sh 무료 티어 일일 한도에 걸림,
      (2) 내 자신의 세션이 상태 파일을 계속 건드려서 격리가 어려움,
      (3) `claude -p` 중첩 실행 행업으로 끝까지 재현 못 함 — 코드 리뷰 수준
      검증까지만 완료.

## 마무리
- [x] 전체 `node --check` 재확인
- [x] STATUS.md 버전 히스토리에 이번 라운드 요약 추가 (v1.11)
- [x] versionCode/versionName 갱신 (12 / "1.11")
- [ ] 커밋 → push → GitHub Actions 빌드 → 릴리스 (사용자가 "배포까지 해줘"로 승인함,
      이 커밋 이후 진행)

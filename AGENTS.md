# AGENTS.md — 공통 작업 지침 (Codex · Claude Code 공용)

이 파일이 두 AI가 함께 따르는 원본이다. Claude Code는 `CLAUDE.md`에서 이 파일을 가져온다.
협업 절차 전체: [docs/AI_COLLABORATION.md](docs/AI_COLLABORATION.md)

## 프로젝트

Claude Code가 권한 승인을 물을 때 PC 앞에 없어도 휴대폰 알림으로 허용·거부하게 해 주는 도구.
PC 쪽 Node.js 훅(`hook/`, 외부 의존성 없음)과 안드로이드 앱(`android/`, Kotlin)이 공개 중계 서비스 ntfy.sh로 메시지를 주고받는다.
작업 완료 알림, 폰에서 다음 지시 보내기, 상시 데몬, 공유기 내장 WOL로 PC 켜기 기능이 있다. 사용법: [README.md](README.md)

## 현재 상태 (2026-10-11 기준)

- 기본 브랜치 `master`. 열린 PR과 다른 브랜치는 없다. 최신 릴리스는 v1.14.
- 긴 배경·검증 기록·운영 메모는 인계 문서 [STATUS.md](STATUS.md)에 있다. 작업 전에 읽는다
  (아직 실측하지 못한 것: 절전 상태에서 WOL로 깨어나는지, 데몬의 `claude -p` 경로).

## 구조

| 경로 | 역할 |
|---|---|
| `hook/permission-relay.js` | PreToolUse 훅: 승인 중계, "항상 허용" 기억, 자동 허용 모드의 위험 판단(`HIGH_RISK_*_PATTERNS`) |
| `hook/task-complete-notify.js`, `hook/stop-failure-notify.js`, `hook/attention-notify.js` | Stop·StopFailure·Notification 훅: 완료·오류·확인 필요 알림, 폰 입력 대기 |
| `hook/daemon.js` | 상시 데몬(수동 실행): PC가 유휴일 때 폰 지시로 `claude -p` 세션 시작 |
| `hook/notify-common.js`, `hook/usage.js` | 공통 유틸·상태 파일, 토큰 사용량 집계 |
| `hook/setup.js` | 페어링 코드 생성, `~/.claude/settings.json`에 훅 등록(재실행해도 안전) |
| `android/` | 안드로이드 앱(Gradle, wrapper 없음). `debug.keystore`는 고정 서명 키 |
| `specs/phone-remote-control/` | v1.11 기능의 요구사항·설계·작업 문서 |
| `.github/workflows/build-apk.yml` | APK 빌드 (master push, 수동 실행) |

## 명령

```sh
ls hook/*.js | xargs -n1 node --check            # 훅 문법 검사 (npm install 불필요)
cd android && gradle assembleDebug --no-daemon   # APK 빌드 (JDK 17, Android SDK, Gradle 8.7 필요)
```

자동 테스트는 없다. 훅을 바꾼 PR은 문법 검사 통과가 필수다. 로컬에 JDK·Android SDK가 없으면
`build-apk.yml`을 작업 브랜치로 수동 실행(workflow_dispatch)해 APK 빌드를 확인한다.
`node hook/setup.js`(사용자 설정을 고침)와 `node hook/daemon.js`(상주 프로세스)는 사용자가 요청할 때만 실행한다.

## 보안·안전 정책

- 이 저장소는 public이다. 페어링 코드·ntfy 토픽 이름, 공유기·DDNS 주소, WOL 비밀번호 같은 실제 값을
  커밋·PR·이슈·댓글·로그에 넣지 않는다. 그 값은 사용자 PC의 `~/.claude/claude-approver.json`과 앱 설정에만 둔다.
- 훅은 오류·타임아웃·미설정이면 결정 없이 끝나 터미널 승인으로 넘어가야 한다. 자동 허용은 애매하면 사람에게 넘긴다.
- ntfy.sh는 공개 무료 서비스다. 메시지에 파일 내용·환경 변수·자격 증명을 싣지 않는다.
  하루 발송 한도가 있으므로 알림이 필요 없는 이벤트는 보내지 않는다(2026-08-25 한도 초과로 승인 요청까지 막힌 적이 있다).
- 사용자 PC의 `~/.claude/settings.json`과 `~/.claude/claude-approver*.json`은 사용자 데이터다. 테스트로 바꿨다면 반드시 되돌린다.
- `android/debug.keystore`를 다시 만들거나 바꾸지 않는다. 바꾸면 기존 앱 위에 덮어 설치할 수 없다.
- 비밀값(API 키, 토큰, `~/.codex/auth.json`, `.env`)을 커밋·PR·댓글·로그에 남기지 않는다.

## 작업 규칙 (요약)

- `master`에 직접 push·병합하지 않는다. 한 작업 = 한 브랜치 = 한 PR. Claude는 `claude/<주제>`, Codex는 `codex/<주제>`.
- 사용자 PC의 `~/claude-approver`는 실제 훅이 실행되는 폴더다. 거기서 브랜치를 바꾸지 말고 별도 worktree에서 작업한다.
- 작업 전 `git fetch`로 최신 상태를 받는다. 열린 PR이 바꾸는 파일은 조율 없이 수정하지 않는다.
- 다른 브랜치에 push·force-push·rebase 하지 않는다. 미커밋 변경을 덮어쓰지 않는다.
- 동작이나 운영 절차가 바뀌면 같은 PR에서 README.md·STATUS.md도 고친다.
- 병합은 사용자만 한다. APK 릴리스는 사용자가 요청할 때만 만든다.

## Codex 역할

구현 계획 검토, 코드 리뷰, 보안 취약점 검사, 테스트 결과 분석, 독립적으로 분리 가능한 기능 개발
(`codex/<주제>` 브랜치, 별도 PR). 지적에는 파일:줄, 심각도(P0~P3), 고칠 방법을 붙인다.

## Code Review Rules

- 훅이 미설정(페어링 없음)이거나, 자동 허용이 꺼진 상태의 폰 승인 중계가 오류·타임아웃으로 끝났는데
  `permissionDecision: "allow"`가 나가게 되거나, `remoteInputMode`가 꺼져 있는데 데몬이 세션을 시작하게 되면 P0.
  안전한 경로: 결정을 출력하지 않고 끝내 터미널 승인으로 넘긴다. 설정 토픽 조회가 실패하면 로컬 `autoApproveMode`
  (필드가 없으면 true)로 대체하는 것과, 자동 허용 모드가 위험하지 않은 요청을 허용하는 것은 의도된 동작이다(STATUS.md "자동 허용 모드").
- `HIGH_RISK_*_PATTERNS`를 빼거나 좁히는 변경, "항상 허용" 기억의 일치 기준(Bash는 명령 전체, Write·Edit는 파일 경로)을
  넓히는 변경, `ExitPlanMode`를 폰 승인 대상으로 바꾸는 변경은 P1. 안전한 경로: 애매하면 "컴퓨터에서 확인" 알림으로 넘긴다.
- 페어링 코드·토픽 이름, 공유기·DDNS 주소, WOL 비밀번호가 코드·문서·로그에 들어가면 P0. 토픽을 아는 사람은
  승인 응답을 위조할 수 있고, 데몬이 켜져 있으면 PC에서 Claude 세션을 시작할 수 있다. ntfy 메시지에 파일 내용·환경 변수가 섞이면 P1.
- `android/debug.keystore`·서명 설정을 바꾸는 변경, 훅 안의 대기 시간이 `hook/setup.js`의 훅 타임아웃
  (PreToolUse 180초, Stop 600초)보다 길어지는 변경은 P1. 타임아웃에 잘리면 폰 응답·지시가 버려진다.

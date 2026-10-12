# AI 협업 규칙 (Claude Code ↔ ChatGPT/Codex)

두 AI와 사용자가 함께 지키는 규칙이다. 프로젝트 목적·구조·보안 정책은 [AGENTS.md](../AGENTS.md)에 있다.

## 역할

| | Claude Code | ChatGPT / Codex | 사용자 |
|---|---|---|---|
| 개발 | 주요 기능, 수정·리팩터링, 테스트, 버그 수정 | 독립적으로 분리 가능한 기능 (`codex/<주제>`) | 요청 |
| 검토 | Codex 지적을 검증하고 반영 | 계획 검토, 코드 리뷰, 보안 검사, 테스트 결과 분석 | 최종 확인 |
| 병합·릴리스 | 병합하지 않음. 릴리스는 사용자가 요청할 때만 | 하지 않음 | 병합, 릴리스 결정 |

## 두 AI를 잇는 경로

| 경로 | 쓰는 곳 | 필요 조건 |
|---|---|---|
| GitHub PR 댓글 `@codex review` (초점 지정: `@codex review for security issues: …`) → Codex가 PR에 리뷰 게시 | 클라우드·로컬 모두 | 저장소가 Codex에 연결되고 Code review가 켜져 있음 |
| Codex CLI (`codex review`, `codex exec`; codex-review 플러그인이 있으면 `/codex-review:plan`, `/codex-review:code`) | 사용자 PC·WSL | `codex login`(ChatGPT 계정)과 OpenAI 접속 |
| PR 댓글 `@codex <작업>` → Codex 클라우드 작업 (수정 push, 기능 개발) | 클라우드·로컬 모두 | 위 GitHub 조건 + 저장소별 [Codex 환경](https://chatgpt.com/codex/cloud/settings/environments) |

`@codex security review`(심층 보안 리뷰)는 Codex Security 권한이 필요하며 Pro·Business·Edu·Enterprise 전용이다.
ChatGPT Plus에서는 일반 리뷰에 보안 초점을 주고, `AGENTS.md`의 Code Review Rules로 보안 기준을 전달한다.

`codex-dispatch`(MCP) 스킬은 쓰지 않는다. 필요한 `codex mcp-server` 명령이 현재 Codex CLI에 없다.

## 브랜치·worktree

- `master`에 직접 push하지 않는다. 한 작업 = 한 브랜치 = 한 PR.
- Claude는 `claude/<주제>`(클라우드 세션이 정한 이름 포함), Codex는 `codex/<주제>`.
- 한 PC에서 동시에 작업할 때는 worktree로 폴더를 나눈다:
  `git worktree add ../claude-approver-codex -b codex/<주제> origin/master`
- 사용자 PC의 `~/claude-approver`는 실제 훅이 실행되는 폴더다. 그 폴더는 `master`에 두고, 작업은 언제나 별도 worktree에서 한다.
  거기서 작업 브랜치를 체크아웃하면 그 순간부터 실제 승인 훅이 검토 전 코드로 돈다.
- 작업 시작 전: `git fetch origin` → `git status` → 열린 PR 목록과 변경 파일 확인.
  다른 PR이 같은 파일을 바꾸고 있으면 그 PR 위에 쌓거나(base = 그 브랜치) PR 댓글로 조율한다.
- 다른 AI나 사람의 브랜치에 push·force-push·rebase 하지 않는다. 미커밋 변경을 덮어쓰지 않는다.
- 공통 지침(`AGENTS.md`, `CLAUDE.md`, 이 문서)은 따로 PR을 만들어 바꾼다.

## 협업 루프

```
사용자 요청 → Claude 계획(docs/plans) + draft PR → Codex 계획 검토
→ Claude 구현·검사 → Codex 코드·보안 검토 → Claude 수정 (P0/P1 없을 때까지, 최대 5회)
→ 문법 검사·APK 빌드 통과 → PR ready → 사용자에게 보고 → 사용자가 병합
```

5회 검토 뒤에도 P0/P1이 남으면 **차단**한다. PR을 ready로 바꾸지 않고 남은 지적을 사용자에게 보고하며,
사용자가 해결하거나 명시적으로 보류를 결정할 때까지 진행하지 않는다.
스택 PR(base가 master가 아닌 PR)은 그 PR의 실제 base 브랜치를 기준으로 리뷰·검사한다.

Codex가 독립 기능을 맡을 때는 방향이 반대다: Codex가 `codex/<주제>` PR을 열고, Claude가 리뷰·검사 결과 분석을 한다.

## 병합 조건

아래를 모두 만족해야 사용자에게 병합을 권한다. 병합은 사용자만 한다.

- 훅을 바꿨으면 `ls hook/*.js | xargs -n1 node --check` 통과. 안드로이드 코드를 바꿨으면 작업 브랜치의 APK 빌드 성공.
  PR에서 자동으로 도는 CI는 아직 없다(`build-apk.yml`은 master push와 수동 실행 때만 돈다).
- 마지막 Codex 리뷰에 P0/P1 지적이 없음. 동의하지 않는 지적도 근거를 스레드에 답한 뒤 다시 리뷰받고, 5회 뒤에도 남으면 차단(사용자가 명시적으로 보류를 결정한 경우만 예외)
- `docs/plans/`의 계획과 실제 변경이 일치
- 실기기·실제 ntfy로 확인하지 못한 부분이 PR 설명에 적혀 있음

## 배포

결과물은 두 가지이고, 둘 다 병합된 `master`에서만 나간다.

- **PC 훅**: 사용자 PC의 `~/claude-approver`(master)에서 바로 실행된다. 병합 후 그 폴더를 `git pull`로 최신화하면
  다음 훅 호출부터 반영된다. 훅 등록(matcher·타임아웃·새 훅)이 바뀌었으면 그 폴더에서 `node hook/setup.js`를 다시 실행한다.
  `setup.js`는 자기가 있는 폴더의 훅을 등록하므로 worktree에서 실행하지 않는다.
- **안드로이드 APK**: `master` push(PR 병합 포함)마다 `build-apk.yml`이 APK를 빌드해 Actions 아티팩트로 올린다.
  병합만으로 사용자 폰에 배포되지는 않는다. 릴리스는 사용자가 요청할 때만 만든다:
  `dist/`를 비우고 `gh run download`로 새 아티팩트를 받는다 → `gh release create vX.Y` →
  릴리스 링크로 직접 내려받아 md5가 빌드본과 같은지 확인 → 저장소가 public인지 확인 → 사용자에게 링크를 준다.
  주의점(예전 APK 재업로드 사고, 카카오톡 인앱 브라우저 다운로드 문제 등)은 [STATUS.md](../STATUS.md)의
  "알아두면 좋은 환경/운영 메모"에 있다.

## 작업 상태 공유

- 원본은 GitHub의 열린 PR·이슈다. PR 설명에 진행 체크리스트를 둔다.
- 계획은 작업마다 새 파일 `docs/plans/YYYY-MM-DD-<주제>.md`로 쓴다. 같은 파일을 두 AI가 동시에 고치지 않게 하기 위해서다.
- [STATUS.md](../STATUS.md)는 세션 사이 인계 문서로 유지한다. 버전별 변경 이유, 검증한 것·못 한 것, 운영 메모처럼
  PR 하나보다 긴 맥락을 담는다. 작업 시작 전에 읽고, 그 내용이 바뀌는 PR 안에서 같이 고친다.
  진행 상황의 원본은 PR이고, STATUS.md는 긴 배경 기록이다.
- `specs/<기능>/`(요구사항·설계·작업)은 사용자가 스펙 드리븐으로 요청한 큰 기능에 쓴다.

## 비밀값·비용

- Codex는 ChatGPT 계정 로그인으로 쓴다(Plus 구독에 포함된 사용량). OpenAI API 키는 발급하지도 쓰지도 않는다.
  API 키 로그인은 별도 종량 과금이다.
- `~/.codex/auth.json`은 비밀번호와 같다. 저장소·채팅·환경 변수·로그에 넣지 않는다.
- Codex 코드 리뷰에는 요금제별 사용 한도가 있다. 지적을 모아 고친 뒤 다시 요청하고, P2 이하만 고친 커밋은 다시 요청하지 않는다.
  남은 한도는 [Codex 사용량](https://chatgpt.com/codex/cloud/settings/usage)에서 본다.
- 이 저장소는 public이다. 페어링 코드, 공유기·DDNS 주소, WOL 비밀번호는 PR 설명·댓글·리뷰 답글에도 쓰지 않는다.

## 사용자 1회 설정

1. **필수** — ChatGPT의 [Codex code review 설정](https://app.chatgpt.com/settings/code-review)에서 저장소의 Code review를 켠다.
   저장소가 목록에 없으면 Codex의 GitHub 연결에서 그 저장소 접근을 허용한다.
2. 선택 — Codex가 PR에서 직접 고치거나 기능을 개발하게 하려면(`@codex <작업>`) 저장소마다
   [Codex 환경](https://chatgpt.com/codex/cloud/settings/environments)을 만든다. 리뷰만 쓸 때는 필요 없다.
3. 선택 — 사용자 PC·WSL에서 CLI 경로를 쓰려면: `npm i -g @openai/codex` → `codex login`(ChatGPT로 로그인).
4. 선택(권장) — GitHub 저장소 Settings → Rules에서 `master`에 PR 필수를 건다. PR용 CI를 추가한 뒤에는 CI 통과 필수도 건다.

## 다른 저장소에 적용

1. 그 저장소의 열린 PR·브랜치에 `AGENTS.md`·`CLAUDE.md`가 있는지 확인한다. 있으면 덮어쓰지 않고 합친다.
2. 이 저장소의 세 파일을 복사하고 `AGENTS.md`의 프로젝트·구조·명령·보안·Code Review Rules를 그 저장소에 맞게 고친다.
3. CI가 없으면 그 저장소의 테스트 명령으로 `.github/workflows/ci.yml`을 추가한다.
   `permissions: contents: read`, 액션은 커밋 SHA로 고정, 비밀값 없이 도는 테스트만 넣는다.
4. 별도 브랜치·PR로 올리고 `@codex review`로 연결을 확인한다. 위 1회 설정 1번을 그 저장소에도 한다.

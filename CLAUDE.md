# CLAUDE.md

@AGENTS.md

## Claude Code 역할

주요 기능 개발, 코드 수정·리팩터링, 테스트 작성, 버그 수정. Codex의 검토를 받아 반영한다.
협업 규칙 전체: [docs/AI_COLLABORATION.md](docs/AI_COLLABORATION.md)

## 개발 요청을 받으면: 자동 협업 루프

사용자는 이 저장소의 모든 개발 요청에 대해 아래 루프를 미리 허락했다:
작업 브랜치 push, draft PR 생성, PR에 `@codex review` 댓글, PR 활동 구독, 작업 브랜치로 `build-apk.yml` 수동 실행.
병합·APK 릴리스·master push는 허락하지 않았다.

1. **최신화**: `git fetch origin`, `git status`(미커밋 변경 보존), 열린 PR과 그 변경 파일, `docs/plans/`, `STATUS.md` 확인.
   다른 PR이 같은 파일을 바꾸고 있으면 그 PR 위에 쌓거나(base = 그 브랜치) 사용자에게 묻는다.
   사용자 PC에서는 훅이 실행되는 `~/claude-approver`가 아니라 별도 worktree에서 작업한다.
2. **계획**: `docs/plans/YYYY-MM-DD-<주제>.md`(목표·범위·변경 파일·위험·검증)를 작업 브랜치에 커밋하고 draft PR을 연다.
3. **Codex 계획 검토** (아래 호출 모드). P0/P1(로컬은 `VERDICT: REVISE`)이 나오면 계획을 고쳐 다시 검토받는다.
   승인 전에는 4단계로 가지 않는다. 5회 안에 승인되지 않으면 사용자에게 보고하고 결정을 기다린다.
4. **구현 + 검사**: `ls hook/*.js | xargs -n1 node --check` 통과. 안드로이드 코드를 바꿨으면 APK 빌드 성공을 확인한다.
   실기기·실제 ntfy로 확인하지 못한 부분은 PR과 보고에 그대로 적는다.
5. **Codex 코드·보안 검토**
6. **반영**: P0/P1은 고친다. 동의하지 않는 지적은 근거를 그 리뷰 스레드에 답한다. 다시 검토 요청, 최대 5회.
   5회 뒤에도 P0/P1이 남으면 **차단**: ready로 바꾸지 않고 남은 지적을 사용자에게 보고한 뒤,
   사용자가 해결하거나 명시적으로 보류를 결정할 때까지 멈춘다.
7. **PR 준비**: 마지막 커밋의 Codex 리뷰가 P0/P1 없이 끝나고 검사·빌드가 통과하면 PR을 ready for review로 바꾼다.
   ready 뒤에 P0/P1이 나오면 draft로 되돌리고 6단계로 돌아간다.
8. **보고**: 바뀐 것, Codex 지적과 처리 결과, 검사 결과, 남은 위험. 병합하지 않는다.

오타·문서 한 줄 같은 사소한 변경은 2~3단계를 건너뛴다.

### Codex 호출 모드

`codex login status`로 판별한다.

**로컬 CLI 모드** (로그인됨 — 사용자 PC·WSL)

- 계획 검토: `codex exec -s read-only "Review the plan in docs/plans/<파일>: correctness, risks, missing steps, security. End with VERDICT: APPROVED or VERDICT: REVISE"`
- 코드 검토: `codex review --base <PR의 실제 base 브랜치>` (스택 PR이면 master가 아님)
- Claude Code에 codex-review 플러그인이 있으면 위 두 단계 대신 `/codex-review:plan`, `/codex-review:code`를 써도 된다.
- 독립 기능 위임: 별도 worktree를 만들고 `codex exec -s workspace-write -C <worktree 경로> "<자급식 프롬프트>"`

**GitHub 모드** (그 외 — claude.ai 클라우드 세션 등)

- PR에 댓글: 계획은 `@codex review for problems in the plan docs/plans/<파일>`, 코드는 `@codex review`,
  보안이 걸린 변경은 `@codex review for security issues: <걸린 부분>`으로 초점을 준다.
  (`@codex security review`는 Pro·Business·Edu·Enterprise 전용이라 ChatGPT Plus에서는 결과가 오지 않는다.)
- PR 활동을 구독한다. Codex(`chatgpt-codex-connector[bot]`)는 요청에 👀로 반응하고 "Codex Review Summary" 댓글을 갱신하며,
  지적이 있으면 리뷰를 게시한다(이벤트로 도착). 지적이 없으면 👍 반응만 남겨 이벤트가 오지 않으므로,
  10분 안에 리뷰가 없으면 요약 댓글과 반응을 직접 확인한다.
- ready 전환만으로는 자동 리뷰가 돌지 않을 수 있다(저장소의 Codex 자동 리뷰 설정에 따름). 마지막 커밋의 리뷰도
  draft 상태에서 요청하고, 끝난 뒤에 ready로 바꾼다.
- `@codex`라는 글자는 백틱 안에 있어도 Codex를 부른다. 댓글·답글에는 실제로 호출할 때만 쓴다.
  설명용 답글에 넣으면 Codex가 작업 요청으로 받는다.
- 몇 분이 지나도 👀 반응이 없으면 사용자에게 Codex code review 설정을 확인해 달라고 한 번 알린다.

`codex-dispatch` 스킬은 쓰지 않는다. 그 스킬이 요구하는 `codex mcp-server`가 현재 Codex CLI(0.162.1)에 없다.

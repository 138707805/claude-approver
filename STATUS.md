# Claude Approver — 진행 상황 (2026-08-17 기준)

새 채팅방에서 이어서 작업할 때 참고용으로 저장한 문서입니다.

## 한 줄 요약

Claude Code가 터미널에서 권한 승인을 물어볼 때, PC 앞에 없어도 휴대폰 알림으로
받아서 허용/거부할 수 있게 해주는 도구. Node.js 훅(PC) + 안드로이드 앱(폰) +
ntfy.sh(무료 중계 서비스)로 구성. **현재 v1.9까지 배포 완료, 실사용 중.**

## 링크

- GitHub 저장소 (public): https://github.com/138707805/claude-approver
- 최신 APK: https://github.com/138707805/claude-approver/releases/download/v1.9/claude-approver.apk
- 로컬 소스 경로: `/home/user/claude-approver`

## 아키텍처

- **PC → 폰**: Claude Code `PreToolUse`/`Stop`/`Notification` 훅(Node.js, 외부
  의존성 없음)이 `ntfy.sh`에 메시지를 publish.
- **폰**: 안드로이드 앱(Kotlin)이 foreground service로 ntfy.sh 스트림을 구독,
  시스템 알림으로 표시. 버튼 응답은 다시 ntfy.sh에 publish.
- **폰 → PC**: 훅이 응답 topic을 스트리밍으로 기다렸다가 `permissionDecision`
  (`allow`/`deny`/미출력=폴백)을 stdout JSON으로 반환.
- 페어링은 무작위 문자열(`ca-xxxx...`) 하나로, `<code>-ask` / `<code>-reply`
  두 개의 ntfy 토픽을 유도해서 사용. 인증서버 없음, ntfy.sh는 완전 무료/공개.

## 파일 구조

```
claude-approver/
  hook/
    permission-relay.js       PreToolUse 훅 — 승인/거부 중계 + 항상허용 기억 + PC전용 처리
                               + 자동 허용 모드(평범한 요청 즉시 자동 허용)
    task-complete-notify.js   Stop 훅 — 작업 완료 알림 + 사용량 스냅샷 + 폰 입력 대기
    stop-failure-notify.js    StopFailure 훅 — API 오류(한도 등)로 턴이 끊겼을 때 알림
    attention-notify.js       Notification 훅 — 컴퓨터 확인 필요 알림 (권한 요청 제외하고 필터링)
    notify-common.js          Stop/StopFailure/Notification 훅 공통 유틸 (설정 조회/상태 파일 포함)
    usage.js                  ~/.claude/projects/**/*.jsonl 파싱해 토큰 사용량 집계
    setup.js                  설정 스크립트 (재실행해도 안전 — 기존 훅 지우고 재등록)
  android/                    Kotlin/Gradle 프로젝트
    debug.keystore             고정 서명 키 (재생성 금지 — 하면 기존 설치와 업데이트 호환 깨짐)
    app/src/main/java/com/claudeapprover/
      service/RelayService.kt        foreground service, 알림 표시, 20초 조용한 정정창(undo) 로직
      service/ResponseHelper.kt      허용/거부 응답을 실제로 ntfy에 전송하는 공통 로직
      ui/MainActivity.kt             페어링 입력, 최근 요청 목록, 인앱 승인/거부
      data/Prefs.kt, RequestItem.kt  로컬 저장 (SharedPreferences)
      net/NtfyClient.kt              ntfy.sh publish/streaming GET
  .github/workflows/build-apk.yml   GitHub Actions로 APK 빌드 (android-actions/setup-android
                                     + gradle/actions/setup-gradle, gradle wrapper 없음)
  README.md                   사용자용 설치/사용 설명서
```

## 자동 허용 모드

앱 메인 화면의 "자동 허용 모드" 스위치가 켜져 있으면, `permission-relay.js`는
폰에 승인 요청 알림을 아예 띄우지 않고 요청이 들어오는 즉시 스스로 판단한다
(응답을 기다리지 않음 — v1.6~v1.7까지는 170초 타임아웃 이후에만 자동 허용이
적용됐는데, 그동안은 계속 승인 요청 알림이 뜬다는 피드백을 받아 v1.8에서
"즉시 판단"으로 바꿨다):

- **평범한 요청**(터미널 명령, 파일 편집 등 대부분)은 사람 확인 없이 즉시
  자동 허용. 알림 없이 "최근 요청" 목록에만 조용히 기록됨.
- **권한/보안 관련으로 보이는 요청**은 모드가 켜져 있어도 자동 허용하지
  않고 곧바로(기다리지 않고) "컴퓨터에서 확인해주세요" 알림으로 넘김 —
  결정은 항상 터미널에서 사람이 내림. 판단 기준은 `permission-relay.js`의
  `HIGH_RISK_BASH_PATTERNS` / `HIGH_RISK_PATH_PATTERNS` /
  `HIGH_RISK_URL_PATTERNS` 목록 — sudo/su, 전체 권한 부여(chmod 777 등),
  SSH 키/자격증명 파일 접근, 방화벽/보안모듈 변경, 원격 스크립트 다운받아
  바로 실행(`curl ... | bash`), 계정/사용자 관리, 크론/systemd 지속성 설정,
  셸 시작 스크립트(.bashrc 등) 변경, 클라우드 IAM 권한 부여, CI 워크플로
  파일(.github/workflows) 변경, 클라우드 메타데이터 서버 접근(SSRF) 등.
- 작업 완료 알림(Stop 훅)은 모드와 무관하게 항상 그대로 온다.
- 모드가 꺼져 있으면 예전 방식 그대로: 모든 요청이 폰에 승인 요청 알림으로
  뜨고, 사람이 직접 허용/거부해야 한다(자동 판단 없음).
- 스위치를 바꾸면 앱이 ntfy의 `<페어링코드>-settings` 토픽에 값을
  publish해두고, PC 훅은 매 요청마다(빠른 폴링, 수 초 이내 실패 시 폴백)
  그 토픽에서 최신 값을 가져와 판단한다. 폰이 한 번도 스위치를 안 건드렸거나
  ntfy 캐시(기본 12시간)가 만료됐으면 `~/.claude/claude-approver.json`의
  `autoApproveMode` 값으로 대체한다(필드가 없으면 기본 true). PC 훅은
  폰에서 가져온 최신 값을 이 파일에도 다시 저장해두므로, 캐시가 나중에
  사라져도 마지막으로 알려진 값은 남는다.
- 이건 규칙 기반 필터일 뿐이라 완벽하지 않음 — 애매하면 사람에게 넘기는
  쪽으로 짜여 있음(과소허용이 과다허용보다 안전하다는 원칙). 새로운 위험
  패턴이 생기면 이 목록에 추가해야 걸러진다.

## PC 쪽 실제 설정 상태 (이 컴퓨터)

- `~/.claude/claude-approver.json` — 페어링 코드/토픽/타임아웃(170초),
  `autoApproveMode: true` 저장됨
- `~/.claude/settings.json` — `hooks.PreToolUse`(matcher:
  `Bash|Edit|Write|NotebookEdit|WebFetch|ExitPlanMode`), `hooks.Stop`,
  `hooks.Notification` 세 개 등록됨. 원본은 `settings.json.claude-approver-backup`
  으로 백업돼 있음.
- `~/.claude/claude-approver-allowlist.json` — "항상 허용" 기억 목록 (Bash는
  명령어 전체, Write/Edit는 파일 경로 기준 exact match)

## 버전 히스토리 (기능 요약)

- **v1.0**: 최초 배포. 승인 요청 알림 + 허용/거부 버튼. Bash/Edit/Write만 대상.
- **v1.1**: "항상 허용" 기억(같은 요청 재발 시 자동 승인), 알림을 실수로 지워도
  앱 "최근 요청" 목록에서 직접 승인/거부 가능. 서명을 고정 keystore로 전환
  (이때부터 삭제 없이 덮어 설치 가능해짐 — v1.0→v1.1만 예외적으로 재설치 필요).
- **v1.2**: 허용/거부를 눌러도 5초간 "취소 가능" 상태로 대기 (실수 탭 방지),
  실행취소 가능. 알림에서 눌러도 앱에서 눌러도 동일하게 적용.
- **v1.3**: 감시 대상 도구 확대(NotebookEdit/WebFetch/ExitPlanMode 추가).
  ExitPlanMode(계획 승인)는 폰에서 blind 판단 불가하다고 보고 "컴퓨터에서
  확인" 알림만 보내고 결정은 항상 터미널에 맡김. 자동승인/타임아웃 시에도
  폰에 상황을 알림. Stop 훅으로 "작업 완료" 알림 추가.
- **v1.4**: 자동 승인(항상 허용) 항목은 알림 없이 앱 히스토리에만 조용히
  기록되도록 변경 (알림이 너무 많다는 피드백 반영).
- **v1.5**: 허용/거부를 누르면 뜨던 "처리 중... 5초 안에 취소" 알림을 제거 —
  화면을 가리는 게 불편하다는 피드백 반영. 이제 버튼을 누르면 원래 알림이
  즉시 조용히 닫히고, 20초 동안은 앱의 "최근 요청" 목록에서만(별도 알림 없이)
  정정할 수 있다. 이 20초는 PC 쪽 훅이 응답을 기다리는 시간(170초)보다 훨씬
  짧게 잡아야 정정이 실제로 반영되므로, 무한정 정정 가능한 건 아니라는 점을
  사용자에게 안내했음.
- **v1.6**: 부재중(폰 응답 없음) 타임아웃 시 평범한 요청은 자동 허용, 권한/
  보안 관련 요청은 여전히 사람 확인 필요하도록 하는 기능 추가.
- **v1.7**: v1.6에서 추가한 스위치 위젯이 이 앱의 테마(Material2)와 안 맞아
  앱이 켜자마자 튕기는 버그 수정(MaterialSwitch → SwitchMaterial).
- **v1.9**: (1) 폰에서 다음 지시를 보내 대화를 이어가는 "폰 입력 모드" 추가,
  (2) 작업 완료 알림이 안 오던 버그 3종 수정, (3) Claude 상태/사용량 표시 추가,
  (4) 앱 아이콘 교체. 자세한 내용은 아래 "v1.9에서 바뀐 것" 절 참고.
- **v1.8**: 자동 허용 모드가 켜져 있으면 승인 요청 알림 자체를 아예 안 띄우고
  즉시 판단하도록 재설계 — v1.6~v1.7 방식은 모드를 켜놔도 매번 승인 요청
  알림이 뜨고 170초를 기다려야 자동 허용됐는데, "왜 계속 승인 요청이 오냐"는
  피드백을 받고 고침. 설정 필드명도 `autoApproveWhenUnreachable` →
  `autoApproveMode`로 바꿈("부재중일 때만"이 아니라 "항상 즉시"로 의미가
  바뀌었으므로).

## v1.9에서 바뀐 것 (상세)

### 1. 폰 입력 모드 — 폰에서 다음 지시 보내기

앱의 "폰 입력 모드" 스위치가 켜져 있으면, Stop 훅이 완료 알림을 보낸 뒤
`<페어링코드>-prompt` 토픽을 구독하고 최대 4분간(`remoteInputWaitSeconds`,
기본 240초) 기다린다. 프롬프트가 오면 훅이 stdout으로
`{"decision":"block","reason":"<입력 내용>"}` 을 출력한다 — 이게 Stop 훅이
턴을 이어가게 만드는 **Claude Code 공식 규격**이다(문서: Stop decision control).
안 오면 아무것도 출력하지 않고 끝나서 평소대로 터미널 대기 상태가 된다.

- 완료 알림에 안드로이드 **RemoteInput**(알림창 안 답장 입력칸)을 달아서, 앱을
  열지 않고 알림에서 바로 입력할 수 있다. RemoteInput용 PendingIntent는
  반드시 `FLAG_MUTABLE`이어야 한다(시스템이 입력값을 넣어줘야 하므로).
- **연속 8번 제한(중요)**: Claude Code는 Stop 훅이 연속으로 턴을 이어붙이는 걸
  8번까지만 허용하고 그 다음부터는 훅 출력을 무시한다. 우리가 못 늘리는
  플랫폼 제한이다. 그래서 세션별 카운트를 `~/.claude/claude-approver-state.json`
  에 저장해서 남은 횟수를 폰에 보여주고, 다 쓰면 "폰 입력 한도에 도달했어요"
  알림을 보낸다. `stop_hook_active`가 false면(사람이 터미널에서 보낸 새 턴)
  카운트를 0으로 되돌린다. **"무한정 이어갈 수 있다"고 속이지 말 것** —
  더 늘려달라는 요청이 오면 이 제한을 설명해야 한다.
- Stop 훅 타임아웃을 15초 → 600초로 올렸다(`setup.js`의 `STOP_HOOK_TIMEOUT`).
  대기 시간은 훅 안에서 540초로 상한을 걸어 타임아웃보다 항상 짧게 유지한다 —
  타임아웃에 잘리면 그 사이 폰에서 보낸 지시가 그냥 버려지기 때문.
- 기본값은 **꺼짐**. 켜져 있으면 매 턴 끝마다 터미널이 그만큼 멈춰 있게 된다.

### 2. "작업 완료 알림이 안 온다" — 원인 3가지 (전부 수정)

1. **(가장 큰 원인) 스트리밍 연결의 읽기 타임아웃이 0(무한)이었다.**
   `NtfyClient.streamingClient`가 `readTimeout(0)`이라, 폰이 네트워크를
   갈아타거나 절전 모드로 들어가 TCP 연결이 *조용히* 죽으면 소켓은 열려 있는
   것처럼 보이지만 아무것도 안 들어온다 → 읽기에서 영원히 멈춰 재연결도 못 하고
   알림이 통째로 끊긴다. ntfy는 45초마다 keepalive를 보내므로 **90초**로 바꿔서
   죽은 연결을 감지하고 재연결하게 했다. 장시간 조용한 상태(자동 허용 모드가
   켜져 있으면 완료 알림 말고는 트래픽이 없다)에서 특히 잘 터지던 조건이다.
2. **재연결할 때 `since=now-5`라서 끊긴 동안 온 메시지를 잃었다.**
   이제 마지막으로 본 이벤트 시각(`Prefs.lastEventEpochSec`, keepalive에도 갱신)
   부터 이어받고, 겹쳐 받은 건 ntfy 메시지 id로 걸러낸다(최근 200개 기억).
   거슬러 올라가는 상한은 12시간(ntfy 무료 캐시 보존 기간과 동일).
3. **API 오류로 끝난 턴은 Stop 훅이 아예 실행되지 않는다** — 그때는
   `StopFailure` 훅이 대신 실행된다(문서 확인함). 이게 없어서 사용량 한도에
   걸려 멈춘 경우 폰에 아무 소식도 안 갔다. `stop-failure-notify.js` 추가.

추가로 완료 알림 본문을 트랜스크립트 파싱 대신 Stop 훅 입력의
`last_assistant_message` 필드에서 가져오게 바꿨다(공식 문서 권장 —
Stop 시점에 트랜스크립트 파일에 마지막 메시지가 아직 없을 수 있음).

### 3. Claude 상태 / 사용량

- `usage.js`가 `~/.claude/projects/**/*.jsonl`을 파싱해서 오늘 / 현재 5시간
  블록 / 모델별 토큰을 합산한다(최근 36시간 내 수정된 파일만 읽어서 0.25초 수준).
  중복은 `message.id`로 거른다. 5시간 블록 계산은 ccusage와 같은 방식
  (첫 활동 시각을 정시로 내림, 5시간 경과 또는 5시간 공백이면 새 블록).
- 이 스냅샷을 Stop / StopFailure 메시지에 실어 보내고, 앱이 첫 화면에 표시한다.
- **"구독 한도의 몇 %" 는 절대 표시하지 않는다** — 그 한도 값은 로컬 어디에도
  없다(`~/.claude` 전체를 뒤져서 확인함; 트랜스크립트의 `rateLimits` 필드는
  오류 레코드에만 있고 항상 null). 추측해서 보여주면 사용자가 틀린 숫자를
  믿게 되므로, 실제로 셀 수 있는 것만 낸다. 나중에 이 기능을 확장하라는
  요청이 와도 같은 원칙을 지킬 것.
- 상태(작업 중/대기 중/멈춤)는 별도 훅 없이 기존 메시지에 얹어서 판단한다 —
  PreToolUse가 보내는 `info`/`approval` 메시지가 오면 "작업 중",
  Stop의 `status`가 오면 "대기 중", StopFailure의 `errorType`이 있으면 "멈춤".

### 4. 아이콘

`ic_launcher_background.xml`(남보라→보라 대각선 그라데이션 + 우상단 광원) +
`ic_launcher_foreground.xml`(방패 안에 체크를 `evenOdd`로 뚫은 형태, 흰색
그라데이션) + `ic_launcher_monochrome.xml`(안드로이드 13+ 테마 아이콘용).
전부 벡터 XML이라 로컬에 이미지 도구 없이도 CI에서 빌드된다.
알림 아이콘(`ic_stat_notify.xml`)도 같은 모양의 실루엣으로 맞췄다.

**아이콘을 눈으로 확인하는 방법**(로컬에 이미지 도구 없음): 벡터 XML과 같은
모양의 SVG를 HTML에 넣고 Playwright의 chromium-headless-shell로 스크린샷을
찍으면 된다. 이 샌드박스엔 공유 라이브러리가 없어서 그냥은 실행이 안 되고,
이전 세션이 추출해둔 것을 `LD_LIBRARY_PATH`로 붙여야 한다 —
`find /tmp/claude-1000 -iname "libnspr4.so"` 로 찾을 것.

## 알림 종류 (앱 내부 `type` 필드 기준)

| type | 상황 | 알림 | 버튼 | 히스토리 기록 |
|---|---|---|---|---|
| `approval` | 실제 승인 필요 | O (고우선순위) | 허용/거부 | O (PENDING) |
| `info` | 항상허용으로 자동승인 | X (v1.4부터) | 없음 | O (자동 허용됨) |
| `attention` | 폰으론 판단 불가/타임아웃 | O (보통 우선순위) | 없음 | O (컴퓨터 확인 필요) |
| `status` | 한 턴 작업 완료 (Stop 훅) | O | 폰 입력 모드면 답장/기다리지않기 | X (기록 안 함) |

## 알아두면 좋은 환경/운영 메모

- 이 개발 환경(sandbox)엔 Java/Gradle/Android SDK가 없어서 APK를 로컬에서
  못 만듦 → GitHub Actions로 빌드. `gh` CLI도 로컬에 없어서 `~/.local/bin`에
  수동 설치했고, `gh auth login --web` 기기 인증으로 로그인함. 워크플로 파일
  push에는 `workflow` OAuth scope가 추가로 필요해서 `gh auth refresh -s workflow`
  한 번 더 필요했음.
- **저장소 visibility가 가끔 자기도 모르게 private로 바뀐 적이 있었음** —
  새 릴리스 링크를 사용자에게 주기 전에 항상
  `gh api repos/138707805/claude-approver --jq '.visibility'` 로 `public`인지
  확인하고, 아니면 `gh repo edit ... --visibility public --accept-visibility-change-consequences`
  로 되돌릴 것.
- **카카오톡 인앱 브라우저로 GitHub 릴리스 링크를 열면 다운로드가 깨짐**
  (404 등) — 항상 "다른 브라우저로 열기"(Chrome 등)로 열라고 안내할 것.
- 새 APK를 낼 때마다: `dist/` 폴더를 지우고 다시 `gh run download`로 받아서
  (예전에 캐시된 파일을 실수로 재업로드한 적 있음 — 파일 크기/해시로 새
  빌드인지 항상 확인) → `gh release create vX.Y ...`.
- `android/debug.keystore`는 절대 재생성하지 말 것 — 재생성하면 기존 설치된
  앱과 서명이 달라져서 사용자가 다시 삭제 후 설치해야 함.

## 아직 안 한 것 / 앞으로 고려할 만한 것

- 딱히 열려 있는 버그나 미완료 작업 없음. 현재 v1.9가 최신.
- v1.9 관련해서 알아두면 좋은 한계:
  - 폰 입력 모드가 켜진 채로 폰이 꺼져 있으면 매 턴 끝마다 최대 4분씩
    터미널이 멈춘다. 앱이 켜질 때마다 설정을 다시 publish해서 캐시를
    살려두긴 하지만(ntfy 캐시 12시간), 근본적으로는 스위치를 꺼야 한다.
    터미널에서 Ctrl-C로도 빠져나올 수 있다.
  - 여러 Claude Code 세션이 동시에 폰 입력을 기다리면 먼저 받는 쪽이
    가져간다. 알림에서 답장하는 경우는 세션 id가 실려 있어 정확히 그
    세션으로 가지만, 앱 입력창에서 그냥 보내면 지목이 없다.
- 사용자가 원하면 추가할 수 있는 것들(요청받은 적은 없음, 아이디어 수준):
  - 앱 안에 버전 표시(현재는 안드로이드 시스템 설정에서만 확인 가능)
  - 항상허용 목록을 앱에서 직접 보고 지울 수 있는 화면 (현재는 PC에서 파일
    직접 삭제해야 함)

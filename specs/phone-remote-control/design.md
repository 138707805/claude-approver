# 설계 — 알림 전체보기 / 폰 원격 조작 / 설정 화면 분리

`requirements.md`의 요구사항을 구현하는 방법. 기존 아키텍처(PC 훅 4개 +
안드로이드 앱 + ntfy.sh pub/sub, `STATUS.md` 참고)를 그대로 확장한다.

## 공통: 알림 크기 한도 헬퍼

`hook/notify-common.js`에 추가:

```js
function truncateForNtfy(text, max) {
  const s = String(text || "");
  if (Buffer.byteLength(s, "utf8") <= max) return s;
  // 바이트 기준으로 안전하게 자르고(멀티바이트 문자 중간에서 안 잘리게),
  // 실제로 잘렸을 때만 표시를 붙인다.
  let cut = s.slice(0, max);
  while (Buffer.byteLength(cut, "utf8") > max) cut = cut.slice(0, -1);
  return cut + "\n\n…(내용이 길어 일부 생략됨)";
}
```

`permission-relay.js`는 `notify-common.js`를 import하지 않고 자체 복사본을
갖고 있으므로(설계 노트: "permission-relay.js는 승인 응답을 기다려야 해서
별도로 자기 코드를 갖고 있다"), 이 함수만 `permission-relay.js`에도 동일하게
복사해 둔다(기존 코드 스타일 유지 — 괜히 지금 와서 공용 모듈로 합치지 않음).

적용 지점:
- `task-complete-notify.js`: `lastMessage.slice(0,300)` → `truncateForNtfy(lastMessage, 1500)`
- `permission-relay.js` `summarize()`의 default 분기: `JSON.stringify(input).slice(0,500)`
  → `truncateForNtfy(JSON.stringify(input), 1500)`. Bash/Write/Edit 분기의 body도
  `truncateForNtfy(body, 3000)`로 감싸서 극단적으로 긴 명령어/설명에 대한
  안전판만 추가(평소엔 안 잘림).
- `stop-failure-notify.js`: `detail.slice(0,300)` → `truncateForNtfy(detail, 1500)`

## 요구사항 A: 히스토리 상세 다이얼로그

`MainActivity.kt`:
- `renderHistory()`에서 각 `itemBinding.root.setOnClickListener { showDetailDialog(item) }` 추가.
- `showDetailDialog(item: RequestItem)`: `AlertDialog.Builder`로 제목 = item.title,
  메시지 = item.body + "\n\n도구: ${item.tool}\n폴더: ${item.cwd}\n시각: ${...}"
  형태의 스크롤 가능한 TextView(`setView`로 padding 있는 스크롤 TextView 주입,
  `TextView.setTextIsSelectable(true)`로 복사 가능하게). 닫기 버튼만.

`Prefs.kt`에 추가:
```kotlin
var lastStatusTitle: String
var lastStatusBody: String
```
`RelayService.handleStatusMessage()`에서 알림 발송 직전에 `prefs.lastStatusTitle = title; prefs.lastStatusBody = body`
저장(이미 만들어진 `title`/`body` 지역변수 재사용).

`activity_main.xml`의 상태 카드 안, `stateDetail` 아래에 "전체 보기" `TextView`
(clickable, 링크 색) 추가 → `MainActivity`에서 `lastStatusBody`가 비어있지 않을
때만 보이게 하고 클릭 시 같은 `showDetailDialog`류 함수(제목/본문만 있는 버전)
호출.

## 요구사항 B: 상시 데몬 + 유휴 감지

### 공유 상태: `~/.claude/claude-approver-state.json`

기존 구조 `{ sessions: {...}, consumedPrompts: [...] }`에 최상위 `daemon` 객체 추가:

```json
{
  "sessions": {},
  "consumedPrompts": [],
  "daemon": {
    "lastCwd": "/home/user/claude-approver",
    "lastSessionId": "…",
    "waitingUntil": 0,
    "waitingSessionId": "",
    "busyUntil": 0
  }
}
```

`notify-common.js`에 추가하는 헬퍼(모두 `loadState`/`saveState`를 감싸는 얇은
함수, 파일 락은 안 씀 — 기존 프로젝트도 안 쓰고 있고 충돌 빈도가 낮음):

```js
function touchBusy(minutes = 6) { const s = loadState(); s.daemon = s.daemon || {}; s.daemon.busyUntil = Date.now() + minutes*60000; saveState(s); }
function clearBusy() { const s = loadState(); s.daemon = s.daemon || {}; s.daemon.busyUntil = 0; saveState(s); }
function markWaiting(sessionId, untilMs) { const s = loadState(); s.daemon = s.daemon || {}; s.daemon.waitingUntil = untilMs; s.daemon.waitingSessionId = sessionId; saveState(s); }
function clearWaiting() { const s = loadState(); s.daemon = s.daemon || {}; s.daemon.waitingUntil = 0; s.daemon.waitingSessionId = ""; saveState(s); }
function touchLastCwd(cwd, sessionId) { if (!cwd) return; const s = loadState(); s.daemon = s.daemon || {}; s.daemon.lastCwd = cwd; if (sessionId) s.daemon.lastSessionId = sessionId; saveState(s); }
```

호출 지점:
- `permission-relay.js` `main()` 진입 직후(승인 요청이든 자동허용이든 도구가
  호출됐다는 것 자체가 "작업 중" 신호): `touchBusy()`, `touchLastCwd(payload.cwd)`.
  이 파일은 `notify-common.js`를 쓰지 않으므로 위 5개 헬퍼도 동일 코드로 복사.
- `task-complete-notify.js`: `waitForPrompt` 호출 직전에 `markWaiting(sessionId, Date.now()+waitMs)`,
  `waitForPrompt` 리턴 후(프롬프트를 받았든 취소/타임아웃이든) `clearWaiting()`.
  `willWait`가 false로 끝나는 경로(턴이 완전히 끝남)에서는 `clearBusy()`도 호출.
  `touchLastCwd(payload.cwd, sessionId)`는 함수 최상단에서 매번.
- `stop-failure-notify.js`: 알림 전송 후 `clearBusy()`, `clearWaiting()`.

### `hook/daemon.js` (신규)

- CLI 진입점: `node hook/daemon.js` (foreground로 실행, Ctrl+C로 종료. 백그라운드로
  두려면 `nohup node hook/daemon.js > ~/.claude/claude-approver-daemon.log 2>&1 &`
  — README에 안내 추가).
- `loadConfig()`로 pairingCode 확인, 없으면 안내 후 종료.
- `<code>-prompt` 토픽에 스트리밍 GET 연결. **Android `RelayService.streamLoop`와
  동일한 재연결 규율**을 그대로 이식:
  - 읽기 타임아웃 유한값(예: 90초) — v1.9에서 겪은 무한 타임아웃 버그를 처음부터
    피함. Node `https.request`의 `timeout` 옵션 + `req.on("timeout", …)`로 구현
    (소켓이 그 시간 동안 아무 데이터도 못 받으면 강제 재연결).
  - 마지막으로 본 이벤트 시각(`sinceSec`)부터 재연결, 최근 200개 메시지 id로
    중복 제거(간단히 배열+Set, 프로세스 메모리에만 유지 — 데몬 재시작 시 리셋
    되는 건 허용 가능한 손실).
  - 지수 백오프(2s → 최대 30s) 재연결.
- 메시지(`type` 필드 없는 prompt 토픽 메시지, `{id, prompt, sessionId, ts}` 또는
  `{cancel:true}`)를 받으면 `cancel`은 무시(데몬은 취소 대상이 없음 — 취소는
  기존 Stop 훅의 대기만 취소한다), `prompt`가 있으면 `handlePrompt()` 호출.
- `handlePrompt(prompt, promptId)`:
  1. `state = loadState(); const d = state.daemon || {};`
  2. `Date.now() < d.waitingUntil` → 아무것도 안 함(로그만 남김, 살아있는 Stop
     훅이 같은 메시지를 받아 처리할 것).
  3. `Date.now() < d.busyUntil` → `postJson(askTopic, {type:"attention", title:"지금 작업 중이라 못 받았어요", body: prompt, ...})`.
  4. `remoteInputMode` 확인(`fetchLatestSettings` 재사용, 4초 타임아웃) → false면
     `attention` 알림("폰 입력 모드가 꺼져 있어요") 후 종료.
  5. 위 세 조건 다 통과 → **깨우기**: `spawnHeadless(prompt, d.lastCwd)`.
- `spawnHeadless(prompt, cwd)`:
  - `const dir = cwd || os.homedir();`
  - `child_process.spawn("claude", ["-p", prompt, "--continue"], { cwd: dir, stdio: "ignore", detached: true })`
    → `child.unref()`로 데몬 프로세스와 독립적으로 살아있게 함(데몬이 나중에
    죽어도 이미 시작한 세션은 끝까지 감).
  - `--continue`가 "그 폴더에 이전 대화 없음"으로 실패하는 경우를 대비해,
    `stdio: "pipe"`로 짧게(첫 출력 몇 초) 지켜보다가 에러 패턴이 보이면 죽이고
    `--continue` 없이 재시도 — **정확한 에러 문구는 구현 단계에서 실제로 실행해
    확인 후 코드에 반영**(설계 시점엔 추정 불가).
  - 성공적으로 시작되면 해당 프로세스가 기존 PreToolUse/Stop 훅을 그대로 태우므로
    데몬은 더 관여하지 않는다.
- 하트비트: `setInterval(() => postJson(settingsTopic, {daemonAlive:true, updatedAt: Date.now()}), 60_000)`.
  기존 settings 토픽 메시지 형식(`autoApproveMode`/`remoteInputMode`)에 필드만
  추가하는 것이라 기존 훅의 `fetchLatestSettings` 파싱(boolean 필드만 골라 담는
  방식)에 영향 없음 — 다만 그 함수가 `daemonAlive`도 같이 읽어가게 화이트리스트에
  추가해야 앱이 최신값을 알 수 있음(아래 앱 쪽 참고).

### 앱 쪽 데몬 상태 표시

- `RelayService.handleStreamLine`이 이미 `type` 없는 settings 메시지는 안 보므로
  (settings 토픽은 별도), 대신 **`RelayService`가 주기적으로(예: 앱이 포그라운드일
  때 60~90초 간격, 또는 `republishSettings()` 옆에 같이) settings 토픽을 한 번
  폴링**해서 `daemonAlive`/`updatedAt`을 읽어 `Prefs`에 저장하는 방식이 지금
  구조(스트리밍은 ask 토픽만 구독)에 제일 잘 맞는다. `NtfyClient`에 이미 있는
  publish 외에 "최근 값 한 번 읽기" 함수가 없다면 간단한 `NtfyClient.fetchLatestJson(topic)`
  하나 추가(PC의 `fetchLatestSettings`와 동일한 패턴 — `/json?poll=1&since=all` GET).
- `Prefs`에 `daemonAliveAt: Long` 추가.
- `MainActivity`의 "다음 지시 보내기" 카드에서 `promptWaitingText` 로직을 확장:
  - `isAwaitingPrompt` → "지금 보내면 바로 이어집니다" (기존)
  - else `daemonAliveAt`이 150초 이내 → "지금 보내면 새로 시작해요"
  - else → "PC에서 데몬을 켜야 언제든 지시가 가능해요 (`node hook/daemon.js`)"

## 요구사항 C: 설정 화면

- `SettingsActivity.kt` 신규, `ActivityMainBinding`처럼 `ActivitySettingsBinding`
  뷰바인딩 사용(`build.gradle.kts`에 이미 viewBinding 활성화돼 있을 것 — 기존
  `ItemHistoryBinding` 사용 확인됨).
- `activity_settings.xml`: `activity_main.xml`에서 페어링 코드 카드 + 사용량
  카드(오늘/블록/평소대비 게이지 포함) 그대로 옮겨옴. 상단에 뒤로가기용
  타이틀바(간단히 `<TextView>설정</TextView>` + `finish()` 호출하는 뒤로가기
  아이콘, 또는 `AppCompatActivity` 기본 ActionBar의 up 버튼 사용 — 테마가
  `NoActionBar`이므로 커스텀 상단 바를 LinearLayout으로 구성).
- `MainActivity.kt`에서 이동하는 코드: `onConnectClicked`, `startMonitoring`,
  `stopMonitoring`, `requestBatteryOptimizationExemption`, `publishSettings`(연결
  관련 부분은 유지, 스위치 관련은 메인에 남음 — 주의: `publishSettings()`는
  auto/remote 스위치 값을 보내는 함수라 **메인에 남아야 함**, 페어링 이동과
  무관), `copyCode`, `formatUsage`, `renderBaseline`, `applyGauge`, `compact`,
  `modelLabel`, `clock`, `duration`(이 중 `duration`/`relativeTime`은 상태 카드
  쪽에서도 쓰므로 공용 유틸로 남기거나 두 Activity에 각각 두는 대신 — 간단하게
  `MainActivity`에 `relativeTime`은 남기고 `SettingsActivity`엔 필요한 것만 복사,
  중복 코드보다 억지 공용화가 더 나쁘지 않은 수준이면 그대로 둠).
- `MainActivity.activity_main.xml` 최상단에 타이틀 옆 톱니바퀴 `ImageButton`
  (`android:src`는 플랫폼 기본 `@android:drawable/ic_menu_manage`류 아이콘 사용 —
  새 벡터 아이콘 리소스 추가 없이 처리해 아이콘 제작 비용 없앰) 추가, 클릭 시
  `startActivity(Intent(this, SettingsActivity::class.java))`.
- 페어링 안 됨 배너: `activity_main.xml`에 `pairingBanner`(LinearLayout, 평소
  `visibility=gone`) 추가, `MainActivity.updateStatusUi()`에서
  `prefs.pairingCode.isBlank()`일 때 보이게 하고 탭하면 설정 화면 이동.
- `AndroidManifest.xml`에 `<activity android:name=".ui.SettingsActivity" android:exported="false" />` 추가.

## 파일별 변경 요약

| 파일 | 변경 |
|---|---|
| `hook/notify-common.js` | `truncateForNtfy`, `touchBusy/clearBusy/markWaiting/clearWaiting/touchLastCwd` 추가 |
| `hook/permission-relay.js` | 위 헬퍼 자체 복사본 추가 + 호출, body truncate 적용 |
| `hook/task-complete-notify.js` | truncate 적용, waiting/busy/lastCwd 마킹 호출 |
| `hook/stop-failure-notify.js` | truncate 적용, clearBusy/clearWaiting 호출 |
| `hook/daemon.js` | 신규 — 상시 리스너 + 헤드리스 spawn |
| `hook/setup.js` | 변경 없음(데몬은 훅이 아니라 수동 실행 프로그램이라 settings.json 등록 대상 아님) — README/STATUS.md에 실행법만 추가 |
| `android/.../data/Prefs.kt` | `lastStatusTitle/Body`, `daemonAliveAt` 추가 |
| `android/.../service/RelayService.kt` | `handleStatusMessage`에서 상태 저장, 데몬 하트비트 폴링 |
| `android/.../net/NtfyClient.kt` | `fetchLatestJson(topic)` 추가(있으면 재사용) |
| `android/.../ui/MainActivity.kt` | 상세 다이얼로그, 설정 이동 버튼, 배너, 데몬 상태 텍스트, (페어링/사용량 코드는 SettingsActivity로 이동) |
| `android/.../ui/SettingsActivity.kt` | 신규 |
| `res/layout/activity_main.xml` | 페어링/사용량 카드 제거, 톱니바퀴 버튼 + 배너 + 전체보기 텍스트 + 데몬 상태 텍스트 추가 |
| `res/layout/activity_settings.xml` | 신규(페어링+사용량 카드) |
| `res/layout/item_history.xml` | 변경 없음(클릭 리스너는 코드에서 루트뷰에 건다) |
| `AndroidManifest.xml` | `SettingsActivity` 등록 |
| `res/values/strings.xml` | 신규 문자열 추가 |

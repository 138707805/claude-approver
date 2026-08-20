#!/usr/bin/env node
// Claude Code Stop 훅: 한 턴이 끝날 때마다 휴대폰에 "작업 완료" 알림을 보낸다.
//
// v1.9부터는 여기서 두 가지를 더 한다:
//  1) 사용량 요약(오늘/현재 5시간 블록)을 같이 실어 보내서 앱에서 볼 수 있게 함
//  2) "폰 입력 모드"가 켜져 있으면, 완료 알림을 보낸 뒤 폰에서 다음 지시가
//     올 때까지 기다렸다가 그걸 그대로 Claude에게 이어붙인다
//     (`{"decision":"block","reason":"<입력한 내용>"}` — Stop 훅의 공식 규격).
//
// 폰 입력이 안 오면(시간 초과/모드 꺼짐/오류) 아무것도 출력하지 않고 조용히
// 끝낸다 — 그러면 평소대로 터미널에서 다음 지시를 기다리는 상태가 된다.
"use strict";

const https = require("https");
const {
  loadConfig,
  topicFor,
  readStdin,
  postJson,
  fetchLatestSettings,
  persistSettings,
  loadState,
  saveState,
  truncateForNtfy,
  NTFY_HOST,
} = require("./notify-common");
const usage = require("./usage");

// Claude Code는 Stop 훅이 연속으로 턴을 이어붙이는 걸 8번까지만 허용하고,
// 그 다음부터는 훅 출력을 무시하고 턴을 끝낸다. 우리가 늘릴 수 없는 플랫폼
// 제한이라, 남은 횟수를 세서 폰에 그대로 보여준다.
const MAX_CONTINUATIONS = 8;
const DEFAULT_WAIT_SECONDS = 240;
// setup.js가 이 훅에 걸어둔 타임아웃(600초)보다 반드시 짧아야 한다. 타임아웃에
// 걸려 훅이 강제 종료되면 그 사이 폰에서 보낸 지시가 그냥 버려지기 때문이다.
const MAX_WAIT_SECONDS = 540;

// 폰에서 "미리" 보내둔 지시도 받아들이기 위해, 대기를 시작할 때 이만큼 거슬러
// 올라가서 확인한다. 턴이 끝나기 직전에 보낸 것(=아직 기다리는 중이 아니었던
// 것)이 그냥 버려지지 않게 하려는 것. 같은 지시를 두 번 쓰지 않도록 한 번 쓴
// 프롬프트 id는 상태 파일에 기록해두고 건너뛴다.
const PROMPT_LOOKBACK_SEC = 120;
const MAX_REMEMBERED_PROMPTS = 50;

// 폰이 프롬프트 토픽에 올린 다음 지시를 기다린다. 취소 메시지가 오면 즉시
// 포기하고, 시간이 다 되면 null을 반환한다.
function waitForPrompt(promptTopic, sessionId, timeoutMs, consumedIds) {
  return new Promise((resolve) => {
    const startSec = Math.floor(Date.now() / 1000);
    const sinceSec = startSec - PROMPT_LOOKBACK_SEC;
    let settled = false;
    const finish = (val) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      req.destroy();
      resolve(val);
    };

    const req = https.get(
      {
        hostname: NTFY_HOST,
        path: `/${encodeURIComponent(promptTopic)}/json?poll=false&since=${sinceSec}`,
        headers: { Accept: "application/x-ndjson" },
      },
      (res) => {
        let buffer = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => {
          buffer += chunk;
          let idx;
          while ((idx = buffer.indexOf("\n")) >= 0) {
            const line = buffer.slice(0, idx).trim();
            buffer = buffer.slice(idx + 1);
            if (!line) continue;
            try {
              const envelope = JSON.parse(line);
              if (envelope.event !== "message" || !envelope.message) continue;
              const msg = JSON.parse(envelope.message);
              // 여러 Claude 세션이 동시에 기다릴 수 있으므로, 특정 세션을 지목한
              // 메시지는 그 세션만 가져간다. 지목이 없으면(앱 입력창에서 그냥
              // 보낸 경우) 먼저 받은 쪽이 처리한다.
              if (msg.sessionId && sessionId && msg.sessionId !== sessionId) continue;

              // "기다리지 않기"는 지금 이 대기에 대한 것만 유효하다 — 과거에
              // 눌러둔 취소가 되살아나서 새 대기를 즉시 끝내면 안 된다.
              if (msg.cancel === true) {
                if ((envelope.time || 0) >= startSec) return finish({ cancelled: true });
                continue;
              }

              if (typeof msg.prompt === "string" && msg.prompt.trim()) {
                // 이미 한 번 쓴 지시는 건너뛴다(거슬러 올라가서 읽기 때문에 필요).
                if (msg.id && consumedIds.includes(msg.id)) continue;
                return finish({ prompt: msg.prompt.trim(), promptId: msg.id || "" });
              }
            } catch {
              // 무시하고 다음 줄 계속 처리
            }
          }
        });
        res.on("error", () => finish(null));
        res.on("end", () => finish(null));
      }
    );
    req.on("error", () => finish(null));
    const timer = setTimeout(() => finish(null), timeoutMs);
  });
}

async function main() {
  const config = loadConfig();
  if (!config) return;

  const raw = await readStdin();
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    payload = {};
  }

  const sessionId = payload.session_id || "";
  // Stop 훅이 이어붙인 턴이면 true. 사람이 터미널에서 직접 보낸 새 턴이면
  // false라서, 연속 카운트를 여기서 0으로 되돌릴 수 있다.
  const continuedByHook = payload.stop_hook_active === true;

  const state = loadState();
  if (!state.sessions) state.sessions = {};
  const prior = continuedByHook ? state.sessions[sessionId] : null;
  const usedContinuations = (prior && prior.blockCount) || 0;
  const remaining = Math.max(0, MAX_CONTINUATIONS - usedContinuations);

  // 데몬(hook/daemon.js)이 다음에 "콜드 스타트"할 때 어느 폴더에서 시작할지,
  // 그리고 지금 이 세션이 대기/작업 중인지 판단하는 근거. 여기서부터는
  // saveState(state)가 호출되는 모든 지점에서 이 값이 같이 저장된다 — 별도로
  // 파일을 다시 읽고 쓰는 헬퍼를 쓰면 아래 waitForPrompt(최대 9분 대기) 동안
  // 다른 곳에서 파일을 건드렸을 때 서로 덮어쓰는 경합이 생길 수 있어서, 이미
  // 들고 있는 state 객체를 그대로 갱신하는 방식으로 통일한다.
  state.daemon = state.daemon || {};
  if (payload.cwd) state.daemon.lastCwd = payload.cwd;
  if (sessionId) state.daemon.lastSessionId = sessionId;

  const settingsTopic = topicFor(config, "settings");
  const remoteSettings = await fetchLatestSettings(settingsTopic, 4000);
  if (Object.keys(remoteSettings).length > 0) persistSettings(remoteSettings);
  const remoteInput =
    typeof remoteSettings.remoteInputMode === "boolean"
      ? remoteSettings.remoteInputMode
      : config.remoteInputMode === true; // 기본값 false — 켜야만 기다린다

  // 대화 마지막 문장은 Stop 훅 입력으로 바로 들어온다. 트랜스크립트 파일은
  // 이 시점에 마지막 메시지가 아직 안 들어가 있을 수 있어서 쓰지 않는다.
  const lastMessage = (payload.last_assistant_message || "").trim();
  const body = lastMessage
    ? truncateForNtfy(lastMessage, 1500)
    : "Claude가 응답을 마치고 다음 지시를 기다리고 있어요.";

  let snapshot = null;
  try {
    snapshot = usage.collect();
  } catch {
    // 사용량 집계가 실패해도 알림 자체는 보내야 한다
  }

  const configuredWait = config.remoteInputWaitSeconds || DEFAULT_WAIT_SECONDS;
  const waitMs = Math.max(0, Math.min(configuredWait, MAX_WAIT_SECONDS) * 1000);
  const willWait = remoteInput && remaining > 0 && waitMs > 0;

  await postJson(topicFor(config, "ask"), {
    id: `stop-${Date.now()}`,
    type: "status",
    title: "작업 완료",
    body,
    cwd: payload.cwd,
    sessionId,
    usage: snapshot,
    // 앱이 "답장" 버튼을 띄울지, 몇 초 동안 기다리는지 판단하는 데 쓴다.
    awaitingPrompt: willWait,
    waitSeconds: willWait ? Math.round(waitMs / 1000) : 0,
    remainingContinuations: remaining,
  });

  if (!willWait) {
    // 폰 입력 모드가 켜져 있는데 연속 한도를 다 쓴 경우에만, 왜 안 기다리는지 알려준다.
    if (remoteInput && remaining <= 0) {
      await postJson(topicFor(config, "ask"), {
        id: `limit-${Date.now()}`,
        type: "attention",
        title: "폰 입력 한도에 도달했어요",
        body:
          `폰에서 보낸 지시로 ${MAX_CONTINUATIONS}번 연속 이어서 작업했습니다. ` +
          "Claude Code가 이 이상은 자동으로 이어주지 않아서 이번 턴은 여기서 끝납니다. " +
          "PC에서 아무 지시나 한 번 보내면 다시 폰으로 이어갈 수 있어요.",
        cwd: payload.cwd,
        sessionId,
      });
    }
    if (sessionId) delete state.sessions[sessionId];
    // 턴이 완전히 끝남 = 데몬 입장에서 "이 PC는 이제 진짜 쉬는 중"이라는 신호.
    state.daemon.busyUntil = 0;
    saveState(state);
    return;
  }

  // 지금부터 최대 waitMs(9분 상한)만큼 폰 입력을 기다린다 — 그 사이에 폰에서
  // 지시가 오면(데몬이 유휴로 착각해 새 세션을 겹쳐 시작하지 않도록) 데몬에게
  // "이미 여기서 기다리고 있다"는 걸 알려야 한다.
  state.daemon.waitingUntil = Date.now() + waitMs;
  state.daemon.waitingSessionId = sessionId;
  saveState(state);

  const consumedIds = Array.isArray(state.consumedPrompts) ? state.consumedPrompts : [];
  const result = await waitForPrompt(topicFor(config, "prompt"), sessionId, waitMs, consumedIds);

  // 대기가 끝났으니(응답을 받았든 취소/타임아웃이든) 더는 "기다리는 중"이 아니다.
  state.daemon.waitingUntil = 0;
  state.daemon.waitingSessionId = "";

  if (result && result.prompt) {
    if (result.promptId) {
      consumedIds.push(result.promptId);
      state.consumedPrompts = consumedIds.slice(-MAX_REMEMBERED_PROMPTS);
    }
    if (sessionId) {
      state.sessions[sessionId] = { blockCount: usedContinuations + 1, updatedAt: Date.now() };
    }
    saveState(state);
    // Stop 훅의 공식 규격: block이면 Claude가 멈추지 않고 reason을 지시로 받아 계속한다.
    process.stdout.write(JSON.stringify({ decision: "block", reason: result.prompt }));
    return;
  }

  // 취소했거나 시간이 다 됐으면 평소대로 턴을 끝낸다(터미널에서 이어서 입력).
  // 이것도 턴이 완전히 끝나는 경우이므로 "쉬는 중"으로 표시한다.
  state.daemon.busyUntil = 0;
  if (sessionId) delete state.sessions[sessionId];
  saveState(state);
}

main()
  .catch(() => {})
  .finally(() => process.exit(0));

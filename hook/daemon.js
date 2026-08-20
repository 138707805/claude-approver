#!/usr/bin/env node
// Claude Approver 상시 실행 데몬 — 훅과 달리 한 번 실행되고 끝나는 게 아니라
// 계속 떠 있으면서 폰의 "다음 지시"를 언제든 받는다.
//
// PC가 이미 뭔가 하고 있거나(작업 중/Stop 훅이 폰 입력을 기다리는 중)이면
// 아무 것도 하지 않고 그 프로세스에 맡긴다. PC가 완전히 유휴 상태일 때만
// "깨우기" — `claude -p`로 새 헤드리스 세션을 그 자리에서 시작한다. 이렇게
// 시작된 세션도 기존 PreToolUse/Stop/StopFailure 훅을 그대로 태우므로, 승인
// 요청/자동허용 판정/완료 알림은 지금까지와 똑같이 폰으로 온다 — 이 데몬은
// "깨우는 것"만 하고 나머지는 기존 훅이 알아서 한다.
//
// 실행: `node hook/daemon.js` (Ctrl+C로 종료). 컴퓨터를 껐다 켜면 다시
// 실행해야 한다(자동 시작은 이번 범위 밖).
"use strict";

const https = require("https");
const fs = require("fs");
const os = require("os");
const { spawn } = require("child_process");
const {
  loadConfig,
  topicFor,
  postJson,
  fetchLatestSettings,
  loadState,
  NTFY_HOST,
} = require("./notify-common");

const IDLE_TIMEOUT_MS = 90 * 1000; // ntfy keepalive(45초)보다 넉넉히 긴 시간
const MAX_BACKFILL_SEC = 12 * 60 * 60;
const MAX_SEEN_IDS = 200;
const HEARTBEAT_INTERVAL_MS = 60 * 1000;
const SPAWN_SETTLE_MS = 8000; // 이 시간 동안 안 죽으면 정상 시작된 것으로 봄

function log(msg) {
  console.log(`[${new Date().toISOString()}] ${msg}`);
}

// ---- 폰의 "다음 지시"를 상시 구독 ----

function streamPrompts(promptTopic, onPrompt) {
  let backoffMs = 2000;
  let lastEventEpochSec = 0;
  const seenIds = [];

  function markSeen(id) {
    if (!id) return true;
    if (seenIds.includes(id)) return false;
    seenIds.push(id);
    while (seenIds.length > MAX_SEEN_IDS) seenIds.shift();
    return true;
  }

  let reconnectScheduled = false;
  function scheduleReconnect() {
    if (reconnectScheduled) return;
    reconnectScheduled = true;
    setTimeout(() => {
      reconnectScheduled = false;
      backoffMs = Math.min(backoffMs * 2, 30000);
      connect();
    }, backoffMs);
  }

  function handleLine(line) {
    let envelope;
    try {
      envelope = JSON.parse(line);
    } catch {
      return;
    }
    const eventTime = envelope.time || 0;
    if (eventTime > lastEventEpochSec) lastEventEpochSec = eventTime;
    if (envelope.event !== "message" || !envelope.message) return;
    if (!markSeen(envelope.id)) return;
    let msg;
    try {
      msg = JSON.parse(envelope.message);
    } catch {
      return;
    }
    onPrompt(msg);
  }

  function connect() {
    const nowSec = Math.floor(Date.now() / 1000);
    const sinceSec =
      lastEventEpochSec <= 0
        ? nowSec - 5
        : nowSec - lastEventEpochSec > MAX_BACKFILL_SEC
        ? nowSec - MAX_BACKFILL_SEC
        : lastEventEpochSec;

    log(`ntfy 프롬프트 토픽 연결 중… (since=${sinceSec})`);
    const req = https.get(
      {
        hostname: NTFY_HOST,
        path: `/${encodeURIComponent(promptTopic)}/json?poll=false&since=${sinceSec}`,
        headers: { Accept: "application/x-ndjson" },
      },
      (res) => {
        if (res.statusCode !== 200) {
          log(`연결 실패 (HTTP ${res.statusCode}), 재시도 예정`);
          res.resume();
          scheduleReconnect();
          return;
        }
        backoffMs = 2000;
        log("연결됨 — 폰의 지시를 기다리는 중");
        let buffer = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => {
          buffer += chunk;
          let idx;
          while ((idx = buffer.indexOf("\n")) >= 0) {
            const line = buffer.slice(0, idx).trim();
            buffer = buffer.slice(idx + 1);
            if (line) handleLine(line);
          }
        });
        res.on("end", () => {
          log("연결이 끊김, 재연결 예정");
          scheduleReconnect();
        });
        res.on("error", () => scheduleReconnect());
      }
    );
    // 읽기 타임아웃을 반드시 유한값으로 둔다 — 무한이면 죽은 연결을 못 알아채고
    // 영영 기다리게 된다(v1.9에서 안드로이드 쪽에 있었던 것과 같은 버그 클래스).
    req.setTimeout(IDLE_TIMEOUT_MS, () => {
      req.destroy(new Error("idle timeout"));
    });
    req.on("error", () => scheduleReconnect());
  }

  connect();
}

// ---- 유휴 상태 판단 + 헤드리스 세션 깨우기 ----

async function handlePrompt(config, msg) {
  const prompt = typeof msg.prompt === "string" ? msg.prompt.trim() : "";
  if (!prompt) return; // cancel 등 — 데몬은 취소할 대상이 없으므로 무시

  const state = loadState();
  const daemon = state.daemon || {};
  const now = Date.now();

  if (daemon.waitingUntil && now < daemon.waitingUntil) {
    log(`이미 살아있는 Stop 훅이 대기 중이라 넘어감 (session ${daemon.waitingSessionId || "-"})`);
    return;
  }

  if (daemon.busyUntil && now < daemon.busyUntil) {
    log("PC가 작업 중이라 지시를 못 받음 — attention 알림 전송");
    await postJson(topicFor(config, "ask"), {
      id: `daemon-busy-${now}`,
      type: "attention",
      title: "지금 작업 중이라 못 받았어요",
      body: `PC가 지금 다른 작업을 하고 있어서 이 지시를 받지 못했어요. 작업이 끝난 뒤 다시 보내주세요.\n\n${prompt}`,
    });
    return;
  }

  const settingsTopic = topicFor(config, "settings");
  const remoteSettings = await fetchLatestSettings(settingsTopic, 4000);
  const remoteInput =
    typeof remoteSettings.remoteInputMode === "boolean"
      ? remoteSettings.remoteInputMode
      : config.remoteInputMode === true;

  if (!remoteInput) {
    log("폰 입력 모드가 꺼져 있어 무시 — attention 알림 전송");
    await postJson(topicFor(config, "ask"), {
      id: `daemon-off-${now}`,
      type: "attention",
      title: "폰 입력 모드가 꺼져 있어요",
      body: `앱에서 폰 입력 모드를 켜야 PC를 원격으로 깨울 수 있어요. 이번 지시는 전달되지 않았어요.\n\n${prompt}`,
    });
    return;
  }

  const dir = daemon.lastCwd && fs.existsSync(daemon.lastCwd) ? daemon.lastCwd : os.homedir();
  log(`PC가 쉬고 있어서 새로 깨웁니다 (폴더: ${dir})`);
  spawnHeadless(dir, prompt, true);
}

function spawnHeadless(dir, prompt, withContinue) {
  const args = withContinue ? ["-p", prompt, "--continue"] : ["-p", prompt];
  log(`실행: claude ${args.map((a) => (a === prompt ? '"<prompt>"' : a)).join(" ")} (cwd=${dir})`);

  const child = spawn("claude", args, {
    cwd: dir,
    stdio: ["ignore", "pipe", "pipe"],
    detached: true,
  });

  let stdoutBuf = "";
  let stderrBuf = "";
  let settled = false;

  const settleTimer = setTimeout(() => {
    if (settled) return;
    settled = true;
    // 여기까지 안 죽고 살아있으면 정상 시작된 것으로 보고, 데몬이 더 이상
    // stdout/stderr를 붙잡고 있지 않게 흘려보낸 뒤(백프레셔로 자식이 막히지
    // 않도록) 데몬 이벤트 루프에서 분리한다 — 세션은 데몬 없이도 계속 진행된다.
    child.stdout.resume();
    child.stderr.resume();
    child.unref();
    log("헤드리스 세션이 정상적으로 시작됨 — 이후는 기존 훅이 처리");
  }, SPAWN_SETTLE_MS);

  child.stdout.on("data", (d) => {
    stdoutBuf += d.toString();
  });
  child.stderr.on("data", (d) => {
    stderrBuf += d.toString();
  });

  child.on("error", (err) => {
    if (settled) return;
    settled = true;
    clearTimeout(settleTimer);
    log(`claude 실행 자체에 실패함: ${err.message} (claude CLI가 PATH에 있는지 확인 필요)`);
  });

  child.on("exit", (code) => {
    if (settled) return;
    settled = true;
    clearTimeout(settleTimer);
    // --continue가 실패하는 정확한 사유(이 폴더에 이어갈 대화가 없음 등)를
    // 문자열로 구분하려 했으나, 중첩된 claude 프로세스를 이 개발 환경에서
    // 직접 실행해서 실제 에러 문구를 확인할 수 없었다(스스로를 호출하는
    // 형태라 행업됨). 그래서 문구 매칭 대신, --continue를 붙였다가 실패하면
    // 그 이유를 따지지 않고 한 번만 --continue 없이 재시도한다 — 재시도도
    // 실패하면 그건 --continue 자체 문제가 아니라는 뜻이므로 거기서 멈춘다
    // (무한 재시도 없음).
    if (withContinue && code !== 0) {
      log(`--continue 시도가 code ${code}로 끝나 --continue 없이 한 번 더 시도합니다`);
      spawnHeadless(dir, prompt, false);
      return;
    }
    if (code !== 0) {
      log(`claude 세션이 code ${code}로 일찍 종료됨: ${(stderrBuf || stdoutBuf).slice(0, 500)}`);
    }
  });
}

// ---- 데몬이 켜져 있다는 걸 폰에 알리는 하트비트 ----

function startHeartbeat(config) {
  const topic = topicFor(config, "settings");
  const beat = () => {
    postJson(topic, { daemonAlive: true, updatedAt: Date.now() });
  };
  beat();
  setInterval(beat, HEARTBEAT_INTERVAL_MS).unref();
}

function main() {
  const config = loadConfig();
  if (!config) {
    console.error(
      "설정이 없습니다. 먼저 `node hook/setup.js`를 실행해서 페어링 코드를 만들어주세요."
    );
    process.exit(1);
  }

  log(`Claude Approver 데몬 시작 (페어링 코드: ${config.pairingCode || "-"})`);
  startHeartbeat(config);
  streamPrompts(topicFor(config, "prompt"), (msg) => {
    handlePrompt(config, msg).catch((err) => log(`프롬프트 처리 중 오류: ${err.message}`));
  });
}

process.on("SIGINT", () => {
  log("종료합니다");
  process.exit(0);
});

main();

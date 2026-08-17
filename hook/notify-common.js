// Stop/StopFailure/Notification 훅에서 공통으로 쓰는 유틸리티.
// (permission-relay.js는 승인 응답을 기다려야 해서 별도로 자기 코드를 갖고 있다.)
"use strict";

const https = require("https");
const fs = require("fs");
const os = require("os");
const path = require("path");

const CONFIG_PATH = path.join(os.homedir(), ".claude", "claude-approver.json");
const STATE_PATH = path.join(os.homedir(), ".claude", "claude-approver-state.json");
const NTFY_HOST = "ntfy.sh";

function loadConfig() {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, "utf8");
    const cfg = JSON.parse(raw);
    if (!cfg.askTopic) return null;
    return cfg;
  } catch {
    return null;
  }
}

function topicFor(config, suffix) {
  if (config.pairingCode) return `${config.pairingCode}-${suffix}`;
  return config.askTopic.replace(/-ask$/, `-${suffix}`);
}

function readStdin() {
  return new Promise((resolve) => {
    let data = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => (data += chunk));
    process.stdin.on("end", () => resolve(data));
    setTimeout(() => resolve(data), 2000).unref();
  });
}

function postJson(topic, payload) {
  return new Promise((resolve) => {
    const body = JSON.stringify(payload);
    const req = https.request(
      {
        hostname: NTFY_HOST,
        path: `/${encodeURIComponent(topic)}`,
        method: "POST",
        headers: {
          "Content-Type": "text/plain; charset=utf-8",
          "Content-Length": Buffer.byteLength(body),
        },
        timeout: 8000,
      },
      (res) => {
        res.on("data", () => {});
        res.on("end", () => resolve(res.statusCode));
      }
    );
    req.on("error", () => resolve(null));
    req.on("timeout", () => {
      req.destroy();
      resolve(null);
    });
    req.write(body);
    req.end();
  });
}

// 폰 앱의 스위치들("자동 허용 모드", "폰 입력 모드")은 settings 토픽에 값을
// publish해두는 방식이다. PC 훅은 매번 새로 실행되고 끝나는 구조라 실시간
// 구독을 못 하므로, 필요한 시점에 캐시된 메시지를 전부 훑어서 키별로 가장
// 최근 값을 모아온다. 한 번도 안 건드렸거나 ntfy 캐시가 만료됐으면 해당 키는 빠진다.
function fetchLatestSettings(settingsTopic, timeoutMs) {
  return new Promise((resolve) => {
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
        path: `/${encodeURIComponent(settingsTopic)}/json?poll=1&since=all`,
        headers: { Accept: "application/x-ndjson" },
      },
      (res) => {
        let buffer = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => (buffer += chunk));
        res.on("end", () => {
          const latest = {};
          for (const line of buffer.split("\n")) {
            const trimmed = line.trim();
            if (!trimmed) continue;
            try {
              const envelope = JSON.parse(trimmed);
              if (envelope.event !== "message" || !envelope.message) continue;
              const msg = JSON.parse(envelope.message);
              for (const key of ["autoApproveMode", "remoteInputMode"]) {
                if (typeof msg[key] === "boolean") latest[key] = msg[key];
              }
            } catch {
              // 무시하고 다음 줄 계속 처리
            }
          }
          finish(latest);
        });
        res.on("error", () => finish({}));
      }
    );
    req.on("error", () => finish({}));
    const timer = setTimeout(() => finish({}), timeoutMs);
  });
}

// ntfy 캐시가 나중에 만료돼도 마지막으로 알려진 설정값은 로컬에 남겨둔다.
function persistSettings(values) {
  try {
    const cfg = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));
    let changed = false;
    for (const [key, value] of Object.entries(values)) {
      if (cfg[key] !== value) {
        cfg[key] = value;
        changed = true;
      }
    }
    if (changed) fs.writeFileSync(CONFIG_PATH, JSON.stringify(cfg, null, 2));
  } catch {
    // 저장 실패해도 이번 판단 자체엔 영향 없음
  }
}

// 세션별로 "폰 입력으로 몇 번 연속 이어붙였는지"를 세는 용도. Claude Code는
// Stop 훅이 8번 연속으로 턴을 이어붙이면 그 다음부터는 무시하고 턴을 끝내기
// 때문에(플랫폼 제한), 남은 횟수를 폰에 같이 보여주려면 직접 세야 한다.
function loadState() {
  try {
    return JSON.parse(fs.readFileSync(STATE_PATH, "utf8"));
  } catch {
    return { sessions: {} };
  }
}

function saveState(state) {
  try {
    // 하루 넘은 세션 기록은 정리해서 파일이 무한정 커지지 않게 한다.
    const cutoff = Date.now() - 24 * 60 * 60 * 1000;
    for (const [sid, entry] of Object.entries(state.sessions || {})) {
      if (!entry || (entry.updatedAt || 0) < cutoff) delete state.sessions[sid];
    }
    fs.writeFileSync(STATE_PATH, JSON.stringify(state));
  } catch {
    // 상태 저장 실패는 치명적이지 않음 — 카운트만 부정확해진다
  }
}

module.exports = {
  loadConfig,
  topicFor,
  readStdin,
  postJson,
  fetchLatestSettings,
  persistSettings,
  loadState,
  saveState,
  CONFIG_PATH,
  NTFY_HOST,
};

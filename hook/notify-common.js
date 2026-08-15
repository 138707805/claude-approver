// Stop/Notification 훅에서 공통으로 쓰는 최소한의 유틸리티.
// (permission-relay.js는 응답을 기다려야 해서 별도로 자기 코드를 갖고 있다.)
"use strict";

const https = require("https");
const fs = require("fs");
const os = require("os");
const path = require("path");

const CONFIG_PATH = path.join(os.homedir(), ".claude", "claude-approver.json");
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

module.exports = { loadConfig, readStdin, postJson };

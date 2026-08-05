#!/usr/bin/env node
// Claude Approver 최초 설정 스크립트.
// 1) 무작위 페어링 코드 생성 → ~/.claude/claude-approver.json 저장
// 2) ~/.claude/settings.json 에 PreToolUse 훅 등록 (기존 설정은 백업 후 보존)
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");

const CLAUDE_DIR = path.join(os.homedir(), ".claude");
const CONFIG_PATH = path.join(CLAUDE_DIR, "claude-approver.json");
const SETTINGS_PATH = path.join(CLAUDE_DIR, "settings.json");
const HOOK_SCRIPT_PATH = path.join(__dirname, "permission-relay.js");

function randomPairingCode() {
  return "ca-" + crypto.randomBytes(9).toString("base64url"); // 12자 랜덤
}

function loadOrCreateConfig() {
  if (fs.existsSync(CONFIG_PATH)) {
    const existing = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));
    if (existing.askTopic && existing.replyTopic) return { config: existing, created: false };
  }
  const base = randomPairingCode();
  const config = {
    pairingCode: base,
    askTopic: `${base}-ask`,
    replyTopic: `${base}-reply`,
    timeoutSeconds: 170,
  };
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2));
  return { config, created: true };
}

function patchSettings() {
  let settings = {};
  if (fs.existsSync(SETTINGS_PATH)) {
    const raw = fs.readFileSync(SETTINGS_PATH, "utf8");
    settings = raw.trim() ? JSON.parse(raw) : {};
    const backupPath = SETTINGS_PATH + ".claude-approver-backup";
    if (!fs.existsSync(backupPath)) {
      fs.copyFileSync(SETTINGS_PATH, backupPath);
      console.log(`기존 settings.json을 ${backupPath} 로 백업했습니다.`);
    }
  }

  settings.hooks = settings.hooks || {};
  settings.hooks.PreToolUse = settings.hooks.PreToolUse || [];

  const command = `node "${HOOK_SCRIPT_PATH}"`;
  const alreadyRegistered = settings.hooks.PreToolUse.some((entry) =>
    (entry.hooks || []).some((h) => h.command === command)
  );

  if (!alreadyRegistered) {
    settings.hooks.PreToolUse.push({
      matcher: "Bash|Edit|Write",
      hooks: [{ type: "command", command, timeout: 180 }],
    });
    fs.writeFileSync(SETTINGS_PATH, JSON.stringify(settings, null, 2) + "\n");
    console.log("settings.json에 PreToolUse 훅을 등록했습니다. (대상 도구: Bash, Edit, Write)");
  } else {
    console.log("훅이 이미 등록되어 있습니다.");
  }
}

function main() {
  fs.mkdirSync(CLAUDE_DIR, { recursive: true });
  const { config, created } = loadOrCreateConfig();
  patchSettings();

  console.log("");
  console.log("================ Claude Approver 설정 완료 ================");
  console.log(created ? "새 페어링 코드를 생성했습니다:" : "기존 페어링 코드를 사용합니다:");
  console.log("");
  console.log(`  ${config.pairingCode}`);
  console.log("");
  console.log("이 코드를 휴대폰의 'Claude Approver' 앱에 입력하면 연결됩니다.");
  console.log("이 코드는 비밀번호와 같으니 타인에게 공유하지 마세요.");
  console.log("=============================================================");
}

main();

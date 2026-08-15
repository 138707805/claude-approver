#!/usr/bin/env node
// Claude Approver 최초 설정 스크립트.
// 1) 무작위 페어링 코드 생성 → ~/.claude/claude-approver.json 저장
// 2) ~/.claude/settings.json 에 PreToolUse/Stop/Notification 훅 등록
//    (기존 Claude Approver 관련 훅은 지우고 다시 씀 — 여러 번 실행해도 안전함)
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");

const CLAUDE_DIR = path.join(os.homedir(), ".claude");
const CONFIG_PATH = path.join(CLAUDE_DIR, "claude-approver.json");
const SETTINGS_PATH = path.join(CLAUDE_DIR, "settings.json");

const PERMISSION_RELAY_PATH = path.join(__dirname, "permission-relay.js");
const TASK_COMPLETE_PATH = path.join(__dirname, "task-complete-notify.js");
const ATTENTION_NOTIFY_PATH = path.join(__dirname, "attention-notify.js");

// 휴대폰으로 승인/거부가 가능한(=의미 있게 판단할 수 있는) 도구들.
// ExitPlanMode는 permission-relay.js 안에서 "PC 전용"으로 따로 처리한다.
const PRE_TOOL_USE_MATCHER = "Bash|Edit|Write|NotebookEdit|WebFetch|ExitPlanMode";

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
    // 폰 응답이 아예 없을 때(부재중 등) 평범한 요청은 자동 허용할지 여부.
    // false로 바꾸면 기존처럼 항상 터미널에서 직접 승인해야 한다.
    autoApproveWhenUnreachable: true,
  };
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2));
  return { config, created: true };
}

function isOurCommand(command) {
  return typeof command === "string" && command.includes("claude-approver");
}

function removeOurHooks(settings) {
  const hooks = settings.hooks || {};
  for (const eventName of Object.keys(hooks)) {
    hooks[eventName] = (hooks[eventName] || []).filter(
      (entry) => !(entry.hooks || []).some((h) => isOurCommand(h.command))
    );
    if (hooks[eventName].length === 0) delete hooks[eventName];
  }
  settings.hooks = hooks;
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

  removeOurHooks(settings); // 예전 버전에서 등록한 훅이 있으면 지우고 최신 구성으로 다시 등록
  settings.hooks = settings.hooks || {};

  settings.hooks.PreToolUse = settings.hooks.PreToolUse || [];
  settings.hooks.PreToolUse.push({
    matcher: PRE_TOOL_USE_MATCHER,
    hooks: [{ type: "command", command: `node "${PERMISSION_RELAY_PATH}"`, timeout: 180 }],
  });

  settings.hooks.Stop = settings.hooks.Stop || [];
  settings.hooks.Stop.push({
    hooks: [{ type: "command", command: `node "${TASK_COMPLETE_PATH}"`, timeout: 15 }],
  });

  settings.hooks.Notification = settings.hooks.Notification || [];
  settings.hooks.Notification.push({
    hooks: [{ type: "command", command: `node "${ATTENTION_NOTIFY_PATH}"`, timeout: 15 }],
  });

  fs.writeFileSync(SETTINGS_PATH, JSON.stringify(settings, null, 2) + "\n");
  console.log(`settings.json에 훅을 등록했습니다.`);
  console.log(`  - PreToolUse (${PRE_TOOL_USE_MATCHER}): 승인/거부 요청`);
  console.log(`  - Stop: 작업 완료 알림`);
  console.log(`  - Notification: 컴퓨터 확인이 필요할 때 알림`);
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

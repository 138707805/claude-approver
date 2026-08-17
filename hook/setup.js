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
const STOP_FAILURE_PATH = path.join(__dirname, "stop-failure-notify.js");

// Stop 훅은 "폰 입력 모드"가 켜져 있으면 폰에서 다음 지시가 올 때까지 기다린다.
// 그래서 훅 타임아웃이 그 대기 시간보다 넉넉히 길어야 한다(중간에 잘리면 폰에서
// 보낸 지시가 버려진다). 대기 시간 자체는 task-complete-notify.js가 이 값보다
// 짧게 잘라서 쓴다.
const STOP_HOOK_TIMEOUT = 600;

// 휴대폰으로 승인/거부가 가능한(=의미 있게 판단할 수 있는) 도구들.
// ExitPlanMode는 permission-relay.js 안에서 "PC 전용"으로 따로 처리한다.
const PRE_TOOL_USE_MATCHER = "Bash|Edit|Write|NotebookEdit|WebFetch|ExitPlanMode";

function randomPairingCode() {
  return "ca-" + crypto.randomBytes(9).toString("base64url"); // 12자 랜덤
}

// 폰에서 다음 지시를 입력받는 기능("폰 입력 모드")의 기본값.
// 기본은 꺼둔다 — 켜져 있으면 매 턴 끝에서 폰 입력을 기다리느라 터미널이
// 그만큼 멈춰 있게 되므로, 사용자가 앱에서 직접 켤 때만 동작하는 게 맞다.
const REMOTE_INPUT_DEFAULTS = {
  remoteInputMode: false,
  remoteInputWaitSeconds: 240,
};

function loadOrCreateConfig() {
  if (fs.existsSync(CONFIG_PATH)) {
    const existing = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));
    if (existing.askTopic && existing.replyTopic) {
      // 예전 버전에서 만든 설정 파일에는 새로 생긴 항목이 없으므로 채워 넣는다.
      let patched = false;
      for (const [key, value] of Object.entries(REMOTE_INPUT_DEFAULTS)) {
        if (existing[key] === undefined) {
          existing[key] = value;
          patched = true;
        }
      }
      if (patched) fs.writeFileSync(CONFIG_PATH, JSON.stringify(existing, null, 2));
      return { config: existing, created: false };
    }
  }
  const base = randomPairingCode();
  const config = {
    pairingCode: base,
    askTopic: `${base}-ask`,
    replyTopic: `${base}-reply`,
    timeoutSeconds: 170,
    // 평범한 요청은 사람 확인 없이 바로 자동 허용할지 여부(권한/보안 관련
    // 요청은 이 값과 무관하게 항상 터미널 확인이 필요함). 앱의 "자동 허용
    // 모드" 스위치로 언제든 켜고 끌 수 있다. false로 바꾸면 항상 폰에 직접
    // 물어보고 응답을 기다리는 기존 방식으로 동작한다.
    autoApproveMode: true,
    ...REMOTE_INPUT_DEFAULTS,
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
    hooks: [{ type: "command", command: `node "${TASK_COMPLETE_PATH}"`, timeout: STOP_HOOK_TIMEOUT }],
  });

  // 턴이 API 오류(사용량 한도 등)로 끝나면 Stop 훅은 실행되지 않는다.
  // 이 훅이 없으면 그때는 폰에 아무 소식도 안 간다.
  settings.hooks.StopFailure = settings.hooks.StopFailure || [];
  settings.hooks.StopFailure.push({
    hooks: [{ type: "command", command: `node "${STOP_FAILURE_PATH}"`, timeout: 15 }],
  });

  settings.hooks.Notification = settings.hooks.Notification || [];
  settings.hooks.Notification.push({
    hooks: [{ type: "command", command: `node "${ATTENTION_NOTIFY_PATH}"`, timeout: 15 }],
  });

  fs.writeFileSync(SETTINGS_PATH, JSON.stringify(settings, null, 2) + "\n");
  console.log(`settings.json에 훅을 등록했습니다.`);
  console.log(`  - PreToolUse (${PRE_TOOL_USE_MATCHER}): 승인/거부 요청`);
  console.log(`  - Stop: 작업 완료 알림 + 폰에서 다음 지시 받기`);
  console.log(`  - StopFailure: 사용량 한도 등 오류로 멈췄을 때 알림`);
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

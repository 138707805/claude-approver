#!/usr/bin/env node
// Claude Code Notification 훅: 권한 요청은 permission-relay.js(PreToolUse)가 이미
// 처리하니 여기서는 건너뛰고, 그 외의 경우(예: 60초 넘게 아무 입력 없이 대기 중)만
// "컴퓨터를 확인해주세요" 알림으로 휴대폰에 전달한다.
"use strict";

const { loadConfig, readStdin, postJson } = require("./notify-common");

function looksLikePermissionMessage(message) {
  return /permission/i.test(message || "");
}

async function main() {
  const config = loadConfig();
  if (!config) return;

  const raw = await readStdin();
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    return;
  }

  const message = payload.message || "";
  if (looksLikePermissionMessage(message)) return; // PreToolUse 훅이 이미 알림

  await postJson(config.askTopic, {
    id: `notify-${Date.now()}`,
    type: "attention",
    title: "컴퓨터에서 확인해주세요",
    body: message || "Claude Code에서 확인이 필요한 상황이 발생했어요.",
    cwd: payload.cwd,
  });
}

main()
  .catch(() => {})
  .finally(() => process.exit(0));

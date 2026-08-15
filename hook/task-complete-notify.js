#!/usr/bin/env node
// Claude Code Stop 훅: 한 턴이 끝날 때마다(작업이 끝나고 다음 지시를 기다릴 때)
// 휴대폰에 "작업 완료" 알림을 보낸다. 결정을 내리는 훅이 아니라서 항상 조용히 종료한다.
"use strict";

const fs = require("fs");
const { loadConfig, readStdin, postJson } = require("./notify-common");

function extractLastAssistantText(transcriptPath) {
  try {
    const lines = fs.readFileSync(transcriptPath, "utf8").trim().split("\n");
    for (let i = lines.length - 1; i >= 0; i--) {
      let entry;
      try {
        entry = JSON.parse(lines[i]);
      } catch {
        continue;
      }
      const message = entry.message || entry;
      if (message && message.role === "assistant" && Array.isArray(message.content)) {
        const textBlock = message.content.find((b) => b.type === "text" && b.text);
        if (textBlock) return textBlock.text.trim();
      }
    }
  } catch {
    // 못 읽으면 그냥 일반 문구로 대체
  }
  return null;
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

  const preview = payload.transcript_path ? extractLastAssistantText(payload.transcript_path) : null;
  const body = preview ? preview.slice(0, 300) : "Claude가 응답을 마치고 다음 지시를 기다리고 있어요.";

  await postJson(config.askTopic, {
    id: `stop-${Date.now()}`,
    type: "status",
    title: "작업 완료",
    body,
    cwd: payload.cwd,
  });
}

main()
  .catch(() => {})
  .finally(() => process.exit(0));

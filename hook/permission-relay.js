#!/usr/bin/env node
// Claude Code PreToolUse hook: 위험한 도구 호출을 휴대폰(Claude Approver 앱)으로 전달해
// 승인/거부를 받아온다. 휴대폰 응답이 없거나 오류가 나면 항상 "결정 없음"으로
// 종료해서 평소처럼 터미널에서 직접 승인하는 화면이 뜨도록 안전하게 폴백한다.

const https = require("https");
const fs = require("fs");
const os = require("os");
const path = require("path");

const CONFIG_PATH = path.join(os.homedir(), ".claude", "claude-approver.json");
const NTFY_HOST = "ntfy.sh";
const DEFAULT_TIMEOUT_SEC = 170; // settings.json 쪽 hook timeout(180s)보다 약간 짧게

function readStdin() {
  return new Promise((resolve) => {
    let data = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => (data += chunk));
    process.stdin.on("end", () => resolve(data));
    // stdin이 없는 환경(터미널에서 직접 실행 테스트)을 대비한 안전장치
    setTimeout(() => resolve(data), 2000).unref();
  });
}

function loadConfig() {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, "utf8");
    const cfg = JSON.parse(raw);
    if (!cfg.askTopic || !cfg.replyTopic) return null;
    return cfg;
  } catch {
    return null; // 설정 안 된 상태 → 훅이 아무 것도 안 하고 평소처럼 동작
  }
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
        timeout: 10000,
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

// ntfy의 /json 스트리밍 엔드포인트를 열어서 requestId가 일치하는 응답이
// 올 때까지 기다린다. timeoutMs가 지나면 null을 반환한다.
function waitForReply(replyTopic, requestId, timeoutMs) {
  return new Promise((resolve) => {
    const sinceSec = Math.floor(Date.now() / 1000) - 5;
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
        path: `/${encodeURIComponent(replyTopic)}/json?poll=false&since=${sinceSec}`,
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
              const reply = JSON.parse(envelope.message);
              if (reply.id === requestId && (reply.decision === "allow" || reply.decision === "deny")) {
                finish(reply.decision);
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

function summarize(toolName, toolInput) {
  const input = toolInput || {};
  switch (toolName) {
    case "Bash":
      return {
        title: "터미널 명령 승인 요청",
        body: input.description ? `${input.description}\n\n${input.command}` : String(input.command || ""),
      };
    case "Write":
      return { title: "파일 쓰기 승인 요청", body: `파일: ${input.file_path || "(알 수 없음)"}` };
    case "Edit":
      return { title: "파일 수정 승인 요청", body: `파일: ${input.file_path || "(알 수 없음)"}` };
    default:
      return {
        title: `${toolName} 실행 승인 요청`,
        body: JSON.stringify(input).slice(0, 500),
      };
  }
}

async function main() {
  const config = loadConfig();
  if (!config) return; // 미설정 → 아무 출력 없이 종료 (평소 동작 유지)

  const raw = await readStdin();
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    return;
  }

  const toolName = payload.tool_name;
  const toolUseId = payload.tool_use_id || `${Date.now()}`;
  const { title, body } = summarize(toolName, payload.tool_input);

  const sent = await postJson(config.askTopic, {
    id: toolUseId,
    tool: toolName,
    title,
    body,
    cwd: payload.cwd,
  });
  if (sent === null) return; // 네트워크 오류 → 평소 터미널 승인으로 폴백

  const timeoutMs = (config.timeoutSeconds || DEFAULT_TIMEOUT_SEC) * 1000;
  const decision = await waitForReply(config.replyTopic, toolUseId, timeoutMs);

  if (decision === "allow") {
    console.log(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "allow",
          permissionDecisionReason: "휴대폰(Claude Approver 앱)에서 승인함",
        },
      })
    );
  } else if (decision === "deny") {
    console.log(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "deny",
          permissionDecisionReason: "휴대폰(Claude Approver 앱)에서 거부함",
        },
      })
    );
  }
  // decision === null (타임아웃/오류): 아무것도 출력하지 않고 정상 종료
  // → Claude Code가 평소처럼 터미널에서 직접 승인을 물어봄
}

main()
  .catch(() => {})
  .finally(() => process.exit(0));

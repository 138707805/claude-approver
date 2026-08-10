#!/usr/bin/env node
// Claude Code PreToolUse hook: 위험한 도구 호출을 휴대폰(Claude Approver 앱)으로 전달해
// 승인/거부를 받아온다. 휴대폰 응답이 없거나 오류가 나면 항상 "결정 없음"으로
// 종료해서 평소처럼 터미널에서 직접 승인하는 화면이 뜨도록 안전하게 폴백한다.

const https = require("https");
const fs = require("fs");
const os = require("os");
const path = require("path");

const CONFIG_PATH = path.join(os.homedir(), ".claude", "claude-approver.json");
const ALLOWLIST_PATH = path.join(os.homedir(), ".claude", "claude-approver-allowlist.json");
const NTFY_HOST = "ntfy.sh";
const DEFAULT_TIMEOUT_SEC = 170; // settings.json 쪽 hook timeout(180s)보다 약간 짧게

// 한 번 "허용"한 요청은 다음에 똑같은 요청이 오면 다시 물어보지 않고 바로 허용한다.
// Bash는 명령어 전체 텍스트, Write/Edit는 대상 파일 경로가 같으면 "같은 요청"으로 본다.
function signatureFor(toolName, toolInput) {
  const input = toolInput || {};
  switch (toolName) {
    case "Bash":
      return `Bash::${input.command || ""}`;
    case "Write":
      return `Write::${input.file_path || ""}`;
    case "Edit":
      return `Edit::${input.file_path || ""}`;
    default:
      return `${toolName}::${JSON.stringify(input)}`;
  }
}

function loadAllowlist() {
  try {
    const raw = fs.readFileSync(ALLOWLIST_PATH, "utf8");
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

function rememberAllowed(signature) {
  try {
    const list = loadAllowlist();
    if (!list.includes(signature)) {
      list.push(signature);
      fs.writeFileSync(ALLOWLIST_PATH, JSON.stringify(list, null, 2));
    }
  } catch {
    // 저장 실패해도 이번 승인 자체는 이미 끝났으니 무시
  }
}

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
  const signature = signatureFor(toolName, payload.tool_input);

  if (loadAllowlist().includes(signature)) {
    console.log(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "allow",
          permissionDecisionReason: "이전에 휴대폰에서 '항상 허용'으로 저장된 요청이라 자동 승인함",
        },
      })
    );
    return;
  }

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
    rememberAllowed(signature);
    console.log(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "allow",
          permissionDecisionReason: "휴대폰(Claude Approver 앱)에서 승인함 (다음부터 동일 요청은 자동 승인)",
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

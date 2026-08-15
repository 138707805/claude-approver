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

function settingsTopicFor(config) {
  if (config.pairingCode) return `${config.pairingCode}-settings`;
  return config.askTopic.replace(/-ask$/, "-settings");
}

// 폰 앱의 "부재중 자동 허용" 스위치를 누르면 앱이 settingsTopic에 최신 값을
// publish해둔다. 이 훅은 실시간으로 폰을 구독하고 있지 않으므로(호출마다
// 새로 실행되고 끝나는 구조), 필요한 시점에 캐시된 메시지 중 가장 최근 것을
// 읽어와서 최신 설정값을 가져온다. 스위치를 한 번도 안 건드렸거나 ntfy 캐시가
// 만료됐으면 null을 반환한다.
function fetchLatestAutoApproveSetting(settingsTopic, timeoutMs) {
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
          let latest = null;
          for (const line of buffer.split("\n")) {
            const trimmed = line.trim();
            if (!trimmed) continue;
            try {
              const envelope = JSON.parse(trimmed);
              if (envelope.event !== "message" || !envelope.message) continue;
              const msg = JSON.parse(envelope.message);
              if (typeof msg.autoApproveWhenUnreachable === "boolean") {
                latest = msg.autoApproveWhenUnreachable;
              }
            } catch {
              // 무시하고 다음 줄 계속 처리
            }
          }
          finish(latest);
        });
        res.on("error", () => finish(null));
      }
    );
    req.on("error", () => finish(null));
    const timer = setTimeout(() => finish(null), timeoutMs);
  });
}

// ntfy 캐시가 나중에 만료돼도 마지막으로 알려진 설정값은 로컬에 남겨둔다.
function persistAutoApproveSetting(value) {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, "utf8");
    const cfg = JSON.parse(raw);
    if (cfg.autoApproveWhenUnreachable !== value) {
      cfg.autoApproveWhenUnreachable = value;
      fs.writeFileSync(CONFIG_PATH, JSON.stringify(cfg, null, 2));
    }
  } catch {
    // 저장 실패해도 이번 판단 자체엔 영향 없음
  }
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

// 휴대폰에서 그냥 허용/거부만 눌러서는 제대로 판단할 수 없는 도구.
// (예: ExitPlanMode는 계획 내용을 직접 읽어야 승인 여부를 판단할 수 있다.)
// 이런 건 앱에 "컴퓨터에서 확인해주세요"라고만 알려주고, 결정은 평소처럼 터미널에서 하게 둔다.
const PC_ONLY_TOOLS = new Set(["ExitPlanMode"]);

// 폰 응답이 아예 없을 때(부재중 등) 사람 판단 없이 자동 허용해도 되는지 걸러내는 목록.
// "평범한 작업"은 통과시키고, 권한/보안 관련 명령만 걸러서 사람 확인을 강제한다.
// 의심스러우면 걸러서 사람에게 넘기는 쪽으로 판단 — 놓쳐서 자동 허용되는 것보다는
// 안전한 걸 자동 허용 안 하는 실수가 훨씬 낫다.
const HIGH_RISK_BASH_PATTERNS = [
  /(^|[\s;&|])sudo\b/, // 관리자 권한 실행
  /(^|[\s;&|])su(\s+-|\s+root)?(\s|$)/, // 사용자 전환
  /\bchmod\b.*(\b777\b|\+s\b|-r\s+\/)/, // 전체 권한 부여 / setuid / 루트 하위 재귀 권한변경
  /\bchown\b.*\broot\b/,
  /\b(useradd|usermod|groupadd|passwd)\b/, // 계정/권한 관리
  /\bsudoers\b/,
  /authorized_keys|id_rsa|id_ed25519|id_ecdsa|\.ssh\/config\b/, // SSH 키/접근 관리
  /\b(iptables|ufw|firewalld|setenforce)\b|\bselinux\b/, // 방화벽/보안모듈
  /\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(sh|bash|zsh)\b/, // 원격 스크립트 다운받아 바로 실행
  /\bdocker\b.*--privileged\b|\/var\/run\/docker\.sock/,
  /\bcrontab\b|\bsystemctl\s+enable\b/, // 지속 실행(백도어성) 설정
  /\.bashrc\b|\.zshrc\b|\.bash_profile\b|\.profile\b/, // 셸 시작 스크립트 변경(지속성)
  /\baws\s+iam\b|\bgcloud\s+projects\s+add-iam-policy-binding\b|\baz\s+role\s+assignment\b/, // 클라우드 권한 부여
  /\.aws\/credentials\b|\.npmrc\b|\.netrc\b|\.pgpass\b|\/etc\/shadow\b/, // 자격증명 파일 접근
  /\bmkfs\b|\bdd\b.*of=\/dev\//, // 디스크 파괴
  /\.github\/workflows\//, // CI 설정(비밀값 접근 가능한 파이프라인) 변경
];

const HIGH_RISK_PATH_PATTERNS = [
  /\.ssh\//,
  /\.aws\//,
  /\.npmrc$|\.netrc$|\.pgpass$/,
  /(^|\/)etc\//,
  /\.github\/workflows\//,
  /sudoers/,
  /\.bashrc$|\.zshrc$|\.bash_profile$|\.profile$|\.bash_login$/,
  /\.kube\/config$/,
  /\.docker\/config\.json$/,
];

const HIGH_RISK_URL_PATTERNS = [
  /169\.254\.169\.254/, // 클라우드 메타데이터 서버(자격증명 탈취 SSRF)
  /metadata\.google\.internal/,
  /metadata\.azure\.com/,
];

function isHighRisk(toolName, toolInput) {
  const input = toolInput || {};
  if (toolName === "Bash") {
    const cmd = String(input.command || "").toLowerCase();
    return HIGH_RISK_BASH_PATTERNS.some((re) => re.test(cmd));
  }
  if (toolName === "Write" || toolName === "Edit") {
    const filePath = String(input.file_path || "").toLowerCase();
    return HIGH_RISK_PATH_PATTERNS.some((re) => re.test(filePath));
  }
  if (toolName === "NotebookEdit") {
    const filePath = String(input.notebook_path || "").toLowerCase();
    return HIGH_RISK_PATH_PATTERNS.some((re) => re.test(filePath));
  }
  if (toolName === "WebFetch") {
    const url = String(input.url || "").toLowerCase();
    return HIGH_RISK_URL_PATTERNS.some((re) => re.test(url));
  }
  return false;
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

  // 계획 승인처럼 폰에서 맹목적으로 허용/거부하면 안 되는 도구는
  // 결정은 터미널에 맡기고, 앱에는 "확인이 필요하다"는 것만 알려준다.
  if (PC_ONLY_TOOLS.has(toolName)) {
    await postJson(config.askTopic, {
      id: toolUseId,
      type: "attention",
      tool: toolName,
      title: `컴퓨터에서 확인해주세요: ${title}`,
      body: `${body}\n\n(이 요청은 내용을 직접 봐야 판단할 수 있어서 휴대폰으로는 승인/거부할 수 없어요)`,
      cwd: payload.cwd,
    });
    return; // 결정 없음 → 평소처럼 터미널에서 처리
  }

  if (loadAllowlist().includes(signature)) {
    // 자동 승인도 조용히 넘어가지 않고 "이미 처리됐다"는 걸 폰에 남긴다.
    await postJson(config.askTopic, {
      id: toolUseId,
      type: "info",
      tool: toolName,
      title,
      body,
      cwd: payload.cwd,
    });
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
    type: "approval",
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
  } else {
    // 타임아웃 = 폰 응답이 없었다는 뜻 (부재중이라 버튼을 못 눌렀을 가능성이 큼).
    // 평범한 요청이면 사람 판단 없이 자동 허용하고, 권한/보안 관련으로 보이면
    // 이전처럼 사람이 터미널에서 직접 봐야만 넘어가게 막는다.
    // "자동 허용" 스위치 자체는 앱에서 켜고 끌 수 있으므로, 로컬 설정 파일보다
    // 폰이 최근에 publish해둔 값을 우선으로 확인한다.
    const remoteAutoApprove = await fetchLatestAutoApproveSetting(settingsTopicFor(config), 5000);
    if (remoteAutoApprove !== null) persistAutoApproveSetting(remoteAutoApprove);
    const autoApprove = remoteAutoApprove !== null ? remoteAutoApprove : config.autoApproveWhenUnreachable !== false; // 기본값 true
    if (autoApprove && !isHighRisk(toolName, payload.tool_input)) {
      await postJson(config.askTopic, {
        id: toolUseId,
        type: "info",
        tool: toolName,
        title: `자동 허용됨 (응답 없음): ${title}`,
        body: `${body}\n\n(폰 응답이 없어서 평범한 요청으로 판단해 자동으로 허용했어요)`,
        cwd: payload.cwd,
      });
      console.log(
        JSON.stringify({
          hookSpecificOutput: {
            hookEventName: "PreToolUse",
            permissionDecision: "allow",
            permissionDecisionReason: "휴대폰 응답 없음 + 평범한 요청으로 판단되어 자동 허용함",
          },
        })
      );
      return;
    }

    await postJson(config.askTopic, {
      id: toolUseId,
      type: "attention",
      tool: toolName,
      title: `컴퓨터에서 확인해주세요: ${title}`,
      body: autoApprove
        ? `휴대폰 응답 시간이 지났고, 권한/보안과 관련된 민감한 요청으로 보여 자동 허용하지 않았어요. 터미널에서 직접 승인해야 해요.\n\n${body}`
        : `휴대폰 응답 시간이 지나서 터미널에서 직접 승인해야 해요.\n\n${body}`,
      cwd: payload.cwd,
    });
  }
}

main()
  .catch(() => {})
  .finally(() => process.exit(0));

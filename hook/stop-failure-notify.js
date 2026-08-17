#!/usr/bin/env node
// Claude Code StopFailure 훅: 턴이 정상 종료가 아니라 API 오류로 끝났을 때 실행된다.
//
// 이 경우 Stop 훅은 아예 실행되지 않기 때문에, 이 훅이 없으면 "작업 완료" 알림도
// 안 오고 아무 소식 없이 조용해진다 — 폰만 보고 있으면 Claude가 아직 일하는 중인지
// 한도에 걸려 멈춰 선 건지 구분할 수 없다. 그래서 오류 종료도 폰에 알린다.
//
// StopFailure에는 결정 권한이 없다(출력이 무시됨). 알림만 보내고 조용히 끝낸다.
"use strict";

const { loadConfig, topicFor, readStdin, postJson, loadState, saveState } = require("./notify-common");
const usage = require("./usage");

// 오류 종류를 사람이 읽을 수 있는 문장으로. 모르는 값이 오면 원문을 그대로 보여준다.
const ERROR_LABELS = {
  rate_limit: "사용량 한도에 걸렸어요",
  overloaded: "Claude 서버가 혼잡해요",
  authentication_failed: "로그인이 풀렸어요",
  oauth_org_not_allowed: "조직 권한 문제로 막혔어요",
  billing_error: "결제 문제로 막혔어요",
  invalid_request: "요청이 잘못돼서 멈췄어요",
  model_not_found: "모델을 찾을 수 없어요",
  server_error: "Claude 서버 오류로 멈췄어요",
  max_output_tokens: "답변이 최대 길이에 도달했어요",
  unknown: "알 수 없는 오류로 멈췄어요",
};

const ERROR_HINTS = {
  rate_limit: "한도가 풀릴 때까지 기다렸다가 PC에서 이어서 지시해야 해요.",
  authentication_failed: "PC에서 다시 로그인해야 이어갈 수 있어요.",
  billing_error: "PC에서 결제 상태를 확인해야 이어갈 수 있어요.",
};

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

  const errorType = payload.error || "unknown";
  const label = ERROR_LABELS[errorType] || `오류로 멈췄어요 (${errorType})`;
  const detail = (payload.error_details || payload.last_assistant_message || "").trim();
  const hint = ERROR_HINTS[errorType] || "PC에서 상태를 확인하고 이어서 지시해주세요.";

  let snapshot = null;
  try {
    snapshot = usage.collect();
  } catch {
    // 사용량 집계 실패가 알림을 막으면 안 된다
  }

  await postJson(topicFor(config, "ask"), {
    id: `fail-${Date.now()}`,
    type: "attention",
    title: `작업이 중단됐어요 — ${label}`,
    body: detail ? `${hint}\n\n${detail.slice(0, 300)}` : hint,
    cwd: payload.cwd,
    sessionId: payload.session_id || "",
    errorType,
    usage: snapshot,
  });

  // 오류로 끝났으면 폰 입력 연속 카운트도 의미가 없으니 정리한다.
  const sessionId = payload.session_id;
  if (sessionId) {
    const state = loadState();
    if (state.sessions && state.sessions[sessionId]) {
      delete state.sessions[sessionId];
      saveState(state);
    }
  }
}

main()
  .catch(() => {})
  .finally(() => process.exit(0));

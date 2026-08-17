// Claude Code 사용량 집계.
//
// 로컬 대화 기록(~/.claude/projects/**/*.jsonl)에 남는 토큰 사용량을 읽어서
// "오늘"과 "현재 5시간 블록" 기준으로 합산한다.
//
// 주의: 이건 어디까지나 **이 컴퓨터에 남은 기록** 기준이다. Anthropic 쪽 실제
// 구독 한도(남은 %, 초기화 시각)는 로컬 어디에도 저장되지 않으므로 알 수 없다.
// 그래서 "한도의 몇 %" 같은 값은 만들어내지 않고, 실제로 셀 수 있는 것만 낸다.
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");

const PROJECTS_DIR = path.join(os.homedir(), ".claude", "projects");

// 5시간 블록: Claude 구독의 사용량 창과 같은 길이. 첫 활동 시각을 정시로 내림해서
// 블록이 시작되고, 5시간이 지나거나 5시간 넘게 활동이 없으면 새 블록이 열린다.
const BLOCK_MS = 5 * 60 * 60 * 1000;

// 오래된 파일까지 다 읽으면 훅이 느려진다. 5시간 블록 + 오늘치만 필요하므로
// 최근에 수정된 파일만 본다.
const LOOKBACK_MS = 36 * 60 * 60 * 1000;

function emptyTotals() {
  return { input: 0, output: 0, cacheWrite: 0, cacheRead: 0, messages: 0 };
}

function addUsage(totals, usage) {
  totals.input += usage.input_tokens || 0;
  totals.output += usage.output_tokens || 0;
  totals.cacheWrite += usage.cache_creation_input_tokens || 0;
  totals.cacheRead += usage.cache_read_input_tokens || 0;
  totals.messages += 1;
}

function listRecentTranscripts(now) {
  const cutoff = now - LOOKBACK_MS;
  const files = [];
  let projectDirs;
  try {
    projectDirs = fs.readdirSync(PROJECTS_DIR, { withFileTypes: true });
  } catch {
    return files;
  }
  for (const dir of projectDirs) {
    if (!dir.isDirectory()) continue;
    const full = path.join(PROJECTS_DIR, dir.name);
    let entries;
    try {
      entries = fs.readdirSync(full);
    } catch {
      continue;
    }
    for (const name of entries) {
      if (!name.endsWith(".jsonl")) continue;
      const filePath = path.join(full, name);
      try {
        if (fs.statSync(filePath).mtimeMs >= cutoff) files.push(filePath);
      } catch {
        // 읽을 수 없는 파일은 건너뛴다
      }
    }
  }
  return files;
}

// 같은 응답이 재개(--resume)로 여러 파일에 중복 기록될 수 있어서 message id로 걸러낸다.
function collectEvents(now) {
  const events = [];
  const seen = new Set();
  for (const filePath of listRecentTranscripts(now)) {
    let raw;
    try {
      raw = fs.readFileSync(filePath, "utf8");
    } catch {
      continue;
    }
    for (const line of raw.split("\n")) {
      if (!line || line.indexOf('"usage"') === -1) continue;
      let entry;
      try {
        entry = JSON.parse(line);
      } catch {
        continue;
      }
      const message = entry.message;
      if (!message || message.role !== "assistant" || !message.usage) continue;
      const key = message.id || `${entry.uuid || ""}`;
      if (key && seen.has(key)) continue;
      if (key) seen.add(key);
      const ts = Date.parse(entry.timestamp || "");
      if (!Number.isFinite(ts)) continue;
      events.push({ ts, usage: message.usage, model: message.model || "unknown" });
    }
  }
  events.sort((a, b) => a.ts - b.ts);
  return events;
}

// 활동을 5시간 블록으로 묶어서 마지막(=현재) 블록을 돌려준다. ccusage와 같은 방식:
// 블록 시작은 첫 활동 시각을 정시로 내림, 5시간이 지나거나 5시간 이상 비면 새 블록.
function currentBlock(events) {
  if (events.length === 0) return null;
  let blockStart = null;
  let lastTs = null;
  let totals = emptyTotals();
  for (const ev of events) {
    const startsNewBlock =
      blockStart === null ||
      ev.ts - blockStart >= BLOCK_MS ||
      (lastTs !== null && ev.ts - lastTs >= BLOCK_MS);
    if (startsNewBlock) {
      blockStart = new Date(ev.ts).setMinutes(0, 0, 0);
      totals = emptyTotals();
    }
    addUsage(totals, ev.usage);
    lastTs = ev.ts;
  }
  return { startedAt: blockStart, endsAt: blockStart + BLOCK_MS, lastActivityAt: lastTs, totals };
}

function collect() {
  const now = Date.now();
  const events = collectEvents(now);

  const todayStart = new Date(now).setHours(0, 0, 0, 0);
  const today = emptyTotals();
  const byModel = {};
  for (const ev of events) {
    if (ev.ts < todayStart) continue;
    addUsage(today, ev.usage);
    // <synthetic>은 실제 모델 호출이 아니라 Claude Code가 만들어낸 안내 메시지라
    // 토큰이 0이다. 모델별 표에 섞이면 헷갈리기만 해서 뺀다.
    if (ev.model === "<synthetic>") continue;
    if (!byModel[ev.model]) byModel[ev.model] = emptyTotals();
    addUsage(byModel[ev.model], ev.usage);
  }

  const block = currentBlock(events);
  // 이미 끝난 블록이면 "현재 블록"이 아니라 비어 있는 상태로 본다.
  const activeBlock = block && now < block.endsAt ? block : null;

  return {
    updatedAt: now,
    today,
    byModel,
    block: activeBlock
      ? {
          startedAt: activeBlock.startedAt,
          endsAt: activeBlock.endsAt,
          lastActivityAt: activeBlock.lastActivityAt,
          totals: activeBlock.totals,
        }
      : null,
  };
}

module.exports = { collect, BLOCK_MS };

if (require.main === module) {
  console.log(JSON.stringify(collect(), null, 2));
}

// Port of ios-app Logic/Developer.swift: the hidden Developer view's rules (7-tap unlock, playback speed, the masked
// in-memory log, the live score overlay, diagnostics). Nothing here is written to storage except the "unlocked" flag.
import { mask } from "./mask.js";

export const DevUnlock = {
  taps: 7,
  /** One tap on the version: none | stepsLeft | unlocked | already; from the 4th tap on it says how many are left. */
  tap(count, unlocked) {
    if (unlocked) return { count: 0, outcome: "already", left: 0 };
    const n = count + 1;
    if (n >= 7) return { count: 0, outcome: "unlocked", left: 0 };
    if (n >= 4) return { count: n, outcome: "stepsLeft", left: 7 - n };
    return { count: n, outcome: "none", left: 0 };
  },
};

export const SPEEDS = {
  "1x": { factor: 1, label: "1×", tag: null }, "2x": { factor: 2, label: "2×", tag: "2× speed" },
  "4x": { factor: 4, label: "4×", tag: "4× speed" }, instant: { factor: 0, label: "Instant", tag: "Instant" },
};
export const Playback = {
  instantGapMs: 150,
  gap: (delayMs, speed) => (speed === "instant" ? 150 : Math.floor(delayMs / SPEEDS[speed].factor)),
};
export const overlayVisible = (enabled, unlocked, inCall, warningOpen) => enabled && unlocked && inCall && !warningOpen;

/** The plain connection status: connected | cantConnect | checking | notNeeded. */
export function connectionStatus(privacy, connection) {
  if (privacy === "on_device") return "notNeeded";
  return connection === true ? "connected" : connection === false ? "cantConnect" : "checking";
}
export const CONNECTION_KEY = { connected: "conn_ok", cantConnect: "conn_fail", checking: "status_checking", notNeeded: "conn_not_needed" };

// ---------------------------------------------------------------- the masked log

const TEXT_KEYS = new Set(["text", "lines", "transcript", "caller_lines", "riskiest_sentence", "sentence"]);
function walk(v, m) {
  if (Array.isArray(v)) return v.map((x) => walk(x, m));
  if (v && typeof v === "object") {
    const r = {};
    for (const k of Object.keys(v).sort()) r[k] = walk(v[k], m || TEXT_KEYS.has(k));
    return r;
  }
  return m && typeof v === "string" ? mask(v) : v;
}
/** JSON: masked and pretty-printed. Anything else: masked as plain text. */
export function maskBody(raw) {
  if (!raw || !raw.trim()) return "";
  try {
    const o = JSON.parse(raw);
    if (o && typeof o === "object") return JSON.stringify(walk(o, false), null, 2);
  } catch { /* not JSON */ }
  return mask(raw);
}

export class DevLog {
  constructor(capacity = 20) { this.capacity = capacity; this.recording = false; this.clear(); }
  clear() { this.entries = []; this.call = null; this.versions = {}; this.lastHealth = null; }
  add(e) {
    if (!this.recording) return;
    this.entries.unshift({ ...e, id: Math.random().toString(36).slice(2), request: maskBody(e.request), response: maskBody(e.response) });
    this.entries.length = Math.min(this.entries.length, this.capacity);
  }
  startCall(callId, privacy) { if (this.recording) this.call = { callId, privacy, lines: [], endedBy: null, end: null }; }
  addLine(callId, line) {
    if (!this.recording || this.call?.callId !== callId) return;
    this.call.lines = [...this.call.lines.filter((l) => l.seq !== line.seq), line].sort((a, b) => a.seq - b.seq);
  }
  endCall(callId, endedBy, end) {
    if (!this.recording || this.call?.callId !== callId) return;
    this.call.endedBy = endedBy;
    if (end) this.call.end = end;
  }
  latestLine(callId) { return this.call?.callId === callId ? this.call.lines[this.call.lines.length - 1] ?? null : null; }
  setModelVersions(v) { if (this.recording && v && Object.keys(v).length) Object.assign(this.versions, v); }
  setHealth(h) { if (this.recording) this.lastHealth = h; }
}

export const DevFormat = {
  score: (v) => (v == null ? "-" : v.toFixed(2)),
  seconds: (ms) => (ms / 1000).toFixed(1) + "s",
  time: (ms) => new Date(ms).toLocaleTimeString("en-GB", { hour12: false }),
  line: (l) => `#${l.seq}  ${DevFormat.seconds(l.tMs)}  U ${DevFormat.score(l.urgency)} M ${DevFormat.score(l.money)} I ${DevFormat.score(l.impersonation)}  ` +
    `C ${DevFormat.score(l.combined)}  BERT ${DevFormat.score(l.bertScore)} ${l.bertStatus ?? "-"}  rule ${l.rule ?? "-"}  ` +
    `live ${l.liveAlert ?? "-"}  ${l.verdict ?? "-"}  ${l.place} ${l.latencyMs}ms`,
  header: ["seq", "t", "U", "M", "I", "comb", "BERT", "status", "rule", "live", "verdict", "where", "ms"],
  row: (l) => [String(l.seq), DevFormat.seconds(l.tMs), DevFormat.score(l.urgency), DevFormat.score(l.money), DevFormat.score(l.impersonation),
    DevFormat.score(l.combined), DevFormat.score(l.bertScore), l.bertStatus ?? "-", l.rule ?? "-", l.liveAlert ?? "-", l.verdict ?? "-", l.place, String(l.latencyMs)],
  table(lines) {
    const rows = [DevFormat.header, ...lines.map(DevFormat.row)];
    const widths = DevFormat.header.map((_, c) => Math.max(...rows.map((r) => r[c].length)));
    return rows.map((r) => r.map((v, c) => v.padEnd(widths[c])).join("  ").trimEnd()).join("\n");
  },
  end: (e) => `final ${e.finalVerdict ?? "-"}  score ${DevFormat.score(e.callScore)}  review ${e.review ?? "-"}  top window ${e.topWindow?.length ? e.topWindow.join(",") : "-"}`,
};

export function diagnostics(connection, models, call) {
  const out = ["ScamGuard diagnostics", "", "1. Connection", ...connection.map(([k, v]) => `${k}: ${v}`), "", "2. Models",
    ...models.map(([k, v]) => `${k}: ${v}`), "", "3. Last call"];
  if (call) {
    out.push(`call_id: ${call.callId}`, `privacy: ${call.privacy}`, `ended_by: ${call.endedBy ?? "-"}`, ...call.lines.map(DevFormat.line));
    if (call.end) out.push("end: " + DevFormat.end(call.end));
  } else out.push("none");
  return out.join("\n");
}

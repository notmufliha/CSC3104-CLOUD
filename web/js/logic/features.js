// Ports of ios-app Logic/Learn.swift (quiz), WeeklySummary.swift, AfterCall.swift and Motion.swift.
import { T } from "./strings.js";
import { mask } from "./mask.js";

// ---------------------------------------------------------------- Learn: the practice quiz

export const QUIZ_SIZE = 5;
const MIN_EACH = 2;

/** Learning-loop test scripts (file number 90 and up): only in the Developer view, never in the quiz. */
export function isTestCall(fileName) {
  const parts = (fileName || "").replace(".json", "").split("_");
  return parts.length > 2 && (parseInt(parts[2], 10) || 0) >= 90;
}

const shuffle = (a, rand) => { a = [...a]; for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };

export const Quiz = {
  pool: (content) => (content?.quiz || []).filter((q) => !isTestCall(q.script)),
  /** 5 questions in random order: at least 2 scam and 2 safe; lines in `avoid` (the last round's) only when nothing else is left. */
  round(content, rand = Math.random, avoid = new Set()) {
    const pool = Quiz.pool(content);
    const pick = (items, n, taken) => {
      const fresh = shuffle(items.filter((q) => !avoid.has(q.text) && !taken.has(q.text)), rand);
      const old = shuffle(items.filter((q) => avoid.has(q.text) && !taken.has(q.text)), rand);
      return [...fresh, ...old].slice(0, n);
    };
    const scam = pick(pool.filter((q) => q.scam), MIN_EACH, new Set());
    const safe = pick(pool.filter((q) => !q.scam), MIN_EACH, new Set());
    const taken = new Set([...scam, ...safe].map((q) => q.text));
    return shuffle([...scam, ...safe, ...pick(pool, QUIZ_SIZE - scam.length - safe.length, taken)], rand);
  },
  isRight: (item, saidScam) => item.scam === saidScam,
  resultLine: (score, of = QUIZ_SIZE) => (score >= of ? "quiz_r5" : score === of - 1 ? "quiz_r4" : score === of - 2 ? "quiz_r3" : "quiz_r_low"),
  best: (saved, score) => Math.max(saved ?? 0, score),
};

// ---------------------------------------------------------------- Weekly summary

const MON = (d) => d.toLocaleString("en-GB", { month: "short" });
/** "12–18 Nov", or "28 Oct – 3 Nov" across a month. */
export function weekLabel(first, last) {
  return first.getMonth() === last.getMonth() && first.getFullYear() === last.getFullYear()
    ? `${first.getDate()}–${last.getDate()} ${MON(last)}` : `${first.getDate()} ${MON(first)} – ${last.getDate()} ${MON(last)}`;
}
export function week(nowMs) {
  const today = new Date(nowMs);
  const first = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6);
  return { startMs: first.getTime(), endMs: nowMs, label: weekLabel(first, today) };
}
const reasonWords = (code) => ({ money: "weekly_r_money", impersonation: "weekly_r_imp", urgency: "weekly_r_urgency" }[code] ?? "weekly_r_other");
const ORDER = ["weekly_r_money", "weekly_r_imp", "weekly_r_urgency", "weekly_r_other"];

/** The note for the family: counts and plain reasons only (no numbers, sentences or links). */
export function weeklyText(calls, nowMs = Date.now()) {
  const w = week(nowMs);
  const inWeek = calls.filter((c) => c.endedAt >= w.startMs && c.endedAt <= w.endMs && ["SAFE", "UNSURE", "SCAM"].includes(c.finalVerdict));
  const head = T("weekly_head", w.label), end = T("weekly_end");
  if (!inWeek.length) return `${head} ${T("weekly_none")} ${end}`;
  const scams = inWeek.filter((c) => c.finalVerdict === "SCAM");
  const careful = inWeek.filter((c) => c.finalVerdict === "UNSURE").length;
  const parts = [head, inWeek.length === 1 ? T("weekly_calls_one") : T("weekly_calls_many", inWeek.length)];
  if (!scams.length) parts.push(T("weekly_scam_zero"));
  else {
    const counts = {};
    for (const c of scams) { const k = reasonWords(c.reasonCode); counts[k] = (counts[k] || 0) + 1; }
    const reasons = Object.entries(counts).sort((a, b) => (a[1] !== b[1] ? b[1] - a[1] : ORDER.indexOf(a[0]) - ORDER.indexOf(b[0])))
      .map(([k]) => T(k)).join("; ");
    parts.push(scams.length === 1 ? T("weekly_scam_one", reasons) : T("weekly_scam_many", scams.length, reasons));
  }
  if (careful) parts.push(careful === 1 ? T("weekly_careful_one") : T("weekly_careful_many", careful));
  parts.push(end);
  return parts.join(" ");
}

// ---------------------------------------------------------------- After the call

/** The "What to do now" card: after a Scam call, and after a "Be careful" call unless those warnings are off. */
export const afterCardShows = (lvl, showCareful) => lvl === "red" || (lvl === "yellow" && showCareful);

/** "19 Nov 2026, 2:45 PM" (the same as both apps). */
export function whenText(ms) {
  const d = new Date(ms);
  let h = d.getHours();
  const ap = h >= 12 ? "PM" : "AM";
  h = h % 12 || 12;
  return `${d.getDate()} ${MON(d)} ${d.getFullYear()}, ${h}:${String(d.getMinutes()).padStart(2, "0")} ${ap}`;
}

/** The text copied before ScamShield opens; the caller's lines (masked) only if "Save the riskiest sentence" is on. */
export function reportSummary(timeMs, result, reason, lines, includeLines) {
  const out = [T("copy_title"), T("copy_when", whenText(timeMs)), T("copy_result", result), T("copy_why", reason)];
  const masked = includeLines ? [...new Set(lines.map((l) => mask(l).trim()).filter(Boolean))] : [];
  if (masked.length) { out.push(T("copy_lines")); for (const m of masked) out.push(`- "${m}"`); }
  out.push(T("copy_footer"));
  return out.join("\n");
}

/** iPhone steps; the web shows these (the apps show their own platform's). */
export const BLOCK_STEPS = ["block_ios_1", "block_ios_2", "block_ios_3", "block_ios_4"];
export const SHARED_STEPS = ["shared_bank", "shared_passwords", "shared_1799"];

// ---------------------------------------------------------------- Motion (prefers-reduced-motion)

export const Motion = {
  pressScale: (reduce) => (reduce ? 1 : 0.97),
  warningStartScale: (reduce) => (reduce ? 1 : 0.92),
  warningEnterMs: (reduce) => (reduce ? 0 : 280),
  warningFlash: (reduce) => !reduce,
  riskBarMs: (reduce) => (reduce ? 0 : 450),
};

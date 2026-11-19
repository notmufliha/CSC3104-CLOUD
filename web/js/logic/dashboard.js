// Ports of ios-app Logic/Dashboard.swift, DashboardStats.swift, PlainWords.swift, SampleHistory.swift and
// ImplicitLabels.swift: the dashboard's numbers and sentences, from this browser's history only.
// Dates use the browser's own time zone (iOS: TimeZone.current).
import { T } from "./strings.js";
import { SIGNALS, SIGNAL_ON, level, displayReason, codeForText, reasonText, PRIVACY_LEVELS } from "./risk.js";

const WARNINGS = new Set(["SCAM", "UNSURE"]);
export const UNSURE_LINE = 0.45, SCAM_LINE = 0.6;
export const DAY_MS = 86_400_000;

// ---------------------------------------------------------------- Dashboard

/** Chart points: [seconds into the call, combined 0-1] in time order. */
export function chart(points) {
  return points.map((p, i) => [p, i]).filter(([p]) => p.combined != null)
    .sort((a, b) => (a[0].secondsIntoCall !== b[0].secondsIntoCall ? a[0].secondsIntoCall - b[0].secondsIntoCall : a[1] - b[1]))
    .map(([p]) => [p.secondsIntoCall, Math.min(Math.max(p.combined, 0), 1)]);
}
export const lineMax = (p) => { const v = [p.urgency, p.money, p.impersonation].filter((x) => x != null); return v.length ? Math.max(...v) : null; };
/** The seq of the riskiest caller line (highest single-signal score, later line on a tie); null below SIGNAL_ON. */
export function riskiestSeq(points) {
  let best = null;
  for (const p of points) {
    const v = lineMax(p);
    if (v == null || v < SIGNAL_ON) continue;
    if (!best || v > best.v || (v === best.v && p.seq > best.seq)) best = { seq: p.seq, v };
  }
  return best?.seq ?? null;
}

// ---------------------------------------------------------------- DashboardStats

export const PERIODS = { week: 7, month: 30, all: null };
const startOfDay = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d; };
/** Calendar days between the call's local date and today's (0 = today; negative = future). */
export function daysAgo(timeMs, nowMs) {
  return Math.round((startOfDay(nowMs) - startOfDay(timeMs)) / DAY_MS);
}
export const hourOf = (ms) => new Date(ms).getHours();
export const inPeriod = (d, p) => (PERIODS[p] == null ? true : d >= 0 && d < PERIODS[p]);
/** Whole percent, rounded half up, without floating point. */
export const percent = (part, total) => (total === 0 ? 0 : Math.floor((part * 200 + total) / (2 * total)));

export function compute(allCalls, allLabels, period, nowMs) {
  const calls = allCalls.filter((c) => inPeriod(daysAgo(c.endedAt, nowMs), period));
  const labels = allLabels.filter((l) => inPeriod(daysAgo(l.time, nowMs), period));
  const count = (v) => calls.filter((c) => c.finalVerdict === v).length;
  const safe = count("SAFE"), unsure = count("UNSURE"), scam = count("SCAM");
  const notChecked = calls.length - safe - unsure - scam;
  const weekly = PERIODS[period] == null;
  const dayIdx = calls.map((c) => Math.max(daysAgo(c.endedAt, nowMs), 0));
  const n = PERIODS[period] ?? (Math.floor(Math.max(0, ...dayIdx) / 7) + 1);
  const width = weekly ? 7 : 1;
  const buckets = [];
  for (let b = 0; b < n; b++) {
    const back = n - 1 - b;
    const members = calls.filter((_, i) => Math.floor(dayIdx[i] / width) === back);
    const scores = members.map((c) => c.callScore).filter((x) => x != null);
    buckets.push({
      daysAgo: back * width + (width - 1),
      safe: members.filter((c) => c.finalVerdict === "SAFE").length,
      unsure: members.filter((c) => c.finalVerdict === "UNSURE").length,
      scam: members.filter((c) => c.finalVerdict === "SCAM").length,
      notChecked: members.filter((c) => !["SAFE", "UNSURE", "SCAM"].includes(c.finalVerdict)).length,
      avgRisk: scores.length ? scores.reduce((a, x) => a + x, 0) / scores.length : null,
      get total() { return this.safe + this.unsure + this.scam + this.notChecked; },
    });
  }
  const warned = calls.filter((c) => WARNINGS.has(c.finalVerdict));
  const reasonCounts = {};
  for (const c of warned) { const r = displayReason(c); if (r.trim()) reasonCounts[r] = (reasonCounts[r] || 0) + 1; }
  const reasons = Object.entries(reasonCounts).sort((a, b) => (a[1] !== b[1] ? b[1] - a[1] : a[0] < b[0] ? -1 : 1)).slice(0, 5);
  const signals = SIGNALS.map((s) => [s, calls.filter((c) => (c.signalsFired || []).includes(s)).length]);
  const hours = new Array(24).fill(0);
  for (const c of warned) hours[hourOf(c.endedAt)]++;
  const liveWarn = calls.filter((c) => WARNINGS.has(c.liveVerdict ?? ""));
  const lengths = calls.map((c) => Math.max(Math.floor((c.endedAt - c.startedAt) / 1000), 0));
  return {
    total: calls.length, safe, unsure, scam, notChecked, safePercent: percent(safe, calls.length), buckets, weekly, reasons, signals,
    riskyHours: hours,
    confirmed: liveWarn.filter((c) => WARNINGS.has(c.finalVerdict)).length,
    calmed: liveWarn.filter((c) => c.finalVerdict === "SAFE").length,
    raised: calls.filter((c) => c.liveVerdict === "SAFE" && WARNINGS.has(c.finalVerdict)).length,
    privacy: PRIVACY_LEVELS.map((l) => [l, calls.filter((c) => c.privacyLevel === l).length]),
    markedFine: labels.filter((l) => l.source === LABELS.sourceFine).length,
    hungUpAfterWarning: labels.filter((l) => l.source === LABELS.sourceHungUp).length,
    lengthVsRisk: calls.map((c, i) => (c.callScore != null ? [lengths[i], c.callScore] : null)).filter(Boolean),
    avgLengthSeconds: lengths.length ? Math.floor(lengths.reduce((a, x) => a + x, 0) / lengths.length) : 0,
    get isEmpty() { return this.total === 0; },
  };
}

export function whatHappened(finalVerdict, liveVerdict, reason) {
  let r = reason.trim();
  while (r.endsWith(".")) r = r.slice(0, -1);
  if (r) r = r[0].toLowerCase() + r.slice(1);
  switch (finalVerdict) {
    case "SCAM": return r ? T("ds_what_scam_r", r) : T("ds_what_scam");
    case "UNSURE": return r ? T("ds_what_off_r", r) : T("ds_what_off");
    case "SAFE": return WARNINGS.has(liveVerdict ?? "") ? T("ds_what_calmed") : T("ds_what_safe");
    default: return T("ds_what_unchecked");
  }
}
export const duration = (s) => (s >= 60 ? T("pw_dur_min", Math.floor(s / 60), s % 60) : T("pw_dur_s", s));

// ---------------------------------------------------------------- PlainWords

const PERIOD_PHRASE = { week: "pw_period_week", month: "pw_period_month", all: "pw_period_all" };
const PERIOD_LABEL = { week: "period_week", month: "period_month", all: "period_all" };
export const PlainWords = {
  periodPhrase: (p) => T(PERIOD_PHRASE[p]),
  periodLabel: (p) => T(PERIOD_LABEL[p]),
  hero(scams, total, p) {
    if (total === 0) return p === "all" ? T("pw_hero_none_all") : T("pw_hero_none", T(PERIOD_PHRASE[p]));
    if (scams === 0) return T("pw_hero_no_scams", T(PERIOD_PHRASE[p]));
    if (scams === 1) return T("pw_hero_one", T(PERIOD_PHRASE[p]));
    return T("pw_hero_many", scams, T(PERIOD_PHRASE[p]));
  },
  signalName: (s) => ({ urgency: T("pw_sig_urgency"), money: T("pw_sig_money"), impersonation: T("pw_sig_impersonation") }[s] ?? s),
  reasonSentence: (code) => T({ money: "pw_reason_money", impersonation: "pw_reason_impersonation", urgency: "pw_reason_urgency", bert: "pw_reason_bert" }[code] ?? "pw_reason_several"),
  topWarning: (s) => (s.reasons.length ? PlainWords.reasonSentence(codeForText(s.reasons[0][0])) : null),
  overTime: (s) => (s.scam === 0 ? T("pw_over_none") : T("pw_over_pct", percent(s.scam, s.total))),
  reasons: (s) => PlainWords.topWarning(s) ?? T("pw_no_flagged"),
  signals(s) {
    let top = null;
    for (const x of s.signals) if (!top || x[1] > top[1]) top = x;
    return top && top[1] > 0 ? T("pw_sign_most", PlainWords.signalName(top[0])) : T("pw_no_signs");
  },
  trend(s) {
    const vals = s.buckets.map((b) => b.avgRisk).filter((x) => x != null);
    if (vals.length < 2) return T("pw_trend_few");
    const half = Math.floor(vals.length / 2);
    const avg = (a) => a.reduce((x, y) => x + y, 0) / a.length;
    const diff = avg(vals.slice(half)) - avg(vals.slice(0, half));
    return diff > 0.05 ? T("pw_trend_up") : diff < -0.05 ? T("pw_trend_down") : T("pw_trend_same");
  },
  hours(s) {
    if (s.riskyHours.reduce((a, x) => a + x, 0) === 0) return T("pw_hours_none");
    let peak = 0;
    s.riskyHours.forEach((n, i) => { if (n > s.riskyHours[peak]) peak = i; });
    const part = peak >= 5 && peak <= 11 ? "morning" : peak >= 12 && peak <= 16 ? "afternoon" : peak >= 17 && peak <= 21 ? "evening" : "night";
    return T("pw_hours_" + part);
  },
  doubleCheck(s) {
    if (s.confirmed + s.calmed === 0) return T("pw_double_none");
    if (s.calmed === 0) return T("pw_double_all");
    return s.calmed === 1 ? T("pw_double_one") : T("pw_double_many", s.calmed);
  },
  whereChecked(s) {
    let top = null;
    for (const x of s.privacy) if (!top || x[1] > top[1]) top = x;
    if (!top || top[1] === 0) return T("pw_none_period");
    return T(top[0] === "hybrid" ? "pw_where_hybrid" : top[0] === "on_device" ? "pw_where_on_device" : "pw_where_cloud");
  },
  answers(s) {
    if (s.markedFine + s.hungUpAfterWarning === 0) return T("pw_answers_none");
    return s.markedFine === 1 ? T("pw_answers_one") : T("pw_answers_many", s.markedFine);
  },
  length: (s) => (s.total === 0 ? T("pw_none_period") : T("pw_length", duration(s.avgLengthSeconds))),
  timeAgo(endedAt, now, days) {
    const minutes = Math.floor((now - endedAt) / 60_000);
    if (minutes < 1) return T("pw_just_now");
    if (minutes < 60) return T("pw_min_ago", minutes);
    if (days <= 0) return T("pw_h_ago", Math.floor(minutes / 60));
    if (days === 1) return T("pw_yesterday");
    if (days < 7) return T("pw_days_ago", days);
    return null;
  },
  historyGroup: (days) => T(days <= 0 ? "pw_today" : days < 7 ? "pw_this_week" : "pw_earlier"),
  checkedWhere: (id) => T(id === "hybrid" ? "pw_checked_hybrid" : id === "on_device" ? "pw_checked_on_device" : "pw_checked_cloud"),
};

const fmt = (ms, opts) => new Date(ms).toLocaleString("en-GB", opts);
export const shortDate = (ms) => fmt(ms, { day: "numeric", month: "short" });
export const hhmm = (ms) => fmt(ms, { hour: "2-digit", minute: "2-digit", hour12: false });
/** "12 min ago", "Yesterday", or "3 Nov". */
export const whenText = (endedAt, now = Date.now()) => PlainWords.timeAgo(endedAt, now, daysAgo(endedAt, now)) ?? shortDate(endedAt);
export function dayLabel(d) {
  if (d === 0) return T("pw_today");
  if (d === 1) return T("pw_yesterday");
  return shortDate(Date.now() - d * DAY_MS);
}

// ---------------------------------------------------------------- implicit labels (kept in this browser only)

export const LABELS = {
  hangUpWindowMs: 10_000, confidentScam: 0.9, confidentSafe: 0.1,
  sourceHungUp: "hung_up_after_alert", sourceFine: "this_was_fine", sourceReview: "final_review",
  atEnd(hungUp, alertAt, endedAt, review) {
    const out = [];
    if (hungUp && alertAt != null && endedAt - alertAt >= 0 && endedAt - alertAt <= LABELS.hangUpWindowMs) out.push({ label: 1, source: LABELS.sourceHungUp });
    if (review?.reviewed && review.callScore != null) {
      if (review.callScore > LABELS.confidentScam) out.push({ label: 1, source: LABELS.sourceReview });
      else if (review.callScore < LABELS.confidentSafe) out.push({ label: 0, source: LABELS.sourceReview });
    }
    return out;
  },
};

// ---------------------------------------------------------------- sample history (Developer view)

/** ~30 days of made-up calls, deterministic (the same LCG as both apps, so the same history from the same seed). */
export function sampleHistory(nowMs, todayStartMs, seed = 42) {
  let s = BigInt(seed);
  const nextInt = () => { s = (s * 1103515245n + 12345n) & 0x7fffffffn; return Number(s); };
  const int = (n) => nextInt() % n;
  const calls = [], points = [], labels = [];
  for (let d = 0; d < 30; d++) {
    const n = 1 + int(3);
    for (let k = 0; k < n; k++) {
      const h = 8 + int(14), m = int(60);
      const endedAt = todayStartMs - d * DAY_MS + h * 3_600_000 + m * 60_000;
      const durationS = 30 + int(121);
      const roll = int(100);
      const final = roll < 55 ? "SAFE" : roll < 75 ? "UNSURE" : roll < 95 ? "SCAM" : "NOT CHECKED";
      const p = int(100);
      const privacy = p < 60 ? "cloud" : p < 85 ? "hybrid" : "on_device";
      const lines = 4 + int(6);
      const sig = [];
      if (final === "SCAM") {
        if (int(100) < 70) sig.push("money");
        if (int(100) < 60) sig.push("impersonation");
        if (int(100) < 50) sig.push("urgency");
        if (!sig.length) sig.push("money");
      } else if (final === "UNSURE") sig.push(SIGNALS[int(3)]);
      let live = final;
      if (final === "SAFE" && int(100) < 15) live = "UNSURE";
      const id = `sample-${d}-${k}`;
      let callScore = null;
      if (final !== "NOT CHECKED") {
        for (let i = 0; i < lines; i++) {
          const base = final === "SAFE" ? 50 + int(250) : final === "UNSURE" ? 250 + int(250) : Math.min(990, 200 + Math.floor(i * 700 / (lines - 1)) + int(100));
          const sc = SIGNALS.map((x) => (sig.includes(x) ? 500 + int(450) : int(300)) / 1000);
          const combined = base / 1000;
          points.push({ callId: id, seq: 2 * i + 1, secondsIntoCall: ((i + 1) * durationS) / lines, combined, urgency: sc[0], money: sc[1], impersonation: sc[2] });
          callScore = combined;
        }
      }
      const code = final === "SAFE" ? "none" : final === "NOT CHECKED" ? "server_not_reached" : final === "UNSURE" ? sig[0]
        : int(100) < 20 ? "bert" : ["money", "impersonation", "urgency"].find((x) => sig.includes(x));
      calls.push({ id, startedAt: endedAt - durationS * 1000, endedAt, finalVerdict: final, liveVerdict: live, topReason: reasonText(code) ?? "",
        callScore, signalsFired: SIGNALS.filter((x) => sig.includes(x)), review: final === "NOT CHECKED" ? "unavailable" : privacy === "cloud" ? "bert" : "on_device",
        riskiestSentence: null, privacyLevel: privacy, reasonCode: code });
      if (final === "SCAM" && int(100) < 40) labels.push({ callId: id, label: 1, source: LABELS.sourceHungUp, time: endedAt });
      if (final === "SAFE" && live === "UNSURE" && int(100) < 50) labels.push({ callId: id, label: 0, source: LABELS.sourceFine, time: endedAt });
    }
  }
  const keep = new Set(calls.filter((c) => c.endedAt <= nowMs).map((c) => c.id));
  return { calls: calls.filter((c) => keep.has(c.id)), points: points.filter((p) => keep.has(p.callId)), labels: labels.filter((l) => keep.has(l.callId)) };
}

export { level };

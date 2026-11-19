// Ports of ios-app Logic/Risk.swift, Reasons.swift, FinalReview.swift, Warnings.swift, PrivacyLevel.swift and the
// derived values of CallState.swift: colours, chips, reasons and the words on the warning, the same on every platform.
import { T } from "./strings.js";
import { REASON_TEXT, actionFor } from "../ondevice/localRules.js";

// ---------------------------------------------------------------- Risk

export const SIGNALS = ["urgency", "money", "impersonation"];
export const SIGNAL_ON = 0.5;
export const NOT_CHECKED = "NOT CHECKED";

/** listening | green | yellow | red | error. A warning always stays; an error beats SAFE. */
export function level(verdict, error) {
  if (verdict === "SCAM") return "red";
  if (verdict === "UNSURE") return "yellow";
  if (error) return "error";
  if (verdict === "SAFE") return "green";
  return "listening";
}
export const litSignals = (callScores) => SIGNALS.filter((s) => (callScores?.[s] ?? 0) >= SIGNAL_ON);
export const chipLabel = (s) => ({ urgency: T("chip_urgency"), money: T("chip_money"), impersonation: T("chip_impersonation") }[s] ?? s);
/** With "Show 'Be careful' warnings" off, a yellow moment shows as "Listening…". */
export const shownLevel = (lvl, showCareful) => (lvl === "yellow" && !showCareful ? "listening" : lvl);

export function oldReason(lit, flaggedTexts) {
  const all = flaggedTexts.join(" ").toLowerCase();
  if (lit.includes("money") && (all.includes("otp") || all.includes("one-time") || all.includes("验证码"))) return "asking for your OTP";
  if (lit.includes("money")) return "asking you to transfer money";
  if (lit.includes("impersonation")) return "pretending to be an official (police, bank, government)";
  if (lit.includes("urgency")) return "pressuring you to act right now";
  return "several small warning signs together";
}
export function oldAction(lit) {
  if (lit.includes("money")) return "Hang up and call your bank on the number on your card.";
  if (lit.includes("impersonation")) return "Hang up and call the agency back on its official number.";
  return "Don't share personal details. Real organisations give you time to check.";
}
export const historyVerdict = (lvl) => ({ red: "SCAM", yellow: "UNSURE", green: "SAFE" }[lvl] ?? NOT_CHECKED);

/** Theme.levelLabel: the live call's word. */
export const levelLabel = (lvl) => T({ listening: "risk_listening", green: "risk_safe_so_far", yellow: "risk_careful", red: "risk_scam", error: "risk_could_not_check" }[lvl]);
/** Theme.riskWord: a finished call's word. */
export const riskWord = (lvl) => T({ green: "risk_safe", yellow: "risk_careful", red: "risk_scam", error: "risk_not_checked", listening: "risk_listening" }[lvl]);
export const verdictWord = (v) => T({ SAFE: "risk_safe", UNSURE: "risk_careful", SCAM: "risk_scam" }[v] ?? "risk_not_checked");
/** A saved verdict -> its colour level ("NOT CHECKED" -> grey "error"). */
export const savedLevel = (v) => (["SAFE", "UNSURE", "SCAM"].includes(v) ? level(v, null) : "error");

// ---------------------------------------------------------------- Reasons

export const REASONS = { money: "money", impersonation: "impersonation", urgency: "urgency", bert: "bert", several: "several",
  earlier: "earlier", none: "none", serverNotReached: "server_not_reached", noLines: "no_lines" };
const TEXT_BY_CODE = { ...REASON_TEXT, several: "Several small warning signs together", earlier: "Warning signs earlier in the call",
  none: "No warning signs", server_not_reached: "Server not reached", no_lines: "Ended before any line was checked" };
export const reasonText = (code) => (code != null ? TEXT_BY_CODE[code] ?? null : null);
export const reasonLabel = (code) => (code && TEXT_BY_CODE[code] ? T("reason_" + code) : null);
const KNOWN_TEXTS = (() => {
  const m = {};
  for (const [code, text] of Object.entries(TEXT_BY_CODE)) m[text.toLowerCase()] = code;
  Object.assign(m, {
    "asking for your otp": "money", "asking you to transfer money": "money",
    "pretending to be an official (police, bank, government)": "impersonation", "pressuring you to act right now": "urgency",
    "several small warning signs together": "several", "no warning signs": "none", "server not reached": "server_not_reached",
    "ended before any line was checked": "no_lines",
  });
  return m;
})();
export const codeForText = (text) => (text == null ? null : KNOWN_TEXTS[text.trim().toLowerCase()] ?? null);
export const localizedReason = (text) => reasonLabel(codeForText(text)) ?? text;
export const displayReason = (row) => reasonText(row.reasonCode) ?? row.topReason ?? "";

export function codeFor(lvl, recentScores, serverReason) {
  if (lvl === "red" || lvl === "yellow") {
    const c = codeForText(serverReason);
    if (c) return c;
    let best = null;
    for (const s of SIGNALS) {
      const v = recentScores?.[s] ?? 0;
      if (v >= SIGNAL_ON && (best === null || v > best[1])) best = [s, v];
    }
    return best?.[0] ?? "several";
  }
  return { green: "none", error: "server_not_reached" }[lvl] ?? "no_lines";
}

// ---------------------------------------------------------------- FinalReview / Summary

export const FinalReview = {
  unavailable: () => ({ finalVerdict: null, reason: "", reviewed: false, topWindowSeqs: [], answered: false, callScore: null, onDevice: false }),
  onDevice: () => ({ finalVerdict: null, reason: "", reviewed: false, topWindowSeqs: [], answered: false, callScore: null, onDevice: true }),
  from(resp) {
    if (!resp) return FinalReview.unavailable();
    const v = ["SAFE", "UNSURE", "SCAM"].includes(resp.final_verdict) ? resp.final_verdict : null;
    return { finalVerdict: v, reason: resp.reason || "", reviewed: resp.review === "bert" && v !== null,
      topWindowSeqs: resp.top_window_seqs || [], answered: true, callScore: resp.call_score ?? null, onDevice: false };
  },
  type(fr) { return fr.onDevice ? "on_device" : fr.reviewed ? "bert" : fr.answered ? "fallback" : "unavailable"; },
};
export const SEVERITY = { listening: 0, green: 1, error: 2, yellow: 3, red: 4 };
export function summaryLevelOf(final, liveVerdict, gatewayError) {
  const finalLevel = level(final?.finalVerdict ?? liveVerdict, gatewayError);
  if (final?.reviewed) return finalLevel;
  const live = level(liveVerdict, gatewayError);
  return SEVERITY[finalLevel] >= SEVERITY[live] ? finalLevel : live;
}

// ---------------------------------------------------------------- Warnings

export function warningKind(lit, flaggedTexts, reasonCode = null) {
  const all = flaggedTexts.join(" ").toLowerCase();
  const otp = all.includes("otp") || all.includes("one-time") || all.includes("验证码");
  if (lit.includes("money")) return otp ? "otp" : "money";
  if (lit.includes("impersonation")) return "official";
  if (lit.includes("urgency")) return "rush";
  return { money: otp ? "otp" : "money", impersonation: "official", urgency: "rush", several: "several", earlier: "earlier" }[reasonCode] ?? "general";
}
export const warnMessageKey = (k) => "warn_msg_" + k;
export const warnDoKey = (k) => (k === "otp" ? "warn_do_otp" : k === "money" ? "warn_do_money" : "warn_do_details");
export const carefulKey = (k) => ({ otp: "careful_msg_money", money: "careful_msg_money", official: "careful_msg_official", rush: "careful_msg_rush" }[k] ?? "careful_msg_general");
export const spokenWarning = (k) => T("warn_spoken", T(warnDoKey(k)));
export const familyMessage = () => T("sms_family");

// ---------------------------------------------------------------- PrivacyLevel

export const PRIVACY = {
  cloud: { id: "cloud", badge: "Cloud", tag: "privacy_tag_cloud", plain: "privacy_plain_cloud",
    explanation: "Every caller sentence is checked by the ScamGuard server. Nothing is stored there." },
  hybrid: { id: "hybrid", badge: "Hybrid", tag: "privacy_tag_hybrid", plain: "privacy_plain_hybrid",
    explanation: "Checked on this phone. Only at unsure moments, the caller's last 5 sentences go to the server, with numbers and names removed. English calls only." },
  on_device: { id: "on_device", badge: "On-device", tag: "privacy_tag_on_device", plain: "privacy_plain_on_device",
    explanation: "Everything is checked on this phone, even in airplane mode. Nothing about the call leaves it. English calls only." },
};
export const PRIVACY_LEVELS = ["cloud", "hybrid", "on_device"];
export const privacyFromId = (id) => (PRIVACY[id] ? id : "cloud");
export const privacyBadge = (id) => PRIVACY[id]?.badge ?? id;
export const scoresOnPhone = (id) => id !== "cloud";
export const choiceTitle = (id, simple) => (simple ? T(PRIVACY[id].tag) : `${PRIVACY[id].badge} · ${T(PRIVACY[id].tag)}`);

// ---------------------------------------------------------------- the call state's derived values (CallState.swift)

export const newCallState = () => ({
  script: null, phase: "idle", lines: [], verdict: null, combinedScore: null, callScores: {}, gatewayError: null,
  scamAlert: false, reviewing: false, finalReview: null, privacyLevel: "cloud", serverAction: null, warningOpen: false,
  carefulAlerted: false, speedTag: null,
});
export const callLevel = (s) => level(s.verdict, s.gatewayError);
export const summaryLevel = (s) => summaryLevelOf(s.finalReview, s.verdict, s.gatewayError);
export const callLit = (s) => litSignals(s.callScores);
export const flaggedTexts = (s) => s.lines.filter((l) => l.flags.length).map((l) => l.text);
export const callWarningKind = (s) => warningKind(callLit(s), flaggedTexts(s), codeForText(s.finalReview?.reason));
export const callReasonCode = (s) => codeFor(summaryLevel(s), s.callScores, s.finalReview?.reason);
export function triggerLines(s) {
  const risky = new Set(s.finalReview?.topWindowSeqs || []);
  return s.lines.filter((l) => l.isCaller && (l.flags.length || risky.has(l.seq)));
}
export function duringCallLine(s) {
  const live = levelLabel(callLevel(s));
  if (s.finalReview?.onDevice) return T("sum_during_phone", live);
  if (s.finalReview?.reviewed) return T("sum_during", live);
  return T("sum_during_unavailable", live);
}
/** The reason text saved with a call (shown only if its code is unknown). */
export function historyReason(s) {
  const lvl = summaryLevel(s);
  if (lvl === "red" || lvl === "yellow") {
    const r = s.finalReview?.reason?.trim();
    if (r) return r;
    return reasonText(callReasonCode(s)) ?? oldReason(callLit(s), flaggedTexts(s));
  }
  return { green: "no warning signs", error: "server not reached" }[lvl] ?? "ended before any line was checked";
}
export { actionFor };

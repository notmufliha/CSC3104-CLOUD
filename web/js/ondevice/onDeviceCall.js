// Port of ios-app OnDevice/OnDeviceCall.swift: one call scored in the browser. Each caller line goes through the
// three checks and the aggregator's rules and comes back in the same shape as the gateway's POST /v1/utterance answer,
// so the in-call screen, chips, warning and dashboard work exactly as in the cloud.
//   Level 3 (On-device): `answer` (no BERT: the rules see it as "off"). Nothing touches the network.
//   Level 2 (Hybrid): `answerHybrid`: the caller's last 5 lines, masked (Mask), go to POST /v1/verify for a cloud BERT
//   opinion, only at unsure moments or on the spot check (every 5th caller line), never once the call is red.
import { LocalRules, SECOND_OPINION_RULES, SIGNALS, actionFor } from "./localRules.js";
import { mask } from "../logic/mask.js";
import { level as riskLevel } from "../logic/risk.js";

export const ASK_AT = 0.45;
export const MIN_LINES = 3;
export const WINDOW_LINES = 5;
export const VERIFY_TIMEOUT_MS = 1500;

export class OnDeviceCall {
  constructor(scorer, rules = new LocalRules(), clock = () => Date.now()) {
    this.scorer = scorer;
    this.rules = rules;
    this.clock = clock;
    this.recentLines = [];
  }

  /** Level 3: everything in the browser. */
  answer(callId, seq, text) {
    const start = performance.now();
    const scores = this.scorer.score(text);
    this.remember(text);
    const d = this.rules.decide(lineOf(scores), this.clock());
    return response(callId, seq, scores, d, start, "off");
  }

  /** Level 2. `verify(maskedLines)` resolves BERT's score, or null on timeout / error. */
  async answerHybrid(callId, seq, text, verify) {
    return this.answerWithSecondOpinion(callId, seq, text, mask, false, verify);
  }

  async answerWithSecondOpinion(callId, seq, text, prepare, askWhenRed, ask) {
    const start = performance.now();
    const now = this.clock();
    const scores = this.scorer.score(text);
    this.remember(text);
    const line = lineOf(scores);
    const own = this.rules.peek(line, now);
    let wanted = null;
    if (this.rules.red) wanted = askWhenRed ? "red" : null;
    else if (own.fast >= ASK_AT || SECOND_OPINION_RULES.has(own.rule)) wanted = "unsure";
    else if (this.rules.isSpotCheck()) wanted = "spot_check";
    const enoughLines = this.rules.callerLines + 1 >= MIN_LINES;
    let second = null;
    if (wanted && enoughLines) {
      const lines = this.recentLines.map(prepare);
      second = { why: wanted, lines, bertScore: await ask(lines) };
    }
    const status = !second && wanted && !enoughLines ? "no_context" : !second ? "skipped" : second.bertScore != null ? "ok" : "error";
    const d = this.rules.decide(line, now, 0, second?.bertScore ?? null, status);
    return { response: response(callId, seq, scores, d, start, status), decision: d, second };
  }

  remember(text) {
    this.recentLines.push(text);
    if (this.recentLines.length > WINDOW_LINES) this.recentLines.splice(0, this.recentLines.length - WINDOW_LINES);
  }

  /** The models are English only: same 30% rule as the gateway's language detection. */
  static isEnglish(scriptLang, text) {
    if (scriptLang === "zh") return false;
    let chinese = 0;
    for (let i = 0; i < text.length; i++) { const c = text.charCodeAt(i); if (c >= 0x4e00 && c <= 0x9fff) chinese++; }
    return chinese * 10 <= text.length * 3;
  }
}

const lineOf = (scores) => SIGNALS.map((s) => scores[s] ?? null);

function response(callId, seq, scores, d, start, bertStatus) {
  const recent = {};
  SIGNALS.forEach((s, i) => { if (d.recent[i] != null) recent[s] = d.recent[i]; });
  return {
    call_id: callId, seq, scored: true, duplicate: false, verdict: d.verdict, combined_score: d.combined, language: "en",
    scores, missing: [], recent_scores: recent, caller_lines: d.callerLines,
    latency_ms: Math.round(performance.now() - start),
    reasons: d.reasons.map((r) => ({ signal: r.signal, score: r.score, text: r.text })),
    action: actionFor(riskLevel(d.verdict, null)) ?? "",
    // for the Developer view only
    live_alert: d.verdict, fast_score: d.fast, bert_score: d.bert, bert_status: bertStatus, rule: d.rule,
  };
}

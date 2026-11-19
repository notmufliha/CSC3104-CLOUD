// Port of ios-app OnDevice/LocalRules.swift (= android-app ondevice/LocalRules.kt, itself a port of
// backend/aggregator/src/policy.rs `decide_with_bert_status`, cascade mode), so levels 2 and 3 give the same
// SAFE / UNSURE / SCAM, chips and reasons as the cloud.
//
// Rules, in order:
//  1. add the line to the window (last 6 caller lines; each signal's recent score = its highest there);
//  2. a red call stays red while BERT is unavailable or confirms it; two BERT disagreements in a row clear it;
//  3. fewer than 2 checks answered (and no BERT) -> UNSURE "not enough data";
//  4. fast = weighted average of the recent scores; combined = fast, or blended 50/50 with BERT when it answered;
//  5. warm-up (first 15 s, nothing >= 0.9, BERT included) -> SAFE;
//  6/7. an alarmed vote (money rule, or fast >= 0.6, or BERT >= 0.6) turns red only if BERT confirms it (>= 0.6),
//     or, when BERT is unavailable ("off" / "timeout" / "error"), two signals are >= 0.8 ("strong_fallback");
//  8. combined >= 0.45, or an alarmed vote BERT didn't confirm -> UNSURE;
//  9. otherwise SAFE.

export const SIGNALS = ["urgency", "money", "impersonation"];

export const defaultConfig = () => ({
  weights: [1 / 3, 1 / 3, 1 / 3], scamThreshold: 0.6, unsureThreshold: 0.45, bertScamThreshold: 0.6,
  fallbackStrongSignal: 0.8, redDisagreementsToClear: 2, windowLines: 6, warmupMs: 15_000, warmupOverride: 0.9,
  moneyRule: 0.7, moneyRuleOther: 0.5, moneyRuleLines: 6, reasonThreshold: 0.5, minQuorum: 2, bertWeight: 0.5,
  bertSpotEvery: 5, bertMinLines: 3,
});

/** policy.rs SECOND_OPINION_RULES: rules whose answer BERT can change. */
export const SECOND_OPINION_RULES = new Set(["quorum", "money_rule", "high_once", "streak", "unsure", "strong_fallback"]);
/** policy.rs reason_text: the server's wording. */
export const REASON_TEXT = {
  money: "Asked for your OTP or money",
  impersonation: "Claims to be police, a bank or the government",
  urgency: "Pressuring you to act now",
  bert: "The conversation as a whole sounds like a scam",
};
export const ACTION_SCAM = "Hang up now. Call the organisation back on its official number, or ScamShield on 1799.";
export const ACTION_UNSURE = "Don't share any codes or transfer money. Hang up and check with the official number.";
export const actionFor = (level) => (level === "red" ? ACTION_SCAM : level === "yellow" ? ACTION_UNSURE : null);

const emptyMemory = () => ({ startedAtMs: 0, window: [], streak: 0, red: false, redDisagreements: 0, callerLines: 0 });

export class LocalRules {
  constructor(cfg = {}) {
    this.cfg = { ...defaultConfig(), ...cfg };
    this.memory = emptyMemory();
  }
  get callerLines() { return this.memory.callerLines; }
  get red() { return this.memory.red; }

  /** One caller line (scores in SIGNALS order; null = that check didn't answer). callTimeMs 0 = unknown. */
  decide(line, nowMs, callTimeMs = 0, bert = null, bertStatus = null) {
    const [next, d] = this.step(this.memory, line, nowMs, callTimeMs, bert, bertStatus ?? (bert != null ? "ok" : "off"));
    this.memory = next;
    return d;
  }

  wantsSecondOpinion(line, nowMs, callTimeMs = 0) {
    return this.memory.red || this.isSpotCheck() || SECOND_OPINION_RULES.has(this.peek(line, nowMs, callTimeMs).rule);
  }

  isSpotCheck() {
    const c = this.cfg;
    return c.bertSpotEvery > 0 && !this.memory.red && (this.memory.callerLines + 1) % c.bertSpotEvery === 0;
  }

  peek(line, nowMs, callTimeMs = 0) { return this.step(this.memory, line, nowMs, callTimeMs, null, "off")[1]; }

  step(before, line, nowMs, callTimeMs, bert, bertStatus) {
    const cfg = this.cfg;
    if (line.length !== SIGNALS.length) throw new Error("line must have 3 scores");
    const startedAt = before.startedAtMs === 0 ? nowMs - Math.max(callTimeMs, 0) : before.startedAtMs;
    const window = [...before.window, line].slice(-cfg.windowLines);
    const callerLines = before.callerLines + 1;
    const recent = this.highs(window);
    const fast = this.combined(recent);
    const answered = line.filter((x) => x != null).length;
    const quorumMet = answered >= cfg.minQuorum;
    const warmingUp = (callTimeMs > 0 ? callTimeMs : nowMs - startedAt) < cfg.warmupMs;
    const combinedScore = bert != null ? (quorumMet ? (1 - cfg.bertWeight) * fast + cfg.bertWeight * bert : bert) : fast;
    const overrideHit = recent.some((v) => (v ?? -1) >= cfg.warmupOverride) || (bert != null && bert >= cfg.warmupOverride);
    const bertUnavailable = ["off", "timeout", "error"].includes(bertStatus);
    const bertConfirms = bert != null && bert >= cfg.bertScamThreshold;
    const redCandidate = (!warmingUp || overrideHit) && (quorumMet || bert != null) &&
      ((quorumMet && this.moneyRule(window)) || fast >= cfg.scamThreshold || bertConfirms);
    const strongFallback = recent.filter((v) => (v ?? -1) >= cfg.fallbackStrongSignal).length >= 2;

    let streak = before.streak, red = before.red, disagreements = before.redDisagreements;
    let verdict, rule;
    if (before.red && bert == null) [verdict, rule] = ["SCAM", "red_bert_unavailable"];
    else if (before.red) {
      if (bertConfirms) { disagreements = 0; [verdict, rule] = ["SCAM", "red_bert_confirmed"]; }
      else {
        disagreements += 1;
        if (disagreements < cfg.redDisagreementsToClear) [verdict, rule] = ["SCAM", "red_bert_disagrees"];
        else { red = false; disagreements = 0; [verdict, rule] = ["UNSURE", "red_cleared"]; }
      }
    } else if (!quorumMet && bert == null) [verdict, rule] = ["UNSURE", "quorum"];
    else if (warmingUp && !overrideHit) { streak = 0; [verdict, rule] = ["SAFE", "warmup"]; }
    else if (redCandidate && bertConfirms) [verdict, rule] = ["SCAM", "bert_confirmed"];
    else if (redCandidate && bert == null && bertUnavailable && strongFallback) [verdict, rule] = ["SCAM", "strong_fallback"];
    else if (combinedScore >= cfg.unsureThreshold || redCandidate) { streak = 0; [verdict, rule] = ["UNSURE", "unsure"]; }
    else { streak = 0; [verdict, rule] = ["SAFE", "safe"]; }
    if (verdict === "SCAM") red = true; else { red = false; disagreements = 0; }
    const after = { startedAtMs: startedAt, window, streak, red, redDisagreements: disagreements, callerLines };

    const reasons = verdict === "SAFE" ? [] : this.allReasons(recent, bert);
    if (rule === "quorum") {
      reasons.push({ signal: "quorum", score: answered, text: `Not enough data: only ${answered} of ${SIGNALS.length} checks answered` });
    }
    return [after, { verdict, combined: combinedScore, fast, bert, recent, reasons, warmingUp, quorumMet, callerLines, rule }];
  }

  highs(lines) {
    return SIGNALS.map((_, i) => {
      const vals = lines.map((l) => l[i]).filter((v) => v != null);
      return vals.length ? Math.max(...vals) : null;
    });
  }

  /** Weighted average over the signals that have a score; a missing one shares its weight out. */
  combined(recent) {
    let sum = 0, weight = 0;
    recent.forEach((s, i) => { if (s != null) { sum += this.cfg.weights[i] * s; weight += this.cfg.weights[i]; } });
    return weight > 0 ? sum / weight : 0;
  }

  moneyRule(window) {
    const recent = this.highs(window.slice(-this.cfg.moneyRuleLines));
    const m = SIGNALS.indexOf("money");
    const moneyHigh = (recent[m] ?? -1) >= this.cfg.moneyRule;
    const otherHigh = recent.some((s, i) => i !== m && (s ?? -1) >= this.cfg.moneyRuleOther);
    return moneyHigh && otherHigh;
  }

  /** Signals (and BERT) at or above the reason threshold, strongest first (stable on ties). */
  allReasons(recent, bert) {
    const out = [];
    SIGNALS.forEach((s, i) => {
      const v = recent[i];
      if (v != null && v >= this.cfg.reasonThreshold) out.push({ signal: s, score: v, text: REASON_TEXT[s] });
    });
    if (bert != null && bert >= this.cfg.reasonThreshold) out.push({ signal: "bert", score: bert, text: REASON_TEXT.bert });
    return out.map((r, i) => [r, i]).sort((a, b) => (a[0].score !== b[0].score ? b[0].score - a[0].score : a[1] - b[1])).map((x) => x[0]);
  }
}

// Port of the apps' LocalRules tests (ios-app LocalRulesTests.swift = android LocalRulesTest.kt): every cascade-mode
// case from backend/aggregator/src/policy.rs, same numbers, same expected rule.
import { test } from "node:test";
import assert from "node:assert/strict";
import { LocalRules } from "../js/ondevice/localRules.js";

const start = 1_700_000_000_000;
const all = (u, m, i) => [u, m, i];
const vr = (d) => d.verdict + "/" + d.rule;
const near = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) <= eps, `${a} != ${b}`);
function replay(lines, cfg = {}) {
  const r = new LocalRules(cfg);
  return [r, lines.map(([secs, line]) => r.decide(line, start + secs * 1000, secs * 1000))];
}
function replayBert(lines, cfg = {}) {
  const r = new LocalRules(cfg);
  return lines.map(([secs, line, bert]) => r.decide(line, start + secs * 1000, secs * 1000, bert));
}
function atLine(n, cfg = {}) {
  const r = new LocalRules(cfg);
  for (let i = 0; i < n; i++) r.decide(all(0, 0, 0), start, 30_000);
  return r;
}

test("missing worker shares its weight", () => {
  const d = replay([[20, [0.9, null, 0.6]]])[1];
  near(d[0].combined, 0.75); assert.ok(d[0].quorumMet);
});
test("fewer than two answers is unsure even in warm-up", () => {
  const d = replay([[2, [null, 0.1, null]]])[1];
  assert.equal(vr(d[0]), "UNSURE/quorum");
  assert.ok(!d[0].quorumMet && d[0].warmingUp);
  assert.ok(d[0].reasons.some((r) => r.signal === "quorum"));
});
test("warm-up stays quiet even against the money rule", () => {
  const d = replay([[8, all(0.2, 0.8, 0.6)], [17, all(0.1, 0.1, 0.1)]])[1];
  assert.equal(vr(d[0]), "SAFE/warmup"); assert.ok(d[0].warmingUp && d[0].reasons.length === 0);
  assert.equal(vr(d[1]), "UNSURE/unsure"); assert.ok(!d[1].warmingUp);
});
test("a very high score breaks the warm-up", () => {
  const d = replay([[5, all(0.2, 0.95, 0.6)]])[1];
  assert.equal(vr(d[0]), "UNSURE/unsure"); assert.ok(d[0].warmingUp);
});
test("money rule needs another signal", () => {
  const d = replay([[20, all(0.1, 0.95, 0.1)]])[1];
  assert.notEqual(d[0].rule, "money_rule"); assert.notEqual(d[0].verdict, "SCAM");
});
test("money rule default span is the whole window", () => {
  const d = replay([[20, all(0.4, 1, 0)], [25, all(0.4, 1, 0)], [30, all(0.3, 0.3, 0)], [35, all(0.5, 0.3, 0)]])[1];
  assert.equal(vr(d[3]), "UNSURE/unsure");
});
test("money rule span two ignores unrelated lines", () => {
  const d = replay([[20, all(0.4, 1, 0)], [25, all(0.4, 1, 0)], [30, all(0.3, 0.3, 0)], [35, all(0.5, 0.3, 0)]], { moneyRuleLines: 2 })[1];
  assert.ok(d.every((x) => x.rule !== "money_rule" && x.verdict !== "SCAM"));
  near(d[3].recent[1], 1);
});
test("money rule span two still catches money next to the claim", () => {
  const cfg = { moneyRuleLines: 2 };
  assert.equal(vr(replay([[20, all(0.2, 0, 0)], [25, all(0.54, 1, 0)]], cfg)[1][1]), "UNSURE/unsure");
  assert.equal(vr(replay([[20, all(0.1, 0.1, 1)], [25, all(0.1, 0.71, 0)]], cfg)[1][1]), "UNSURE/unsure");
  const d = replay([[20, all(0.1, 0.1, 1)], [25, all(0.1, 0.1, 0.1)], [30, all(0.1, 0.71, 0)]], cfg)[1];
  assert.notEqual(d[2].rule, "money_rule");
});
test("money rule span one is same line only", () => {
  const cfg = { moneyRuleLines: 1 };
  assert.notEqual(replay([[20, all(0.1, 0.1, 1)], [25, all(0.1, 0.9, 0)]], cfg)[1][1].rule, "money_rule");
  assert.equal(vr(replay([[20, all(0.1, 0.9, 0.8)]], cfg)[1][0]), "SCAM/strong_fallback");
});
test("one high sentence is not red", () => {
  const d = replay([[20, all(0.65, 0.6, 0.65)], [25, all(0.1, 0.1, 0.1)]])[1];
  assert.equal(vr(d[0]), "UNSURE/unsure"); assert.equal(vr(d[1]), "UNSURE/unsure");
});
test("no context cannot authorize fallback red", () => {
  assert.equal(vr(new LocalRules().decide(all(0.9, 0.9, 0.1), start + 20_000, 20_000, null, "no_context")), "UNSURE/unsure");
  assert.equal(new LocalRules().decide(all(0.9, 0.9, 0.1), start + 20_000, 20_000, null, "skipped").verdict, "UNSURE");
  assert.equal(new LocalRules().decide(all(0.9, 0.9, 0.1), start + 20_000, 20_000, null, "timeout").verdict, "SCAM");
});
test("a low update resets the streak", () => {
  const d = replay([[20, all(0.65, 0.6, 0.65)], [25, all(0.1, 0.1, 0.1)], [30, all(0.65, 0.6, 0.65)]], { windowLines: 1 })[1];
  assert.equal(d[0].rule, "unsure"); assert.equal(vr(d[1]), "SAFE/safe"); assert.equal(d[2].rule, "unsure");
});
test("strong fallback can turn red without BERT", () => {
  const [rules, d] = replay([[20, all(0.9, 0.9, 0.9)], [25, all(0, 0, 0)], [99, [null, null, null]]]);
  assert.equal(d[0].verdict, "SCAM");
  assert.equal(vr(d[1]), "SCAM/red_bert_unavailable"); assert.equal(vr(d[2]), "SCAM/red_bert_unavailable");
  assert.ok(rules.red);
});
test("unsure band", () => assert.equal(vr(replay([[20, all(0.5, 0.5, 0.5)]])[1][0]), "UNSURE/unsure"));
test("window forgets old lines", () => {
  const lines = [[20, all(0.9, 0.2, 0.2)], ...Array.from({ length: 6 }, (_, i) => [25 + i, all(0.1, 0.1, 0.1)])];
  const [rules, d] = replay(lines, { weights: [0.01, 1 / 3, 1 / 3] });
  near(d[5].recent[0], 0.9); near(d[6].recent[0], 0.1); assert.equal(rules.callerLines, 7);
});
test("normal call stays green", () => {
  const [rules, d] = replay(Array.from({ length: 10 }, (_, i) => [i * 4, all(0.35, 0.3, 0.05)]));
  assert.ok(d.every((x) => x.verdict === "SAFE")); assert.ok(!rules.red);
});
test("scripted scam turns red at the right line", () => {
  const d = replay([[0, all(0.1, 0, 0.2)], [6, all(0.2, 0.1, 0.95)], [20, all(0.6, 0.2, 0.3)], [28, all(0.4, 0.95, 0.2)]])[1];
  assert.equal(d[0].verdict, "SAFE"); assert.notEqual(d[1].verdict, "SCAM"); assert.notEqual(d[2].verdict, "SCAM");
  assert.equal(vr(d[3]), "SCAM/strong_fallback");
  assert.deepEqual(d[3].reasons.map((r) => r.signal), ["money", "impersonation", "urgency"]);
  assert.equal(d[3].reasons[0].text, "Asked for your OTP or money");
});
test("phone time wins over the clock", () => {
  const r = new LocalRules();
  assert.ok(r.decide(all(0.1, 0.1, 0.1), start, 3_000).warmingUp);
  assert.ok(!r.decide(all(0.1, 0.1, 0.1), start + 1_000, 40_000).warmingUp);
  const r2 = new LocalRules();
  assert.ok(r2.decide(all(0.1, 0.1, 0.1), start).warmingUp);
  assert.ok(!r2.decide(all(0.1, 0.1, 0.1), start + 16_000).warmingUp);
});
test("BERT is only wanted when the fast vote is unsure or alarmed", () => {
  const t = start + 20_000;
  assert.ok(!new LocalRules().wantsSecondOpinion(all(0.1, 0.1, 0.1), t, 20_000));
  assert.ok(!new LocalRules().wantsSecondOpinion(all(0.5, 0.5, 0.5), start, 3_000));
  assert.ok(new LocalRules().wantsSecondOpinion(all(0.5, 0.5, 0.5), t, 20_000));
  assert.ok(new LocalRules().wantsSecondOpinion(all(0.65, 0.6, 0.65), t, 20_000));
  assert.ok(new LocalRules().wantsSecondOpinion(all(0.2, 0.95, 0.6), t, 20_000));
  assert.ok(new LocalRules().wantsSecondOpinion([null, 0.1, null], t, 20_000));
  const red = new LocalRules();
  red.decide(all(0.9, 0.9, 0.9), t, 20_000);
  assert.ok(red.red); assert.ok(red.wantsSecondOpinion(all(0.9, 0.9, 0.9), t, 20_000));
});
test("asking changes nothing when BERT does not answer", () => {
  const lines = [[20, all(0.65, 0.6, 0.65)], [25, all(0.1, 0.1, 0.1)]];
  const without = replay(lines)[1];
  assert.deepEqual(without, replayBert(lines.map(([s, l]) => [s, l, null])));
  assert.equal(without[0].fast, without[0].combined);
});
test("final score is the average of fast vote and BERT", () => {
  const d = replayBert([[20, all(0.5, 0.5, 0.5), 0.9]]);
  near(d[0].fast, 0.5); near(d[0].combined, 0.7);
  assert.equal(vr(d[0]), "SCAM/bert_confirmed"); assert.equal(d[0].bert, 0.9); assert.equal(d[0].reasons[0].signal, "bert");
});
test("a calm second opinion turns unsure into safe", () => {
  const d = replayBert([[20, all(0.5, 0.5, 0.5), 0.1]]);
  assert.equal(vr(d[0]), "SAFE/safe"); assert.equal(d[0].reasons.length, 0);
});
test("BERT scam requires an alarmed worker vote", () => {
  const d = replayBert([[20, all(0.5, 0.5, 0.5), 0.9], [25, all(0.5, 0.5, 0.5), 0.9]]);
  assert.equal(d[0].rule, "bert_confirmed"); assert.equal(vr(d[1]), "SCAM/red_bert_confirmed");
});
test("BERT safe keeps an alarmed worker vote yellow", () => {
  assert.equal(vr(replayBert([[20, all(0.2, 0.95, 0.6), 0]])[0]), "UNSURE/unsure");
  assert.equal(vr(replayBert([[20, all(0.9, 0.9, 0.9), 0.8]])[0]), "SCAM/bert_confirmed");
});
test("two BERT disagreements clear a live red alert", () => {
  const d = replayBert([[20, all(0.9, 0.9, 0.9), 0.8], [25, all(0.1, 0.1, 0.1), 0.1], [30, all(0.1, 0.1, 0.1), 0.1]]);
  assert.equal(d[0].verdict, "SCAM"); assert.equal(d[1].rule, "red_bert_disagrees"); assert.equal(vr(d[2]), "UNSURE/red_cleared");
});
test("BERT stands in when too few checks answer", () => {
  const d = replayBert([[20, [null, 0.1, null], 0.8], [25, [null, null, null], 0.8]]);
  assert.ok(!d[0].quorumMet); near(d[0].combined, 0.8);
  assert.equal(vr(d[0]), "SCAM/bert_confirmed"); assert.equal(vr(d[1]), "SCAM/red_bert_confirmed");
  assert.ok(!d[0].reasons.some((r) => r.signal === "quorum"));
  assert.equal(vr(replayBert([[20, [null, 0.1, null], 0.1]])[0]), "SAFE/safe");
});
test("warm-up still wins unless BERT is very sure", () => {
  assert.equal(vr(replayBert([[3, [null, 0.1, null], 0.5]])[0]), "SAFE/warmup");
  assert.notEqual(replayBert([[3, [null, 0.1, null], 0.95]])[0].rule, "warmup");
});
test("BERT weight is configurable", () => near(replayBert([[20, all(0.5, 0.5, 0.5), 0.9]], { bertWeight: 0.25 })[0].combined, 0.6));
test("spot check asks on every 5th line of a quiet call", () => {
  const asked = Array.from({ length: 12 }, (_, n) => n).filter((n) => atLine(n).wantsSecondOpinion(all(0, 0, 0), start + 30_000, 30_000)).map((n) => n + 1);
  assert.deepEqual(asked, [5, 10]);
});
test("spot check runs during the warm-up and can break it", () => {
  const r = new LocalRules();
  for (let i = 0; i < 4; i++) r.decide(all(0, 0, 0), start, 1_000);
  assert.ok(r.wantsSecondOpinion(all(0, 0, 0), start + 5_000, 5_000));
  const d = r.decide(all(0, 0, 0), start + 5_000, 5_000, 0.95);
  assert.ok(d.warmingUp); assert.notEqual(d.rule, "warmup");
});
test("spot check can be switched off", () => assert.ok(!atLine(4, { bertSpotEvery: 0 }).wantsSecondOpinion(all(0, 0, 0), start + 30_000, 30_000)));
test("a low spot check on a safe line stays safe", () => assert.equal(vr(atLine(4).decide(all(0.1, 0.1, 0.1), start + 30_000, 30_000, 0.02)), "SAFE/safe"));
test("peek changes nothing", () => {
  const r = new LocalRules();
  for (let i = 0; i < 3; i++) assert.equal(r.peek(all(0.9, 0.9, 0.9), start + 20_000, 20_000).rule, "strong_fallback");
  assert.ok(!r.red); assert.equal(r.callerLines, 0);
});
test("constants", () => {
  const c = new LocalRules().cfg;
  assert.equal(c.warmupMs, 15_000); assert.equal(c.warmupOverride, 0.9); assert.equal(c.moneyRule, 0.7); assert.equal(c.moneyRuleOther, 0.5);
  assert.equal(c.minQuorum, 2); assert.equal(c.bertScamThreshold, 0.6); assert.equal(c.fallbackStrongSignal, 0.8); assert.equal(c.redDisagreementsToClear, 2);
});

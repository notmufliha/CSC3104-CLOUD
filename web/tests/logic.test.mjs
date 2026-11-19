// Masking, PIN, onboarding / app style rules, weekly summary, quiz, risk words and the Developer view's rules, with
// the same cases as the apps' tests.
import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { ROOT, readJson, asset } from "./helpers.mjs";
import { mask } from "../js/logic/mask.js";
import * as Pin from "../js/logic/pin.js";
import * as Onb from "../js/logic/onboarding.js";
import { weeklyText, weekLabel, Quiz, isTestCall, reportSummary, afterCardShows } from "../js/logic/features.js";
import { level, codeFor, warningKind, summaryLevelOf, FinalReview, historyVerdict } from "../js/logic/risk.js";
import { DevUnlock, Playback, maskBody, connectionStatus, overlayVisible, DevFormat } from "../js/logic/developer.js";
import { compute, percent, riskiestSeq, sampleHistory, whatHappened, LABELS } from "../js/logic/dashboard.js";
import { OnDeviceCall } from "../js/ondevice/onDeviceCall.js";
import { OnDeviceScorer } from "../js/ondevice/linearModel.js";

// ---------------------------------------------------------------- masking

test("masks like the review service (mask_cases.json)", () => {
  const cases = readJson(join(ROOT, "backend/review/mask_cases.json")).cases;
  assert.ok(cases.length >= 10);
  for (const c of cases) assert.equal(mask(c.text), c.masked, c.text);
});

// ---------------------------------------------------------------- PIN

test("PIN: only four digits", () => {
  assert.ok(Pin.isValid("0000")); assert.ok(Pin.isValid("2468"));
  for (const p of ["", "123", "12345", "12a4", "١٢٣٤", " 1234"]) assert.ok(!Pin.isValid(p), p);
});
test("PIN: the same hash as both apps (PBKDF2-HMAC-SHA256, 20 000 rounds)", async () => {
  const salt = Pin.unhex("000102030405060708090a0b0c0d0e0f");
  const want = "874306222d2ae5883abfc71a9ee1618098b7a33f9504cea3e2213739c89d5275";
  assert.equal(Pin.hex(await Pin.hash("2468", salt)), want);
  // the pure-JS fallback (plain http:// pages have no crypto.subtle) gives the same bytes
  assert.equal(Pin.hex(Pin.pbkdf2Sha256(new TextEncoder().encode("2468"), salt, Pin.ITERATIONS, 32)), want);
  assert.ok(await Pin.matches("2468", salt, Pin.unhex(want)));
  assert.ok(!(await Pin.matches("2469", salt, Pin.unhex(want))));
  assert.ok(!(await Pin.matches("12", salt, Pin.unhex(want))));
});
test("SHA-256 known answers", () => {
  assert.equal(Pin.sha256Hex(new Uint8Array()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  assert.equal(Pin.sha256Hex(new TextEncoder().encode("abc")), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
});

// ---------------------------------------------------------------- onboarding and modes

class Store {
  constructor() { Object.assign(this, { privacyLevel: "cloud", saveRiskiestSentence: false, helpImproveDetection: false, onboardingCompleted: null,
    onboardingVersion: 0, textSize: "normal", readAloud: false, textSizeChosen: false, readAloudChosen: false, simpleMode: null,
    appStyleTipShown: false, familyName: "", familyPhone: "" }); }
}
test("first launch shows the setup; an old install never does; a reset does", () => {
  assert.equal(Onb.atLaunch(null, false, false), "showSetup");
  assert.equal(Onb.atLaunch(null, true, false), "existingUser");
  assert.equal(Onb.atLaunch(null, false, true), "existingUser");
  assert.equal(Onb.atLaunch(true, false, false), "done");
  assert.equal(Onb.atLaunch(false, true, true), "showSetup");
});
test("the screens for each answer to 'Who will use this phone?'", () => {
  const d = Onb.defaults();
  assert.deepEqual(Onb.steps(d), ["who"]);
  assert.deepEqual(Onb.steps(Onb.choose(d, "standard")), ["who", "welcome", "privacy", "data", "done"]);
  assert.deepEqual(Onb.steps(Onb.choose(d, "simple")), ["who", "welcome", "recommended"]);
  assert.deepEqual(Onb.steps({ ...d, path: "custom" }), ["who", "welcome", "recommended", "privacy", "data", "done"]);
  assert.deepEqual(Onb.steps(Onb.choose(d, "helper")), ["who", "helper", "family", "pin", "handover"]);
  assert.equal(Onb.next("family", Onb.choose(d, "helper")), "pin");
  assert.equal(Onb.previous("who", d), null);
  assert.deepEqual(Onb.progress("family", Onb.choose(d, "helper")), [3, 5]);
});
test("choosing a style sets its text size and read-aloud unless the user chose them", () => {
  const s = Onb.choose(Onb.defaults(), "simple");
  assert.equal(s.textSize, "large"); assert.equal(s.readAloud, true);
  const kept = Onb.choose({ ...Onb.defaults(), textSize: "xlarge", textSizeChosen: true }, "standard");
  assert.equal(kept.textSize, "xlarge"); assert.equal(kept.readAloud, false);
});
test("Skip on the first screen = Standard with recommended settings", () => {
  const st = new Store();
  Onb.apply(Onb.defaults(), st);
  assert.equal(st.simpleMode, false); assert.equal(st.privacyLevel, "cloud"); assert.equal(st.onboardingCompleted, true);
  assert.equal(st.onboardingVersion, 2); assert.equal(st.appStyleTipShown, true);
});
test("helper setup saves Simple mode and the trimmed contact", () => {
  const st = new Store();
  Onb.apply({ ...Onb.choose(Onb.defaults(), "helper"), familyName: " Mei Ling ", familyPhone: " 9123 4567 " }, st);
  assert.equal(st.simpleMode, true); assert.equal(st.familyName, "Mei Ling"); assert.equal(st.familyPhone, "9123 4567");
  assert.equal(st.textSize, "large"); assert.equal(st.readAloud, true);
});
test("recommended settings", () => {
  const r = Onb.recommended({ ...Onb.defaults(), privacy: "on_device", saveSentence: true });
  assert.equal(r.privacy, "cloud"); assert.equal(r.saveSentence, false); assert.equal(r.readAloud, true); assert.equal(r.path, "simple");
});
test("app style switch keeps a chosen text size; leaving Simple needs the PIN when set", () => {
  const st = new Store();
  Onb.AppStyle.switchTo(true, st);
  assert.equal(st.textSize, "large"); assert.equal(st.readAloud, true);
  st.textSizeChosen = true; st.textSize = "xlarge";
  Onb.AppStyle.switchTo(false, st);
  assert.equal(st.textSize, "xlarge"); assert.equal(st.readAloud, false);
  assert.ok(Onb.AppStyle.needsPin(true, false, true));
  assert.ok(!Onb.AppStyle.needsPin(true, false, false));
  assert.ok(!Onb.AppStyle.needsPin(false, true, true));
  assert.ok(Onb.AppStyle.showTip(false, false)); assert.ok(!Onb.AppStyle.showTip(true, false));
});
test("tour: 3 steps in Simple mode, 7 in the full app", () => {
  assert.equal(Onb.Tour.steps(true).length, 3); assert.equal(Onb.Tour.steps(false).length, 7);
  assert.ok(Onb.Tour.isLast(2, true)); assert.ok(!Onb.Tour.isLast(2, false));
});

// ---------------------------------------------------------------- weekly summary (the apps' cases)

const at = (d, h = 12, month = 11) => new Date(2026, month - 1, d, h).getTime();
const now = at(18, 20);
const call = (endedAt, finalVerdict, reasonCode) => ({ endedAt, finalVerdict, reasonCode });
test("weekly: counts, reasons and the date range", () => {
  const calls = [call(at(18), "SCAM", "money"), call(at(17), "SCAM", "impersonation"), call(at(16), "SCAM", "money"),
    call(at(15), "UNSURE", "urgency"), call(at(14), "SAFE", "none"), call(at(13), "SAFE", "none"), call(at(12, 0), "SAFE", "none"),
    call(at(12), "NOT CHECKED", "server_not_reached"), call(at(11), "SCAM", "money")];
  assert.equal(weeklyText(calls, now), "ScamGuard weekly summary (12–18 Nov): 7 calls checked. 3 scam calls warned (asked for money or a code; " +
    "pretended to be an official). 1 'be careful'. Stay safe! Call 1799 if unsure.");
});
test("weekly: singulars, no scams, empty week", () => {
  assert.equal(weeklyText([call(at(16), "SAFE", "none")], now), "ScamGuard weekly summary (12–18 Nov): 1 call checked. No scam calls. Stay safe! Call 1799 if unsure.");
  assert.equal(weeklyText([call(at(16), "SCAM", "urgency"), call(at(15), "UNSURE", "money")], now),
    "ScamGuard weekly summary (12–18 Nov): 2 calls checked. 1 scam call warned (rushed you). 1 'be careful'. Stay safe! Call 1799 if unsure.");
  assert.equal(weeklyText([], now), "ScamGuard weekly summary (12–18 Nov): no calls checked this week. Stay safe! Call 1799 if unsure.");
});
test("weekly: date range across months and years", () => {
  assert.equal(weekLabel(new Date(2026, 9, 28), new Date(2026, 10, 3)), "28 Oct – 3 Nov");
  assert.equal(weekLabel(new Date(2025, 11, 29), new Date(2026, 0, 4)), "29 Dec – 4 Jan");
});
test("weekly: no numbers, sentences or links", () => {
  const text = weeklyText([call(at(16), "SCAM", "Call 91234567 at www.scam.com, my OTP is 123456")], now);
  assert.ok(!/https?:\/\/|www\.|\.com|@/.test(text));
  assert.ok(!/\d{3,}/.test(text.replace("12–18 Nov", "").replace("1799", "")));
  assert.ok(text.includes("sounded like a scam"));
});

// ---------------------------------------------------------------- learn

const learn = JSON.parse(asset("learn.json"));
test("quiz: 5 questions, at least 2 of each, never a test script, avoids the last round", () => {
  for (let i = 0; i < 50; i++) {
    const r = Quiz.round(learn);
    assert.equal(r.length, 5);
    assert.ok(r.filter((q) => q.scam).length >= 2 && r.filter((q) => !q.scam).length >= 2);
    assert.ok(r.every((q) => !isTestCall(q.script)));
    const next = Quiz.round(learn, Math.random, new Set(r.map((q) => q.text)));
    assert.ok(next.every((q) => !r.some((x) => x.text === q.text)));
  }
  assert.equal(Quiz.resultLine(5), "quiz_r5"); assert.equal(Quiz.resultLine(4), "quiz_r4"); assert.equal(Quiz.resultLine(3), "quiz_r3");
  assert.equal(Quiz.resultLine(1), "quiz_r_low"); assert.equal(Quiz.best(null, 3), 3); assert.equal(Quiz.best(4, 2), 4);
});
test("test scripts are 90 and up", () => {
  assert.ok(isTestCall("scam_en_90_x.json")); assert.ok(!isTestCall("scam_en_11_bank_otp.json"));
});

// ---------------------------------------------------------------- after the call

test("after-call card: scam always, be careful unless turned off, never safe", () => {
  assert.ok(afterCardShows("red", false)); assert.ok(afterCardShows("yellow", true)); assert.ok(!afterCardShows("yellow", false));
  assert.ok(!afterCardShows("green", true));
});
test("report summary masks lines and leaves them out unless allowed", () => {
  const t = reportSummary(at(19, 14), "Scam", "They asked for your OTP.", ["Call me on 91234567"], true);
  assert.ok(t.includes("[PHONE_NUMBER]") && !t.includes("91234567"));
  assert.ok(!reportSummary(at(19, 14), "Scam", "x", ["Call me on 91234567"], false).includes("Call me"));
});

// ---------------------------------------------------------------- risk words

test("risk levels, reason codes, warning kinds, summary level", () => {
  assert.equal(level("SAFE", "err"), "error"); assert.equal(level("UNSURE", "err"), "yellow"); assert.equal(level(null, null), "listening");
  assert.equal(codeFor("red", { money: 0.9, urgency: 0.95 }, null), "urgency");
  assert.equal(codeFor("red", {}, "Claims to be police, a bank or the government"), "impersonation");
  assert.equal(codeFor("green", {}, null), "none");
  assert.equal(warningKind(["money"], ["Read me your OTP"]), "otp");
  assert.equal(warningKind([], [], "earlier"), "earlier");
  const fr = FinalReview.from({ final_verdict: "SAFE", review: "fallback" });
  assert.equal(summaryLevelOf(fr, "SCAM", null), "red");     // an unreviewed fallback never calms a warning
  assert.equal(summaryLevelOf(FinalReview.from({ final_verdict: "SAFE", review: "bert" }), "UNSURE", null), "green");
  assert.equal(historyVerdict("error"), "NOT CHECKED");
});

// ---------------------------------------------------------------- dashboard

test("dashboard: percent rounds half up; riskiest line; sample history is deterministic", () => {
  assert.equal(percent(1, 8), 13); assert.equal(percent(0, 0), 0);
  assert.equal(riskiestSeq([{ seq: 1, urgency: 0.6 }, { seq: 3, money: 0.6 }, { seq: 5, money: 0.4 }]), 3);
  const nowMs = Date.now(), today = new Date(); today.setHours(0, 0, 0, 0);
  const a = sampleHistory(nowMs, today.getTime()), b = sampleHistory(nowMs, today.getTime());
  assert.deepEqual(a, b); assert.ok(a.calls.length > 30);
  const s = compute(a.calls, a.labels, "month", nowMs);
  assert.equal(s.total, s.safe + s.unsure + s.scam + s.notChecked);
  assert.equal(s.buckets.length, 30);
  assert.ok(whatHappened("SCAM", "SCAM", "Asked for your OTP or money.").startsWith("Likely scam: asked"));
  assert.deepEqual(LABELS.atEnd(true, 1000, 5000, null), [{ label: 1, source: "hung_up_after_alert" }]);
});

// ---------------------------------------------------------------- Developer view

test("developer: 7 taps, countdown from the 4th, playback gaps, masked log", () => {
  let c = 0; const outs = [];
  for (let i = 0; i < 7; i++) { const t = DevUnlock.tap(c, false); c = t.count; outs.push(t.outcome); }
  assert.deepEqual(outs, ["none", "none", "none", "stepsLeft", "stepsLeft", "stepsLeft", "unlocked"]);
  assert.equal(DevUnlock.tap(0, true).outcome, "already");
  assert.equal(Playback.gap(1500, "1x"), 1500); assert.equal(Playback.gap(1500, "4x"), 375); assert.equal(Playback.gap(1500, "instant"), 150);
  const m = maskBody('{"call_id":"x","text":"Call 91234567","seq":3}');
  assert.ok(m.includes("[PHONE_NUMBER]") && !m.includes("91234567") && m.includes('"seq": 3'));
  assert.equal(connectionStatus("on_device", false), "notNeeded"); assert.equal(connectionStatus("cloud", null), "checking");
  assert.ok(!overlayVisible(true, true, true, true));
  assert.ok(DevFormat.table([{ seq: 1, tMs: 1500, urgency: 0.1, place: "cloud", latencyMs: 20 }]).startsWith("seq"));
});

// ---------------------------------------------------------------- a whole call scored in the browser

test("on-device call: a scam script turns red, a normal one stays safe; Hybrid masks what it sends", async () => {
  const scorer = await OnDeviceScorer.load(async (f) => asset("models/" + f));
  const play = (file) => {
    const lines = JSON.parse(asset("scripts/" + file)).filter((l) => l.speaker === "caller");
    let t = 0;
    const c = new OnDeviceCall(scorer, undefined, () => (t += 6000));
    return lines.map((l, i) => c.answer("id", i + 1, l.text).verdict);
  };
  assert.ok(play("scam_en_11_bank_otp.json").includes("SCAM"));
  assert.ok(!play("normal_en_11_clinic_appointment.json").includes("SCAM"));
  let t = 0;
  const c = new OnDeviceCall(scorer, undefined, () => (t += 6000));
  const sent = [];
  const lines = JSON.parse(asset("scripts/scam_en_11_bank_otp.json")).filter((l) => l.speaker === "caller");
  for (const [i, l] of lines.entries()) await c.answerHybrid("id", i + 1, l.text + " Call 91234567.", async (ls) => { sent.push(...ls); return 0.95; });
  assert.ok(sent.length > 0 && sent.every((s) => !s.includes("91234567")));
  assert.ok(!OnDeviceCall.isEnglish("zh", "hello")); assert.ok(!OnDeviceCall.isEnglish(null, "这是警察局请你配合")); assert.ok(OnDeviceCall.isEnglish(null, "hello"));
});

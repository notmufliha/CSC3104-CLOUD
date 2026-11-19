// The browser's on-device checks against the workers' own Python (the same fixture and tolerance as the iOS
// OnDeviceParityTests): every cleaned text identical, every score within 0.001.
import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { ROOT, readJson, asset } from "./helpers.mjs";
import * as Py from "../js/ondevice/pyregex.js";
import { UrgencyText, MoneyText, ImpersonationText } from "../js/ondevice/textCleaning.js";
import { OnDeviceScorer, LinearTextModel, hash32, slot } from "../js/ondevice/linearModel.js";
import { sha256Hex } from "../js/logic/pin.js";

const TOL = 0.001;
const cases = readJson(join(ROOT, "android-app/app/src/androidTest/assets/parity/parity_cases.json")).cases;
const scorer = await OnDeviceScorer.load(async (f) => asset("models/" + f));

function check(signal, clean, score) {
  let worst = 0, worstText = "";
  const failures = [];
  for (const c of cases) {
    const want = c[signal];
    const got = clean(c.text);
    if (got !== want.clean) failures.push(`clean "${c.text}"\n   python: ${want.clean}\n   js:     ${got}`);
    const s = score(c.text);
    const d = Math.abs(s - want.score);
    if (d > worst) { worst = d; worstText = c.text; }
    if (d > TOL) failures.push(`score "${c.text}": python ${want.score}, js ${s}`);
  }
  console.log(`PARITY ${signal}: ${cases.length} sentences, largest difference ${worst.toFixed(7)} ("${worstText.slice(0, 60)}")`);
  assert.equal(failures.length, 0, `${signal}: ${failures.length} mismatches\n` + failures.slice(0, 10).join("\n"));
}

test("at least 300 sentences", () => assert.ok(cases.length >= 300));
test("urgency matches Python", () => check("urgency", (t) => UrgencyText.normalize(t), (t) => scorer.urgency(t)));
test("money matches Python", () => check("money", (t) => MoneyText.clean(t, scorer.moneyBrands), (t) => scorer.money(t)));
test("impersonation matches Python", () => {
  const prep = new ImpersonationText(scorer.impersonationModel.orgTypes);
  check("impersonation", (t) => prep.prep(t), (t) => scorer.impersonation(t));
});

test("bundled models match the manifest", () => {
  const manifest = JSON.parse(asset("models/manifest.json"));
  for (const m of Object.values(manifest.models)) {
    assert.equal(sha256Hex(new TextEncoder().encode(asset("models/" + m.file))), m.sha256, m.file);
  }
  assert.equal(scorer.urgencyModel.version, "v4");
  assert.equal(scorer.moneyModel.version, "money_en_v2c");
  assert.equal(scorer.impersonationModel.version, "impersonation_en_v5");
  assert.ok(scorer.moneyModel.maskBrands);
  assert.ok(!scorer.urgencyModel.isHashing);
});

test("Python string helpers", () => {
  assert.deepEqual(Py.split("  a\tb\u000bc\u001cd  "), ["a", "b", "c", "d"]);
  assert.deepEqual(Py.splitLines("a\r\nb c\n"), ["a", "b", "c"]);
  assert.equal(Py.strip("\u001f x \u0085"), "x");
  assert.deepEqual(Py.findAll(Py.re(String.raw`\b\w\w+\b`), "café 东京 x ab"), ["café", "东京", "ab"]);
  assert.equal(Py.sub(Py.re(String.raw`\s+`), "a\u000b\u000bb", " "), "a b");
  assert.deepEqual(Py.splitRx(Py.re(String.raw`(?<=[.!?])\s+`), "Hi. You! Ok"), ["Hi.", "You!", "Ok"]);
});

// Values printed by scikit-learn 1.4.2 (the apps' Murmur3 tests): hash and abs(h) % 2**18.
const MURMUR = [["now", 1153501715, 68115], ["act now", 576793883, 77083], ["arrested", 1873934942, 129630],
  ["été", 865297935, 222735], [" ab", -760043134, 87678], [" pol", 122369946, 210842], ["police officer", -1029493358, 53870]];
test("murmur3 matches scikit-learn", () => {
  for (const [s, h, sl] of MURMUR) {
    assert.equal(hash32(new TextEncoder().encode(s)), h, s);
    assert.equal(slot(s, 1 << 18), sl, s);
  }
});

test("a format-2 (hashing) model scores like the formula", () => {
  const n = 1 << 18;
  const coef = new Float32Array(n);
  const sNow = slot("now", n), sAct = slot("act", n), sActNow = slot("act now", n);
  coef[sNow] = 1.5; coef[sAct] = 0.5; coef[sActNow] = 2.0;
  const b64 = Buffer.from(coef.buffer).toString("base64");
  const m = LinearTextModel.load({ format: 2, name: "urgency_en", version: "v3", threshold: 0.5, intercept: -1.0, calibration: { a: 2.0, b: 0.1 },
    vectorizers: [{ analyzer: "word", ngram_range: [1, 2], lowercase: true, token_pattern: String.raw`(?u)\b\w\w+\b`, stop_words: [],
      sublinear_tf: true, n_features: n, idf_default: 1.0, idf_index: [sNow], idf_value: [3.0], coef_b64: b64 }] });
  assert.ok(m.isHashing);
  const wNow = (Math.log(2) + 1) * 3, norm = Math.sqrt(wNow * wNow + 3);
  const z = -1 + (wNow * 1.5 + 0.5 + 2.0) / norm;
  assert.ok(Math.abs(m.decision("Act NOW now") - z) < 1e-6);
  assert.ok(Math.abs(m.proba("Act NOW now") - 1 / (1 + Math.exp(-(2 * z + 0.1)))) < 1e-9);
  assert.ok(Math.abs(m.proba("nothing here") - 1 / (1 + Math.exp(-(2 * -1 + 0.1)))) < 1e-9);
});

test("a broken model file is refused", () => {
  assert.throws(() => LinearTextModel.load('{"format":3,"name":"x","version":"v","threshold":0.5,"vectorizers":[],"intercept":0}'));
  assert.throws(() => LinearTextModel.load("not json"));
});

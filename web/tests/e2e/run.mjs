// End-to-end tests in Chromium against the running stack (docker compose up -d; the edge serves web/ and /api/).
//   node --test tests/e2e/run.mjs            (BASE=http://127.0.0.1:8088/ by default)
// Each test starts from a clean browser (no history, no settings).
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright-core";
import { existsSync } from "node:fs";

const BASE = process.env.BASE || "http://127.0.0.1:8088/";
const CHROME = ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/usr/bin/google-chrome", "/usr/bin/chromium"].find(existsSync);
let browser;
before(async () => { browser = await chromium.launch(CHROME ? { executablePath: CHROME } : {}); });
after(async () => { await browser?.close(); });

async function open(query = "", { viewport = { width: 1200, height: 1000 }, isMobile = false } = {}) {
  const ctx = await browser.newContext({ viewport, isMobile, hasTouch: isMobile });
  const page = await ctx.newPage();
  page.errors = [];
  page.on("pageerror", (e) => page.errors.push(e.message));
  page.apiCalls = [];
  page.on("request", (r) => { if (/\/api\//.test(r.url())) page.apiCalls.push(r.url().replace(/^.*\/api\//, "")); });
  await page.goto(BASE + (query ? "?" + query : ""));
  await page.waitForSelector("#app");
  return page;
}
const id = (page, x) => page.locator(`[data-id="${x}"]`).first();
const vm = (page, fn) => page.evaluate(fn);
const prefs = (page) => page.evaluate(() => Object.fromEntries(Object.keys(localStorage).filter((k) => k.startsWith("scamguard.")).map((k) => [k.slice(10), JSON.parse(localStorage.getItem(k))])));

// ---------------------------------------------------------------- setup paths

test("setup: Me -> Standard mode, 5 screens, then Home", async () => {
  const p = await open();
  await p.waitForSelector('[data-screen="setup-who"]');
  await id(p, "setupNext").click();                                   // nothing picked: says what to do
  await p.getByRole("alert").waitFor();
  await id(p, "who_me").click(); await id(p, "setupNext").click();
  await p.waitForSelector('[data-screen="setup-welcome"]'); await id(p, "setupNext").click();
  await p.waitForSelector('[data-screen="setup-privacy"]'); await id(p, "privacy_hybrid").click(); await id(p, "setupNext").click();
  await p.waitForSelector('[data-screen="setup-data"]'); await id(p, "setupNext").click();
  await p.waitForSelector('[data-screen="setup-done"]'); await id(p, "setupNoTour").click();
  await p.waitForSelector('[data-screen="home"]');
  const s = await prefs(p);
  assert.equal(s.simple_mode, false); assert.equal(s.privacy_level, "hybrid"); assert.equal(s.onboarding_completed, true);
  assert.deepEqual(p.errors, []);
  await p.context().close();
});

test("setup: Me, but simpler -> recommended settings, the 3-step tour, Simple Home", async () => {
  const p = await open();
  await id(p, "who_simpler").click(); await id(p, "setupNext").click();
  await id(p, "setupNext").click();                                   // welcome
  await id(p, "setupRecommended").click();
  await p.waitForSelector('[data-id="tourOverlay"]');
  for (let i = 0; i < 3; i++) await id(p, "tourNext").click();
  await p.waitForSelector('[data-screen="simpleHome"]');
  const s = await prefs(p);
  assert.equal(s.simple_mode, true); assert.equal(s.text_size, "large"); assert.equal(s.read_aloud, true);
  assert.deepEqual(p.errors, []);
  await p.context().close();
});

test("setup: an older family member -> contact, PIN, hand over; the PIN is not added to the phone number", async () => {
  const p = await open();
  await id(p, "who_family").click(); await id(p, "setupNext").click();
  await p.waitForSelector('[data-screen="setup-helper"]'); await id(p, "text_xlarge").click(); await id(p, "setupNext").click();
  await p.waitForSelector('[data-screen="setup-family"]');
  await id(p, "familyName").fill("Mei Ling"); await id(p, "familyPhone").fill("98765432");
  await id(p, "setupFamilyNext").click();
  await p.waitForSelector('[data-screen="setup-pin"]');
  await p.keyboard.type("1234");                                      // typed with nothing focused: must go nowhere
  await id(p, "pinNew").fill("2468"); await id(p, "pinAgain").fill("2468");
  await id(p, "setupNext").click();
  await p.waitForSelector('[data-screen="setup-handover"]');
  assert.match(await p.locator("h1").first().innerText(), /Mei Ling/);
  await id(p, "setupNoTour").click();
  await p.waitForSelector('[data-screen="simpleHome"]');
  const s = await prefs(p);
  assert.equal(s.family_phone, "98765432"); assert.equal(s.family_name, "Mei Ling"); assert.equal(s.text_size, "xlarge");
  assert.equal(typeof s.pin, "string"); assert.equal(s.pin.length, 96); assert.ok(!s.pin.includes("2468"));
  // "For family" now asks for the PIN; the right one opens it
  await id(p, "tab.dashboard").click();
  await p.waitForSelector('[data-id="pinGate"]');
  await id(p, "pinEnter").fill("1111"); await id(p, "pinUnlock").click(); await p.waitForSelector('[role="alert"]');
  await id(p, "pinEnter").fill("2468"); await id(p, "pinUnlock").click();
  await p.waitForSelector('[data-screen="dashboard"]');
  assert.deepEqual(p.errors, []);
  await p.context().close();
});

// ---------------------------------------------------------------- calls (fast playback through the Developer view)

const FAST = "demoSkipSetup=1&demoDev=1&demoSpeed=4x";

async function playToEnd(p, maxMs = 90_000) {
  await p.waitForSelector('[data-screen="summary"]', { timeout: maxMs });
}

test("Cloud: a scam call turns red, the warning hangs up, the summary shows 'What to do now'", async () => {
  const p = await open(`${FAST}&demoLevel=cloud&demoFamily=${encodeURIComponent("Mei Ling|91234567")}&demoScript=scam_en_11_bank_otp.json`);
  await p.waitForSelector(".warning", { timeout: 60_000 });
  assert.match(await p.locator(".warning h1").innerText(), /This may be a scam/);
  await id(p, "warnHangUp").click();
  await playToEnd(p);
  await p.waitForSelector('[data-id="afterCallCard"]', { timeout: 10_000 });
  const calls = await vm(p, () => scamguard.store.calls().map((c) => [c.finalVerdict, c.privacyLevel]));
  assert.deepEqual(calls[0], ["SCAM", "cloud"]);
  assert.ok(p.apiCalls.includes("v1/utterance") && p.apiCalls.includes("v1/call/end"));
  assert.deepEqual(p.errors, []);
  await p.context().close();
});

test("Cloud: a normal call stays Safe", async () => {
  const p = await open(`${FAST}&demoLevel=cloud&demoScript=normal_en_11_clinic_appointment.json`);
  await playToEnd(p);
  await p.waitForFunction(() => scamguard.store.calls().length === 1, null, { timeout: 10_000 });
  assert.equal(await vm(p, () => scamguard.store.calls()[0].finalVerdict), "SAFE");
  assert.equal(await p.locator(".warning").count(), 0);
  await p.context().close();
});

test("Hybrid: scored in the browser; only masked lines go to /v1/verify", async () => {
  const p = await open(`${FAST}&demoLevel=hybrid&demoScript=scam_en_11_bank_otp.json`);
  await p.waitForSelector(".warning", { timeout: 60_000 });
  await id(p, "warnClose").click();
  await playToEnd(p);
  await p.waitForFunction(() => scamguard.store.calls().length === 1, null, { timeout: 10_000 });
  const row = await vm(p, () => scamguard.store.calls()[0]);
  assert.equal(row.privacyLevel, "hybrid"); assert.equal(row.finalVerdict, "SCAM");
  assert.ok(!p.apiCalls.includes("v1/utterance"), "no line goes to the cloud's scorer");
  assert.ok(!p.apiCalls.includes("v1/call/end"), "no end message at level 2");
  const verify = await vm(p, () => scamguard.devLog.entries.filter((e) => e.path.endsWith("/v1/verify")).map((e) => e.request));
  for (const body of verify) assert.ok(!/\b\d{4,}\b/.test(body), "numbers are masked: " + body);
  assert.deepEqual(p.errors, []);
  await p.context().close();
});

test("On-device: scored in the browser, nothing sent during the call", async () => {
  const p = await open(`${FAST}&demoLevel=on_device&demoScript=scam_en_11_bank_otp.json`);
  const before = p.apiCalls.length;
  await p.waitForSelector(".warning", { timeout: 60_000 });
  await id(p, "warnHangUp").click();
  await playToEnd(p);
  await p.waitForFunction(() => scamguard.store.calls().length === 1, null, { timeout: 10_000 });
  assert.equal(await vm(p, () => scamguard.store.calls()[0].privacyLevel), "on_device");
  assert.equal(p.apiCalls.length, before, "no request at level 3: " + p.apiCalls.slice(before).join(", "));
  await p.context().close();
});

// ---------------------------------------------------------------- learn, weekly, developer, reset

test("Learn: a 5-question quiz to the result; the best score is kept", async () => {
  const p = await open("demoSkipSetup=1&demoLearn=list");
  await id(p, "startQuiz").click();
  for (let i = 0; i < 5; i++) { await id(p, "answerScam").click(); await id(p, "quizNext").click(); }
  await p.waitForSelector('[data-id="quizResult"]');
  const best = (await prefs(p)).quiz_best;
  assert.ok(Number.isInteger(best) && best >= 0 && best <= 5);
  assert.match(await id(p, "quizScore").innerText(), /of 5/);
  await p.context().close();
});

test("Weekly summary: counts only; Share hands the note to the browser's share sheet", async () => {
  const ctx = await browser.newContext({ viewport: { width: 1200, height: 1000 } });
  // headless Chrome's own share sheet never answers: stand in for it and keep what it was given
  await ctx.addInitScript(() => { navigator.share = async (d) => { window.__shared = d.text; }; });
  const p = await ctx.newPage();
  await p.goto(BASE + "?demoSkipSetup=1&demoLoadSample=1&demoDashboard=weekly");
  const text = await id(p, "weeklyText").innerText();
  assert.match(text, /^ScamGuard weekly summary \(/);
  assert.ok(!/\d{5,}/.test(text));
  await id(p, "weeklyShare").click();
  await p.waitForFunction(() => window.__shared);
  assert.equal(await p.evaluate(() => window.__shared), text);
  await ctx.close();
});

test("Developer view: 7 taps unlock it; reset to first launch clears everything", async () => {
  const p = await open("demoSkipSetup=1&demoLoadSample=1&demoSettings=help");
  for (let i = 0; i < 7; i++) await id(p, "aboutVersion").click();
  await p.waitForSelector('[data-id="devToast"]');
  assert.match(await id(p, "devToast").innerText(), /unlocked/i);
  await id(p, "navBack").click();
  await id(p, "rowDeveloper").click();
  await p.waitForSelector('[data-screen="developer"]');
  await id(p, "resetApp").click(); await id(p, "sheetYes").click();
  await p.waitForSelector('[data-screen="setup-who"]');
  assert.deepEqual(await prefs(p), {});
  assert.equal(await vm(p, () => scamguard.store.calls().length), 0);
  await p.context().close();
});

// ---------------------------------------------------------------- phone-sized, no frame

test("phone viewport (390x844): no frame, the app fills the screen, no zoom on fields", async () => {
  const p = await open("demoSkipSetup=1&demoSettings=family", { viewport: { width: 390, height: 844 }, isMobile: true });
  assert.equal(await p.locator(".device").count(), 0);
  const box = await p.locator("#app").boundingBox();
  assert.equal(Math.round(box.width), 390); assert.equal(Math.round(box.height), 844);
  const fs = await id(p, "familyPhone").evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
  assert.ok(fs >= 16, "inputs are 16 px or larger: " + fs);
  await p.screenshot({ path: "build/phone-390x844.png" });
  await p.context().close();
});

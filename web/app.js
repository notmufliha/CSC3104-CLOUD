// ScamGuard web app (POST v1/utterance, v1/call/end, v1/verify, v1/report, GET healthz): the phone apps in a
// browser, so the system can be tried without Android Studio or Xcode. All three privacy levels: 1 Cloud (the gateway checks every line), 2 Hybrid and 3 On-device (checked in this page by
// ondevice.js, the apps' own on-device models and rules; Hybrid asks the cloud's BERT only at unsure moments, with
// the lines masked; On-device asks MobileBERT, run here with onnxruntime-web, and makes no request at all). Same gateway calls (POST v1/utterance, v1/call/end, GET healthz), the same screens in
// Standard and Simple mode, and the same wording: strings.json is generated from tools/i18n/strings/*.json together
// with the apps' string tables (tools/i18n/gen_strings.py), and learn.json is the apps' Learn content
// (tools/learn/build_learn.py). Kept in step with android-app ui/CallViewModel.kt, logic/Risk.kt, logic/Warnings.kt,
// logic/AfterCall.kt, logic/Learn.kt, logic/WeeklySummary.kt and logic/Mask.kt.
// The call scripts are the apps' own (android-app/app/src/main/assets/scripts), served at scripts/.
// Everything the phone keeps on the phone (history, settings, family contact, quiz score) stays in this browser
// (localStorage). Nothing new is uploaded.
// Not in the browser: real phone calls and messages (Call / Tell my family open the computer's tel: / sms: handler,
// if any), the weekly reminder notification, the PIN lock, the hidden Developer view.
"use strict";

// ---------------------------------------------------------------- wording (the apps' string table)

let STR = {};
/** The apps' text for a key, with {0} {1} filled in. */
function T(key, ...args) {
  const s = STR[key];
  if (s === undefined) return key;
  return s.replace(/\{(\d+)\}/g, (_, i) => (args[+i] ?? ""));
}

// ---------------------------------------------------------------- constants (as in the apps)

const SIGNALS = ["urgency", "money", "impersonation"];
const SIGNAL_ON = 0.5;                 // Risk.SIGNAL_ON
const DEFAULT_DELAY_MS = 1500;
const FINAL_REVIEW_WAIT_MS = 3000;
const UNSURE_LINE = 0.45, SCAM_LINE = 0.6;   // the aggregator's default thresholds, drawn on the chart
const SCAMSHIELD_URL = "https://www.scamshield.gov.sg/";
const HELPLINE = "1799";
const COLORS = { GREEN: "var(--safe)", YELLOW: "var(--warn)", RED: "var(--scam)", ERROR: "var(--grey)", LISTENING: "var(--grey)" };
/** The word for a level (the apps' riskWord / levelLabel). */
const levelWord = (lvl, live = false) => T({
  LISTENING: "risk_listening", GREEN: live ? "risk_safe_so_far" : "risk_safe", YELLOW: "risk_careful",
  RED: "risk_scam", ERROR: "risk_could_not_check",
}[lvl]);
const ICON = { GREEN: "✓", YELLOW: "!", RED: "✕", ERROR: "?", LISTENING: "…" };
const chipLabel = (s) => T("chip_" + s);
const reasonText = (code) => (STR["reason_" + code] ? T("reason_" + code) : code || "");

// ---------------------------------------------------------------- storage (every access guarded)

const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem("scamguard." + key); return v === null ? fallback : JSON.parse(v); }
    catch { return fallback; }
  },
  set(key, value) { try { localStorage.setItem("scamguard." + key, JSON.stringify(value)); } catch { /* private mode */ } },
};
const simple = () => store.get("simple", false);
const textSize = () => store.get("textSize", simple() ? "large" : "normal");
const family = () => { const p = store.get("familyPhone", ""); return p ? { name: store.get("familyName", "") || p, phone: p } : null; };
const showCareful = () => store.get("showCareful", true);
/** Privacy level (logic/PrivacyLevel.kt ids): "cloud" (default), "hybrid" or "on_device". A change applies from the next call. */
const PRIVACY_LEVELS = ["cloud", "hybrid", "on_device"];
const privacyLevel = () => { const v = store.get("privacyLevel", "cloud"); return PRIVACY_LEVELS.includes(v) ? v : "cloud"; };

/** "api/" (same address as this page) unless Settings saved another. */
function gatewayUrl() { return store.get("gatewayUrl", "api/"); }
function normalize(url) {
  let u = (url || "").trim() || "api/";
  // "192.168.1.23:8080" or "host.example:8443/api" -> add http://; "api/" or "/api/" stay relative to this page
  if (!/^https?:\/\//i.test(u) && /^[\w.-]+(:\d+)(\/|$)|^\d+\.\d+\.\d+\.\d+/.test(u)) u = "http://" + u;
  if (!u.endsWith("/")) u += "/";
  return u;
}

// ---------------------------------------------------------------- risk rules (logic/Risk.kt, logic/Warnings.kt)

function level(verdict, error) {
  if (verdict === "SCAM") return "RED";
  if (verdict === "UNSURE") return "YELLOW";
  if (error) return "ERROR";
  if (verdict === "SAFE") return "GREEN";
  return "LISTENING";
}
const SEVERITY = { LISTENING: 0, GREEN: 1, ERROR: 2, YELLOW: 3, RED: 4 };
const litSignals = (scores) => SIGNALS.filter((s) => (scores?.[s] ?? 0) >= SIGNAL_ON);

/** Warnings.kind: money (OTP when the caller said it), then a fake official, then rushing; else the reason code. */
function warningKind(lit, texts, reasonCode) {
  const all = texts.join(" ").toLowerCase();
  const otp = all.includes("otp") || all.includes("one-time") || all.includes("验证码");
  if (lit.includes("money")) return otp ? "otp" : "money";
  if (lit.includes("impersonation")) return "official";
  if (lit.includes("urgency")) return "rush";
  return { money: otp ? "otp" : "money", impersonation: "official", urgency: "rush", several: "several", earlier: "earlier" }[reasonCode] || "general";
}
const warnMessage = (k) => T("warn_msg_" + k);
const warnDo = (k) => T(k === "otp" ? "warn_do_otp" : k === "money" ? "warn_do_money" : "warn_do_details");
const carefulLine = (k) => T({ otp: "careful_msg_money", money: "careful_msg_money", official: "careful_msg_official", rush: "careful_msg_rush" }[k] || "careful_msg_general");

// Masking (logic/Mask.kt, backend/review/mask.py) is in ondevice.js (OnDevice.Mask): Hybrid uses it too.
const Mask = OnDevice.Mask;

// ---------------------------------------------------------------- on-device checking (levels 2 and 3)

// The apps' model files, served next to this page (ondevice/), loaded once when a level that needs them is picked.
const engine = { scorer: null, scorerLoading: null, bert: null, bertLoading: null, bertStatus: "not loaded" };
function loadScorer() {
  engine.scorerLoading ??= OnDevice.OnDeviceScorer.load(async (f) => {
    const r = await fetch("ondevice/" + f);
    if (!r.ok) throw new Error(`ondevice/${f}: ${r.status}`);
    return r.json();
  }).then((s) => { engine.scorer = s; render(); return s; }).catch((e) => { engine.scorerLoading = null; throw e; });
  return engine.scorerLoading;
}
function loadScript(src) {
  return new Promise((ok, fail) => {
    const el = document.createElement("script");
    el.src = src; el.onload = ok; el.onerror = () => fail(new Error("could not load " + src));
    document.head.appendChild(el);
  });
}
/** Level 3's second opinion: MobileBERT (ondevice/mobilebert/) in onnxruntime-web (vendor/ort/). Resolves to it or null. */
function loadMobileBert() {
  engine.bertLoading ??= (async () => {
    engine.bertStatus = "loading"; render();
    try {
      if (!window.ort) await loadScript("vendor/ort/ort.wasm.min.js");
      ort.env.wasm.wasmPaths = new URL("vendor/ort/", location.href).href;
      const get = async (n, kind) => { const r = await fetch("ondevice/mobilebert/" + n); return r.ok ? r[kind]() : null; };
      const res = await OnDevice.MobileBert.load(ort, (n) => get(n, "text"), (n) => get(n, "arrayBuffer"));
      engine.bert = res.installed || null;
      engine.bertStatus = res.installed ? "ready" : res.reason;
    } catch (e) {
      engine.bert = null; engine.bertStatus = "could not be loaded: " + (e?.message || e);
    }
    render();
    return engine.bert;
  })();
  return engine.bertLoading;
}
/** Start loading what the chosen level needs, so the first call doesn't wait. */
function preload(level = privacyLevel()) {
  if (level === "cloud") return;
  loadScorer().catch(() => render());
  if (level === "on_device") loadMobileBert();
}

/** G.3: POST v1/verify with the masked lines; BERT's score, or null if the server didn't answer within 1.5 s. */
async function verifyInCloud(lines) {
  if (!lines.length) return null;
  try {
    const r = await api("v1/verify", { lines, lang: "en" }, OnDevice.OnDeviceCall.VERIFY_TIMEOUT_MS);
    const score = r.ok ? r.json?.score : null;
    return typeof score === "number" && score >= 0 && score <= 1 ? score : null;
  } catch { return null; }
}

// ---------------------------------------------------------------- gateway

async function api(path, body, timeoutMs = 4000, base = gatewayUrl()) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const resp = await fetch(normalize(base) + path, {
      method: body === undefined ? "GET" : "POST",
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctrl.signal,
    });
    let json = null;
    try { json = await resp.json(); } catch { /* not JSON */ }
    return { ok: resp.ok, status: resp.status, json };
  } finally { clearTimeout(timer); }
}

/** Plain connection status for Home and Settings: null = checking, true / false. */
let connected = null;
async function checkConnection() {
  connected = null; render();
  try { const r = await api("healthz"); connected = r.ok && r.json?.status === "ok"; } catch { connected = false; }
  render();
}

// ---------------------------------------------------------------- scripts and learn content

let scripts = [];
async function loadScripts() {
  try {
    const list = await (await fetch("scripts/")).json();          // nginx autoindex (JSON)
    const names = list.map((f) => f.name).filter((n) => n.endsWith(".json")).sort();
    scripts = (await Promise.all(names.map(async (file) => {
      try {
        const lines = await (await fetch("scripts/" + file)).json();
        const parts = file.replace(/\.json$/, "").split("_");
        const title = parts.slice(3).join(" ");
        return {
          file, kind: (parts[0] || "unknown").toLowerCase(),
          lang: ["en", "zh"].includes(parts[1]) ? parts[1] : null,
          num: parseInt(parts[2], 10),
          title: title ? title[0].toUpperCase() + title.slice(1) : file,
          lines: lines.filter((l) => l.text && l.text.trim()),
        };
      } catch { return null; }
    }))).filter(Boolean);
  } catch { scripts = []; }
}
/** Learning-loop test scripts (90+) are for the developer view in the apps; not offered here. */
const isTestCall = (file) => { const n = parseInt((file || "").split("_")[2], 10); return n >= 90; };
const isCaller = (l) => ["caller", "speaker 1", "speaker1", "speaker_1"].includes((l.speaker || "").trim().toLowerCase());
let lastRandom = null;
function randomScript(kind) {
  const pool = scripts.filter((s) => s.kind === kind && !isTestCall(s.file));
  const others = pool.filter((s) => s.file !== lastRandom);
  const pick = (others.length ? others : pool)[Math.floor(Math.random() * (others.length || pool.length))];
  lastRandom = pick?.file;
  return pick;
}

let learn = null;                // { types, quiz } from learn.json; null while loading
async function loadLearn() {
  try { learn = await (await fetch("learn.json")).json(); } catch { learn = { types: [], quiz: [] }; }
}

// ---------------------------------------------------------------- state

// home | pick | ringing | call | summary | dashboard | detail | weekly | settings | learn | learnType | quiz
let screen = "home";
let call = null;               // the current call (see newCall)
let openCallId = null;
let learnTypeId = null;
let quiz = null;               // { items, i, answer, score, done }
let pickFilter = { kind: null, lang: null };
let sheet = null;              // "block" | "shared" on the summary

function newCall(script) {
  return {
    script, id: crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random(),
    lines: [], verdict: null, combinedScore: null, callScores: {}, error: null, lastOutcomeSeq: 0,
    scamAlert: false, warningOpen: false, points: [], startedAt: 0, timers: [], ended: false,
    reviewing: false, finalReview: null, copied: false, reportResult: null, reportSent: false, sending: false,
    level: "cloud", chain: Promise.resolve(),
  };
}
const callLevel = (c) => level(c.verdict, c.error);
const flaggedTexts = (c) => c.lines.filter((l) => l.flags?.length).map((l) => l.text);
function summaryLevel(c) {
  const fr = c.finalReview;
  const live = callLevel(c);
  const fin = level(fr?.finalVerdict ?? c.verdict, c.error);
  if (fr?.reviewed) return fin;
  return SEVERITY[fin] >= SEVERITY[live] ? fin : live;
}
const callKind = (c) => warningKind(litSignals(c.callScores), flaggedTexts(c), reasonCode(summaryLevel(c), c.callScores, c.finalReview?.reason));

// ---------------------------------------------------------------- call lifecycle (CallViewModel.kt)

function ring(script) { stopTimers(); call = newCall(script); go("ringing"); }

function answer() {
  const c = call;
  c.startedAt = Date.now();
  c.level = privacyLevel();
  go("call");
  if (c.level !== "cloud") return playOnDevice(c);
  let at = 0;
  const sends = [];
  c.script.lines.forEach((line, i) => {
    at += line.delay_ms ?? DEFAULT_DELAY_MS;
    c.timers.push(setTimeout(() => {
      if (c.ended) return;
      const seq = i + 1;
      const caller = isCaller(line);
      c.lines.push({ seq, isCaller: caller, text: line.text, flags: [], pending: caller, failed: false });
      if (caller) sends.push(send(c, seq, line.text));
      render();
      if (i === c.script.lines.length - 1) {
        // let the last answers arrive (up to 5 s), a moment to read the final state, then end
        Promise.race([Promise.allSettled(sends), sleep(5000)]).then(() => sleep(1500)).then(() => endCall(c, "script_finished"));
      }
    }, at));
  });
  if (!c.script.lines.length) endCall(c, "script_finished");
}

async function send(c, seq, text) {
  let r;
  try {
    r = await api("v1/utterance", { call_id: c.id, seq, speaker: "caller", text, ...(c.script.lang ? { lang: c.script.lang } : {}) });
  } catch { return markError(c, seq, "Could not reach the server"); }
  if (c.ended) return;
  if (!r.ok) return markError(c, seq, `Server error ${r.status}`);
  if (!r.json) return markError(c, seq, "Empty answer from server");
  applyAnswer(c, seq, r.json);
}

/**
 * Levels 2 and 3 (CallViewModel.playOnDevice): the same playback, but every caller line is scored in this page,
 * one at a time and in order (the rules need that). Level 3 makes no request at all; level 2 sends the caller's last
 * 5 lines, masked, to v1/verify only at unsure moments or on the spot check, and keeps its own answer if the server
 * is slow or down. English only; other lines show "could not check".
 */
function playOnDevice(c) {
  const hybrid = c.level === "hybrid";
  const models = loadScorer().then((s) => new OnDevice.OnDeviceCall(s)).catch(() => null);
  const bert = hybrid ? Promise.resolve(null) : loadMobileBert();
  let at = 0;
  c.script.lines.forEach((line, i) => {
    at += line.delay_ms ?? DEFAULT_DELAY_MS;
    c.timers.push(setTimeout(() => {
      if (c.ended) return;
      const seq = i + 1;
      const caller = isCaller(line);
      c.lines.push({ seq, isCaller: caller, text: line.text, flags: [], pending: caller, failed: false, note: null });
      render();
      if (caller) c.chain = c.chain.then(() => scoreHere(c, seq, line.text, models, bert, hybrid)).catch(() => markError(c, seq, "Could not check on this device"));
      if (i === c.script.lines.length - 1) {
        // let the last answers arrive (up to 5 s), a moment to read the final state, then end
        Promise.race([c.chain, sleep(5000)]).then(() => sleep(1500)).then(() => endCall(c, "script_finished"));
      }
    }, at));
  });
  if (!c.script.lines.length) endCall(c, "script_finished");
}

async function scoreHere(c, seq, text, models, bertLoading, hybrid) {
  if (c.ended) return;
  const local = await models;
  if (c.ended) return;
  if (!local) return markError(c, seq, "On-device models could not be loaded");
  if (!OnDevice.OnDeviceCall.isEnglish(c.script.lang, text)) return markError(c, seq, "On-device checks are English only");
  const setNote = (key) => { const l = c.lines.find((x) => x.seq === seq); if (l) l.note = key; };
  if (hybrid) {
    const a = await local.answerHybrid(c.id, seq, text, verifyInCloud, Mask);
    if (c.ended) return;
    if (a.second) setNote(a.second.bertScore != null ? "note_cloud_checked" : "note_cloud_unavailable");
    return applyAnswer(c, seq, a.response);
  }
  const mb = await bertLoading;
  if (c.ended) return;
  if (mb) {
    // MobileBERT reads the lines unmasked: they never leave this page
    const a = await local.answerWithSecondOpinion(c.id, seq, text, (x) => x, true, (lines) => mb.score(lines));
    if (c.ended) return;
    if (a.second) setNote(a.second.bertScore != null ? "note_mb_checked" : "note_mb_failed");
    return applyAnswer(c, seq, a.response);
  }
  applyAnswer(c, seq, local.answer(c.id, seq, text));
}

function applyAnswer(c, seq, body) {
  const line = c.lines.find((l) => l.seq === seq);
  if (body.ignored) { if (line) line.pending = false; return render(); }
  if (!["SAFE", "UNSURE", "SCAM"].includes(body.verdict)) return markError(c, seq, "Server gave no verdict");
  const flags = SIGNALS.filter((s) => (body.scores?.[s] ?? 0) >= SIGNAL_ON);
  c.points.push({ seq, t: (Date.now() - c.startedAt) / 1000, combined: body.combined_score,
    urgency: body.scores?.urgency, money: body.scores?.money, impersonation: body.scores?.impersonation });
  if (line) { line.pending = false; line.flags = flags; }
  if (seq >= c.lastOutcomeSeq) {           // answers can arrive out of order; newest wins
    c.lastOutcomeSeq = seq;
    c.verdict = body.verdict;
    c.combinedScore = body.combined_score ?? c.combinedScore;
    c.callScores = body.recent_scores ?? body.call_scores ?? c.callScores;
    c.error = null;
  }
  if (c.verdict === "SCAM" && !c.scamAlert) {
    c.scamAlert = true; c.warningOpen = true;
    try { navigator.vibrate?.(600); } catch { /* not supported */ }
    if (store.get("readAloud", simple())) speak(T("warn_spoken", warnDo(callKind(c))));
  }
  render();
}

function speak(text) {
  try { const u = new SpeechSynthesisUtterance(text); u.lang = "en-SG"; speechSynthesis.cancel(); speechSynthesis.speak(u); } catch { /* no voice */ }
}

function markError(c, seq, msg) {
  const line = c.lines.find((l) => l.seq === seq);
  if (line) { line.pending = false; line.failed = true; }
  if (seq >= c.lastOutcomeSeq) { c.lastOutcomeSeq = seq; c.error = msg; }
  render();
}

function stopTimers() { if (call) { call.timers.forEach(clearTimeout); call.timers = []; } }

/** Leave without saving: the server is told (so it clears the call's memory); no summary, nothing saved. */
function discard() {
  if (call && screen === "call" && !call.ended) {
    call.ended = true; stopTimers();
    // levels 2 and 3: the server keeps no memory of the call, so nothing is sent
    if (call.level === "cloud") sendEnd(call, "user_discarded");
  }
  call = null; go("home");
}

function sendEnd(c, endedBy) {
  return api("v1/call/end", {
    call_id: c.id, ended_by: endedBy, last_seq: Math.max(0, ...c.lines.map((l) => l.seq)),
    caller_lines: c.lines.filter((l) => l.isCaller && l.text.trim()).map((l) => ({ seq: l.seq, text: l.text })),
    ...(c.script.lang ? { lang: c.script.lang } : {}), event: "end",
  }, 5000).then((r) => (r.ok ? r.json : null)).catch(() => null);
}

async function endCall(c, endedBy) {
  if (c.ended || c !== call) return;
  c.ended = true; stopTimers();
  try { speechSynthesis.cancel(); } catch { /* no voice */ }
  c.lines.forEach((l) => { if (l.pending) { l.pending = false; l.failed = true; } });
  c.reviewing = true; c.warningOpen = false;
  go("summary");
  if (c.level !== "cloud") {
    // levels 2 and 3 send no end message (it would carry every caller line, unmasked): the live verdict is final
    c.finalReview = { finalVerdict: null, reason: "", reviewed: false, topWindowSeqs: [], answered: false, onDevice: true };
    c.reviewing = false;
    saveHistory(c);
    if (c === call) render();
    return;
  }
  const resp = await Promise.race([sendEnd(c, endedBy), sleep(FINAL_REVIEW_WAIT_MS).then(() => null)]);
  const verdict = ["SAFE", "UNSURE", "SCAM"].includes(resp?.final_verdict) ? resp.final_verdict : null;
  c.finalReview = resp
    ? { finalVerdict: verdict, reason: resp.reason || "", reviewed: resp.review === "bert" && verdict !== null,
        topWindowSeqs: resp.top_window_seqs || [], answered: true }
    : { finalVerdict: null, reason: "", reviewed: false, topWindowSeqs: [], answered: false };
  c.reviewing = false;
  saveHistory(c);
  if (c === call) render();
}

// ---------------------------------------------------------------- history (in this browser)

const history = () => store.get("calls", []);
const historyVerdict = (lvl) => ({ RED: "SCAM", YELLOW: "UNSURE", GREEN: "SAFE" }[lvl] || "NOT CHECKED");
const SERVER_REASONS = {
  money: "Asked for your OTP or money", impersonation: "Claims to be police, a bank or the government",
  urgency: "Pressuring you to act now", bert: "The conversation as a whole sounds like a scam",
  several: "Several small warning signs together",
};
function reasonCode(lvl, scores, serverReason) {
  if (lvl === "RED" || lvl === "YELLOW") {
    const fromServer = Object.entries(SERVER_REASONS).find(([, t]) => t.toLowerCase() === (serverReason || "").trim().toLowerCase());
    if (fromServer) return fromServer[0];
    const lit = SIGNALS.filter((s) => (scores?.[s] ?? 0) >= SIGNAL_ON).sort((a, b) => scores[b] - scores[a]);
    return lit[0] || "several";
  }
  return { GREEN: "none", ERROR: "server_not_reached" }[lvl] || "no_lines";
}
function saveHistory(c) {
  const lvl = summaryLevel(c);
  const verdict = historyVerdict(lvl);
  let sentence = null;
  if (store.get("saveSentence", false) && (verdict === "SCAM" || verdict === "UNSURE")) {
    const best = c.points.map((p) => [p.seq, Math.max(p.urgency ?? 0, p.money ?? 0, p.impersonation ?? 0)])
      .filter(([, s]) => s >= SIGNAL_ON).sort((a, b) => b[1] - a[1] || b[0] - a[0])[0];
    if (best) sentence = c.lines.find((l) => l.seq === best[0])?.text ?? null;
  }
  const calls = history();
  calls.unshift({
    id: c.id, startedAt: c.startedAt, endedAt: Date.now(), finalVerdict: verdict, liveVerdict: historyVerdict(callLevel(c)),
    reasonCode: reasonCode(lvl, c.callScores, c.finalReview?.reason), callScore: c.combinedScore,
    review: c.finalReview?.reviewed ? "bert" : c.finalReview?.onDevice ? "on_device" : c.finalReview?.answered ? "fallback" : "unavailable",
    privacyLevel: c.level,
    title: c.script.title, riskiestSentence: sentence, points: c.points.map((p) => [p.t, p.combined]),
  });
  store.set("calls", calls.slice(0, 200));
}
const levelOfRow = (r) => level(r.finalVerdict === "NOT CHECKED" ? null : r.finalVerdict, r.finalVerdict === "NOT CHECKED");

// ---------------------------------------------------------------- after the call (logic/AfterCall.kt)

const afterCardShows = (lvl) => lvl === "RED" || (lvl === "YELLOW" && showCareful());
/** "19 Nov 2026, 2:45 PM" (as in the apps). */
function whenText(ms) {
  const d = new Date(ms);
  const mon = d.toLocaleString("en-GB", { month: "short" });
  let h = d.getHours(); const ap = h >= 12 ? "PM" : "AM"; h = h % 12 || 12;
  return `${d.getDate()} ${mon} ${d.getFullYear()}, ${h}:${String(d.getMinutes()).padStart(2, "0")} ${ap}`;
}
/** The text copied before ScamShield opens: masked caller lines only if "Save the riskiest sentence" is on. */
function reportSummary(c) {
  const lvl = summaryLevel(c);
  const out = [T("copy_title"), T("copy_when", whenText(c.startedAt || Date.now())), T("copy_result", levelWord(lvl)),
    T("copy_why", warnMessage(callKind(c)))];
  if (store.get("saveSentence", false)) {
    const masked = [...new Set(flaggedTexts(c).map((t) => Mask(t).trim()).filter(Boolean))];
    if (masked.length) { out.push(T("copy_lines")); masked.forEach((m) => out.push(`- "${m}"`)); }
  }
  out.push(T("copy_footer"));
  return out.join("\n");
}
async function copyText(text) {
  try { await navigator.clipboard.writeText(text); return true; } catch { return false; }
}
// ---------------------------------------------------------------- report to the team (Workstream H.3)
// "This was a scam" / "This was fine" on the summary: with the user's consent, the caller's lines (masked) go to the
// review queue (POST v1/report), where a reviewer labels them for retraining (demo/LEARNING_LOOP.md). Cloud level only,
// as in the apps (CallViewModel.sendReport): at levels 2 and 3 nothing about the call leaves the device.

/** Exactly what a report would send: the caller's lines, masked. Shown on the consent screen first. */
const reportPreview = (c) => c.lines.filter((l) => l.isCaller && l.text.trim()).map((l) => ({ seq: l.seq, text: Mask(l.text) }));
async function sendReport(c, kind) {
  const lines = reportPreview(c);
  if (!lines.length) return [T("report_nothing"), false];
  const text = lines.map((l) => l.text).join(" ");
  const chinese = [...text].filter((ch) => ch >= "一" && ch <= "鿿").length;
  const language = c.script.lang || (chinese * 10 > text.length * 3 ? "zh" : "en");   // the gateway's 30% rule
  try {
    const r = await api("v1/report", { call_id: c.id, kind, app_verdict: c.verdict || "", language, consent: true, lines, consent_version: 2 }, 8000);
    if (r.ok) return [T("report_thanks", r.json?.lines_stored ?? lines.length), true];
    if (r.status === 503) return [T("report_not_set_up"), false];
    return [T("report_failed"), false];
  } catch { return [T("report_failed"), false]; }
}
function consentSheet(c, kind) {
  const lines = reportPreview(c);
  return `<div class="scrim" data-act="consent-no"></div><div class="sheet" role="dialog" aria-modal="true" aria-labelledby="consent-title">
    <h2 id="consent-title">${kind === "scam" ? "Share this scam call?" : "Share this call as a false alarm?"}</h2>
    <p>Helps ScamGuard learn new scams. A team member reads it, labels it, and it may be used to retrain the checks.</p>
    <p class="small">Sent: the caller's ${lines.length} lines below. We try to remove numbers, amounts, links and names,
      but some can slip through, such as a name said in passing or digits read out as words. Check the text; if you see
      anything private, choose Don't share.</p>
    <p class="small">Not sent: your phone number, the caller's number, anything you said.</p>
    <p class="small">Kept: deleted after 30 days, except lines a reviewer approves as training examples. Those stay
      encrypted with no link to you, so they can't be withdrawn later.</p>
    <div class="col">${lines.map((l) => `<div class="card">“${esc(l.text)}”</div>`).join("")}</div>
    <div class="row"><button class="btn grow big" data-act="consent-no">Don't share</button>
      <button class="btn fill grow big" data-act="consent-yes" data-kind="${kind}" ${lines.length ? "" : "disabled"}>${esc(T("btn_share"))}</button></div></div>`;
}

const telHref = (n) => "tel:" + String(n).replace(/[^\d+]/g, "");
const smsHref = (n, body) => "sms:" + String(n).replace(/[^\d+]/g, "") + "?body=" + encodeURIComponent(body);

// ---------------------------------------------------------------- weekly summary (logic/WeeklySummary.kt)

function weekLabel(first, last) {
  const m = (d) => d.toLocaleString("en-GB", { month: "short" });
  return first.getMonth() === last.getMonth() ? `${first.getDate()}–${last.getDate()} ${m(last)}`
    : `${first.getDate()} ${m(first)} – ${last.getDate()} ${m(last)}`;
}
function weeklyText(calls, now = Date.now()) {
  const today = new Date(now); const first = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6);
  const inWeek = calls.filter((c) => c.endedAt >= first.getTime() && c.endedAt <= now && ["SAFE", "UNSURE", "SCAM"].includes(c.finalVerdict));
  const head = T("weekly_head", weekLabel(first, today)), end = T("weekly_end");
  if (!inWeek.length) return `${head} ${T("weekly_none")} ${end}`;
  const ORDER = ["weekly_r_money", "weekly_r_imp", "weekly_r_urgency", "weekly_r_other"];
  const words = (code) => ({ money: "weekly_r_money", impersonation: "weekly_r_imp", urgency: "weekly_r_urgency" }[code] || "weekly_r_other");
  const scams = inWeek.filter((c) => c.finalVerdict === "SCAM");
  const careful = inWeek.filter((c) => c.finalVerdict === "UNSURE").length;
  const parts = [head, inWeek.length === 1 ? T("weekly_calls_one") : T("weekly_calls_many", inWeek.length)];
  if (!scams.length) parts.push(T("weekly_scam_zero"));
  else {
    const counts = {}; scams.forEach((c) => { const k = words(c.reasonCode); counts[k] = (counts[k] || 0) + 1; });
    const reasons = Object.entries(counts).sort((a, b) => b[1] - a[1] || ORDER.indexOf(a[0]) - ORDER.indexOf(b[0])).map(([k]) => T(k)).join("; ");
    parts.push(scams.length === 1 ? T("weekly_scam_one", reasons) : T("weekly_scam_many", scams.length, reasons));
  }
  if (careful) parts.push(careful === 1 ? T("weekly_careful_one") : T("weekly_careful_many", careful));
  parts.push(end);
  return parts.join(" ");
}

// ---------------------------------------------------------------- quiz (logic/Learn.kt)

const QUIZ_SIZE = 5, QUIZ_MIN_EACH = 2;
const shuffle = (a) => { a = [...a]; for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
function quizRound(avoid = new Set()) {
  const pool = (learn?.quiz || []).filter((q) => !isTestCall(q.script));
  const pick = (items, n, taken) => {
    const fresh = shuffle(items.filter((q) => !avoid.has(q.text) && !taken.has(q.text)));
    const old = shuffle(items.filter((q) => avoid.has(q.text) && !taken.has(q.text)));
    return [...fresh, ...old].slice(0, n);
  };
  const scam = pick(pool.filter((q) => q.scam), QUIZ_MIN_EACH, new Set());
  const safe = pick(pool.filter((q) => !q.scam), QUIZ_MIN_EACH, new Set());
  const taken = new Set([...scam, ...safe].map((q) => q.text));
  return shuffle([...scam, ...safe, ...pick(pool, QUIZ_SIZE - scam.length - safe.length, taken)]);
}
const quizResultLine = (s, of = QUIZ_SIZE) => T(s >= of ? "quiz_r5" : s === of - 1 ? "quiz_r4" : s === of - 2 ? "quiz_r3" : "quiz_r_low");

// ---------------------------------------------------------------- rendering helpers

const $ = (sel) => document.querySelector(sel);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
const fmtDate = (ms) => new Date(ms).toLocaleString("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" });
const back = (to) => `<button class="link" data-go="${to}" aria-label="${esc(T("a11y_back"))}">‹ ${esc(T("btn_back"))}</button>`;
const badge = (lvl, word, big = false) => `<div class="badge ${big ? "big" : ""}"><span class="badge-dot" style="background:${COLORS[lvl]}" aria-hidden="true">${ICON[lvl]}</span><span>${esc(word)}</span></div>`;
const stateBanner = (lvl, text) => `<div class="banner" style="border-color:${COLORS[lvl]}"><span class="badge-dot small" style="background:${COLORS[lvl]}" aria-hidden="true">${ICON[lvl]}</span><span>${esc(text)}</span></div>`;
const emptyState = (icon, text) => `<div class="empty"><div class="empty-ico" aria-hidden="true">${icon}</div><p>${esc(text)}</p></div>`;

function bubble(l, risky = false) {
  const state = l.pending ? `<div class="state">${esc(T("call_checking"))}</div>` : l.failed ? `<div class="state">${esc(T("risk_not_checked"))}</div>` : "";
  const flags = l.flags?.length && !simple() ? `<div class="flags">⚑ ${esc(T("call_flagged", l.flags.map(chipLabel).join(", ")))}</div>` : "";
  const note = l.note && !simple() ? `<div class="state">${esc(T(l.note))}</div>` : "";
  return `<div class="bubble ${l.isCaller ? "" : "me"} ${l.flags?.length ? "flagged" : ""} ${risky ? "risky" : ""}">
    <div class="who">${esc(l.isCaller ? T("call_caller") : T("call_you"))}${risky ? " · " + esc(T("call_risky_here")) : ""}</div>${esc(l.text)}${flags}${note}${state}</div>`;
}

function go(to) { screen = to; sheet = null; render(); $("#screen").scrollTop = 0; }

// ---------------------------------------------------------------- screens

function statusCard() {
  const [cls, title, line] = connected === null ? ["", T("home_status_checking"), ""]
    : connected ? ["ok", T("home_status_ok") + " ✓", T("home_status_ok_line")]
    : ["bad", T("risk_could_not_check"), T("error_banner")];
  return `<div class="status ${cls}" role="status"><span class="badge-dot" aria-hidden="true">${connected ? "✓" : connected === false ? "!" : "…"}</span>
    <div class="grow"><div class="status-title">${esc(title)}</div>${line ? `<div>${esc(line)}</div>` : ""}</div>
    ${connected === false ? `<button class="btn" data-act="recheck">${esc(T("btn_try_again"))}</button>` : ""}</div>`;
}
function learnCard() {
  return `<button class="learn-card" data-go="learn"><span class="badge-dot" aria-hidden="true">★</span>
    <span class="grow"><span class="lc-title">${esc(T("learn_title"))}</span><span>${esc(T("learn_card_line"))}</span></span><span aria-hidden="true">›</span></button>`;
}
function callRow(r) {
  const lvl = levelOfRow(r);
  return `<button class="card click row callrow" data-open="${esc(r.id)}">${badge(lvl, levelWord(lvl))}
    <span class="grow right small muted">${esc(fmtDate(r.endedAt))}</span></button>`;
}

const SCREENS = {
  home: () => simple() ? `
    <h1>${esc(T("app_name_text"))}</h1>
    ${statusCard()}
    <button class="btn fill wide big" data-act="scam">📞 ${esc(T("btn_practice_call"))}</button>
    <p class="muted center">${esc(T("home_practice_line"))}</p>
    ${learnCard()}
    <div class="card"><h2>${esc(T("home_recent"))}</h2>
      ${history().length ? history().slice(0, 3).map(callRow).join("") : emptyState("📞", T("home_no_calls"))}</div>
    <div class="note small">${webNote()}</div>` : `
    <h1>${esc(T("app_name_text"))}</h1>
    <p class="muted">${esc(T("home_full_line"))}</p>
    <button class="btn fill wide" data-act="scam">${esc(T("btn_sim_scam"))}</button>
    <button class="btn fill wide" data-act="normal">${esc(T("btn_sim_normal"))}</button>
    <button class="btn wide" data-go="pick">${esc(T("btn_pick_script"))}</button>
    <button class="btn wide" data-go="learn">${esc(T("btn_learn"))}</button>
    ${scripts.length ? "" : emptyState("ℹ", T("pick_none"))}
    ${connected === false && privacyLevel() !== "on_device" ? stateBanner("ERROR", T("error_banner")) : ""}
    <div class="note small">${webNote()}</div>`,

  pick: () => {
    const shown = scripts.filter((s) => !isTestCall(s.file) && (!pickFilter.kind || s.kind === pickFilter.kind) && (!pickFilter.lang || s.lang === pickFilter.lang));
    const chip = (key, val, label) => `<button class="chip ${pickFilter[key] === val ? "sel" : ""}" data-filter="${key}:${val ?? ""}" aria-pressed="${pickFilter[key] === val}">${esc(label)}</button>`;
    return `${back("home")}<h1>${esc(T("pick_title"))}</h1>
      <div class="chips">${chip("kind", null, T("pick_all"))}${chip("kind", "scam", T("pick_scam"))}${chip("kind", "normal", T("pick_normal"))}</div>
      <div class="chips">${chip("lang", null, T("pick_all"))}${chip("lang", "en", T("pick_english"))}${chip("lang", "zh", T("pick_chinese"))}</div>
      ${shown.map((s) => `<button class="card click row" data-script="${esc(s.file)}">
        <span class="grow"><span class="tag" style="background:${s.kind === "scam" ? "var(--scam)" : "var(--safe)"}">${esc(T(s.kind === "scam" ? "pick_kind_scam" : "pick_kind_normal"))}</span>
          <span class="block strong">${esc(s.title)}</span>
          <span class="block small muted">${esc(T("pick_lines", s.lang === "zh" ? T("pick_chinese") : s.lang === "en" ? T("pick_english") : T("pick_auto"), s.lines.length))}</span></span>
      </button>`).join("") || emptyState("ℹ", T("hist_none"))}`;
  },

  ringing: () => `<div class="ringing">
      <div><p class="muted">${esc(T("call_incoming"))}</p><div class="avatar" aria-hidden="true">👤</div>
        <h1>${esc(T("call_unknown_caller"))}</h1><p class="muted">${esc(T("call_private_number"))}</p></div>
      <div class="answer-row">
        <div><button class="round" style="background:var(--scam)" data-act="decline" aria-label="${esc(T("btn_decline"))}">✕</button><span>${esc(T("btn_decline"))}</span></div>
        <div><button class="round" style="background:var(--safe)" data-act="answer" aria-label="${esc(T("btn_answer"))}">✆</button><span>${esc(T("btn_answer"))}</span></div>
      </div></div>`,

  call: () => {
    const c = call, lvl = callLevel(c), lit = litSignals(c.callScores), color = COLORS[lvl];
    const has = ["SAFE", "UNSURE", "SCAM"].includes(c.verdict);
    const progress = lvl === "LISTENING" ? 0 : lvl === "ERROR" ? 1 : Math.max(0.04, Math.min(1, c.combinedScore ?? 0));
    const kind = warningKind(lit, flaggedTexts(c), null);
    const banner = lvl === "RED" ? stateBanner("RED", warnMessage(kind) + " " + warnDo(kind))
      : lvl === "YELLOW" && showCareful() ? stateBanner("YELLOW", carefulLine(kind))
      : lvl === "ERROR" ? stateBanner("ERROR", T("error_banner")) : "";
    const word = lvl === "YELLOW" && !showCareful() ? T("risk_safe_so_far") : levelWord(lvl, true);
    return `<div ${c.warningOpen ? 'aria-hidden="true" inert' : ""} class="col">
      <div class="strong">${esc(T("call_on_call"))}</div>
      ${simple() ? "" : `<div class="small muted">${esc(c.script.title)} · <span aria-label="${esc(T("a11y_privacy_badge", T("privacy_tag_" + c.level)))}">${esc(T("pw_checked_" + c.level))}</span></div>`}
      <div class="riskbar">
        <div class="row"><span class="grow strong" style="font-size:1.25rem">${esc(word)}</span>
          ${has && c.combinedScore != null && !simple() ? `<span class="small">${esc(T("call_risk_score", c.combinedScore.toFixed(2)))}</span>` : ""}</div>
        <div class="track"><div class="bar" style="width:${progress * 100}%;background:${color}"></div></div>
      </div>
      ${simple() ? "" : `<div class="chips">${SIGNALS.map((s) => `<span class="chip ${lit.includes(s) ? "on" : ""}">${esc(chipLabel(s))}</span>`).join("")}</div>`}
      ${banner}
      <div class="chat">${c.lines.map((l) => bubble(l)).join("")}</div>
      <div class="sticky-bottom"><button class="btn red wide big" data-act="hangup">${esc(T("btn_hang_up"))}</button>
        <button class="link center" data-act="discard">${esc(T("btn_discard_call"))}</button></div></div>
      ${c.warningOpen ? warningScreen(c) : ""}`;
  },

  summary: () => {
    const c = call;
    if (c.reviewing) return `<h1>${esc(T("summary_title"))}</h1><div class="row"><span class="spinner" aria-hidden="true"></span><h2>${esc(T("sum_checking"))}</h2></div><p>${esc(T("sum_checking_line"))}</p>`;
    const lvl = summaryLevel(c), fr = c.finalReview, risky = new Set(fr?.topWindowSeqs || []);
    const hiddenYellow = lvl === "YELLOW" && !showCareful();
    const kind = callKind(c);
    const banner = hiddenYellow ? "" : lvl === "RED" ? stateBanner("RED", warnMessage(kind) + " " + warnDo(kind))
      : lvl === "YELLOW" ? stateBanner("YELLOW", carefulLine(kind)) : lvl === "ERROR" ? stateBanner("ERROR", T("error_banner")) : "";
    const shown = c.lines.filter((l) => l.isCaller && (l.flags.length || risky.has(l.seq)));
    const after = afterCardShows(lvl);
    const fam = family();
    return `<h1>${esc(T("summary_title"))}</h1>
      ${hiddenYellow ? badge("LISTENING", T("risk_call_ended"), true) : badge(lvl, levelWord(lvl), true)}
      ${simple() ? "" : `<p class="muted">${esc(T(fr?.onDevice ? "sum_during_phone" : fr?.reviewed ? "sum_during" : "sum_during_unavailable", levelWord(callLevel(c), true)))}</p>`}
      ${banner}
      ${after ? `<div class="after card">
        <h2>${esc(T("after_title"))}</h2>
        <button class="btn red wide big" data-act="report">${esc(T("after_report"))}</button>
        <p class="muted">${esc(c.copied ? T("after_copied") : T("after_report_line"))}</p>
        <a class="btn wide big outline" href="${telHref(HELPLINE)}">${esc(T("btn_call_1799"))}</a>
        ${fam ? `<a class="btn wide big outline" href="${esc(smsHref(fam.phone, T("sms_family")))}">${esc(T("after_tell_family"))}</a>` : ""}
        <button class="btn wide big outline" data-act="sheet-block">${esc(T("after_block"))}</button>
        <button class="btn wide big outline" data-act="sheet-shared">${esc(T("after_shared"))}</button></div>` : ""}
      ${lvl !== "GREEN" && !hiddenYellow ? `<h2>${esc(T("sum_triggered"))}</h2>
        ${shown.length ? `<div class="chat">${shown.map((l) => bubble(l, risky.has(l.seq))).join("")}</div>` : `<p>${esc(T("sum_no_lines"))}</p>`}` : ""}
      ${c.level === "cloud" ? `<div class="card"><h2>Help ScamGuard learn</h2>
        <p class="small muted">Was this result right? You see exactly what would be shared before anything is sent.</p>
        <div class="row"><button class="btn grow" data-act="learn-scam" ${c.reportSent || c.sending ? "disabled" : ""}>This was a scam</button>
          <button class="btn grow" data-act="learn-fine" ${c.reportSent || c.sending ? "disabled" : ""}>This was fine</button></div>
        ${c.sending ? `<p class="small" role="status">${esc(T("loading"))}</p>` : ""}
        ${c.reportResult ? `<p class="small strong" role="status">${esc(c.reportResult)}</p>` : ""}</div>` : ""}
      <button class="btn fill wide big" data-act="done">${esc(T("btn_done"))}</button>
      ${after ? "" : `<a class="btn wide big outline red-outline" href="${SCAMSHIELD_URL}" target="_blank" rel="noopener">${esc(T("btn_report_scamshield"))}</a>`}
      ${sheet ? (sheet.startsWith("consent-") ? consentSheet(c, sheet.slice(8)) : stepsSheet(sheet)) : ""}`;
  },

  dashboard: () => {
    const calls = history();
    if (!calls.length) return `<h1>${esc(T(simple() ? "tab_family" : "dash_title"))}</h1>${emptyState("📞", T("dash_empty"))}
      <button class="btn fill wide" data-go="home">${esc(T("btn_go_home"))}</button>
      <button class="btn wide" data-go="weekly">${esc(T("btn_weekly"))}</button>`;
    const week = calls.filter((c) => c.endedAt >= Date.now() - 7 * 864e5);
    const scams = week.filter((c) => c.finalVerdict === "SCAM").length;
    const hero = !week.length ? T("pw_hero_none", T("pw_period_week")) : scams === 0 ? T("pw_hero_no_scams", T("pw_period_week"))
      : scams === 1 ? T("pw_hero_one", T("pw_period_week")) : T("pw_hero_many", scams, T("pw_period_week"));
    const counts = {};
    calls.filter((c) => c.finalVerdict === "SCAM" || c.finalVerdict === "UNSURE").forEach((c) => { counts[c.reasonCode] = (counts[c.reasonCode] || 0) + 1; });
    const top = Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 3);
    const save = store.get("saveSentence", false);
    return `<h1>${esc(T(simple() ? "tab_family" : "dash_title"))}</h1>
      <div class="card hero"><div class="hero-text">${esc(hero)}</div><div class="muted">${esc(week.length === 1 ? T("calls_one") : T("calls_many", week.length))} · ${esc(T("period_week"))}</div></div>
      <button class="btn fill wide big" data-go="weekly">${esc(T("btn_weekly"))}</button>
      ${simple() ? "" : `<div class="card"><h2>${esc(T("ins_why"))}</h2>
        ${top.length ? top.map(([r, n]) => `<div class="row"><span class="grow">${esc(reasonText(r))}</span><b>${n}×</b></div>`).join("") : `<p>${esc(T("pw_no_flagged"))}</p>`}</div>`}
      <h2>${esc(T("hist_title"))} (${calls.length})</h2>
      ${calls.map(callRow).join("")}
      <hr><h2>${esc(T("set_my_data"))}</h2>
      <label class="switch"><span class="grow"><span>Save the riskiest sentence</span></span>
        <input type="checkbox" id="save-sentence" ${save ? "checked" : ""}></label>
      <p class="small muted">${esc(T("data_info"))}</p>
      <button class="btn wide red-outline" data-act="delete-history">${esc(T("btn_delete_history"))}</button>`;
  },

  detail: () => {
    const c = history().find((x) => x.id === openCallId);
    if (!c) return `${back("dashboard")}<p>${esc(T("det_gone"))}</p>`;
    const lvl = levelOfRow(c);
    return `${back("dashboard")}<h1>${esc(levelWord(lvl))}</h1>${badge(lvl, levelWord(lvl), true)}
      <p class="muted">${esc(fmtDate(c.startedAt || c.endedAt))}${simple() ? "" : " · " + esc(c.title || "")}</p>
      <div class="card"><p>${esc(reasonText(c.reasonCode))}</p></div>
      <h2>${esc(T("det_risk"))}</h2>${(c.points || []).length ? chart(c.points) : emptyState("📈", T("det_no_scores"))}
      ${c.riskiestSentence ? `<div class="card"><b>${esc(T("det_sentence"))}</b><p>“${esc(c.riskiestSentence)}”</p></div>` : ""}`;
  },

  weekly: () => {
    const text = weeklyText(history());
    return `${back("dashboard")}<h1>${esc(T("weekly_title"))}</h1>
      <p>${esc(T("weekly_line"))}</p>
      <p class="muted">${esc(T("weekly_preview"))}</p>
      <div class="card preview" id="weekly-text">${esc(text)}</div>
      <button class="btn fill wide big" data-act="weekly-share" id="weekly-share">${esc(T("btn_share"))}</button>
      <p class="small" id="weekly-result" role="status"></p>
      <div class="card"><h2>${esc(T("weekly_reminder"))}</h2><p class="muted">${esc(T("weekly_reminder_line"))}</p>
        <p class="small muted">Web version: the reminder is in the Android and iOS apps only.</p></div>`;
  },

  learn: () => {
    if (!learn) return `${back("home")}<h1>${esc(T("learn_title"))}</h1>${emptyState("…", T("loading"))}`;
    return `${back("home")}<h1>${esc(T("learn_title"))}</h1><p class="muted">${esc(T("learn_line"))}</p>
      <button class="learn-card" data-act="quiz-start"><span class="badge-dot" aria-hidden="true">?</span>
        <span class="grow"><span class="lc-title">${esc(T("learn_quiz"))}</span><span>${esc(T("learn_quiz_line"))}</span>
        ${!simple() && store.get("quizBest", null) != null ? `<span class="small">${esc(T("quiz_best", store.get("quizBest"), QUIZ_SIZE))}</span>` : ""}</span><span aria-hidden="true">›</span></button>
      <h2>${esc(T("learn_types"))}</h2>
      ${learn.types.length ? learn.types.map((t) => `<button class="card click row" data-learn="${esc(t.id)}"><span class="grow strong">${esc(t.title)}</span><span aria-hidden="true">›</span></button>`).join("") : emptyState("★", T("empty_learn"))}`;
  },

  learnType: () => {
    const t = learn?.types.find((x) => x.id === learnTypeId);
    if (!t) return `${back("learn")}${emptyState("★", T("empty_learn"))}`;
    return `${back("learn")}<h1>${esc(t.title)}</h1>
      <div class="card"><h2>${esc(T("learn_signs"))}</h2><ul>${t.signs.map((s) => `<li>${esc(s)}</li>`).join("")}</ul></div>
      <div class="card"><h2>${esc(T("learn_todo"))}</h2><p>${esc(t.todo)}</p></div>
      ${scripts.some((s) => s.file === t.script) ? `<button class="btn fill wide big" data-script="${esc(t.script)}">▶ ${esc(T("learn_hear"))}</button>
      <p class="muted">${esc(T("learn_hear_line"))}</p>` : ""}
      <p class="small muted">${esc(T("learn_source", ""))}<a href="${esc(t.source_url)}" target="_blank" rel="noopener">${esc(t.source_name)}</a></p>`;
  },

  quiz: () => {
    const q = quiz;
    if (!q || !q.items.length) return `${back("learn")}${emptyState("★", T("empty_learn"))}`;
    if (q.done) {
      const best = store.get("quizBest", null);
      return `${back("learn")}<h1>${esc(T("quiz_title"))}</h1>
        <div class="card center">${simple() ? "" : `<div class="hero-text">${esc(T("quiz_score", q.score, q.items.length))}</div>`}
          <p class="big-line">${esc(quizResultLine(q.score, q.items.length))}</p>
          ${simple() || best == null ? "" : `<p class="muted">${esc(T("quiz_best", best, QUIZ_SIZE))}</p>`}</div>
        <div class="sticky-bottom"><button class="btn fill wide big" data-act="quiz-again">${esc(T("btn_try_again"))}</button>
          <button class="btn wide big" data-go="learn">${esc(T("btn_done"))}</button></div>`;
    }
    const item = q.items[q.i], a = q.answer;
    const right = a != null && a === item.scam;
    return `${back("learn")}<h1>${esc(T("quiz_title"))}</h1>
      <p class="muted">${esc(T("quiz_progress", q.i + 1, q.items.length))}</p>
      <p class="muted">${esc(T("quiz_caller_says"))}</p>
      <div class="card quote-big">“${esc(item.text)}”</div>
      <h2>${esc(T("quiz_question"))}</h2>
      ${a == null ? "" : `<div class="banner" style="border-color:${right ? "var(--safe)" : "var(--scam)"}" role="status">
        <div><div class="strong">${esc(T(right ? "quiz_right" : "quiz_wrong"))} ${esc(T(item.scam ? "quiz_was_scam" : "quiz_was_safe"))}</div><p>${esc(item.why)}</p></div></div>`}
      <div class="sticky-bottom">${a == null ? `<div class="row"><button class="btn red grow big" data-act="quiz-answer" data-scam="1">${esc(T("quiz_scam"))}</button>
          <button class="btn green grow big" data-act="quiz-answer" data-scam="0">${esc(T("quiz_safe"))}</button></div>`
        : `<button class="btn fill wide big" data-act="quiz-next">${esc(q.i + 1 < q.items.length ? T("btn_next") : T("quiz_see_result"))}</button>`}</div>`;
  },

  settings: () => {
    const url = gatewayUrl();
    const encrypted = /^https:\/\//i.test(url) || (!/^http:\/\//i.test(url) && location.protocol === "https:");
    const fam = family();
    const size = textSize();
    const radio = (name, val, cur, title, line) => `<label class="choice ${cur === val ? "on" : ""}"><input type="radio" name="${name}" value="${val}" ${cur === val ? "checked" : ""}>
      <span><span class="strong block">${esc(title)}</span>${line ? `<span class="small muted block">${esc(line)}</span>` : ""}</span></label>`;
    return `<h1>${esc(T("tab_settings"))}</h1>
      <h2>${esc(T("set_app_style"))}</h2><p class="small muted">${esc(T("style_page_line"))}</p>
      <div class="col" role="radiogroup">${radio("style", "standard", simple() ? "simple" : "standard", T("style_standard"), T("style_standard_line"))}
        ${radio("style", "simple", simple() ? "simple" : "standard", T("style_simple"), T("style_simple_line"))}</div>
      <h2>${esc(T("set_text_size"))}</h2>
      <div class="col" role="radiogroup">${radio("size", "normal", size, T("text_normal"), T("text_normal_line"))}${radio("size", "large", size, T("text_large"))}${radio("size", "xlarge", size, T("text_xlarge"))}</div>
      <hr><h2>${esc(T("set_family"))}</h2><p class="small muted">${esc(T("family_page_line"))}</p>
      <label class="field">${esc(T("field_name"))}<input type="text" id="fam-name" value="${esc(store.get("familyName", ""))}" autocomplete="off"></label>
      <label class="field">${esc(T("field_phone"))}<input type="tel" id="fam-phone" value="${esc(store.get("familyPhone", ""))}" placeholder="${esc(T("field_phone_hint"))}" autocomplete="off"></label>
      <button class="btn fill wide" data-act="fam-save">${esc(T("btn_save"))}</button>
      ${fam ? `<button class="btn wide red-outline" data-act="fam-remove">${esc(T("btn_remove_contact"))}</button>` : ""}
      <p class="small strong" id="fam-result" role="status"></p>
      <hr><h2>${esc(T("set_group_warnings"))}</h2>
      <label class="switch"><span class="grow"><span class="block">${esc(T("set_read_aloud"))}</span><span class="small muted">${esc(T("set_read_aloud_line"))}</span></span>
        <input type="checkbox" id="read-aloud" ${store.get("readAloud", simple()) ? "checked" : ""}></label>
      <label class="switch"><span class="grow"><span class="block">${esc(T("set_show_careful"))}</span><span class="small muted">${esc(T("set_show_careful_line"))}</span></span>
        <input type="checkbox" id="show-careful" ${showCareful() ? "checked" : ""}></label>
      <hr><h2>${esc(T("set_connection"))}</h2>
      ${statusCard()}
      ${simple() ? "" : `<label class="field">Server address<input type="text" id="url" value="${esc(url)}" spellcheck="false"></label>
      <p class="small" style="color:${encrypted ? "var(--safe)" : "var(--scam)"}">${esc(encrypted ? T("srv_encrypted") : T("srv_not_encrypted"))}</p>
      <p class="small muted"><b>api/</b> (the default) means the server this page came from: the docker compose edge
        (http://127.0.0.1:8088/) or the Kubernetes Ingress (https://&lt;laptop IP&gt;:8443/).</p>
      <div class="row"><button class="btn fill" data-act="save-url">${esc(T("btn_save"))}</button><button class="btn" data-act="test-url">${esc(T("btn_test"))}</button></div>
      <p id="url-result" class="small" role="status"></p>`}
      <hr><h2>${esc(T("set_how_checked"))}</h2>
      <div class="col" role="radiogroup">${PRIVACY_LEVELS.map((l) => radio("privacy", l, privacyLevel(), T("privacy_tag_" + l), T("privacy_plain_" + l))).join("")}</div>
      <p class="small muted">${esc(T("privacy_applies_next"))}${privacyLevel() === "cloud" ? "" : " " + esc(T("privacy_english_only"))}</p>
      ${privacyLevel() === "cloud" || simple() ? "" : `<p class="small muted">${esc(onDeviceStatus())}</p>`}
      <hr><h2>${esc(T("help_colours"))}</h2>
      ${badge("GREEN", T("risk_safe") + " – " + T("help_safe_line"))}${badge("YELLOW", T("risk_careful") + " – " + T("help_careful_line"))}
      ${badge("RED", T("risk_scam") + " – " + T("help_scam_line"))}${badge("ERROR", T("risk_not_checked") + " – " + T("help_notchecked_line"))}`;
  },
};

function webNote() {
  const where = { cloud: "Calls are checked online by the ScamGuard server.",
    hybrid: "Calls are checked in this browser; only unsure moments go to the server, masked.",
    on_device: "Calls are checked in this browser only; nothing about the call is sent." }[privacyLevel()];
  return "Web version of the ScamGuard app (for trying it without installing). " + where +
    " Change it in Settings → How calls are checked. Phone calls, messages, the weekly reminder and the PIN lock need the Android or iOS app.";
}

/** Settings, levels 2 and 3: what is loaded in this page (the Developer view's model line, in plain words). */
function onDeviceStatus() {
  const m = engine.scorer ? "On-device models: " + engine.scorer.models.map((x) => x.version).join(", ")
    : engine.scorerLoading ? "On-device models: loading…" : "On-device models: not loaded";
  if (privacyLevel() !== "on_device") return m + ".";
  const b = engine.bert ? `MobileBERT ${engine.bert.config.version}: ready (second opinion in this browser)`
    : engine.bertStatus === "loading" ? "MobileBERT: loading (about 37 MB, once)…"
    : engine.bertStatus === "not loaded" ? "MobileBERT: not loaded"
    : `${engine.bertStatus}: level 3 uses the small models only`;
  return m + ". " + b + ".";
}

function warningScreen(c) {
  const kind = warningKind(litSignals(c.callScores), flaggedTexts(c), null);
  const fam = family();
  return `<div class="warning" role="alertdialog" aria-modal="true" aria-labelledby="warn-title">
    <div class="warn-icon" aria-hidden="true">⚠</div>
    <h1 id="warn-title">${esc(T("warn_title"))}</h1>
    <p class="warn-msg">${esc(warnMessage(kind))}</p>
    <p class="warn-do">${esc(warnDo(kind))}</p>
    <div class="col warn-actions">
      <button class="btn white wide big" data-act="hangup">${esc(T("btn_hang_up"))}</button>
      ${fam ? `<a class="btn outline-white wide big" href="${telHref(fam.phone)}" data-act="hangup-then">${esc(T("btn_call_family"))}</a>
      <a class="btn outline-white wide big" href="${esc(smsHref(fam.phone, T("sms_family")))}">${esc(T("btn_tell_family", fam.name))}</a>` : ""}
      <a class="btn outline-white wide big" href="${telHref(HELPLINE)}">${esc(T("btn_call_1799"))}</a>
      <button class="link white center" data-act="close-warning">${esc(T("btn_close_warning"))}</button>
    </div></div>`;
}

function stepsSheet(kind) {
  const block = [["Android", ["block_android_1", "block_android_2", "block_android_3", "block_android_4"]],
                 ["iPhone", ["block_ios_1", "block_ios_2", "block_ios_3", "block_ios_4"]]];
  const body = kind === "block"
    ? `<h2>${esc(T("block_title"))}</h2>${block.map(([os, keys]) => `<p class="strong">${os}</p><ol>${keys.map((k) => `<li>${esc(T(k))}</li>`).join("")}</ol>`).join("")}`
    : `<h2>${esc(T("shared_title"))}</h2><p>${esc(T("shared_line"))}</p><ol><li>${esc(T("shared_bank"))}</li><li>${esc(T("shared_passwords"))}</li><li>${esc(T("shared_1799"))}</li></ol>
       <a class="btn fill wide big" href="${telHref(HELPLINE)}">${esc(T("btn_call_1799"))}</a>`;
  return `<div class="scrim" data-act="sheet-close"></div><div class="sheet" role="dialog" aria-modal="true">${body}
    <button class="btn wide big" data-act="sheet-close">${esc(T("btn_close"))}</button></div>`;
}

function chart(points) {
  const pts = points.filter(([, v]) => v != null).sort((a, b) => a[0] - b[0]);
  const w = 400, h = 180, pad = 18;
  const maxT = Math.max(1, ...pts.map(([t]) => t));
  const x = (t) => pad + (t / maxT) * (w - 2 * pad), y = (v) => h - pad - Math.min(1, Math.max(0, v)) * (h - 2 * pad);
  const line = (v, col) => `<line x1="${pad}" x2="${w - pad}" y1="${y(v)}" y2="${y(v)}" stroke="${col}" stroke-dasharray="4 4"/>`;
  const path = pts.map(([t, v], i) => `${i ? "L" : "M"}${x(t).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  return `<svg class="chart" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" role="img" aria-label="${esc(T("det_risk"))}">
    ${line(UNSURE_LINE, "var(--warn)")}${line(SCAM_LINE, "var(--scam)")}
    <path d="${path}" fill="none" stroke="var(--primary)" stroke-width="2.5"/>
    ${pts.map(([t, v]) => `<circle cx="${x(t)}" cy="${y(v)}" r="3" fill="var(--primary)"/>`).join("")}</svg>`;
}

// ---------------------------------------------------------------- render + events

const TAB_OF = { home: "home", dashboard: "dashboard", detail: "dashboard", weekly: "dashboard", settings: "settings" };
function applyLook() {
  document.documentElement.dataset.size = textSize();
  document.documentElement.dataset.simple = simple() ? "yes" : "no";
  const dash = document.querySelector('#tabs button[data-tab="dashboard"] .lbl');
  if (dash) dash.textContent = T(simple() ? "tab_family" : "tab_dashboard");
  document.querySelector('#tabs button[data-tab="home"] .lbl').textContent = T("tab_home");
  document.querySelector('#tabs button[data-tab="settings"] .lbl').textContent = T("tab_settings");
}
function render() {
  const keepScroll = screen === "call" ? null : $("#screen").scrollTop;
  applyLook();
  $("#screen").innerHTML = SCREENS[screen]();
  const tab = TAB_OF[screen];
  $("#tabs").hidden = !tab;
  document.querySelectorAll("#tabs button").forEach((b) => { b.classList.toggle("on", b.dataset.tab === tab); b.setAttribute("aria-current", b.dataset.tab === tab ? "page" : "false"); });
  if (screen === "call" && !call?.warningOpen) { const s = $("#screen"); s.scrollTop = s.scrollHeight; }
  else if (keepScroll != null) $("#screen").scrollTop = keepScroll;
  if (call?.warningOpen && screen === "call") $(".warning h1")?.focus?.();
}

document.addEventListener("click", async (e) => {
  const el = e.target.closest("[data-go],[data-act],[data-tab],[data-script],[data-open],[data-filter],[data-learn]");
  if (!el) return;
  if (el.dataset.tab) return go(el.dataset.tab);
  if (el.dataset.go) return go(el.dataset.go);
  if (el.dataset.script) { const s = scripts.find((x) => x.file === el.dataset.script); if (s) ring(s); return; }
  if (el.dataset.open) { openCallId = el.dataset.open; return go("detail"); }
  if (el.dataset.learn) { learnTypeId = el.dataset.learn; return go("learnType"); }
  if (el.dataset.filter) { const [k, v] = el.dataset.filter.split(":"); pickFilter[k] = v || null; return render(); }
  const c = call;
  switch (el.dataset.act) {
    case "scam": case "normal": { const s = randomScript(el.dataset.act); if (s) ring(s); else render(); break; }
    case "answer": answer(); break;
    case "decline": stopTimers(); call = null; go("home"); break;
    case "hangup": if (c) endCall(c, "user_hung_up"); break;
    case "hangup-then": if (c) setTimeout(() => endCall(c, "user_hung_up"), 0); break;   // the tel: link still opens
    case "discard": discard(); break;
    case "close-warning": if (c) { c.warningOpen = false; render(); } break;
    case "done": call = null; go("home"); break;
    case "recheck": checkConnection(); break;
    case "report":
      if (c) { c.copied = await copyText(reportSummary(c)); window.open(SCAMSHIELD_URL, "_blank", "noopener"); render(); }
      break;
    case "sheet-block": sheet = "block"; render(); break;
    case "sheet-shared": sheet = "shared"; render(); break;
    case "sheet-close": sheet = null; render(); break;
    case "learn-scam": case "learn-fine": if (c) { sheet = el.dataset.act === "learn-scam" ? "consent-scam" : "consent-fine"; render(); } break;
    case "consent-no": sheet = null; if (c) c.reportResult = "Nothing was shared."; render(); break;
    case "consent-yes":
      if (!c) break;
      sheet = null; c.sending = true; render();
      [c.reportResult, c.reportSent] = await sendReport(c, el.dataset.kind);
      c.sending = false; render(); break;
    case "quiz-start": case "quiz-again": {
      const avoid = new Set((quiz?.items || []).map((q) => q.text));
      quiz = { items: quizRound(avoid), i: 0, answer: null, score: 0, done: false };
      go("quiz"); break;
    }
    case "quiz-answer": {
      const said = el.dataset.scam === "1";
      quiz.answer = said; if (said === quiz.items[quiz.i].scam) quiz.score++;
      render(); break;
    }
    case "quiz-next":
      if (quiz.i + 1 < quiz.items.length) { quiz.i++; quiz.answer = null; }
      else { quiz.done = true; store.set("quizBest", Math.max(store.get("quizBest", 0) ?? 0, quiz.score)); }
      render(); $("#screen").scrollTop = 0; break;
    case "weekly-share": {
      const text = weeklyText(history());
      const out = $("#weekly-result");
      try {
        if (navigator.share) { await navigator.share({ text }); out.textContent = ""; }
        else { out.textContent = (await copyText(text)) ? T("after_copied").split(":")[0] : ""; }
      } catch { /* share closed */ }
      break;
    }
    case "fam-save": {
      const phone = $("#fam-phone").value.trim(), name = $("#fam-name").value.trim();
      if (!phone) { $("#fam-result").textContent = T("family_need_phone"); break; }
      store.set("familyName", name); store.set("familyPhone", phone);
      render(); $("#fam-result").textContent = T("saved") + " ✓"; break;
    }
    case "fam-remove": store.set("familyName", ""); store.set("familyPhone", ""); render(); break;
    case "delete-history":
      if (confirm(T("delete_title") + "\n\n" + T("delete_text"))) { store.set("calls", []); render(); }
      break;
    case "save-url": store.set("gatewayUrl", $("#url").value.trim() || "api/"); $("#url-result").textContent = T("saved") + " ✓"; checkConnection(); break;
    case "test-url": {
      const out = $("#url-result");
      out.textContent = T("mdl_res_checking");
      try {
        const r = await api("healthz", undefined, 4000, $("#url").value);
        out.textContent = !r.ok ? `Server answered ${r.status}${r.status === 404 ? ": check the address (it should end in /api/)" : ""}`
          : r.json?.status === "ok" ? "Connected: ok" : "Something answered, but not the ScamGuard gateway: check the address";
      } catch { out.textContent = "Could not connect (server down, wrong address, or the server doesn't allow this page: CORS)"; }
      break;
    }
  }
});
document.addEventListener("change", (e) => {
  const t = e.target;
  if (t.id === "save-sentence") {
    store.set("saveSentence", t.checked);
    if (!t.checked) store.set("calls", history().map((c) => ({ ...c, riskiestSentence: null })));
  }
  if (t.id === "read-aloud") store.set("readAloud", t.checked);
  if (t.id === "show-careful") store.set("showCareful", t.checked);
  if (t.name === "style") {
    const toSimple = t.value === "simple";
    store.set("simple", toSimple);
    if (store.get("textSize", null) === null || toSimple) store.set("textSize", toSimple ? "large" : textSize());
    render();
  }
  if (t.name === "size") { store.set("textSize", t.value); render(); }
  if (t.name === "privacy") { store.set("privacyLevel", t.value); preload(t.value); render(); }
});
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (sheet) { sheet = null; return render(); }
  if (screen === "call") { if (call?.warningOpen) { call.warningOpen = false; return render(); } return discard(); }
  if (["pick", "ringing"].includes(screen)) { stopTimers(); call = null; go("home"); }
  else if (screen === "detail" || screen === "weekly") go("dashboard");
  else if (screen === "learnType" || screen === "quiz") go("learn");
  else if (screen === "learn") go("home");
});

(async () => {
  try { STR = await (await fetch("strings.json")).json(); } catch { STR = {}; }
  render();
  await Promise.all([loadScripts(), loadLearn()]);
  render();
  preload();
  checkConnection();
})();

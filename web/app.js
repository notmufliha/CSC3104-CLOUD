// ScamGuard web app: the Android app's cloud mode (privacy level 1) in a browser. Same gateway calls
// (POST v1/utterance, v1/call/end, v1/report, GET healthz), same screens and the same rules for colours, reasons
// and history. Kept in step with android-app/.../ui/CallViewModel.kt, logic/Risk.kt, logic/Mask.kt.
// The call scripts are the app's own (android-app/app/src/main/assets/scripts), served at scripts/.
// History stays in this browser (localStorage), like the phone's on-device database.
"use strict";

// ---------------------------------------------------------------- constants (as in the app)

const SIGNALS = ["urgency", "money", "impersonation"];
const SIGNAL_ON = 0.5;                 // Risk.SIGNAL_ON
const DEFAULT_DELAY_MS = 1500;
const FINAL_REVIEW_WAIT_MS = 3000;
const UNSURE_LINE = 0.45, SCAM_LINE = 0.6;   // the aggregator's default thresholds, drawn on the chart
const SCAMSHIELD_URL = "https://www.scamshield.gov.sg/";
const COLORS = { GREEN: "var(--safe)", YELLOW: "var(--warn)", RED: "var(--scam)", ERROR: "var(--grey)", LISTENING: "var(--grey)" };
const LABELS = { LISTENING: "Listening…", GREEN: "Looks safe so far", YELLOW: "Suspicious", RED: "Likely scam", ERROR: "Could not check" };
const REASON_TEXT = {
  money: "Asked for your OTP or money",
  impersonation: "Claims to be police, a bank or the government",
  urgency: "Pressuring you to act now",
  bert: "The conversation as a whole sounds like a scam",
  several: "Several small warning signs together",
  none: "No warning signs",
  server_not_reached: "Server not reached",
  no_lines: "Ended before any line was checked",
};

// ---------------------------------------------------------------- storage (every access guarded)

const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem("scamguard." + key); return v === null ? fallback : JSON.parse(v); }
    catch { return fallback; }
  },
  set(key, value) { try { localStorage.setItem("scamguard." + key, JSON.stringify(value)); } catch { /* private mode */ } },
};

/** "api/" (same address as this page) unless Settings saved another. */
function gatewayUrl() { return store.get("gatewayUrl", "api/"); }
function normalize(url) {
  let u = (url || "").trim() || "api/";
  // "192.168.1.23:8080" or "host.example:8443/api" -> add http://; "api/" or "/api/" stay relative to this page
  if (!/^https?:\/\//i.test(u) && /^[\w.-]+(:\d+)(\/|$)|^\d+\.\d+\.\d+\.\d+/.test(u)) u = "http://" + u;
  if (!u.endsWith("/")) u += "/";
  return u;
}

// ---------------------------------------------------------------- risk rules (logic/Risk.kt)

function level(verdict, error) {
  if (verdict === "SCAM") return "RED";
  if (verdict === "UNSURE") return "YELLOW";
  if (error) return "ERROR";
  if (verdict === "SAFE") return "GREEN";
  return "LISTENING";
}
const SEVERITY = { LISTENING: 0, GREEN: 1, ERROR: 2, YELLOW: 3, RED: 4 };
const litSignals = (scores) => SIGNALS.filter((s) => (scores?.[s] ?? 0) >= SIGNAL_ON);
const chipLabel = (s) => ({ urgency: "Urgency", money: "Money / OTP", impersonation: "Impersonation" }[s] || s);
function reasonFor(lit, texts) {
  const all = texts.join(" ").toLowerCase();
  if (lit.includes("money") && (all.includes("otp") || all.includes("one-time") || all.includes("验证码"))) return "asking for your OTP";
  if (lit.includes("money")) return "asking you to transfer money";
  if (lit.includes("impersonation")) return "pretending to be an official (police, bank, government)";
  if (lit.includes("urgency")) return "pressuring you to act right now";
  return "several small warning signs together";
}
function actionFor(lit) {
  if (lit.includes("money")) return "Hang up and call your bank on the number on your card.";
  if (lit.includes("impersonation")) return "Hang up and call the agency back on its official number.";
  return "Don't share personal details. Real organisations give you time to check.";
}
function bannerFor(lvl, lit, texts) {
  if (lvl === "RED") return `Likely scam: ${reasonFor(lit, texts)}. ${actionFor(lit)}`;
  if (lvl === "YELLOW") return `Be careful: ${reasonFor(lit, texts)}. ${actionFor(lit)}`;
  if (lvl === "ERROR") return "Could not check this call. The server can't be reached — this does NOT mean it is safe.";
  return null;
}

// ---------------------------------------------------------------- masking (logic/Mask.kt, backend/review/mask.py)

const Mask = (() => {
  const email = /\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b/g;
  const url = /\b(?:https?:\/\/|www\.)\S+|\b[\w-]+\.(?:com|sg|net|org|gov|io|co|my|cn)(?:\/\S*)?\b/gi;
  const money = /(?:(?:S?\$|SGD|USD|RM|MYR|RMB|¥|€|£)\s?\d[\d,]*(?:\.\d+)?(?:\s?(?:k|m|million|thousand))?)|(?:\d[\d,]*(?:\.\d+)?\s?(?:dollars?|sgd|bucks|元|块))/gi;
  const id = /\b[STFGM]\d{7}[A-Z]\b/gi;
  const phone = /(?<![\w])\+?\d[\d\s-]{6,}\d(?![\w])/g;
  const digits = /\d+/g;
  const TITLE = "(?:[Oo]fficer|[Ii]nspector|[Ss]ergeant|[Mm]r|[Mm]rs|[Mm]s|[Mm]adam|[Dd]r|[Dd]octor)\\.?";
  const CUE = "(?:[Mm]y name is|[Tt]his is|[Ii] am|[Ii]'m|[Ss]peaking with)";
  const name = new RegExp(`((?:\\b${CUE}\\s+(?:${TITLE}\\s+)?|\\b${TITLE}\\s+))([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?)`, "g");
  const nameZh = /(我叫|我姓)([一-鿿]{1,3})/g;
  const notNames = new Set(("police singapore dbs ocbc uob posb maybank citibank hsbc standard moh mom iras cpf ica spf " +
    "customs immigration interpol the your a an calling from with here just not sorry sure fine okay ok very shopee " +
    "lazada grab singtel starhub m1 dhl fedex ninja amazon apple microsoft google manager officer inspector sergeant " +
    "madam sir doctor").split(" "));
  return (text) => {
    let t = text.replace(email, "[EMAIL_ADDRESS]");
    t = t.replace(url, "[URL]").replace(money, "[MONEY_AMOUNT]").replace(id, "[NUMBER]");
    t = t.replace(phone, "[PHONE_NUMBER]").replace(digits, "[NUMBER]");
    t = t.replace(name, (_, cue, who) => cue + (notNames.has(who.split(" ")[0].toLowerCase()) ? who : "[PERSON_NAME]"));
    return t.replace(nameZh, (_, cue) => cue + "[PERSON_NAME]");
  };
})();

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

// ---------------------------------------------------------------- scripts

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
          title: title ? title[0].toUpperCase() + title.slice(1) : file,
          lines: lines.filter((l) => l.text && l.text.trim()),
        };
      } catch { return null; }
    }))).filter(Boolean);
  } catch { scripts = []; }
}
const isCaller = (l) => ["caller", "speaker 1", "speaker1", "speaker_1"].includes((l.speaker || "").trim().toLowerCase());
let lastRandom = null;
function randomScript(kind) {
  const pool = scripts.filter((s) => s.kind === kind);
  const others = pool.filter((s) => s.file !== lastRandom);
  const pick = (others.length ? others : pool)[Math.floor(Math.random() * (others.length || pool.length))];
  lastRandom = pick?.file;
  return pick;
}

// ---------------------------------------------------------------- state

let screen = "home";           // home | pick | paste | ringing | call | summary | dashboard | detail | settings
let call = null;               // the current call (see newCall)
let openCallId = null;
let pickFilter = { kind: null, lang: null };

function newCall(script) {
  return {
    script, id: crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random(),
    lines: [], verdict: null, combinedScore: null, callScores: {}, error: null, lastOutcomeSeq: 0,
    scamAlert: false, alertShown: false, points: [], startedAt: 0, timers: [], ended: false,
    reviewing: false, finalReview: null, reportResult: null, sending: false,
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

// ---------------------------------------------------------------- call lifecycle (CallViewModel.kt)

function ring(script) { stopTimers(); call = newCall(script); go("ringing"); }

function answer() {
  const c = call;
  c.startedAt = Date.now();
  go("call");
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
    c.scamAlert = true;
    try { navigator.vibrate?.(600); } catch { /* not supported */ }
    showAlert(c);
  }
  render();
}

function markError(c, seq, msg) {
  const line = c.lines.find((l) => l.seq === seq);
  if (line) { line.pending = false; line.failed = true; }
  if (seq >= c.lastOutcomeSeq) { c.lastOutcomeSeq = seq; c.error = msg; }
  render();
}

function stopTimers() { if (call) { call.timers.forEach(clearTimeout); call.timers = []; } }

/** Back during a call: the server is told (so it clears the call's memory); no summary, nothing saved. */
function discard() {
  if (call && screen === "call" && !call.ended) {
    call.ended = true; stopTimers();
    sendEnd(call, "user_discarded");
  }
  call = null; closeOverlay(); go("home");
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
  c.lines.forEach((l) => { if (l.pending) { l.pending = false; l.failed = true; } });
  c.reviewing = true;
  closeOverlay();
  go("summary");
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

// ---------------------------------------------------------------- history (B.7, in this browser)

const history = () => store.get("calls", []);
const historyVerdict = (lvl) => ({ RED: "SCAM", YELLOW: "UNSURE", GREEN: "SAFE" }[lvl] || "NOT CHECKED");
function reasonCode(lvl, scores, serverReason) {
  if (lvl === "RED" || lvl === "YELLOW") {
    const fromServer = Object.entries(REASON_TEXT).find(([, t]) => t.toLowerCase() === (serverReason || "").trim().toLowerCase());
    if (fromServer) return fromServer[0];
    const lit = SIGNALS.filter((s) => (scores[s] ?? 0) >= SIGNAL_ON).sort((a, b) => scores[b] - scores[a]);
    return lit[0] || "several";
  }
  return { GREEN: "none", ERROR: "server_not_reached" }[lvl] || "no_lines";
}
function saveHistory(c) {
  if (c.script.kind === "pasted") return;
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
    review: c.finalReview?.reviewed ? "bert" : c.finalReview?.answered ? "fallback" : "unavailable",
    title: c.script.title, riskiestSentence: sentence, points: c.points.map((p) => [p.t, p.combined]),
  });
  store.set("calls", calls.slice(0, 200));
}

// ---------------------------------------------------------------- report (H.3)

async function sendReport(c, kind) {
  const lines = reportPreview(c);
  if (!lines.length) return "Nothing to report: the caller said nothing.";
  const text = lines.map((l) => l.text).join(" ");
  const chinese = [...text].filter((ch) => ch >= "一" && ch <= "鿿").length;
  const language = c.script.lang || (chinese * 10 > text.length * 3 ? "zh" : "en");
  try {
    const r = await api("v1/report", { call_id: c.id, kind, app_verdict: c.verdict || "", language, consent: true, lines, consent_version: 2 });
    if (r.ok) return `Thank you. Shared ${r.json?.lines_stored ?? lines.length} lines; lines not approved for training are deleted after 30 days.`;
    if (r.status === 503) return "Reporting is not set up on this server.";
    return `Could not send the report (server answered ${r.status}).`;
  } catch (e) { return "Could not send the report: " + (e.name || "error"); }
}
const reportPreview = (c) => c.lines.filter((l) => l.isCaller && l.text.trim()).map((l) => ({ seq: l.seq, text: Mask(l.text) }));

// ---------------------------------------------------------------- rendering helpers

const $ = (sel) => document.querySelector(sel);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
const fmtDate = (ms) => new Date(ms).toLocaleString(undefined, { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
const banner = (text, color) => `<div class="banner" style="border-color:${color};background:color-mix(in srgb, ${color} 15%, transparent)">${esc(text)}</div>`;
const back = (to, label = "‹ Back") => `<button class="link" data-go="${to}">${label}</button>`;

function bubble(l, risky = false) {
  const state = l.pending ? `<div class="state">checking…</div>` : l.failed ? `<div class="state">could not check</div>` : "";
  const flags = l.flags?.length ? `<div class="flags">⚑ ${l.flags.map(chipLabel).join(", ")}</div>` : "";
  return `<div class="bubble ${l.isCaller ? "" : "me"} ${l.flags?.length ? "flagged" : ""} ${risky ? "risky" : ""}">
    <div class="who">${l.isCaller ? "Caller" : "You"}${risky ? " · where it turned risky" : ""}</div>${esc(l.text)}${flags}${state}</div>`;
}

function go(to) { screen = to; render(); if (to === "call" || to === "home") $("#screen").scrollTop = 0; }

// ---------------------------------------------------------------- screens

const SCREENS = {
  home: () => `
    <h1>ScamGuard</h1>
    <p class="muted">Simulate a call and watch the scam check work live.</p>
    <button class="btn fill wide" data-act="scam">Simulate scam call</button>
    <button class="btn fill wide" data-act="normal">Simulate normal call</button>
    <button class="btn wide" data-go="pick">Choose a call script…</button>
    <button class="btn wide" data-go="paste">Paste text</button>
    ${scripts.length ? "" : `<div class="note">No call scripts found at <code>scripts/</code>. Paste text still works.</div>`}
    <div class="note small">Web version of the ScamGuard Android app. It checks calls with the ScamGuard server
      (privacy level 1, Cloud). The on-device privacy levels need the Android app.</div>`,

  pick: () => {
    const shown = scripts.filter((s) => (!pickFilter.kind || s.kind === pickFilter.kind) && (!pickFilter.lang || s.lang === pickFilter.lang));
    const chip = (key, val, label) => `<button class="chip ${pickFilter[key] === val ? "sel" : ""}" data-filter="${key}:${val ?? ""}">${label}</button>`;
    return `${back("home")}<h1>Call scripts</h1>
      <div class="chips">${chip("kind", null, "All")}${chip("kind", "scam", "Scam")}${chip("kind", "normal", "Normal")}
        ${chip("lang", null, "Any language")}${chip("lang", "en", "English")}${chip("lang", "zh", "中文")}</div>
      ${shown.map((s) => `<div class="card click row" data-script="${esc(s.file)}">
        <div class="grow"><div style="font-weight:600">${esc(s.title)}</div>
          <div class="small muted">${s.lines.length} lines · ${s.lang === "zh" ? "Mandarin" : s.lang === "en" ? "English" : "auto"}</div></div>
        <span class="tag" style="background:${s.kind === "scam" ? "var(--scam)" : "var(--safe)"}">${s.kind === "scam" ? "Scam" : "Normal"}</span>
      </div>`).join("") || `<p class="muted">No scripts match.</p>`}`;
  },

  paste: () => `${back("home")}<h1>Paste text</h1>
    <p class="muted small">Paste what a caller said (a message, a transcript). It is checked as one caller line.</p>
    <textarea id="pasted" placeholder="e.g. This is the police. Transfer the money to the safe account now."></textarea>
    <button class="btn fill wide" data-act="check-pasted">Check</button>`,

  ringing: () => `<div class="ringing">
      <div><p class="muted">Incoming call</p><div class="avatar">👤</div>
        <h1>Unknown caller</h1><p class="muted">Private number</p>
        <p class="small muted" style="margin-top:8px">Script: ${esc(call.script.title)}</p></div>
      <div class="answer-row">
        <div><button class="round" style="background:var(--scam)" data-act="decline" aria-label="Decline">✕</button><span class="small">Decline</span></div>
        <div><button class="round" style="background:var(--safe)" data-act="answer" aria-label="Answer">✆</button><span class="small">Answer</span></div>
      </div></div>`,

  call: () => {
    const c = call, lvl = callLevel(c), lit = litSignals(c.callScores), color = COLORS[lvl];
    const has = ["SAFE", "UNSURE", "SCAM"].includes(c.verdict);
    const progress = lvl === "LISTENING" ? 0 : lvl === "ERROR" ? 1 : Math.max(0.04, Math.min(1, c.combinedScore ?? 0));
    const b = bannerFor(lvl, lit, flaggedTexts(c));
    return `<div class="row"><div class="grow"><div style="font-weight:600">Unknown caller</div>
        <div class="small muted">${esc(c.script.title)} · Cloud</div></div></div>
      <div class="riskbar">
        <div class="row"><span class="grow" style="font-weight:600;color:${color}">${LABELS[lvl]}</span>
          ${has && c.combinedScore != null ? `<span class="small">risk ${c.combinedScore.toFixed(2)}</span>` : ""}</div>
        <div class="track"><div class="bar" style="width:${progress * 100}%;background:${color}"></div></div>
      </div>
      <div class="chips">${SIGNALS.map((s) => `<span class="chip ${lit.includes(s) ? "on" : ""}">${chipLabel(s)}</span>`).join("")}</div>
      ${b ? banner(b, color) : ""}
      <div class="chat">${c.lines.map((l) => bubble(l)).join("")}</div>
      <div class="sticky-bottom"><button class="btn red wide" data-act="hangup">Hang up</button></div>`;
  },

  summary: () => {
    const c = call;
    if (c.reviewing) return `<h1>Call summary</h1><h2>Final review…</h2><p class="small muted">Checking the whole call once more.</p>`;
    const lvl = summaryLevel(c), fr = c.finalReview, risky = new Set(fr?.topWindowSeqs || []), lit = litSignals(c.callScores);
    const reason = fr?.reason && (lvl === "RED" || lvl === "YELLOW") ? fr.reason : null;
    const b = reason ? `${reason}. ${actionFor(lit)}` : bannerFor(lvl, lit, flaggedTexts(c));
    const shown = c.lines.filter((l) => l.isCaller && (l.flags.length || risky.has(l.seq)));
    const sent = (c.reportResult || "").startsWith("Thank you");
    return `<h1>Call summary</h1>
      <h2 style="font-size:24px;color:${COLORS[lvl]}">${LABELS[lvl].toUpperCase()}</h2>
      <p class="small muted">During the call: ${LABELS[callLevel(c)]}${fr?.reviewed ? "" : " · Final review unavailable"}</p>
      ${b ? banner(b, COLORS[lvl]) : ""}
      <h2>What triggered it</h2>
      ${shown.length ? `<div class="chat">${shown.map((l) => bubble(l, risky.has(l.seq))).join("")}</div>` : `<p>No caller lines were flagged.</p>`}
      <a class="btn red wide" href="${SCAMSHIELD_URL}" target="_blank" rel="noopener">Report to ScamShield</a>
      <div class="row">
        <button class="btn grow" data-act="report-scam" ${sent || c.sending ? "disabled" : ""}>This was a scam</button>
        <button class="btn grow" data-act="report-fine" ${sent || c.sending ? "disabled" : ""}>This was fine</button>
      </div>
      ${c.sending ? `<p class="small">Sending…</p>` : ""}
      ${c.reportResult ? `<p class="small">${esc(c.reportResult)}</p>` : ""}
      <button class="btn wide" data-act="done">Done</button>`;
  },

  dashboard: () => {
    const calls = history();
    const counts = {};
    calls.filter((c) => c.finalVerdict === "SCAM" || c.finalVerdict === "UNSURE")
      .forEach((c) => { const t = REASON_TEXT[c.reasonCode] || c.reasonCode; counts[t] = (counts[t] || 0) + 1; });
    const top = Object.entries(counts).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, 3);
    const save = store.get("saveSentence", false);
    return `<h1>Dashboard</h1>
      <div class="card"><div style="font-weight:600">Most common warning reasons</div>
        ${top.length ? top.map(([r, n]) => `<div class="row"><span class="grow">${esc(r)}</span><b>${n}×</b></div>`).join("") : `<p class="small">No warnings yet.</p>`}</div>
      <h2>Past calls (${calls.length})</h2>
      ${calls.length ? calls.map((c) => `<div class="card click row" data-open="${esc(c.id)}">
          <span class="dot" style="background:${COLORS[level(c.finalVerdict === "NOT CHECKED" ? null : c.finalVerdict, c.finalVerdict === "NOT CHECKED")]}"></span>
          <div class="grow"><div class="row"><b class="grow" style="color:${COLORS[level(c.finalVerdict === "NOT CHECKED" ? null : c.finalVerdict, c.finalVerdict === "NOT CHECKED")]}">${esc(c.finalVerdict)}</b>
            <span class="tiny muted">${fmtDate(c.endedAt)}</span></div>
            <div class="row"><span class="grow small">${esc(REASON_TEXT[c.reasonCode] || c.reasonCode)}</span><span class="tiny muted">Cloud · Web</span></div></div>
        </div>`).join("") : `<p>No calls yet.</p>`}
      <hr><h2>Privacy</h2>
      <label class="switch"><span class="grow"><span>Save the riskiest sentence</span><br>
        <span class="small muted">Keeps the one caller line that scored highest in calls that ended with a warning. Off: no words from your calls are kept, only verdicts and scores.</span></span>
        <input type="checkbox" id="save-sentence" ${save ? "checked" : ""}></label>
      <p class="small muted">Your history stays in this browser. Nothing on this screen is uploaded.</p>
      <button class="btn wide" data-act="delete-history" ${calls.length ? "" : "disabled"}>Delete my history</button>`;
  },

  detail: () => {
    const c = history().find((x) => x.id === openCallId);
    if (!c) return `${back("dashboard")}<p>Call not found.</p>`;
    const color = COLORS[level(c.finalVerdict === "NOT CHECKED" ? null : c.finalVerdict, c.finalVerdict === "NOT CHECKED")];
    return `${back("dashboard")}<h1>Call details</h1>
      <h2 style="font-size:22px;color:${color}">${esc(c.finalVerdict)}</h2>
      <p class="small muted">${fmtDate(c.startedAt)} · ${esc(c.title || "")} · during the call: ${esc(c.liveVerdict)} · review: ${esc(c.review)}</p>
      <div class="card"><b>Reason</b><p>${esc(REASON_TEXT[c.reasonCode] || c.reasonCode)}</p></div>
      <h2>Risk during the call</h2>${chart(c.points || [])}
      <p class="tiny muted">Dashed lines: UNSURE (${UNSURE_LINE}) and SCAM (${SCAM_LINE}) thresholds.</p>
      ${c.riskiestSentence ? `<div class="card"><b>Riskiest sentence</b><p>“${esc(c.riskiestSentence)}”</p></div>` : ""}`;
  },

  settings: () => {
    const url = gatewayUrl();
    const encrypted = /^https:\/\//i.test(url) || (!/^http:\/\//i.test(url) && location.protocol === "https:");
    return `<h1>Settings</h1>
      <label class="field">Gateway URL<input type="text" id="url" value="${esc(url)}" spellcheck="false"></label>
      <p class="small" style="color:${encrypted ? "var(--primary)" : "var(--scam)"}">${encrypted ? "Encrypted (HTTPS)" : "Not encrypted: transcripts are sent as plain text"}</p>
      <p class="small muted"><b>api/</b> (the default) means the server this page came from: the docker compose edge
        (http://127.0.0.1:8088/) or the Kubernetes Ingress (https://&lt;laptop IP&gt;:8443/). Another server must allow
        this page's address (CORS).</p>
      <div class="row"><button class="btn fill" data-act="save-url">Save</button><button class="btn" data-act="test-url">Test connection</button></div>
      <p id="url-result" class="small"></p>
      <hr><h2>Privacy level</h2>
      <div class="radio"><input type="radio" checked><div><div>1. Cloud (default)</div>
        <div class="small muted">Every caller sentence is checked by the ScamGuard server. Nothing is stored there.</div></div></div>
      <div class="radio off"><input type="radio" disabled><div><div>2. Hybrid</div>
        <div class="small muted">Android app only: checked on the phone, only unsure moments go to the server.</div></div></div>
      <div class="radio off"><input type="radio" disabled><div><div>3. On-device</div>
        <div class="small muted">Android app only: everything is checked on the phone, even in airplane mode.</div></div></div>
      <p class="small muted">On-device models, federated learning on the phone and levels 2 and 3 need the Android app
        (android-app/ in Android Studio).</p>`;
  },
};

function chart(points) {
  const pts = points.filter(([, v]) => v != null).sort((a, b) => a[0] - b[0]);
  const w = 400, h = 180, pad = 18;
  const maxT = Math.max(1, ...pts.map(([t]) => t));
  const x = (t) => pad + (t / maxT) * (w - 2 * pad), y = (v) => h - pad - Math.min(1, Math.max(0, v)) * (h - 2 * pad);
  const line = (v, col) => `<line x1="${pad}" x2="${w - pad}" y1="${y(v)}" y2="${y(v)}" stroke="${col}" stroke-dasharray="4 4"/>`;
  const path = pts.map(([t, v], i) => `${i ? "L" : "M"}${x(t).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  return `<svg class="chart" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" role="img" aria-label="Risk score over the call">
    ${line(UNSURE_LINE, "var(--warn)")}${line(SCAM_LINE, "var(--scam)")}
    ${pts.length ? `<path d="${path}" fill="none" stroke="var(--primary)" stroke-width="2.5"/>` +
      pts.map(([t, v]) => `<circle cx="${x(t)}" cy="${y(v)}" r="3" fill="var(--primary)"/>`).join("") :
      `<text x="${w / 2}" y="${h / 2}" text-anchor="middle" fill="var(--muted)" font-size="13">No scores</text>`}</svg>`;
}

// ---------------------------------------------------------------- overlays

function closeOverlay() { const o = $("#overlay"); o.hidden = true; o.innerHTML = ""; }
function showAlert(c) {
  if (c.alertShown) return;
  c.alertShown = true;
  const lit = litSignals(c.callScores);
  const o = $("#overlay");
  o.innerHTML = `<div class="alert" role="alertdialog" aria-label="Scam warning">
    <div style="font-size:22px;font-weight:700">⚠ WARNING</div>
    <div>This call looks like a scam: ${esc(reasonFor(lit, flaggedTexts(c)))}.</div><div>${esc(actionFor(lit))}</div>
    <div class="row"><button class="btn white grow" data-act="hangup">Hang up now</button><button class="btn" data-act="dismiss">Dismiss</button></div></div>`;
  o.hidden = false;
}
function showConsent(c, kind) {
  const lines = reportPreview(c);
  const o = $("#overlay");
  o.innerHTML = `<div class="dialog" role="dialog" aria-label="Share this call">
    <h2 style="font-size:20px">${kind === "scam" ? "Share this scam call?" : "Share this call as a false alarm?"}</h2>
    <p>Helps ScamGuard learn new scams. A team member reads it, labels it, and it may be used to retrain the detectors.</p>
    <p class="small">Sent: the caller's ${lines.length} lines below. We try to remove numbers, amounts, links and names,
      but some can slip through, such as a name said in passing or digits read out as words. Check the text; if you see
      anything private, choose Don't share.</p>
    <p class="small">Not sent: your phone number, the caller's number, anything you said.</p>
    <p class="small">Kept: deleted after 30 days, except lines a reviewer approves as training examples. Those stay
      encrypted with no link to you, so they can't be withdrawn later.</p><hr>
    <div>${lines.map((l) => `<div class="quote">“${esc(l.text)}”</div>`).join("")}</div>
    <div class="actions"><button class="btn" data-act="consent-no">Don't share</button>
      <button class="btn fill" data-act="consent-yes" data-kind="${kind}" ${lines.length ? "" : "disabled"}>Share</button></div></div>`;
  o.hidden = false;
}

// ---------------------------------------------------------------- render + events

const TAB_OF = { home: "home", dashboard: "dashboard", detail: "dashboard", settings: "settings" };
function render() {
  const keepScroll = screen === "call" ? null : $("#screen").scrollTop;
  $("#screen").innerHTML = SCREENS[screen]();
  const tab = TAB_OF[screen];
  $("#tabs").hidden = !tab || screen === "detail";
  document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("on", b.dataset.tab === tab));
  if (screen === "call") { const s = $("#screen"); s.scrollTop = s.scrollHeight; }
  else if (keepScroll != null) $("#screen").scrollTop = keepScroll;
}

document.addEventListener("click", async (e) => {
  const el = e.target.closest("[data-go],[data-act],[data-tab],[data-script],[data-open],[data-filter]");
  if (!el) return;
  if (el.dataset.tab) return go(el.dataset.tab);
  if (el.dataset.go) return go(el.dataset.go);
  if (el.dataset.script) return ring(scripts.find((s) => s.file === el.dataset.script));
  if (el.dataset.open) { openCallId = el.dataset.open; return go("detail"); }
  if (el.dataset.filter) { const [k, v] = el.dataset.filter.split(":"); pickFilter[k] = v || null; return render(); }
  const c = call;
  switch (el.dataset.act) {
    case "scam": case "normal": { const s = randomScript(el.dataset.act); if (s) ring(s); else alertNoScripts(); break; }
    case "check-pasted": {
      const text = $("#pasted").value.trim();
      if (!text) return;
      call = newCall({ file: "pasted", kind: "pasted", lang: null, title: "Pasted text", lines: [{ speaker: "caller", text, delay_ms: 0 }] });
      answer(); break;
    }
    case "answer": answer(); break;
    case "decline": stopTimers(); call = null; go("home"); break;
    case "hangup": if (c) endCall(c, "user_hung_up"); break;
    case "dismiss": closeOverlay(); break;
    case "done": call = null; go("home"); break;
    case "report-scam": case "report-fine": showConsent(c, el.dataset.act === "report-scam" ? "scam" : "fine"); break;
    case "consent-no": closeOverlay(); c.reportResult = "Nothing was shared."; render(); break;
    case "consent-yes":
      closeOverlay(); c.sending = true; render();
      c.reportResult = await sendReport(c, el.dataset.kind); c.sending = false; render(); break;
    case "delete-history":
      if (confirm("Delete my history?\n\nDeletes every saved call in this browser: verdicts, charts and saved sentences. This can't be undone.")) {
        store.set("calls", []); render();
      }
      break;
    case "save-url": store.set("gatewayUrl", $("#url").value.trim() || "api/"); $("#url-result").textContent = "Saved"; break;
    case "test-url": {
      const out = $("#url-result");
      out.textContent = "Testing…";
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
  if (e.target.id === "save-sentence") {
    store.set("saveSentence", e.target.checked);
    if (!e.target.checked) store.set("calls", history().map((c) => ({ ...c, riskiestSentence: null })));
  }
});
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (!$("#overlay").hidden && screen !== "call") return closeOverlay();
  if (screen === "call") return discard();
  if (["pick", "paste", "ringing"].includes(screen)) { stopTimers(); call = null; go("home"); }
  else if (screen === "detail") go("dashboard");
});
function alertNoScripts() { $("#screen").insertAdjacentHTML("beforeend", `<div class="note">No call scripts could be loaded.</div>`); }

render();
loadScripts().then(render);

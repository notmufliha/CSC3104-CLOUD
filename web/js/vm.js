// Port of ios-app UI/CallViewModel.swift: plays a script as a call, scores each caller line (in the cloud, or in the
// browser at levels 2-3), ends the call, saves the history row and the implicit labels; plus every setting, the setup,
// the tour and the Developer view's actions. Screens read `vm` and re-render when it calls notify().
import { T } from "./logic/strings.js";
import * as R from "./logic/risk.js";
import * as Onb from "./logic/onboarding.js";
import * as Pin from "./logic/pin.js";
import { Quiz, weeklyText, isTestCall } from "./logic/features.js";
import { riskiestSeq, LABELS, sampleHistory } from "./logic/dashboard.js";
import { DevLog, DevUnlock, SPEEDS, Playback, diagnostics } from "./logic/developer.js";
import { AppPrefs, normalizeUrl, uuid } from "./data/prefs.js";
import { GatewayClient, updateModels, MODEL_NAMES } from "./data/gateway.js";
import { OnDeviceScorer } from "./ondevice/linearModel.js";
import { OnDeviceCall, VERIFY_TIMEOUT_MS } from "./ondevice/onDeviceCall.js";
import { Alerts } from "./ui/alerts.js";

const DEFAULT_DELAY_MS = 1500;
const FINAL_REVIEW_WAIT_MS = 3000;
const KNOWN = new Set(["SAFE", "UNSURE", "SCAM"]);
export const CLOUD_CHECKED = "checked with cloud", CLOUD_UNAVAILABLE = "cloud check unavailable, checked on this phone";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const withTimeout = (p, ms) => Promise.race([p, sleep(ms).then(() => null)]);

export class ViewModel {
  constructor({ store, scripts, learn, bundledModel }) {
    this.prefs = new AppPrefs();
    this.store = store;
    this.scripts = scripts;
    this.learn = learn;
    this.bundledModel = bundledModel;              // (file) -> Promise<JSON text>
    this.alerts = new Alerts();
    this.devLog = new DevLog();
    this.listeners = new Set();

    const p = this.prefs;
    this.state = R.newCallState();
    this.connection = null;
    this.historyVersion = 0;
    this.devUnlocked = p.developerUnlocked;
    this.devLog.recording = this.devUnlocked;
    this.devOverlay = false;
    this.playbackSpeed = "1x";
    this.devLatest = null;
    this.modelCheck = "";
    this.syncFromPrefs();
    this.pinSet = !!p.pinBlob;
    this.unlockOpen = false;                       // PinUnlock.open: "For family" / Advanced stay open until Home
    this.tourStep = null;
    this.tourSimple = false;
    this.setupChoices = Onb.defaults();
    this.setupStep = "who";
    this.setupIsReplay = false;
    this.nav = { tab: "home", paths: { home: [], dashboard: [], settings: [] }, dir: "none" };
    this.sheet = null;                             // { kind, ... } a sheet over the app
    this.toast = null;

    this.callId = "";
    this.lastOutcomeSeq = 0;
    this.points = [];
    this.callStartedAt = 0;
    this.alertAt = null;
    this.lineShownAt = {};
    this.lastRandom = null;
    this.generation = 0;                           // bumped to cancel a playing call
    this.scorerPromise = null;

    const completed = p.onboardingCompleted;
    const hasSettings = p.hasAnySetting();
    const hasHistory = completed == null && !hasSettings && store.calls().length > 0;
    switch (Onb.atLaunch(completed, hasSettings, hasHistory)) {
      case "showSetup": this.setupTextSize(); this.gate = "setup"; break;
      case "existingUser": Onb.markCompleted(p); this.openApp(); break;
      default: this.openApp();
    }
    if (this.privacyLevel !== "cloud") this.preloadModels();
    this.checkWeeklyReminder();
  }

  syncFromPrefs() {
    const p = this.prefs;
    this.gatewayUrl = p.gatewayUrl;
    this.privacyLevel = p.privacyLevel;
    this.saveSentence = p.saveRiskiestSentence;
    this.helpImprove = p.helpImproveDetection;
    this.readAloud = p.readAloud;
    this.textSize = p.textSize;
    this.showBeCareful = p.showBeCareful;
    this.simpleMode = Onb.AppStyle.simpleAtLaunch(p.simpleMode);
    this.appStyleTip = Onb.AppStyle.showTip(this.simpleMode, p.appStyleTipShown);
    this.family = this.savedFamily();
    this.quizBest = p.quizBest;
    this.weeklyReminder = p.weeklyReminder;
  }

  subscribe(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  notify() { for (const fn of this.listeners) fn(); }
  set(patch) { Object.assign(this.state, patch); this.notify(); }

  // ------------------------------------------------------------ navigation (three tabs, each with its own stack)

  go(tab) {
    if (tab === "home") this.unlockOpen = false;
    this.nav.tab = tab; this.nav.dir = "none"; this.notify();
  }
  push(route) { this.nav.paths[this.nav.tab].push(route); this.nav.dir = "push"; this.notify(); }
  pop() { this.nav.paths[this.nav.tab].pop(); this.nav.dir = "pop"; this.notify(); }
  showPath(tab, path) { this.nav.tab = tab; this.nav.paths[tab] = path; this.nav.dir = "none"; if (tab === "home") this.unlockOpen = false; this.notify(); }
  openSheet(s) { this.sheet = s; this.notify(); }
  closeSheet() { this.sheet = null; this.notify(); }
  showToast(text) {
    this.toast = text; this.notify();
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => { if (this.toast === text) { this.toast = null; this.notify(); } }, 2000);
  }

  // ------------------------------------------------------------ the gateway

  gateway() { try { return new GatewayClient(this.prefs.gatewayUrl, this.devLog); } catch { return null; } }

  // ------------------------------------------------------------ call lifecycle

  randomScript(kind) {
    const pool = this.scripts.filter((s) => s.kind === kind && (this.devUnlocked || !isTestCall(s.fileName)));
    const others = pool.filter((s) => s.fileName !== this.lastRandom);
    const from = others.length ? others : pool;
    const pick = from[Math.floor(Math.random() * from.length)] ?? null;
    this.lastRandom = pick?.fileName;
    return pick;
  }

  ring(script) { this.cancelPlayback(); this.state = { ...R.newCallState(), script, phase: "ringing" }; this.notify(); }
  decline() { this.reset(); }

  reset() {
    this.alerts.stop();
    this.cancelPlayback();
    this.callId = "";
    const wasCall = this.state.phase !== "idle";
    this.state = R.newCallState();
    if (wasCall) { this.nav.tab = "home"; this.nav.paths.home = []; this.nav.dir = "none"; this.unlockOpen = false; }
    this.notify();
  }

  cancelPlayback() { this.generation++; }

  /** "Leave without saving": tells the server (cloud) so it clears the call's memory; no summary, nothing saved. */
  discard() {
    const s = this.state;
    if (s.phase === "inCall") {
      this.cancelPlayback();
      const api = this.gateway();
      if (s.privacyLevel === "cloud" && api) this.sendEnd(api, this.callId, "user_discarded", s);
    }
    this.reset();
  }

  answer() {
    const script = this.state.script;
    if (!script) return;
    this.cancelPlayback();
    const gen = this.generation;
    this.callId = uuid();
    this.lastOutcomeSeq = 0;
    this.points = [];
    this.alertAt = null;
    this.callStartedAt = Date.now();
    this.lineShownAt = {};
    const level = this.prefs.privacyLevel;
    this.devLog.startCall(this.callId, level);
    this.devLatest = null;
    Object.assign(this.state, { phase: "inCall", privacyLevel: level, speedTag: SPEEDS[this.playbackSpeed].tag });
    this.notify();
    const id = this.callId;
    const speed = this.playbackSpeed;
    const api = level === "on_device" ? null : this.gateway();
    if (level === "cloud" && !api) this.set({ gatewayError: "Bad server address. Check Settings." });
    const onPhone = level !== "cloud";
    const loading = onPhone ? this.scorer().catch(() => null) : null;
    let previous = Promise.resolve();
    const tasks = [];
    (async () => {
      for (const [i, line] of script.lines.entries()) {
        await sleep(Playback.gap(line.delay_ms ?? DEFAULT_DELAY_MS, speed));
        if (gen !== this.generation) return;
        const seq = i + 1;
        const text = line.text ?? "";
        const caller = isCaller(line);
        this.lineShownAt[seq] = Date.now() - this.callStartedAt;
        const waiting = caller && (onPhone || !!api);
        this.state.lines = [...this.state.lines, { seq, isCaller: caller, text, flags: [], pending: waiting, failed: caller && !waiting, cloudNote: null }];
        this.notify();
        if (!waiting) continue;
        if (!onPhone) tasks.push(this.send(api, id, seq, text, script.lang, gen));
        else {
          const before = previous;
          previous = (async () => {
            await before;
            if (gen !== this.generation) return;
            const t0 = performance.now();
            const local = await loading;
            if (!local) return this.markError(id, seq, "On-device models could not be loaded");
            if (!OnDeviceCall.isEnglish(script.lang, text)) return this.markError(id, seq, "On-device checks are English only");
            if (level === "hybrid") {
              const a = await local.answerHybrid(id, seq, text, (lines) => this.verifyInCloud(api, lines));
              if (gen !== this.generation) return;
              this.applyAnswer(id, seq, a.response);
              if (a.second) this.markCloud(seq, a.second.bertScore != null ? CLOUD_CHECKED : CLOUD_UNAVAILABLE);
              this.devRecord(id, seq, a.response, !a.second ? "phone" : a.second.bertScore != null ? "phone + cloud BERT" : "phone (cloud BERT unavailable)", performance.now() - t0);
            } else {
              const r = local.answer(id, seq, text);
              this.applyAnswer(id, seq, r);
              this.devRecord(id, seq, r, "phone", performance.now() - t0);
            }
          })();
          tasks.push(previous);
        }
      }
      await withTimeout(Promise.allSettled(tasks), 5000);           // let the last answers arrive
      if (gen !== this.generation) return;
      this.generation++;                                              // nothing may change the call after this point
      await sleep(Playback.gap(1500, speed));
      if (this.callId !== id) return;
      this.endCall("script_finished");
    })();
  }

  /** A new OnDeviceCall per call (its rules remember the call), on the shared scorer. */
  async scorer() {
    const sc = await this.loadScorer();
    return new OnDeviceCall(sc);
  }
  loadScorer() {
    if (!this.scorerPromise) {
      this.scorerPromise = OnDeviceScorer.load(async (file) => (await this.store.model(file)) ?? this.bundledModel(file))
        .catch((e) => { this.scorerPromise = null; throw e; });
    }
    return this.scorerPromise;
  }
  preloadModels() { this.loadScorer().catch(() => {}); }

  async verifyInCloud(api, maskedLines) {
    if (!api || !maskedLines.length) return null;
    try {
      const r = await withTimeout(api.verify({ lines: maskedLines, lang: "en" }), VERIFY_TIMEOUT_MS);
      if (r?.body?.model_version) this.devLog.setModelVersions({ bert: r.body.model_version });
      const s = r?.ok ? r.body?.score : null;
      return typeof s === "number" && s >= 0 && s <= 1 ? s : null;
    } catch { return null; }
  }

  hangUp() {
    this.alerts.stop();
    this.state.warningOpen = false;
    this.cancelPlayback();
    this.endCall("user_hung_up");
  }

  async send(api, id, seq, text, lang, gen) {
    const t0 = performance.now();
    let r;
    try { r = await api.utterance({ call_id: id, seq, speaker: "caller", text, ...(lang ? { lang } : {}) }); }
    catch { if (gen === this.generation) this.markError(id, seq, "Could not reach the server"); return; }
    if (gen !== this.generation) return;                  // the call moved on (hung up, or the end began)
    if (!r.ok) return this.markError(id, seq, `Server error ${r.status}`);
    if (!r.body) return this.markError(id, seq, "Empty answer from server");
    this.applyAnswer(id, seq, r.body);
    if (r.body.ignored !== true) this.devRecord(id, seq, r.body, "cloud", performance.now() - t0);
  }

  devRecord(id, seq, b, place, latencyMs) {
    if (!this.devLog.recording || id !== this.callId) return;
    const sc = b.scores || {};
    this.devLog.addLine(id, { seq, tMs: this.lineShownAt[seq] ?? 0, urgency: sc.urgency ?? null, money: sc.money ?? null,
      impersonation: sc.impersonation ?? null, combined: b.combined_score ?? null, bertScore: b.bert_score ?? null,
      bertStatus: b.bert_status ?? null, rule: b.rule ?? null, liveAlert: b.live_alert ?? null, verdict: b.verdict ?? null,
      place, latencyMs: Math.round(latencyMs) });
    if (b.model_versions) this.devLog.setModelVersions(b.model_versions);
    this.devLatest = this.devLog.latestLine(id);
    this.notify();
  }

  /** One caller line's answer -> bubble flags, chart point, bar, red warning. Cloud and browser answers alike. */
  applyAnswer(id, seq, body) {
    const s = this.state;
    if (id !== this.callId || s.phase !== "inCall") return;
    if (body.ignored === true) return this.updateLine(seq, { pending: false });
    if (!KNOWN.has(body.verdict)) return this.markError(id, seq, "Server gave no verdict");
    const score = (k) => body.scores?.[k] ?? null;
    const flags = R.SIGNALS.filter((k) => (score(k) ?? 0) >= R.SIGNAL_ON);
    if (body.duplicate !== true) {
      this.points.push({ callId: id, seq, secondsIntoCall: (Date.now() - this.callStartedAt) / 1000, combined: body.combined_score ?? null,
        urgency: score("urgency"), money: score("money"), impersonation: score("impersonation") });
    }
    const newest = seq >= this.lastOutcomeSeq;
    if (newest) this.lastOutcomeSeq = seq;
    this.updateLine(seq, { pending: false, flags }, false);
    if (newest) {
      s.verdict = body.verdict;
      s.combinedScore = body.combined_score ?? s.combinedScore;
      s.callScores = body.recent_scores ?? body.call_scores ?? s.callScores;
      s.serverAction = body.action ?? s.serverAction;
      s.gatewayError = null;
    }
    if (s.verdict === "SCAM" && !s.scamAlert) {
      this.alertAt = Date.now();
      s.scamAlert = true;
      s.warningOpen = true;
      this.alerts.red(this.prefs.readAloud ? R.spokenWarning(R.callWarningKind(s)) : null);
    } else if (s.verdict === "UNSURE" && !s.scamAlert && !s.carefulAlerted) {
      s.carefulAlerted = true;
      if (this.prefs.showBeCareful) this.alerts.yellow();
    }
    this.notify();
  }

  markError(id, seq, msg) {
    if (id !== this.callId || this.state.phase !== "inCall") return;
    const newest = seq >= this.lastOutcomeSeq;
    if (newest) this.lastOutcomeSeq = seq;
    this.updateLine(seq, { pending: false, failed: true }, false);
    if (newest) this.state.gatewayError = msg;
    this.notify();
  }
  markCloud(seq, note) { this.updateLine(seq, { cloudNote: note }); }
  updateLine(seq, patch, notify = true) {
    this.state.lines = this.state.lines.map((l) => (l.seq === seq ? { ...l, ...patch } : l));
    if (notify) this.notify();
  }

  /** The call is over: "Checking the whole call once more…" up to 3 s (cloud), then the summary and the history row. */
  endCall(endedBy) {
    const s = this.state;
    if (s.phase !== "inCall") return;
    this.generation++;
    s.phase = "ended";
    s.reviewing = true;
    s.lines = s.lines.map((l) => (l.pending ? { ...l, pending: false, failed: true } : l));
    this.notify();
    const id = this.callId, snapshot = { ...s }, pts = [...this.points], startedAt = this.callStartedAt, endedAt = Date.now();
    const onPhone = s.privacyLevel !== "cloud";
    const api = onPhone ? null : this.gateway();
    const answer = api ? this.sendEnd(api, id, endedBy, s) : null;
    const firstAlert = this.alertAt;
    (async () => {
      const resp = answer ? await withTimeout(answer, FINAL_REVIEW_WAIT_MS) : null;
      const review = resp ? R.FinalReview.from(resp) : onPhone ? R.FinalReview.onDevice() : R.FinalReview.unavailable();
      this.devLog.endCall(id, endedBy, resp ? { finalVerdict: resp.final_verdict, callScore: resp.call_score, review: resp.review, topWindow: resp.top_window_seqs || [] } : null);
      if (this.callId === id) { this.state.reviewing = false; this.state.finalReview = review; }
      const done = { ...snapshot, reviewing: false, finalReview: review };
      this.saveHistory(id, done, pts, startedAt, endedAt);
      this.recordLabels(id, done, LABELS.atEnd(endedBy === "user_hung_up", firstAlert, endedAt, review));
      this.notify();
    })();
  }

  sendEnd(api, id, endedBy, s) {
    const callerLines = s.lines.filter((l) => l.isCaller && l.text.trim()).map((l) => ({ seq: l.seq, text: l.text }));
    if (!id) return Promise.resolve(null);
    return api.endCall({ call_id: id, ended_by: endedBy, last_seq: Math.max(0, ...s.lines.map((l) => l.seq)), caller_lines: callerLines,
      ...(s.script?.lang ? { lang: s.script.lang } : {}), event: "end" }).then((r) => (r.ok ? r.body : null)).catch(() => null);
  }

  saveHistory(id, s, pts, startedAt, endedAt) {
    if (!id) return;
    const verdict = R.historyVerdict(R.summaryLevel(s));
    let sentence = null;
    if (this.prefs.saveRiskiestSentence && (verdict === "SCAM" || verdict === "UNSURE")) {
      const seq = riskiestSeq(pts);
      if (seq != null) sentence = s.lines.find((l) => l.seq === seq)?.text ?? null;
    }
    const fired = new Set([...R.callLit(s), ...s.lines.flatMap((l) => l.flags)]);
    this.store.save({ id, startedAt, endedAt, finalVerdict: verdict, liveVerdict: R.historyVerdict(R.callLevel(s)), topReason: R.historyReason(s),
      callScore: s.combinedScore, signalsFired: R.SIGNALS.filter((x) => fired.has(x)), review: s.finalReview ? R.FinalReview.type(s.finalReview) : "unavailable",
      riskiestSentence: sentence, privacyLevel: s.privacyLevel, reasonCode: R.callReasonCode(s) }, pts);
    this.historyVersion++;
  }

  recordLabels(id, s, labels) {
    if (!labels.length) return;
    this.store.recordLabels(id, labels, s.lines.filter((l) => l.isCaller).map((l) => [l.seq, l.text]), this.prefs.helpImproveDetection);
    this.historyVersion++;
  }

  // ------------------------------------------------------------ warning actions

  closeWarning() { this.alerts.stop(); this.state.warningOpen = false; this.notify(); }
  leaveCallForHelp() { if (this.state.phase === "inCall") this.hangUp(); else this.closeWarning(); }

  // ------------------------------------------------------------ settings

  setTextSize(size) { this.prefs.textSize = size; this.prefs.textSizeChosen = true; this.textSize = size; this.notify(); }
  previewTextSize(size) { this.textSize = size; this.notify(); }
  setAppStyle(simple) {
    Onb.AppStyle.switchTo(simple, this.prefs);
    this.simpleMode = simple; this.textSize = this.prefs.textSize; this.readAloud = this.prefs.readAloud; this.appStyleTip = false;
    this.notify();
  }
  appStyleNeedsPin(toSimple) { return Onb.AppStyle.needsPin(this.simpleMode, toSimple, this.pinSet); }
  dismissAppStyleTip() { this.prefs.appStyleTipShown = true; this.appStyleTip = false; this.notify(); }

  async verifyPin(pin) {
    const blob = this.prefs.pinBlob;
    if (!blob || blob.length !== 96) return false;
    return Pin.matches(pin, Pin.unhex(blob.slice(0, 32)), Pin.unhex(blob.slice(32)));
  }
  async setPin(pin) {
    if (!Pin.isValid(pin)) return false;
    const salt = Pin.newSalt();
    this.prefs.pinBlob = Pin.hex(salt) + Pin.hex(await Pin.hash(pin, salt));
    this.pinSet = true; this.notify();
    return true;
  }
  clearPin() { this.prefs.pinBlob = null; this.pinSet = false; this.notify(); }

  setReadAloud(on) { this.prefs.readAloud = on; this.prefs.readAloudChosen = true; this.readAloud = on; this.notify(); }
  setShowBeCareful(on) { this.prefs.showBeCareful = on; this.showBeCareful = on; this.notify(); }
  setFamily(name, phone) { this.prefs.familyName = name; this.prefs.familyPhone = phone; this.family = this.savedFamily(); this.notify(); }
  savedFamily() {
    const phone = this.prefs.familyPhone;
    return phone ? { name: this.prefs.familyName || phone, phone } : null;
  }
  setPrivacyLevel(level) {
    this.prefs.privacyLevel = level; this.privacyLevel = this.prefs.privacyLevel;
    if (level !== "cloud") this.preloadModels();
    if (level !== "on_device" && this.connection == null) this.refreshConnection();
    this.notify();
  }
  setSaveSentence(on) { this.prefs.saveRiskiestSentence = on; this.saveSentence = on; if (!on) { this.store.forgetSentences(); this.historyVersion++; } this.notify(); }
  setHelpImprove(on) { this.prefs.helpImproveDetection = on; this.helpImprove = on; if (!on) { this.store.forgetLabelSentences(); this.historyVersion++; } this.notify(); }
  recordQuiz(score) { this.prefs.quizBest = Quiz.best(this.prefs.quizBest, score); this.quizBest = this.prefs.quizBest; this.notify(); }
  weeklySummary() { return weeklyText(this.store.calls(), Date.now()); }

  /** The weekly reminder: the browser's Notification permission is asked only when it is turned on. */
  async setWeeklyReminder(on) {
    if (!on) { this.prefs.weeklyReminder = false; this.weeklyReminder = false; this.notify(); return true; }
    let ok = false;
    try {
      if (globalThis.Notification && globalThis.isSecureContext) {
        ok = Notification.permission === "granted" || (await Notification.requestPermission()) === "granted";
      }
    } catch { ok = false; }
    this.prefs.weeklyReminder = ok; this.weeklyReminder = ok; this.notify();
    return ok;
  }
  /** At start (and when the page comes back): on Sunday after 10:00, one note a week, while the page is open. */
  checkWeeklyReminder() {
    try {
      if (!this.prefs.weeklyReminder || !globalThis.Notification || Notification.permission !== "granted") return;
      const now = new Date();
      if (now.getDay() !== 0 || now.getHours() < 10) return;
      if (Date.now() - (this.prefs.weeklyReminderLast || 0) < 6 * 86_400_000) return;
      this.prefs.weeklyReminderLast = Date.now();
      new Notification(T("notif_weekly_title"), { body: T("notif_weekly_text"), icon: "icons/icon-192.png" });
    } catch { /* not allowed here */ }
  }

  // ------------------------------------------------------------ connection

  saveGatewayUrl(url) {
    if (normalizeUrl(url) !== this.prefs.gatewayUrl) this.connection = null;
    this.prefs.gatewayUrl = url; this.gatewayUrl = this.prefs.gatewayUrl; this.notify();
  }
  async testConnection(url) {
    const msg = await this.checkConnection(url);
    if (normalizeUrl(url) === this.prefs.gatewayUrl) this.connection = msg.startsWith("Connected");
    this.devLog.setHealth({ ok: msg.startsWith("Connected"), message: `${msg} (${normalizeUrl(url)})`, timeMs: Date.now() });
    this.notify();
    return msg;
  }
  async refreshConnection() {
    if (this.connectionFixed || this.prefs.privacyLevel === "on_device") return;
    await this.testConnection(this.prefs.gatewayUrl);
  }
  async checkConnection(url) {
    let api;
    try { api = new GatewayClient(url, this.devLog); } catch { return "Not a valid address"; }
    try {
      const r = await api.health();
      if (!r.ok) return `Server answered ${r.status}` + (r.status === 404 ? ": check the address (Kubernetes HTTPS addresses end in /api/)" : "");
      if (r.body?.status === "ok") return "Connected: ok";
      return "Something answered, but not the ScamGuard gateway: check the address";
    } catch (e) {
      if (e.timeout) return "Could not connect: timed out (is the server running, and is this the laptop's address?)";
      return "Could not connect (server down, wrong address, a certificate this browser doesn't trust, or a server that doesn't allow this page: CORS)";
    }
  }

  deleteHistory() { this.store.deleteHistory(); this.historyVersion++; this.devLog.clear(); this.devLatest = null; this.notify(); }

  // ------------------------------------------------------------ Developer view

  developerTap(count) {
    const t = DevUnlock.tap(count, this.devUnlocked);
    if (t.outcome === "unlocked") this.setDeveloperUnlocked(true);
    return t;
  }
  setDeveloperUnlocked(on) {
    this.prefs.developerUnlocked = on; this.devUnlocked = on; this.devLog.recording = on;
    if (!on) { this.devLog.clear(); this.devOverlay = false; this.playbackSpeed = "1x"; this.devLatest = null; }
    this.notify();
  }
  setDevOverlay(on) { if (this.devUnlocked) { this.devOverlay = on; this.notify(); } }
  setPlaybackSpeed(s) { if (this.devUnlocked) { this.playbackSpeed = s; this.notify(); } }
  loadSampleHistory() {
    const today = new Date(); today.setHours(0, 0, 0, 0);
    this.store.insertSample(sampleHistory(Date.now(), today.getTime()));
    this.historyVersion++; this.notify();
  }
  deleteSampleHistory() { this.store.deleteSample(); this.historyVersion++; this.notify(); }

  devConnectionRows() {
    const h = this.devLog.lastHealth;
    return [
      ["gateway URL", this.prefs.gatewayUrl],
      ["last /healthz", h ? `${h.ok ? "ok" : "failed"} at ${new Date(h.timeMs).toLocaleTimeString("en-GB", { hour12: false })}: ${h.message}` : "not checked since unlocking"],
      ["privacy level", this.prefs.privacyLevel],
      ["app version", "1.0 (web)"],
      ["platform", `${navigator.userAgent.replace(/^Mozilla\/5\.0 /, "")}`],
      ["page", `${location.origin} (${globalThis.isSecureContext ? "secure context" : "not a secure context: no clipboard / share / notifications"})`],
    ];
  }
  devModelRows() {
    const out = [];
    const m = this.bundledManifest?.models || {};
    for (const name of Object.keys(m).sort()) out.push([`bundled ${name}`, `${m[name].version ?? "?"}  sha256 ${m[name].sha256 ?? "?"}`]);
    const inst = this.prefs.installedModels;
    const names = Object.keys(inst).sort();
    if (!names.length) out.push(["downloaded", "none"]);
    for (const n of names) out.push([`downloaded ${n}`, `${inst[n].version}  sha256 ${inst[n].sha256}`]);
    out.push(["MobileBERT", "not in the web version: level 3 (On-device) uses the three small models only"]);
    const v = this.devLog.versions;
    const server = Object.keys(v).sort().map((k) => `${k} ${v[k]}`).join(", ");
    out.push(["server model_versions", server || "none yet (from the last reply)"]);
    return out;
  }
  devDiagnostics() { return diagnostics(this.devConnectionRows(), this.devModelRows(), this.devLog.call); }
  async checkModelUpdates() {
    this.modelCheck = "Checking for model updates…"; this.notify();
    const msg = await updateModels(this.prefs.gatewayUrl, this.prefs, this.store);
    this.modelCheck = msg;
    if (msg.startsWith("Updated")) this.scorerPromise = null;
    if (this.prefs.privacyLevel !== "cloud") this.preloadModels();
    this.notify();
    return msg;
  }

  /** "Reset app to first launch": history, settings, PIN, downloaded models, logs; then "Who will use this phone?". */
  async resetApp() {
    if (!this.devUnlocked) return;
    this.alerts.stop();
    this.cancelPlayback();
    this.callId = "";
    this.state = R.newCallState();
    await this.store.deleteHistory();
    await this.store.clearModels();
    this.prefs.clearAll();
    this.devLog.clear(); this.devLog.recording = false; this.devUnlocked = false; this.devOverlay = false; this.playbackSpeed = "1x";
    this.scorerPromise = null;
    this.connection = null; this.connectionFixed = false;
    this.syncFromPrefs();
    this.pinSet = false;
    this.modelCheck = "";
    this.historyVersion++;
    this.setupChoices = Onb.defaults(); this.setupStep = "who"; this.setupIsReplay = false;
    this.setupTextSize();
    this.tourStep = null;
    this.nav = { tab: "home", paths: { home: [], dashboard: [], settings: [] }, dir: "none" };
    this.sheet = null;
    this.gate = "setup";
    this.notify();
  }

  // ------------------------------------------------------------ setup + tour

  openApp() {
    this.gate = "app";
    if (this.prefs.privacyLevel !== "on_device") this.refreshConnection();
  }
  setSetupStep(step) { this.setupStep = step; this.notify(); }
  updateSetup(fn) { this.setupChoices = fn({ ...this.setupChoices }); this.notify(); }

  async finishSetup(startTour) {
    const c = this.setupChoices;
    const before = this.prefs.gatewayUrl;
    Onb.apply(c, this.prefs);
    if (this.prefs.gatewayUrl !== before) this.connection = null;
    this.syncFromPrefs();
    this.appStyleTip = false;
    if (Pin.isValid(c.pin)) await this.setPin(c.pin);
    if (!c.saveSentence) this.store.forgetSentences();
    if (!c.helpImprove) this.store.forgetLabelSentences();
    this.historyVersion++;
    if (c.privacy !== "cloud") this.preloadModels();
    const wasReplay = this.setupIsReplay;
    this.setupIsReplay = false;
    if (wasReplay) this.gate = "app"; else this.openApp();
    if (startTour) this.startTour(); else this.tourStep = null;
    this.notify();
  }
  setupTextSize() { if (!this.prefs.textSizeChosen) this.textSize = Onb.AppStyle.textSize(true); }
  leaveSetup() { this.setupIsReplay = false; this.textSize = this.prefs.textSize; this.gate = "app"; this.notify(); }
  runSetupAgain() {
    this.setupChoices = Onb.fromSaved(this.prefs); this.setupStep = "who"; this.setupTextSize();
    this.setupIsReplay = true; this.tourStep = null; this.gate = "setup"; this.notify();
  }
  resetOnboarding() {
    Onb.reset(this.prefs);
    this.setupChoices = Onb.defaults(); this.setupStep = "who"; this.setupTextSize();
    this.setupIsReplay = false; this.tourStep = null; this.gate = "setup"; this.notify();
  }

  startTour(at = 0) {
    this.tourSimple = this.simpleMode;
    this.tourStep = Math.min(Math.max(at, 0), Onb.Tour.steps(this.tourSimple).length - 1);
    this.showTourPlace();
  }
  tourNext() {
    if (this.tourStep == null) return;
    this.tourStep = Onb.Tour.isLast(this.tourStep, this.tourSimple) ? null : this.tourStep + 1;
    this.showTourPlace();
  }
  endTour() { this.tourStep = null; this.notify(); }
  showTourPlace() {
    if (this.tourStep != null) {
      const place = Onb.Tour.steps(this.tourSimple)[this.tourStep].place;
      const tab = place === "call" ? "home" : place;
      this.nav.tab = tab; this.nav.paths[tab] = []; this.nav.dir = "none";
    } else { this.nav.tab = "home"; this.nav.paths.home = []; }
    this.notify();
  }

  /** The pretend call the tour (and the demo screens) show. Nothing is sent and nothing is saved. */
  static tourDemoCall(redAlert) {
    return { ...R.newCallState(), phase: "inCall", verdict: "SCAM", combinedScore: 0.79, callScores: { urgency: 0.47, money: 1, impersonation: 0.9 },
      lines: [
        { seq: 1, isCaller: false, text: T("demo_line_hello"), flags: [], pending: false, failed: false },
        { seq: 2, isCaller: true, text: T("demo_line_officer"), flags: ["impersonation"], pending: false, failed: false },
        { seq: 3, isCaller: true, text: T("demo_line_otp"), flags: ["money"], pending: false, failed: false },
      ], scamAlert: redAlert, warningOpen: redAlert };
  }

  // ------------------------------------------------------------ demo launch options (screenshots, end-to-end tests)

  demoWarning(red) {
    this.cancelPlayback(); this.callId = "";
    const s = ViewModel.tourDemoCall(red);
    s.privacyLevel = this.prefs.privacyLevel;
    if (!red) Object.assign(s, carefulDemo());
    this.state = s; this.notify();
  }
  demoSummary(red) {
    this.cancelPlayback(); this.callId = "";
    const s = ViewModel.tourDemoCall(false);
    s.phase = "ended"; s.privacyLevel = this.prefs.privacyLevel;
    if (red) { s.scamAlert = true; s.finalReview = { finalVerdict: "SCAM", reason: "", reviewed: true, topWindowSeqs: [2, 3], answered: true, callScore: 0.97, onDevice: false }; }
    else Object.assign(s, carefulDemo(), { finalReview: { finalVerdict: "UNSURE", reason: "", reviewed: true, topWindowSeqs: [2], answered: true, callScore: 0.55, onDevice: false } });
    this.state = s; this.notify();
  }
  demoConnection(ok) { this.connection = ok; this.connectionFixed = true; this.notify(); }
}

function carefulDemo() {
  return { verdict: "UNSURE", combinedScore: 0.52, callScores: { urgency: 0.2, money: 0.62, impersonation: 0.1 },
    lines: [{ seq: 1, isCaller: false, text: T("demo_line_hello"), flags: [], pending: false, failed: false },
      { seq: 2, isCaller: true, text: T("demo_line_parcel"), flags: ["money"], pending: false, failed: false }] };
}

export const isCaller = (l) => ["caller", "speaker 1", "speaker1", "speaker_1"].includes((l.speaker || "").trim().toLowerCase());
export { MODEL_NAMES };

// Start-up: load the apps' wording, scripts and Learn content (static files in assets/), open the history, build the
// view model, apply the demo launch options and draw the app.
import { installStrings } from "./logic/strings.js";
import { CallStore } from "./data/db.js";
import { ViewModel } from "./vm.js";
import { mount } from "./ui/app.js";
import { applyAppearance } from "./ui/theme.js";

const getJson = async (p) => (await fetch(p, { cache: "no-cache" })).json();
const getText = async (p) => (await fetch(p, { cache: "no-cache" })).text();

/** A script file name -> what it is: scam_en_11_bank_otp.json -> scam, en, "Bank otp". */
function parseScript(fileName, lines) {
  const parts = fileName.replace(/\.json$/, "").split("_");
  const title = parts.slice(3).join(" ");
  return { fileName, kind: (parts[0] || "unknown").toLowerCase(), lang: ["en", "zh"].includes(parts[1]) ? parts[1] : null,
    title: title ? title[0].toUpperCase() + title.slice(1) : fileName, lines: lines.filter((l) => l.text && l.text.trim()) };
}

async function start() {
  applyAppearance();
  const [strings, names, learn, manifest] = await Promise.all([
    getJson("assets/strings.json"), getJson("assets/scripts/index.json"), getJson("assets/learn.json").catch(() => ({ types: [], quiz: [] })),
    getJson("assets/models/manifest.json").catch(() => null)]);
  installStrings(strings);
  const scripts = (await Promise.all(names.map(async (n) => { try { return parseScript(n, await getJson("assets/scripts/" + n)); } catch { return null; } }))).filter(Boolean);
  const store = await new CallStore().init();
  const vm = new ViewModel({ store, scripts, learn, bundledModel: (file) => getText("assets/models/" + file) });
  vm.bundledManifest = manifest;
  globalThis.scamguard = vm;                                  // for the end-to-end tests and the console
  demo(vm, new URLSearchParams(location.search));
  mount(vm, document.getElementById("root"));
  document.getElementById("boot")?.remove();
  addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") vm.checkWeeklyReminder(); });
}

/**
 * Demo launch options (the iOS app's -demo* launch arguments), for screenshots and the end-to-end tests:
 *   ?demoReset=1 ?demoSkipSetup=1 ?demoSetup=who|welcome|...&demoPath=standard|simple|helper ?demoTour=0-6
 *   ?demoSimple=1|0 ?demoSize=normal|large|xlarge ?demoFamily=Mei%20Ling|91234567 ?demoPin=2468|off ?demoConnection=ok|fail
 *   ?demoLevel=cloud|hybrid|on_device ?demoDev=1 ?demoSpeed=4x ?demoOverlay=1 ?demoLoadSample=1 ?demoDeleteHistory=1
 *   ?demoTab=dashboard|settings ?demoDashboard=insights|history|weekly ?demoSettings=privacy|data|help|...
 *   ?demoLearn=list|quiz|<id> &demoQuiz=answered|result ?demoWarning=red|yellow ?demoSummary=red|yellow ?demoScript=<file>
 */
function demo(vm, q) {
  if (![...q.keys()].some((k) => k.startsWith("demo"))) return;
  const p = vm.prefs;
  if (q.get("demoReset") === "1") { p.clearAll(); vm.store.deleteHistory(); vm.syncFromPrefs(); vm.pinSet = false; vm.resetOnboarding(); }
  if (q.get("demoSkipSetup") === "1" && vm.gate === "setup") vm.finishSetup(false);
  if (q.has("demoSimple")) vm.setAppStyle(q.get("demoSimple") === "1");
  if (q.has("demoSize")) { p.textSize = q.get("demoSize"); vm.textSize = p.textSize; }
  if (q.has("demoFamily")) { const [n, ph] = q.get("demoFamily").split("|"); vm.setFamily(n || "", ph || ""); }
  if (q.has("demoPin")) { if (q.get("demoPin") === "off") vm.clearPin(); else vm.setPin(q.get("demoPin")); }
  if (q.has("demoConnection")) vm.demoConnection(q.get("demoConnection") === "ok");
  if (q.has("demoLevel")) vm.setPrivacyLevel(q.get("demoLevel"));
  if (q.has("demoDev")) vm.setDeveloperUnlocked(q.get("demoDev") === "1");
  if (q.has("demoSpeed")) vm.setPlaybackSpeed(q.get("demoSpeed"));
  if (q.get("demoOverlay") === "1") vm.setDevOverlay(true);
  if (q.get("demoDeleteHistory") === "1") vm.deleteHistory();
  if (q.get("demoLoadSample") === "1") vm.loadSampleHistory();
  if (q.has("demoSetup")) {
    vm.setupChoices = { ...vm.setupChoices };
    const path = q.get("demoPath");
    if (path) { const ch = { ...vm.setupChoices, path, textSize: path === "standard" ? "normal" : "large", readAloud: path !== "standard" }; vm.setupChoices = ch; vm.previewTextSize(ch.textSize); }
    vm.setupStep = q.get("demoSetup"); vm.gate = "setup";
  }
  if (q.has("demoTab")) vm.go(q.get("demoTab"));
  const dash = q.get("demoDashboard");
  if (dash) vm.showPath("dashboard", [{ name: dash }]);
  const settings = q.get("demoSettings");
  if (settings) vm.showPath("settings", [{ name: settings }]);
  const learn = q.get("demoLearn");
  if (learn === "list") vm.showPath("home", [{ name: "learn" }]);
  else if (learn === "quiz") vm.showPath("home", [{ name: "learn" }, { name: "quiz", demo: q.get("demoQuiz") }]);
  else if (learn) vm.showPath("home", [{ name: "learn" }, { name: "learnType", id: learn }]);
  if (q.has("demoWarning")) vm.demoWarning(q.get("demoWarning") === "red");
  if (q.has("demoSummary")) vm.demoSummary(q.get("demoSummary") === "red");
  if (q.has("demoScript")) { const s = vm.scripts.find((x) => x.fileName === q.get("demoScript")); if (s) { vm.ring(s); if (q.get("demoRingOnly") !== "1") vm.answer(); } }
  if (q.has("demoTour")) { if (vm.gate === "setup") vm.finishSetup(false); vm.startTour(parseInt(q.get("demoTour"), 10)); }
  vm.nav.dir = "none";
}

start().catch((e) => {
  document.getElementById("boot").textContent = "ScamGuard could not start: " + e.message;
  console.error(e);
});

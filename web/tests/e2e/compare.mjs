// Visual comparison: the same screens on the iOS Simulator and on the web (in the phone frame), light + dark,
// default + largest text, side by side in web/build/compare/ (not committed). Both apps get the same demo launch
// options (iOS: -demo* arguments; web: ?demo* query), so they show the same data.
//   node tests/e2e/compare.mjs [--no-ios] [--only name,name] [--base http://127.0.0.1:8088/]
// Needs: the compose stack (for the web), Xcode's simctl with the ScamGuard debug app on the booted Simulator (for iOS).
import { chromium } from "playwright-core";
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, existsSync, readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const WEB = join(dirname(fileURLToPath(import.meta.url)), "../..");
const OUT = join(WEB, "build/compare");
const args = process.argv.slice(2);
const BASE = args.includes("--base") ? args[args.indexOf("--base") + 1] : "http://127.0.0.1:8088/";
const ONLY = args.includes("--only") ? args[args.indexOf("--only") + 1].split(",") : null;
const IOS = !args.includes("--no-ios");
const BUNDLE = "com.rhaylene.ScamGuard";
const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

/** name, iOS launch arguments, web query, simple mode. */
export const SCREENS = [
  ["who", ["-demoSetup", "who"], "demoSetup=who", false, { setup: true }],
  ["welcome", ["-demoSetup", "welcome", "-demoPath", "standard"], "demoSetup=welcome&demoPath=standard", false, { setup: true }],
  ["home", [], "", false],
  ["home_simple", [], "", true],
  ["in_call_careful", ["-demoWarning", "yellow"], "demoWarning=yellow", false],
  ["red_warning", ["-demoWarning", "red"], "demoWarning=red", false],
  ["summary", ["-demoSummary", "red"], "demoSummary=red", false],
  ["learn", ["-demoLearn", "list"], "demoLearn=list", false],
  ["scam_type", ["-demoLearn", "bank_code"], "demoLearn=bank_code", false],
  ["quiz_result", ["-demoLearn", "quiz", "-demoQuiz", "result"], "demoLearn=quiz&demoQuiz=result", false],
  ["dashboard", ["-demoDeleteHistory", "YES", "-demoLoadSample", "YES", "-demoTab", "dashboard"], "demoDeleteHistory=1&demoLoadSample=1&demoTab=dashboard", false],
  ["dashboard_empty", ["-demoDeleteHistory", "YES", "-demoTab", "dashboard"], "demoDeleteHistory=1&demoTab=dashboard", false],
  ["weekly", ["-demoDeleteHistory", "YES", "-demoLoadSample", "YES", "-demoDashboard", "weekly"], "demoDeleteHistory=1&demoLoadSample=1&demoDashboard=weekly", false],
  ["settings", ["-demoTab", "settings"], "demoTab=settings", false],
  ["settings_simple", ["-demoTab", "settings"], "demoTab=settings", true],
  ["help", ["-demoSettings", "help"], "demoSettings=help", false],
  ["family_contact", ["-demoSettings", "family"], "demoSettings=family", false],
  ["developer", ["-demoDev", "YES", "-demoSettings", "developer"], "demoDev=1&demoSettings=developer", false],
];
const ALL_VARIANTS = [["light", "default"], ["dark", "default"], ["light", "largest"], ["dark", "largest"]];
const VARIANTS = process.argv.includes("--variants") ? ALL_VARIANTS.filter(([t, s]) => process.argv[process.argv.indexOf("--variants") + 1].split(",").includes(`${t}_${s}`)) : ALL_VARIANTS;

const sh = (cmd, a) => execFileSync(cmd, a, { stdio: ["ignore", "pipe", "pipe"] }).toString();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function iosShot(name, iosArgs, simple, dark, largest, file, setup) {
  sh("xcrun", ["simctl", "ui", "booted", "appearance", dark ? "dark" : "light"]);
  sh("xcrun", ["simctl", "ui", "booted", "content_size", "large"]);
  const size = largest ? "xlarge" : simple ? "large" : "normal";
  const base = setup ? ["-text_size_chosen", "YES", "-text_size", size] : ["-onboarding_completed", "YES", "-onboarding_version", "2", "-app_style_tip_shown", "YES",
    "-simple_mode", simple ? "YES" : "NO", "-text_size", size];
  try { sh("xcrun", ["simctl", "terminate", "booted", BUNDLE]); } catch { /* not running */ }
  sh("xcrun", ["simctl", "launch", "booted", BUNDLE, ...base, "-demoPin", "off", "-demoConnection", "ok", "-demoFamily", "Mei Ling|91234567", ...iosArgs]);
  await sleep(5500);
  sh("xcrun", ["simctl", "io", "booted", "screenshot", file]);
}

async function webShot(browser, query, simple, dark, largest, file, setup) {
  const ctx = await browser.newContext({ viewport: { width: 1000, height: 960 }, deviceScaleFactor: 3, colorScheme: dark ? "dark" : "light" });
  const page = await ctx.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const size = largest ? "xlarge" : simple ? "large" : "normal";
  const common = setup ? `demoReset=1&demoSize=${size}` : `demoSkipSetup=1&demoSimple=${simple ? 1 : 0}&demoSize=${size}`;
  await page.goto(`${BASE}?${common}&demoPin=off&demoConnection=ok&demoFamily=${encodeURIComponent("Mei Ling|91234567")}${query ? "&" + query : ""}`);
  await page.waitForSelector("#app"); await page.waitForTimeout(1200);
  await page.evaluate(() => { const d = document.querySelector(".device"); if (d) d.style.transform = "none"; document.documentElement.classList.add("compare"); });
  await page.waitForTimeout(150);
  await page.locator(".device .screen").screenshot({ path: file });
  await ctx.close();
  return errors;
}

async function sideBySide(browser, pairs, file) {
  const ctx = await browser.newContext({ viewport: { width: 900, height: 1000 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const img = (p) => (existsSync(p) ? `data:image/png;base64,${readFileSync(p).toString("base64")}` : "");
  await page.setContent(`<body style="margin:0;background:#888;font:14px system-ui;display:flex;gap:16px;padding:16px">
    ${pairs.map(([label, p]) => `<figure style="margin:0;text-align:center;color:#fff">${p && existsSync(p) ? `<img src="${img(p)}" style="width:402px;height:874px;border-radius:24px;display:block">` : `<div style="width:402px;height:874px;display:grid;place-items:center">no shot</div>`}<figcaption>${label}</figcaption></figure>`).join("")}</body>`);
  await page.screenshot({ path: file, fullPage: true });
  await ctx.close();
}

mkdirSync(join(OUT, "ios"), { recursive: true });
mkdirSync(join(OUT, "web"), { recursive: true });
mkdirSync(join(OUT, "side"), { recursive: true });
const browser = await chromium.launch({ executablePath: CHROME });
const rows = [];
// the first launch after a build or a reboot is slow: warm the app up once so no shot shows the launch screen
if (IOS) { try { sh("xcrun", ["simctl", "launch", "booted", BUNDLE]); await sleep(8000); } catch { /* no Simulator */ } }
for (const [name, iosArgs, query, simple, opts = {}] of SCREENS) {
  if (ONLY && !ONLY.includes(name)) continue;
  for (const [theme, size] of VARIANTS) {
    const tag = `${name}_${theme}_${size}`;
    const iosFile = join(OUT, "ios", tag + ".png"), webFile = join(OUT, "web", tag + ".png");
    if (IOS) { try { await iosShot(name, iosArgs, simple, theme === "dark", size === "largest", iosFile, opts.setup); } catch (e) { console.log("ios failed", tag, e.message.split("\n")[0]); } }
    const errs = await webShot(browser, query, simple, theme === "dark", size === "largest", webFile, opts.setup);
    if (errs.length) console.log("web errors", tag, errs);
    await sideBySide(browser, [["iOS " + tag, iosFile], ["web " + tag, webFile]], join(OUT, "side", tag + ".png"));
    rows.push(tag);
    console.log("shot", tag);
  }
}
if (IOS) sh("xcrun", ["simctl", "ui", "booted", "appearance", "light"]);
await browser.close();
writeFileSync(join(OUT, "index.html"), `<!doctype html><meta name=viewport content="width=device-width"><title>ScamGuard: iOS vs web</title>
<body style="font:15px system-ui;background:#222;color:#eee;margin:16px"><h1>iOS Simulator (left) vs web (right)</h1>
${rows.map((r) => `<h3>${r}</h3><img loading=lazy src="side/${r}.png" style="max-width:100%">`).join("\n")}`);
console.log(`${rows.length} pairs -> ${OUT}/index.html`);

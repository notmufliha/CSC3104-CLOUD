// Quick look: load a URL, print errors, save a screenshot. node tests/e2e/smoke.mjs "<query>" out.png [w h]
import { chromium } from "playwright-core";
const [q = "", out = "build/smoke.png", w = "1280", h = "960"] = process.argv.slice(2);
const b = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" });
const p = await b.newPage({ viewport: { width: +w, height: +h } });
p.on("pageerror", (e) => console.log("PAGEERROR", e.message));
p.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") console.log("CONSOLE", m.type(), m.text()); });
await p.goto("http://127.0.0.1:8088/" + q); await p.waitForTimeout(1500);
await p.screenshot({ path: out }); console.log("saved", out); await b.close();

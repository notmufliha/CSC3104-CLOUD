// Port of ios-app Data/GatewayClient.swift and Data/ModelUpdater.swift: the gateway's REST API (same requests and
// timeouts as the apps: 4 s per request) and the model-download service (/models/ next to /api/ on the edge).
import { normalizeUrl } from "./prefs.js";
import { LinearTextModel } from "../ondevice/linearModel.js";
import { sha256Hex } from "../logic/pin.js";

export class GatewayClient {
  constructor(baseUrl, log) {
    this.base = normalizeUrl(baseUrl);
    const u = new URL(this.base);
    if (!/^https?:$/.test(u.protocol) || !u.hostname) throw new Error("invalid URL");
    this.log = log;
  }

  async send(method, path, body, timeoutMs = 4000) {
    const url = new URL(path, this.base).href;
    const sent = body === undefined ? "" : JSON.stringify(body);
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    const t0 = performance.now(), now = Date.now();
    const pathOnly = new URL(url).pathname;
    try {
      const resp = await fetch(url, { method, signal: ctrl.signal, cache: "no-store",
        headers: body === undefined ? {} : { "Content-Type": "application/json" }, body: body === undefined ? undefined : sent });
      const text = await resp.text();
      this.log?.add({ timeMs: now, method, path: pathOnly, status: resp.status, ms: Math.round(performance.now() - t0), request: sent, response: text.slice(0, 64 * 1024) });
      let json = null;
      if (resp.ok) { try { json = JSON.parse(text); } catch { /* not JSON */ } }
      return { status: resp.status, ok: resp.ok, body: json };
    } catch (e) {
      const msg = e.name === "AbortError" ? "timed out" : e.message || String(e);
      this.log?.add({ timeMs: now, method, path: pathOnly, status: 0, ms: Math.round(performance.now() - t0), request: sent, response: msg });
      throw Object.assign(new Error(msg), { timeout: e.name === "AbortError" });
    } finally { clearTimeout(timer); }
  }

  utterance(b) { return this.send("POST", "v1/utterance", b); }
  endCall(b) { return this.send("POST", "v1/call/end", b, 5000); }
  verify(b) { return this.send("POST", "v1/verify", b); }
  report(b) { return this.send("POST", "v1/report", b, 8000); }
  health() { return this.send("GET", "healthz"); }
}

// ---------------------------------------------------------------- model updates (G.10)

export const MODEL_NAMES = { urgency_en: "urgency_en.json", money_en: "money_en.json", impersonation_en: "impersonation_en.json" };

/** https://1.2.3.4:8443/api/ -> https://1.2.3.4:8443/models/; http://h:8080/ -> http://h:8088/models/. */
export function modelsBase(gatewayUrl) {
  const u = normalizeUrl(gatewayUrl);
  if (u.endsWith("/api/")) return u.slice(0, -4) + "models/";
  try {
    const x = new URL(u);
    if (x.port === "8080") { x.port = "8088"; x.pathname = "/"; }
    return x.href + "models/";
  } catch { return null; }
}

/** Checks the manifest and installs anything newer (into IndexedDB). Returns a short message for the Developer view. */
export async function updateModels(gatewayUrl, prefs, store) {
  const base = modelsBase(gatewayUrl);
  if (!base) return "Bad server address";
  let manifest;
  try {
    const r = await fetch(base + "manifest", { cache: "no-store" });
    if (!r.ok) throw new Error("HTTP " + r.status);
    manifest = (await r.json()).models || {};
  } catch (e) {
    return `No model service at ${base}manifest (${e.message}). Is the edge (port 8088 on compose, 8443 on Kubernetes) up?`;
  }
  const inst = { ...prefs.installedModels };
  const changed = [], problems = [];
  for (const [name, file] of Object.entries(MODEL_NAMES)) {
    const entry = manifest[name];
    if (!entry?.version || !entry?.sha256 || inst[name]?.sha256 === entry.sha256) continue;
    const signal = name.split("_")[0];
    try {
      const r = await fetch(`${base}${name}/${entry.version}`, { cache: "no-store" });
      if (!r.ok) throw new Error("HTTP " + r.status);
      const bytes = new Uint8Array(await r.arrayBuffer());
      if (sha256Hex(bytes) !== entry.sha256) throw new Error("checksum mismatch");
      const text = new TextDecoder().decode(bytes);
      LinearTextModel.load(text);                         // refuse a file the app can't read
      await store.saveModel(file, text);
      inst[name] = { version: entry.version, sha256: entry.sha256, installedAt: Date.now() };
      changed.push(`${signal} ${entry.version}`);
    } catch (e) { problems.push(`${signal}: ${e.message}`); }
  }
  if (changed.length) prefs.installedModels = inst;
  if (changed.length) return `Updated: ${changed.join(", ")}` + (problems.length ? `. Kept the old model for ${problems.join("; ")}` : "");
  if (problems.length) return `Kept the current models: ${problems.join("; ")}`;
  if (!Object.keys(manifest).length) return "The registry has no phone models yet (run a federated round with --publish)";
  return "Up to date";
}

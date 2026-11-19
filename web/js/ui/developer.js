// Port of ios-app UI/DeveloperViews.swift: the hidden Developer view (Settings → Help & about → tap the version 7
// times): connection and server address, models, the last call's table, the masked request log, tools.
import { html, useState, Page, Section, ToggleRow, Segmented, YesNoSheet, copyText } from "./components.js";
import { DevFormat, SPEEDS, Playback } from "../logic/developer.js";
import { setAppearance, getAppearance } from "./theme.js";

const Mono = ({ text, pre = false }) => html`<div class=${"mono" + (pre ? " pre" : "")}>${text}</div>`;
const KeyValues = ({ rows }) => rows.map(([k, v]) => html`<div class="cell" style="flex-direction:column;align-items:stretch;gap:2px;min-height:0;padding:8px 20px">
  <span class="f-caption muted">${k}</span><${Mono} text=${v} /></div>`);
const ToolButton = ({ title, onClick, id }) => html`<button class="btn ios-bordered small grey" data-id=${id} onClick=${onClick}>${title}</button>`;

function Header({ title, onCopy }) {
  return html`<span class="row"><span class="grow">${title}</span>${onCopy && html`<button class="link-btn" style="min-height:32px;font-weight:400" onClick=${onCopy}>Copy</button>`}</span>`;
}

function ServerAddress({ vm }) {
  const [url, setUrl] = useState(vm.gatewayUrl);
  const [result, setResult] = useState(null);
  const [testing, setTesting] = useState(false);
  const here = location.origin + "/api/";
  const host = location.hostname;
  const presets = [["This page (edge)", here], ["Compose edge", `http://${host}:8088/api/`], ["HTTPS (Kubernetes)", `https://${host}:8443/api/`]];
  const encrypted = url.trim().toLowerCase().startsWith("https://");
  const c = vm.connection;
  const chipColor = testing ? "var(--muted)" : c === true ? "var(--safe)" : c === false ? "var(--scam)" : "var(--muted)";
  return html`<div class="cell" style="flex-direction:column;align-items:stretch;gap:10px">
    <span class="f-subheadline" style="font-weight:600">Server address</span>
    <span class="status-chip" style=${`background:color-mix(in srgb, ${chipColor} 15%, transparent)`}><i style=${`background:${chipColor}`}></i>
      ${testing ? "Checking…" : c === true ? "Connected" : c === false ? "Can't reach the server" : "Not checked yet"}</span>
    <input class="field mono" data-id="devUrl" value=${url} spellcheck="false" autocapitalize="off" onInput=${(e) => setUrl(e.target.value)} />
    <span class="f-subheadline" style=${`color:${encrypted ? "var(--safe)" : "var(--scam)"}`}>${encrypted ? "🔒 Encrypted (HTTPS)" : "⚠ Not encrypted (HTTP): caller text is sent as plain text"}</span>
    <div class="row hscroll" style="gap:8px">${presets.map(([n, u]) => html`<button class="btn ios-bordered small" style=${`width:auto;padding:0 14px;white-space:nowrap;${url === u ? "" : "background:var(--fill-tertiary);color:var(--label)"}`}
      onClick=${() => setUrl(u)}>${n}</button>`)}</div>
    <span class="f-footnote muted">A browser can only reach a server that allows this page (the same address, or CORS). The edge serves both.</span>
    <div class="row" style="gap:12px">
      <button class="btn ios-prominent small grow" data-id="devSave" onClick=${() => { vm.saveGatewayUrl(url); setUrl(vm.gatewayUrl); setResult("Saved."); }}>Save</button>
      <button class="btn ios-bordered small grow" data-id="devTest" onClick=${async () => { setTesting(true); setResult(await vm.testConnection(url)); setTesting(false); }}>Test connection</button></div>
    ${result && html`<${Mono} text=${result} />`}</div>`;
}

export function DeveloperView({ vm, dir, onBack }) {
  const [confirmReset, setConfirmReset] = useState(false);
  const [note, setNote] = useState(null);
  const [copied, setCopied] = useState(null);
  const conn = vm.devConnectionRows(), models = vm.devModelRows(), call = vm.devLog.call, entries = vm.devLog.entries;
  const rows = (r) => r.map(([k, v]) => `${k}: ${v}`).join("\n");
  const copy = async (label, text) => { if (await copyText(text)) setCopied(label); };
  return html`<${Page} title="Developer view" back onBack=${onBack} dir=${dir} id="developer">
    <div class="list">
      <${Section}>
        <div class="cell f-subheadline muted">For demos and debugging. Only the unlocked flag is saved; logs stay in memory and caller text is masked.</div>
        <${ToggleRow} title="Developer view" line="Off hides this page and the server settings again, and forgets the logs." on=${vm.devUnlocked} id="devToggle"
          onChange=${(on) => { vm.setDeveloperUnlocked(on); if (!on) onBack(); }} />
        ${copied && html`<div class="cell f-subheadline brand">Copied ${copied}</div>`}</${Section}>
      <${Section} header=${html`<${Header} title="1. Connection" onCopy=${() => copy("connection", rows(conn))} />`}>
        <${KeyValues} rows=${conn} /><${ServerAddress} vm=${vm} /></${Section}>
      <${Section} header=${html`<${Header} title="2. Models" onCopy=${() => copy("models", rows(models))} />`}>
        <${KeyValues} rows=${models} />
        <div class="cell" style="flex-direction:column;align-items:stretch;gap:8px">
          <button class="btn ios-prominent small" data-id="devModelUpdate" onClick=${() => vm.checkModelUpdates()}>Check for model updates</button>
          ${vm.modelCheck && html`<${Mono} text=${vm.modelCheck} />`}
          <button class="btn ios-bordered small grey" disabled style="opacity:0.5">Train on my calls</button>
          <span class="f-footnote muted">Training on the phone comes in a later version.</span></div></${Section}>
      <${Section} header=${html`<${Header} title="3. Last call" onCopy=${() => copy("last call", vm.devDiagnostics().split("3. Last call\n").pop())} />`}>
        ${call ? html`<${KeyValues} rows=${[["call_id", call.callId], ["privacy", call.privacy], ["ended_by", call.endedBy ?? "still on"]]} />
          <div class="cell hscroll" style="align-items:flex-start;min-height:0" data-id="devTable"><${Mono} text=${DevFormat.table(call.lines)} pre /></div>
          <div class="cell" style="flex-direction:column;align-items:stretch;min-height:0;gap:4px"><span class="f-subheadline" style="font-weight:600">End-of-call reply</span>
            <${Mono} text=${call.end ? DevFormat.end(call.end) : call.privacy === "cloud" ? "none yet" : "none (not sent at this privacy level)"} /></div>`
          : html`<div class="cell f-subheadline">No call since unlocking. Calls are kept in memory only.</div>`}</${Section}>
      <${Section} header=${`4. Raw replies (${entries.length} of ${vm.devLog.capacity})`}>
        ${!entries.length && html`<div class="cell f-subheadline">No requests since unlocking.</div>`}
        ${entries.map((e) => html`<${RawEntry} e=${e} onCopy=${() => copy("reply", `${e.method} ${e.path} ${e.status} ${e.ms} ms ${DevFormat.time(e.timeMs)}\nRequest:\n${e.request || "(none)"}\nReply:\n${e.response || "(none)"}`)} />`)}</${Section}>
      <${Section} header="5. Tools"><div class="col gap12" style="padding:16px 20px">
        <span class="f-subheadline" style="font-weight:600">Call playback speed</span>
        <${Segmented} label="Call playback speed" id="speedPicker" value=${vm.playbackSpeed} onChange=${(v) => vm.setPlaybackSpeed(v)} options=${Object.entries(SPEEDS).map(([k, v]) => [k, v.label])} />
        <span class="f-footnote muted">Scripted calls. Instant keeps a ${Playback.instantGapMs} ms gap so lines stay in order. Back to 1× when locked.</span>
        <div class="group clear"><${ToggleRow} title="Show live scores during calls" line="A small panel at the top of the call screen. Hidden while the red warning shows." on=${vm.devOverlay}
          onChange=${(v) => vm.setDevOverlay(v)} id="devOverlayToggle" /></div>
        <span class="f-subheadline" style="font-weight:600">Appearance (web)</span>
        <${Segmented} label="Appearance" value=${getAppearance()} onChange=${(v) => { setAppearance(v); vm.notify(); }} options=${[["system", "System"], ["light", "Light"], ["dark", "Dark"]]} />
        <${ToolButton} title="Load sample history" id="loadSample" onClick=${() => { vm.loadSampleHistory(); setNote("Added about 60 sample calls."); }} />
        <${ToolButton} title="Delete sample history" onClick=${() => { vm.deleteSampleHistory(); setNote("Sample calls deleted (real calls kept)."); }} />
        ${note && html`<span class="f-subheadline">${note}</span>`}
        <${ToolButton} title="Copy diagnostics" onClick=${() => copy("diagnostics", vm.devDiagnostics())} />
        <${ToolButton} title="Reset setup only" id="resetSetup" onClick=${() => vm.resetOnboarding()} />
        <span class="f-footnote muted">Keeps the history and settings; shows "Who will use this phone?" again.</span>
        <button class="btn ios-prominent small red" data-id="resetApp" onClick=${() => setConfirmReset(true)}>Reset app to first launch</button></div></${Section}>
    </div>
    ${confirmReset && html`<${YesNoSheet} title="Reset app to first launch?" text="This deletes all call history, settings, contact and PIN. The app will start like a new install."
      yes="Reset app" no="Cancel" danger onYes=${() => { setConfirmReset(false); vm.resetApp(); }} onNo=${() => setConfirmReset(false)} />`}
  </${Page}>`;
}

function RawEntry({ e, onCopy }) {
  const [open, setOpen] = useState(false);
  return html`<div class="cell" style="flex-direction:column;align-items:stretch;gap:6px;min-height:0">
    <button class="btn" style="justify-content:flex-start;text-align:left" onClick=${() => setOpen(!open)}>
      <${Mono} text=${`${open ? "▾" : "▸"} ${e.method} ${e.path}  ${e.status}  ${e.ms} ms  ${DevFormat.time(e.timeMs)}`} /></button>
    ${open && html`<span class="f-caption">Request</span><div class="hscroll"><${Mono} text=${e.request || "(none)"} pre /></div>
      <span class="f-caption">Reply</span><div class="hscroll"><${Mono} text=${e.response || "(none)"} pre /></div>
      <button class="link-btn" style="align-self:flex-start;min-height:32px" onClick=${onCopy}>Copy</button>`}</div>`;
}

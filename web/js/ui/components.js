// Shared building blocks, each matching an iOS view: BigButton, ChoiceCard, Toggle, segmented Picker, RiskIcon /
// RiskBadge, StateBanner, CarefulBanner, EmptyState, YesNoSheet, InfoSheet, StepsSheet, PinField, NewPinFields,
// PinGateView, and Page (a NavigationStack screen with a large title that collapses into the bar on scroll).
import { html, useState, useEffect, useRef } from "../../vendor/preact-htm.js";
import { ICONS } from "./icons.js";
import { T } from "../logic/strings.js";
import * as Pin from "../logic/pin.js";
import { tap } from "./alerts.js";

export { html, useState, useEffect, useRef };

/** A Lucide icon; `fill` fills it (SF Symbols' ".fill" look). */
/** SF Symbols with no close Lucide match, drawn here: phone.down.fill, exclamationmark.triangle.fill, checkmark.circle.fill. */
const CUSTOM = {
  "phone-down": '<path fill="currentColor" stroke="none" d="M12 8.2c-3.9 0-7.5 1.1-9.6 3a1.6 1.6 0 0 0-.4 1.8l.8 1.9c.3.7 1.1 1.1 1.9.9l3.1-.8c.6-.2 1-.7 1-1.4v-1.7c1-.3 2.1-.4 3.2-.4s2.2.1 3.2.4v1.7c0 .6.4 1.2 1 1.4l3.1.8c.8.2 1.6-.2 1.9-.9l.8-1.9a1.6 1.6 0 0 0-.4-1.8c-2.1-1.9-5.7-3-9.6-3z"/>',
  "alert-fill": '<path fill="currentColor" stroke="none" d="M10.3 3.4a2 2 0 0 1 3.4 0l8.4 14.5a2 2 0 0 1-1.7 3H3.6a2 2 0 0 1-1.7-3z"/><path d="M12 9v4.5" stroke="#fff" stroke-width="2.4"/><circle cx="12" cy="17" r="1.3" fill="#fff" stroke="none"/>',
  "check-fill": '<circle cx="12" cy="12" r="10.5" fill="currentColor" stroke="none"/><path d="M7.5 12.3l3 3 6-6.3" stroke="#fff" stroke-width="2.6"/>',
};

export function Icon({ name, size, fill = false, stroke = 2, cls = "", style = "" }) {
  const body = CUSTOM[name] || ICONS[name] || "";
  const s = size ? `width:${size}px;height:${size}px;` : "";
  return html`<svg class=${"i " + cls} viewBox="0 0 24 24" fill=${fill ? "currentColor" : "none"} stroke="currentColor" stroke-width=${stroke}
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" style=${s + style} dangerouslySetInnerHTML=${{ __html: body }}></svg>`;
}

const GLYPH = { green: "check", yellow: "!", red: "x", listening: "phone", error: "i" };
/** A filled circle in the risk colour with ✓ / ! / ✕ / i, so the meaning never depends on colour alone. */
export function RiskIcon({ level, size = 28 }) {
  const g = GLYPH[level];
  const inner = g === "!" || g === "i"
    ? html`<span style=${`font-size:${size * 0.55}px;line-height:1;font-family:var(--font-display)`}>${g === "!" ? "!" : "i"}</span>`
    : html`<${Icon} name=${g === "check" ? "check" : g === "x" ? "x" : "phone"} size=${size * 0.55} stroke=${3.2} fill=${g === "phone"} />`;
  return html`<span class=${"risk-icon " + level} style=${`width:${size}px;height:${size}px`} aria-hidden="true">${inner}</span>`;
}

export function RiskBadge({ level, word, size = 28, cls = "f-title2b" }) {
  return html`<div class="badge" role="text"><${RiskIcon} level=${level} size=${size} /><span class=${cls}>${word}</span></div>`;
}

export function BigButton({ title, onClick, prominent = true, danger = false, red = false, id, disabled, icon, href, target }) {
  const cls = "btn big" + (red ? " red" : prominent ? "" : danger ? " danger" : " outline");
  const click = (e) => { tap(); onClick?.(e); };
  if (href) return html`<a class=${cls} href=${href} target=${target} rel="noopener" data-id=${id} onClick=${click}>${icon && html`<${Icon} name=${icon} fill />`}${title}</a>`;
  return html`<button class=${cls} data-id=${id} disabled=${disabled} onClick=${click}>${icon && html`<${Icon} name=${icon} fill />`}${title}</button>`;
}

export function Toggle({ on, onChange, label, disabled, id }) {
  return html`<button class="switch" role="switch" aria-checked=${on ? "true" : "false"} aria-label=${label} data-id=${id} disabled=${disabled}
    onClick=${() => onChange(!on)}></button>`;
}

/** A settings toggle row: title + one line, the switch on the right. */
export function ToggleRow({ title, line, on, onChange, id, disabled }) {
  return html`<div class="cell toggle">
    <div class="row-text"><span class="row-title">${title}</span>${line && html`<span class="row-value">${line}</span>`}</div>
    <${Toggle} on=${on} onChange=${onChange} label=${title} id=${id} disabled=${disabled} /></div>`;
}

export function Segmented({ options, value, onChange, label, id }) {
  return html`<div class="segmented" role="radiogroup" aria-label=${label} data-id=${id}>
    ${options.map(([v, l]) => html`<button role="radio" aria-checked=${v === value ? "true" : "false"} onClick=${() => onChange(v)}>${l}</button>`)}</div>`;
}

export function ChoiceCard({ selected, title, line, onClick, id }) {
  return html`<button class="choice btn" role="radio" aria-checked=${selected ? "true" : "false"} data-id=${id} onClick=${() => { tap(); onClick(); }}>
    <span class="radio" aria-hidden="true"></span>
    <span class="col gap4" style="flex:1;min-width:0"><span class="t">${title}</span>${line && html`<span class="l">${line}</span>`}</span></button>`;
}

/** A settings row: icon, title, current value, chevron. */
export function NavRow({ icon, title, value, valueColor, onClick, id, tour }) {
  return html`<button class="cell has-icon" data-id=${id} data-tour=${tour} onClick=${onClick}>
    ${icon && html`<span class="row-icon hide-ax"><${Icon} name=${icon} fill=${icon !== "smile" && icon !== "text-size" && icon !== "help" && icon !== "antenna"} /></span>`}
    <span class="row-text"><span class="row-title">${title}</span>${value != null && html`<span class="row-value" style=${valueColor ? `color:${valueColor}` : ""}>${value}</span>`}</span>
    <${Icon} name="chevron-right" cls="chev" stroke=${2.5} /></button>`;
}

export const Section = ({ header, footer, children, cls = "", tour }) => html`<div class="section" data-tour=${tour}>
  ${header && html`<div class="section-header" role="heading" aria-level="2">${header}</div>`}
  <div class=${"group " + cls}>${children}</div>
  ${footer && html`<div class="section-footer">${footer}</div>`}</div>`;

export function EmptyState({ text, icon = "phone", compact = false, goHome }) {
  return html`<div class=${"empty" + (compact ? " compact" : "")} data-id="emptyState">
    <div class="circle" aria-hidden="true"><${Icon} name=${icon} fill=${icon === "phone" || icon === "star"} /></div>
    <div class=${compact ? "f-body muted" : "f-title2 muted"}>${text}</div>
    ${goHome && html`<button class="btn ios-prominent" style="width:auto;padding:0 20px;min-height:56px;font:var(--t-body)/1 var(--font)" onClick=${goHome}>${T("btn_go_home")}</button>`}</div>`;
}

export function StateBanner({ level, text }) {
  const color = { green: "var(--safe)", yellow: "var(--warn)", red: "var(--scam)" }[level] ?? "var(--grey)";
  return html`<div class="state-banner" style=${`border-color:${color}`} role="text"><${RiskIcon} level=${level} size=${32} /><span class="f-body grow">${text}</span></div>`;
}

export function CarefulBanner({ kindKey }) {
  return html`<div class="careful-banner" role="text"><span class="ex" aria-hidden="true">!</span>
    <div class="col gap4 grow"><span class="f-title3b">${T("risk_careful")}</span><span class="f-body">${T(kindKey)}</span></div></div>`;
}

// ---------------------------------------------------------------- sheets

export function Sheet({ onClose, children, size = "large", dismissable = true, id }) {
  useEffect(() => {
    const k = (e) => { if (e.key === "Escape" && dismissable) onClose(); };
    addEventListener("keydown", k);
    return () => removeEventListener("keydown", k);
  }, []);
  return html`<div class="scrim" onClick=${() => dismissable && onClose()}></div>
    <div class=${"sheet " + size} role="dialog" aria-modal="true" data-id=${id}><div class="grabber"></div><div class="body">${children}</div></div>`;
}

export function YesNoSheet({ title, text, yes, no, danger, onYes, onNo }) {
  return html`<${Sheet} onClose=${onNo} dismissable=${false} id="yesNo">
    <h2 class="f-title" style="margin:0">${title}</h2><p class="f-body" style="margin:0">${text}</p>
    <${BigButton} title=${yes} prominent=${false} danger=${danger} onClick=${onYes} id="sheetYes" />
    <${BigButton} title=${no} prominent=${false} onClick=${onNo} id="sheetNo" /></${Sheet}>`;
}

export function InfoSheet({ title, text, onClose }) {
  return html`<${Sheet} onClose=${onClose} size="medium" id="infoSheet">
    <h2 class="f-title2s" style="margin:0">${title}</h2><p class="f-body" style="margin:0;white-space:pre-line">${text}</p>
    <div class="row" style="justify-content:flex-end"><button class="link-btn" onClick=${onClose}>${T("btn_ok")}</button></div></${Sheet}>`;
}

export function StepsSheet({ title, intro, steps, action, onClose }) {
  return html`<${Sheet} onClose=${onClose} id="stepsSheet">
    <h2 class="f-title2b" style="margin:0">${title}</h2>${intro && html`<p class="f-body" style="margin:0">${intro}</p>`}
    ${steps.map((s, i) => html`<div class="row top"><span class="f-title3b brand" style="min-width:28px">${i + 1}.</span><span class="f-body grow">${s}</span></div>`)}
    ${action && html`<${BigButton} title=${action[0]} onClick=${action[1]} href=${action[2]} id="sheetAction" />`}
    <${BigButton} title=${T("btn_close")} prominent=${false} onClick=${onClose} id="sheetClose" /></${Sheet}>`;
}

// ---------------------------------------------------------------- PIN

export function PinField({ label, value, onInput, id }) {
  return html`<label class="col"><span class="field-label">${label}</span>
    <input class="field pin" type="password" inputmode="numeric" autocomplete="off" maxlength="4" data-id=${id} aria-label=${label}
      value=${value} onInput=${(e) => { const v = e.target.value.replace(/\D/g, "").slice(0, 4); e.target.value = v; onInput(v); }} /></label>`;
}

/** Choose a PIN twice; onPin gets it when both match (or "" while they don't). */
export function NewPinFields({ onPin }) {
  const [a, setA] = useState(""), [b, setB] = useState("");
  const report = (x, y) => onPin(Pin.isValid(x) && x === y ? x : "");
  let note = null;
  if (a || b) {
    if (a.length < 4) note = T("pin_need_4");
    else if (b.length === 4 && a !== b) note = T("pin_mismatch");
    else if (Pin.isValid(a) && a === b) note = T("pin_ok") + " ✓";
  }
  return html`<div class="col gap12">
    <${PinField} label=${T("pin_new")} value=${a} id="pinNew" onInput=${(v) => { setA(v); report(v, b); }} />
    <${PinField} label=${T("pin_again")} value=${b} id="pinAgain" onInput=${(v) => { setB(v); report(a, v); }} />
    ${note && html`<p class="f-bodys" role="status" style="margin:0">${note}</p>`}</div>`;
}

/** Asks for the PIN. "Forgot PIN" removes only the PIN, after a clear warning. */
export function PinGate({ vm, onOpen }) {
  const [pin, setPin] = useState(""), [wrong, setWrong] = useState(false), [short, setShort] = useState(false), [forgot, setForgot] = useState(false);
  const open = async () => {
    if (!Pin.isValid(pin)) { setShort(true); return; }
    if (await vm.verifyPin(pin)) onOpen(); else { setWrong(true); setPin(""); }
  };
  return html`<div class="col gap16" style="align-items:center;padding:24px 24px 40px;text-align:center" data-id="pinGate">
    <div class="icon-circle"><${Icon} name="lock" fill /></div>
    <h2 class="f-title" style="margin:0">${T("pin_enter_title")}</h2>
    <p class="f-body muted" style="margin:0">${T("pin_enter_line")}</p>
    <div style="width:100%;max-width:360px;text-align:left"><${PinField} label=${T("field_pin")} value=${pin} id="pinEnter" onInput=${(v) => { setPin(v); setWrong(false); }} /></div>
    ${wrong && html`<p class="f-bodys" role="alert" style="margin:0;color:var(--warning-ink-adaptive)">${T("pin_wrong")}</p>`}
    ${short && !Pin.isValid(pin) && html`<p class="f-bodys" role="alert" style="margin:0">${T("pin_need_4")}</p>`}
    <${BigButton} title=${T("btn_unlock")} onClick=${open} id="pinUnlock" />
    <button class="link-btn" onClick=${() => setForgot(true)}>${T("btn_forgot_pin")}</button>
    ${forgot && html`<${YesNoSheet} title=${T("pin_forgot_title")} text=${T("pin_forgot_text")} yes=${T("btn_remove_pin")} no=${T("btn_keep_pin")} danger
      onYes=${() => { setForgot(false); vm.clearPin(); onOpen(); }} onNo=${() => setForgot(false)} />`}</div>`;
}

// ---------------------------------------------------------------- a NavigationStack screen

/**
 * A screen in a stack: the navigation bar (back chevron, the inline title once scrolled) and a large title that
 * scrolls away, then the content. `inline` = navigationBarTitleDisplayMode(.inline). `bottom` = a pinned bottom bar.
 */
export function Page({ title, back, onBack, inline = false, children, bottom, dir = "none", scrollKey, id, bg }) {
  const ref = useRef(null);
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => { if (ref.current) ref.current.scrollTop = 0; setScrolled(false); }, [scrollKey]);
  const onScroll = (e) => setScrolled(e.target.scrollTop > (inline ? 2 : 40));
  return html`<div class=${"page " + (dir === "push" ? "push" : dir === "pop" ? "pop" : "")} data-screen=${id} style=${bg ? `background:${bg}` : ""}>
    <div class=${"navbar" + (scrolled ? " scrolled" : "") + (inline ? " inline" : "")}>
      ${back && html`<button class="back" onClick=${onBack} aria-label=${T("a11y_back")} data-id="navBack"><span class="glass"><${Icon} name="chevron-left" size=${22} stroke=${2.6} /></span></button>`}
      <span class="inline-title" aria-hidden=${!inline}>${title}</span></div>
    <div class="scroller" ref=${ref} onScroll=${onScroll}>
      ${!inline && title && html`<h1 class="large-title" tabindex="-1">${title}</h1>`}
      ${children}</div>
    ${bottom && html`<div class="bottom-bar">${bottom}</div>`}</div>`;
}

/** Copy to the clipboard: navigator.clipboard on secure pages, else the old execCommand way (plain http://). */
export async function copyText(text) {
  try { if (navigator.clipboard && isSecureContext) { await navigator.clipboard.writeText(text); return true; } } catch { /* fall back */ }
  try {
    const ta = document.createElement("textarea");
    ta.value = text; ta.setAttribute("readonly", ""); ta.style.position = "fixed"; ta.style.opacity = "0";
    document.body.appendChild(ta); ta.select();
    const ok = document.execCommand("copy");
    ta.remove();
    return ok;
  } catch { return false; }
}

/** tel: / sms: links work on phones; a desktop browser usually has nothing to open them. */
export const isPhone = () => /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);
export const telHref = (n) => "tel:" + String(n).replace(/[^\d+]/g, "");
export const smsHref = (n, body) => "sms:" + String(n).replace(/[^\d+]/g, "") + (/iPhone|iPad|Mac/i.test(navigator.userAgent) ? "&" : "?") + "body=" + encodeURIComponent(body);

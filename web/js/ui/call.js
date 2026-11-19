// Ports of ios-app UI/CallViews.swift (incoming call, in-call screen), UI/WarningViews.swift (the full-screen red
// warning), UI/SummaryView.swift and UI/AfterCallCard.swift, and the Developer view's live score overlay.
import { html, useState, useEffect, useRef, Icon, RiskIcon, RiskBadge, BigButton, StateBanner, CarefulBanner, StepsSheet, copyText, isPhone, telHref, smsHref } from "./components.js";
import { T } from "../logic/strings.js";
import * as R from "../logic/risk.js";
import { afterCardShows, reportSummary, BLOCK_STEPS, SHARED_STEPS } from "../logic/features.js";
import { overlayVisible, DevFormat } from "../logic/developer.js";
import { CLOUD_CHECKED, CLOUD_UNAVAILABLE } from "../vm.js";
import { WEB } from "./webText.js";
import { tap } from "./alerts.js";

const SCAMSHIELD = "https://www.scamshield.gov.sg/";
const levelColor = (l) => ({ green: "var(--safe)", yellow: "var(--warn)", red: "var(--scam)" }[l] ?? "var(--grey)");

/** Opens tel: / sms: on a phone; on a computer says what would happen. */
export function phoneLink(vm, href, desktopText) {
  if (isPhone()) location.href = href;
  else vm.showToast(desktopText);
}

export function CallFlow({ vm }) {
  const s = vm.state;
  const phase = s.phase;
  return html`<div class=${"call-root" + (phase === "ended" ? " summary-bg" : "")} data-screen=${"call-" + phase}>
    <div aria-hidden=${s.warningOpen ? "true" : "false"} inert=${s.warningOpen} style="flex:1;min-height:0;display:flex;flex-direction:column">
      ${phase === "ringing" && html`<${IncomingCall} vm=${vm} />`}
      ${phase === "inCall" && html`<${InCallContent} state=${s} vm=${vm} showCareful=${vm.showBeCareful} simple=${vm.simpleMode} />`}
      ${phase === "ended" && html`<${SummaryView} vm=${vm} />`}
    </div>
    ${overlayVisible(vm.devOverlay, vm.devUnlocked, phase === "inCall", s.warningOpen) && html`<${DevScoreOverlay} line=${vm.devLatest} />`}
    ${s.warningOpen && (phase === "inCall" || phase === "ended") && html`<${WarningScreen} kind=${R.callWarningKind(s)} callEnded=${phase === "ended"}
      familyName=${vm.family?.name} vm=${vm} />`}
  </div>`;
}

function IncomingCall({ vm }) {
  const btn = (label, icon, color, fn, id) => html`<div class="col gap8" style="align-items:center">
    <button class="round-btn btn" style=${`background:${color}`} aria-label=${label} data-id=${id} onClick=${() => { tap(); fn(); }}>
      <${Icon} name=${icon} fill=${icon === "phone"} stroke=${icon === "x" ? 3 : 2} /></button><span class="f-subheadline">${label}</span></div>`;
  return html`<div class="ringing">
    <span class="f-headline muted">${T("call_incoming")}</span>
    <div style="flex:1;display:flex;flex-direction:column;justify-content:center;gap:12px">
      <span class="who">${T("call_unknown_caller")}</span><span class="f-title2 muted">${T("call_private_number")}</span></div>
    <div class="row" style="justify-content:space-evenly;width:100%">
      ${btn(T("btn_decline"), "x", "var(--scam)", () => vm.decline(), "decline")}
      ${btn(T("btn_answer"), "phone", "var(--safe)", () => vm.answer(), "answer")}</div></div>`;
}

/** The in-call screen, for a real call or the tour's pretend one (demo: nothing can be tapped). */
export function InCallContent({ state: s, vm, demo = false, showCareful = true, simple = false }) {
  const shown = R.shownLevel(R.callLevel(s), showCareful);
  const kind = R.callWarningKind(s);
  const ref = useRef(null);
  useEffect(() => { const el = ref.current; if (el) el.scrollTop = el.scrollHeight; }, [s.lines.length]);
  return html`<div class="incall" style=${demo ? "pointer-events:none" : ""}>
    <div class="col gap12">
      <div class="row"><span class="f-headline muted grow">${T("call_on_call")}</span>
        ${s.speedTag && html`<span class="pill-tag speed" data-id="speedTag">${s.speedTag}</span>`}
        ${!simple && html`<span class="pill-tag" aria-label=${T("a11y_privacy_badge", R.privacyBadge(s.privacyLevel))}>${R.privacyBadge(s.privacyLevel)}</span>`}</div>
      <${RiskBar} level=${shown} score=${s.combinedScore} showScore=${!simple} />
      ${!simple && html`<div class="chips" data-tour="warningChips">${R.SIGNALS.map((sig) => {
        const on = R.callLit(s).includes(sig);
        return html`<span class=${"chip" + (on ? " on" : "")} aria-label=${on ? T("a11y_flagged_suffix", R.chipLabel(sig)) : R.chipLabel(sig)}>${R.chipLabel(sig)}</span>`;
      })}</div>`}
      ${shown === "yellow" && html`<${CarefulBanner} kindKey=${R.carefulKey(kind)} />`}
      ${shown === "red" && !s.warningOpen && html`<${StateBanner} level="red" text=${T("warn_title") + ". " + T(R.warnMessageKey(kind))} />`}
      ${shown === "error" && html`<${StateBanner} level="error" text=${T("error_banner")} />`}
    </div>
    <div class="transcript" ref=${ref} aria-live="polite">${s.lines.map((l) => html`<${ChatBubble} line=${l} showNotes=${!simple} />`)}</div>
    <button class="btn ios-prominent red" data-id="hangUp" onClick=${() => { tap(); vm?.hangUp(); }}><${Icon} name="phone-down" size=${26} />${T("btn_hang_up")}</button>
    <button class="btn ios-bordered" style="font:var(--t-body)/1.2 var(--font)" data-id="discard" onClick=${() => vm?.discard()} title=${T("a11y_discard_hint")}>${T("btn_discard_call")}</button>
  </div>`;
}

export function RiskBar({ level, score, showScore = true }) {
  const has = level !== "listening" && level !== "error";
  const target = has ? Math.min(Math.max(score ?? 0, 0.08), 1) : 0;
  return html`<div class="col gap8" data-tour="riskBar" role="text">
    <${RiskBadge} level=${level} word=${R.levelLabel(level)} />
    <div class="riskbar-track"><div class="riskbar-fill" style=${`transform:scaleX(${target});background:${levelColor(level)}`}></div></div>
    ${has && showScore && score != null && html`<span class="f-caption muted" style="text-align:right">${T("call_risk_score", score.toFixed(2))}</span>`}</div>`;
}

const noteText = (n) => (n === CLOUD_CHECKED ? T("note_cloud_checked") : n === CLOUD_UNAVAILABLE ? T("note_cloud_unavailable") : n);

export function ChatBubble({ line, riskyHere = false, showNotes = true }) {
  const flagged = line.flags.length > 0;
  return html`<div class=${"bubble-wrap" + (line.isCaller ? "" : " me")}>
    <span class="f-caption muted">${T(line.isCaller ? "call_caller" : "call_you")}</span>
    <div class=${"bubble" + (flagged || riskyHere ? " flag" : "")}>${line.text}</div>
    ${riskyHere && html`<span class="f-caption" style="font-weight:600">${T("call_risky_here")}</span>`}
    ${showNotes && line.cloudNote && html`<span class="f-caption brand">${noteText(line.cloudNote)}</span>`}
    ${line.pending ? html`<span class="f-caption muted">${T("call_checking")}</span>`
      : line.failed ? html`<span class="f-caption muted">${T("risk_could_not_check")}</span>`
      : flagged && html`<span class="f-caption" style="font-weight:600">${T("call_flagged", line.flags.map(R.chipLabel).join(", "))}</span>`}</div>`;
}

// ---------------------------------------------------------------- the red warning

export function WarningScreen({ kind, callEnded, familyName, vm, demo = false }) {
  const title = T("warn_title"), message = T(R.warnMessageKey(kind)), doLine = T(R.warnDoKey(kind));
  const scroller = useRef(null);
  const [fits, setFits] = useState(true);
  const head = useRef(null);
  useEffect(() => {
    if (!demo) head.current?.focus();
    const el = scroller.current;
    if (el) setFits(el.scrollHeight <= el.clientHeight + 1);
  }, [vm?.textSize, familyName]);
  const fam = vm?.family;
  const btn = (label, icon, onClick, sub, id) => html`<button class="warn-btn btn" data-id=${id} onClick=${() => { tap(); onClick(); }}>
    <span class="icon"><${Icon} name=${icon} fill=${icon !== "phone-down"} stroke=${2.4} /></span>
    <span class="col" style="flex:1"><span class="l">${label}</span>${sub && html`<span class="s">${sub}</span>`}</span></button>`;
  const hangUp = btn(T("btn_hang_up"), "phone-down", () => vm.hangUp(), null, "warnHangUp");
  return html`<div class="warning" role="alertdialog" aria-modal="true" aria-labelledby="warnTitle" style=${demo ? "animation:none" : ""} data-tour="warningScreen">
    <div class="scroll" ref=${scroller}>
      <div class="col gap12" data-tour="redWarning" aria-live="assertive">
        <div class="x" aria-hidden="true"><${Icon} name="x" stroke=${4} size=${44} /></div>
        <h1 id="warnTitle" ref=${head} tabindex="-1">${title}</h1>
        <p class="msg">${message}</p><p class="do">${doLine}</p>
        ${callEnded && html`<p class="f-title3" style="margin:0">${T("warn_call_ended")}</p>`}</div>
      <div class="col gap16" data-tour="warningButtons">
        ${fits && hangUp}
        ${familyName && btn(T("btn_call_family"), "phone", () => { vm.leaveCallForHelp(); phoneLink(vm, telHref(fam.phone), WEB.desktopCall(familyName)); }, familyName, "warnCallFamily")}
        ${btn(T("btn_call_1799"), "phone", () => { vm.leaveCallForHelp(); phoneLink(vm, telHref("1799"), WEB.desktopCall("1799")); }, null, "warnCall1799")}
        ${familyName && btn(T("btn_tell_family", familyName), "send", () => phoneLink(vm, smsHref(fam.phone, R.familyMessage()), WEB.desktopSms(familyName)), null, "warnTell")}
        <button class="warn-close btn" data-id="warnClose" onClick=${() => vm.closeWarning()}>${T("btn_close_warning")}</button></div>
    </div>
    ${!fits && html`<div class="pinned">${hangUp}</div>`}
    ${!demo && html`<div class="flash on" aria-hidden="true"></div>`}</div>`;
}

// ---------------------------------------------------------------- summary

function SummaryView({ vm }) {
  const s = vm.state;
  if (s.reviewing) {
    return html`<div class="scroller" style="padding:calc(var(--safe-top) + 8px) 16px 16px">
      <h1 class="large-title" style="margin:4px 0 12px">${T("summary_title")}</h1>
      <div class="row"><span class="spinner"></span><span class="f-title3s">${T("sum_checking")}</span></div>
      <p class="f-body">${T("sum_checking_line")}</p></div>`;
  }
  const level = R.summaryLevel(s);
  const hiddenYellow = level === "yellow" && !vm.showBeCareful;
  const kind = R.callWarningKind(s);
  const after = afterCardShows(level, vm.showBeCareful);
  const risky = new Set(s.finalReview?.topWindowSeqs || []);
  const shown = R.triggerLines(s);
  return html`<div class="scroller" style="padding:calc(var(--safe-top) + 8px) 0 calc(16px + var(--safe-bottom))" data-screen="summary">
    <h1 class="large-title" tabindex="-1">${T("summary_title")}</h1>
    <div class="content" style="gap:14px">
      ${hiddenYellow ? html`<${RiskBadge} level="listening" word=${T("risk_call_ended")} size=${40} cls="f-title" />`
        : html`<${RiskBadge} level=${level} word=${R.riskWord(level)} size=${40} cls="f-title" />`}
      ${!vm.simpleMode && html`<p class="f-body muted" style="margin:0">${R.duringCallLine(s)}</p>`}
      ${!hiddenYellow && level === "red" && html`<${StateBanner} level="red" text=${T(R.warnMessageKey(kind)) + " " + T(R.warnDoKey(kind))} />`}
      ${!hiddenYellow && level === "yellow" && html`<${CarefulBanner} kindKey=${R.carefulKey(kind)} />`}
      ${!hiddenYellow && level === "error" && html`<${StateBanner} level="error" text=${T("error_banner")} />`}
      ${after && html`<${AfterCallCard} vm=${vm} state=${s} level=${level} kind=${kind} />`}
      ${level !== "green" && !hiddenYellow && html`<h2 class="f-title3s" style="margin:0">${T("sum_triggered")}</h2>
        ${!shown.length && html`<p class="f-body" style="margin:0">${T("sum_no_lines")}</p>`}
        ${shown.map((l) => html`<${ChatBubble} line=${l} riskyHere=${risky.has(l.seq)} showNotes=${!vm.simpleMode} />`)}`}
      <${BigButton} title=${T("btn_done")} onClick=${() => vm.reset()} id="summaryDone" />
      ${!after && html`<${BigButton} title=${T("btn_report_scamshield")} prominent=${false} danger href=${SCAMSHIELD} target="_blank" id="summaryScamShield" />`}
    </div></div>`;
}

function AfterCallCard({ vm, state: s, level, kind }) {
  const [copied, setCopied] = useState(false);
  const [sheet, setSheet] = useState(null);
  const report = async () => {
    const reason = T(level === "red" ? R.warnMessageKey(kind) : R.carefulKey(kind));
    const text = reportSummary(Date.now(), T(level === "red" ? "risk_scam" : "risk_careful"), reason, R.triggerLines(s).map((l) => l.text), vm.saveSentence);
    window.open(SCAMSHIELD, "_blank", "noopener");
    setCopied(await copyText(text));
  };
  const call1799 = () => phoneLink(vm, telHref("1799"), WEB.desktopCall("1799"));
  return html`<div class="card high" data-id="afterCallCard">
    <h2 class="f-title2b" style="margin:0">${T("after_title")}</h2>
    <button class="btn big red" data-id="afterReport" onClick=${() => { tap(); report(); }}>${T("after_report")}</button>
    <p class=${copied ? "f-bodys brand" : "f-body muted"} style="margin:0" role="status" data-id=${copied ? "afterCopied" : "afterReportLine"}>${T(copied ? "after_copied" : "after_report_line")}</p>
    <${BigButton} title=${T("btn_call_1799")} prominent=${false} onClick=${call1799} id="after1799" />
    ${vm.family && html`<${BigButton} title=${T("after_tell_family")} prominent=${false} id="afterFamily"
      onClick=${() => phoneLink(vm, smsHref(vm.family.phone, R.familyMessage()), WEB.desktopSms(vm.family.name))} />`}
    <${BigButton} title=${T("after_block")} prominent=${false} onClick=${() => setSheet("block")} id="afterBlock" />
    <${BigButton} title=${T("after_shared")} prominent=${false} onClick=${() => setSheet("shared")} id="afterShared" />
    ${sheet === "block" && html`<${StepsSheet} title=${T("block_title")} steps=${BLOCK_STEPS.map((k) => T(k))} onClose=${() => setSheet(null)} />`}
    ${sheet === "shared" && html`<${StepsSheet} title=${T("shared_title")} intro=${T("shared_line")} steps=${SHARED_STEPS.map((k) => T(k))}
      action=${[T("btn_call_1799"), call1799]} onClose=${() => setSheet(null)} />`}</div>`;
}

// ---------------------------------------------------------------- Developer view: live scores over the call

function DevScoreOverlay({ line }) {
  const [collapsed, setCollapsed] = useState(false);
  const lines = !line ? ["waiting for a caller line…"] : [
    `#${line.seq}  U ${DevFormat.score(line.urgency)}  M ${DevFormat.score(line.money)}  I ${DevFormat.score(line.impersonation)}`,
    `combined ${DevFormat.score(line.combined)}  BERT ${DevFormat.score(line.bertScore)} ${line.bertStatus ?? "-"}`,
    `rule ${line.rule ?? "-"}  live ${line.liveAlert ?? "-"}  ${line.verdict ?? "-"}`,
    `checked: ${line.place} · ${line.latencyMs} ms`];
  return html`<div class="dev-overlay" data-id="devOverlay" onClick=${() => setCollapsed(!collapsed)}>
    ${collapsed ? "Live scores ▸" : html`<div style="opacity:0.7;font-size:11px">Live scores · tap to hide</div>${lines.map((l) => html`<div>${l}</div>`)}`}</div>`;
}

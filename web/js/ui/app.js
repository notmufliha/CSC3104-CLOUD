// Port of ios-app ScamGuardApp.swift (AppRoot, RootView, CallFlowView): the setup or the app; the three tabs, each
// with its own stack; the call over everything; the tour; inside a generic phone frame on wide screens.
import { html, useState, useEffect, render } from "../../vendor/preact-htm.js";
import { Icon, PinGate, Page } from "./components.js";
import { T } from "../logic/strings.js";
import { Tour } from "../logic/onboarding.js";
import { HomeView, SimpleHomeView, ScriptPickerView, LearnView, ScamTypeView, QuizView } from "./home.js";
import { CallFlow, InCallContent, WarningScreen } from "./call.js";
import { DashboardView, InsightsView, HistoryView, CallDetailView, WeeklySummaryView } from "./dashboard.js";
import { SettingsView, AdvancedPage, AppStylePage, TextSizePage, FamilyPage, PinSettingsPage, PrivacyPage, DataPage, HelpPage } from "./settings.js";
import { DeveloperView } from "./developer.js";
import { SetupView } from "./setup.js";
import { TourOverlay } from "./tour.js";
import { ViewModel } from "../vm.js";
import * as R from "../logic/risk.js";
import { applyAppearance, setAppearance, getAppearance, isDark } from "./theme.js";
import { WEB } from "./webText.js";

const DEVICE_W = 426, DEVICE_H = 898;
const frameKey = "scamguard.web_frame";
const frameOn = () => { try { return localStorage.getItem(frameKey) !== "off"; } catch { return true; } };
const wide = () => innerWidth > 600;

function useVm(vm) {
  const [, force] = useState(0);
  useEffect(() => vm.subscribe(() => force((n) => n + 1)), []);
}

export function mount(vm, el) { render(html`<${Root} vm=${vm} />`, el); }

function Root({ vm }) {
  useVm(vm);
  const [framed, setFramed] = useState(wide() && frameOn());
  const [scale, setScale] = useState(1);
  const [, setTick] = useState(0);
  useEffect(() => {
    const fit = () => {
      setFramed(wide() && frameOn());
      setScale(Math.min(1, (innerHeight - 24) / DEVICE_H, (innerWidth - 24) / DEVICE_W));
    };
    fit();
    addEventListener("resize", fit);
    const clock = setInterval(() => setTick((n) => n + 1), 15_000);
    return () => { removeEventListener("resize", fit); clearInterval(clock); };
  }, []);
  const app = html`<${AppScreen} vm=${vm} framed=${framed} />`;
  const red = vm.state.warningOpen && (vm.state.phase === "inCall" || vm.state.phase === "ended");
  return html`<div id="stage" class=${framed ? "framed" : "unframed"}>
    ${wide() && html`<div id="toolbar">
      <label><input type="checkbox" checked=${framed} onChange=${(e) => { try { localStorage.setItem(frameKey, e.target.checked ? "on" : "off"); } catch { /* refused */ } setFramed(e.target.checked); }} /> ${WEB.frame}</label>
      <select aria-label=${WEB.appearance} value=${getAppearance()} onChange=${(e) => { setAppearance(e.target.value); setTick((n) => n + 1); }}>
        <option value="system">${WEB.appearance}: System</option><option value="light">${WEB.appearance}: Light</option><option value="dark">${WEB.appearance}: Dark</option></select></div>`}
    ${framed ? html`<div class="device" style=${`transform:scale(${scale})`}><div class="screen">
        <${StatusBar} light=${red} />
        <div class="camera-pill" aria-hidden="true"></div>${app}
        <div class=${"home-indicator" + (red ? " light-content" : "")} aria-hidden="true"></div></div></div>`
      : html`<div class="unframed"><div class="screen">${app}</div></div>`}</div>`;
}

function StatusBar({ light }) {
  const t = new Date().toLocaleTimeString("en-GB", { hour: "numeric", minute: "2-digit", hour12: true }).replace(/\s?[ap]m$/i, "");
  return html`<div class=${"statusbar" + (light ? " light-content" : "")} aria-hidden="true"><span>${t}</span>
    <span class="right">
      <svg width="18" height="12" viewBox="0 0 18 12"><rect x="0" y="8" width="3" height="4" rx="1" fill="currentColor"/><rect x="5" y="5.5" width="3" height="6.5" rx="1" fill="currentColor"/><rect x="10" y="3" width="3" height="9" rx="1" fill="currentColor"/><rect x="15" y="0" width="3" height="12" rx="1" fill="currentColor"/></svg>
      <svg width="17" height="12" viewBox="0 0 17 12"><path d="M8.5 2.3c2.4 0 4.6.9 6.3 2.5l1.2-1.2C14 1.7 11.4.6 8.5.6S3 1.7 1 3.6l1.2 1.2C3.9 3.2 6.1 2.3 8.5 2.3zm0 3.4c1.5 0 2.9.6 4 1.6l1.2-1.2C12.3 4.8 10.5 4 8.5 4S4.7 4.8 3.3 6.1l1.2 1.2c1.1-1 2.5-1.6 4-1.6zm0 3.4c.6 0 1.2.2 1.6.6L8.5 11.3 6.9 9.7c.4-.4 1-.6 1.6-.6z" fill="currentColor"/></svg>
      <svg width="27" height="13" viewBox="0 0 27 13"><rect x="0.5" y="0.5" width="23" height="12" rx="3.8" fill="none" stroke="currentColor" opacity="0.4"/><rect x="2" y="2" width="20" height="9" rx="2.5" fill="currentColor"/><path d="M25 4.5v4c.8-.3 1.3-1.1 1.3-2s-.5-1.7-1.3-2z" fill="currentColor" opacity="0.5"/></svg>
    </span></div>`;
}

function AppScreen({ vm, framed }) {
  const ax = vm.textSize === "xlarge";
  return html`<div id="app" data-size=${vm.textSize} class=${ax ? "ax" : ""} data-simple=${vm.simpleMode ? "yes" : "no"}>
    ${vm.gate === "setup" ? html`<${SetupView} vm=${vm} />` : html`<${RootView} vm=${vm} />`}
    ${vm.toast && html`<div class="toast" role="status" data-id="toast">${vm.toast}</div>`}</div>`;
}

const TABS = [["home", "house", "tab_home"], ["dashboard", "list", "tab_dashboard"], ["settings", "settings", "tab_settings"]];

function RootView({ vm }) {
  const tourSteps = Tour.steps(vm.tourSimple);
  const step = vm.tourStep != null ? tourSteps[vm.tourStep] : null;
  const calling = vm.state.phase !== "idle";
  const demoCall = step?.place === "call" ? ViewModel.tourDemoCall(step.target === "redWarning" || step.target === "warningButtons") : null;
  return html`
    <div class="tabs-area" aria-hidden=${calling || !!step} inert=${calling}>${tabPage(vm)}</div>
    <nav class="tabbar" aria-label="Tabs" inert=${calling}>
      ${TABS.map(([tab, icon, key]) => {
        const on = vm.nav.tab === tab;
        const label = tab === "dashboard" && vm.simpleMode ? T("tab_family") : T(key);
        return html`<button class=${on ? "on" : ""} aria-current=${on ? "page" : undefined} data-id=${"tab." + tab}
          onClick=${() => { if (on && vm.nav.paths[tab].length) vm.showPath(tab, []); else vm.go(tab); }}>
          <span class="pill"><${Icon} name=${icon} size=${24} fill=${on && tab !== "dashboard"} stroke=${on ? 2.3 : 1.8} /></span><span class="lbl">${label}</span></button>`;
      })}</nav>
    ${calling && html`<${CallFlow} vm=${vm} />`}
    ${demoCall && html`<div class="call-root" style="animation:none;z-index:21" aria-hidden="true">
      <${InCallContent} state=${demoCall} demo simple=${vm.tourSimple} />
      ${demoCall.warningOpen && html`<${WarningScreen} kind=${R.callWarningKind(demoCall)} callEnded=${false} familyName=${vm.family?.name} vm=${vm} demo />`}</div>`}
    ${step && html`<${TourOverlay} vm=${vm} steps=${tourSteps} index=${vm.tourStep} />`}`;
}

function tabPage(vm) {
  const tab = vm.nav.tab;
  const path = vm.nav.paths[tab];
  const route = path[path.length - 1];
  const dir = vm.nav.dir;
  const back = () => vm.pop();
  const key = tab + ":" + path.length + ":" + (route ? route.name + (route.id ?? "") : "root");
  let page;
  if (!route) {
    if (tab === "home") page = vm.simpleMode ? html`<${SimpleHomeView} vm=${vm} dir=${dir} />` : html`<${HomeView} vm=${vm} dir=${dir} />`;
    else if (tab === "dashboard") {
      page = vm.simpleMode && vm.pinSet && !vm.unlockOpen
        ? html`<${Page} title="" inline dir=${dir} id="familyPin"><${PinGate} vm=${vm} onOpen=${() => { vm.unlockOpen = true; vm.notify(); }} /></${Page}>`
        : html`<${DashboardView} vm=${vm} dir=${dir} />`;
    } else page = html`<${SettingsView} vm=${vm} dir=${dir} />`;
  } else {
    const p = { vm, dir, onBack: back, id: route.id };
    const views = {
      pick: ScriptPickerView, learn: LearnView, learnType: ScamTypeView, quiz: QuizView,
      call: CallDetailView, insights: InsightsView, history: HistoryView, weekly: WeeklySummaryView,
      privacy: PrivacyPage, data: DataPage, help: HelpPage, appStyle: AppStylePage, textSize: TextSizePage, family: FamilyPage,
      advanced: AdvancedPage, pin: PinSettingsPage, developer: vm.devUnlocked ? DeveloperView : SettingsView,
    };
    const V = views[route.name];
    page = html`<${V} ...${p} demo=${route.demo} />`;
  }
  return html`<div key=${key} style="position:absolute;inset:0">${page}</div>`;
}

export { applyAppearance, isDark };

// Ports of ios-app UI/SettingsView.swift and the settings pages in UI/SimpleViews.swift: the grouped Settings menu
// (Simple: everyday rows, Help, Advanced behind the PIN; Standard: everything), App style, Text size, family contact,
// PIN lock, privacy level, My data, Help & about (tap the version 7 times for the Developer view).
import { html, useState, useEffect, Icon, RiskIcon, BigButton, Page, Section, NavRow, ToggleRow, ChoiceCard, InfoSheet, YesNoSheet, NewPinFields, PinGate } from "./components.js";
import { T } from "../logic/strings.js";
import * as R from "../logic/risk.js";
import { connectionStatus, CONNECTION_KEY } from "../logic/developer.js";
import { phoneLink } from "./call.js";
import { WEB } from "./webText.js";

const textSizeKey = (s) => (s === "xlarge" ? "text_xlarge" : s === "large" ? "text_large" : "text_normal");
const callsCount = (n) => (n === 1 ? T("calls_one") : T("calls_many", n));

function Everyday({ vm }) {
  return html`<${Section}>
    <${NavRow} icon="smile" title=${T("set_app_style")} value=${T(vm.simpleMode ? "style_simple" : "style_standard")} id="rowAppStyle" onClick=${() => vm.push({ name: "appStyle" })} />
    <${NavRow} icon="text-size" title=${T("set_text_size")} value=${T(textSizeKey(vm.textSize))} id="rowTextSize" onClick=${() => vm.push({ name: "textSize" })} />
    <${ToggleRow} title=${T("set_read_aloud")} line=${T("set_read_aloud_line")} on=${vm.readAloud} onChange=${(v) => vm.setReadAloud(v)} id="readAloud" />
    <${NavRow} icon="phone" title=${T("set_family")} value=${vm.family?.name ?? T("not_set")} id="rowFamily" onClick=${() => vm.push({ name: "family" })} />
  </${Section}>`;
}

function ConnectionRow({ vm }) {
  const [checking, setChecking] = useState(false);
  const st = connectionStatus(vm.privacyLevel, vm.connection);
  const color = st === "connected" ? "var(--safe)" : st === "cantConnect" ? "var(--scam)" : "var(--muted)";
  return html`<div class="cell has-icon" style="flex-direction:column;align-items:stretch" data-id="connectionStatus">
    <div class="row" style="gap:14px"><span class="row-icon hide-ax"><${Icon} name="antenna" /></span>
      <span class="row-text"><span class="row-title">${T("set_connection")}</span>
        <span class="f-body" style=${`color:${color}`}>${T(checking ? CONNECTION_KEY.checking : CONNECTION_KEY[st])}</span>
        ${st === "cantConnect" && html`<span class="f-body">${T("conn_fail_line")}</span>`}</span></div>
    ${st === "cantConnect" && html`<${BigButton} title=${T(checking ? "status_checking" : "btn_try_again")} prominent=${false} disabled=${checking} id="connectionRetry"
      onClick=${async () => { setChecking(true); await vm.refreshConnection(); setChecking(false); }} />`}</div>`;
}

function AdvancedSections({ vm, simple }) {
  return html`
    <${Section}>
      <${ToggleRow} title=${T("set_show_careful")} line=${T("set_show_careful_line")} on=${vm.showBeCareful} onChange=${(v) => vm.setShowBeCareful(v)} id="showCareful" />
      <${NavRow} icon="lock" title=${T("set_pin")} value=${T(vm.pinSet ? "set_pin_line_on" : "set_pin_line_off")} id="rowPin" onClick=${() => vm.push({ name: "pin" })} />
    </${Section}>
    <${Section} header=${T("set_group_checking")}>
      <${NavRow} icon="shield-check" title=${T("set_how_checked")} value=${R.choiceTitle(vm.privacyLevel, simple)} tour="privacyRow" id="rowPrivacy" onClick=${() => vm.push({ name: "privacy" })} />
      <${ConnectionRow} vm=${vm} />
    </${Section}>
    <${Section}><${NavRow} icon="user" title=${T("set_my_data")} value=${callsCount(vm.store.calls().length)} id="rowData" onClick=${() => vm.push({ name: "data" })} /></${Section}>`;
}

const DeveloperRow = ({ vm }) => html`<${Section}><${NavRow} icon="hammer" title=${T("set_developer")} id="rowDeveloper" onClick=${() => vm.push({ name: "developer" })} /></${Section}>`;

export function SettingsView({ vm, dir }) {
  useEffect(() => { vm.refreshConnection(); }, []);
  return html`<${Page} title=${T("tab_settings")} dir=${dir} id="settings">
    <div class="list">
      <${Everyday} vm=${vm} />
      ${vm.simpleMode ? html`<${Section}>
          <${NavRow} icon="help" title=${T("set_help")} id="rowHelp" onClick=${() => vm.push({ name: "help" })} />
          <${NavRow} icon=${vm.pinSet ? "lock" : "settings"} title=${T("set_advanced")} value=${T("set_advanced_line")} id="rowAdvanced" onClick=${() => vm.push({ name: "advanced" })} /></${Section}>`
        : html`<${AdvancedSections} vm=${vm} simple=${false} />
          <${Section}><${NavRow} icon="help" title=${T("set_help")} id="rowHelp" onClick=${() => vm.push({ name: "help" })} /></${Section}>
          ${vm.devUnlocked && html`<${DeveloperRow} vm=${vm} />`}`}
    </div></${Page}>`;
}

export function AdvancedPage({ vm, dir, onBack }) {
  useEffect(() => { vm.refreshConnection(); }, []);
  if (vm.pinSet && !vm.unlockOpen) {
    return html`<${Page} title="" inline back onBack=${onBack} dir=${dir} id="advancedPin"><${PinGate} vm=${vm} onOpen=${() => { vm.unlockOpen = true; vm.notify(); }} /></${Page}>`;
  }
  return html`<${Page} title=${T("set_advanced")} back onBack=${onBack} dir=${dir} id="advanced">
    <div class="list"><${AdvancedSections} vm=${vm} simple=${true} />${vm.devUnlocked && html`<${DeveloperRow} vm=${vm} />`}</div></${Page}>`;
}

export function AppStylePage({ vm, dir, onBack }) {
  const [askPin, setAskPin] = useState(false);
  const pick = (simple) => {
    if (simple === vm.simpleMode) return;
    if (vm.appStyleNeedsPin(simple)) setAskPin(true); else vm.setAppStyle(simple);
  };
  if (askPin) {
    return html`<${Page} title="" inline back onBack=${() => setAskPin(false)} dir=${dir} id="appStylePin">
      <${PinGate} vm=${vm} onOpen=${() => { setAskPin(false); vm.setAppStyle(false); }} /></${Page}>`;
  }
  return html`<${Page} title=${T("set_app_style")} back onBack=${onBack} dir=${dir} id="appStyle">
    <div class="content" role="radiogroup">
      <p class="f-body muted" style="margin:0">${T("style_page_line")}</p>
      <${ChoiceCard} selected=${!vm.simpleMode} title=${T("style_standard")} line=${T("style_standard_line")} id="style_standard" onClick=${() => pick(false)} />
      <${ChoiceCard} selected=${vm.simpleMode} title=${T("style_simple")} line=${T("style_simple_line")} id="style_simple" onClick=${() => pick(true)} /></div></${Page}>`;
}

export function TextSizeChoices({ current, onPick }) {
  return html`<div class="col gap12" role="radiogroup"><h2 class="f-headline" style="margin:0">${T("set_text_size")}</h2>
    <${ChoiceCard} selected=${current === "normal"} title=${T("text_normal")} line=${T("text_normal_line")} id="text_normal" onClick=${() => onPick("normal")} />
    <${ChoiceCard} selected=${current === "large"} title=${T("text_large")} line=${T("text_size_sample")} id="text_large" onClick=${() => onPick("large")} />
    <${ChoiceCard} selected=${current === "xlarge"} title=${T("text_xlarge")} line=${T("text_size_sample")} id="text_xlarge" onClick=${() => onPick("xlarge")} /></div>`;
}

export function TextSizePage({ vm, dir, onBack }) {
  return html`<${Page} title=${T("set_text_size")} back onBack=${onBack} dir=${dir} id="textSize">
    <div class="content"><${TextSizeChoices} current=${vm.textSize} onPick=${(s) => vm.setTextSize(s)} /></div></${Page}>`;
}

/** Name + phone number fields (setup and Settings → My family contact). */
export function ContactFields({ name, phone, onName, onPhone }) {
  return html`<div class="col gap12">
    <label class="col"><span class="field-label">${T("field_name")}</span>
      <input class="field" data-id="familyName" value=${name} autocomplete="off" onInput=${(e) => onName(e.target.value)} /></label>
    <label class="col"><span class="field-label">${T("field_phone")}</span>
      <input class="field" data-id="familyPhone" type="tel" inputmode="tel" autocomplete="off" placeholder=${T("field_phone_hint")} value=${phone}
        onInput=${(e) => onPhone(e.target.value)} /></label></div>`;
}

export function FamilyPage({ vm, dir, onBack }) {
  const f = vm.family;
  const [name, setName] = useState(f && f.name !== f.phone ? f.name : "");
  const [phone, setPhone] = useState(f?.phone ?? "");
  const [note, setNote] = useState(null);
  return html`<${Page} title=${T("set_family")} back onBack=${onBack} dir=${dir} id="family">
    <div class="content" style="gap:16px">
      <p class="f-body muted" style="margin:0">${T("family_page_line")}</p>
      <${ContactFields} name=${name} phone=${phone} onName=${(v) => { setName(v); setNote(null); }} onPhone=${(v) => { setPhone(v); setNote(null); }} />
      <${BigButton} title=${T("btn_save")} id="familySave" onClick=${() => {
        if (!phone.trim()) setNote(T("family_need_phone")); else { vm.setFamily(name, phone); setNote(T("saved") + " ✓"); }
      }} />
      ${note && html`<p class="f-bodys" role="status" style="margin:0" data-id="familyNote">${note}</p>`}
      ${vm.family && html`<${BigButton} title=${T("btn_remove_contact")} prominent=${false} danger id="familyRemove"
        onClick=${() => { setName(""); setPhone(""); vm.setFamily("", ""); setNote(null); }} />`}
    </div></${Page}>`;
}

export function PinSettingsPage({ vm, dir, onBack }) {
  const [newPin, setNewPin] = useState(""), [changing, setChanging] = useState(false), [done, setDone] = useState(false);
  return html`<${Page} title=${T("set_pin")} back onBack=${onBack} dir=${dir} id="pinSettings">
    <div class="content" style="gap:16px">
      <p class="f-body muted" style="margin:0">${T("pin_setup_line")}</p>
      <p class="f-title3s" style="margin:0">${T(vm.pinSet ? "set_pin_line_on" : "set_pin_line_off")}</p>
      ${changing || !vm.pinSet ? html`<${NewPinFields} onPin=${setNewPin} />
          <${BigButton} title=${T("btn_save")} id="pinSave" disabled=${!newPin} onClick=${async () => { await vm.setPin(newPin); setChanging(false); setDone(true); }} />`
        : html`${done && html`<p class="f-bodys" style="margin:0">${T("saved")} ✓</p>`}
          <${BigButton} title=${T("btn_change_pin")} prominent=${false} id="pinChange" onClick=${() => { setChanging(true); setDone(false); }} />`}
      ${vm.pinSet && html`<${BigButton} title=${T("btn_turn_off_pin")} prominent=${false} danger id="pinOff" onClick=${() => { vm.clearPin(); setChanging(true); setDone(false); }} />`}
    </div></${Page}>`;
}

export function PrivacyPage({ vm, dir, onBack }) {
  const [info, setInfo] = useState(false);
  return html`<${Page} title=${T("set_how_checked")} back onBack=${onBack} dir=${dir} id="privacy">
    <div class="content" role="radiogroup">
      ${R.PRIVACY_LEVELS.map((l) => html`<${ChoiceCard} selected=${l === vm.privacyLevel} title=${R.choiceTitle(l, vm.simpleMode)} line=${T(R.PRIVACY[l].plain)}
        id=${"privacy_" + l} onClick=${() => vm.setPrivacyLevel(l)} />`)}
      <p class="f-body muted" style="margin:0">${T("privacy_applies_next")}</p>
      <p class="f-body muted" style="margin:0">${T("privacy_english_only")}</p>
      ${!vm.simpleMode && html`<${BigButton} title=${T("setup_more_about")} prominent=${false} id="privacyInfo" onClick=${() => setInfo(true)} />`}
    </div>
    ${info && html`<${InfoSheet} title=${T("set_how_checked")} text=${R.PRIVACY_LEVELS.map((l) => `${R.PRIVACY[l].badge}: ${R.PRIVACY[l].explanation}`).join("\n\n")} onClose=${() => setInfo(false)} />`}
  </${Page}>`;
}

export function DataPage({ vm, dir, onBack }) {
  const [confirm, setConfirm] = useState(false), [info, setInfo] = useState(false);
  const calls = vm.store.calls().length, labels = vm.store.labelCount();
  return html`<${Page} title=${T("set_my_data")} back onBack=${onBack} dir=${dir} id="data">
    <div class="list">
      <div class="group"><button class="cell" onClick=${() => setInfo(true)} aria-label=${T("a11y_about", T("set_my_data"))}>
        <span class="f-body brand grow">${T("what_does_this_mean")}</span><span class="brand" style="font-size:var(--t-title3)"><${Icon} name="circle-info" /></span></button></div>
      <${Section}>
        <${ToggleRow} title=${T("data_save_sentence")} line=${T("data_save_sentence_line")} on=${vm.saveSentence} onChange=${(v) => vm.setSaveSentence(v)} id="saveSentence" />
        <${ToggleRow} title=${T("data_help_improve")} line=${T("data_help_improve_line")} on=${vm.helpImprove} onChange=${(v) => vm.setHelpImprove(v)} id="helpImprove" />
      </${Section}>
      <${Section} footer=${T("data_nothing_uploaded")}>
        <div class="cell f-body" role="text"><span class="grow">${T("data_calls_saved")}</span><span class="muted">${calls}</span></div>
        <div class="cell f-body" role="text"><span class="grow">${T("data_labels_kept")}</span><span class="muted">${labels}</span></div></${Section}>
      <button class="btn ios-prominent red" data-id="deleteHistory" disabled=${calls === 0 && labels === 0} style=${calls === 0 && labels === 0 ? "opacity:0.5" : ""}
        onClick=${() => setConfirm(true)}>${T("btn_delete_history")}</button>
    </div>
    ${info && html`<${InfoSheet} title=${T("set_my_data")} text=${T("data_info")} onClose=${() => setInfo(false)} />`}
    ${confirm && html`<${YesNoSheet} title=${T("delete_title")} text=${T("delete_text")} yes=${T("btn_yes_delete")} no=${T("btn_no_keep")} danger
      onYes=${() => { setConfirm(false); vm.deleteHistory(); }} onNo=${() => setConfirm(false)} />`}
  </${Page}>`;
}

export function PrivacyPromise() {
  return html`<div class="row" style="gap:14px;padding:16px;border-radius:20px;background:var(--brand-container);color:var(--on-brand-container)" role="text">
    <span style="font-size:26px"><${Icon} name="lock" fill /></span><span class="f-bodys grow">${T("privacy_promise")}</span></div>`;
}

const Bullet = ({ text }) => html`<div class="cell f-body" style="align-items:baseline;gap:8px"><span class="brand">•</span><span class="grow">${text}</span></div>`;

export function HelpPage({ vm, dir, onBack }) {
  const [taps, setTaps] = useState(0);
  const [message, setMessage] = useState(null);
  const tapVersion = () => {
    const t = vm.developerTap(taps);
    setTaps(t.count);
    const text = { stepsLeft: T("dev_taps_left", t.left), unlocked: T("dev_unlocked"), already: T("dev_already") }[t.outcome];
    if (!text) return;
    setMessage(text);
    clearTimeout(HelpPage.timer);
    HelpPage.timer = setTimeout(() => setMessage(null), 2000);
  };
  const colour = (level, name, meaning) => html`<div class="cell" role="text"><${RiskIcon} level=${level} size=${28} />
    <span class="row-text"><span class="row-title">${name}</span><span class="row-value">${meaning}</span></span></div>`;
  return html`<${Page} title=${T("set_help")} back onBack=${onBack} dir=${dir} id="help">
    <div class="list">
      <${PrivacyPromise} />
      <${Section} header=${T("help_how")}>${["help_b1", "help_b2", "help_b3"].map((k) => html`<${Bullet} text=${T(k)} />`)}</${Section}>
      <${Section} header=${T("help_colours")}>
        ${colour("green", T("risk_safe"), T("help_safe_line"))}${colour("yellow", T("risk_careful"), T("help_careful_line"))}
        ${colour("red", T("risk_scam"), T("help_scam_line"))}${colour("error", T("risk_not_checked"), T("help_notchecked_line"))}</${Section}>
      <${Section} header=${T("help_again")} cls="clear"><div class="col gap12">
        <${BigButton} title=${T("btn_replay_tour")} id="replayTour" onClick=${() => vm.startTour()} />
        <${BigButton} title=${T("btn_run_setup")} prominent=${false} id="runSetup" onClick=${() => vm.runSetupAgain()} /></div></${Section}>
      <${Section} header=${T("about_title")}><div class="col gap12" style="padding:16px 20px">
        <p class="f-body" style="margin:0">${T("about_who")}</p><p class="f-bodys" style="margin:0">${T("about_not_gov")}</p><p class="f-body" style="margin:0">${T("about_verify")}</p>
        <${BigButton} title=${T("btn_call_1799")} id="about1799" onClick=${() => phoneLink(vm, "tel:1799", WEB.desktopCall("1799"))} />
        <${BigButton} title=${T("btn_open_scamshield")} prominent=${false} id="aboutScamShield" href="https://www.scamshield.gov.sg/" target="_blank" />
        <button class="btn f-body muted" style="justify-content:flex-start;min-height:44px" data-id="aboutVersion" onClick=${tapVersion}>${T("about_version")} 1.0</button>
        ${message && html`<p class="f-bodys brand" role="status" style="margin:0" data-id="devToast">${message}</p>`}</div></${Section}>
      ${!vm.simpleMode && html`<${Section} header=${T("help_privacy_levels")}>${R.PRIVACY_LEVELS.map((l) => html`<${Bullet} text=${`${R.PRIVACY[l].badge} (${T(R.PRIVACY[l].tag)}): ${T(R.PRIVACY[l].plain)}`} />`)}</${Section}>`}
    </div></${Page}>`;
}

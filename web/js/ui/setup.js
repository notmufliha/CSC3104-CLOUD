// Port of ios-app UI/SetupViews.swift: first-launch setup. "Who will use this phone?" -> Me (Standard + 5 screens),
// Me, but simpler (Simple + Welcome + recommended), An older family member (their settings, contact, PIN, hand over).
import { html, useState, useEffect, Icon, RiskIcon, BigButton, ChoiceCard, ToggleRow, InfoSheet, YesNoSheet, NewPinFields } from "./components.js";
import { T } from "../logic/strings.js";
import * as Onb from "../logic/onboarding.js";
import * as R from "../logic/risk.js";
import { PrivacyPromise, TextSizeChoices, ContactFields } from "./settings.js";

const TITLE = { who: "who_title", handover: "handover_title_noname", welcome: "setup_welcome_title", recommended: "setup_rec_title",
  privacy: "setup_privacy_title", data: "setup_data_title", helper: "setup_helper_title", pin: "pin_setup_title", done: "setup_done_title" };
const LINE = { who: "who_line", handover: "handover_line", welcome: "setup_welcome_line", recommended: "setup_rec_line",
  privacy: "setup_privacy_line", data: "setup_data_line", helper: "setup_helper_line", pin: "pin_setup_line", done: "setup_done_line" };
const ICON = { recommended: "star", privacy: "lock", pin: "lock", data: "user", helper: "settings", family: "phone", handover: "heart" };

export function SetupView({ vm }) {
  const c = vm.setupChoices;
  const steps = Onb.steps(c);
  const step = vm.setupStep;
  const index = Math.max(steps.indexOf(step), 0);
  const previous = Onb.previous(step, c);
  const [askWho, setAskWho] = useState(false);
  const [leave, setLeave] = useState(false);
  const [dir, setDir] = useState("none");
  useEffect(() => { document.activeElement?.blur?.(); }, [step]);   // a new screen drops the keyboard (the "1234" fix)
  const go = (to) => { if (!to) return; setDir(steps.indexOf(to) < index ? "pop" : "push"); vm.setSetupStep(to); };
  const helper = c.path === "helper";
  const name = c.familyName.trim();
  const title = step === "handover" && name ? T("handover_title", name) : T(step === "family" ? (helper ? "setup_helper_family_title" : "setup_family_title") : TITLE[step]);
  const line = T(step === "family" ? (helper ? "setup_helper_family_line" : "setup_family_line") : LINE[step]);
  const [n, of] = Onb.progress(step, c);
  let buttons;
  switch (step) {
    case "who": buttons = html`
      ${askWho && c.path == null && html`<p class="f-bodys center" role="alert" style="margin:0">${T("who_pick_one")}</p>`}
      <${BigButton} title=${T("btn_next")} id="setupNext" onClick=${() => (c.path == null ? setAskWho(true) : go(Onb.next(step, c)))} />`; break;
    case "welcome": buttons = html`<${BigButton} title=${T("btn_get_started")} id="setupNext" onClick=${() => go(Onb.next(step, c))} />`; break;
    case "recommended": buttons = html`
      <${BigButton} title=${T("btn_use_recommended")} id="setupRecommended" onClick=${() => { vm.updateSetup((x) => Onb.recommended(x)); vm.finishSetup(true); }} />
      <${BigButton} title=${T("btn_choose_myself")} prominent=${false} id="setupCustom" onClick=${() => { vm.updateSetup((x) => ({ ...x, path: "custom" })); go("privacy"); }} />`; break;
    case "family": {
      const last = Onb.next(step, c) == null;
      buttons = html`<${BigButton} title=${T(last ? "btn_save_finish" : "btn_next")} id="setupFamilyNext" onClick=${() => (last ? vm.finishSetup(true) : go(Onb.next(step, c)))} />
        <button class="link-btn" data-id="setupFamilySkip" onClick=${() => { vm.updateSetup((x) => ({ ...x, familyName: "", familyPhone: "" })); last ? vm.finishSetup(true) : go(Onb.next(step, vm.setupChoices)); }}>${T("btn_skip_for_now")}</button>`;
      break;
    }
    case "handover": buttons = html`<${BigButton} title=${T("btn_start_tour")} id="setupTour" onClick=${() => vm.finishSetup(true)} />
      <button class="link-btn" data-id="setupNoTour" onClick=${() => vm.finishSetup(false)}>${T("btn_finish_no_tour")}</button>`; break;
    case "done": buttons = html`<${BigButton} title=${T("btn_show_me_around")} id="setupTour" onClick=${() => vm.finishSetup(true)} />
      <${BigButton} title=${T("btn_skip_tour")} prominent=${false} id="setupNoTour" onClick=${() => vm.finishSetup(false)} />`; break;
    default: buttons = html`<${BigButton} title=${T("btn_next")} id="setupNext" onClick=${() => go(Onb.next(step, c))} />`;
  }
  return html`<div class="page" data-screen=${"setup-" + step} style="padding-top:var(--safe-top)">
    <div class="row" style="padding:0 16px;min-height:56px">
      ${previous ? html`<button class="link-btn" aria-label=${T("a11y_back")} data-id="setupBack" onClick=${() => go(previous)}>‹ ${T("btn_back")}</button>`
        : vm.setupIsReplay && html`<button class="link-btn" onClick=${() => setLeave(true)}>‹ ${T("btn_back")}</button>`}
      <span class="grow"></span>
      ${step !== "done" && step !== "handover" && html`<button class="link-btn" data-id="setupSkip" onClick=${() => vm.finishSetup(false)}>${T("btn_skip")}</button>`}</div>
    <div class=${"scroller" + (dir === "push" ? " page-anim-push" : dir === "pop" ? " page-anim-pop" : "")} key=${step} style="padding-top:0">
      <div class="col" style="max-width:520px;margin:0 auto;padding:8px 24px 16px;gap:14px;align-items:center;text-align:center">
        ${(step === "welcome" || step === "who") && html`<div style="height:16px"></div>`}
        ${step === "welcome" ? html`<img src="icons/logo.png" alt="" width="104" height="104" style="border-radius:24px" />`
          : step !== "who" && html`<div class="icon-circle"><${Icon} name=${ICON[step] ?? "circle-check"} fill=${step !== "helper"} /></div>`}
        <h1 class="f-title" style="margin:0" tabindex="-1">${title}</h1>
        <p class="f-title3 muted" style="margin:0">${line}</p>
        ${(step === "welcome" || step === "handover") && html`<div style="width:100%;text-align:left"><${PrivacyPromise} /></div>`}
        <div style="width:100%;text-align:left;margin-top:4px">${content(vm, step, c)}</div></div></div>
    <div class="col gap12" style="padding:12px 24px calc(12px + var(--safe-bottom));align-items:center">
      ${c.path != null && html`<div class="dots" role="img" aria-label=${T("setup_step", n, of)}>${steps.map((_, i) => html`<span class=${i === index ? "on" : ""}></span>`)}</div>`}
      ${buttons}</div>
    ${leave && html`<${YesNoSheet} title=${T("setup_leave_title")} text=${T("setup_leave_replay")} yes=${T("btn_leave")} no=${T("btn_stay")}
      onYes=${() => { setLeave(false); vm.leaveSetup(); }} onNo=${() => setLeave(false)} />`}</div>`;
}

function content(vm, step, c) {
  switch (step) {
    case "who": {
      const picked = c.path === "custom" ? "simple" : c.path;
      const pick = (p) => { const next = Onb.choose(vm.setupChoices, p); vm.updateSetup((x) => Onb.choose(x, p)); vm.previewTextSize(next.textSize); };
      return html`<div class="col gap12" role="radiogroup">
        <${ChoiceCard} selected=${picked === "standard"} title=${T("who_me")} line=${T("who_me_line")} id="who_me" onClick=${() => pick("standard")} />
        <${ChoiceCard} selected=${picked === "simple"} title=${T("who_simpler")} line=${T("who_simpler_line")} id="who_simpler" onClick=${() => pick("simple")} />
        <${ChoiceCard} selected=${picked === "helper"} title=${T("who_family")} line=${T("who_family_line")} id="who_family" onClick=${() => pick("helper")} /></div>`;
    }
    case "recommended": return html`<div class="card" style="padding:8px 0;gap:0">${["setup_rec_b1", "setup_rec_b2", "setup_rec_b3"].map((k) => html`
      <div class="row" style="gap:14px;padding:8px 16px"><${RiskIcon} level="green" size=${30} /><span class="f-body grow">${T(k)}</span></div>`)}</div>`;
    case "privacy": return html`<${PrivacyChoices} vm=${vm} c=${c} />`;
    case "data": return html`<div class="group">
      <${ToggleRow} title=${T("data_save_sentence")} line=${T("data_save_sentence_line")} on=${c.saveSentence} onChange=${(v) => vm.updateSetup((x) => ({ ...x, saveSentence: v }))} />
      <${ToggleRow} title=${T("data_help_improve")} line=${T("data_help_improve_line")} on=${c.helpImprove} onChange=${(v) => vm.updateSetup((x) => ({ ...x, helpImprove: v }))} /></div>`;
    case "helper": return html`<div class="col gap16">
      <${TextSizeChoices} current=${c.textSize} onPick=${(size) => { vm.updateSetup((x) => ({ ...x, textSize: size, textSizeChosen: true })); vm.previewTextSize(size); }} />
      <div class="group"><${ToggleRow} title=${T("set_read_aloud")} line=${T("set_read_aloud_line")} on=${c.readAloud}
        onChange=${(v) => vm.updateSetup((x) => ({ ...x, readAloud: v, readAloudChosen: true }))} /></div></div>`;
    case "family": return html`<${ContactFields} name=${c.familyName} phone=${c.familyPhone}
      onName=${(v) => { vm.setupChoices.familyName = v; }} onPhone=${(v) => { vm.setupChoices.familyPhone = v; }} />`;
    case "pin": return html`<${NewPinFields} onPin=${(pin) => { vm.setupChoices.pin = pin; }} />`;
    case "done": return html`<${SetupSummary} c=${c} />`;
    default: return null;
  }
}

function PrivacyChoices({ vm, c }) {
  const [info, setInfo] = useState(false);
  return html`<div class="col gap12" role="radiogroup">
    ${R.PRIVACY_LEVELS.map((l) => html`<${ChoiceCard} selected=${l === c.privacy} title=${R.choiceTitle(l, Onb.isSimple(c))} line=${T(R.PRIVACY[l].plain)} id=${"privacy_" + l}
      onClick=${() => vm.updateSetup((x) => ({ ...x, privacy: l }))} />`)}
    <${BigButton} title=${T("setup_more_about")} prominent=${false} id="privacyInfo" onClick=${() => setInfo(true)} />
    ${info && html`<${InfoSheet} title=${T("set_how_checked")} onClose=${() => setInfo(false)}
      text=${R.PRIVACY_LEVELS.map((l) => `${T(R.PRIVACY[l].tag)}: ${T(R.PRIVACY[l].plain)}`).join("\n\n") + "\n\n" + T("privacy_english_only")} />`}</div>`;
}

function SetupSummary({ c }) {
  const onOff = (b) => T(b ? "on" : "off");
  const rows = [[T("set_app_style"), T(Onb.isSimple(c) ? "style_simple" : "style_standard")],
    [T("set_text_size"), T(c.textSize === "xlarge" ? "text_xlarge" : c.textSize === "large" ? "text_large" : "text_normal")], [T("sum_privacy"), T(R.PRIVACY[c.privacy].tag)]];
  if (c.path === "custom" || c.path === "standard") rows.push([T("data_save_sentence"), onOff(c.saveSentence)], [T("data_help_improve"), onOff(c.helpImprove)]);
  const fam = c.familyName || c.familyPhone;
  if (c.path === "helper" || c.familyPhone) rows.push([T("sum_family"), fam || T("not_set")]);
  if (c.path === "helper") rows.push([T("sum_pin"), onOff(c.pin.length === 4)]);
  return html`<div class="group">${rows.map(([k, v]) => html`<div class="cell f-body" role="text"><span class="grow">${k}</span><span class="muted">${v}</span></div>`)}</div>`;
}

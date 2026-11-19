// Port of ios-app UI/DashboardViews.swift (Dashboard, Insights, History, Call detail) and UI/WeeklyViews.swift.
// Everything comes from this browser's history; nothing is uploaded.
import { html, useState, Icon, RiskIcon, BigButton, Page, EmptyState, Segmented, Section, InfoSheet, ToggleRow, copyText } from "./components.js";
import { Donut, StackedBars, HorizontalBars, SegmentedBar, RiskLine, HourChart, Scatter, Sparkline, SliceKey, Legend } from "./charts.js";
import { T } from "../logic/strings.js";
import * as R from "../logic/risk.js";
import { compute, PlainWords, whenText, dayLabel, daysAgo, riskiestSeq, whatHappened, duration, hhmm } from "../logic/dashboard.js";
import { WEB } from "./webText.js";
import { tap } from "./alerts.js";

const periodStore = { get: () => { try { return localStorage.getItem("scamguard.dashboardPeriod") || "week"; } catch { return "week"; } },
  set: (v) => { try { localStorage.setItem("scamguard.dashboardPeriod", v); } catch { /* refused */ } } };

const resultSlices = (s) => [
  { label: T("risk_safe"), value: s.safe, color: "var(--safe)", level: "green" },
  { label: T("risk_careful"), value: s.unsure, color: "var(--warn)", level: "yellow" },
  { label: T("risk_scam"), value: s.scam, color: "var(--scam)", level: "red" }];

function PeriodSwitcher({ period, setPeriod }) {
  return html`<${Segmented} label=${T("ins_title")} value=${period} onChange=${(v) => { periodStore.set(v); setPeriod(v); }}
    options=${[["week", T("period_week")], ["month", T("period_month")], ["all", T("period_all")]]} />`;
}

function IconBadge({ name, color, fill }) {
  return html`<span style=${`width:48px;height:48px;border-radius:50%;display:grid;place-items:center;flex:none;font-size:22px;color:${color};background:color-mix(in srgb, ${color} 16%, transparent)`}>
    <${Icon} name=${name} fill=${fill} /></span>`;
}

export function CallListRow({ call, spark, onClick }) {
  const big = document.getElementById("app")?.dataset.size === "xlarge";
  return html`<button class="cell" style="gap:10px;padding:8px 20px;min-height:56px" onClick=${onClick} aria-label=${`${R.verdictWord(call.finalVerdict)}, ${whenText(call.endedAt)}`}>
    <${RiskIcon} level=${R.savedLevel(call.finalVerdict)} size=${28} />
    <span class="f-headline" style="white-space:nowrap">${R.verdictWord(call.finalVerdict)}</span><span class="grow"></span>
    ${spark && !big && html`<${Sparkline} values=${spark} />`}
    <span class="f-body muted" style="min-width:96px;text-align:right;white-space:nowrap">${whenText(call.endedAt)}</span>
    <${Icon} name="chevron-right" cls="chev" stroke=${2.5} /></button>`;
}

export function WeeklyButton({ vm }) {
  return html`<button class="btn big outline" data-id="weeklyButton" onClick=${() => { tap(); vm.push({ name: "weekly" }); }}><${Icon} name="share" />${T("btn_weekly")}</button>`;
}

export function DashboardView({ vm, dir }) {
  const [period, setPeriod] = useState(periodStore.get());
  const calls = vm.store.calls();
  const s = compute(calls, vm.store.labelRows(), period, Date.now());
  const top = PlainWords.topWarning(s);
  const goHome = () => vm.showPath("home", []);
  return html`<${Page} title=${T(vm.simpleMode ? "tab_family" : "dash_title")} dir=${dir} id="dashboard">
    <div class="content p20">
      ${!calls.length ? html`<div data-tour="dashboardHero"><${EmptyState} text=${T("dash_empty")} goHome=${goHome} /></div><${WeeklyButton} vm=${vm} />` : html`
        <${PeriodSwitcher} period=${period} setPeriod=${setPeriod} />
        <div class="card" data-tour="dashboardHero">
          <h2 class="f-largeTitle" style="margin:0">${PlainWords.hero(s.scam, s.total, period)}</h2>
          <div class="row" style="gap:20px"><${Donut} slices=${resultSlices(s)} centerLabel=${T("dash_calls_center")} />
            <div class="col" style="gap:6px">${resultSlices(s).map((x) => html`<span class="row" style="gap:8px"><${SliceKey} slice=${x} /><span class="f-body">${x.value} ${x.label}</span></span>`)}</div></div></div>
        <${WeeklyButton} vm=${vm} />
        <div class="card p0">
          <div class="row" style="padding:8px 20px 0"><h2 class="f-title2s grow" style="margin:0">${T("home_recent")}</h2>
            <button class="link-btn" onClick=${() => vm.push({ name: "history" })}>${T("btn_see_all")}</button></div>
          <div class="group" style="background:transparent;padding-bottom:8px">${calls.slice(0, 3).map((c) => html`<${CallListRow} call=${c} onClick=${() => vm.push({ name: "call", id: c.id })} />`)}</div></div>
        <button class="btn card" style="text-align:left;align-items:stretch" onClick=${() => vm.push({ name: "insights" })}>
          <h2 class="f-title2s" style="margin:0">${T("dash_top_sign")}</h2>
          <span class="row" style="gap:14px"><${IconBadge} name=${top ? "alert-fill" : "check-fill"} color=${top ? "var(--scam)" : "var(--safe)"} />
            <span class="f-headline grow">${top ?? T("dash_no_signs_period", PlainWords.periodPhrase(period))}</span><${Icon} name="chevron-right" cls="chev" stroke=${2.5} /></span></button>
        <button class="btn card brandc" style="flex-direction:row;align-items:center;gap:14px;text-align:left" onClick=${() => vm.push({ name: "insights" })}>
          <${IconBadge} name="star" color="var(--brand)" fill /><span class="f-title2s grow">${T("dash_more_insights")}</span><${Icon} name="chevron-right" stroke=${2.5} /></button>`}
    </div></${Page}>`;
}

function InsightSection({ title, takeaway, info, children }) {
  const [show, setShow] = useState(false);
  return html`<div class="col gap8">
    <div class="row"><h2 class="f-title2s grow" style="margin:0">${title}</h2>
      <button class="btn" style="width:56px;height:56px;color:var(--brand);font-size:var(--t-title3)" aria-label=${T("a11y_about", title)} onClick=${() => setShow(true)}><${Icon} name="circle-info" /></button></div>
    <p class="f-body" style="margin:0">${takeaway}</p>
    <div class="card">${children}</div>
    ${show && html`<${InfoSheet} title=${title} text=${info} onClose=${() => setShow(false)} />`}</div>`;
}
const AxisEnds = ({ left, right }) => html`<div class="row f-body muted"><span class="grow">${left}</span><span>${right}</span></div>`;

export function InsightsView({ vm, dir, onBack }) {
  const [period, setPeriod] = useState(periodStore.get());
  const s = compute(vm.store.calls(), vm.store.labelRows(), period, Date.now());
  let body;
  if (s.isEmpty) body = html`<${EmptyState} text=${T("ins_none_period", PlainWords.periodPhrase(period))} />`;
  else {
    const first = dayLabel(s.buckets[0].daysAgo), last = T(s.weekly ? "pw_this_week" : "pw_today");
    body = html`
      <${InsightSection} title=${T("ins_over_time")} takeaway=${PlainWords.overTime(s)} info=${T(s.weekly ? "ins_over_time_info_week" : "ins_over_time_info_day")}>
        <${StackedBars} bars=${s.buckets.map((b) => [{ value: b.safe, color: "var(--safe)" }, { value: b.unsure, color: "var(--warn)" }, { value: b.scam, color: "var(--scam)" }, { value: b.notChecked, color: "var(--grey)" }])}
          description=${T("ins_over_time") + ": " + s.buckets.map((b) => `${dayLabel(b.daysAgo)} ${b.total}`).join(", ")} />
        <${AxisEnds} left=${first} right=${last} /><${Legend} slices=${resultSlices(s)} counts=${false} /></${InsightSection}>
      ${s.reasons.length > 0 && html`<${InsightSection} title=${T("ins_why")} takeaway=${PlainWords.reasons(s)} info=${T("ins_why_info")}>
        <${HorizontalBars} rows=${s.reasons.map(([r, n]) => [R.localizedReason(r), n])} color="var(--scam)" description=${T("ins_why")} /></${InsightSection}>`}
      <${InsightSection} title=${T("ins_signs")} takeaway=${PlainWords.signals(s)} info=${T("ins_signs_info")}>
        <${HorizontalBars} rows=${s.signals.map(([k, n]) => [PlainWords.signalName(k), n])} color="var(--brand)" description=${T("ins_signs")} /></${InsightSection}>
      <${InsightSection} title=${T("ins_trend")} takeaway=${PlainWords.trend(s)} info=${T("ins_trend_info")}>
        <${RiskLine} values=${s.buckets.map((b) => b.avgRisk)} description=${T("ins_trend")} /><${AxisEnds} left=${first} right=${last} /></${InsightSection}>
      <${InsightSection} title=${T("ins_hours")} takeaway=${PlainWords.hours(s)} info=${T("ins_hours_info")}>
        <${HourChart} hours=${s.riskyHours} color="var(--scam)" description=${T("ins_hours")} /></${InsightSection}>
      <${InsightSection} title=${T("ins_double")} takeaway=${PlainWords.doubleCheck(s)} info=${T("ins_double_info")}>
        <${SegmentedBar} slices=${[{ label: T("ins_confirmed"), value: s.confirmed, color: "var(--scam)", level: "red" }, { label: T("ins_turned_safe"), value: s.calmed, color: "var(--safe)", level: "green" }]}
          description=${T("a11y_double", s.confirmed, s.calmed)} /></${InsightSection}>
      <${InsightSection} title=${T("ins_where")} takeaway=${PlainWords.whereChecked(s)} info=${T("ins_where_info")}>
        <${SegmentedBar} slices=${s.privacy.map(([id, n]) => ({ label: R.privacyBadge(id), value: n, color: `var(--privacy-${id === "on_device" ? "on-device" : id})` }))} description=${T("ins_where")} /></${InsightSection}>
      <${InsightSection} title=${T("ins_answers")} takeaway=${PlainWords.answers(s)} info=${T("ins_answers_info")}>
        <div class="row top" style="gap:16px">
          <div class="col grow"><span class="f-largeTitle" style="color:var(--safe)">${s.markedFine}</span><span class="f-body muted">${T("ins_marked_fine")}</span></div>
          <div class="col grow"><span class="f-largeTitle" style="color:var(--scam)">${s.hungUpAfterWarning}</span><span class="f-body muted">${T("ins_hung_up")}</span></div></div></${InsightSection}>
      ${s.lengthVsRisk.length > 0 && html`<${InsightSection} title=${T("ins_length")} takeaway=${PlainWords.length(s)} info=${T("ins_length_info")}>
        <${Scatter} points=${s.lengthVsRisk} description=${T("a11y_length_chart", s.lengthVsRisk.length)} /><${AxisEnds} left=${T("ins_short")} right=${T("ins_long")} /></${InsightSection}>`}`;
  }
  return html`<${Page} title=${T("ins_title")} back onBack=${onBack} dir=${dir} id="insights">
    <div class="content p20" style="gap:28px"><${PeriodSwitcher} period=${period} setPeriod=${setPeriod} />${body}</div></${Page}>`;
}

const FILTERS = [[null, "pick_all"], ["SCAM", "risk_scam"], ["UNSURE", "risk_careful"], ["SAFE", "risk_safe"]];
export function HistoryView({ vm, dir, onBack }) {
  const [filter, setFilter] = useState(null);
  const calls = vm.store.calls();
  const now = Date.now();
  const shown = calls.filter((c) => !filter || c.finalVerdict === filter);
  const groups = {};
  for (const c of shown) (groups[PlainWords.historyGroup(daysAgo(c.endedAt, now))] ||= []).push(c);
  return html`<${Page} title=${T("hist_title")} back onBack=${onBack} dir=${dir} id="history">
    <div class="content p20" style="gap:12px">
      ${!calls.length ? html`<${EmptyState} text=${T("dash_empty")} goHome=${() => vm.showPath("home", [])} />` : html`
        <div class="row hscroll" style="gap:8px">${FILTERS.map(([v, k]) => html`<button class="btn" aria-pressed=${filter === v}
          style=${`min-height:56px;padding:0 16px;border-radius:999px;font-size:var(--t-body);white-space:nowrap;background:${filter === v ? "var(--brand)" : "var(--card)"};color:${filter === v ? "var(--on-brand)" : "var(--label)"}`}
          onClick=${() => setFilter(v)}>${T(k)}</button>`)}</div>
        ${!shown.length && html`<${EmptyState} text=${T("hist_none")} icon="list-plain" compact />`}
        ${[T("pw_today"), T("pw_this_week"), T("pw_earlier")].map((g) => groups[g] && html`
          <h2 class="f-title2s" style="margin:8px 0 0">${g}</h2>
          <div class="group" style="padding:4px 0">${groups[g].map((c) => html`<${CallListRow} call=${c} spark=${vm.store.points(c.id).map((p) => p.combined).filter((x) => x != null)}
            onClick=${() => vm.push({ name: "call", id: c.id })} />`)}</div>`)}`}
    </div></${Page}>`;
}

const reviewWords = (r) => ({ bert: T("det_rechecked"), fallback: T("det_no_final"), unavailable: T("det_not_reached"), on_device: T("pw_checked_on_device"), migrated: T("det_old") }[r] ?? r);

export function CallDetailView({ vm, id, dir, onBack }) {
  const [details, setDetails] = useState(false);
  const call = vm.store.call(id);
  const points = vm.store.points(id);
  const rs = riskiestSeq(points);
  const riskiest = rs == null ? null : points.findIndex((p) => p.seq === rs);
  return html`<${Page} title="" back onBack=${onBack} dir=${dir} inline id="callDetail">
    <div class="content p20">
      ${!call ? html`<p class="f-body">${T("det_gone")}</p>` : html`
        <div class="row" style="gap:14px"><${RiskIcon} level=${R.savedLevel(call.finalVerdict)} size=${26} />
          <h1 class="f-largeTitle" style=${`margin:0;color:${{ green: "var(--safe)", yellow: "var(--warn)", red: "var(--scam)" }[R.savedLevel(call.finalVerdict)] ?? "var(--grey)"}`}>${R.verdictWord(call.finalVerdict)}</h1></div>
        <p class="f-title2" style="margin:0">${whatHappened(call.finalVerdict, call.liveVerdict, R.localizedReason(R.displayReason(call)))}</p>
        <p class="f-body muted" style="margin:0">${whenText(call.endedAt)} at ${hhmm(call.endedAt)}</p>
        <div class="card"><h2 class="f-title2s" style="margin:0">${T("det_risk")}</h2>
          ${!points.length ? html`<${EmptyState} text=${T("det_no_scores")} icon="info" compact />` : html`
            <${RiskLine} values=${points.map((p) => p.combined)} marker=${riskiest}
              description=${`Risk during the call: ${points.length} points, highest ${Math.max(...points.map((p) => p.combined ?? 0)).toFixed(2)}`} />
            ${riskiest != null && html`<p class="f-body muted" style="margin:0">${T("det_circle")}</p>`}`}</div>
        ${call.riskiestSentence && html`<div class="card"><h2 class="f-title2s" style="margin:0">${T("det_sentence")}</h2><p class="f-body" style="margin:0">“${call.riskiestSentence}”</p></div>`}
        <div class="card p0">
          <button class="cell" style="min-height:64px" onClick=${() => setDetails(!details)} aria-label=${T(details ? "det_hide" : "det_show")}>
            <span class="f-title2s grow">${T("det_details")}</span><${Icon} name=${details ? "chevron-up" : "chevron-down"} /></button>
          ${details && html`<div class="col gap12" style="padding:0 20px 20px">
            ${[[T("det_length"), duration(Math.max(Math.floor((call.endedAt - call.startedAt) / 1000), 0))], [T("det_checked"), PlainWords.checkedWhere(call.privacyLevel)],
              [T("det_during"), call.liveVerdict ? R.verdictWord(call.liveVerdict) : "-"], [T("det_final"), reviewWords(call.review)]].map(([k, v]) => html`
              <div class="row f-body"><span class="muted grow">${k}</span><span style="text-align:right">${v}</span></div>`)}
            ${points.length > 0 && html`<span class="f-body muted">${T("det_signs")}</span>
              <${HorizontalBars} color="var(--brand)" description=${T("ins_signs")}
                rows=${R.SIGNALS.map((sig) => [PlainWords.signalName(sig), Math.floor(Math.max(0, ...points.map((p) => p[sig] ?? 0)) * 100)])} />`}</div>`}</div>`}
    </div></${Page}>`;
}

export function WeeklySummaryView({ vm, dir, onBack }) {
  const text = vm.weeklySummary();
  const [note, setNote] = useState(null), [busy, setBusy] = useState(false), [denied, setDenied] = useState(false);
  const share = async () => {
    if (navigator.share && isSecureContext) {
      try { await navigator.share({ text }); return; } catch (e) { if (e.name === "AbortError") return; }
    }
    setNote((await copyText(text)) ? WEB.copied + " ✓" : WEB.copyFailed);
  };
  return html`<${Page} title=${T("weekly_title")} back onBack=${onBack} dir=${dir} id="weekly">
    <div class="list">
      <p class="f-body muted" style="margin:0 20px">${T("weekly_line")}</p>
      <${Section} header=${T("weekly_preview")}><div class="cell"><span class="f-body" data-id="weeklyText">${text}</span></div></${Section}>
      <div class="col gap8">
        <button class="btn big" data-id="weeklyShare" onClick=${() => { tap(); share(); }}><${Icon} name="share" />${navigator.share && isSecureContext ? T("btn_share") : WEB.copyText}</button>
        ${note && html`<p class="f-bodys brand center" role="status" style="margin:0">${note}</p>`}</div>
      <${Section} footer=${denied ? null : WEB.reminderWeb}>
        <${ToggleRow} title=${T("weekly_reminder")} line=${T("weekly_reminder_line")} on=${vm.weeklyReminder} disabled=${busy} id="weeklyReminder"
          onChange=${async (on) => { setBusy(true); setDenied(false); const ok = await vm.setWeeklyReminder(on); setDenied(on && !ok); setBusy(false); }} />
        ${denied && html`<div class="cell"><span class="f-body" style="color:var(--warning-ink-adaptive)" data-id="weeklyDenied">${WEB.reminderDenied}</span></div>`}
      </${Section}>
    </div></${Page}>`;
}

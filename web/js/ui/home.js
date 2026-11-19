// Ports of ios-app UI/HomeViews.swift (Home, script picker), the Simple Home (UI/SimpleViews.swift) and
// UI/LearnViews.swift (Learn, scam type card, practice quiz).
import { html, useState, useEffect, Icon, RiskIcon, BigButton, Page, EmptyState, Segmented, Section } from "./components.js";
import { T } from "../logic/strings.js";
import { savedLevel, riskWord } from "../logic/risk.js";
import { whenText } from "../logic/dashboard.js";
import { Quiz, QUIZ_SIZE, isTestCall } from "../logic/features.js";
import { tap } from "./alerts.js";

const ring = (vm, kind) => { const s = vm.randomScript(kind); if (s) vm.ring(s); };

export function HomeView({ vm, dir }) {
  const wide = (label, onClick, prominent, tour, id) => html`<button class=${"btn " + (prominent ? "ios-prominent" : "ios-bordered")} data-tour=${tour} data-id=${id}
    onClick=${() => { tap(); onClick(); }}>${label}</button>`;
  return html`<${Page} title=${T("app_name_text")} dir=${dir} id="home">
    <div class="content">
      ${vm.appStyleTip && html`<${AppStyleTipCard} vm=${vm} />`}
      <p class="f-body muted" style="margin:0 0 4px">${T("home_full_line")}</p>
      ${wide(T("btn_sim_scam"), () => ring(vm, "scam"), true, "scamCall", "simScam")}
      ${wide(T("btn_sim_normal"), () => ring(vm, "normal"), true, null, "simNormal")}
      ${wide(T("btn_pick_script"), () => vm.push({ name: "pick" }), false, "pickScript", "pickScript")}
      ${wide(T("btn_learn"), () => vm.push({ name: "learn" }), false, null, "learnButton")}
      ${!vm.scripts.length && html`<${EmptyState} text=${T("pick_none")} icon="info" compact />`}
    </div></${Page}>`;
}

function AppStyleTipCard({ vm }) {
  return html`<div class="card brandc" data-id="styleTip"><p class="f-body" style="margin:0">${T("tip_app_style")}</p>
    <${BigButton} title=${T("btn_got_it")} prominent=${false} onClick=${() => vm.dismissAppStyleTip()} id="styleTipOk" /></div>`;
}

export function homeStatus(privacy, connection) {
  if (privacy === "on_device") return "protecting";
  return connection === true ? "protecting" : connection === false ? "notConnected" : "checking";
}

export function SimpleHomeView({ vm, dir }) {
  const [fixing, setFixing] = useState(false);
  useEffect(() => { vm.refreshConnection(); }, []);
  const status = homeStatus(vm.privacyLevel, vm.connection);
  const [level, title, line] = status === "protecting" ? ["green", "home_status_ok", "home_status_ok_line"]
    : status === "notConnected" ? ["yellow", "conn_fail", "conn_fail_line"] : ["listening", "home_status_checking", null];
  const color = { green: "var(--safe)", yellow: "var(--warn)" }[level] ?? "var(--grey)";
  const calls = vm.store.calls().slice(0, 3);
  const fix = async () => { setFixing(true); await vm.refreshConnection(); setFixing(false); };
  return html`<${Page} title=${T("app_name_text")} dir=${dir} id="simpleHome">
    <div class="content gap20" style="gap:20px">
      <div class="status-card" style=${`border-color:${color};background:${status === "notConnected" ? "color-mix(in srgb, var(--warn) 22%, transparent)" : "var(--card)"}`} data-id="homeStatus">
        <div class=${"row " + (vm.textSize === "xlarge" ? "" : "")} style="gap:14px">
          <${RiskIcon} level=${level} size=${48} />
          <div class="col gap4 grow"><span class="f-title2b" role="heading" aria-level="2">${T(title) + (status === "protecting" ? " ✓" : "")}</span>
            ${line && html`<span class="f-body">${T(line)}</span>`}</div></div>
        ${status === "notConnected" && html`<${BigButton} title=${T(fixing ? "status_checking" : "btn_try_again")} onClick=${fix} disabled=${fixing} id="fixIt" />`}
      </div>
      <div class="col gap8">
        <button class="btn" data-tour="practiceCall" data-id="practiceCall" onClick=${() => { tap(); ring(vm, "scam"); }}
          style="min-height:76px;border-radius:24px;background:var(--brand);color:var(--on-brand);padding:14px 16px;font:600 var(--t-title2)/1.2 var(--font-display);width:100%">
          <${Icon} name="phone" fill />${T("btn_practice_call")}</button>
        <p class="f-body muted" style="margin:0 8px">${T("home_practice_line")}</p>
      </div>
      <${LearnHomeCard} vm=${vm} />
      <div class="card"><h2 class="f-title2b" style="margin:0">${T("home_recent")}</h2>
        ${!calls.length && html`<${EmptyState} text=${T("home_no_calls")} compact />`}
        ${calls.map((c, i) => html`${i > 0 && html`<hr style="border:0;border-top:0.5px solid var(--separator);margin:0;width:100%" />`}
          <div class="row" style="min-height:56px" role="text"><${RiskIcon} level=${savedLevel(c.finalVerdict)} size=${32} />
            <span class="f-title3s">${riskWord(savedLevel(c.finalVerdict))}</span><span class="grow"></span><span class="f-body muted">${whenText(c.endedAt)}</span></div>`)}
      </div>
    </div></${Page}>`;
}

export function LearnHomeCard({ vm }) {
  return html`<button class="btn" data-id="learnCard" onClick=${() => { tap(); vm.push({ name: "learn" }); }}
    style="width:100%;justify-content:flex-start;text-align:left;padding:20px;border-radius:24px;background:var(--brand-container);color:var(--on-brand-container);gap:14px">
    <span style="width:48px;height:48px;border-radius:50%;background:var(--brand);color:var(--on-brand);display:grid;place-items:center;flex:none;font-size:24px"><${Icon} name="star" fill /></span>
    <span class="col gap4 grow"><span class="f-title2b">${T("learn_title")}</span><span class="f-body">${T("learn_card_line")}</span></span>
    <${Icon} name="chevron-right" stroke=${2.5} /></button>`;
}

// ---------------------------------------------------------------- script picker

const SORTS = [["type", "pick_sort_type"], ["title", "pick_sort_title"], ["language", "pick_sort_lang"], ["length", "pick_sort_short"]];
const kindLabel = (k) => T(k === "scam" ? "pick_kind_scam" : k === "normal" ? "pick_kind_normal" : k);
const kindColor = (k) => (k === "scam" ? "var(--scam)" : k === "normal" ? "var(--safe)" : "var(--grey)");
const langLabel = (l) => T(l === "en" ? "pick_english" : l === "zh" ? "pick_chinese" : "pick_auto");

export function ScriptPickerView({ vm, dir, onBack }) {
  const [kind, setKind] = useState(null), [lang, setLang] = useState(null), [sort, setSort] = useState("type"), [open, setOpen] = useState(false);
  const everyday = vm.scripts.filter((s) => !isTestCall(s.fileName));
  const testCalls = vm.devUnlocked ? vm.scripts.filter((s) => isTestCall(s.fileName)) : [];
  const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
  let shown = everyday.filter((s) => (!kind || s.kind === kind) && (!lang || s.lang === lang));
  shown = [...shown].sort({
    type: (a, b) => ((a.kind === "scam") !== (b.kind === "scam") ? (a.kind === "scam" ? -1 : 1) : cmp(a.title, b.title)),
    title: (a, b) => cmp(a.title, b.title),
    language: (a, b) => ((a.lang ?? "~") !== (b.lang ?? "~") ? cmp(a.lang ?? "~", b.lang ?? "~") : cmp(a.title, b.title)),
    length: (a, b) => (a.lines.length !== b.lines.length ? a.lines.length - b.lines.length : cmp(a.title, b.title)),
  }[sort]);
  const parts = [kind && T(kind === "scam" ? "pick_scam" : "pick_normal"), lang && langLabel(lang), T(SORTS.find((x) => x[0] === sort)[1])].filter(Boolean);
  const row = (s) => html`<button class="cell" data-script=${s.fileName} onClick=${() => vm.ring(s)} style="gap:12px;padding:8px 20px">
    <span style=${`width:6px;align-self:stretch;border-radius:3px;background:${kindColor(s.kind)}`}></span>
    <span class="col gap4 grow"><span class="f-caption2" style=${`font-weight:700;color:#fff;background:${kindColor(s.kind)};border-radius:4px;padding:2px 6px;align-self:flex-start`}>${kindLabel(s.kind)}</span>
      <span class="f-bodys">${s.title}</span><span class="f-caption muted">${T("pick_lines", langLabel(s.lang), s.lines.length)}</span></span></button>`;
  return html`<${Page} title=${T("pick_title")} back onBack=${onBack} dir=${dir} id="picker">
    <div class="list">
      <div class="group">
        <button class="cell" onClick=${() => setOpen(!open)} aria-expanded=${open}>
          <span class="col gap4 grow"><span class="f-bodys">${T("pick_filter")}</span><span class="f-caption muted">${T("pick_count", parts.join(" · "), shown.length, everyday.length)}</span></span>
          <${Icon} name=${open ? "chevron-down" : "chevron-right"} cls="chev" stroke=${2.5} /></button>
        ${open && html`<div class="col gap12" style="padding:4px 20px 16px">
          <${Segmented} label=${T("pick_type")} value=${kind} onChange=${setKind} options=${[[null, T("pick_all")], ["scam", T("pick_scam")], ["normal", T("pick_normal")]]} />
          <${Segmented} label=${T("pick_language")} value=${lang} onChange=${setLang} options=${[[null, T("pick_all")], ["en", T("pick_english")], ["zh", T("pick_chinese")]]} />
          <${Segmented} label=${T("pick_sort")} value=${sort} onChange=${setSort} options=${SORTS.map(([v, k]) => [v, T(k)])} /></div>`}
      </div>
      <div class="group">${shown.map(row)}</div>
      ${testCalls.length > 0 && html`<${Section} header="Test calls (learning loop)">${testCalls.map(row)}</${Section}>`}
    </div></${Page}>`;
}

// ---------------------------------------------------------------- Learn

export function LearnView({ vm, dir, onBack }) {
  const types = vm.learn?.types || [];
  return html`<${Page} title=${T("learn_title")} back onBack=${onBack} dir=${dir} id="learn">
    <div class="list">
      <p class="f-body muted" style="margin:0 20px">${T("learn_line")}</p>
      <div class="group brandc" style="padding:20px;display:flex;flex-direction:column;gap:10px">
        <h2 class="f-title2b" style="margin:0">${T("learn_quiz")}</h2><p class="f-body" style="margin:0">${T("learn_quiz_line")}</p>
        <button class="btn big" data-id="startQuiz" onClick=${() => { tap(); vm.push({ name: "quiz" }); }}>${T("learn_quiz")}</button></div>
      <${Section} header=${T("learn_types")}>
        ${!types.length && html`<${EmptyState} text=${T("empty_learn")} icon="info" />`}
        ${types.map((t) => html`<button class="cell has-icon" data-id=${"type_" + t.id} onClick=${() => vm.push({ name: "learnType", id: t.id })}>
          <span class="row-icon hide-ax"><${Icon} name="alert-fill" /></span><span class="row-text"><span class="row-title">${t.title}</span></span>
          <${Icon} name="chevron-right" cls="chev" stroke=${2.5} /></button>`)}
      </${Section}>
    </div></${Page}>`;
}

export function ScamTypeView({ vm, id, dir, onBack }) {
  const t = vm.learn?.types.find((x) => x.id === id);
  const script = t && vm.scripts.find((s) => s.fileName === t.script);
  return html`<${Page} title=${t?.title ?? ""} back onBack=${onBack} dir=${dir} id="scamType">
    ${t && html`<div class="content" style="gap:16px">
      <div class="card"><h2 class="f-title2b" style="margin:0">${T("learn_signs")}</h2>
        ${t.signs.map((s) => html`<div class="row top"><span style="color:var(--warn);font-size:var(--t-title3);padding-top:2px"><${Icon} name="alert-fill" /></span><span class="f-body grow">${s}</span></div>`)}</div>
      <div class="card" style="background:color-mix(in srgb, var(--safe) 14%, transparent)"><h2 class="f-title2b" style="margin:0">${T("learn_todo")}</h2>
        <div class="row top"><span style="color:var(--safe);font-size:var(--t-title3);padding-top:2px"><${Icon} name="check-fill" /></span><span class="f-body grow">${t.todo}</span></div></div>
      <${BigButton} title=${T("learn_hear")} onClick=${() => script && vm.ring(script)} id="hearExample" />
      <p class="f-body muted" style="margin:0">${T("learn_hear_line")}</p>
      <a class="link-btn" style="display:flex;align-items:center;text-decoration:none" href=${t.source_url} target="_blank" rel="noopener" data-id="typeSource">${T("learn_source", t.source_name)}</a>
    </div>`}</${Page}>`;
}

export function QuizView({ vm, dir, onBack, demo }) {
  const [round, setRound] = useState(() => Quiz.round(vm.learn));
  const [index, setIndex] = useState(0);
  const [answer, setAnswer] = useState(demo === "answered" ? true : null);
  const [score, setScore] = useState(demo === "result" ? 4 : 0);
  const [done, setDone] = useState(demo === "result");
  const item = round[index];
  const say = (scam) => { setAnswer(scam); if (Quiz.isRight(item, scam)) setScore(score + 1); };
  let bottom;
  if (done) bottom = html`<${BigButton} title=${T("btn_try_again")} id="quizAgain" onClick=${() => {
    setRound(Quiz.round(vm.learn, Math.random, new Set(round.map((q) => q.text)))); setIndex(0); setAnswer(null); setScore(0); setDone(false);
  }} />`;
  else if (item && answer == null) {
    bottom = html`<div class="row" style="gap:12px;flex-wrap:wrap">
      ${[["quiz_scam", "var(--scam)", true, "answerScam"], ["quiz_safe", "var(--safe)", false, "answerSafe"]].map(([k, c, v, idd]) => html`
        <button class="btn" data-id=${idd} onClick=${() => { tap(); say(v); }} style=${`flex:1 1 140px;min-height:72px;border-radius:999px;box-shadow:inset 0 0 0 3px ${c};font:700 var(--t-title)/1.1 var(--font-display)`}>${T(k)}</button>`)}</div>`;
  } else if (item) {
    const last = index === round.length - 1;
    bottom = html`<${BigButton} title=${T(last ? "quiz_see_result" : "btn_next")} id="quizNext" onClick=${() => {
      if (last) { setDone(true); vm.recordQuiz(score); } else { setIndex(index + 1); setAnswer(null); }
    }} />`;
  }
  const right = item && answer != null && Quiz.isRight(item, answer);
  return html`<${Page} title=${T("quiz_title")} back onBack=${onBack} dir=${dir} id="quiz" bottom=${bottom} scrollKey=${index + (done ? 100 : 0)}>
    <div class="content" style="gap:16px">
      ${done ? html`<div class="card brandc" style="align-items:center;text-align:center" data-id="quizResult" role="status">
          <span style="font-size:52px;color:var(--brand)"><${Icon} name="star" fill size=${52} /></span>
          ${!vm.simpleMode && html`<span class="f-largeTitle" data-id="quizScore">${T("quiz_score", score, QUIZ_SIZE)}</span>`}
          <span class="f-title2">${T(Quiz.resultLine(score))}</span>
          ${!vm.simpleMode && vm.quizBest != null && html`<span class="f-body" data-id="quizBest">${T("quiz_best", vm.quizBest, QUIZ_SIZE)}</span>`}</div>`
        : item && html`
          <span class="f-headline muted" data-id="quizProgress">${T("quiz_progress", index + 1, round.length)}</span>
          <div class="card"><span class="f-headline muted">${T("quiz_caller_says")}</span><span class="f-title2" style="font-style:italic" data-id="quizLine">“${item.text}”</span></div>
          <h2 class="f-title2b" style="margin:0">${T("quiz_question")}</h2>
          ${answer != null && html`<div class="card" role="status" data-id="quizFeedback" style=${`background:color-mix(in srgb, ${right ? "var(--safe)" : "var(--warn)"} 16%, transparent)`}>
            <div class="row"><span style=${`font-size:34px;color:${right ? "var(--safe)" : "var(--warning-ink-adaptive)"}`}><${Icon} name=${right ? "circle-check" : "circle-info"} size=${34} /></span>
              <span class="f-title2b grow">${T(right ? "quiz_right" : "quiz_wrong") + " " + T(item.scam ? "quiz_was_scam" : "quiz_was_safe")}</span></div>
            <p class="f-body" style="margin:0">${item.why}</p></div>`}`}
    </div></${Page}>`;
}

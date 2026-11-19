// Port of ios-app UI/ChartViews.swift (Swift Charts) as small SVG drawings: donut, stacked bars, horizontal bars,
// segmented bar, risk line with thresholds, hour bars, scatter, sparkline. Same colours; every chart has a text label.
import { html, RiskIcon } from "./components.js";

const dotColor = (v) => (v >= 0.6 ? "var(--scam)" : v >= 0.45 ? "var(--warn)" : "var(--safe)");

export function SliceKey({ slice }) {
  return slice.level ? html`<${RiskIcon} level=${slice.level} size=${22} />` : html`<span class="key-dot" style=${`background:${slice.color}`}></span>`;
}
export function Legend({ slices, counts = true }) {
  return html`<div class="legend" aria-hidden=${!counts}>${slices.map((s) => html`<span class="row" style="gap:8px"><${SliceKey} slice=${s} /><span class="f-body">${counts ? `${s.label} ${s.value}` : s.label}</span></span>`)}</div>`;
}

export function Donut({ slices, centerLabel, size = 112, compact = true }) {
  const total = slices.reduce((a, s) => a + s.value, 0);
  const r = size / 2, inner = r * 0.68, mid = (r + inner) / 2, w = r - inner;
  let a0 = -Math.PI / 2;
  const gap = total > 0 && slices.filter((s) => s.value > 0).length > 1 ? 0.03 : 0;
  const arcs = slices.filter((s) => s.value > 0).map((s) => {
    const a1 = a0 + (s.value / total) * Math.PI * 2;
    const from = a0 + gap / 2, to = a1 - gap / 2;
    a0 = a1;
    if (to - from >= Math.PI * 2 - 0.001) return html`<circle cx=${r} cy=${r} r=${mid} fill="none" stroke=${s.color} stroke-width=${w} />`;
    const p = (a) => `${r + mid * Math.cos(a)},${r + mid * Math.sin(a)}`;
    return html`<path d=${`M${p(from)} A${mid},${mid} 0 ${to - from > Math.PI ? 1 : 0} 1 ${p(to)}`} fill="none" stroke=${s.color} stroke-width=${w} />`;
  });
  return html`<div style=${`position:relative;width:${size}px;height:${size}px;flex:none`} role="img" aria-label=${`${centerLabel}: ${total}. ` + slices.map((s) => `${s.label} ${s.value}`).join(", ")}>
    <svg width=${size} height=${size} viewBox=${`0 0 ${size} ${size}`}>${total === 0 ? html`<circle cx=${r} cy=${r} r=${mid} fill="none" stroke="var(--track)" stroke-width=${w} />` : arcs}</svg>
    <div style="position:absolute;inset:0;display:grid;place-items:center;text-align:center"><span class=${compact ? "f-title2b" : "f-largeTitle"}>${total}</span></div></div>`;
}

export function StackedBars({ bars, description, height = 160 }) {
  const n = Math.max(bars.length, 1);
  const max = Math.max(1, ...bars.map((b) => b.reduce((a, s) => a + s.value, 0)));
  const W = 100, bw = (W / n) * 0.62;
  return html`<svg class="chart" viewBox=${`0 0 ${W} ${height}`} preserveAspectRatio="none" style=${`height:${height}px`} role="img" aria-label=${description}>
    ${bars.map((b, i) => {
      let y = height;
      const x = (i + 0.5) * (W / n) - bw / 2;
      return b.map((s) => { const h = (s.value / max) * (height - 4); y -= h; return s.value ? html`<rect x=${x} y=${y} width=${bw} height=${h} rx="0.8" fill=${s.color} />` : null; });
    })}</svg>`;
}

export function HorizontalBars({ rows, color, description }) {
  const max = Math.max(1, ...rows.map((r) => r[1]));
  return html`<div class="col" style="gap:10px" role="img" aria-label=${description}>${rows.map(([label, v]) => html`<div class="col gap4">
    <div class="row"><span class="f-body grow">${label}</span><b class="f-body">${v}</b></div>
    <div class="hbar-track"><div class="hbar-fill" style=${`width:${(v / max) * 100}%;background:${color}`}></div></div></div>`)}</div>`;
}

export function SegmentedBar({ slices, description }) {
  const total = slices.reduce((a, s) => a + s.value, 0);
  return html`<div class="col" style="gap:10px"><div class="segbar" role="img" aria-label=${description}>
    ${total > 0 && slices.filter((s) => s.value > 0).map((s) => html`<span style=${`flex:${s.value};background:${s.color}`}></span>`)}</div>
    <${Legend} slices=${slices} /></div>`;
}

/** Values on a 0-1 scale, dashed 0.45 / 0.6 thresholds, points coloured by level, `marker` circles the riskiest. */
export function RiskLine({ values, description, marker = null, height = 170 }) {
  const W = 340, pad = 10;
  const n = Math.max(values.length - 1, 1);
  const x = (i) => pad + (i / n) * (W - 2 * pad), y = (v) => height - pad - Math.min(Math.max(v, 0), 1) * (height - 2 * pad);
  const pts = values.map((v, i) => [i, v]).filter(([, v]) => v != null);
  const path = pts.map(([i, v], k) => `${k ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const rule = (v, c) => html`<line x1=${pad} x2=${W - pad} y1=${y(v)} y2=${y(v)} stroke=${c} stroke-width="1.5" stroke-dasharray="5 4" />`;
  return html`<svg class="chart" viewBox=${`0 0 ${W} ${height}`} style=${`height:${height}px`} role="img" aria-label=${description}>
    ${rule(0.45, "var(--warn)")}${rule(0.6, "var(--scam)")}
    <path d=${path} fill="none" stroke="var(--brand)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
    ${marker != null && values[marker] != null && html`<line x1=${x(marker)} x2=${x(marker)} y1=${pad} y2=${height - pad} stroke="var(--label)" stroke-opacity="0.5" stroke-dasharray="5 4" />
      <circle cx=${x(marker)} cy=${y(values[marker])} r="10" fill="none" stroke="var(--label)" stroke-width="2" />`}
    ${pts.map(([i, v]) => html`<circle cx=${x(i)} cy=${y(v)} r="4.5" fill=${dotColor(v)} />`)}</svg>`;
}

export function HourChart({ hours, color, description }) {
  const max = Math.max(1, ...hours), W = 240, H = 110, bw = (W / 24) * 0.66;
  return html`<div class="col gap4" role="img" aria-label=${description}>
    <svg class="chart" viewBox=${`0 0 ${W} ${H}`} preserveAspectRatio="none" style=${`height:${H}px`}>
      ${hours.map((n, h) => n > 0 && html`<rect x=${(h + 0.5) * (W / 24) - bw / 2} y=${H - (n / max) * (H - 2)} width=${bw} height=${(n / max) * (H - 2)} rx="1.5" fill=${color} />`)}</svg>
    <div class="row" style="gap:0">${["12 am", "6 am", "12 pm", "6 pm"].map((t) => html`<span class="f-body muted" style="flex:1">${t}</span>`)}</div></div>`;
}

export function Scatter({ points, description }) {
  const W = 340, H = 150, pad = 8, maxX = Math.max(1, ...points.map((p) => p[0]));
  return html`<svg class="chart" viewBox=${`0 0 ${W} ${H}`} style=${`height:${H}px`} role="img" aria-label=${description}>
    ${points.map(([s, v]) => html`<circle cx=${pad + (s / maxX) * (W - 2 * pad)} cy=${H - pad - v * (H - 2 * pad)} r="5" fill=${dotColor(v)} fill-opacity="0.85" />`)}</svg>`;
}

export function Sparkline({ values }) {
  if (!values.length) return null;
  const W = 64, H = 26, pad = 2;
  const x = (i) => (values.length === 1 ? W / 2 : pad + (i * (W - 2 * pad)) / (values.length - 1));
  const y = (v) => H - pad - Math.min(Math.max(v, 0), 1) * (H - 2 * pad);
  const last = values[values.length - 1];
  return html`<svg width=${W} height=${H} aria-hidden="true" style="flex:none">
    <path d=${values.map((v, i) => `${i ? "L" : "M"}${x(i)},${y(v)}`).join(" ")} fill="none" stroke="var(--brand)" stroke-width="2" stroke-linecap="round" />
    <circle cx=${x(values.length - 1)} cy=${y(last)} r="3" fill=${dotColor(last)} /></svg>`;
}

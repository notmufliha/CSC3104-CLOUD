// Port of ios-app UI/TourOverlay.swift: dims the screen, cuts a rounded hole around the step's element (found by its
// data-tour attribute) and shows a bubble with the title, one line, "2 of 7", Skip tour and Next.
import { html, useState, useEffect, useRef } from "./components.js";
import { T } from "../logic/strings.js";

const PAD = 8;

export function TourOverlay({ vm, steps, index }) {
  const step = steps[Math.min(Math.max(index, 0), steps.length - 1)];
  const ref = useRef(null), bubble = useRef(null), title = useRef(null);
  const [hole, setHole] = useState(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const [bh, setBh] = useState(0);
  useEffect(() => {
    let raf;
    const measure = () => {
      const root = ref.current?.parentElement;
      if (!root) return;
      const box = root.getBoundingClientRect();
      const el = [...root.querySelectorAll(`[data-tour="${step.target}"]`)].pop();
      const scale = box.width / root.offsetWidth || 1;
      setSize({ w: root.offsetWidth, h: root.offsetHeight });
      if (el) {
        const r = el.getBoundingClientRect();
        setHole({ x: (r.left - box.left) / scale - PAD, y: (r.top - box.top) / scale - PAD, w: r.width / scale + 2 * PAD, h: r.height / scale + 2 * PAD });
      } else setHole(null);
      if (bubble.current) setBh(bubble.current.offsetHeight);
    };
    const t = setTimeout(() => { measure(); raf = requestAnimationFrame(measure); }, 80);
    title.current?.focus();
    addEventListener("resize", measure);
    return () => { clearTimeout(t); cancelAnimationFrame(raf); removeEventListener("resize", measure); };
  }, [index, step.target]);
  const top = 70, bottom = size.h - 40, gap = 12;
  let y = hole ? (hole.y + hole.h / 2 < size.h / 2 ? hole.y + hole.h + gap : hole.y - gap - bh) : (size.h - bh) / 2;
  y = Math.min(Math.max(y, top), Math.max(bottom - bh, top));
  const last = index >= steps.length - 1;
  return html`<div class="tour" ref=${ref} role="dialog" aria-modal="true" data-id="tourOverlay" onClick=${(e) => e.stopPropagation()}>
    <svg class="dim" aria-hidden="true"><defs><mask id="tourHole"><rect width="100%" height="100%" fill="#fff" />
      ${hole && html`<rect x=${hole.x} y=${hole.y} width=${hole.w} height=${hole.h} rx="16" fill="#000" />`}</mask></defs>
      <rect width="100%" height="100%" fill="rgba(0,0,0,0.62)" mask="url(#tourHole)" /></svg>
    ${hole && html`<div class="hole" style=${`left:${hole.x}px;top:${hole.y}px;width:${hole.w}px;height:${hole.h}px`} aria-label=${T("a11y_highlighted", T(step.title))}></div>`}
    <div class="bubble-card" ref=${bubble} style=${`top:${y}px`} data-id="tourBubble">
      <span class="f-bodys brand">${T("tour_progress", index + 1, steps.length)}</span>
      <h2 class="f-title2b" style="margin:0" tabindex="-1" ref=${title}>${T(step.title)}</h2>
      <p class="f-body" style="margin:0">${T(step.line)}</p>
      <div class="row" style="padding-top:4px">
        <button class="link-btn" data-id="tourSkip" onClick=${() => vm.endTour()}>${T("btn_skip_tour")}</button><span class="grow"></span>
        <button class="btn ios-prominent" data-id="tourNext" style="width:auto;padding:0 24px;font:600 var(--t-body)/1 var(--font)" onClick=${() => vm.tourNext()}>${T(last ? "btn_done" : "btn_next")}</button></div></div></div>`;
}

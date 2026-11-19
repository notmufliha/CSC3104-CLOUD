// Light / dark: the system setting (prefers-color-scheme), or a choice saved in this browser (the toolbar outside the
// phone frame, or Developer view → Appearance). The iOS app follows the phone's own setting; this stands in for it.
const KEY = "scamguard.web_appearance";
const media = globalThis.matchMedia?.("(prefers-color-scheme: dark)");

export function getAppearance() { try { return localStorage.getItem(KEY) || "system"; } catch { return "system"; } }
export function isDark() {
  const a = getAppearance();
  return a === "dark" || (a === "system" && !!media?.matches);
}
export function applyAppearance() {
  const dark = isDark();
  document.documentElement.classList.toggle("dark", dark);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", dark ? "#141218" : "#fffbfe");
}
export function setAppearance(v) {
  try { if (v === "system") localStorage.removeItem(KEY); else localStorage.setItem(KEY, v); } catch { /* refused */ }
  applyAppearance();
}
media?.addEventListener?.("change", applyAppearance);

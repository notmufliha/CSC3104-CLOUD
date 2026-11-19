// Port of ios-app Data/AppPrefs.swift: settings in localStorage under the same key names as the apps
// ("scamguard.<key>"). Every access is guarded (private windows can refuse storage). Same defaults as the apps,
// except the server address: the web app talks to the server that served the page (same-origin "api/").
import { AppStyle, TEXT } from "../logic/onboarding.js";
import { privacyFromId } from "../logic/risk.js";

const P = "scamguard.";
const raw = {
  get(k) { try { const v = localStorage.getItem(P + k); return v === null ? undefined : JSON.parse(v); } catch { return undefined; } },
  set(k, v) { try { if (v === undefined || v === null) localStorage.removeItem(P + k); else localStorage.setItem(P + k, JSON.stringify(v)); } catch { /* refused */ } },
};

export const KEYS = ["gateway_url", "save_riskiest_sentence", "privacy_level", "help_improve_detection", "fl_phone_id",
  "onboarding_completed", "onboarding_version", "text_size", "read_aloud", "show_be_careful", "simple_mode", "app_style_tip_shown",
  "text_size_chosen", "read_aloud_chosen", "family_name", "family_phone", "developer_view_unlocked", "quiz_best", "weekly_reminder",
  "pin", "weekly_reminder_last", "installed_models"];
const EXISTING = ["gateway_url", "save_riskiest_sentence", "privacy_level", "help_improve_detection", "fl_phone_id"];

/** "192.168.1.23:8080" -> "http://192.168.1.23:8080/"; "api/" -> this page's address + "api/". */
export function normalizeUrl(url) {
  let u = (url || "").trim();
  if (!u) u = "api/";
  if (!/^https?:\/\//i.test(u)) {
    if (/^[\w.-]+:\d+(\/|$)|^\d+\.\d+\.\d+\.\d+|^localhost(\/|:|$)/i.test(u)) u = "http://" + u;
    else { try { u = new URL(u, globalThis.location?.href ?? "http://localhost/").href; } catch { /* keep */ } }
  }
  if (!u.endsWith("/")) u += "/";
  return u;
}
export const defaultUrl = () => normalizeUrl("api/");

function prop(key, dflt, set = (v) => v) {
  return {
    get() { const v = raw.get(key); return v === undefined ? (typeof dflt === "function" ? dflt.call(this) : dflt) : v; },
    set(v) { raw.set(key, set(v)); },
    enumerable: true,
  };
}

export class AppPrefs {
  constructor() {
    Object.defineProperties(this, {
      gatewayUrl: prop("gateway_url", defaultUrl, normalizeUrl),
      saveRiskiestSentence: prop("save_riskiest_sentence", false),
      privacyLevel: { get: () => privacyFromId(raw.get("privacy_level")), set: (v) => raw.set("privacy_level", v), enumerable: true },
      helpImproveDetection: prop("help_improve_detection", false),
      onboardingCompleted: prop("onboarding_completed", null),
      onboardingVersion: prop("onboarding_version", 0),
      textSize: prop("text_size", function () { return AppStyle.textSize(AppStyle.simpleAtLaunch(this.simpleMode)); }),
      readAloud: prop("read_aloud", function () { return AppStyle.readAloud(AppStyle.simpleAtLaunch(this.simpleMode)); }),
      textSizeChosen: prop("text_size_chosen", false),
      readAloudChosen: prop("read_aloud_chosen", false),
      showBeCareful: prop("show_be_careful", true),
      simpleMode: prop("simple_mode", null),
      appStyleTipShown: prop("app_style_tip_shown", false),
      familyName: prop("family_name", "", (v) => (v || "").trim()),
      familyPhone: prop("family_phone", "", (v) => (v || "").trim()),
      quizBest: prop("quiz_best", null),
      weeklyReminder: prop("weekly_reminder", false),
      weeklyReminderLast: prop("weekly_reminder_last", 0),
      developerUnlocked: prop("developer_view_unlocked", false),
      pinBlob: prop("pin", null),
      installedModels: prop("installed_models", {}),
    });
  }
  get flPhoneId() {
    let id = raw.get("fl_phone_id");
    if (!id) { id = uuid(); raw.set("fl_phone_id", id); }
    return id;
  }
  hasAnySetting() { return EXISTING.some((k) => raw.get(k) !== undefined); }
  /** "Reset app to first launch": every setting, as on a new install. */
  clearAll() { for (const k of KEYS) raw.set(k, undefined); }
}

/** crypto.randomUUID needs a secure context; getRandomValues works everywhere. */
export function uuid() {
  if (globalThis.crypto?.randomUUID) { try { return crypto.randomUUID(); } catch { /* insecure context */ } }
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export { TEXT };

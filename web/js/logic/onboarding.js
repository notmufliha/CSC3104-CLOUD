// Port of ios-app Logic/Onboarding.swift: first-launch setup ("Who will use this phone?"), the two app styles and
// the guided tour. Pure; the same rules and tests as both apps.

export const VERSION = 2;
export const TEXT = { normal: "normal", large: "large", xlarge: "xlarge" };

export const STEPS = ["who", "welcome", "recommended", "privacy", "data", "helper", "family", "pin", "handover", "done"];

/** completed: the saved flag (null = never written). */
export function atLaunch(completed, hasSettings, hasHistory) {
  if (completed === true) return "done";
  if (completed === false) return "showSetup";
  return hasSettings || hasHistory ? "existingUser" : "showSetup";
}

export function steps(c) {
  switch (c.path) {
    case "standard": return ["who", "welcome", "privacy", "data", "done"];
    case "simple": return ["who", "welcome", "recommended"];
    case "custom": return ["who", "welcome", "recommended", "privacy", "data", "done"];
    case "helper": return ["who", "helper", "family", "pin", "handover"];
    default: return ["who"];
  }
}
export function next(step, c) { const s = steps(c); const i = s.indexOf(step); return i >= 0 && i + 1 < s.length ? s[i + 1] : null; }
export function previous(step, c) { const s = steps(c); const i = s.indexOf(step); return i > 0 ? s[i - 1] : null; }
export function progress(step, c) { const s = steps(c); return [Math.max(s.indexOf(step), 0) + 1, s.length]; }

export const AppStyle = {
  textSize: (simple) => (simple ? TEXT.large : TEXT.normal),
  readAloud: (simple) => simple,
  simpleAtLaunch: (saved) => saved ?? false,
  /** Settings → App style: the text size and read-aloud follow the new style unless the user set them. */
  switchTo(simple, store) {
    store.simpleMode = simple;
    if (!store.textSizeChosen) store.textSize = AppStyle.textSize(simple);
    if (!store.readAloudChosen) store.readAloud = AppStyle.readAloud(simple);
    store.appStyleTipShown = true;
  },
  needsPin: (fromSimple, toSimple, pinSet) => fromSimple && !toSimple && pinSet,
  showTip: (simple, tipShown) => !simple && !tipShown,
};

export const isSimple = (c) => c.path != null && c.path !== "standard";

export function defaults() {
  return { privacy: "cloud", saveSentence: false, helpImprove: false, path: null, textSize: AppStyle.textSize(false),
    readAloud: AppStyle.readAloud(false), textSizeChosen: false, readAloudChosen: false, familyName: "", familyPhone: "", pin: "" };
}

export function choose(c, path) {
  const simple = path !== "standard";
  const r = { ...c, path };
  if (!c.textSizeChosen) r.textSize = AppStyle.textSize(simple);
  if (!c.readAloudChosen) r.readAloud = AppStyle.readAloud(simple);
  return r;
}
export const answered = (c) => (c.path == null ? choose(c, "standard") : c);

export function fromSaved(store) {
  return { ...defaults(), privacy: store.privacyLevel, saveSentence: store.saveRiskiestSentence, helpImprove: store.helpImproveDetection,
    textSize: store.textSize, readAloud: store.readAloud, textSizeChosen: store.textSizeChosen, readAloudChosen: store.readAloudChosen,
    familyName: store.familyName, familyPhone: store.familyPhone };
}

export function recommended(c) {
  const r = { ...c, privacy: "cloud", saveSentence: false, helpImprove: false, path: "simple" };
  if (!c.readAloudChosen) r.readAloud = true;
  return r;
}

/** Saves the choices and marks the setup done. The PIN is saved separately by the caller. */
export function apply(choices, store) {
  const c = answered(choices);
  store.privacyLevel = c.privacy;
  store.saveRiskiestSentence = c.saveSentence;
  store.helpImproveDetection = c.helpImprove;
  store.simpleMode = isSimple(c);
  store.textSize = c.textSize;
  store.readAloud = c.readAloud;
  store.textSizeChosen = c.textSizeChosen;
  store.readAloudChosen = c.readAloudChosen;
  store.appStyleTipShown = true;
  store.familyName = c.familyName.trim();
  store.familyPhone = c.familyPhone.trim();
  markCompleted(store);
}
export function markCompleted(store) { store.onboardingCompleted = true; store.onboardingVersion = VERSION; }
export function reset(store) { store.onboardingCompleted = false; }

// ---------------------------------------------------------------- the guided tour

export const Tour = {
  simpleSteps: [
    { target: "practiceCall", place: "home", title: "tour_practice_title", line: "tour_practice_line" },
    { target: "redWarning", place: "call", title: "tour_red_title", line: "tour_red_line" },
    { target: "warningButtons", place: "call", title: "tour_buttons_title", line: "tour_buttons_line" },
  ],
  fullSteps: [
    { target: "scamCall", place: "home", title: "tour_scam_title", line: "tour_scam_line" },
    { target: "pickScript", place: "home", title: "tour_pick_title", line: "tour_pick_line" },
    { target: "riskBar", place: "call", title: "tour_bar_title", line: "tour_bar_line" },
    { target: "warningChips", place: "call", title: "tour_chips_title", line: "tour_chips_line" },
    { target: "redWarning", place: "call", title: "tour_red_title", line: "tour_red_line_full" },
    { target: "dashboardHero", place: "dashboard", title: "tour_dash_title", line: "tour_dash_line" },
    { target: "privacyRow", place: "settings", title: "tour_privacy_title", line: "tour_privacy_line" },
  ],
  steps: (simple) => (simple ? Tour.simpleSteps : Tour.fullSteps),
  isLast: (i, simple) => i === Tour.steps(simple).length - 1,
};

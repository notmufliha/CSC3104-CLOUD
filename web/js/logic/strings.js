// The apps' wording: assets/strings.json is generated from tools/i18n/strings/*.json by tools/i18n/gen_strings.py,
// together with Android's strings.xml and iOS's Localizable.xcstrings, so all three say exactly the same.
let table = {};

/** Installs the table (the browser fetches assets/strings.json; tests read the file). */
export function installStrings(t) { table = t || {}; }

/** The text for a key with {0} {1} filled in (L.s on iOS). A missing key shows the key, so it is easy to spot. */
export function T(key, ...args) {
  const s = table[key];
  if (s === undefined) return key;
  return s.replace(/\{(\d+)\}/g, (_, i) => String(args[+i] ?? ""));
}

export const hasString = (key) => key in table;

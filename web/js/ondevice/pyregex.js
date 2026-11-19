// Port of ios-app OnDevice/PyRegex.swift (and android-app ondevice/PyRegex.kt): just enough of Python's `re` and `str`
// to port the workers' text cleaning exactly.
//
// Python 3 str patterns are Unicode-aware: \w, \d, \s and \b match any language's letters, digits and spaces, and
// re.I folds case for all of them. JavaScript (even with the u flag) keeps \w, \d and \b ASCII-only and has a different
// \s, so `translate` rewrites them:
//   \w -> [\p{L}\p{N}_]  (Python: str.isalnum() or "_")      \W -> the opposite
//   \d -> \p{Nd}         (Unicode decimal digits)              \D -> the opposite
//   \s -> Python's whitespace set                              \S -> the opposite
//   \b / \B -> lookarounds on the \w class above
// The parity tests (317 sentences) compare the result with the workers' own Python.

const W = "\\p{L}\\p{N}_";
const WS = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x85\\p{Z}";
const B = `(?:(?<=[${W}])(?![${W}])|(?<![${W}])(?=[${W}]))`;
const NB = `(?:(?<=[${W}])(?=[${W}])|(?<![${W}])(?![${W}]))`;

/** Rewrites a Python pattern for JavaScript's `u` flag (see above). */
export function translate(pattern) {
  let out = "";
  let inClass = false;
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i];
    if (c === "\\" && i + 1 < pattern.length) {
      const n = pattern[i + 1];
      i++;
      if (n === "w") out += inClass ? W : `[${W}]`;
      else if (n === "W") out += inClass ? "\\P{L}" /* rare; not used inside classes by the workers */ : `[^${W}]`;
      else if (n === "d") out += "\\p{Nd}";
      else if (n === "D") out += "\\P{Nd}";
      else if (n === "s") out += inClass ? WS : `[${WS}]`;
      else if (n === "S") out += inClass ? n : `[^${WS}]`;
      else if (n === "b" && !inClass) out += B;
      else if (n === "B" && !inClass) out += NB;
      else if (/[A-Za-z0-9]/.test(n) || "\\^$.|?*+()[]{}/-".includes(n)) out += "\\" + n;
      else out += n;                                  // Python allows \' \" \# etc.: a plain character in JS u mode
      continue;
    }
    if (c === "[" && !inClass) {
      inClass = true;
      out += c;
      if (pattern[i + 1] === "^") { out += "^"; i++; }
      if (pattern[i + 1] === "]") { out += "\\]"; i++; }
    } else if (c === "]" && inClass) {
      inClass = false;
      out += c;
    } else if (inClass && (c === "[" || c === "/" || c === "{" || c === "}" || c === "(" || c === ")" || c === "|")) {
      out += "\\" + c;                                // literal inside a class in Python; must be escaped in u mode
    } else {
      out += c;
    }
  }
  return out;
}

/** re.compile(pattern[, re.I]) as a global JS RegExp. A leading (?u) (Python's default) is dropped. */
export function re(pattern, ignoreCase = false) {
  const p = pattern.startsWith("(?u)") ? pattern.slice(4) : pattern;
  return new RegExp(translate(p), "gu" + (ignoreCase ? "i" : ""));
}

/** re.sub: `rep` is a string (no group references) or a function (match array -> string). */
export function sub(rx, s, rep) {
  rx.lastIndex = 0;
  if (typeof rep !== "function") return s.replace(rx, () => rep);
  // callback args: match, group1..groupN, offset, string -> pass [match, group1..groupN] (unmatched group = undefined)
  return s.replace(rx, (...args) => rep(args.slice(0, args.findIndex((x) => typeof x === "number"))));
}

/** All matches as strings (re.findall without groups). */
export function findAll(rx, s) {
  rx.lastIndex = 0;
  return Array.from(s.matchAll(rx), (m) => m[0]);
}

/** All match objects (index, groups). */
export function matches(rx, s) {
  rx.lastIndex = 0;
  return Array.from(s.matchAll(rx));
}

/** re.split (no capture groups): keeps empty pieces; skips empty matches at either end, like Python. */
export function splitRx(rx, s) {
  const out = [];
  let last = 0;
  for (const m of matches(rx, s)) {
    if (m[0].length === 0 && (m.index === 0 || m.index === s.length)) continue;
    out.push(s.slice(last, m.index));
    last = m.index + m[0].length;
  }
  out.push(s.slice(last));
  return out;
}

/** re.fullmatch: the match array if the pattern covers the whole string, else null. */
export function fullMatch(rx, s) {
  const anchored = new RegExp("^(?:" + rx.source + ")$", rx.flags.replace("g", ""));
  return s.match(anchored);
}

const WHITESPACE = re("\\s+");
/** str.split() with no argument. */
export function split(s) { return splitRx(WHITESPACE, s).filter((x) => x.length > 0); }

const LINE_BREAK = /\r\n|[\n\r\x0B\x0C\x1C\x1D\x1E\x85\u2028\u2029]/gu;
/** str.splitlines(). */
export function splitLines(s) {
  if (!s) return [];
  const parts = splitRx(LINE_BREAK, s);
  return parts[parts.length - 1] === "" ? parts.slice(0, -1) : parts;
}

const STRIP = /^[\t\n\x0B\f\r\x1C-\x1F\x85\p{Z}]+|[\t\n\x0B\f\r\x1C-\x1F\x85\p{Z}]+$/gu;
/** str.strip(). */
export function strip(s) { return s.replace(STRIP, ""); }

export const lower = (s) => s.toLowerCase();
export const nfkc = (s) => s.normalize("NFKC");

/** re.escape for an alternation of literal names. */
export function escape(s) { return s.replace(/[\\^$.*+?()[\]{}|\/-]/g, "\\$&"); }

/** Python's str.isdigit for one code point (Unicode decimal digits, as the Swift port uses). */
export const isDigit = (ch) => /\p{Nd}/u.test(ch);

// Port of ios-app OnDevice/TextCleaning.swift (= android-app ondevice/TextCleaning.kt): the English workers' text
// cleaning, line by line. The parity tests compare against the workers' Python output (parity_cases.json).
import * as Py from "./pyregex.js";

// ---------------------------------------------------------------- urgency_en (text_norm.normalize + predict windows)

const TAG_MAP = {
  MONEY_AMOUNT: " moneytoken ", PHONE_NUMBER: " phonetoken ", EMAIL_ADDRESS: " emailtoken ", URL: " urltoken ",
  DATE: " timetoken ", DATE_OF_BIRTH: " timetoken ", TIME: " timetoken ", DURATION: " numtoken ", NUMBER: " numtoken ",
};
const U = {
  tag: Py.re(String.raw`\[([A-Z_]+)\]`),
  speaker: Py.re(String.raw`^\s*(person [ab]|caller|receiver|agent|customer|suspect|innocent|user|system|assistant)\s*:\s*`, true),
  email: Py.re(String.raw`\b[\w.+-]+@[\w-]+\.[\w.]+\b`),
  url: Py.re(String.raw`(https?://\S+|www\.\S+|\b[\w-]+\.(com|org|net|gov|sg|cn|io|co)(/\S*)?\b)`, true),
  money: Py.re(String.raw`([$¥€£]\s?\d[\d,]*(\.\d+)?|\b\d[\d,]*(\.\d+)?\s?(dollars|sgd|usd|yuan|rmb|cny)\b)`, true),
  date: Py.re(String.raw`\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\b`),
  phoneCand: Py.re(String.raw`(?<![\w])\+?\(?\d[\d\s().-]{5,18}\d(?![\w])`),
  time: Py.re(String.raw`\b\d{1,2}[:.]\d{2}(\s?[ap]\.?m\.?)?\b|\b\d{1,2}\s?[ap]\.?m\.?\b`, true),
  hours: Py.re(String.raw`\b\d+\s?(h|hr|hrs)\b`, true),
  mins: Py.re(String.raw`\b\d+\s?(m|min|mins)\b`, true),
  code: Py.re(String.raw`\b(?=[a-z]*\d)(?=\d*[a-z])[a-z\d]{3,}\b`, true),
  num: Py.re(String.raw`\d[\d,.]*`),
  notLetters: Py.re(String.raw`[^a-z' ]+`),
  strayQuote: Py.re(String.raw`(?<![a-z])'|'(?![a-z])`),
  spaces: Py.re(String.raw`\s+`),
  sentenceEnd: Py.re(String.raw`(?<=[.!?])\s+`),
  tagLine: Py.re(String.raw`^\s*([a-z]+)\s*:\s*(.*)$`, true),
};
const CALLER_TAGS = new Set(["caller", "agent", "suspect", "scammer"]);
const LISTENER_TAGS = new Set(["receiver", "customer", "innocent", "victim", "user"]);

export const UrgencyText = {
  normalize(text) {
    let t = Py.nfkc(text);
    t = Py.sub(U.speaker, t, "");
    t = Py.sub(U.tag, t, (m) => TAG_MAP[m[1] ?? ""] ?? " ");
    t = t.replaceAll("’", "'").replaceAll("‘", "'");
    t = Py.sub(U.email, t, " emailtoken ");
    t = Py.sub(U.url, t, " urltoken ");
    t = Py.sub(U.money, t, " moneytoken ");
    t = Py.sub(U.date, t, " timetoken ");
    t = Py.sub(U.time, t, " timetoken ");
    t = Py.sub(U.phoneCand, t, (m) => {
      const digits = [...m[0]].filter(Py.isDigit).length;
      return digits >= 7 && digits <= 15 ? " phonetoken " : m[0];
    });
    t = Py.sub(U.hours, t, " numtoken hours ");
    t = Py.sub(U.mins, t, " numtoken minutes ");
    t = Py.sub(U.code, t, " numtoken ");
    t = Py.sub(U.num, t, " numtoken ");
    t = Py.lower(t);
    t = Py.sub(U.notLetters, t, " ");
    t = Py.sub(U.strayQuote, t, " ");
    return Py.strip(Py.sub(U.spaces, t, " "));
  },

  /** The caller's turns if the text is speaker-tagged, else the whole text. */
  callerTurns(transcript) {
    const turns = [];
    let tagged = false;
    let who = null;
    for (const raw of Py.splitLines(transcript)) {
      let line = raw;
      const m = Py.fullMatch(U.tagLine, line);
      if (m) {
        const tag = Py.lower(m[1] ?? "");
        if (CALLER_TAGS.has(tag) || LISTENER_TAGS.has(tag)) { tagged = true; who = tag; line = m[2] ?? ""; }
      }
      if (who === null || CALLER_TAGS.has(who)) turns.push(line);
    }
    return tagged ? turns : [transcript];
  },

  /** Sentences plus pairs of neighbouring sentences; the worker's score is the highest over these. */
  windows(transcript) {
    const sentences = [];
    for (const turn of this.callerTurns(transcript)) {
      for (const s of Py.splitRx(U.sentenceEnd, turn)) {
        const words = Py.split(s);
        if (words.length <= 40) sentences.push(s);
        else for (let i = 0; i < Math.max(words.length - 10, 1); i += 10) sentences.push(words.slice(i, Math.min(i + 20, words.length)).join(" "));
      }
    }
    const kept = sentences.filter((s) => this.normalize(s) !== "");
    const out = [...kept];
    for (let i = 0; i < kept.length - 1; i++) out.push(kept[i] + " " + kept[i + 1]);
    return out;
  },
};

// ---------------------------------------------------------------- money_en (money_norm.clean_text + split_sentences)

const NUMW = "(?:a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
  "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|" +
  "million|and|numtoken)";
const WORD_MONEY = Py.re(String.raw`\b(?:` + NUMW + String.raw`[ -]){1,6}(?:dollars?|bucks|sgd|ringgit)\b` +
  String.raw`|\bnumtoken ?(?:k|grand)\b|\b(?:a|one|two|three|five|ten) grand\b`);

const byLengthThenName = (a, b) => (a.length !== b.length ? b.length - a.length : a < b ? -1 : a > b ? 1 : 0);

/** money_norm v2: brand names -> their type token (DBS -> orgbank), amounts in words -> moneytoken. */
export class MoneyBrands {
  constructor(brandTypes) {
    this.toType = {};
    for (const [type, names] of Object.entries(brandTypes || {})) for (const n of names) this.toType[n] = type;
    const names = Object.keys(this.toType).sort(byLengthThenName);
    this.brand = names.length ? Py.re(String.raw`\b(` + names.map(Py.escape).join("|") + String.raw`)(?![\w])`) : null;
  }
  apply(lowered) {
    let t = lowered;
    if (this.brand) t = Py.sub(this.brand, t, (m) => this.toType[m[1] ?? ""] ?? m[0]);
    return Py.sub(WORD_MONEY, t, " moneytoken ");
  }
}

function placeholder(name) {
  const k = name.toUpperCase();
  const any = (...w) => w.some((x) => k.includes(x));
  if (any("MONEY", "AMOUNT", "PRICE", "BALANCE")) return " moneytoken ";
  if (any("OTP", "PIN", "CVV", "TAC", "PASSCODE", "PASSWORD", "CODE")) return " codetoken ";
  if (any("ORG", "COMPANY", "BUSINESS")) return " orgtoken ";
  if (k.includes("NAME")) return " nametoken ";
  if (any("DATE", "BIRTH", "DOB")) return " datetoken ";
  if (k.includes("PHONE")) return " phonetoken ";
  if (any("EMAIL", "URL", "WEBSITE", "LINK")) return " linktoken ";
  if (any("ADDRESS", "LOCATION", "CITY", "ZIP", "POSTAL", "STREET")) return " loctoken ";
  if (k.includes("CARD")) return " cardtoken ";
  if (any("ACCOUNT", "NUMBER", "SSN", "ID")) return " numtoken ";
  if (any("TIME", "DURATION")) return " timetoken ";
  return " ";
}

const MONTHS = "(?:january|february|march|april|may|june|july|august|september|october|" +
  "november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)";
const M = {
  placeholder: Py.re(String.raw`\[([A-Za-z_ ]{2,40})\]`),
  html: Py.re(String.raw`<[^>]+>`),
  steps: [
    [Py.re(String.raw`\S+@\S+\.\S+`), " linktoken "],
    [Py.re(String.raw`(?:https?://|www\.)\S+`, true), " linktoken "],
    [Py.re(String.raw`\b[\w-]+\.(?:com|net|org|sg|gov|io|co|info|biz)\b\S*`, true), " linktoken "],
    [Py.re(String.raw`(?:s\$|us\$|\$|£|€|¥|\bsgd|\busd|\brm)\s?\d[\d,]*(?:\.\d+)?` +
      String.raw`(?:\s?(?:k|m|million|billion|thousand)\b)?`, true), " moneytoken "],
    [Py.re(String.raw`\b\d[\d,]*(?:\.\d+)?\s?(?:dollars?|bucks|yuan|rmb|cents?|sgd|usd|` +
      String.raw`ringgit|pounds?|euros?)\b`, true), " moneytoken "],
    [Py.re(String.raw`\b(?:\d[ -]?){12,19}\b`), " cardtoken "],
    [Py.re(String.raw`(?:\+?\d{1,3}[\s-]?)?\(?\d{3}\)?X?[\s.-]?\(?\d{3}\)?X?[\s.-]?\(?\d{4}\)?`), " phonetoken "],
    [Py.re(String.raw`\b[689]\d{3}\s?\d{4}\b`), " phonetoken "],
    [Py.re(String.raw`\b\d{1,2}(?:st|nd|rd|th)?\s+(?:of\s+)?` + MONTHS + String.raw`\b\.?(?:,?\s+\d{4})?`, true), " datetoken "],
    [Py.re(String.raw`\b` + MONTHS + String.raw`\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?\b`, true), " datetoken "],
    [Py.re(String.raw`\b\d{1,4}[/-]\d{1,2}[/-]\d{2,4}\b`), " datetoken "],
    [Py.re(String.raw`\b\d{1,2}(?::\d{2})?\s?(?:am|pm|a\.m\.|p\.m\.)`, true), " timetoken "],
    [Py.re(String.raw`\b\d+(?:st|nd|rd|th)\b`, true), " numtoken "],
    [Py.re(String.raw`\d+(?:[.,]\d+)*`), " numtoken "],
  ],
  contractions: [
    [Py.re(String.raw`\bwon't\b`), "will not"], [Py.re(String.raw`\bcan't\b`), "cannot"],
    [Py.re(String.raw`\bshan't\b`), "shall not"], [Py.re(String.raw`n't\b`), " not"],
  ],
  notLetters: Py.re(String.raw`[^a-z\s]`),
  repeatedToken: Py.re(String.raw`\b(\w+token)(?:\s+\1\b)+`),
  spaces: Py.re(String.raw`\s+`),
  glued: Py.re(String.raw`([a-z][.!?])([A-Z])`),
  newlines: Py.re(String.raw`\s*\n+\s*`),
  sentEnd: Py.re(String.raw`(?<=[.!?])\s+`),
};

function lightNorm(text) {
  const t = text.replaceAll("’", "'").replaceAll("‘", "'").replaceAll("“", '"').replaceAll("”", '"');
  return Py.lower(Py.strip(Py.sub(M.spaces, t, " ")));
}

export const MoneyText = {
  /** brands = the v2 brand/amount masking (clean_text(mask_brands=True)); null = v1's cleaning exactly. */
  clean(text, brands = null) {
    let t = Py.sub(M.placeholder, text, (m) => placeholder(m[1] ?? ""));
    t = Py.sub(M.html, t, " ");
    for (const [rx, rep] of M.steps) t = Py.sub(rx, t, rep);
    // unicodedata.normalize("NFKD", t).encode("ascii", "ignore")
    t = [...t.normalize("NFKD")].filter((c) => c.codePointAt(0) < 128).join("");
    t = lightNorm(t);
    if (brands) t = brands.apply(t);
    for (const [rx, rep] of M.contractions) t = Py.sub(rx, t, rep);
    t = t.replaceAll("'", "");
    t = Py.sub(M.notLetters, t, " ");
    t = Py.sub(M.repeatedToken, t, (m) => m[1] ?? "");
    return Py.strip(Py.sub(M.spaces, t, " "));
  },

  /** Sentences; pieces longer than 40 words are cut into 20-word chunks. */
  splitSentences(text, maxWords = 40, chunkWords = 20) {
    const out = [];
    const t = Py.sub(M.glued, text, (m) => (m[1] ?? "") + " " + (m[2] ?? ""));
    for (const block of Py.splitRx(M.newlines, t)) {
      for (const raw of Py.splitRx(M.sentEnd, block)) {
        const s = Py.strip(raw);
        if (!s) continue;
        const words = Py.split(s);
        if (words.length > maxWords) for (let i = 0; i < words.length; i += chunkWords) out.push(words.slice(i, i + chunkWords).join(" "));
        else out.push(s);
      }
    }
    return out;
  },
};

// ---------------------------------------------------------------- impersonation_en (text_utils.prep)

export class ImpersonationText {
  constructor(orgTypes) {
    this.tag = Py.re(String.raw`\[[A-Za-z_ ]{2,30}\]`);
    this.speaker = Py.re(String.raw`\b(?:caller|receiver|agent|customer|person\s*[ab12]|speaker\s*\d|scammer|victim|user)\s*:`, true);
    this.digits = Py.re(String.raw`\d{4,}`);
    this.spaces = Py.re(String.raw`\s+`);
    this.labelSplit = Py.re(String.raw`\b(caller|scammer|agent|receiver|customer|victim|user)\s*:`, true);
    this.remote = new Set(["caller", "scammer", "agent"]);
    this.nameToType = {};
    for (const [type, names] of Object.entries(orgTypes || {})) for (const n of names) this.nameToType[n] = type;
    const names = Object.values(orgTypes || {}).flat().sort(byLengthThenName);
    this.org = names.length ? Py.re(String.raw`\b(` + names.map(Py.escape).join("|") + String.raw`)\b`) : null;
  }
  cleanText(text) {
    let t = Py.sub(this.tag, text, " ");
    t = Py.sub(this.speaker, t, " ");
    t = Py.lower(t);
    t = Py.sub(this.digits, t, "longnumber");
    return Py.strip(Py.sub(this.spaces, t, " "));
  }
  maskBrands(text) {
    if (!this.org) return text;
    return Py.sub(this.org, text, (m) => this.nameToType[m[1] ?? ""] ?? m[0]);
  }
  /** Every word the caller says; the receiver's turns are dropped. Untagged text is returned unchanged. */
  callerOnly(text) {
    const ms = Py.matches(this.labelSplit, text);
    if (!ms.length) return text;
    const kept = [text.slice(0, ms[0].index)];
    ms.forEach((m, i) => {
      const start = m.index + m[0].length;
      const end = i + 1 < ms.length ? ms[i + 1].index : text.length;
      if (this.remote.has(Py.lower(m[1] ?? ""))) kept.push(text.slice(start, end));
    });
    return kept.join(" ");
  }
  prep(text) { return this.maskBrands(this.cleanText(this.callerOnly(text))); }
}

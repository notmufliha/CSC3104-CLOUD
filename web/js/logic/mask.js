// Port of ios-app Logic/Mask.swift (= android-app logic/Mask.kt, backend/review/mask.py): removes personal details
// from a caller line before it leaves the browser (Hybrid's /v1/verify) or is kept (Developer view log, the copied
// report summary). Same rules and tags; checked against backend/review/mask_cases.json in the tests.
import * as Py from "../ondevice/pyregex.js";

/** Case-insensitive letters for a group JavaScript can't mark with (?i:...): "dr" -> "[dD][rR]". */
const ci = (s) => s.replace(/[a-z]/gi, (c) => `[${c.toLowerCase()}${c.toUpperCase()}]`);

const EMAIL = Py.re(String.raw`\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b`);
const URL_RX = Py.re(String.raw`\b(?:https?://|www\.)\S+|\b[\w-]+\.(?:com|sg|net|org|gov|io|co|my|cn)(?:/\S*)?\b`, true);
const MONEY = Py.re(String.raw`(?:(?:S?\$|SGD|USD|RM|MYR|RMB|¥|€|£)\s?\d[\d,]*(?:\.\d+)?(?:\s?(?:k|m|million|thousand))?)` +
  String.raw`|(?:\d[\d,]*(?:\.\d+)?\s?(?:dollars?|sgd|bucks|元|块))`, true);
const ID = Py.re(String.raw`\b[STFGM]\d{7}[A-Z]\b`, true);
const PHONE = Py.re(String.raw`(?<![\w])\+?\d[\d\s-]{6,}\d(?![\w])`);
const DIGITS = Py.re(String.raw`\d+`);
const TITLE = "(?:" + ["officer", "inspector", "sergeant", "mr", "mrs", "ms", "madam", "dr", "doctor"].map(ci).join("|") + ")\\.?";
const CUE = "(?:" + ["my name is", "this is", "i am", "i'm", "speaking with"].map(ci).join("|") + ")";
const NAME = Py.re(String.raw`((?:\b` + CUE + String.raw`\s+(?:` + TITLE + String.raw`\s+)?|\b` + TITLE + String.raw`\s+))` +
  String.raw`([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)`);
const NAME_ZH = /(我叫|我姓)([一-鿿]{1,3})/gu;
/** Capitalised words after "this is" / "I'm" that are not people (kept so impersonation stays labelable). */
const NOT_NAMES = new Set(("police singapore dbs ocbc uob posb maybank citibank hsbc standard moh mom iras cpf ica spf " +
  "customs immigration interpol the your a an calling from with here just not sorry sure fine okay ok very shopee " +
  "lazada grab singtel starhub m1 dhl fedex ninja amazon apple microsoft google manager officer inspector sergeant " +
  "madam sir doctor").split(" "));

export function mask(text) {
  let t = Py.sub(EMAIL, text, "[EMAIL_ADDRESS]");
  t = Py.sub(URL_RX, t, "[URL]");
  t = Py.sub(MONEY, t, "[MONEY_AMOUNT]");
  t = Py.sub(ID, t, "[NUMBER]");
  t = Py.sub(PHONE, t, "[PHONE_NUMBER]");
  t = Py.sub(DIGITS, t, "[NUMBER]");
  t = Py.sub(NAME, t, (m) => {
    const who = m[2] ?? "";
    const first = (who.split(" ")[0] || "").toLowerCase();
    return (m[1] ?? "") + (NOT_NAMES.has(first) ? who : "[PERSON_NAME]");
  });
  return Py.sub(NAME_ZH, t, (m) => (m[1] ?? "") + "[PERSON_NAME]");
}

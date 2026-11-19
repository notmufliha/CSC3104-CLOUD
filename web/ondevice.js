// ScamGuard web app: privacy levels 2 (Hybrid) and 3 (On-device) in the browser. A line-by-line port of the Android
// app's ondevice/ package (Murmur3.kt, PyRegex.kt, TextCleaning.kt, LinearTextModel.kt, OnDeviceScorer.kt,
// LocalRules.kt, OnDeviceCall.kt, WordPieceTokenizer.kt, MobileBert.kt), so the browser gives the same scores and the
// same SAFE / UNSURE / SCAM as the phone. The model files are the app's own (android-app/app/src/main/assets/models),
// served at ondevice/; MobileBERT runs with onnxruntime-web (vendor/ort/), loaded only when it is needed.
// Checked against the same parity files as the apps: tools/testing/web_ondevice_parity.mjs.
// If a Kotlin file here changes, change its port too and re-run that test.
"use strict";

(function (root) {
  // -------------------------------------------------------------- PyRegex.kt: Python `re` behaviour in JS
  //
  // Python 3 str patterns are Unicode-aware: \w, \d and \b match any language's letters and digits. JS keeps \w, \d
  // and \b ASCII-only even with the u flag, so they are rewritten here (\w = [\p{L}\p{N}_], \d = \p{Nd}).
  const W = "\\p{L}\\p{N}_";
  const WORD_B = `(?:(?<=[${W}])(?![${W}])|(?<![${W}])(?=[${W}]))`;
  const NOT_WORD_B = `(?:(?<=[${W}])(?=[${W}])|(?<![${W}])(?![${W}]))`;

  function translate(src) {
    let out = "";
    let inClass = false;
    for (let i = 0; i < src.length; i++) {
      const ch = src[i];
      if (ch === "\\") {
        const n = src[i + 1];
        i++;
        if (inClass) {
          if (n === "w") out += W;
          else if (n === "d") out += "\\p{Nd}";
          else if (n === "W" || n === "D" || n === "b") throw new Error(`unsupported \\${n} in a class: ${src}`);
          else out += "\\" + n;
        } else if (n === "w") out += `[${W}]`;
        else if (n === "W") out += `[^${W}]`;
        else if (n === "d") out += "\\p{Nd}";
        else if (n === "D") out += "\\P{Nd}";
        else if (n === "b") out += WORD_B;
        else if (n === "B") out += NOT_WORD_B;
        else out += "\\" + n;
        continue;
      }
      if (inClass) { if (ch === "]") inClass = false; out += ch; continue; }
      if (ch === "[") {
        inClass = true; out += ch;
        if (src[i + 1] === "^") { out += "^"; i++; }
        if (src[i + 1] === "]") { out += "\\]"; i++; }   // a literal ] first in a class
        continue;
      }
      out += ch;
    }
    return out;
  }

  // str.splitlines() breaks; U+2028/2029 made with fromCharCode so no raw line separator sits in the source.
  const LINE_BREAK = new RegExp("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085" + String.fromCharCode(0x2028, 0x2029) + "]", "u");
  const Py = {
    /** re.compile(pattern[, re.I]), global (for replace / findAll / split). */
    re(pattern, ignoreCase = false) { return new RegExp(translate(pattern), "gu" + (ignoreCase ? "i" : "")); },
    /** str.split() with no argument. */
    split(s) { return s.split(/\s+/u).filter((x) => x.length > 0); },
    /** str.splitlines(), no trailing empty line. */
    splitLines(s) {
      if (!s.length) return [];
      const parts = s.split(LINE_BREAK);
      return parts[parts.length - 1] === "" ? parts.slice(0, -1) : parts;
    },
    /** str.strip() */
    strip(s) { return s.replace(/^[\s\u001C-\u001F]+|[\s\u001C-\u001F]+$/gu, ""); },
    lower(s) { return s.toLowerCase(); },
    /** Regex.escape for a u-flag pattern ("-" must not be escaped outside a class there). */
    escape(s) { return s.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&"); },
  };
  const fullMatch = (rx, s) => {
    const r = new RegExp(rx.source, rx.flags.replace("g", ""));
    const m = r.exec(s);
    return m && m.index === 0 && m[0].length === s.length ? m : null;
  };
  const sub = (rx, s, rep) => s.replace(rx, typeof rep === "function" ? rep : () => rep);
  const nfkc = (s) => s.normalize("NFKC");

  // -------------------------------------------------------------- Murmur3.kt (sklearn murmurhash3_32, seed 0)

  const utf8 = new TextEncoder();
  const rotl = (x, r) => (x << r) | (x >>> (32 - r));
  const Murmur3 = {
    hash32(data, seed = 0) {
      const C1 = 0xcc9e2d51 | 0, C2 = 0x1b873593;
      let h = seed | 0;
      const blocks = data.length >> 2;
      for (let i = 0; i < blocks; i++) {
        const o = i * 4;
        let k = data[o] | (data[o + 1] << 8) | (data[o + 2] << 16) | (data[o + 3] << 24);
        k = Math.imul(k, C1); k = rotl(k, 15); k = Math.imul(k, C2);
        h ^= k; h = rotl(h, 13); h = (Math.imul(h, 5) + (0xe6546b64 | 0)) | 0;
      }
      const tail = blocks * 4, rest = data.length & 3;
      let k = 0;
      if (rest >= 3) k ^= data[tail + 2] << 16;
      if (rest >= 2) k ^= data[tail + 1] << 8;
      if (rest >= 1) {
        k ^= data[tail];
        k = Math.imul(k, C1); k = rotl(k, 15); k = Math.imul(k, C2);
        h ^= k;
      }
      h ^= data.length;
      h ^= h >>> 16; h = Math.imul(h, 0x85ebca6b | 0);
      h ^= h >>> 13; h = Math.imul(h, 0xc2b2ae35 | 0);
      h ^= h >>> 16;
      return h | 0;
    },
    slot(token, nFeatures) {
      const h = Murmur3.hash32(utf8.encode(token));
      return h === -2147483648 ? (2147483647 - (nFeatures - 1)) % nFeatures : Math.abs(h) % nFeatures;
    },
  };

  // -------------------------------------------------------------- TextCleaning.kt

  /** backend/workers/urgency_en/text_norm.py `normalize` + predict.py `caller_turns` / `windows`. */
  const UrgencyText = (() => {
    const TAG_MAP = {
      MONEY_AMOUNT: " moneytoken ", PHONE_NUMBER: " phonetoken ", EMAIL_ADDRESS: " emailtoken ", URL: " urltoken ",
      DATE: " timetoken ", DATE_OF_BIRTH: " timetoken ", TIME: " timetoken ", DURATION: " numtoken ", NUMBER: " numtoken ",
    };
    const TAG = Py.re(String.raw`\[([A-Z_]+)\]`);
    const SPEAKER = Py.re(String.raw`^\s*(person [ab]|caller|receiver|agent|customer|suspect|innocent|user|system|assistant)\s*:\s*`, true);
    const EMAIL = Py.re(String.raw`\b[\w.+-]+@[\w-]+\.[\w.]+\b`);
    const URL = Py.re(String.raw`(https?://\S+|www\.\S+|\b[\w-]+\.(com|org|net|gov|sg|cn|io|co)(/\S*)?\b)`, true);
    const MONEY = Py.re(String.raw`([$¥€£]\s?\d[\d,]*(\.\d+)?|\b\d[\d,]*(\.\d+)?\s?(dollars|sgd|usd|yuan|rmb|cny)\b)`, true);
    const DATE = Py.re(String.raw`\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\b`);
    const PHONE_CAND = Py.re(String.raw`(?<![\w])\+?\(?\d[\d\s().-]{5,18}\d(?![\w])`);
    const TIME = Py.re(String.raw`\b\d{1,2}[:.]\d{2}(\s?[ap]\.?m\.?)?\b|\b\d{1,2}\s?[ap]\.?m\.?\b`, true);
    const HOURS = Py.re(String.raw`\b\d+\s?(h|hr|hrs)\b`, true);
    const MINS = Py.re(String.raw`\b\d+\s?(m|min|mins)\b`, true);
    const CODE = Py.re(String.raw`\b(?=[a-z]*\d)(?=\d*[a-z])[a-z\d]{3,}\b`, true);
    const NUM = Py.re(String.raw`\d[\d,.]*`);
    const NOT_LETTERS = Py.re(String.raw`[^a-z' ]+`);
    const STRAY_QUOTE = Py.re(String.raw`(?<![a-z])'|'(?![a-z])`);
    const SPACES = Py.re(String.raw`\s+`);
    const DIGIT = /\p{Nd}/u;

    function normalize(text) {
      let t = nfkc(text);
      t = sub(SPEAKER, t, "");
      t = sub(TAG, t, (_, g) => TAG_MAP[g] ?? " ");
      t = t.replace(/’/g, "'").replace(/‘/g, "'");
      t = sub(EMAIL, t, " emailtoken ");
      t = sub(URL, t, " urltoken ");
      t = sub(MONEY, t, " moneytoken ");
      t = sub(DATE, t, " timetoken ");
      t = sub(TIME, t, " timetoken ");
      t = sub(PHONE_CAND, t, (m) => { const n = [...m].filter((c) => DIGIT.test(c)).length; return n >= 7 && n <= 15 ? " phonetoken " : m; });
      t = sub(HOURS, t, " numtoken hours ");
      t = sub(MINS, t, " numtoken minutes ");
      t = sub(CODE, t, " numtoken ");
      t = sub(NUM, t, " numtoken ");
      t = Py.lower(t);
      t = sub(NOT_LETTERS, t, " ");
      t = sub(STRAY_QUOTE, t, " ");
      return Py.strip(sub(SPACES, t, " "));
    }

    const SENTENCE_END = Py.re(String.raw`(?<=[.!?])\s+`);
    const MAX_WORDS = 40, WINDOW_WORDS = 20, WINDOW_STEP = 10;
    const CALLER_TAGS = new Set(["caller", "agent", "suspect", "scammer"]);
    const LISTENER_TAGS = new Set(["receiver", "customer", "innocent", "victim", "user"]);
    const TAG_LINE = Py.re(String.raw`^\s*([a-z]+)\s*:\s*(.*)$`, true);

    function callerTurns(transcript) {
      const turns = [];
      let tagged = false, speaker = null;
      for (const raw of Py.splitLines(transcript)) {
        let line = raw;
        const m = fullMatch(TAG_LINE, line);
        if (m && (CALLER_TAGS.has(Py.lower(m[1])) || LISTENER_TAGS.has(Py.lower(m[1])))) {
          tagged = true; speaker = Py.lower(m[1]); line = m[2];
        }
        if (speaker === null || CALLER_TAGS.has(speaker)) turns.push(line);
      }
      return tagged ? turns : [transcript];
    }

    function windows(transcript) {
      const sentences = [];
      for (const turn of callerTurns(transcript)) {
        for (const s of turn.split(SENTENCE_END)) {
          const words = Py.split(s);
          if (words.length <= MAX_WORDS) sentences.push(s);
          else for (let i = 0; i < Math.max(words.length - WINDOW_STEP, 1); i += WINDOW_STEP) {
            sentences.push(words.slice(i, Math.min(i + WINDOW_WORDS, words.length)).join(" "));
          }
        }
      }
      const kept = sentences.filter((s) => normalize(s).length > 0);
      const pairs = [];
      for (let i = 0; i + 1 < kept.length; i++) pairs.push(kept[i] + " " + kept[i + 1]);
      return kept.concat(pairs);
    }
    return { normalize, callerTurns, windows };
  })();

  /** money_norm.py v2: brand names -> their type token, amounts in words -> moneytoken. */
  class MoneyBrands {
    constructor(brandTypes) {
      this.toType = new Map();
      for (const [type, names] of Object.entries(brandTypes || {})) for (const n of names) this.toType.set(n, type);   // LinkedHashMap.put: a repeated name keeps its place
      const names = [...this.toType.keys()].sort((a, b) => b.length - a.length);
      this.brand = names.length ? Py.re(String.raw`\b(` + names.map(Py.escape).join("|") + String.raw`)(?![\w])`) : null;
    }
    apply(lowered) {
      const t = this.brand ? sub(this.brand, lowered, (_, g) => this.toType.get(g)) : lowered;
      return sub(MoneyBrands.WORD_MONEY, t, " moneytoken ");
    }
  }
  {
    const NUMW = "(?:a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
      "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|" +
      "million|and|numtoken)";
    MoneyBrands.WORD_MONEY = Py.re(String.raw`\b(?:` + NUMW + String.raw`[ -]){1,6}(?:dollars?|bucks|sgd|ringgit)\b` +
      String.raw`|\bnumtoken ?(?:k|grand)\b|\b(?:a|one|two|three|five|ten) grand\b`);
  }

  /** backend/workers/money_en/money_norm.py `clean_text` + `split_sentences`. */
  const MoneyText = (() => {
    const PLACEHOLDER = Py.re(String.raw`\[([A-Za-z_ ]{2,40})\]`);
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
    const HTML = Py.re(String.raw`<[^>]+>`);
    const CLEAN_STEPS = [
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
    ];
    const CONTRACTIONS = [
      [Py.re(String.raw`\bwon't\b`), "will not"], [Py.re(String.raw`\bcan't\b`), "cannot"],
      [Py.re(String.raw`\bshan't\b`), "shall not"], [Py.re(String.raw`n't\b`), " not"],
    ];
    const NOT_LETTERS = Py.re(String.raw`[^a-z\s]`);
    const REPEATED_TOKEN = Py.re(String.raw`\b(\w+token)(?:\s+\1\b)+`);
    const SPACES = Py.re(String.raw`\s+`);

    function lightNorm(text) {
      const t = text.replace(/’/g, "'").replace(/‘/g, "'").replace(/“/g, '"').replace(/”/g, '"');
      return Py.lower(Py.strip(sub(SPACES, t, " ")));
    }
    /** `brands` = v2 brand/amount masking; null = v1's cleaning. */
    function clean(text, brands = null) {
      let t = sub(PLACEHOLDER, text, (_, g) => placeholder(g));
      t = sub(HTML, t, " ");
      for (const [rx, rep] of CLEAN_STEPS) t = sub(rx, t, rep);
      t = [...t.normalize("NFKD")].filter((c) => c.charCodeAt(0) < 128 && c.length === 1).join("");   // .encode("ascii", "ignore")
      t = lightNorm(t);
      if (brands) t = brands.apply(t);
      for (const [rx, rep] of CONTRACTIONS) t = sub(rx, t, rep);
      t = t.replace(/'/g, "");
      t = sub(NOT_LETTERS, t, " ");
      t = sub(REPEATED_TOKEN, t, (_, g) => g);
      return Py.strip(sub(SPACES, t, " "));
    }
    const GLUED = Py.re(String.raw`([a-z][.!?])([A-Z])`);
    const NEWLINES = Py.re(String.raw`\s*\n+\s*`);
    const SENT_END = Py.re(String.raw`(?<=[.!?])\s+`);
    function splitSentences(text, maxWords = 40, chunkWords = 20) {
      const out = [];
      const t = sub(GLUED, text, (_, a, b) => `${a} ${b}`);
      for (const block of t.split(NEWLINES)) for (const raw of block.split(SENT_END)) {
        const s = Py.strip(raw);
        if (!s) continue;
        const words = Py.split(s);
        if (words.length > maxWords) {
          for (let i = 0; i < words.length; i += chunkWords) out.push(words.slice(i, Math.min(i + chunkWords, words.length)).join(" "));
        } else out.push(s);
      }
      return out;
    }
    return { clean, splitSentences };
  })();

  /** backend/workers/impersonation_en/text_utils.py `prep(mask=True, caller=True)`. */
  class ImpersonationText {
    constructor(orgTypes) {
      this.tag = Py.re(String.raw`\[[A-Za-z_ ]{2,30}\]`);
      this.speaker = Py.re(String.raw`\b(?:caller|receiver|agent|customer|person\s*[ab12]|speaker\s*\d|scammer|victim|user)\s*:`, true);
      this.digits = Py.re(String.raw`\d{4,}`);
      this.spaces = Py.re(String.raw`\s+`);
      this.labelSplit = Py.re(String.raw`\b(caller|scammer|agent|receiver|customer|victim|user)\s*:`, true);
      this.remote = new Set(["caller", "scammer", "agent"]);
      this.nameToType = new Map();
      for (const [type, names] of Object.entries(orgTypes || {})) for (const n of names) this.nameToType.set(n, type);
      const names = Object.values(orgTypes || {}).flat().sort((a, b) => b.length - a.length);
      this.org = names.length ? Py.re(String.raw`\b(` + names.map(Py.escape).join("|") + String.raw`)\b`) : null;
    }
    cleanText(text) {
      let t = sub(this.tag, text, " ");
      t = sub(this.speaker, t, " ");
      t = Py.lower(t);
      t = sub(this.digits, t, "longnumber");
      return Py.strip(sub(this.spaces, t, " "));
    }
    maskBrands(text) { return this.org ? sub(this.org, text, (_, g) => this.nameToType.get(g)) : text; }
    callerOnly(text) {
      const matches = [...text.matchAll(this.labelSplit)];
      if (!matches.length) return text;
      const kept = [text.substring(0, matches[0].index)];
      matches.forEach((m, i) => {
        const end = i + 1 < matches.length ? matches[i + 1].index : text.length;
        if (this.remote.has(Py.lower(m[1]))) kept.push(text.substring(m.index + m[0].length, end));
      });
      return kept.join(" ");
    }
    prep(text) { return this.maskBrands(this.cleanText(this.callerOnly(text))); }
  }

  // -------------------------------------------------------------- LinearTextModel.kt

  function decodeFloats(b64) {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const view = new DataView(bytes.buffer);
    const out = new Float64Array(bytes.length >> 2);
    for (let i = 0; i < out.length; i++) out[i] = view.getFloat32(i * 4, true);
    return out;
  }

  class Part {
    constructor(v) {
      if (v.analyzer !== "word" && v.analyzer !== "char_wb") throw new Error("unsupported analyzer " + v.analyzer);
      this.analyzer = v.analyzer;
      [this.minN, this.maxN] = v.ngram_range;
      this.lowercase = v.lowercase;
      this.sublinear = v.sublinear_tf;
      this.stopWords = new Set(v.stop_words || []);
      this.token = Py.re(v.token_pattern.replace(/^\(\?u\)/, ""));
      this.hashSlots = v.n_features ?? null;
      this.index = v.terms ? new Map(v.terms.map((t, i) => [t, i])) : null;
      this.idf = v.idf ?? null;
      this.idfDefault = v.idf_default ?? 0;
      this.idfSeen = v.idf_index ? new Map(v.idf_index.map((slot, k) => [slot, v.idf_value[k]])) : null;
      this.coef = v.coef ?? (v.coef_b64 ? decodeFloats(v.coef_b64) : null);
      if (!this.coef) throw new Error("model file has no weights");
      if (this.hashSlots != null ? this.coef.length !== this.hashSlots
        : (v.terms?.length !== this.coef.length || this.idf?.length !== this.coef.length)) throw new Error("model file is inconsistent");
    }
    slotOf(gram) { return this.hashSlots != null ? Murmur3.slot(gram, this.hashSlots) : this.index.get(gram); }
    idfOf(i) { return this.idf ? this.idf[i] : (this.idfSeen?.get(i) ?? this.idfDefault); }
    dot(text) { let s = 0; for (const [i, v] of this.features(text)) s += v * this.coef[i]; return s; }
    features(text) {
      const doc = this.lowercase ? Py.lower(text) : text;
      const counts = new Map();
      for (const gram of this.analyzer === "word" ? this.wordNgrams(doc) : this.charWbNgrams(doc)) {
        const i = this.slotOf(gram);
        if (i === undefined) continue;
        counts.set(i, (counts.get(i) || 0) + 1);
      }
      if (!counts.size) return new Map();
      const w = new Map();
      let norm = 0;
      for (const [i, c] of counts) {
        const v = (this.sublinear ? Math.log(c) + 1 : c) * this.idfOf(i);
        w.set(i, v); norm += v * v;
      }
      if (norm <= 0) return new Map();
      const n = Math.sqrt(norm);
      for (const [k, v] of w) w.set(k, v / n);
      return w;
    }
    wordNgrams(doc) {
      const tokens = [...doc.matchAll(this.token)].map((m) => m[0]).filter((t) => !this.stopWords.has(t));
      const out = [];
      for (let n = this.minN; n <= Math.min(this.maxN, tokens.length); n++) {
        for (let i = 0; i <= tokens.length - n; i++) out.push(n === 1 ? tokens[i] : tokens.slice(i, i + n).join(" "));
      }
      return out;
    }
    charWbNgrams(doc) {
      const out = [];
      for (const word of Py.split(doc)) {
        const w = Array.from(" " + word + " ");
        const len = w.length;
        for (let n = this.minN; n <= this.maxN; n++) {
          let offset = 0;
          out.push(w.slice(offset, Math.min(n, len)).join(""));
          while (offset + n < len) { offset += 1; out.push(w.slice(offset, offset + n).join("")); }
          if (offset === 0) break;
        }
      }
      return out;
    }
  }

  class LinearTextModel {
    constructor(doc) {
      if (doc.format !== 1 && doc.format !== 2) throw new Error(`model file format ${doc.format}, this page reads 1 and 2`);
      this.doc = doc;
      this.name = doc.name; this.version = doc.version;
      this.orgTypes = doc.org_types || {};
      this.maskBrands = doc.mask_brands === true;
      this.brandTypes = doc.brand_types || {};
      this.parts = doc.vectorizers.map((v) => new Part(v));
    }
    decision(cleaned) { return this.doc.intercept + this.parts.reduce((s, p) => s + p.dot(cleaned), 0); }
    proba(cleaned) {
      const z = this.decision(cleaned), cal = this.doc.calibration;
      return 1 / (1 + Math.exp(-(cal ? cal.a * z + cal.b : z)));
    }
  }

  // -------------------------------------------------------------- OnDeviceScorer.kt

  const fr = Math.fround;   // Kotlin's Float: the rules below decide in 32-bit floats, as on the phone

  class OnDeviceScorer {
    constructor(urgency, money, impersonation) {
      this.u = urgency; this.m = money; this.i = impersonation;
      this.impersonationText = new ImpersonationText(impersonation.orgTypes);
      this.moneyBrands = money.maskBrands ? new MoneyBrands(money.brandTypes) : null;
    }
    urgency(text) {
      const ws = UrgencyText.windows(text);
      return ws.length ? Math.max(...ws.map((w) => this.u.proba(UrgencyText.normalize(w)))) : 0;
    }
    money(text) {
      let ss = MoneyText.splitSentences(text);
      if (!ss.length) ss = [text];
      return Math.max(...ss.map((s) => this.m.proba(MoneyText.clean(s, this.moneyBrands))));
    }
    impersonation(text) { return this.i.proba(this.impersonationText.prep(text)); }
    score(line) { return { urgency: fr(this.urgency(line)), money: fr(this.money(line)), impersonation: fr(this.impersonation(line)) }; }
    get models() { return [this.u, this.m, this.i]; }
    static FILES = ["urgency_en.json", "money_en.json", "impersonation_en.json"];
    /** `load(name)` returns the parsed JSON of one model file. */
    static async load(load) {
      const [u, m, i] = await Promise.all(OnDeviceScorer.FILES.map(async (f) => new LinearTextModel(await load(f))));
      return new OnDeviceScorer(u, m, i);
    }
  }

  // -------------------------------------------------------------- LocalRules.kt (policy.rs, cascade)

  const SIGNALS = ["urgency", "money", "impersonation"];
  const SECOND_OPINION_RULES = new Set(["quorum", "money_rule", "high_once", "streak", "unsure", "strong_fallback"]);
  const REASON_TEXT = {
    money: "Asked for your OTP or money", impersonation: "Claims to be police, a bank or the government",
    urgency: "Pressuring you to act now", bert: "The conversation as a whole sounds like a scam",
  };
  const DEFAULT_CONFIG = {
    weights: [1 / 3, 1 / 3, 1 / 3], scamThreshold: 0.6, unsureThreshold: 0.45, bertScamThreshold: 0.6,
    fallbackStrongSignal: 0.8, redDisagreementsToClear: 2, windowLines: 6, warmupMs: 15000, warmupOverride: 0.9,
    moneyRule: 0.7, moneyRuleOther: 0.5, moneyRuleLines: 6, reasonThreshold: 0.5, minQuorum: 2, bertWeight: 0.5,
    bertSpotEvery: 5, bertMinLines: 3,
  };
  const FLOAT_KEYS = ["scamThreshold", "unsureThreshold", "bertScamThreshold", "fallbackStrongSignal", "warmupOverride",
    "moneyRule", "moneyRuleOther", "reasonThreshold", "bertWeight"];

  class LocalRules {
    constructor(cfg = {}) {
      const c = { ...DEFAULT_CONFIG, ...cfg };
      FLOAT_KEYS.forEach((k) => { c[k] = fr(c[k]); });
      c.weights = c.weights.map(fr);
      this.cfg = c;
      this.memory = { startedAtMs: 0, window: [], streak: 0, red: false, redDisagreements: 0, callerLines: 0 };
    }
    get callerLines() { return this.memory.callerLines; }
    get red() { return this.memory.red; }
    decide(line, nowMs, callTimeMs = 0, bert = null, bertStatus = bert != null ? "ok" : "off") {
      const [next, d] = this.step(this.memory, line, nowMs, callTimeMs, bert, bertStatus);
      this.memory = next;
      return d;
    }
    wantsSecondOpinion(line, nowMs, callTimeMs = 0) {
      return this.memory.red || this.isSpotCheck() || SECOND_OPINION_RULES.has(this.peek(line, nowMs, callTimeMs).rule);
    }
    isSpotCheck() { return this.cfg.bertSpotEvery > 0 && !this.memory.red && (this.memory.callerLines + 1) % this.cfg.bertSpotEvery === 0; }
    peek(line, nowMs, callTimeMs = 0) { return this.step(this.memory, line, nowMs, callTimeMs, null, "off")[1]; }

    step(before, lineIn, nowMs, callTimeMs, bertIn, bertStatus) {
      const cfg = this.cfg;
      if (lineIn.length !== SIGNALS.length) throw new Error("line must have 3 scores");
      const line = lineIn.map((s) => (s == null ? null : fr(s)));
      const bert = bertIn == null ? null : fr(bertIn);
      const startedAt = before.startedAtMs === 0 ? nowMs - Math.max(callTimeMs, 0) : before.startedAtMs;
      const window = before.window.concat([line]).slice(-cfg.windowLines);
      const callerLines = before.callerLines + 1;
      const recent = this.highs(window);
      const fast = this.combined(recent);
      const answered = line.filter((s) => s != null).length;
      const quorumMet = answered >= cfg.minQuorum;
      const warmingUp = (callTimeMs > 0 ? callTimeMs : nowMs - startedAt) < cfg.warmupMs;
      const combined = bert == null ? fast
        : quorumMet ? fr(fr(fr(1 - cfg.bertWeight) * fast) + fr(cfg.bertWeight * bert)) : bert;
      const overrideHit = recent.some((s) => s != null && s >= cfg.warmupOverride) || (bert != null && bert >= cfg.warmupOverride);
      const bertUnavailable = ["off", "timeout", "error"].includes(bertStatus);
      const bertConfirms = bert != null && bert >= cfg.bertScamThreshold;
      const redCandidate = (!warmingUp || overrideHit) && (quorumMet || bert != null) &&
        ((quorumMet && this.moneyRule(window)) || fast >= cfg.scamThreshold || bertConfirms);
      const strongFallback = recent.filter((s) => s != null && s >= cfg.fallbackStrongSignal).length >= 2;

      let streak = before.streak, red = before.red, disagreements = before.redDisagreements;
      let verdict, rule;
      if (before.red && bert == null) [verdict, rule] = ["SCAM", "red_bert_unavailable"];
      else if (before.red) {
        if (bertConfirms) { disagreements = 0; [verdict, rule] = ["SCAM", "red_bert_confirmed"]; }
        else {
          disagreements += 1;
          if (disagreements < cfg.redDisagreementsToClear) [verdict, rule] = ["SCAM", "red_bert_disagrees"];
          else { red = false; disagreements = 0; [verdict, rule] = ["UNSURE", "red_cleared"]; }
        }
      } else if (!quorumMet && bert == null) [verdict, rule] = ["UNSURE", "quorum"];
      else if (warmingUp && !overrideHit) { streak = 0; [verdict, rule] = ["SAFE", "warmup"]; }
      else if (redCandidate && bertConfirms) [verdict, rule] = ["SCAM", "bert_confirmed"];
      else if (redCandidate && bert == null && bertUnavailable && strongFallback) [verdict, rule] = ["SCAM", "strong_fallback"];
      else if (combined >= cfg.unsureThreshold || redCandidate) { streak = 0; [verdict, rule] = ["UNSURE", "unsure"]; }
      else { streak = 0; [verdict, rule] = ["SAFE", "safe"]; }
      if (verdict === "SCAM") red = true; else { red = false; disagreements = 0; }
      const after = { startedAtMs: startedAt, window, streak, red, redDisagreements: disagreements, callerLines };

      const reasons = verdict === "SAFE" ? [] : this.allReasons(recent, bert);
      if (rule === "quorum") reasons.push({ signal: "quorum", score: answered, text: `Not enough data: only ${answered} of ${SIGNALS.length} checks answered` });
      return [after, { verdict, combined, fast, bert, recent, reasons, warmingUp, quorumMet, callerLines, rule }];
    }
    highs(lines) {
      return SIGNALS.map((_, i) => {
        const v = lines.map((l) => l[i]).filter((s) => s != null);
        return v.length ? Math.max(...v) : null;
      });
    }
    combined(recent) {
      let sum = 0, weight = 0;
      recent.forEach((s, i) => { if (s != null) { sum = fr(sum + fr(this.cfg.weights[i] * s)); weight = fr(weight + this.cfg.weights[i]); } });
      return weight > 0 ? fr(sum / weight) : 0;
    }
    moneyRule(window) {
      const recent = this.highs(window.slice(-this.cfg.moneyRuleLines));
      const m = SIGNALS.indexOf("money");
      const moneyHigh = recent[m] != null && recent[m] >= this.cfg.moneyRule;
      const otherHigh = recent.some((s, i) => i !== m && s != null && s >= this.cfg.moneyRuleOther);
      return moneyHigh && otherHigh;
    }
    allReasons(recent, bert) {
      const out = [];
      SIGNALS.forEach((s, i) => { if (recent[i] != null && recent[i] >= this.cfg.reasonThreshold) out.push({ signal: s, score: recent[i], text: REASON_TEXT[s] }); });
      if (bert != null && bert >= this.cfg.reasonThreshold) out.push({ signal: "bert", score: bert, text: REASON_TEXT.bert });
      return out.sort((a, b) => b.score - a.score);   // stable, like Kotlin's sortedByDescending
    }
  }
  LocalRules.SIGNALS = SIGNALS;
  LocalRules.SECOND_OPINION_RULES = SECOND_OPINION_RULES;
  LocalRules.REASON_TEXT = REASON_TEXT;

  // -------------------------------------------------------------- OnDeviceCall.kt

  const ASK_AT = fr(0.45), MIN_LINES = 3, WINDOW_LINES = 5, VERIFY_TIMEOUT_MS = 1500;

  class OnDeviceCall {
    constructor(scorer, rules = new LocalRules(), clock = Date.now) {
      this.scorer = scorer; this.rules = rules; this.clock = clock; this.recentLines = [];
    }
    /** Level 3 without MobileBERT: everything here, no second opinion. */
    answer(callId, seq, text) {
      const start = performance.now();
      const scores = this.scorer.score(text);
      this.remember(text);
      const d = this.rules.decide(lineOf(scores), this.clock());
      return response(callId, seq, scores, d, start);
    }
    /** Level 2: `verify(maskedLines)` asks the cloud's BERT; resolves to its score, or null. */
    answerHybrid(callId, seq, text, verify, mask) {
      return this.answerWithSecondOpinion(callId, seq, text, mask, false, verify);
    }
    async answerWithSecondOpinion(callId, seq, text, prepare, askWhenRed, ask) {
      const start = performance.now();
      const now = this.clock();
      const scores = this.scorer.score(text);
      this.remember(text);
      const line = lineOf(scores);
      const own = this.rules.peek(line, now);
      let wanted = null;
      if (this.rules.red) wanted = askWhenRed ? "red" : null;
      else if (own.fast >= ASK_AT || SECOND_OPINION_RULES.has(own.rule)) wanted = "unsure";
      else if (this.rules.isSpotCheck()) wanted = "spot_check";
      const enoughLines = this.rules.callerLines + 1 >= MIN_LINES;
      let second = null;
      if (wanted && enoughLines) {
        const lines = this.recentLines.map(prepare);
        let score = null;
        try { score = await ask(lines); } catch { score = null; }
        second = { why: wanted, lines, bertScore: score == null ? null : score };
      }
      const status = second == null && wanted != null && !enoughLines ? "no_context"
        : second == null ? "skipped" : second.bertScore != null ? "ok" : "error";
      const d = this.rules.decide(line, now, 0, second?.bertScore ?? null, status);
      return { response: response(callId, seq, scores, d, start, status), decision: d, second };
    }
    remember(text) { this.recentLines.push(text); while (this.recentLines.length > WINDOW_LINES) this.recentLines.shift(); }

    /** Same 30% rule as the gateway: more than 30% Chinese characters = Chinese; a "zh" script is Chinese throughout. */
    static isEnglish(scriptLang, text) {
      if (scriptLang === "zh") return false;
      let chinese = 0;
      for (let i = 0; i < text.length; i++) { const c = text.charCodeAt(i); if (c >= 0x4e00 && c <= 0x9fff) chinese++; }
      return chinese * 10 <= text.length * 3;
    }
  }
  OnDeviceCall.ASK_AT = ASK_AT; OnDeviceCall.MIN_LINES = MIN_LINES; OnDeviceCall.WINDOW_LINES = WINDOW_LINES;
  OnDeviceCall.VERIFY_TIMEOUT_MS = VERIFY_TIMEOUT_MS;

  const lineOf = (scores) => SIGNALS.map((s) => scores[s] ?? null);
  /** The gateway's POST /v1/utterance answer, so the page handles both the same way. */
  function response(callId, seq, scores, d, start, bertStatus = "off") {
    const callScores = {};
    SIGNALS.forEach((s, i) => { if (d.recent[i] != null) callScores[s] = d.recent[i]; });
    return {
      call_id: callId, seq, scored: true, duplicate: false, verdict: d.verdict, combined_score: d.combined,
      language: "en", scores, missing: [], call_scores: callScores, recent_scores: callScores,
      caller_lines: d.callerLines, latency_ms: Math.round(performance.now() - start),
      fast_score: d.fast, bert_score: d.bert, bert_status: bertStatus, rule: d.rule,
      reason: d.reasons[0]?.text ?? "",
    };
  }

  // -------------------------------------------------------------- WordPieceTokenizer.kt

  const CONTROL = /^[\p{Cc}\p{Cf}]$/u, SPACE_SEP = /^\p{Zs}$/u, PUNCT = /^\p{P}$/u, MN = /\p{Mn}/gu;
  class WordPieceTokenizer {
    constructor(vocab, lowerCase = true, maxCharsPerWord = 100) {
      this.vocab = vocab; this.lowerCase = lowerCase; this.maxCharsPerWord = maxCharsPerWord;
      const id = (t) => { const v = vocab.get(t); if (v === undefined) throw new Error("vocab.txt has no " + t); return v; };
      this.clsId = id("[CLS]"); this.sepId = id("[SEP]"); this.unkId = id("[UNK]");
    }
    static fromVocabText(text, lowerCase = true) {
      const vocab = new Map();
      const lines = text.split("\n");
      if (lines.length && lines[lines.length - 1] === "") lines.pop();
      lines.forEach((line, i) => { const t = line.replace(/[\r\n]+$/, ""); if (!vocab.has(t)) vocab.set(t, i); });
      return new WordPieceTokenizer(vocab, lowerCase);
    }
    tokenize(text) { return this.basic(text).flatMap((w) => this.wordPiece(w)); }
    encode(text, maxLen) {
      const ids = this.tokenize(text).map((t) => this.vocab.get(t) ?? this.unkId);
      const kept = ids.slice(Math.max(0, ids.length - (maxLen - 2)));
      return [this.clsId, ...kept, this.sepId];
    }
    basic(text) {
      let cleaned = "";
      for (const ch of text) {
        const cp = ch.codePointAt(0);
        if (cp === 0 || cp === 0xfffd || isControl(ch, cp)) continue;
        if (isWhitespace(ch, cp)) cleaned += " ";
        else if (isCjk(cp)) cleaned += " " + ch + " ";
        else cleaned += ch;
      }
      const words = [];
      for (const raw of cleaned.split(" ").filter((x) => x)) {
        let w = raw;
        if (this.lowerCase) w = w.toLowerCase().normalize("NFD").replace(MN, "");
        let cur = "";
        for (const ch of w) {
          if (isPunctuation(ch, ch.codePointAt(0))) { if (cur) { words.push(cur); cur = ""; } words.push(ch); }
          else cur += ch;
        }
        if (cur) words.push(cur);
      }
      return words;
    }
    wordPiece(word) {
      const cps = Array.from(word);
      if (cps.length > this.maxCharsPerWord) return ["[UNK]"];
      const out = [];
      let start = 0;
      while (start < cps.length) {
        let end = cps.length, piece = null;
        while (start < end) {
          const s = (start > 0 ? "##" : "") + cps.slice(start, end).join("");
          if (this.vocab.has(s)) { piece = s; break; }
          end -= 1;
        }
        if (piece == null) return ["[UNK]"];
        out.push(piece); start = end;
      }
      return out;
    }
  }
  const isWhitespace = (ch, cp) => cp === 32 || cp === 9 || cp === 10 || cp === 13 || SPACE_SEP.test(ch);
  const isControl = (ch, cp) => (cp === 9 || cp === 10 || cp === 13) ? false : CONTROL.test(ch);
  const isPunctuation = (ch, cp) => (cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) ||
    (cp >= 123 && cp <= 126) || PUNCT.test(ch);
  const isCjk = (cp) => (cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x20000 && cp <= 0x2a6df) ||
    (cp >= 0x2a700 && cp <= 0x2b73f) || (cp >= 0x2b740 && cp <= 0x2b81f) || (cp >= 0x2b820 && cp <= 0x2ceaf) ||
    (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0x2f800 && cp <= 0x2fa1f);

  // -------------------------------------------------------------- MobileBert.kt

  class MobileBert {
    constructor(ort, session, tokenizer, config, modelBytes) {
      this.ort = ort; this.session = session; this.tokenizer = tokenizer; this.config = config; this.modelBytes = modelBytes;
    }
    /** bert_text.make_window: NFKC, whitespace collapsed, empty lines dropped, the last maxTurns, " | ". */
    static window(lines, maxTurns = 5) {
      return lines.map((l) => nfkc(l).replace(/\s+/g, " ").trim()).filter((l) => l).slice(-maxTurns).join(" | ");
    }
    static softmax(logits, temperature) {
      const z = Array.from(logits, (v) => v / temperature);
      const max = Math.max(...z);
      const e = z.map((v) => Math.exp(v - max));
      const sum = e.reduce((a, b) => a + b, 0);
      return e.map((v) => v / sum);
    }
    /** P(scam) for the caller's recent lines, oldest first. */
    async score(lines) {
      const text = MobileBert.window(lines, this.config.max_turns);
      if (!text) return 0;
      const ids = this.tokenizer.encode(text, this.config.max_len);
      const dims = [1, ids.length];
      const T = (arr) => new this.ort.Tensor("int64", BigInt64Array.from(arr, (v) => BigInt(v)), dims);
      const feed = {};
      const names = this.session.inputNames;
      if (names.includes("input_ids")) feed.input_ids = T(ids);
      if (names.includes("attention_mask")) feed.attention_mask = T(ids.map(() => 1));
      if (names.includes("token_type_ids")) feed.token_type_ids = T(ids.map(() => 0));
      const out = await this.session.run(feed);
      const logits = out[this.session.outputNames[0]].data;
      return fr(MobileBert.softmax(logits, this.config.temperature)[this.config.scam_index]);
    }
    /**
     * `ort` = the onnxruntime-web module; `fetchBytes(name)` -> ArrayBuffer or null, `fetchText(name)` -> string or null.
     * Resolves to { installed: MobileBert } or { reason }.
     */
    static async load(ort, fetchText, fetchBytes) {
      const defaults = { version: "unknown", max_len: 128, temperature: 1.0, scam_index: 1, max_turns: 5, lower_case: true, model_file: "model.onnx" };
      let cfg = defaults;
      try { const t = await fetchText("bert_config.json"); if (t) cfg = { ...defaults, ...JSON.parse(t) }; } catch { /* defaults */ }
      const bytes = await fetchBytes(cfg.model_file);
      if (!bytes) return { reason: `MobileBERT not installed (no ${cfg.model_file})` };
      const vocab = await fetchText("vocab.txt");
      if (!vocab) return { reason: "MobileBERT not installed (no vocab.txt)" };
      try {
        const session = await ort.InferenceSession.create(new Uint8Array(bytes), { executionProviders: ["wasm"] });
        return { installed: new MobileBert(ort, session, WordPieceTokenizer.fromVocabText(vocab, cfg.lower_case), cfg, bytes.byteLength) };
      } catch (e) {
        return { reason: `MobileBERT could not be loaded: ${e && e.message ? String(e.message).slice(0, 120) : e}` };
      }
    }
  }

  // -------------------------------------------------------------- logic/Mask.kt (and backend/review/mask.py)
  // What Hybrid sends to the cloud, and what the report summary copies. Same rules and tags as the apps.

  const Mask = (() => {
    const email = /\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b/g;
    const url = /\b(?:https?:\/\/|www\.)\S+|\b[\w-]+\.(?:com|sg|net|org|gov|io|co|my|cn)(?:\/\S*)?\b/gi;
    const money = /(?:(?:S?\$|SGD|USD|RM|MYR|RMB|¥|€|£)\s?\d[\d,]*(?:\.\d+)?(?:\s?(?:k|m|million|thousand))?)|(?:\d[\d,]*(?:\.\d+)?\s?(?:dollars?|sgd|bucks|元|块))/gi;
    const id = /\b[STFGM]\d{7}[A-Z]\b/gi;
    const phone = /(?<![\w])\+?\d[\d\s-]{6,}\d(?![\w])/g;
    const digits = /\d+/g;
    const TITLE = "(?:[Oo]fficer|[Ii]nspector|[Ss]ergeant|[Mm]r|[Mm]rs|[Mm]s|[Mm]adam|[Dd]r|[Dd]octor)\\.?";
    const CUE = "(?:[Mm]y name is|[Tt]his is|[Ii] am|[Ii]'m|[Ss]peaking with)";
    const name = new RegExp(`((?:\\b${CUE}\\s+(?:${TITLE}\\s+)?|\\b${TITLE}\\s+))([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?)`, "g");
    const nameZh = /(我叫|我姓)([一-鿿]{1,3})/g;
    const notNames = new Set(("police singapore dbs ocbc uob posb maybank citibank hsbc standard moh mom iras cpf ica spf " +
      "customs immigration interpol the your a an calling from with here just not sorry sure fine okay ok very shopee " +
      "lazada grab singtel starhub m1 dhl fedex ninja amazon apple microsoft google manager officer inspector sergeant " +
      "madam sir doctor").split(" "));
    return (text) => {
      let t = text.replace(email, "[EMAIL_ADDRESS]");
      t = t.replace(url, "[URL]").replace(money, "[MONEY_AMOUNT]").replace(id, "[NUMBER]");
      t = t.replace(phone, "[PHONE_NUMBER]").replace(digits, "[NUMBER]");
      t = t.replace(name, (_, cue, who) => cue + (notNames.has(who.split(" ")[0].toLowerCase()) ? who : "[PERSON_NAME]"));
      return t.replace(nameZh, (_, cue) => cue + "[PERSON_NAME]");
    };
  })();

  const api = { Py, Murmur3, UrgencyText, MoneyText, MoneyBrands, ImpersonationText, LinearTextModel, OnDeviceScorer,
    LocalRules, OnDeviceCall, WordPieceTokenizer, MobileBert, Mask, SIGNALS };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.OnDevice = api;
})(typeof globalThis !== "undefined" ? globalThis : this);

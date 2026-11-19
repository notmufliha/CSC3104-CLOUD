// Ports of ios-app OnDevice/Murmur3.swift, LinearTextModel.swift and OnDeviceScorer.swift.
//
// LinearTextModel: one exported English model (assets/models/<name>_en.json, written by tools/export_models.py):
// TF-IDF vectoriser(s) -> logistic regression -> optional Platt calibration. Reproduces scikit-learn's
// TfidfVectorizer.transform + LogisticRegression.decision_function + predict_proba. Format 2 (the federated models
// downloaded from the registry) is a HASHING vectoriser: a token's slot is abs(murmurhash3_32(token)) % n_features.
import * as Py from "./pyregex.js";
import { UrgencyText, MoneyText, MoneyBrands, ImpersonationText } from "./textCleaning.js";

// ---------------------------------------------------------------- MurmurHash3 x86 32-bit (scikit-learn's)

const utf8 = new TextEncoder();

/** The signed 32-bit hash of the bytes, as Python / Java print it. */
export function hash32(bytes, seed = 0) {
  const c1 = 0xcc9e2d51, c2 = 0x1b873593;
  let h = seed >>> 0;
  const blocks = bytes.length >> 2;
  for (let i = 0; i < blocks; i++) {
    const o = i * 4;
    let k = (bytes[o] | (bytes[o + 1] << 8) | (bytes[o + 2] << 16) | (bytes[o + 3] << 24)) >>> 0;
    k = Math.imul(k, c1); k = (k << 15) | (k >>> 17); k = Math.imul(k, c2);
    h ^= k; h = (h << 13) | (h >>> 19); h = (Math.imul(h, 5) + 0xe6546b64) | 0;
  }
  const tail = blocks * 4;
  const rest = bytes.length & 3;
  let k = 0;
  if (rest >= 3) k ^= bytes[tail + 2] << 16;
  if (rest >= 2) k ^= bytes[tail + 1] << 8;
  if (rest >= 1) {
    k ^= bytes[tail];
    k = Math.imul(k, c1); k = (k << 15) | (k >>> 17); k = Math.imul(k, c2);
    h ^= k;
  }
  h ^= bytes.length;
  h ^= h >>> 16; h = Math.imul(h, 0x85ebca6b);
  h ^= h >>> 13; h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return h | 0;
}

/** scikit-learn's slot for a token: abs(hash) % nFeatures (with its special case for Int32.min). */
export function slot(token, nFeatures) {
  const h = hash32(utf8.encode(token));
  if (h === -2147483648) return (2147483647 - (nFeatures - 1)) % nFeatures;
  return Math.abs(h) % nFeatures;
}

// ---------------------------------------------------------------- the model

/** float32 little-endian, base64 -> numbers. */
export function decodeFloats(b64) {
  const bin = atob(b64);
  const buf = new ArrayBuffer(bin.length);
  const u8 = new Uint8Array(buf);
  for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
  return Array.from(new Float32Array(buf, 0, bin.length >> 2));
}

class Part {
  constructor(v) {
    this.analyzer = v.analyzer;
    [this.minN, this.maxN] = v.ngram_range;
    this.lowercase = v.lowercase;
    this.sublinear = v.sublinear_tf;
    this.stopWords = new Set(v.stop_words || []);
    this.token = Py.re(v.token_pattern);
    this.hashSlots = v.n_features ?? null;
    this.index = null;
    if (v.terms) { this.index = new Map(); v.terms.forEach((t, i) => this.index.set(t, i)); }
    this.idf = v.idf ?? null;
    this.idfDefault = v.idf_default ?? 0;
    this.idfSeen = null;
    if (v.idf_index) {
      if (!v.idf_value) throw new Error("model file is inconsistent: idf_index without idf_value");
      this.idfSeen = new Map(); v.idf_index.forEach((s, k) => this.idfSeen.set(s, v.idf_value[k]));
    }
    if (v.coef) this.coef = v.coef;
    else if (v.coef_b64) this.coef = decodeFloats(v.coef_b64);
    else throw new Error("model file is inconsistent: model file has no weights");
    if (this.analyzer !== "word" && this.analyzer !== "char_wb") throw new Error(`model file is inconsistent: unsupported analyzer ${this.analyzer}`);
    if (this.hashSlots != null) {
      if (this.coef.length !== this.hashSlots) throw new Error(`model file is inconsistent: ${this.coef.length} weights for ${this.hashSlots} slots`);
    } else if (v.terms?.length !== this.coef.length || this.idf?.length !== this.coef.length) {
      throw new Error("model file is inconsistent: sizes differ");
    }
  }

  slotOf(gram) { return this.hashSlots != null ? slot(gram, this.hashSlots) : this.index.get(gram); }
  idfOf(i) { return this.idf ? this.idf[i] : (this.idfSeen?.get(i) ?? this.idfDefault); }

  /** tf-idf (sublinear, l2-normalised) of the text, dotted with this part's weights. */
  dot(text) {
    let s = 0;
    for (const [i, v] of this.features(text)) s += v * this.coef[i];
    return s;
  }

  features(text) {
    const doc = this.lowercase ? Py.lower(text) : text;
    const counts = new Map();
    for (const gram of this.analyzer === "word" ? this.wordNgrams(doc) : this.charWbNgrams(doc)) {
      const i = this.slotOf(gram);
      if (i === undefined) continue;
      counts.set(i, (counts.get(i) || 0) + 1);
    }
    const w = new Map();
    let norm = 0;
    for (const [i, c] of counts) {
      const v = (this.sublinear ? Math.log(c) + 1 : c) * this.idfOf(i);
      w.set(i, v);
      norm += v * v;
    }
    if (norm <= 0) return new Map();
    const n = Math.sqrt(norm);
    for (const [k, v] of w) w.set(k, v / n);
    return w;
  }

  /** sklearn _word_ngrams: tokens minus stop words, then all n-grams joined by a space. */
  wordNgrams(doc) {
    const tokens = Py.findAll(this.token, doc).filter((t) => !this.stopWords.has(t));
    const out = [];
    for (let n = this.minN; n <= Math.min(this.maxN, tokens.length); n++) {
      for (let i = 0; i + n <= tokens.length; i++) out.push(n === 1 ? tokens[i] : tokens.slice(i, i + n).join(" "));
    }
    return out;
  }

  /** sklearn _char_wb_ngrams: per whitespace-separated word, padded with one space each side (code points). */
  charWbNgrams(doc) {
    const out = [];
    for (const word of Py.split(doc)) {
      const w = [...(" " + word + " ")];
      const len = w.length;
      for (let n = this.minN; n <= this.maxN; n++) {
        let offset = 0;
        out.push(w.slice(offset, offset + Math.min(n, len)).join(""));
        while (offset + n < len) { offset++; out.push(w.slice(offset, offset + n).join("")); }
        if (offset === 0) break;
      }
    }
    return out;
  }
}

export class LinearTextModel {
  static FORMAT = 1;
  static FORMAT_HASHING = 2;

  /** From the JSON text or object; throws on a file this app can't read. */
  static load(json) {
    const doc = typeof json === "string" ? JSON.parse(json) : json;
    if (doc.format !== 1 && doc.format !== 2) throw new Error(`model file format ${doc.format}, this app reads 1 and 2`);
    return new LinearTextModel(doc);
  }

  constructor(doc) {
    this.doc = doc;
    this.parts = doc.vectorizers.map((v) => new Part(v));
  }
  get name() { return this.doc.name; }
  get version() { return this.doc.version; }
  get threshold() { return this.doc.threshold; }
  get orgTypes() { return this.doc.org_types || {}; }
  get maskBrands() { return this.doc.mask_brands === true; }
  get brandTypes() { return this.doc.brand_types || {}; }
  get isHashing() { return this.doc.format === 2; }
  get featureCount() { return this.parts.reduce((a, p) => a + p.coef.length, 0); }

  decision(cleaned) { return this.doc.intercept + this.parts.reduce((a, p) => a + p.dot(cleaned), 0); }
  proba(cleaned) {
    const z = this.decision(cleaned);
    const cal = this.doc.calibration;
    return sigmoid(cal ? cal.a * z + cal.b : z);
  }
}
const sigmoid = (x) => 1 / (1 + Math.exp(-x));

// ---------------------------------------------------------------- the three checks

/** The three English checks, run in the browser, each scored the way its worker scores a caller line. */
export class OnDeviceScorer {
  static FILES = ["urgency_en.json", "money_en.json", "impersonation_en.json"];

  constructor(urgency, money, impersonation) {
    this.urgencyModel = urgency;
    this.moneyModel = money;
    this.impersonationModel = impersonation;
    this.impersonationText = new ImpersonationText(impersonation.orgTypes);
    this.moneyBrands = money.maskBrands ? new MoneyBrands(money.brandTypes) : null;
  }

  /** `open(file)` returns the JSON text (or object) of one model file. */
  static async load(open) {
    const m = await Promise.all(OnDeviceScorer.FILES.map(async (f) => LinearTextModel.load(await open(f))));
    return new OnDeviceScorer(m[0], m[1], m[2]);
  }

  /** urgency / money / impersonation -> 0-1 for one caller line. */
  score(line) { return { urgency: this.urgency(line), money: this.money(line), impersonation: this.impersonation(line) }; }

  urgency(text) {
    const w = UrgencyText.windows(text);
    return w.length ? Math.max(...w.map((x) => this.urgencyModel.proba(UrgencyText.normalize(x)))) : 0;
  }
  money(text) {
    const s = MoneyText.splitSentences(text);
    return Math.max(...(s.length ? s : [text]).map((x) => this.moneyModel.proba(MoneyText.clean(x, this.moneyBrands))));
  }
  impersonation(text) { return this.impersonationModel.proba(this.impersonationText.prep(text)); }
  get models() { return [this.urgencyModel, this.moneyModel, this.impersonationModel]; }
}

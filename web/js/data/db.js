// Port of ios-app Data/CallStore.swift: the call history, chart points and implicit labels, in IndexedDB (this browser
// only; nothing is uploaded). Reads come from an in-memory copy loaded at start, so screens read synchronously like
// the app's store; every change is written through to IndexedDB. Downloaded models live here too.
const DB_NAME = "scamguard", VERSION = 1;

function open() {
  return new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) return reject(new Error("no IndexedDB"));
    const req = indexedDB.open(DB_NAME, VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains("calls")) db.createObjectStore("calls", { keyPath: "id" });
      if (!db.objectStoreNames.contains("points")) db.createObjectStore("points", { keyPath: "key" });
      if (!db.objectStoreNames.contains("labels")) db.createObjectStore("labels", { keyPath: "key" });
      if (!db.objectStoreNames.contains("models")) db.createObjectStore("models", { keyPath: "file" });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
const all = (db, store) => new Promise((res, rej) => {
  const r = db.transaction(store).objectStore(store).getAll();
  r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
});

export class CallStore {
  constructor() { this.db = null; this._calls = []; this._points = []; this._labels = []; }

  /** Loads everything (falls back to memory only if IndexedDB is unavailable, e.g. some private windows). */
  async init() {
    try {
      this.db = await open();
      [this._calls, this._points, this._labels] = await Promise.all([all(this.db, "calls"), all(this.db, "points"), all(this.db, "labels")]);
      this.sort();
    } catch { this.db = null; }
    return this;
  }
  sort() { this._calls.sort((a, b) => b.endedAt - a.endedAt); }

  write(store, fn) {
    if (!this.db) return Promise.resolve();
    return new Promise((res) => {
      const tx = this.db.transaction(store, "readwrite");
      fn(tx.objectStore(store));
      tx.oncomplete = () => res(); tx.onerror = () => res(); tx.onabort = () => res();
    });
  }

  calls() { return this._calls; }
  call(id) { return this._calls.find((c) => c.id === id) ?? null; }
  points(id) { return this._points.filter((p) => p.callId === id).sort((a, b) => a.seq - b.seq); }
  allPoints() { return this._points; }
  labelRows() { return this._labels; }
  labelCount() { return this._labels.length; }

  save(row, points) {
    this._calls = [row, ...this._calls.filter((c) => c.id !== row.id)];
    this.sort();
    const pts = points.map((p) => ({ ...p, key: `${p.callId}#${p.seq}` }));
    this._points = [...this._points.filter((p) => p.callId !== row.id), ...pts];
    this.write("calls", (s) => s.put(row));
    this.write("points", (s) => pts.forEach((p) => s.put(p)));
  }

  /** Labels (and, with "Help improve detection" on, the caller's lines of that call). */
  recordLabels(callId, labels, callerLines, helpImprove) {
    const time = Date.now();
    const rows = labels.map((l, i) => ({ key: `${callId}#${l.source}#${time}#${i}`, callId, label: l.label, source: l.source, time,
      lines: helpImprove ? callerLines.map(([seq, text]) => ({ seq, text })) : null }));
    this._labels.push(...rows);
    this.write("labels", (s) => rows.forEach((r) => s.put(r)));
  }

  deleteHistory() {
    this._calls = []; this._points = []; this._labels = [];
    return Promise.all(["calls", "points", "labels"].map((st) => this.write(st, (s) => s.clear())));
  }
  forgetSentences() {
    this._calls = this._calls.map((c) => ({ ...c, riskiestSentence: null }));
    this.write("calls", (s) => this._calls.forEach((c) => s.put(c)));
  }
  forgetLabelSentences() {
    this._labels = this._labels.map((l) => ({ ...l, lines: null }));
    this.write("labels", (s) => this._labels.forEach((l) => s.put(l)));
  }
  insertSample(h) {
    h.labels.forEach((l, i) => { l.key = `${l.callId}#${l.source}#${i}`; });
    for (const c of h.calls) this.save(c, h.points.filter((p) => p.callId === c.id));
    this._labels.push(...h.labels);
    this.write("labels", (s) => h.labels.forEach((l) => s.put(l)));
  }
  deleteSample() {
    const isSample = (id) => id.startsWith("sample-");
    const gone = this._calls.filter((c) => isSample(c.id));
    this._calls = this._calls.filter((c) => !isSample(c.id));
    const pts = this._points.filter((p) => isSample(p.callId)), lbl = this._labels.filter((l) => isSample(l.callId));
    this._points = this._points.filter((p) => !isSample(p.callId));
    this._labels = this._labels.filter((l) => !isSample(l.callId));
    this.write("calls", (s) => gone.forEach((c) => s.delete(c.id)));
    this.write("points", (s) => pts.forEach((p) => s.delete(p.key)));
    this.write("labels", (s) => lbl.forEach((l) => s.delete(l.key)));
  }

  // ---- downloaded (federated) models
  async model(file) {
    if (!this.db) return null;
    return new Promise((res) => {
      const r = this.db.transaction("models").objectStore("models").get(file);
      r.onsuccess = () => res(r.result?.text ?? null); r.onerror = () => res(null);
    });
  }
  saveModel(file, text) { return this.write("models", (s) => s.put({ file, text })); }
  clearModels() { return this.write("models", (s) => s.clear()); }
}

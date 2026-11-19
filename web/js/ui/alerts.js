// Port of ios-app UI/Alerts.swift: the red warning's sound (three 880 Hz beeps, the same tone as both apps' AlertTone),
// a long vibration, and the warning read aloud (Web Speech API); the yellow "Be careful" is one short tap. Haptics use
// navigator.vibrate where the browser has it (Android browsers; iPhone browsers ignore it).
export const vibrate = (pattern) => { try { navigator.vibrate?.(pattern); } catch { /* not supported */ } };

export class Alerts {
  constructor() { this.ctx = null; this.nodes = []; }

  red(spoken) {
    vibrate([600]);
    this.beep();
    if (spoken) {
      // after the beeps (3 x 260 ms + 2 x 140 ms), as on the phones
      this.speakTimer = setTimeout(() => this.speak(spoken), 1100);
    }
  }
  yellow() { vibrate(40); }

  beep() {
    try {
      const AC = globalThis.AudioContext || globalThis.webkitAudioContext;
      if (!AC) return;
      this.ctx = this.ctx || new AC();
      const ctx = this.ctx;
      ctx.resume?.();
      const t0 = ctx.currentTime + 0.02;
      for (let b = 0; b < 3; b++) {
        const start = t0 + b * 0.4;
        const osc = ctx.createOscillator(), gain = ctx.createGain();
        osc.frequency.value = 880;
        gain.gain.setValueAtTime(0, start);
        gain.gain.linearRampToValueAtTime(0.35, start + 0.01);
        gain.gain.setValueAtTime(0.35, start + 0.25);
        gain.gain.linearRampToValueAtTime(0, start + 0.26);
        osc.connect(gain).connect(ctx.destination);
        osc.start(start); osc.stop(start + 0.27);
        this.nodes.push(osc);
      }
    } catch { /* no audio */ }
  }

  speak(text) {
    try {
      const u = new SpeechSynthesisUtterance(text);
      u.lang = "en-SG"; u.rate = 0.9;
      speechSynthesis.cancel();
      speechSynthesis.speak(u);
    } catch { /* no voice */ }
  }

  stop() {
    clearTimeout(this.speakTimer);
    try { speechSynthesis.cancel(); } catch { /* none */ }
    for (const n of this.nodes) { try { n.stop(); } catch { /* already stopped */ } }
    this.nodes = [];
  }
}

/** A light tap when a big button is released (iOS PressFeedbackStyle). */
export const tap = () => vibrate(8);

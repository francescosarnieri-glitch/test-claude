// GARGANTUA — optional synthesized soundtrack (WebAudio, no assets).
// A deep gravitational drone plus a filtered-noise "accretion wind" whose pitch
// and level track the camera's radial distance and the simulation clock, so the
// audio stays synchronized with what is on screen.

export class AudioEngine {
  constructor() {
    this.ctx = null;
    this.on = false;
  }

  _build() {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    this.ctx = ctx;

    this.master = ctx.createGain();
    this.master.gain.value = 0.0;
    const comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -18;
    comp.ratio.value = 6;
    this.master.connect(comp);
    comp.connect(ctx.destination);

    // Gravitational drone: two detuned low oscillators + a sub sine.
    this.droneGain = ctx.createGain();
    this.droneGain.gain.value = 0.16;
    const droneLp = ctx.createBiquadFilter();
    droneLp.type = 'lowpass';
    droneLp.frequency.value = 160;
    this.droneGain.connect(droneLp);
    droneLp.connect(this.master);

    this.oscA = ctx.createOscillator();
    this.oscA.type = 'sawtooth';
    this.oscA.frequency.value = 36;
    this.oscB = ctx.createOscillator();
    this.oscB.type = 'sawtooth';
    this.oscB.frequency.value = 36.6;
    this.sub = ctx.createOscillator();
    this.sub.type = 'sine';
    this.sub.frequency.value = 24;
    const subGain = ctx.createGain();
    subGain.gain.value = 0.9;
    this.oscA.connect(this.droneGain);
    this.oscB.connect(this.droneGain);
    this.sub.connect(subGain);
    subGain.connect(this.droneGain);

    // Accretion wind: looping noise through a swept band-pass.
    const len = ctx.sampleRate * 2;
    const buf = ctx.createBuffer(1, len, ctx.sampleRate);
    const data = buf.getChannelData(0);
    let seed = 1234567;
    for (let i = 0; i < len; i++) {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      data[i] = (seed / 4294967296) * 2 - 1;
    }
    this.noise = ctx.createBufferSource();
    this.noise.buffer = buf;
    this.noise.loop = true;
    this.bp = ctx.createBiquadFilter();
    this.bp.type = 'bandpass';
    this.bp.frequency.value = 220;
    this.bp.Q.value = 1.1;
    this.windGain = ctx.createGain();
    this.windGain.gain.value = 0.0;
    this.noise.connect(this.bp);
    this.bp.connect(this.windGain);
    this.windGain.connect(this.master);

    this.oscA.start();
    this.oscB.start();
    this.sub.start();
    this.noise.start();
  }

  async toggle() {
    if (!this.ctx) {
      try {
        this._build();
      } catch (e) {
        console.warn('Audio unavailable:', e);
        return false;
      }
    }
    this.on = !this.on;
    if (this.on && this.ctx.state === 'suspended') await this.ctx.resume();
    const t = this.ctx.currentTime;
    this.master.gain.cancelScheduledValues(t);
    this.master.gain.setTargetAtTime(this.on ? 0.7 : 0.0, t, 0.4);
    return this.on;
  }

  /** Drive modulation from render telemetry — keeps sound and image in sync. */
  update(simTime, camR, paused) {
    if (!this.ctx || !this.on) return;
    const t = this.ctx.currentTime;
    const proximity = Math.min(1, 3.5 / Math.max(camR - 1.2, 0.4)); // 0 far .. 1 at horizon
    this.windGain.gain.setTargetAtTime(paused ? 0.02 : 0.04 + 0.30 * proximity, t, 0.25);
    this.bp.frequency.setTargetAtTime(140 + 900 * proximity * proximity, t, 0.3);
    // Slow beating tied to the simulation clock (pauses when time pauses).
    const lfo = Math.sin(simTime * 0.35);
    this.oscB.frequency.setTargetAtTime(36.6 + 0.9 * lfo, t, 0.2);
    this.droneGain.gain.setTargetAtTime(0.13 + 0.10 * proximity, t, 0.3);
  }
}

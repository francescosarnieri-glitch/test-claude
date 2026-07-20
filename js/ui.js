// GARGANTUA — control panel, telemetry HUD, help overlay.
import { PARAM_DEFS, PRESETS, DEBUG_NAMES, CINEMATIC_NAMES, QUALITY_PROFILES } from './presets.js';

export class UI {
  /**
   * @param {object} app hooks: params, onParamChange(key), onPreset(i), onQuality(q),
   *                 onDebug(i), onCinematic(i), onAudioToggle(), onScreenshot(), onReset()
   */
  constructor(app) {
    this.app = app;
    this.inputs = new Map();
    this.panel = document.getElementById('panel');
    this.hud = document.getElementById('hud');
    this.help = document.getElementById('help');
    this.msg = document.getElementById('message');
    this.buildPanel();
  }

  buildPanel() {
    const app = this.app;
    const frag = document.createDocumentFragment();

    const title = document.createElement('div');
    title.className = 'panel-title';
    title.textContent = 'GARGANTUA';
    frag.appendChild(title);

    // Presets
    const presetRow = document.createElement('div');
    presetRow.className = 'row buttons';
    PRESETS.forEach((p, i) => {
      const b = document.createElement('button');
      b.textContent = p.name;
      b.dataset.preset = i;
      b.addEventListener('click', () => app.onPreset(i));
      presetRow.appendChild(b);
    });
    frag.appendChild(this.group('Presets', [presetRow]));

    // Selects
    const mkSelect = (labelText, options, handler, id) => {
      const row = document.createElement('div');
      row.className = 'row';
      const label = document.createElement('label');
      label.textContent = labelText;
      const sel = document.createElement('select');
      sel.id = id;
      options.forEach((name, i) => {
        const o = document.createElement('option');
        o.value = i;
        o.textContent = name;
        sel.appendChild(o);
      });
      sel.addEventListener('change', () => handler(sel.value));
      row.appendChild(label);
      row.appendChild(sel);
      return row;
    };

    const qualityKeys = Object.keys(QUALITY_PROFILES);
    const qRow = mkSelect('Quality', qualityKeys.map(k => QUALITY_PROFILES[k].label),
      v => app.onQuality(qualityKeys[+v]), 'sel-quality');
    const dRow = mkSelect('Debug view', DEBUG_NAMES.map((n, i) => `${i} — ${n}`),
      v => app.onDebug(+v), 'sel-debug');
    const cRow = mkSelect('Camera path', CINEMATIC_NAMES, v => app.onCinematic(+v), 'sel-cinematic');
    frag.appendChild(this.group('Mode', [qRow, dRow, cRow]));

    // Sliders, grouped
    const groups = new Map();
    for (const [group, key, label, min, max, step] of PARAM_DEFS) {
      if (!groups.has(group)) groups.set(group, []);
      groups.get(group).push(this.slider(key, label, min, max, step));
    }
    for (const [name, rows] of groups) frag.appendChild(this.group(name, rows));

    // Action buttons
    const actions = document.createElement('div');
    actions.className = 'row buttons';
    const mkBtn = (txt, fn, id) => {
      const b = document.createElement('button');
      b.textContent = txt;
      if (id) b.id = id;
      b.addEventListener('click', fn);
      actions.appendChild(b);
    };
    mkBtn('Audio: off', () => app.onAudioToggle(), 'btn-audio');
    mkBtn('Screenshot', () => app.onScreenshot());
    mkBtn('Reset', () => app.onReset());
    frag.appendChild(this.group('Actions', [actions]));

    this.panel.appendChild(frag);
  }

  group(name, children) {
    const det = document.createElement('details');
    det.open = name === 'Presets' || name === 'Mode';
    const sum = document.createElement('summary');
    sum.textContent = name;
    det.appendChild(sum);
    children.forEach(c => det.appendChild(c));
    return det;
  }

  slider(key, label, min, max, step) {
    const app = this.app;
    const row = document.createElement('div');
    row.className = 'row slider';
    const lab = document.createElement('label');
    lab.textContent = label;
    const val = document.createElement('span');
    val.className = 'val';
    const input = document.createElement('input');
    input.type = 'range';
    input.min = min;
    input.max = max;
    input.step = step;
    input.value = app.params[key];
    const show = () => { val.textContent = (+app.params[key]).toFixed(step >= 1 ? 0 : 2); };
    input.addEventListener('input', () => {
      app.params[key] = parseFloat(input.value);
      show();
      app.onParamChange(key);
    });
    show();
    this.inputs.set(key, { input, show });
    row.appendChild(lab);
    row.appendChild(val);
    row.appendChild(input);
    return row;
  }

  /** Push current app.params values into the widgets (after preset/reset/load). */
  syncFromParams() {
    for (const [key, w] of this.inputs) {
      w.input.value = this.app.params[key];
      w.show();
    }
  }

  syncSelectors({ quality, debug, cinematic, preset }) {
    const q = document.getElementById('sel-quality');
    if (q) q.value = Object.keys(QUALITY_PROFILES).indexOf(quality);
    const d = document.getElementById('sel-debug');
    if (d) d.value = debug;
    const c = document.getElementById('sel-cinematic');
    if (c) c.value = cinematic;
    this.panel.querySelectorAll('button[data-preset]').forEach(b => {
      b.classList.toggle('active', +b.dataset.preset === preset);
    });
  }

  setAudioLabel(on) {
    const b = document.getElementById('btn-audio');
    if (b) b.textContent = on ? 'Audio: on' : 'Audio: off';
  }

  updateHUD(t) {
    this.hud.textContent =
`FPS ${t.fps.toFixed(0)}  (${t.ms.toFixed(1)} ms)
render ${t.w}×${t.h}  @${t.scale.toFixed(2)}x  [${t.quality}]
steps ${t.steps}  dλ×${t.stepScale}
r = ${t.camR.toFixed(2)} rs   incl ${t.incl.toFixed(1)}°
t = ${t.simTime.toFixed(1)} s ${t.paused ? '(paused)' : ''}
view: ${DEBUG_NAMES[t.debug]}
path: ${CINEMATIC_NAMES[t.cinematic]}
preset: ${PRESETS[t.preset] ? PRESETS[t.preset].name : 'custom'}
audio: ${t.audio ? 'on' : 'off'}`;
  }

  toggleChrome() {
    const hidden = this.hud.classList.toggle('hidden');
    this.panel.classList.toggle('hidden', hidden);
  }

  showMessage(text, sticky = false) {
    this.msg.textContent = text;
    this.msg.classList.add('visible');
    clearTimeout(this._msgT);
    if (!sticky) this._msgT = setTimeout(() => this.msg.classList.remove('visible'), 2500);
  }

  hideMessage() { this.msg.classList.remove('visible'); }

  toggleHelp(force) {
    this.help.classList.toggle('hidden', force === undefined ? undefined : !force);
  }
}

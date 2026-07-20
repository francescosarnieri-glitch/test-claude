// GARGANTUA — Schwarzschild black hole raytracer.
// The scene is rendered entirely in a fragment shader (js/shaders.js) by
// integrating null geodesics; Three.js provides the GL plumbing, render
// targets for the HDR post chain, and OrbitControls.

import * as THREE from 'three';
import { OrbitControls } from '../vendor/OrbitControls.js';
import { FSQ_VERT, BLACKHOLE_FRAG, BRIGHT_FRAG, BLUR_FRAG, COMPOSITE_FRAG } from './shaders.js';
import { PARAM_DEFS, PRESETS, QUALITY_PROFILES, DEBUG_NAMES, CINEMATIC_NAMES } from './presets.js';
import { UI } from './ui.js';
import { AudioEngine } from './audio.js';

const STORAGE_KEY = 'gargantua-state-v1';
const BLOOM_LEVELS = 5;
const BLUR_RADII = [3, 5, 7, 9, 11];

const canvas = document.getElementById('view');
const overlay = document.getElementById('overlay');

// ---------------------------------------------------------------------------
// URL-driven deterministic screenshot mode
// ---------------------------------------------------------------------------
const query = new URLSearchParams(location.search);
const shotMode = query.get('shot') === '1';
const qNum = (k, d) => (query.has(k) ? parseFloat(query.get(k)) : d);

// ---------------------------------------------------------------------------
// App state
// ---------------------------------------------------------------------------
const app = {
  params: { ...PRESETS[0].params },
  quality: 'medium',
  debug: 0,
  cinematic: 0,
  preset: 0,
  paused: false,
  simTime: 0,
  audioOn: false,
};

let renderer, camera, controls, ortho;
let rtScene, rtBright, rtMip = [], rtTmp = [];
let matBH, matBright, matBlur, matComposite;
let sceneBH, sceneBright, sceneBlur, sceneComposite;
let ui, audio;
let renderW = 2, renderH = 2, renderScaleUsed = 1;
let fpsEMA = 60, msEMA = 16, lastFrameT = performance.now(), hudT = 0, saveT = 0;
let cinStart = 0, cinBase = { yaw: 0, pitch: 0, dist: 13 };
let contextLost = false;

function fail(err) {
  console.warn('GARGANTUA fatal:', err);
  overlay.textContent = 'GARGANTUA could not start.\n\n' + (err && err.message ? err.message : String(err)) +
    '\n\nA WebGL2-capable browser is required.';
  overlay.classList.add('visible');
}

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------
function saveState() {
  if (shotMode) return;
  try {
    const { yaw, pitch, dist } = cameraSpherical();
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      params: app.params, quality: app.quality, debug: app.debug,
      preset: app.preset, camera: { yaw, pitch, dist },
    }));
  } catch (e) { /* storage unavailable — run stateless */ }
}

function loadState() {
  if (shotMode || query.get('fresh') === '1') return null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const s = JSON.parse(raw);
    if (!s || typeof s !== 'object' || !s.params) return null;
    for (const [, key] of PARAM_DEFS.map(d => [d[0], d[1]])) {
      if (typeof s.params[key] !== 'number' || !isFinite(s.params[key])) return null;
    }
    return s;
  } catch (e) { return null; }
}

// ---------------------------------------------------------------------------
// Camera helpers
// ---------------------------------------------------------------------------
function cameraSpherical() {
  const p = camera.position;
  const dist = p.length();
  const pitch = Math.asin(THREE.MathUtils.clamp(p.y / Math.max(dist, 1e-6), -1, 1));
  const yaw = Math.atan2(p.z, p.x);
  return { yaw, pitch, dist };
}

function setCameraSpherical(yaw, pitch, dist) {
  dist = THREE.MathUtils.clamp(dist, 2.05, 34);
  camera.position.set(
    dist * Math.cos(pitch) * Math.cos(yaw),
    dist * Math.sin(pitch),
    dist * Math.cos(pitch) * Math.sin(yaw));
  camera.lookAt(0, 0, 0);
}

// ---------------------------------------------------------------------------
// Init
// ---------------------------------------------------------------------------
function init() {
  renderer = new THREE.WebGLRenderer({
    canvas,
    antialias: false,
    depth: false,
    stencil: false,
    powerPreference: 'high-performance',
    preserveDrawingBuffer: shotMode,
  });
  renderer.setPixelRatio(1);
  renderer.autoClear = false;
  // Custom ShaderMaterials skip three's colorspace chunk; the composite pass
  // outputs sRGB itself, so keep the renderer's own conversion a no-op.
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  renderer.toneMapping = THREE.NoToneMapping;     // manual ACES in the composite pass

  camera = new THREE.PerspectiveCamera(app.params.fov, 1, 0.1, 100);
  ortho = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);

  const mkPass = (frag, uniforms, defines) => {
    const mat = new THREE.ShaderMaterial({
      vertexShader: FSQ_VERT, fragmentShader: frag, uniforms, defines: defines || {},
      depthTest: false, depthWrite: false,
    });
    const scene = new THREE.Scene();
    scene.add(new THREE.Mesh(new THREE.PlaneGeometry(2, 2), mat));
    return { mat, scene };
  };

  const bh = mkPass(BLACKHOLE_FRAG, {
    uResolution: { value: new THREE.Vector2(2, 2) },
    uTime: { value: 0 },
    uCamPos: { value: new THREE.Vector3() },
    uCamBasis: { value: new THREE.Matrix3() },
    uTanHalfFov: { value: 0.5 },
    uPixAngle: { value: 0.001 },
    uStepScale: { value: 0.2 },
    uSteps: { value: 256 },
    uDebug: { value: 0 },
    uDiskInner: { value: 3 }, uDiskOuter: { value: 14 },
    uDiskTemp: { value: 5600 }, uDiskDensity: { value: 0.85 },
    uTurbAmount: { value: 0.55 }, uTurbScale: { value: 1.6 }, uTurbSpeed: { value: 1.0 },
    uBeaming: { value: 1.6 }, uRedshift: { value: 0.45 },
    uStarDensity: { value: 1 }, uStarBright: { value: 1 }, uMilkyWay: { value: 0.9 },
  }, { MAXSTEPS: 512 });
  matBH = bh.mat; sceneBH = bh.scene;

  const br = mkPass(BRIGHT_FRAG, {
    tInput: { value: null }, uThreshold: { value: 0.85 }, uKnee: { value: 0.35 },
  });
  matBright = br.mat; sceneBright = br.scene;

  const bl = mkPass(BLUR_FRAG, {
    tInput: { value: null }, uDir: { value: new THREE.Vector2(1, 0) },
    uTexel: { value: new THREE.Vector2() }, uSigma: { value: 2 }, uRadius: { value: 5 },
  });
  matBlur = bl.mat; sceneBlur = bl.scene;

  const cp = mkPass(COMPOSITE_FRAG, {
    tScene: { value: null },
    tB0: { value: null }, tB1: { value: null }, tB2: { value: null },
    tB3: { value: null }, tB4: { value: null },
    uBloomStrength: { value: 0.9 }, uBloomRadius: { value: 0.55 },
    uExposure: { value: 1.15 }, uVignette: { value: 0.42 },
    uGrain: { value: 0.05 }, uGrainSeed: { value: 0 },
    uCA: { value: 0.8 }, uResolution: { value: new THREE.Vector2(2, 2) },
    uBypass: { value: 0 },
  });
  matComposite = cp.mat; sceneComposite = cp.scene;

  controls = new OrbitControls(camera, canvas);
  controls.enableDamping = true;
  controls.dampingFactor = 0.06;
  controls.enablePan = false;
  controls.minDistance = 2.05;
  controls.maxDistance = 34;
  controls.rotateSpeed = 0.6;
  controls.addEventListener('start', () => { if (app.cinematic !== 0) setCinematic(0); });
  controls.addEventListener('end', () => { saveT = 0.35; });

  // Graceful context recovery: Three re-uploads programs/targets on restore.
  canvas.addEventListener('webglcontextlost', e => {
    e.preventDefault();
    contextLost = true;
    overlay.textContent = 'WebGL context lost — recovering…';
    overlay.classList.add('visible');
  });
  canvas.addEventListener('webglcontextrestored', () => {
    contextLost = false;
    resize(true);
    overlay.classList.remove('visible');
  });

  audio = new AudioEngine();
  ui = new UI({
    params: app.params,
    onParamChange: key => { applyParams(); saveT = 0.35; if (key === 'fov') resizeDependent(); },
    onPreset: applyPreset,
    onQuality: setQuality,
    onDebug: setDebug,
    onCinematic: setCinematic,
    onAudioToggle: toggleAudio,
    onScreenshot: () => downloadScreenshot(),
    onReset: resetAll,
  });

  // Restore persisted state (live mode only).
  const saved = loadState();
  if (saved) {
    Object.assign(app.params, saved.params);
    app.quality = QUALITY_PROFILES[saved.quality] ? saved.quality : 'medium';
    app.debug = Number.isInteger(saved.debug) && saved.debug >= 0 && saved.debug <= 9 ? saved.debug : 0;
    app.preset = Number.isInteger(saved.preset) ? saved.preset : 0;
    const c = saved.camera || PRESETS[0].camera;
    setCameraSpherical(c.yaw, c.pitch, c.dist);
  } else {
    const c = PRESETS[0].camera;
    setCameraSpherical(c.yaw, c.pitch, c.dist);
  }

  // Screenshot-mode overrides (deterministic: fixed time, seed, camera, size).
  if (shotMode) {
    const presetIdx = THREE.MathUtils.clamp(Math.round(qNum('preset', 0)), 0, PRESETS.length - 1);
    app.preset = presetIdx;
    app.params = { ...PRESETS[presetIdx].params };
    ui.app.params = app.params;
    for (const [, key] of PARAM_DEFS.map(d => [d[0], d[1]])) {
      if (query.has(key)) app.params[key] = parseFloat(query.get(key));
    }
    app.simTime = qNum('t', 12.0);
    app.debug = THREE.MathUtils.clamp(Math.round(qNum('debug', 0)), 0, 9);
    const qq = query.get('quality');
    if (QUALITY_PROFILES[qq]) app.quality = qq;
    const base = PRESETS[presetIdx].camera;
    setCameraSpherical(qNum('yaw', base.yaw), qNum('pitch', base.pitch), qNum('dist', base.dist));
    controls.enabled = false;
    app.paused = true;
    document.body.classList.add('shot');
  }

  controls.target.set(0, 0, 0);
  controls.update();

  window.addEventListener('resize', () => resize());
  window.addEventListener('keydown', onKey);
  window.addEventListener('beforeunload', saveState);

  ui.syncFromParams();
  ui.syncSelectors(app);
  applyParams();
  resize(true);

  if (!shotMode && !localStorage.getItem('gargantua-help-seen')) {
    ui.toggleHelp(true);
    try { localStorage.setItem('gargantua-help-seen', '1'); } catch (e) { /* ignore */ }
  }

  if (shotMode) {
    renderShot();
  } else {
    requestAnimationFrame(loop);
  }
}

// ---------------------------------------------------------------------------
// Sizing / quality
// ---------------------------------------------------------------------------
function resize(force = false) {
  const prof = QUALITY_PROFILES[app.quality];
  let w, h;
  if (shotMode) {
    w = Math.round(qNum('w', 1280));
    h = Math.round(qNum('h', 720));
    renderScaleUsed = 1;
  } else {
    const dpr = Math.min(window.devicePixelRatio || 1, prof.maxDpr);
    renderScaleUsed = dpr * prof.renderScale;
    w = Math.max(2, Math.round(canvas.clientWidth * renderScaleUsed));
    h = Math.max(2, Math.round(canvas.clientHeight * renderScaleUsed));
  }
  if (!force && w === renderW && h === renderH) return;
  renderW = w; renderH = h;

  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();

  const mk = (mw, mh) => new THREE.WebGLRenderTarget(mw, mh, {
    type: THREE.HalfFloatType,
    minFilter: THREE.LinearFilter,
    magFilter: THREE.LinearFilter,
    depthBuffer: false,
    stencilBuffer: false,
  });
  if (rtScene) rtScene.dispose();
  if (rtBright) rtBright.dispose();
  rtMip.forEach(rt => rt.dispose());
  rtTmp.forEach(rt => rt.dispose());
  rtMip = []; rtTmp = [];

  rtScene = mk(w, h);
  const hw = Math.max(2, w >> 1), hh = Math.max(2, h >> 1);
  rtBright = mk(hw, hh);
  for (let i = 0; i < BLOOM_LEVELS; i++) {
    const mw = Math.max(2, w >> (i + 1)), mh = Math.max(2, h >> (i + 1));
    rtMip.push(mk(mw, mh));
    rtTmp.push(mk(mw, mh));
  }

  matBH.uniforms.uResolution.value.set(w, h);
  matComposite.uniforms.uResolution.value.set(w, h);
  matComposite.uniforms.tScene.value = rtScene.texture;
  for (let i = 0; i < BLOOM_LEVELS; i++) matComposite.uniforms['tB' + i].value = rtMip[i].texture;
  resizeDependent();
}

function resizeDependent() {
  const tan = Math.tan(THREE.MathUtils.degToRad(app.params.fov) * 0.5);
  matBH.uniforms.uTanHalfFov.value = tan;
  matBH.uniforms.uPixAngle.value = (2 * tan) / renderH;
}

function setQuality(q) {
  app.quality = q;
  ui.syncSelectors(app);
  applyParams();
  resize(true);
  ui.showMessage(`Quality: ${QUALITY_PROFILES[q].label}`);
  saveT = 0.35;
}

// ---------------------------------------------------------------------------
// Param plumbing
// ---------------------------------------------------------------------------
function applyParams() {
  const p = app.params;
  const prof = QUALITY_PROFILES[app.quality];
  camera.fov = p.fov;
  camera.updateProjectionMatrix();

  const u = matBH.uniforms;
  u.uSteps.value = prof.steps;
  u.uStepScale.value = prof.stepScale;
  u.uDebug.value = app.debug;
  u.uDiskInner.value = p.diskInner;
  u.uDiskOuter.value = Math.max(p.diskOuter, p.diskInner + 1.5);
  u.uDiskTemp.value = p.diskTemp;
  u.uDiskDensity.value = p.diskDensity;
  u.uTurbAmount.value = p.turbAmount;
  u.uTurbScale.value = p.turbScale;
  u.uTurbSpeed.value = p.turbSpeed;
  u.uBeaming.value = p.beaming;
  u.uRedshift.value = p.redshift;
  u.uStarDensity.value = p.starDensity;
  u.uStarBright.value = p.starBright;
  u.uMilkyWay.value = p.milkyWay;

  matBright.uniforms.uThreshold.value = p.bloomThreshold;
  const c = matComposite.uniforms;
  c.uBloomStrength.value = p.bloomStrength;
  c.uBloomRadius.value = p.bloomRadius;
  c.uExposure.value = p.exposure;
  c.uVignette.value = p.vignette;
  c.uGrain.value = p.grain;
  c.uCA.value = p.chromAb;
  resizeDependent();
}

function applyPreset(i) {
  app.preset = i;
  app.params = { ...PRESETS[i].params };
  ui.app.params = app.params;
  const c = PRESETS[i].camera;
  setCameraSpherical(c.yaw, c.pitch, c.dist);
  controls.update();
  ui.syncFromParams();
  ui.syncSelectors(app);
  applyParams();
  ui.showMessage(`Preset: ${PRESETS[i].name}`);
  saveT = 0.35;
}

function setDebug(i) {
  app.debug = i;
  matBH.uniforms.uDebug.value = i;
  ui.syncSelectors(app);
  ui.showMessage(`View ${i}: ${DEBUG_NAMES[i]}`);
  saveT = 0.35;
}

function setCinematic(i) {
  app.cinematic = i;
  controls.enabled = i === 0;
  if (i !== 0) {
    cinStart = app.simTime;
    cinBase = cameraSpherical();
  }
  ui.syncSelectors(app);
  ui.showMessage(`Camera: ${CINEMATIC_NAMES[i]}`);
}

async function toggleAudio() {
  app.audioOn = await audio.toggle();
  ui.setAudioLabel(app.audioOn);
}

function resetAll() {
  try { localStorage.removeItem(STORAGE_KEY); } catch (e) { /* ignore */ }
  app.quality = 'medium';
  app.debug = 0;
  setCinematic(0);
  applyPreset(0);
  app.simTime = 0;
  app.paused = false;
  ui.syncSelectors(app);
  ui.showMessage('Reset to defaults');
}

// ---------------------------------------------------------------------------
// Cinematic camera paths
// ---------------------------------------------------------------------------
function updateCinematic() {
  const t = app.simTime - cinStart;
  const b = cinBase;
  if (app.cinematic === 1) {            // slow orbit with a gentle breathing dolly
    const yaw = b.yaw + t * 0.06;
    const pitch = b.pitch + 0.05 * Math.sin(t * 0.11);
    const dist = b.dist + 1.2 * Math.sin(t * 0.045);
    setCameraSpherical(yaw, pitch, dist);
  } else if (app.cinematic === 2) {     // the dive: spiral toward the photon sphere and back
    const period = 55;
    const ph = (t % period) / period;
    const depth = 0.5 - 0.5 * Math.cos(ph * Math.PI * 2);
    const dist = THREE.MathUtils.lerp(b.dist, 2.35, depth);
    const yaw = b.yaw + t * (0.05 + 0.22 * depth);
    const pitch = THREE.MathUtils.lerp(b.pitch, 0.03, depth);
    setCameraSpherical(yaw, pitch, dist);
  } else if (app.cinematic === 3) {     // flyby: hyperbolic-style pass over the disk
    const period = 40;
    const ph = ((t % period) / period) * 2 - 1;   // -1 .. 1
    const x = ph * 30;
    const y = 2.4 + 3.4 * ph * ph;
    const z = 7.5 + 6.0 * ph * ph;
    camera.position.set(
      x * Math.cos(b.yaw) - z * Math.sin(b.yaw),
      y,
      x * Math.sin(b.yaw) + z * Math.cos(b.yaw));
    camera.lookAt(0, 0, 0);
  }
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------
function renderFrame(grainSeed) {
  camera.updateMatrixWorld();
  const u = matBH.uniforms;
  u.uTime.value = app.simTime;
  u.uCamPos.value.copy(camera.position);
  u.uCamBasis.value.setFromMatrix4(camera.matrixWorld);

  renderer.setRenderTarget(rtScene);
  renderer.clear();
  renderer.render(sceneBH, ortho);

  const bypass = app.debug >= 3;
  matComposite.uniforms.uBypass.value = bypass ? 1 : 0;

  if (!bypass) {
    matBright.uniforms.tInput.value = rtScene.texture;
    renderer.setRenderTarget(rtBright);
    renderer.clear();
    renderer.render(sceneBright, ortho);

    let input = rtBright;
    for (let i = 0; i < BLOOM_LEVELS; i++) {
      const radius = BLUR_RADII[i];
      const bu = matBlur.uniforms;
      bu.uSigma.value = radius * 0.55;
      bu.uRadius.value = radius;

      bu.tInput.value = input.texture;
      bu.uDir.value.set(1, 0);
      bu.uTexel.value.set(1 / input.width, 1 / input.height);
      renderer.setRenderTarget(rtTmp[i]);
      renderer.clear();
      renderer.render(sceneBlur, ortho);

      bu.tInput.value = rtTmp[i].texture;
      bu.uDir.value.set(0, 1);
      bu.uTexel.value.set(1 / rtTmp[i].width, 1 / rtTmp[i].height);
      renderer.setRenderTarget(rtMip[i]);
      renderer.clear();
      renderer.render(sceneBlur, ortho);
      input = rtMip[i];
    }
  }

  matComposite.uniforms.uGrainSeed.value = grainSeed;
  renderer.setRenderTarget(null);
  renderer.clear();
  renderer.render(sceneComposite, ortho);
}

function loop(now) {
  requestAnimationFrame(loop);
  if (contextLost) return;

  const dt = Math.min((now - lastFrameT) / 1000, 0.1);
  lastFrameT = now;
  msEMA = msEMA * 0.92 + (dt * 1000) * 0.08;
  fpsEMA = fpsEMA * 0.92 + (dt > 0 ? 1 / dt : 60) * 0.08;

  if (!app.paused) app.simTime += dt * app.params.timeScale;
  if (app.cinematic !== 0) updateCinematic(); else controls.update();

  audio.update(app.simTime, camera.position.length(), app.paused);

  renderFrame((now * 0.061) % 977);

  if (saveT > 0) { saveT -= dt; if (saveT <= 0) saveState(); }
  hudT -= dt;
  if (hudT <= 0) {
    hudT = 0.25;
    const sph = cameraSpherical();
    const prof = QUALITY_PROFILES[app.quality];
    ui.updateHUD({
      fps: fpsEMA, ms: msEMA, w: renderW, h: renderH, scale: renderScaleUsed,
      quality: prof.label, steps: prof.steps, stepScale: prof.stepScale,
      camR: sph.dist, incl: THREE.MathUtils.radToDeg(sph.pitch),
      simTime: app.simTime, paused: app.paused, debug: app.debug,
      cinematic: app.cinematic, preset: app.preset, audio: app.audioOn,
    });
  }
}

function renderShot() {
  // Deterministic single frame: fixed simTime, fixed camera, seeded grain.
  const seed = qNum('seed', 42);
  renderFrame(seed);
  window.__SHOT_DONE = true;
  document.title = 'GARGANTUA [shot ready]';
  if (query.get('download') === '1') downloadScreenshot(false);
}

function downloadScreenshot(rerender = true) {
  if (rerender) renderFrame(matComposite.uniforms.uGrainSeed.value);
  // toBlob straight after the synchronous render keeps the buffer valid.
  canvas.toBlob(blob => {
    if (!blob) return;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `gargantua-${Date.now()}.png`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }, 'image/png');
  ui.showMessage('Screenshot saved');
}

// ---------------------------------------------------------------------------
// Hotkeys
// ---------------------------------------------------------------------------
function onKey(e) {
  if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT')) return;
  const k = e.key;
  if (k >= '0' && k <= '9') { setDebug(parseInt(k, 10)); return; }
  switch (k.toLowerCase()) {
    case ' ': e.preventDefault(); app.paused = !app.paused;
      ui.showMessage(app.paused ? 'Time paused' : 'Time running'); break;
    case 'p': applyPreset((app.preset + 1) % PRESETS.length); break;
    case 'c': setCinematic((app.cinematic + 1) % CINEMATIC_NAMES.length); break;
    case 'q': {
      const keys = Object.keys(QUALITY_PROFILES);
      setQuality(keys[(keys.indexOf(app.quality) + 1) % keys.length]);
      break;
    }
    case 'h': ui.toggleChrome(); break;
    case 'm': toggleAudio(); break;
    case 's': downloadScreenshot(); break;
    case 'r': resetAll(); break;
    case 'f':
      if (document.fullscreenElement) document.exitFullscreen();
      else document.documentElement.requestFullscreen().catch(() => {});
      break;
    case 'k': case '?': ui.toggleHelp(); break;
    case 'escape': ui.toggleHelp(false); break;
  }
}

// ---------------------------------------------------------------------------
try {
  init();
  window.__GARGANTUA = { app, get renderer() { return renderer; }, renderFrame };
} catch (err) {
  fail(err);
}

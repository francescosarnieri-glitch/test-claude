// ============================================================================
//  SNAKE 3D — Dal Bosco al Lago
//  Gioco completo in Three.js ottimizzato per touch screen Android.
//  Joypad virtuale + swipe, 6+ livelli a tema, 9 varietà di cibo/power-up,
//  combo, vite, record salvati in locale, audio procedurale (WebAudio).
// ============================================================================
import * as THREE from 'three';

// ----------------------------------------------------------------------------
// Costanti e salvataggi
// ----------------------------------------------------------------------------
const GRID = 23;                 // celle per lato
const HALF = (GRID - 1) / 2;
const BASE_STEP = 0.24;          // secondi per passo al livello 1
const COMBO_WINDOW = 3.5;        // secondi per mantenere la combo
const MAX_COMBO = 5;

const store = {
  get best()      { return +(localStorage.getItem('snake3d_best') || 0); },
  set best(v)     { localStorage.setItem('snake3d_best', v); },
  get unlocked()  { return +(localStorage.getItem('snake3d_unlocked') || 0); },
  set unlocked(v) { localStorage.setItem('snake3d_unlocked', v); },
  get settings()  { try { return JSON.parse(localStorage.getItem('snake3d_set')) || {}; } catch { return {}; } },
  set settings(v) { localStorage.setItem('snake3d_set', JSON.stringify(v)); },
};
const settings = Object.assign({ sound: true, joystick: true, swipe: true, camera: 'top' }, store.settings);
const saveSettings = () => { store.settings = settings; };

// ----------------------------------------------------------------------------
// Audio procedurale (nessun file: tutto sintetizzato)
// ----------------------------------------------------------------------------
const AudioFX = (() => {
  let ctx = null, musicTimer = null, musicOn = false;
  const ac = () => {
    if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === 'suspended') ctx.resume();
    return ctx;
  };
  function tone(freq, dur, type = 'sine', vol = 0.18, when = 0, glide = 0) {
    if (!settings.sound) return;
    const a = ac(), t = a.currentTime + when;
    const o = a.createOscillator(), g = a.createGain();
    o.type = type; o.frequency.setValueAtTime(freq, t);
    if (glide) o.frequency.exponentialRampToValueAtTime(Math.max(30, freq + glide), t + dur);
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(vol, t + 0.012);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.connect(g).connect(a.destination);
    o.start(t); o.stop(t + dur + 0.05);
  }
  const PENTA = [261.6, 293.7, 329.6, 392.0, 440.0, 523.3, 587.3, 659.3];
  function musicTick() {
    if (!settings.sound || !musicOn) return;
    if (Math.random() < 0.75) tone(PENTA[Math.floor(Math.random() * PENTA.length)], 0.55, 'triangle', 0.035);
    if (Math.random() < 0.30) tone(PENTA[Math.floor(Math.random() * 4)] / 2, 0.9, 'sine', 0.045);
  }
  return {
    unlock() { ac(); },
    eat(combo)   { tone(420 + combo * 90, 0.12, 'square', 0.10); tone(640 + combo * 90, 0.10, 'square', 0.07, 0.06); },
    power()      { [523, 659, 784, 1047].forEach((f, i) => tone(f, 0.14, 'triangle', 0.12, i * 0.07)); },
    bad()        { tone(220, 0.25, 'sawtooth', 0.10, 0, -120); },
    death()      { tone(300, 0.5, 'sawtooth', 0.16, 0, -240); tone(150, 0.7, 'sawtooth', 0.14, 0.15, -110); },
    levelup()    { [392, 523, 659, 784, 1047].forEach((f, i) => tone(f, 0.2, 'triangle', 0.14, i * 0.1)); },
    click()      { tone(700, 0.05, 'square', 0.06); },
    tick()       { tone(1200, 0.03, 'square', 0.03); },
    startMusic() { musicOn = true; if (!musicTimer) musicTimer = setInterval(musicTick, 500); },
    stopMusic()  { musicOn = false; },
  };
})();

// ----------------------------------------------------------------------------
// Definizione LIVELLI — dal bosco fitto fino al grande lago al tramonto
// ----------------------------------------------------------------------------
const LEVELS = [
  { name: 'Bosco Fitto',       desc: 'Alberi ovunque: serpeggia tra i tronchi!',
    sky: 0x87c5eb, fog: 0xa8d8b0, fogNear: 26, fogFar: 60, ground: ['#3f7d2f', '#468a35', '#356b27'],
    sun: 0xfff3d6, sunInt: 2.6, amb: 0x9ec9ff, ambInt: 0.9,
    trees: 26, rocks: 4, flowers: 8, water: 'none', speed: 1.0, target: 150, food: 3,
    weights: { mela: 5, mirtillo: 2, fungo: 1.2, oro: .5, ciliegia: .8, stella: .35, cristallo: .4, fiore: .5 },
    color: '#2e7d32', ambientFx: 'leaves' },
  { name: 'Radura Fiorita',    desc: 'Prati, fiori e farfalle. Occhio ai massi!',
    sky: 0x9fd8ff, fog: 0xcfe8b8, fogNear: 30, fogFar: 70, ground: ['#5da13f', '#6fb14b', '#4f9038'],
    sun: 0xfff8e0, sunInt: 3.0, amb: 0xbfe3ff, ambInt: 1.0,
    trees: 10, rocks: 10, flowers: 40, water: 'none', speed: 1.12, target: 200, food: 3,
    weights: { mela: 5, mirtillo: 3, fungo: 1.4, oro: .7, ciliegia: .8, stella: .4, cristallo: .5, fiore: .6 },
    color: '#7cb342', ambientFx: 'butterflies' },
  { name: 'Sentiero Roccioso', desc: 'Rocce taglienti tra i pini. Più veloce!',
    sky: 0x8fb4d9, fog: 0xb0bfae, fogNear: 24, fogFar: 58, ground: ['#6d7f5a', '#7c8a63', '#5d6f4e'],
    sun: 0xffedc9, sunInt: 2.4, amb: 0xaebfd9, ambInt: 0.85,
    trees: 14, rocks: 20, flowers: 4, water: 'none', speed: 1.25, target: 260, food: 4,
    weights: { mela: 5, mirtillo: 3, fungo: 1.6, oro: .8, ciliegia: 1, stella: .5, cristallo: .6, fiore: .7 },
    color: '#78909c', ambientFx: 'leaves' },
  { name: 'Stagno delle Ninfee', desc: "Prime acque! Non finirci dentro…",
    sky: 0x8fd0e8, fog: 0xa9d6c8, fogNear: 26, fogFar: 62, ground: ['#4b9b58', '#57a862', '#3f8a4c'],
    sun: 0xfff3d6, sunInt: 2.7, amb: 0xa8d8e8, ambInt: 0.95,
    trees: 10, rocks: 6, flowers: 14, water: 'ponds', speed: 1.32, target: 320, food: 4,
    weights: { mela: 4, mirtillo: 3, fungo: 1.6, oro: .9, ciliegia: 1, stella: .6, cristallo: .7, fiore: .8, pesce: 1.5 },
    color: '#26a69a', ambientFx: 'butterflies' },
  { name: 'Grande Lago',       desc: 'Isole e acque profonde. Pesci deliziosi!',
    sky: 0x7ec8ef, fog: 0x9fd2e0, fogNear: 28, fogFar: 66, ground: ['#c9b77a', '#d6c489', '#b8a76c'],
    sun: 0xfff8e8, sunInt: 3.1, amb: 0xa8d8ff, ambInt: 1.0,
    trees: 7, rocks: 7, flowers: 6, water: 'lake', speed: 1.42, target: 400, food: 5,
    weights: { mela: 3.5, mirtillo: 3, fungo: 1.8, oro: 1.1, ciliegia: 1, stella: .7, cristallo: .8, fiore: .9, pesce: 2.5 },
    color: '#29b6f6', ambientFx: 'butterflies' },
  { name: 'Lago al Tramonto',  desc: 'Lucciole sul lago dorato. Velocità massima!',
    sky: 0xff9e66, fog: 0xe8a070, fogNear: 22, fogFar: 55, ground: ['#7a6b4a', '#8a7a55', '#6a5c40'],
    sun: 0xffb060, sunInt: 2.6, amb: 0x8060a0, ambInt: 0.8,
    trees: 9, rocks: 6, flowers: 4, water: 'lake', speed: 1.55, target: 500, food: 5,
    weights: { mela: 3, mirtillo: 3, fungo: 2, oro: 1.3, ciliegia: 1.2, stella: .8, cristallo: 1, fiore: 1, pesce: 3 },
    color: '#ff7043', ambientFx: 'fireflies' },
];
// dopo l'ultimo livello si continua all'infinito, sempre più veloce
function levelConfig(i) {
  const base = i < LEVELS.length ? LEVELS[i] : LEVELS[LEVELS.length - 1 - (i % 2)];
  if (i < LEVELS.length) return { ...base, idx: i, label: `Livello ${i + 1}` };
  const loop = i - LEVELS.length + 1;
  return { ...base, idx: i, label: `Livello ${i + 1} ∞`,
    name: base.name + ' ∞', speed: base.speed + loop * 0.08,
    target: Math.round(base.target * (1 + loop * 0.35)) };
}

// ----------------------------------------------------------------------------
// Definizione CIBI e power-up
// ----------------------------------------------------------------------------
const FOODS = {
  mela:      { pts: 10, grow: 1, label: 'Mela',            icon: '🍎', desc: '+10 punti, cresci di 1' },
  mirtillo:  { pts: 20, grow: 1, label: 'Mirtilli',        icon: '🫐', desc: '+20 punti, cresci di 1' },
  fungo:     { pts: 15, grow: 1, label: 'Fungo Scattante', icon: '🍄', desc: '+15, super velocità 6s' },
  oro:       { pts: 50, grow: 2, label: "Bacca d'Oro",     icon: '✨', desc: '+50! Sparisce in fretta', ttl: 7 },
  ciliegia:  { pts: 5,  grow: -3, label: 'Ciliegie Magiche', icon: '🍒', desc: '+5 e ti ACCORCIA di 3' },
  stella:    { pts: 30, grow: 1, label: 'Stella Boschiva', icon: '⭐', desc: 'Invincibile 8s: attraversi tutto!', ttl: 9 },
  cristallo: { pts: 25, grow: 1, label: 'Cristallo Magnete', icon: '💎', desc: 'Attira il cibo per 8s' },
  fiore:     { pts: 15, grow: 1, label: 'Fiore del Tempo', icon: '🌸', desc: 'Rallenta il tempo 6s' },
  pesce:     { pts: 40, grow: 2, label: 'Pesce di Lago',   icon: '🐟', desc: '+40, solo vicino alle acque' },
};

// ----------------------------------------------------------------------------
// Setup Three.js
// ----------------------------------------------------------------------------
const canvas = document.getElementById('c');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.outputColorSpace = THREE.SRGBColorSpace;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(55, 1, 0.1, 200);
scene.add(camera);

const sunLight = new THREE.DirectionalLight(0xffffff, 2.5);
sunLight.castShadow = true;
sunLight.shadow.mapSize.set(1024, 1024);
sunLight.shadow.camera.left = -HALF - 3; sunLight.shadow.camera.right = HALF + 3;
sunLight.shadow.camera.top = HALF + 3;   sunLight.shadow.camera.bottom = -HALF - 3;
sunLight.shadow.camera.far = 80;
sunLight.position.set(14, 22, 8);
sunLight.target.position.set(0, 0, 0);
scene.add(sunLight, sunLight.target);
const ambLight = new THREE.AmbientLight(0xffffff, 0.9);
scene.add(ambLight);
const fillLight = new THREE.HemisphereLight(0xbfd9ff, 0x3a5a2a, 0.7);
scene.add(fillLight);

const levelGroup = new THREE.Group();   // scenografia del livello
const foodGroup  = new THREE.Group();   // cibi
const snakeGroup = new THREE.Group();   // serpente
const fxGroup    = new THREE.Group();   // particelle & sprite
scene.add(levelGroup, foodGroup, snakeGroup, fxGroup);

function resize() {
  const w = innerWidth, h = innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
addEventListener('resize', resize); resize();

// ----------------------------------------------------------------------------
// Utility geometrie/materiali condivisi
// ----------------------------------------------------------------------------
const G = {
  sphere: new THREE.SphereGeometry(1, 18, 14),
  sphereLow: new THREE.SphereGeometry(1, 10, 8),
  box: new THREE.BoxGeometry(1, 1, 1),
  cyl: new THREE.CylinderGeometry(1, 1, 1, 8),
  cone: new THREE.ConeGeometry(1, 1, 8),
  ico: new THREE.IcosahedronGeometry(1, 0),
  octa: new THREE.OctahedronGeometry(1, 0),
  dodeca: new THREE.DodecahedronGeometry(1, 0),
  circle: new THREE.CircleGeometry(1, 16),
};
const mat = (color, opts = {}) => new THREE.MeshStandardMaterial({ color, roughness: 0.85, metalness: 0.05, ...opts });
const gx2w = g => g - HALF;           // grid → world
const key = (x, z) => x + ',' + z;

function groundTexture(colors) {
  const cv = document.createElement('canvas'); cv.width = cv.height = 512;
  const c = cv.getContext('2d');
  c.fillStyle = colors[0]; c.fillRect(0, 0, 512, 512);
  for (let i = 0; i < 260; i++) {
    c.fillStyle = colors[1 + (i % (colors.length - 1))];
    c.globalAlpha = 0.25 + Math.random() * 0.4;
    const r = 8 + Math.random() * 42;
    c.beginPath(); c.arc(Math.random() * 512, Math.random() * 512, r, 0, 7); c.fill();
  }
  c.globalAlpha = 0.5;
  for (let i = 0; i < 900; i++) {
    c.fillStyle = Math.random() < .5 ? 'rgba(255,255,255,.10)' : 'rgba(0,0,0,.12)';
    c.fillRect(Math.random() * 512, Math.random() * 512, 2.2, 2.2);
  }
  const t = new THREE.CanvasTexture(cv);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function textSprite(text, color = '#ffd54a', size = 46) {
  const cv = document.createElement('canvas'); cv.width = 256; cv.height = 96;
  const c = cv.getContext('2d');
  c.font = `900 ${size}px sans-serif`; c.textAlign = 'center'; c.textBaseline = 'middle';
  c.lineWidth = 8; c.strokeStyle = 'rgba(0,0,0,.75)'; c.strokeText(text, 128, 48);
  c.fillStyle = color; c.fillText(text, 128, 48);
  const t = new THREE.CanvasTexture(cv); t.colorSpace = THREE.SRGBColorSpace;
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, transparent: true, depthWrite: false }));
  s.scale.set(2.6, 1.0, 1);
  return s;
}

// ----------------------------------------------------------------------------
// Stato di gioco
// ----------------------------------------------------------------------------
const S = {
  mode: 'menu',            // menu | intro | play | pause | dead | over | levelup
  levelIdx: 0, startLevel: 0,
  score: 0, levelScore: 0, lives: 3,
  combo: 1, lastEat: -99,
  cells: [], dir: { x: 1, z: 0 }, dirQueue: [],
  growPending: 0, acc: 0, stepTime: BASE_STEP,
  obstacles: new Set(), water: new Set(),
  foods: new Map(), foodSeq: 0,
  fx: { speed: 0, slow: 0, star: 0, magnet: 0 },   // timestamp di scadenza (clock di gioco)
  clock: 0, deadUntil: 0, introUntil: 0,
  camMode: settings.camera, camPos: new THREE.Vector3(0, 16, 12), camLook: new THREE.Vector3(),
  shake: 0,
};
let LV = levelConfig(0);

// ----------------------------------------------------------------------------
// Costruzione scena del livello
// ----------------------------------------------------------------------------
let ambientParticles = null, waterMeshes = [], cloudMeshes = [];

function clearGroup(g) { while (g.children.length) g.remove(g.children[0]); }

function addTree(x, z, sunset) {
  const t = new THREE.Group();
  const trunk = new THREE.Mesh(G.cyl, mat(0x6d4c33));
  trunk.scale.set(0.16, 1.0, 0.16); trunk.position.y = 0.5;
  const c1 = new THREE.Mesh(G.cone, mat(sunset ? 0x33691e : 0x2e7d32));
  c1.scale.set(0.75, 1.1, 0.75); c1.position.y = 1.35;
  const c2 = new THREE.Mesh(G.cone, mat(sunset ? 0x558b2f : 0x43a047));
  c2.scale.set(0.55, 0.9, 0.55); c2.position.y = 1.95;
  [trunk, c1, c2].forEach(m => { m.castShadow = true; t.add(m); });
  t.position.set(gx2w(x), 0, gx2w(z));
  t.rotation.y = Math.random() * 6.28;
  const s = 0.85 + Math.random() * 0.5; t.scale.set(s, s, s);
  levelGroup.add(t);
}
function addRock(x, z) {
  const r = new THREE.Mesh(G.dodeca, mat(0x8d8d8d, { roughness: 1 }));
  r.scale.set(0.42, 0.3 + Math.random() * 0.18, 0.42);
  r.position.set(gx2w(x), 0.22, gx2w(z));
  r.rotation.set(Math.random(), Math.random() * 6, Math.random());
  r.castShadow = true;
  levelGroup.add(r);
}
function addFlower(x, z) {
  const f = new THREE.Group();
  const stem = new THREE.Mesh(G.cyl, mat(0x4caf50));
  stem.scale.set(0.03, 0.3, 0.03); stem.position.y = 0.15;
  const head = new THREE.Mesh(G.sphereLow, mat([0xffeb3b, 0xff7043, 0xec407a, 0xffffff, 0xba68c8][Math.floor(Math.random() * 5)]));
  head.scale.setScalar(0.09); head.position.y = 0.33;
  f.add(stem, head);
  f.position.set(gx2w(x) + (Math.random() - .5) * .6, 0, gx2w(z) + (Math.random() - .5) * .6);
  levelGroup.add(f);
}

function genWater(kind) {
  const w = new Set();
  if (kind === 'none') return w;
  const blob = (cx, cz, r) => {
    for (let x = 0; x < GRID; x++) for (let z = 0; z < GRID; z++) {
      const d = Math.hypot(x - cx, z - cz) + (Math.random() - .5) * 1.2;
      if (d < r) w.add(key(x, z));
    }
  };
  if (kind === 'ponds') { blob(5, 5, 2.6); blob(17, 16, 3.0); blob(16, 4, 2.2); }
  else { blob(5, 17, 4.2); blob(18, 5, 4.6); blob(11, 11, 2.4); }
  // libera la zona di partenza (riga centrale, lato sinistro)
  for (let x = 1; x <= 9; x++) for (let dz = -1; dz <= 1; dz++) w.delete(key(x, HALF + dz));
  return w;
}

function buildLevel() {
  clearGroup(levelGroup); clearGroup(foodGroup); clearGroup(fxGroup);
  waterMeshes = []; cloudMeshes = []; ambientParticles = null;
  S.obstacles.clear(); S.foods.clear();

  scene.background = new THREE.Color(LV.sky);
  scene.fog = new THREE.Fog(LV.fog, LV.fogNear, LV.fogFar);
  sunLight.color.set(LV.sun); sunLight.intensity = LV.sunInt;
  ambLight.color.set(LV.amb); ambLight.intensity = LV.ambInt;

  // terreno
  const ground = new THREE.Mesh(new THREE.PlaneGeometry(GRID, GRID),
    new THREE.MeshStandardMaterial({ map: groundTexture(LV.ground), roughness: 1 }));
  ground.rotation.x = -Math.PI / 2; ground.receiveShadow = true;
  levelGroup.add(ground);
  // prato esterno sfumato nella nebbia
  const outer = new THREE.Mesh(new THREE.PlaneGeometry(GRID * 6, GRID * 6),
    mat(new THREE.Color(LV.ground[0]).multiplyScalar(0.8), { roughness: 1 }));
  outer.rotation.x = -Math.PI / 2; outer.position.y = -0.06;
  levelGroup.add(outer);

  // acqua
  S.water = genWater(LV.water);
  if (S.water.size) {
    const wmat = new THREE.MeshStandardMaterial({ color: 0x2196f3, roughness: 0.25, metalness: 0.2,
      transparent: true, opacity: 0.85, emissive: 0x0a3d62, emissiveIntensity: 0.4 });
    for (const k of S.water) {
      const [x, z] = k.split(',').map(Number);
      const tile = new THREE.Mesh(G.box, wmat);
      tile.scale.set(1.001, 0.14, 1.001);
      tile.position.set(gx2w(x), -0.04, gx2w(z));
      levelGroup.add(tile); waterMeshes.push(tile);
      if (LV.water === 'ponds' && Math.random() < 0.18) {          // ninfee
        const pad = new THREE.Mesh(G.circle, mat(0x388e3c, { side: THREE.DoubleSide }));
        pad.rotation.x = -Math.PI / 2; pad.scale.setScalar(0.3);
        pad.position.set(gx2w(x), 0.045, gx2w(z));
        levelGroup.add(pad);
      }
    }
  }

  // bordo: siepi/canneti tutt'attorno
  const hedgeMat = mat(LV.water === 'lake' ? 0x8d9b4e : 0x1b5e20);
  for (let i = -1; i <= GRID; i++) {
    for (const [bx, bz] of [[i, -1], [i, GRID], [-1, i], [GRID, i]]) {
      if (bx === -1 && bz === -1) continue;
      const h = new THREE.Mesh(G.ico, hedgeMat);
      h.scale.set(0.55, 0.45 + Math.random() * 0.35, 0.55);
      h.position.set(gx2w(bx), 0.3, gx2w(bz));
      h.rotation.y = Math.random() * 6;
      h.castShadow = true;
      levelGroup.add(h);
    }
  }

  // ostacoli (mai nella zona di partenza né in acqua)
  const reserved = new Set();
  for (let x = 0; x <= 10; x++) for (let dz = -1; dz <= 1; dz++) reserved.add(key(x, HALF + dz));
  const freeCell = () => {
    for (let tries = 0; tries < 200; tries++) {
      const x = Math.floor(Math.random() * GRID), z = Math.floor(Math.random() * GRID);
      const k = key(x, z);
      if (!reserved.has(k) && !S.obstacles.has(k) && !S.water.has(k)) return [x, z];
    }
    return null;
  };
  const sunset = LV.ambientFx === 'fireflies';
  for (let i = 0; i < LV.trees; i++) { const c = freeCell(); if (c) { S.obstacles.add(key(...c)); addTree(...c, sunset); } }
  for (let i = 0; i < LV.rocks; i++) { const c = freeCell(); if (c) { S.obstacles.add(key(...c)); addRock(...c); } }
  for (let i = 0; i < LV.flowers; i++) { const c = freeCell(); if (c) addFlower(...c); }   // i fiori sono solo decorativi

  // nuvole
  for (let i = 0; i < 6; i++) {
    const cl = new THREE.Group();
    for (let j = 0; j < 3; j++) {
      const p = new THREE.Mesh(G.sphereLow, new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.85, fog: false }));
      p.scale.set(1.6 + Math.random(), 0.6, 1.1); p.position.x = j * 1.4 - 1.4;
      cl.add(p);
    }
    cl.position.set((Math.random() - .5) * 70, 16 + Math.random() * 6, (Math.random() - .5) * 70);
    cl.userData.v = 0.25 + Math.random() * 0.35;
    levelGroup.add(cl); cloudMeshes.push(cl);
  }

  // particelle ambientali (foglie / farfalle / lucciole)
  const N = 60;
  const pos = new Float32Array(N * 3), vel = [];
  for (let i = 0; i < N; i++) {
    pos[i * 3] = (Math.random() - .5) * GRID;
    pos[i * 3 + 1] = 1 + Math.random() * 7;
    pos[i * 3 + 2] = (Math.random() - .5) * GRID;
    vel.push(new THREE.Vector3((Math.random() - .5) * .5, -(0.1 + Math.random() * .3), (Math.random() - .5) * .5));
  }
  const pgeo = new THREE.BufferGeometry();
  pgeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const pcol = { leaves: 0xd4e157, butterflies: 0xffeb3b, fireflies: 0xffff8d }[LV.ambientFx] || 0xffffff;
  const pts = new THREE.Points(pgeo, new THREE.PointsMaterial({ color: pcol, size: LV.ambientFx === 'fireflies' ? 0.22 : 0.16,
    transparent: true, opacity: 0.9, sizeAttenuation: true }));
  levelGroup.add(pts);
  ambientParticles = { pts, vel, kind: LV.ambientFx };

  for (let i = 0; i < LV.food; i++) spawnFood();
}

// ----------------------------------------------------------------------------
// Serpente
// ----------------------------------------------------------------------------
let snakeMeshes = [], headMesh = null, prevPos = [], currPos = [];

function makeHead() {
  const h = new THREE.Group();
  const skull = new THREE.Mesh(G.sphere, mat(0x66bb6a, { roughness: 0.55 }));
  skull.scale.set(0.48, 0.44, 0.52); skull.castShadow = true;
  h.add(skull);
  for (const sx of [-0.2, 0.2]) {
    const eye = new THREE.Mesh(G.sphereLow, new THREE.MeshBasicMaterial({ color: 0xffffff }));
    eye.scale.setScalar(0.13); eye.position.set(sx, 0.18, 0.30); h.add(eye);
    const pup = new THREE.Mesh(G.sphereLow, new THREE.MeshBasicMaterial({ color: 0x101010 }));
    pup.scale.setScalar(0.065); pup.position.set(sx, 0.19, 0.41); h.add(pup);
  }
  const tongue = new THREE.Mesh(G.cone, new THREE.MeshBasicMaterial({ color: 0xef5350 }));
  tongue.scale.set(0.05, 0.3, 0.05);
  tongue.rotation.x = Math.PI / 2; tongue.position.set(0, 0, 0.62);
  h.add(tongue);
  h.userData.tongue = tongue; h.userData.skull = skull;
  return h;
}

function bodyColor(i, n) {
  const t = n > 1 ? i / (n - 1) : 0;
  return new THREE.Color().setHSL(0.30 - t * 0.10, 0.65, 0.42 + t * 0.12);
}

function resetSnake() {
  clearGroup(snakeGroup);
  snakeMeshes = []; prevPos = []; currPos = [];
  S.cells = [];
  for (let i = 0; i < 4; i++) S.cells.push({ x: 6 - i, z: HALF });
  S.dir = { x: 1, z: 0 }; S.dirQueue = []; S.growPending = 0; S.acc = 0;
  headMesh = makeHead();
  snakeGroup.add(headMesh);
  snakeMeshes.push(headMesh);
  for (let i = 1; i < S.cells.length; i++) addBodyMesh(i);
  syncSnakePositions(true);
}
function addBodyMesh(i) {
  const m = new THREE.Mesh(G.sphere, mat(bodyColor(i, Math.max(S.cells.length, 6)), { roughness: 0.5 }));
  const r = Math.max(0.30, 0.42 - i * 0.006);
  m.scale.setScalar(r); m.castShadow = true;
  snakeGroup.add(m); snakeMeshes.push(m);
}
function syncSnakePositions(hard) {
  prevPos = currPos.map(v => v.clone());
  currPos = S.cells.map(c => new THREE.Vector3(gx2w(c.x), 0.42, gx2w(c.z)));
  if (hard || prevPos.length === 0) prevPos = currPos.map(v => v.clone());
  while (prevPos.length < currPos.length) prevPos.push(currPos[prevPos.length].clone());
  while (prevPos.length > currPos.length) prevPos.pop();
}

// ----------------------------------------------------------------------------
// Cibo
// ----------------------------------------------------------------------------
function pickFoodType() {
  const w = { ...LV.weights };
  if (!S.water.size) delete w.pesce;
  let tot = 0; for (const k in w) tot += w[k];
  let r = Math.random() * tot;
  for (const k in w) { r -= w[k]; if (r <= 0) return k; }
  return 'mela';
}
function foodMesh(type) {
  const g = new THREE.Group();
  const add = (geo, m, s, p, r) => {
    const mesh = new THREE.Mesh(geo, m);
    mesh.scale.set(...s); mesh.position.set(...p);
    if (r) mesh.rotation.set(...r);
    mesh.castShadow = true; g.add(mesh); return mesh;
  };
  switch (type) {
    case 'mela':
      add(G.sphere, mat(0xe53935, { roughness: .4 }), [.30, .28, .30], [0, .30, 0]);
      add(G.cyl, mat(0x5d4037), [.03, .16, .03], [0, .55, 0]);
      add(G.sphereLow, mat(0x66bb6a), [.10, .04, .06], [.09, .56, 0]);
      break;
    case 'mirtillo':
      for (const [px, pz] of [[-.14, 0], [.12, .1], [.02, -.15]])
        add(G.sphereLow, mat(0x3f51b5, { roughness: .35 }), [.13, .13, .13], [px, .14, pz]);
      break;
    case 'fungo':
      add(G.cyl, mat(0xfff3e0), [.10, .3, .10], [0, .15, 0]);
      add(G.sphere, mat(0xd32f2f, { roughness: .5 }), [.26, .16, .26], [0, .34, 0]);
      for (let i = 0; i < 4; i++)
        add(G.sphereLow, mat(0xffffff), [.045, .03, .045], [Math.cos(i * 1.7) * .15, .43, Math.sin(i * 1.7) * .15]);
      break;
    case 'oro':
      add(G.ico, mat(0xffc107, { metalness: .7, roughness: .25, emissive: 0xff8f00, emissiveIntensity: .5 }), [.26, .26, .26], [0, .34, 0]);
      break;
    case 'ciliegia':
      add(G.sphereLow, mat(0xc2185b, { roughness: .35 }), [.16, .16, .16], [-.12, .18, 0]);
      add(G.sphereLow, mat(0xe91e63, { roughness: .35 }), [.15, .15, .15], [.12, .16, 0]);
      add(G.cyl, mat(0x33691e), [.02, .3, .02], [0, .40, 0], [0, 0, .5]);
      break;
    case 'stella':
      add(G.octa, mat(0xfff176, { emissive: 0xffee58, emissiveIntensity: .9, metalness: .3, roughness: .3 }), [.3, .38, .3], [0, .42, 0]);
      break;
    case 'cristallo':
      add(G.octa, mat(0x4dd0e1, { emissive: 0x00acc1, emissiveIntensity: .7, metalness: .5, roughness: .2, transparent: true, opacity: .92 }), [.2, .42, .2], [0, .42, 0]);
      break;
    case 'fiore':
      add(G.cyl, mat(0x4caf50), [.03, .3, .03], [0, .15, 0]);
      add(G.sphereLow, mat(0xffeb3b), [.09, .09, .09], [0, .36, 0]);
      for (let i = 0; i < 5; i++)
        add(G.sphereLow, mat(0xba68c8), [.10, .05, .10], [Math.cos(i * 1.256) * .16, .36, Math.sin(i * 1.256) * .16]);
      break;
    case 'pesce':
      add(G.sphere, mat(0xff9800, { roughness: .35 }), [.30, .16, .12], [0, .25, 0]);
      add(G.cone, mat(0xf57c00), [.10, .18, .04], [-.32, .25, 0], [0, 0, Math.PI / 2]);
      add(G.sphereLow, new THREE.MeshBasicMaterial({ color: 0x111111 }), [.03, .03, .03], [.22, .29, .07]);
      break;
  }
  return g;
}
function spawnFood(forceType) {
  const type = forceType || pickFoodType();
  const nearWater = type === 'pesce';
  for (let tries = 0; tries < 250; tries++) {
    const x = Math.floor(Math.random() * GRID), z = Math.floor(Math.random() * GRID);
    const k = key(x, z);
    if (S.obstacles.has(k) || S.water.has(k)) continue;
    if (S.cells.some(c => c.x === x && c.z === z)) continue;
    if ([...S.foods.values()].some(f => f.x === x && f.z === z)) continue;
    const head = S.cells[0];
    if (head && Math.abs(x - head.x) + Math.abs(z - head.z) < 3) continue;
    if (nearWater) {
      let ok = false;
      for (let dx = -1; dx <= 1 && !ok; dx++) for (let dz = -1; dz <= 1; dz++)
        if (S.water.has(key(x + dx, z + dz))) { ok = true; break; }
      if (!ok) continue;
    }
    const mesh = foodMesh(type);
    mesh.position.set(gx2w(x), 0, gx2w(z));
    foodGroup.add(mesh);
    S.foods.set(++S.foodSeq, { type, x, z, mesh, born: S.clock, ttl: FOODS[type].ttl || 0 });
    return true;
  }
  return false;   // griglia piena o nessuna cella valida: niente spawn
}

// ----------------------------------------------------------------------------
// Particelle "una tantum" (esplosioni all'ingestione) + punteggi volanti
// ----------------------------------------------------------------------------
const bursts = [], floaters = [];
function burst(wx, wz, color, n = 16) {
  const pos = new Float32Array(n * 3), vel = [];
  for (let i = 0; i < n; i++) {
    pos[i * 3] = wx; pos[i * 3 + 1] = 0.5; pos[i * 3 + 2] = wz;
    vel.push(new THREE.Vector3((Math.random() - .5) * 5, 2 + Math.random() * 4, (Math.random() - .5) * 5));
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const p = new THREE.Points(g, new THREE.PointsMaterial({ color, size: 0.14, transparent: true, opacity: 1 }));
  fxGroup.add(p);
  bursts.push({ p, vel, life: 0.8, t: 0 });
}
function floatScore(wx, wz, text, color) {
  const s = textSprite(text, color);
  s.position.set(wx, 1.1, wz);
  fxGroup.add(s);
  floaters.push({ s, t: 0 });
}
function updateFX(dt) {
  for (let i = bursts.length - 1; i >= 0; i--) {
    const b = bursts[i]; b.t += dt;
    const a = b.p.geometry.attributes.position;
    for (let j = 0; j < b.vel.length; j++) {
      b.vel[j].y -= 9.5 * dt;
      a.array[j * 3] += b.vel[j].x * dt; a.array[j * 3 + 1] += b.vel[j].y * dt; a.array[j * 3 + 2] += b.vel[j].z * dt;
    }
    a.needsUpdate = true;
    b.p.material.opacity = Math.max(0, 1 - b.t / b.life);
    if (b.t >= b.life) { fxGroup.remove(b.p); b.p.geometry.dispose(); b.p.material.dispose(); bursts.splice(i, 1); }
  }
  for (let i = floaters.length - 1; i >= 0; i--) {
    const f = floaters[i]; f.t += dt;
    f.s.position.y += dt * 1.4;
    f.s.material.opacity = Math.max(0, 1 - f.t / 1.1);
    if (f.t >= 1.1) { fxGroup.remove(f.s); f.s.material.map.dispose(); f.s.material.dispose(); floaters.splice(i, 1); }
  }
}

// ----------------------------------------------------------------------------
// HUD
// ----------------------------------------------------------------------------
const $ = id => document.getElementById(id);
const hud = $('hud'), toastEl = $('toast'), flashEl = $('flash');
let toastTimer = 0;
function toast(msg, color = '#ffd54a') {
  toastEl.textContent = msg; toastEl.style.color = color;
  toastEl.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove('show'), 1400);
}
function updateHUD() {
  $('scoreBox').childNodes[0].nodeValue = S.score;
  $('bestInline').textContent = Math.max(store.best, S.score);
  $('livesBox').textContent = '❤'.repeat(S.lives) + '🖤'.repeat(Math.max(0, 3 - S.lives));
  $('levelName').textContent = `${LV.label} · ${LV.name}`;
  $('progressInner').style.width = Math.min(100, S.levelScore / LV.target * 100) + '%';
  const cb = $('comboBox');
  if (S.combo > 1 && S.clock - S.lastEat < COMBO_WINDOW) { cb.textContent = `COMBO ×${S.combo}`; cb.classList.add('on'); }
  else cb.classList.remove('on');
}
const FX_LABEL = { speed: ['🍄', '#ff7043'], slow: ['🌸', '#ba68c8'], star: ['⭐', '#ffe082'], magnet: ['💎', '#4dd0e1'] };
const FX_DUR = { speed: 6, slow: 6, star: 8, magnet: 8 };
function updateEffectsHUD() {
  const box = $('effects');
  let html = '';
  for (const k in S.fx) {
    const left = S.fx[k] - S.clock;
    if (left > 0) {
      const [icon, col] = FX_LABEL[k];
      html += `<div class="fx"><span>${icon}</span><span class="bar"><i style="width:${Math.min(100, left / FX_DUR[k] * 100)}%;background:${col}"></i></span></div>`;
    }
  }
  if (box._last !== html) { box.innerHTML = html; box._last = html; }
  // nascondi la combo quando la finestra temporale scade
  if (S.clock - S.lastEat >= COMBO_WINDOW) $('comboBox').classList.remove('on');
}

// ----------------------------------------------------------------------------
// Input: joypad virtuale + swipe + tastiera
// ----------------------------------------------------------------------------
const joyZone = $('joyZone'), joyBase = $('joyBase'), joyKnob = $('joyKnob');
let joyActive = false, joyId = null, joyCenter = { x: 0, y: 0 }, joyVec = { x: 0, y: 0 };

function joyLayout() {
  const r = joyBase.getBoundingClientRect();
  joyCenter = { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}
function setKnob(dx, dy) {
  const R = 42, len = Math.hypot(dx, dy) || 1;
  const cl = Math.min(len, R);
  joyKnob.style.transform = `translate(${dx / len * cl}px, ${dy / len * cl}px)`;
}
function joyStart(e) {
  if (!settings.joystick || S.mode !== 'play') return;
  const t = e.changedTouches ? e.changedTouches[0] : e;
  joyActive = true; joyId = t.identifier ?? 'mouse';
  joyLayout();
  // il joypad "salta" dove tocchi, se tocchi lontano dalla base
  const d = Math.hypot(t.clientX - joyCenter.x, t.clientY - joyCenter.y);
  if (d > 110) {
    const zr = joyZone.getBoundingClientRect();
    joyBase.style.left = (t.clientX - zr.left - 66) + 'px';
    joyBase.style.bottom = 'auto';
    joyBase.style.top = (t.clientY - zr.top - 66) + 'px';
    joyLayout();
  }
  joyMove(e);
}
function joyMove(e) {
  if (!joyActive) return;
  const touches = e.changedTouches ? [...e.changedTouches] : [e];
  const t = touches.find(t => (t.identifier ?? 'mouse') === joyId);
  if (!t) return;
  const dx = t.clientX - joyCenter.x, dy = t.clientY - joyCenter.y;
  setKnob(dx, dy);
  if (Math.hypot(dx, dy) > 18) { joyVec = { x: dx, y: dy }; applyInput(dx, -dy); }
}
function joyEnd(e) {
  const touches = e.changedTouches ? [...e.changedTouches] : [e];
  if (!touches.some(t => (t.identifier ?? 'mouse') === joyId)) return;
  joyActive = false; joyVec = { x: 0, y: 0 };
  joyKnob.style.transform = 'translate(0,0)';
}
joyZone.addEventListener('touchstart', e => { e.preventDefault(); joyStart(e); }, { passive: false });
joyZone.addEventListener('touchmove', e => { e.preventDefault(); joyMove(e); }, { passive: false });
joyZone.addEventListener('touchend', joyEnd);
joyZone.addEventListener('touchcancel', joyEnd);
joyZone.addEventListener('mousedown', joyStart);
addEventListener('mousemove', joyMove);
addEventListener('mouseup', joyEnd);

// swipe (sul canvas, fuori dalla zona joypad)
let swipeOrigin = null;
canvas.addEventListener('touchstart', e => {
  if (!settings.swipe || S.mode !== 'play') return;
  const t = e.changedTouches[0];
  swipeOrigin = { x: t.clientX, y: t.clientY, id: t.identifier };
}, { passive: true });
canvas.addEventListener('touchmove', e => {
  if (!swipeOrigin) return;
  const t = [...e.changedTouches].find(t => t.identifier === swipeOrigin.id);
  if (!t) return;
  const dx = t.clientX - swipeOrigin.x, dy = t.clientY - swipeOrigin.y;
  if (Math.hypot(dx, dy) > 26) {
    applyInput(dx, -dy);
    swipeOrigin = { x: t.clientX, y: t.clientY, id: t.identifier };   // consenti swipe concatenati
  }
}, { passive: true });
canvas.addEventListener('touchend', () => swipeOrigin = null);

addEventListener('keydown', e => {
  const map = { ArrowUp: [0, 1], ArrowDown: [0, -1], ArrowLeft: [-1, 0], ArrowRight: [1, 0], w: [0, 1], s: [0, -1], a: [-1, 0], d: [1, 0] };
  if (map[e.key] && S.mode === 'play') applyInput(...map[e.key].map(v => v * 100));
  if ((e.key === ' ' || e.key === 'Escape') && (S.mode === 'play' || S.mode === 'pause')) togglePause();
});

// Converte un vettore di input schermo (ix destra+, iy su+) in direzione griglia,
// tenendo conto dell'orientamento della telecamera.
const _f = new THREE.Vector3(), _r = new THREE.Vector3();
function applyInput(ix, iy) {
  camera.getWorldDirection(_f); _f.y = 0;
  if (_f.lengthSq() < 1e-6) _f.set(0, 0, -1);
  _f.normalize();
  _r.set(-_f.z, 0, _f.x);         // destra rispetto alla camera
  const wx = _r.x * ix + _f.x * iy;
  const wz = _r.z * ix + _f.z * iy;
  let d;
  if (Math.abs(wx) > Math.abs(wz)) d = { x: Math.sign(wx), z: 0 };
  else d = { x: 0, z: Math.sign(wz) };
  if (!d.x && !d.z) return;
  const last = S.dirQueue.length ? S.dirQueue[S.dirQueue.length - 1] : S.dir;
  if (d.x === last.x && d.z === last.z) return;              // stessa direzione
  if (d.x === -last.x && d.z === -last.z) return;            // retromarcia vietata
  if (S.dirQueue.length < 2) { S.dirQueue.push(d); AudioFX.tick(); }
}

// ----------------------------------------------------------------------------
// Logica di gioco
// ----------------------------------------------------------------------------
function currentStepTime() {
  let m = LV.speed;
  if (S.fx.speed > S.clock) m *= 1.45;
  if (S.fx.slow > S.clock) m *= 0.62;
  return BASE_STEP / m;
}

function step() {
  if (S.dirQueue.length) S.dir = S.dirQueue.shift();
  const head = S.cells[0];
  let nx = head.x + S.dir.x, nz = head.z + S.dir.z;
  const star = S.fx.star > S.clock;

  // muri: morte (o attraversamento se invincibile)
  if (nx < 0 || nx >= GRID || nz < 0 || nz >= GRID) {
    if (star) { nx = (nx + GRID) % GRID; nz = (nz + GRID) % GRID; }
    else return die('🧱 Ahi! Il muro di siepi!');
  }
  const nk = key(nx, nz);
  if (!star && S.obstacles.has(nk)) return die('🌲 Sbattuto contro un ostacolo!');
  if (!star && S.water.has(nk)) return die('💦 Splash! I serpenti di bosco non nuotano…');
  const growing = S.growPending > 0;
  const bodyToCheck = growing ? S.cells : S.cells.slice(0, -1);
  if (!star && bodyToCheck.some(c => c.x === nx && c.z === nz)) return die('🌀 Ti sei morso la coda!');

  S.cells.unshift({ x: nx, z: nz });
  if (growing) { S.growPending--; addBodyMesh(snakeMeshes.length); }
  else S.cells.pop();

  // magnete: raccogli il cibo nelle vicinanze
  const magnetR = S.fx.magnet > S.clock ? 2.2 : 0.1;
  for (const [id, f] of [...S.foods]) {
    if (Math.hypot(f.x - nx, f.z - nz) <= magnetR || (f.x === nx && f.z === nz)) eatFood(id, f);
  }

  syncSnakePositions(false);
}

function eatFood(id, f) {
  const def = FOODS[f.type];
  S.foods.delete(id);
  foodGroup.remove(f.mesh);

  // combo
  if (S.clock - S.lastEat < COMBO_WINDOW) S.combo = Math.min(MAX_COMBO, S.combo + 1);
  else S.combo = 1;
  S.lastEat = S.clock;

  const pts = def.pts * S.combo;
  S.score += pts; S.levelScore += pts;
  if (def.grow > 0) S.growPending += def.grow;
  else if (def.grow < 0) shrink(-def.grow);

  // effetti speciali
  const wx = gx2w(f.x), wz = gx2w(f.z);
  switch (f.type) {
    case 'fungo':     S.fx.speed = S.clock + FX_DUR.speed; toast('🍄 SUPER VELOCITÀ!', '#ff8a65'); AudioFX.power(); break;
    case 'fiore':     S.fx.slow = S.clock + FX_DUR.slow; toast('🌸 Tempo rallentato…', '#ce93d8'); AudioFX.power(); break;
    case 'stella':    S.fx.star = S.clock + FX_DUR.star; toast('⭐ INVINCIBILE!', '#fff176'); AudioFX.power(); break;
    case 'cristallo': S.fx.magnet = S.clock + FX_DUR.magnet; toast('💎 MAGNETE ATTIVO!', '#4dd0e1'); AudioFX.power(); break;
    case 'ciliegia':  toast('🍒 Più corto e agile!', '#f48fb1'); AudioFX.power(); break;
    case 'oro':       toast("✨ BACCA D'ORO! +" + pts, '#ffd54a'); AudioFX.power(); break;
    default:          AudioFX.eat(S.combo);
  }
  burst(wx, wz, { mela: 0xe53935, mirtillo: 0x5c6bc0, fungo: 0xff7043, oro: 0xffc107, ciliegia: 0xec407a, stella: 0xfff176, cristallo: 0x4dd0e1, fiore: 0xba68c8, pesce: 0xff9800 }[f.type] || 0xffffff);
  floatScore(wx, wz, '+' + pts, S.combo > 1 ? '#ffd54a' : '#ffffff');
  navigator.vibrate?.(25);

  // ripristina il numero di cibi previsto dal livello
  while (S.foods.size < LV.food) { if (!spawnFood()) break; }

  if (S.levelScore >= LV.target) return levelComplete();
  updateHUD();
}

function shrink(n) {
  while (n-- > 0 && S.cells.length > 3) {
    S.cells.pop();
    const m = snakeMeshes.pop();
    snakeGroup.remove(m);
  }
  syncSnakePositions(true);
}

function die(reason) {
  if (S.clock < S.deadUntil) return;
  AudioFX.death();
  navigator.vibrate?.([80, 40, 160]);
  S.lives--;
  S.shake = 0.6;
  flashEl.style.opacity = 0.45;
  setTimeout(() => flashEl.style.opacity = 0, 120);
  updateHUD();
  if (S.lives <= 0) return gameOver();
  toast(reason + `  (❤ ${S.lives})`, '#ff8a65');
  S.mode = 'dead';
  S.deadUntil = S.clock + 1.2;
  for (const k in S.fx) S.fx[k] = 0;
}

function respawn() {
  resetSnake();
  S.combo = 1;
  S.mode = 'play';
}

function levelComplete() {
  AudioFX.levelup();
  const bonus = 50 * (S.levelIdx + 1);
  S.score += bonus;
  toast(`🎉 LIVELLO COMPLETATO! +${bonus}`, '#7dffb0');
  S.levelIdx++;
  if (S.levelIdx < LEVELS.length && S.levelIdx > store.unlocked) store.unlocked = S.levelIdx;
  S.mode = 'levelup';
  S.introUntil = S.clock + 2.2;
  updateHUD();
}

function startLevel() {
  LV = levelConfig(S.levelIdx);
  S.levelScore = 0;
  for (const k in S.fx) S.fx[k] = 0;
  buildLevel();
  resetSnake();
  $('introTitle').textContent = `${LV.label} — ${LV.name}`;
  $('introDesc').textContent = LV.desc + `  ·  Obiettivo: ${LV.target} punti`;
  $('levelIntro').classList.remove('hidden');
  S.mode = 'intro';
  S.introUntil = S.clock + 2.2;
  updateHUD();
}

function gameOver() {
  S.mode = 'over';
  const isRecord = S.score > store.best;
  if (isRecord) store.best = S.score;
  $('goScore').textContent = S.score;
  $('goBest').textContent = store.best;
  $('goNewRecord').classList.toggle('hidden', !isRecord);
  $('gameoverOverlay').classList.remove('hidden');
  hud.style.display = 'none'; joyZone.style.display = 'none';
  AudioFX.stopMusic();
}

function newGame() {
  S.score = 0; S.lives = 3; S.combo = 1; S.lastEat = -99;
  S.levelIdx = S.startLevel;
  $('menuOverlay').classList.add('hidden');
  $('gameoverOverlay').classList.add('hidden');
  $('pauseOverlay').classList.add('hidden');
  hud.style.display = 'block';
  joyZone.style.display = settings.joystick ? 'block' : 'none';
  AudioFX.unlock(); AudioFX.startMusic();
  startLevel();
}

function togglePause() {
  if (S.mode === 'play') {
    S.mode = 'pause';
    $('pauseOverlay').classList.remove('hidden');
  } else if (S.mode === 'pause') {
    S.mode = 'play';
    $('pauseOverlay').classList.add('hidden');
  }
  AudioFX.click();
}
document.addEventListener('visibilitychange', () => { if (document.hidden && S.mode === 'play') togglePause(); });

// ----------------------------------------------------------------------------
// Telecamera
// ----------------------------------------------------------------------------
const _headW = new THREE.Vector3(), _tgtPos = new THREE.Vector3(), _tgtLook = new THREE.Vector3();
function updateCamera(dt) {
  if (headMesh) _headW.copy(headMesh.position); else _headW.set(0, 0, 0);
  if (S.camMode === 'top') {
    _tgtPos.set(_headW.x * 0.35, 15.5, _headW.z * 0.35 + 10.5);
    _tgtLook.set(_headW.x * 0.5, 0, _headW.z * 0.5);
  } else {
    const d = S.dir;
    _tgtPos.set(_headW.x - d.x * 5.5, 4.6, _headW.z - d.z * 5.5);
    _tgtLook.set(_headW.x + d.x * 3, 0.5, _headW.z + d.z * 3);
  }
  const k = 1 - Math.pow(0.0015, dt);
  S.camPos.lerp(_tgtPos, k);
  S.camLook.lerp(_tgtLook, k * 1.2 > 1 ? 1 : k * 1.2);
  camera.position.copy(S.camPos);
  if (S.shake > 0) {
    S.shake = Math.max(0, S.shake - dt);
    camera.position.x += (Math.random() - .5) * S.shake * 0.5;
    camera.position.y += (Math.random() - .5) * S.shake * 0.5;
  }
  camera.lookAt(S.camLook);
}

// ----------------------------------------------------------------------------
// Render loop
// ----------------------------------------------------------------------------
let lastT = performance.now();
function frame(now) {
  requestAnimationFrame(frame);
  const dt = Math.min(0.05, (now - lastT) / 1000);
  lastT = now;
  if (S.mode === 'menu') { renderer.render(scene, camera); return; }
  S.clock += dt;

  // stati temporizzati
  if (S.mode === 'intro' && S.clock >= S.introUntil) { $('levelIntro').classList.add('hidden'); S.mode = 'play'; }
  if (S.mode === 'levelup' && S.clock >= S.introUntil) startLevel();
  if (S.mode === 'dead' && S.clock >= S.deadUntil) respawn();

  // passi logici
  if (S.mode === 'play') {
    S.stepTime = currentStepTime();
    S.acc += dt;
    while (S.acc >= S.stepTime && S.mode === 'play') {
      S.acc -= S.stepTime;
      step();
    }
  }

  // interpolazione serpente
  const alpha = S.mode === 'play' ? Math.min(1, S.acc / S.stepTime) : 1;
  const star = S.fx.star > S.clock;
  for (let i = 0; i < snakeMeshes.length && i < currPos.length; i++) {
    const m = snakeMeshes[i];
    m.position.lerpVectors(prevPos[i], currPos[i], alpha);
    if (i === 0) {
      m.rotation.y = Math.atan2(S.dir.x, S.dir.z);
      const tongue = m.userData.tongue;
      if (tongue) tongue.scale.y = 0.2 + Math.abs(Math.sin(S.clock * 6)) * 0.25;
      const skull = m.userData.skull;
      if (skull) skull.material.emissive.setHex(star ? 0xffee58 : 0x000000),
                 skull.material.emissiveIntensity = star ? (0.6 + Math.sin(S.clock * 10) * 0.4) : 0;
    } else {
      m.position.y = 0.34 + Math.sin(S.clock * 5 + i * 0.6) * 0.03;
      if (star) { m.material.emissive.setHex(0xffee58); m.material.emissiveIntensity = 0.4 + Math.sin(S.clock * 10 + i) * 0.3; }
      else if (m.material.emissiveIntensity) { m.material.emissive.setHex(0x000000); m.material.emissiveIntensity = 0; }
    }
  }

  // cibi: rotazione, rimbalzo, scadenza
  for (const [id, f] of [...S.foods]) {
    f.mesh.rotation.y += dt * 1.6;
    f.mesh.position.y = Math.sin(S.clock * 3 + id) * 0.06;
    if (f.ttl) {
      const left = f.born + f.ttl - S.clock;
      if (left < 0) { foodGroup.remove(f.mesh); S.foods.delete(id); spawnFood(); }
      else if (left < 2.5) f.mesh.visible = Math.sin(S.clock * 12) > -0.4;   // lampeggia prima di sparire
    }
  }

  // acqua, nuvole, particelle ambientali
  const wob = 0.85 + Math.sin(S.clock * 2.2) * 0.06;
  for (const w of waterMeshes) { w.material.opacity = wob; break; }          // materiale condiviso
  for (const cl of cloudMeshes) {
    cl.position.x += cl.userData.v * dt;
    if (cl.position.x > 45) cl.position.x = -45;
  }
  if (ambientParticles) {
    const a = ambientParticles.pts.geometry.attributes.position;
    for (let i = 0; i < ambientParticles.vel.length; i++) {
      const v = ambientParticles.vel[i];
      if (ambientParticles.kind === 'leaves') {
        a.array[i * 3] += (v.x + Math.sin(S.clock * 2 + i) * .4) * dt;
        a.array[i * 3 + 1] += v.y * dt;
        a.array[i * 3 + 2] += v.z * dt;
        if (a.array[i * 3 + 1] < 0) { a.array[i * 3 + 1] = 7 + Math.random() * 3; a.array[i * 3] = (Math.random() - .5) * GRID; a.array[i * 3 + 2] = (Math.random() - .5) * GRID; }
      } else {
        a.array[i * 3] += Math.sin(S.clock * 1.5 + i * 2.1) * dt * 1.2;
        a.array[i * 3 + 1] += Math.cos(S.clock * 2 + i) * dt * 0.7;
        a.array[i * 3 + 2] += Math.cos(S.clock * 1.3 + i * 1.7) * dt * 1.2;
        if (a.array[i * 3 + 1] < 0.3) a.array[i * 3 + 1] = 0.3;
        if (a.array[i * 3 + 1] > 8) a.array[i * 3 + 1] = 8;
      }
    }
    a.needsUpdate = true;
    if (ambientParticles.kind === 'fireflies')
      ambientParticles.pts.material.opacity = 0.5 + Math.sin(S.clock * 4) * 0.4;
  }

  updateFX(dt);
  updateEffectsHUD();
  updateCamera(dt);
  renderer.render(scene, camera);
}
requestAnimationFrame(frame);

// ----------------------------------------------------------------------------
// Menu, impostazioni, bottoni
// ----------------------------------------------------------------------------
function buildLevelSelect() {
  const box = $('levelSelect');
  box.innerHTML = '';
  LEVELS.forEach((lv, i) => {
    const locked = i > store.unlocked;
    const b = document.createElement('button');
    b.className = 'lvbtn' + (locked ? ' locked' : '') + (i === S.startLevel ? ' sel' : '');
    b.style.background = `linear-gradient(160deg, ${lv.color}, #143d1a)`;
    b.innerHTML = `${locked ? '🔒' : i + 1}<small>${lv.name.split(' ')[0]}</small>`;
    b.onclick = () => {
      if (locked) { toast('🔒 Completa i livelli precedenti!', '#ff8a65'); return; }
      S.startLevel = i; AudioFX.click(); buildLevelSelect();
    };
    box.appendChild(b);
  });
}
function buildLegend() {
  const box = $('foodLegend');
  box.innerHTML = Object.values(FOODS).map(f => `<div>${f.icon} <b>${f.label}</b><br>${f.desc}</div>`).join('') +
    `<div>🕹 <b>Joypad & Swipe</b><br>trascina la levetta o fai swipe per girare</div>` +
    `<div>🔥 <b>Combo</b><br>mangia in fretta: punti ×2 ×3 ×4 ×5!</div>` +
    `<div>💧 <b>Acqua & ostacoli</b><br>evitali… o prendi la Stella!</div>`;
}
function showMenu() {
  S.mode = 'menu';
  $('menuOverlay').classList.remove('hidden');
  $('gameoverOverlay').classList.add('hidden');
  $('pauseOverlay').classList.add('hidden');
  $('levelIntro').classList.add('hidden');
  hud.style.display = 'none'; joyZone.style.display = 'none';
  $('bestMenu').textContent = store.best;
  buildLevelSelect();
  AudioFX.stopMusic();
  // scena di sfondo del menu
  LV = levelConfig(Math.min(S.startLevel, LEVELS.length - 1));
  buildLevel(); resetSnake();
  S.camPos.set(0, 14, 15); S.camLook.set(0, 0, 0);
}

$('btnPlay').onclick = () => { AudioFX.click(); newGame(); };
$('btnRetry').onclick = () => { AudioFX.click(); newGame(); };
$('btnResume').onclick = togglePause;
$('btnQuit').onclick = () => { AudioFX.click(); showMenu(); };
$('btnMenu2').onclick = () => { AudioFX.click(); showMenu(); };
$('btnPause').onclick = togglePause;
$('btnHow').onclick = () => { AudioFX.click(); buildLegend(); $('foodLegend').classList.toggle('hidden'); };
$('btnFull').onclick = () => {
  AudioFX.click();
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen?.().catch(() => {});
};
$('btnCam').onclick = () => {
  S.camMode = S.camMode === 'top' ? 'chase' : 'top';
  settings.camera = S.camMode; saveSettings();
  toast(S.camMode === 'top' ? "🎥 Vista dall'alto" : '🎥 Vista inseguimento', '#90caf9');
  AudioFX.click();
};
$('btnSound').onclick = function () {
  settings.sound = !settings.sound; saveSettings();
  this.textContent = settings.sound ? '🔊' : '🔇';
  if (settings.sound) { AudioFX.unlock(); AudioFX.startMusic(); AudioFX.click(); }
};
$('ctlJoy').onclick = function () {
  settings.joystick = !settings.joystick; saveSettings();
  this.classList.toggle('on', settings.joystick);
};
$('ctlSwipe').onclick = function () {
  settings.swipe = !settings.swipe; saveSettings();
  this.classList.toggle('on', settings.swipe);
};
$('ctlJoy').classList.toggle('on', settings.joystick);
$('ctlSwipe').classList.toggle('on', settings.swipe);
$('btnSound').textContent = settings.sound ? '🔊' : '🔇';

// blocco dello standby dello schermo (se supportato)
async function wakeLock() {
  try { await navigator.wakeLock?.request('screen'); } catch { }
}
document.addEventListener('visibilitychange', () => { if (!document.hidden) wakeLock(); });
wakeLock();

// avvio
showMenu();

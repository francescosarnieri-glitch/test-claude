// GARGANTUA — the 21 live parameters and the 4 cinematic presets.

export const PARAM_DEFS = [
  // group, key, label, min, max, step
  ['Scene', 'fov',            'Field of view (°)',      30,   100,  1],
  ['Scene', 'exposure',       'Exposure',               0.1,  4.0,  0.01],
  ['Scene', 'timeScale',      'Time scale',             0.0,  4.0,  0.01],
  ['Accretion disk', 'diskInner',   'Inner radius (rs)', 2.2,  6.0,  0.05],
  ['Accretion disk', 'diskOuter',   'Outer radius (rs)', 7.0,  24.0, 0.1],
  ['Accretion disk', 'diskTemp',    'Temperature (K)',   2000, 12000, 50],
  ['Accretion disk', 'diskDensity', 'Density',           0.0,  2.0,  0.01],
  ['Turbulence', 'turbAmount', 'Amount',                 0.0,  1.0,  0.01],
  ['Turbulence', 'turbScale',  'Scale',                  0.3,  4.0,  0.01],
  ['Turbulence', 'turbSpeed',  'Speed',                  0.0,  3.0,  0.01],
  ['Relativity', 'beaming',    'Doppler beaming',        0.0,  5.0,  0.05],
  ['Relativity', 'redshift',   'Red/blueshift',          0.0,  1.0,  0.01],
  ['Environment', 'starDensity', 'Star density',         0.0,  2.0,  0.01],
  ['Environment', 'starBright',  'Star brightness',      0.0,  3.0,  0.01],
  ['Environment', 'milkyWay',    'Milky Way',            0.0,  2.5,  0.01],
  ['Post', 'bloomStrength', 'Bloom strength',            0.0,  3.0,  0.01],
  ['Post', 'bloomThreshold','Bloom threshold',           0.0,  2.0,  0.01],
  ['Post', 'bloomRadius',   'Bloom radius',              0.0,  1.0,  0.01],
  ['Post', 'vignette',      'Vignette',                  0.0,  1.0,  0.01],
  ['Post', 'grain',         'Film grain',                0.0,  0.2,  0.005],
  ['Post', 'chromAb',       'Chromatic aberration',      0.0,  3.0,  0.05],
];

export const PRESETS = [
  {
    name: 'Gargantua',
    camera: { yaw: 0.35, pitch: 0.085, dist: 13.5 },
    params: {
      fov: 60, exposure: 1.15, timeScale: 1.0,
      diskInner: 3.0, diskOuter: 14.0, diskTemp: 5600, diskDensity: 0.85,
      turbAmount: 0.55, turbScale: 1.6, turbSpeed: 1.0,
      beaming: 1.6, redshift: 0.45,
      starDensity: 1.0, starBright: 1.0, milkyWay: 0.9,
      bloomStrength: 0.9, bloomThreshold: 0.85, bloomRadius: 0.55,
      vignette: 0.42, grain: 0.05, chromAb: 0.8,
    },
  },
  {
    name: 'Blazar',
    camera: { yaw: 2.2, pitch: 0.045, dist: 11.0 },
    params: {
      fov: 55, exposure: 1.0, timeScale: 1.3,
      diskInner: 3.0, diskOuter: 11.0, diskTemp: 9500, diskDensity: 1.1,
      turbAmount: 0.7, turbScale: 2.3, turbSpeed: 1.8,
      beaming: 3.2, redshift: 1.0,
      starDensity: 0.8, starBright: 0.8, milkyWay: 0.5,
      bloomStrength: 1.5, bloomThreshold: 0.7, bloomRadius: 0.7,
      vignette: 0.5, grain: 0.06, chromAb: 1.4,
    },
  },
  {
    name: 'Ember',
    camera: { yaw: -1.1, pitch: 0.22, dist: 6.8 },
    params: {
      fov: 68, exposure: 1.05, timeScale: 0.7,
      diskInner: 3.1, diskOuter: 17.0, diskTemp: 3300, diskDensity: 1.5,
      turbAmount: 0.85, turbScale: 2.8, turbSpeed: 0.6,
      beaming: 1.1, redshift: 0.6,
      starDensity: 0.6, starBright: 0.55, milkyWay: 0.35,
      bloomStrength: 0.65, bloomThreshold: 0.95, bloomRadius: 0.45,
      vignette: 0.55, grain: 0.075, chromAb: 0.6,
    },
  },
  {
    name: 'Deep Field',
    camera: { yaw: 0.9, pitch: 0.5, dist: 19.0 },
    params: {
      fov: 52, exposure: 1.3, timeScale: 1.0,
      diskInner: 3.0, diskOuter: 10.0, diskTemp: 4600, diskDensity: 0.28,
      turbAmount: 0.4, turbScale: 1.2, turbSpeed: 0.8,
      beaming: 1.8, redshift: 0.5,
      starDensity: 1.8, starBright: 1.9, milkyWay: 1.8,
      bloomStrength: 0.75, bloomThreshold: 0.75, bloomRadius: 0.6,
      vignette: 0.35, grain: 0.045, chromAb: 0.5,
    },
  },
];

export const QUALITY_PROFILES = {
  low:    { label: 'Low',    renderScale: 0.5,  maxDpr: 1.0, steps: 140, stepScale: 0.32 },
  medium: { label: 'Medium', renderScale: 0.75, maxDpr: 1.5, steps: 256, stepScale: 0.21 },
  high:   { label: 'High',   renderScale: 1.0,  maxDpr: 2.0, steps: 384, stepScale: 0.15 },
};

export const DEBUG_NAMES = [
  'Beauty', 'Disk only', 'Background only', 'Disk image order', 'Integration cost',
  'Periapsis / photon ring', 'Doppler g-factor', 'Deflection angle', 'Disk turbulence', 'Exit direction',
];

export const CINEMATIC_NAMES = ['Free orbit (manual)', 'Slow orbit', 'The Dive', 'Flyby'];

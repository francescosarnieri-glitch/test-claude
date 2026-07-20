# GARGANTUA — Schwarzschild Black Hole Raytracer

A full-screen, interactive black hole renderer. The entire subject — event horizon,
photon ring, multi-crossing accretion disk, lensed starfield and Milky Way — is
generated in a single fragment shader by numerically integrating **Schwarzschild
null geodesics**. There are no meshes, textures, images, or video: every photon
path is computed per pixel, per frame.

Built with native HTML, CSS, and JavaScript ES modules on top of a locally
vendored Three.js (r160). No build step, no CDN, no network access required.

## Run it

Any static file server from the repository root works:

```bash
# pick one
python3 -m http.server 8000
npx serve .
npx http-server -p 8000
```

Then open <http://localhost:8000/>. A WebGL2-capable browser is required
(any recent Chrome, Firefox, Edge, or Safari).

> ES modules don't load from `file://` URLs — use a server, as above.

## What is simulated

- **Geodesics** — rays are traced backwards from the camera by integrating the
  Schwarzschild null-geodesic equation, written as an effective central force
  `d²x/dλ² = −(3/2) h² x / r⁵` (units `rs = 1`), which reproduces the Binet
  equation `u'' + u = (3/2)u²` exactly. Integration is velocity-Verlet with an
  adaptive step (fine near the photon sphere at `r = 1.5 rs`).
- **Event horizon** — rays reaching `r < 1 rs` terminate on black.
- **Photon ring** — emerges naturally from near-critical orbits (see debug view 5).
- **Accretion disk** — a thin Keplerian disk in the equatorial plane, detected by
  plane-crossing tests along the bent ray; multiple crossings composite
  front-to-back, producing the higher-order "over and under" disk images
  (debug view 3 shows image order).
- **Doppler beaming & redshift** — circular-orbit speed `v = √(M/(r−2M))`,
  combined factor `g = √(1 − 3M/r) / (1 + β·d̂)`; intensity scales as `g^p`
  (beaming slider) and the blackbody temperature is shifted by `g`
  (red/blueshift slider, debug view 6).
- **Disk turbulence** — animated fbm noise advected by differential Keplerian
  rotation (shear), modulating both emission and opacity (debug view 8).
- **Background** — three procedural star shells (flux-conserving Gaussian PSF,
  blackbody-tinted) plus a procedural Milky Way band with emission nebulosity
  and dust lanes; all of it is gravitationally lensed for free via the bent
  exit ray.

## Post-processing

HDR half-float pipeline: threshold + 5-level separable-Gaussian bloom →
subtle radial **chromatic aberration** → exposure → pre-tonemap **vignette**
(keeps blacks deep) → **manual ACES** filmic tonemap → zero-mean,
luminance-weighted **film grain** that never lifts true blacks → sRGB.
Debug views 3–9 bypass the pipeline to show raw data.

## Controls

Drag to orbit, scroll to dolly (OrbitControls). Right panel exposes
**21 live parameters** across Scene / Accretion disk / Turbulence / Relativity /
Environment / Post groups.

| Key | Action |
|-----|--------|
| `0`–`9` | Debug views: 0 beauty, 1 disk only, 2 background only, 3 disk image order, 4 integration cost, 5 periapsis/photon ring, 6 Doppler g-factor, 7 deflection angle, 8 disk turbulence, 9 exit direction |
| `P` | Cycle the 4 presets (Gargantua, Blazar, Ember, Deep Field) |
| `C` | Cycle cinematic camera paths (free orbit → slow orbit → the dive → flyby) |
| `Q` | Cycle quality (low / medium / high) |
| `Space` | Pause / resume simulation time |
| `M` | Toggle synthesized audio (WebAudio drone + accretion wind, modulated by camera radius and the simulation clock) |
| `S` | Save a PNG screenshot |
| `H` | Hide / show HUD and panel |
| `F` | Fullscreen |
| `R` | Reset everything |
| `K` / `?` | Help overlay |

The HUD reports FPS, frame time, render resolution and scale, integrator
step budget, camera radius/inclination in `rs`, simulation time, active view,
camera path, preset, and audio state.

## Quality, persistence, recovery

- **Quality profiles** (`Q`): low / medium / high set render scale, DPR cap
  (Retina-aware), geodesic step count (140 / 256 / 384), and step size.
- **Persistence**: parameters, quality, debug view, preset, and camera pose are
  saved to `localStorage` and restored on reload (`?fresh=1` skips restore,
  `R` clears).
- **WebGL recovery**: context loss is intercepted, an overlay is shown, and
  rendering resumes automatically when the context is restored.

## Deterministic screenshot mode

```
index.html?shot=1&w=1280&h=720&preset=0&t=12&seed=42
```

Renders exactly one frame at the requested size with a fixed simulation time
(`t`), seeded film grain (`seed`), URL-set camera (`yaw`, `pitch`, `dist`),
optional `debug=0..9`, `quality=low|medium|high`, and any of the 21 parameters
by name (e.g. `&beaming=3`). The page sets `window.__SHOT_DONE = true` when the
frame is on screen; add `&download=1` to auto-save the PNG. Output is
bit-identical across reloads.

## Project layout

```
index.html            shell, import map (three → local vendor copy)
css/style.css         HUD / panel / overlay chrome
js/main.js            renderer, HDR pipeline, camera paths, hotkeys, persistence
js/shaders.js         geodesic raytracer + bright/blur/composite GLSL
js/presets.js         21 parameter definitions, 4 presets, quality profiles
js/ui.js              control panel, telemetry HUD, help overlay
js/audio.js           synthesized soundtrack (no assets)
vendor/three.module.js, vendor/OrbitControls.js   local Three.js r160
```

## Verification results

Verified headlessly (Playwright 1.56 + Chromium/SwiftShader, 2026-07-20)
against a plain `python3 -m http.server`; **21/21 checks passed**:

```
PASS  shot mode renders and signals __SHOT_DONE
PASS  shot: canvas sized from URL (800x480)      — 800x480
PASS  shot: not a black screen                   — nonBlack=79.8% max=255
PASS  shot: has bright disk pixels               — bright=12.65%
PASS  shot: preserves deep blacks                — nonBlack=79.8%
PASS  shot: deterministic across reloads         — identical pixel digests
PASS  shot: debug views 3, 5, 6, 9 render
PASS  live: HUD shows telemetry                  — FPS 38 (SwiftShader, low)
PASS  live: 21 live parameters in panel          — 21 sliders
PASS  live: Q cycles quality
PASS  live: P cycles preset
PASS  live: digit sets debug view
PASS  live: Space pauses time
PASS  live: C engages cinematic path
PASS  live: state persisted to localStorage
PASS  live: WebGL context loss handled + recovered
PASS  no unhandled page errors
PASS  no console errors
```

All four presets were additionally rendered and visually inspected
(shadow, photon ring, lensed upper/lower disk images, Doppler asymmetry,
starfield swirl near the critical curve all present; no black screens).

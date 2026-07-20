// GARGANTUA — shader sources.
// The whole scene is generated in the black-hole fragment shader by integrating
// Schwarzschild null geodesics. Units: Schwarzschild radius rs = 1, so M = 0.5.

export const FSQ_VERT = /* glsl */ `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.0, 1.0);
}
`;

// ---------------------------------------------------------------------------
// Black-hole raytracer
// ---------------------------------------------------------------------------
export const BLACKHOLE_FRAG = /* glsl */ `
varying vec2 vUv;

uniform vec2  uResolution;
uniform float uTime;        // simulation time (seconds, pausable, deterministic in shot mode)
uniform vec3  uCamPos;      // camera position in rs units
uniform mat3  uCamBasis;    // camera world basis (columns: right, up, back)
uniform float uTanHalfFov;
uniform float uPixAngle;    // angular size of one pixel (radians), for star anti-aliasing
uniform float uStepScale;   // integrator step scale (quality dependent)
uniform int   uSteps;       // runtime step cap (<= MAXSTEPS)
uniform int   uDebug;       // 0..9 debug views

uniform float uDiskInner;   // in rs (ISCO = 3 rs)
uniform float uDiskOuter;
uniform float uDiskTemp;    // Kelvin at the inner edge
uniform float uDiskDensity;
uniform float uTurbAmount;
uniform float uTurbScale;
uniform float uTurbSpeed;
uniform float uBeaming;     // Doppler beaming exponent (I ~ g^p)
uniform float uRedshift;    // 0..1 strength of color (temperature) shift

uniform float uStarDensity;
uniform float uStarBright;
uniform float uMilkyWay;

#define PI 3.14159265359

// -------------------------------- hashing / noise ---------------------------
float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
float hash13(vec3 p3) {
  p3 = fract(p3 * 0.1031);
  p3 += dot(p3, p3.zyx + 31.32);
  return fract((p3.x + p3.y) * p3.z);
}
vec3 hash33(vec3 p3) {
  p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
  p3 += dot(p3, p3.yxz + 33.33);
  return fract((p3.xxy + p3.yxx) * p3.zyx);
}

float vnoise(vec3 p) {
  vec3 i = floor(p);
  vec3 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
  float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
  float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
  float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
  float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
  float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
  float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
  float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
  return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
             mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}

float fbm(vec3 p) {
  float a = 0.5;
  float s = 0.0;
  for (int i = 0; i < 5; i++) {
    s += a * vnoise(p);
    p = p * 2.03 + vec3(11.5, 7.7, 3.1);
    a *= 0.5;
  }
  return s;
}

// -------------------------------- physics ------------------------------------
// Blackbody chromaticity approximation (T in Kelvin), normalized-ish RGB.
vec3 blackbody(float t) {
  t = clamp(t, 800.0, 40000.0);
  vec3 c;
  c.r = 56100000.0 * pow(t, -1.5) + 148.0;
  c.g = t > 6500.0 ? 35200000.0 * pow(t, -1.5) + 184.0 : 100.04 * log(t) - 623.6;
  c.b = 194.18 * log(t) - 1448.6;
  c = clamp(c, 0.0, 255.0) / 255.0;
  if (t < 1400.0) c *= t / 1400.0; // fade very cool emitters to black
  return c;
}

// Null geodesic in Schwarzschild (rs = 1) as an effective central force:
// d2x/dl2 = -(3/2) h^2 x / r^5, with h = |x × v| conserved.
// This reproduces the Binet equation u'' + u = (3/2) u^2 exactly.
vec3 geoAccel(vec3 p, float h2) {
  float r2 = dot(p, p);
  float r = sqrt(r2);
  return (-1.5 * h2 / (r2 * r2 * r)) * p;
}

// -------------------------------- background ---------------------------------
vec3 starLayer(vec3 d, float scale, float prob, float bright) {
  vec3 p = d * scale;
  vec3 id = floor(p);
  float sel = hash13(id + 17.17);
  if (sel > prob) return vec3(0.0);
  vec3 f = fract(p) - 0.5;
  vec3 rnd = hash33(id);
  vec3 offset = (rnd - 0.5) * 0.72;
  float dc = length(f - offset);
  float ang = dc / scale;                        // approx angular distance (rad)
  float sigma0 = 0.0016;
  float sigma = max(sigma0, uPixAngle * 1.1);    // widen PSF at low res -> stable stars
  float b = exp(-0.5 * ang * ang / (sigma * sigma));
  b *= (sigma0 * sigma0) / (sigma * sigma);      // conserve flux as the PSF widens
  float mag = 0.06 + 3.5 * pow(hash13(id + 3.3), 14.0);
  float temp = mix(2600.0, 14000.0, pow(hash13(id + 7.7), 2.2));
  return blackbody(temp) * (b * mag * bright);
}

vec3 background(vec3 d) {
  vec3 col = vec3(0.0);

  // Milky Way band with emission nebulosity and dust lanes.
  vec3 pole = normalize(vec3(0.22, 0.86, 0.46));
  float lat = dot(d, pole);
  float band = exp(-lat * lat * 16.0);
  float neb  = fbm(d * 3.2 + vec3(7.31, 1.7, 4.2));
  float neb2 = fbm(d * 7.5 - vec3(3.7, 9.1, 1.3));
  float dust = fbm(d * 5.1 + vec3(21.7, 5.2, 13.9));
  float mw = band * (0.28 + 0.72 * smoothstep(0.32, 0.78, neb));
  mw *= mix(1.0, smoothstep(0.62, 0.22, dust), 0.8 * band); // dust absorption
  vec3 mwCol = mix(vec3(0.62, 0.70, 1.00), vec3(1.00, 0.82, 0.62), smoothstep(0.3, 0.8, neb2));
  col += mwCol * mw * uMilkyWay * 0.30;
  col += vec3(0.010, 0.012, 0.022) * pow(neb, 2.0) * uMilkyWay; // faint airglow / nebulosity

  // Three procedural star shells (all lensed for free via the bent exit ray).
  float sd = clamp(uStarDensity, 0.0, 2.5);
  col += starLayer(d, 23.0, 0.07 * sd, 1.2) * uStarBright;
  col += starLayer(d + 11.0, 61.0, 0.10 * sd, 0.6) * uStarBright;
  col += starLayer(d + 37.0, 141.0, 0.14 * sd, 0.28) * uStarBright;
  return col;
}

// -------------------------------- accretion disk -----------------------------
// Shades a geodesic/disk-plane intersection. Returns rgb premultiplied-ish
// emission and alpha; also outputs the relativistic g factor for debug view 6.
vec4 diskShade(vec3 hit, vec3 photonDir, out float gOut, out float turbOut) {
  float rc = length(hit.xz);
  float phi = atan(hit.z, hit.x);

  // Differentially rotating turbulence pattern (Keplerian shear, M = 0.5).
  float omega = 0.7071 * pow(rc, -1.5);
  float ang = phi - omega * uTime * uTurbSpeed;
  vec3 q = vec3(cos(ang), sin(ang), 0.0) * (1.0 + 0.55 * log(rc));
  q.z = 2.6 * log(rc);
  float n = fbm(q * uTurbScale + vec3(0.0, 0.0, uTime * 0.015 * uTurbSpeed));
  turbOut = n;
  float turbB = mix(1.0, 0.25 + 1.9 * smoothstep(0.25, 0.85, n), uTurbAmount);
  float turbA = mix(1.0, 0.20 + 1.3 * smoothstep(0.20, 0.75, n), uTurbAmount);

  // Radial envelope: sharp ISCO edge, long soft outer falloff.
  float env = smoothstep(uDiskInner, uDiskInner + 0.12, rc)
            * pow(clamp(1.0 - smoothstep(uDiskInner, uDiskOuter, rc), 0.0, 1.0), 1.35);

  // Circular-orbit speed measured by a local static observer: v = sqrt(M/(r-2M)).
  float v = clamp(1.0 / sqrt(max(2.0 * (rc - 1.0), 0.10)), 0.0, 0.985);
  vec3 ephi = normalize(vec3(-hit.z, 0.0, hit.x));
  // g = nu_obs/nu_emit for a distant observer; sqrt(1 - 3M/r) folds in
  // gravitational + transverse-Doppler time dilation of the orbiting gas,
  // the denominator is the line-of-sight Doppler term (photon travels -photonDir).
  float grav = sqrt(max(1.0 - 1.5 / rc, 0.02));
  float g = grav / (1.0 + v * dot(ephi, photonDir));
  gOut = g;

  float tempProfile = pow(uDiskInner / rc, 0.75);           // T ~ r^-3/4
  float tObs = uDiskTemp * tempProfile * mix(1.0, g, uRedshift);
  float beam = pow(clamp(g, 0.05, 8.0), uBeaming);          // relativistic beaming I ~ g^p

  float intensity = env * turbB * pow(uDiskInner / rc, 2.0) * beam * 3.2;
  vec3 c = blackbody(tObs) * intensity;
  float alpha = clamp(uDiskDensity * env * turbA * 0.85, 0.0, 1.0);
  return vec4(c, alpha);
}

// -------------------------------- debug helpers ------------------------------
vec3 heat(float t) {
  t = clamp(t, 0.0, 1.0);
  return clamp(vec3(smoothstep(0.35, 0.8, t),
                    smoothstep(0.0, 0.55, t) - smoothstep(0.75, 1.0, t) * 0.5,
                    1.0 - smoothstep(0.2, 0.65, t)), 0.0, 1.0);
}

// -------------------------------- main ---------------------------------------
void main() {
  vec2 ndc = vUv * 2.0 - 1.0;
  float aspect = uResolution.x / uResolution.y;
  vec3 rd = normalize(uCamBasis * vec3(ndc.x * aspect * uTanHalfFov, ndc.y * uTanHalfFov, -1.0));
  vec3 rdInitial = rd;

  vec3 pos = uCamPos;
  vec3 vel = rd;
  vec3 hv = cross(pos, vel);
  float h2 = dot(hv, hv);

  float escR = max(40.0, length(uCamPos) + 5.0);
  bool wantDisk = (uDebug != 2);
  bool wantBg   = (uDebug != 1);

  vec3 col = vec3(0.0);
  float trans = 1.0;         // transmittance for front-to-back compositing
  float crossings = 0.0;     // number of disk-plane crossings (image order)
  float minR = 1e9;
  float gFirst = -1.0;
  float turbFirst = 0.0;
  float stepsUsed = 0.0;
  bool captured = false;
  bool escaped = false;

  for (int i = 0; i < MAXSTEPS; i++) {
    if (i >= uSteps) break;
    float r = length(pos);
    minR = min(minR, r);
    if (r < 1.0) { captured = true; break; }
    if (r > escR && dot(pos, vel) > 0.0) { escaped = true; break; }

    // Adaptive step: fine near the photon sphere, coarse far away.
    float dt = uStepScale * clamp(0.45 * r, 0.10, 4.5);

    // Velocity-Verlet update of the geodesic ODE.
    vec3 a1 = geoAccel(pos, h2);
    vec3 prevPos = pos;
    pos += vel * dt + a1 * (0.5 * dt * dt);
    vec3 a2 = geoAccel(pos, h2);
    vel += (a1 + a2) * (0.5 * dt);
    stepsUsed += 1.0;

    // Disk lives in the y = 0 plane; detect sign change and shade the crossing.
    if (wantDisk && prevPos.y * pos.y < 0.0) {
      float f = prevPos.y / (prevPos.y - pos.y);
      vec3 hitP = mix(prevPos, pos, f);
      float rc = length(hitP.xz);
      if (rc > uDiskInner && rc < uDiskOuter) {
        crossings += 1.0;
        float gC, turbC;
        vec4 e = diskShade(hitP, normalize(vel), gC, turbC);
        if (gFirst < 0.0) { gFirst = gC; turbFirst = turbC; }
        col += trans * e.rgb * e.a;
        trans *= (1.0 - e.a);
        if (trans < 0.015) break;
      }
    }
  }

  vec3 exitDir = normalize(vel);
  if (!captured && !escaped) {
    // Ran out of steps: near-critical orbit -> capture, otherwise let it escape.
    if (minR < 1.7 && length(pos) < 6.0) captured = true; else escaped = true;
  }
  if (escaped && wantBg) col += trans * background(exitDir);

  // ------------------------------ debug views --------------------------------
  if (uDebug >= 3) {
    vec3 dbg = vec3(0.0);
    if (uDebug == 3) dbg = heat(crossings / 4.0);                       // disk image order
    else if (uDebug == 4) dbg = heat(stepsUsed / float(uSteps));        // integrator cost
    else if (uDebug == 5) dbg = captured ? vec3(0.0) : heat(clamp(1.0 - (minR - 1.0) / 6.0, 0.0, 1.0)); // periapsis
    else if (uDebug == 6) dbg = gFirst < 0.0 ? vec3(0.02) : heat(clamp((gFirst - 0.3) / 1.4, 0.0, 1.0)); // g factor
    else if (uDebug == 7) dbg = heat(acos(clamp(dot(rdInitial, exitDir), -1.0, 1.0)) / PI); // deflection angle
    else if (uDebug == 8) dbg = vec3(turbFirst);                        // disk turbulence field
    else if (uDebug == 9) dbg = captured ? vec3(0.6, 0.0, 0.0) : exitDir * 0.5 + 0.5; // exit direction
    gl_FragColor = vec4(dbg, 1.0);
    return;
  }

  gl_FragColor = vec4(col, 1.0);
}
`;

// ---------------------------------------------------------------------------
// Post-processing
// ---------------------------------------------------------------------------
export const BRIGHT_FRAG = /* glsl */ `
varying vec2 vUv;
uniform sampler2D tInput;
uniform float uThreshold;
uniform float uKnee;
void main() {
  vec3 c = texture2D(tInput, vUv).rgb;
  float l = max(c.r, max(c.g, c.b));
  float w = smoothstep(uThreshold, uThreshold + max(uKnee, 1e-3), l);
  gl_FragColor = vec4(c * w, 1.0);
}
`;

export const BLUR_FRAG = /* glsl */ `
varying vec2 vUv;
uniform sampler2D tInput;
uniform vec2 uDir;      // (1,0) or (0,1)
uniform vec2 uTexel;
uniform float uSigma;
uniform int uRadius;    // <= 12
void main() {
  vec3 sum = texture2D(tInput, vUv).rgb;
  float wsum = 1.0;
  for (int i = 1; i <= 12; i++) {
    if (i > uRadius) break;
    float fi = float(i);
    float w = exp(-0.5 * fi * fi / (uSigma * uSigma));
    vec2 off = uDir * uTexel * fi;
    sum += (texture2D(tInput, vUv + off).rgb + texture2D(tInput, vUv - off).rgb) * w;
    wsum += 2.0 * w;
  }
  gl_FragColor = vec4(sum / wsum, 1.0);
}
`;

export const COMPOSITE_FRAG = /* glsl */ `
varying vec2 vUv;
uniform sampler2D tScene;
uniform sampler2D tB0;
uniform sampler2D tB1;
uniform sampler2D tB2;
uniform sampler2D tB3;
uniform sampler2D tB4;
uniform float uBloomStrength;
uniform float uBloomRadius;
uniform float uExposure;
uniform float uVignette;
uniform float uGrain;
uniform float uGrainSeed;
uniform float uCA;          // chromatic aberration strength
uniform vec2  uResolution;
uniform int   uBypass;      // 1 -> raw data view (debug modes 3..9)

float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float bw(float f) { return mix(f, 1.2 - f, uBloomRadius); }

vec3 fetchHDR(vec2 uv) {
  vec3 c = texture2D(tScene, uv).rgb;
  c += uBloomStrength * (bw(1.0) * texture2D(tB0, uv).rgb +
                         bw(0.8) * texture2D(tB1, uv).rgb +
                         bw(0.6) * texture2D(tB2, uv).rgb +
                         bw(0.4) * texture2D(tB3, uv).rgb +
                         bw(0.2) * texture2D(tB4, uv).rgb);
  return c;
}

// Narkowicz ACES filmic fit (manual tonemap — renderer tonemapping is off).
vec3 aces(vec3 x) {
  return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 lin2srgb(vec3 c) {
  return mix(c * 12.92, 1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055,
             step(0.0031308, c));
}

void main() {
  if (uBypass == 1) {
    vec3 raw = texture2D(tScene, vUv).rgb;
    gl_FragColor = vec4(lin2srgb(clamp(raw, 0.0, 1.0)), 1.0);
    return;
  }

  vec2 fc = vUv - 0.5;
  float r2 = dot(fc, fc);

  // Subtle radial chromatic aberration (applied to scene + bloom together).
  vec2 caOff = fc * r2 * uCA * 0.012;
  vec3 c;
  c.r = fetchHDR(vUv - caOff).r;
  c.g = fetchHDR(vUv).g;
  c.b = fetchHDR(vUv + caOff).b;

  c *= uExposure;
  c *= 1.0 - uVignette * smoothstep(0.12, 0.62, r2);  // pre-tonemap vignette keeps blacks deep
  c = aces(c);

  // Zero-mean luminance-weighted film grain (never lifts true blacks).
  float g = hash12(vUv * uResolution + vec2(uGrainSeed, uGrainSeed * 1.618)) - 0.5;
  float luma = clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
  c += g * uGrain * (0.2 + 0.8 * (1.0 - luma)) * step(0.001, luma);
  c = max(c, 0.0);

  gl_FragColor = vec4(lin2srgb(c), 1.0);
}
`;

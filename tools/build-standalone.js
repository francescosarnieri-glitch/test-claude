// Bundle GARGANTUA into a single self-contained HTML page for claude.ai Artifacts.
// Strategy: all ES modules are concatenated into one inline <script type="module">.
// three.module.js's trailing `export { ... }` becomes `const THREE = { ... }`;
// every other import/export is stripped (all symbols end up in one module scope).
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');

// --- three ------------------------------------------------------------------
let three = read('vendor/three.module.js');
const expMatch = three.match(/\nexport\s*\{([\s\S]*?)\};?\s*$/);
if (!expMatch) throw new Error('three export statement not found');
if (/\sas\s/.test(expMatch[1])) throw new Error('aliased exports need handling');
three = three.slice(0, expMatch.index) +
  '\nconst THREE = {' + expMatch[1] + '};\n';

// --- OrbitControls ----------------------------------------------------------
let orbit = read('vendor/OrbitControls.js');
orbit = orbit.replace(/^import\s*\{[\s\S]*?\}\s*from\s*'three';\s*/m, '');
orbit = orbit.replace(/export\s*\{\s*OrbitControls\s*\};?/, '');

// --- app modules ------------------------------------------------------------
const stripModule = src => src
  .replace(/^import[\s\S]*?from\s*['"][^'"]+['"];\s*$/gm, '')
  .replace(/^export\s+(const|class|function|let)\s/gm, '$1 ')
  .replace(/^export\s*\{[^}]*\};?\s*$/gm, '');

const shaders = stripModule(read('js/shaders.js'));
const presets = stripModule(read('js/presets.js'));
const ui = stripModule(read('js/ui.js'));
const audio = stripModule(read('js/audio.js'));
const main = stripModule(read('js/main.js'));

const css = read('css/style.css');

// Body markup + help table lifted from index.html between the canvas and the
// module script tag, so the two stay in sync with the repo version.
const html = read('index.html');
const bodyMatch = html.match(/<canvas[\s\S]*?(?=<script type="module")/);
if (!bodyMatch) throw new Error('body extraction failed');
const body = bodyMatch[0];

// Each chunk keeps its own scope (three and OrbitControls collide on private
// top-level names like _ray); an IIFE returns only the chunk's public symbols.
const wrap = (src, ret, decl) =>
  `const ${decl} = (() => {\n${src}\n;return ${ret};\n})();`;

const js = [
  three,
  wrap(orbit, 'OrbitControls', 'OrbitControls'),
  wrap(shaders, '{ FSQ_VERT, BLACKHOLE_FRAG, BRIGHT_FRAG, BLUR_FRAG, COMPOSITE_FRAG }',
    '{ FSQ_VERT, BLACKHOLE_FRAG, BRIGHT_FRAG, BLUR_FRAG, COMPOSITE_FRAG }'),
  wrap(presets, '{ PARAM_DEFS, PRESETS, QUALITY_PROFILES, DEBUG_NAMES, CINEMATIC_NAMES }',
    '{ PARAM_DEFS, PRESETS, QUALITY_PROFILES, DEBUG_NAMES, CINEMATIC_NAMES }'),
  wrap(ui, 'UI', 'UI'),
  wrap(audio, 'AudioEngine', 'AudioEngine'),
  `{\n${main}\n}`,
  `document.getElementById('help-close').addEventListener('click', () =>
     document.getElementById('help').classList.add('hidden'));`,
].join('\n;\n');
if (js.includes('</script')) throw new Error('script-breaking sequence in bundle');

// Charset-proof the page: escape every non-ASCII char so rendering is correct
// regardless of what charset the serving wrapper declares.
const escJS = s => s.replace(/[\u0080-\uffff]/g,
  c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
const escHTML = s => s.replace(/[\u0080-\uffff]/g,
  c => '&#x' + c.charCodeAt(0).toString(16) + ';');

const page = `<title>GARGANTUA &#x2014; Black Hole Raytracer</title>
<style>
${css.replace(/[\u0080-\uffff]/g, '-')}</style>
${escHTML(body)}<script type="module">
${escJS(js)}
</script>
`;

const out = path.join(ROOT, 'dist', 'GARGANTUA.html');
fs.writeFileSync(out, page);
console.log('wrote', out, (page.length / 1024).toFixed(0) + ' KiB');

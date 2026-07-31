/**
 * Prepara la cartella www/ per Capacitor.
 *
 * L'APK non contiene una seconda copia della dashboard scritta a mano: prende
 * quella del backend, cosi' interfaccia web e app restano sempre identiche.
 * L'unica differenza e' l'indirizzo del backend, che dentro l'app non puo'
 * essere `location.origin` (li' sarebbe http://localhost) e viene quindi
 * iniettato qui in fase di build.
 */
import { mkdirSync, copyFileSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const webDir = join(here, '..', '..', 'backend', 'memescan', 'web');
const outDir = join(here, '..', 'www');

if (!existsSync(join(webDir, 'index.html'))) {
  console.error(`Dashboard non trovata in ${webDir}`);
  process.exit(1);
}

mkdirSync(outDir, { recursive: true });

// L'URL puo' arrivare dall'ambiente (locale o CI). Senza, l'app parte e chiede
// l'indirizzo nella scheda Setup al primo avvio.
const backendUrl = (process.env.MEMESCAN_BACKEND_URL || '').replace(/\/$/, '');

let html = readFileSync(join(webDir, 'index.html'), 'utf8');

// I percorsi assoluti /static/... non esistono dentro l'APK: diventano relativi.
html = html.replaceAll('"/static/', '"./');

html = html.replace(
  '<script>',
  `<script>window.MEMESCAN_DEFAULT_BASE = ${JSON.stringify(backendUrl)};</script>\n<script>`
);

writeFileSync(join(outDir, 'index.html'), html);

for (const asset of ['manifest.webmanifest', 'icon.svg']) {
  if (existsSync(join(webDir, asset))) {
    copyFileSync(join(webDir, asset), join(outDir, asset));
  }
}

console.log(
  `www/ pronta${backendUrl ? ` · backend predefinito: ${backendUrl}` : ' · backend da configurare al primo avvio'}`
);

import { mkdir, copyFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const out = resolve(root, 'dist');

await mkdir(out, { recursive: true });
for (const file of ['index.html', 'style.css', 'app.js']) {
  await copyFile(resolve(root, file), resolve(out, file));
}
console.log('[nekoplay] web assets -> dist/');

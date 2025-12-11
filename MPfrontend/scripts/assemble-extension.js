// scripts/assemble-extension.js
const fs = require('fs');
const path = require('path');
const { copyFileSync, mkdirSync, existsSync, readdirSync, statSync } = fs;

const root = path.resolve(__dirname, '..');
const dist = path.join(root, 'dist');
const browserDir = path.join(dist, 'browser'); // lo que produces con ng build
const src = path.join(root, 'src');

function copyRecursive(srcPath, destPath) {
  if (!existsSync(srcPath)) return;
  const stat = statSync(srcPath);
  if (stat.isFile()) {
    mkdirSync(path.dirname(destPath), { recursive: true });
    copyFileSync(srcPath, destPath);
    console.log('copied file', srcPath, '->', destPath);
    return;
  }
  // directorio
  mkdirSync(destPath, { recursive: true });
  for (const entry of readdirSync(srcPath)) {
    copyRecursive(path.join(srcPath, entry), path.join(destPath, entry));
  }
}

// 1) Buscar index.html dentro de dist o dist/browser
function findIndexHtml(base) {
  if (!existsSync(base)) return null;
  const entries = readdirSync(base);
  for (const e of entries) {
    const p = path.join(base, e);
    if (statSync(p).isFile() && /^index(\..*)?\.html$/.test(e)) return p;
  }
  // buscar en subfolders
  for (const e of entries) {
    const p = path.join(base, e);
    if (statSync(p).isDirectory()) {
      const found = findIndexHtml(p);
      if (found) return found;
    }
  }
  return null;
}

const indexSrc = findIndexHtml(dist) || findIndexHtml(browserDir);
if (!indexSrc) {
  console.error('No se encontró index.html en dist o dist/browser. Ejecuta ng build primero.');
  process.exit(1);
}

// 2) Copiar todos los archivos de la carpeta que contiene index.html al root dist/
//    (por ejemplo dist/browser/* -> dist/)
const indexFolder = path.dirname(indexSrc);
console.log('Index encontrado en', indexSrc, ' => copiando contenidos de', indexFolder);

for (const entry of readdirSync(indexFolder)) {
  const srcPath = path.join(indexFolder, entry);
  const destPath = path.join(dist, entry);
  // evita sobrescribir directorio browser/ en sí, copia su contenido al root dist
  if (statSync(srcPath).isDirectory()) {
    copyRecursive(srcPath, destPath);
  } else {
    copyFileSync(srcPath, destPath);
  }
}

// 3) Copiar manifest, background, content-script y assets desde src
const extras = ['manifest.json', 'background.js', 'content-script.js'];
for (const f of extras) {
  const s = path.join(src, f);
  if (existsSync(s)) {
    copyFileSync(s, path.join(dist, f));
    console.log('Copiado', f);
  } else {
    console.warn('No existe en src:', f);
  }
}

// 4) Copiar carpeta assets si existe
const assetsSrc = path.join(src, 'assets');
const assetsDest = path.join(dist, 'assets');
if (existsSync(assetsSrc)) {
  copyRecursive(assetsSrc, assetsDest);
  console.log('Assets copiados');
} else {
  console.warn('No existe src/assets; no se copiaron assets');
}

console.log('Ensamblado completado en', dist);

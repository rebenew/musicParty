// scripts/fix-base-href.js
const fs = require('fs');
const path = require('path');

const distRoot = path.resolve(__dirname, '..', 'dist');

function findIndexHtml(dir) {
  if (!fs.existsSync(dir)) return null;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  // buscar index.html o index.*.html
  for (const e of entries) {
    if (e.isFile() && /^index(\..*)?\.html$/.test(e.name)) return path.join(dir, e.name);
  }
  // si no hay, buscar en la primera subcarpeta que contenga html
  for (const e of entries) {
    if (e.isDirectory()) {
      const candidate = findIndexHtml(path.join(dir, e.name));
      if (candidate) return candidate;
    }
  }
  return null;
}

const indexPath = findIndexHtml(distRoot);

if (!indexPath) {
  console.error('No se encontró index.html en', distRoot);
  process.exit(1);
}

let html = fs.readFileSync(indexPath, 'utf8');
if (/<base href=/i.test(html)) {
  html = html.replace(/<base href=".*?">/i, '<base href="./">');
} else {
  html = html.replace(/<head([^>]*)>/i, `<head$1>\n  <base href="./">`);
}
fs.writeFileSync(indexPath, html, 'utf8');
console.log('Se actualizó base href en', indexPath);

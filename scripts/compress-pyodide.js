#!/usr/bin/env node
/*
 * Brotli-precompress the large Pyodide assets.
 *
 * MainActivity's JadexAssetClient serves `<name>.br` with
 * `Content-Encoding: br` whenever the sibling exists, so this shrinks the APK
 * and speeds up the interpreter's cold start without any runtime cost.
 *
 *   node scripts/compress-pyodide.js          # write .br files
 *   node scripts/compress-pyodide.js --check  # verify they exist and are fresh
 */
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const IDE = path.join(__dirname, "..", "app", "src", "main", "assets", "ide");
const TARGETS = [
  "pyodide/pyodide.asm.wasm",
  "pyodide/pyodide.asm.js",
  "pyodide/python_stdlib.zip",
  "pyodide/pyodide-lock.json",
  "python.js",
];

const check = process.argv.includes("--check");
let failed = false;
let saved = 0;

for (const rel of TARGETS) {
  const src = path.join(IDE, rel);
  if (!fs.existsSync(src)) continue;
  const dst = src + ".br";
  const srcStat = fs.statSync(src);

  if (check) {
    if (!fs.existsSync(dst) || fs.statSync(dst).mtimeMs < srcStat.mtimeMs) {
      console.error(`stale or missing: ${rel}.br`);
      failed = true;
    }
    continue;
  }

  const raw = fs.readFileSync(src);
  const out = zlib.brotliCompressSync(raw, {
    params: {
      [zlib.constants.BROTLI_PARAM_QUALITY]: 11,
      [zlib.constants.BROTLI_PARAM_SIZE_HINT]: raw.length,
    },
  });
  fs.writeFileSync(dst, out);
  saved += raw.length - out.length;
  const pct = ((1 - out.length / raw.length) * 100).toFixed(1);
  console.log(
    `${rel}: ${(raw.length / 1048576).toFixed(2)}MB -> ${(out.length / 1048576).toFixed(2)}MB (-${pct}%)`
  );
}

if (check) {
  if (failed) {
    console.error("run: node scripts/compress-pyodide.js");
    process.exit(1);
  }
  console.log("brotli assets are fresh");
} else {
  console.log(`total saved: ${(saved / 1048576).toFixed(2)}MB`);
}

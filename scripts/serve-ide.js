#!/usr/bin/env node
/*
 * Dev server that mirrors MainActivity.JadexAssetClient exactly: same COOP/COEP
 * headers, same .br negotiation, same MIME table. Lets the IDE be exercised in a
 * desktop browser with the same cross-origin-isolation guarantees the WebView has.
 *
 *   node scripts/serve-ide.js [port]
 */
const http = require("http");
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..", "app", "src", "main", "assets");
const PORT = parseInt(process.argv[2] || "8080", 10);

const MIME = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".css": "text/css",
  ".wasm": "application/wasm",
  ".json": "application/json",
  ".zip": "application/zip",
  ".png": "image/png",
  ".svg": "image/svg+xml",
};

http
  .createServer((req, res) => {
    let rel = decodeURIComponent(req.url.split("?")[0]);
    if (rel === "/") rel = "/ide/index.html";
    if (rel.includes("..")) {
      res.writeHead(400).end("bad path");
      return;
    }
    const file = path.join(ROOT, rel);
    const ext = path.extname(file);
    const headers = {
      "Content-Type": MIME[ext] || "application/octet-stream",
      "Cross-Origin-Opener-Policy": "same-origin",
      "Cross-Origin-Embedder-Policy": "require-corp",
      "Cross-Origin-Resource-Policy": "same-origin",
      "Cache-Control": "no-cache",
    };

    const accepts = String(req.headers["accept-encoding"] || "").includes("br");
    if (accepts && fs.existsSync(file + ".br")) {
      headers["Content-Encoding"] = "br";
      res.writeHead(200, headers);
      fs.createReadStream(file + ".br").pipe(res);
      return;
    }
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404, headers).end("not found");
      return;
    }
    res.writeHead(200, headers);
    fs.createReadStream(file).pipe(res);
  })
  .listen(PORT, "0.0.0.0", () => {
    console.log(`Jadex IDE on http://0.0.0.0:${PORT}/ide/index.html`);
  });

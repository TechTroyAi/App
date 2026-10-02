#!/usr/bin/env node
/*
 * Live Preview server for NumStrike (Mental Math Status & Secret Technique Trainer).
 * Serves app/src/main/assets/math/ at / and serves the compiled Android APK at
 * /artifacts/NumStrike-v1.0.0-installable.apk for one-tap download.
 */
const http = require("http");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.join(__dirname, "..");
const MATH_ROOT = path.join(REPO_ROOT, "app", "src", "main", "assets", "math");
const PORT = parseInt(process.argv[2] || "8080", 10);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".apk": "application/vnd.android.package-archive",
};

http
  .createServer((req, res) => {
    let urlPath = decodeURIComponent((req.url || "/").split("?")[0]);
    if (urlPath.includes("..")) {
      res.writeHead(400).end("Bad Request");
      return;
    }

    // Serve APK artifacts directly for one-click download
    if (urlPath.startsWith("/artifacts/")) {
      const apkFile = path.join(REPO_ROOT, urlPath);
      if (fs.existsSync(apkFile) && fs.statSync(apkFile).isFile()) {
        const fname = path.basename(apkFile);
        res.writeHead(200, {
          "Content-Type": "application/vnd.android.package-archive",
          "Content-Disposition": `attachment; filename="${fname}"`,
          "Content-Length": fs.statSync(apkFile).size,
          "Cache-Control": "no-cache",
        });
        fs.createReadStream(apkFile).pipe(res);
        return;
      }
    }

    if (urlPath === "/" || urlPath === "/index.html" || urlPath === "/math" || urlPath === "/math/") {
      urlPath = "/index.html";
    } else if (urlPath.startsWith("/math/")) {
      urlPath = urlPath.slice("/math".length);
    }

    const file = path.join(MATH_ROOT, urlPath);
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404, { "Content-Type": "text/plain" }).end("Not Found");
      return;
    }

    const ext = path.extname(file);
    res.writeHead(200, {
      "Content-Type": MIME[ext] || "application/octet-stream",
      "Cache-Control": "no-cache",
    });
    fs.createReadStream(file).pipe(res);
  })
  .listen(PORT, "0.0.0.0", () => {
    console.log(`NumStrike Live Demo running on http://0.0.0.0:${PORT}`);
  });

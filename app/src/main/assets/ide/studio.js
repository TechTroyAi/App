(function () {
  window.JadexStudio = window.JadexStudio || {};
  var native = window.JadexNative;
  var lastErrorLine = 0;
  var idleTimer = null;

  function nativeCall(name, a, b) {
    try {
      if (!native || typeof native[name] !== "function") return null;
      if (b !== undefined) return native[name](a, b);
      if (a !== undefined) return native[name](a);
      return native[name]();
    } catch (e) { return null; }
  }

  // Splash is a whisper, not a lock: it breathes once and leaves.
  var splash = document.getElementById("splash");
  if (splash) {
    var dismiss = function () { splash.classList.add("gone"); };
    setTimeout(dismiss, 650);
    splash.addEventListener("pointerdown", dismiss);
  }

  var consoleEl = document.getElementById("console");
  if (consoleEl && !consoleEl.textContent) {
    var w = document.createElement("span");
    w.className = "ok";
    w.textContent = "jadex · by Troy\n";
    consoleEl.appendChild(w);
  }

  function pulse() {
    var st = document.getElementById("status");
    if (!st) return;
    st.classList.add("pulse");
    setTimeout(function () { st.classList.remove("pulse"); }, 220);
    nativeCall("haptic");
  }

  function bumpIdle() {
    document.body.classList.remove("quiet");
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function () { document.body.classList.add("quiet"); }, 10000);
  }
  ["pointerdown", "keydown", "touchstart"].forEach(function (ev) {
    window.addEventListener(ev, bumpIdle, { passive: true });
  });
  bumpIdle();

  window.JadexStudio.plot = function (data) {
    var pane = document.getElementById("plot-pane");
    var cv = document.getElementById("plot-cv");
    if (!pane || !cv || !data) return;
    pane.classList.remove("hidden");
    var x = data.x || [];
    var y = data.y || [];
    var w = cv.width = cv.clientWidth || 640;
    var h = cv.height = 220;
    var ctx = cv.getContext("2d");
    ctx.fillStyle = "#070a08";
    ctx.fillRect(0, 0, w, h);
    if (!y.length) return;
    var minY = Math.min.apply(null, y), maxY = Math.max.apply(null, y);
    if (minY === maxY) { minY -= 1; maxY += 1; }
    ctx.strokeStyle = "#1c2a24";
    ctx.beginPath();
    ctx.moveTo(36, 12); ctx.lineTo(36, h - 24); ctx.lineTo(w - 12, h - 24);
    ctx.stroke();
    ctx.strokeStyle = "#2ee59b";
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (var i = 0; i < y.length; i++) {
      var px = 36 + (i / Math.max(y.length - 1, 1)) * (w - 52);
      var py = 12 + (1 - (y[i] - minY) / (maxY - minY)) * (h - 36);
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    }
    ctx.stroke();
    ctx.fillStyle = "#6e8a7c";
    ctx.font = "11px sans-serif";
    ctx.fillText(String(maxY), 4, 16);
    ctx.fillText(String(minY), 4, h - 28);
  };
  var plotClose = document.getElementById("plot-close");
  if (plotClose) plotClose.onclick = function () {
    document.getElementById("plot-pane").classList.add("hidden");
  };

  var keep = document.getElementById("keep-on");
  if (keep) keep.onchange = function () { nativeCall("keepScreen", keep.checked); };

  window.JadexStudio.markError = function (line) {
    lastErrorLine = line || 0;
    var g = document.getElementById("gutter");
    if (g) g.classList.toggle("errline", !!line);
  };

  var origSave = window.saveFiles;
  // history + disk
  function snapshot(name, body) {
    try {
      var key = "jadex-history-v1";
      var all = JSON.parse(localStorage.getItem(key) || "{}");
      all[name] = all[name] || [];
      all[name].unshift({ t: Date.now(), body: body });
      all[name] = all[name].slice(0, 20);
      localStorage.setItem(key, JSON.stringify(all));
    } catch (e) {}
    nativeCall("saveFile", name, body);
  }
  window.JadexStudio.restore = function () {
    var name = document.getElementById("file-chip").textContent;
    try {
      var all = JSON.parse(localStorage.getItem("jadex-history-v1") || "{}");
      var list = all[name] || [];
      if (list[1]) {
        var code = document.getElementById("code");
        code.value = list[1].body;
        code.dispatchEvent(new Event("input"));
      }
    } catch (e) {}
  };

  var code = document.getElementById("code");
  if (code) {
    code.addEventListener("input", function () {
      snapshot(document.getElementById("file-chip").textContent, code.value);
    });
    code.addEventListener("click", function (e) {
      if (!(e.ctrlKey || e.metaKey)) return;
      var pos = code.selectionStart;
      var m = code.value.slice(0, pos).match(/[A-Za-z_][A-Za-z0-9_]*$/);
      var rest = code.value.slice(pos).match(/^[A-Za-z0-9_]*/);
      var name = (m ? m[0] : "") + (rest ? rest[0] : "");
      if (!name || !window.TroyPython) return;
      var hit = TroyPython.outline(code.value).filter(function (o) { return o.name === name; })[0];
      if (hit && window.gotoLine) window.gotoLine(hit.line);
    });
  }

  var repl = document.getElementById("repl-in");
  if (repl) {
    repl.addEventListener("keydown", function (e) {
      if (e.key !== "Enter") return;
      window.jadexStdinQueue = window.jadexStdinQueue || [];
      window.jadexStdinQueue.push(repl.value);
    });
  }

  var testBtn = document.getElementById("btn-test");
  if (testBtn) testBtn.onclick = async function () {
    pulse();
    var files = {};
    try { files = JSON.parse(localStorage.getItem("jadex-files-v1") || "{}"); } catch (e) {}
    var src = "import unittest, sys\n";
    src += "loader = unittest.defaultTestLoader\n";
    src += "suite = unittest.TestSuite()\n";
    Object.keys(files).forEach(function (n) {
      if (n.indexOf("test_") === 0 && n.slice(-3) === ".py") {
        src += "suite.addTests(loader.loadTestsFromName(" + JSON.stringify(n.replace(/\.py$/, "")) + "))\n";
      }
    });
    src += "r = unittest.TextTestRunner(verbosity=2).run(suite)\n";
    src += "print('OK' if r.wasSuccessful() else 'FAILED')\n";
    if (window.runCode && window.JadexCPython && JadexCPython.ready) {
      var codeEl = document.getElementById("code");
      var prev = codeEl.value;
      codeEl.value = src;
      await runCode(false);
      codeEl.value = prev;
    } else if (window.runCode) runCode(false);
  };

  var runBtn = document.getElementById("btn-run");
  if (runBtn) {
    var prev = runBtn.onclick;
    runBtn.addEventListener("click", function () { pulse(); });
  }

  if (window.COMMANDS) {
    window.COMMANDS.push({ name: "Restore previous save", run: window.JadexStudio.restore });
    window.COMMANDS.push({ name: "Go to definition (Ctrl+click)", run: function () {} });
  }

  try {
    var listed = nativeCall("listFiles");
    if (listed && listed.length > 2) {
      var names = JSON.parse(listed);
      var store = {};
      try { store = JSON.parse(localStorage.getItem("jadex-files-v1") || "{}"); } catch (e) {}
      names.forEach(function (n) {
        var body = nativeCall("readFile", n);
        if (body && !store[n]) store[n] = body;
      });
      localStorage.setItem("jadex-files-v1", JSON.stringify(store));
    }
  } catch (e) {}
})();

# README for AI Agents: How to Build `.exe` Programs

*Practical playbook for coding agents (and humans) that need to turn source
code into a Windows `.exe` — including the exact recipe this repo used for
its screen recorder.*

---

## 0. The one rule that matters most

**You usually cannot "cross-compile" a Windows exe with the same toolchain
the language normally uses.** Each ecosystem falls into one of three camps:

| Camp | Ecosystems | What it means for you |
| --- | --- | --- |
| ✅ **Cross-compiles natively** | Go, Zig, C/C++ (MinGW), .NET console apps | You can build the exe from a Linux/macOS sandbox in one command. |
| ⚠️ **Cross-compiles with caveats** | Rust (`windows-gnu` target), .NET WinForms/WPF | Works, but GUI frameworks / MSVC-dependent code often require building on Windows. |
| ❌ **Cannot cross-compile** | Python (PyInstaller/Nuitka/cx_Freeze), Electron, most interpreted-language packagers | You **must** run the packager on Windows — a real PC, a Windows VM, or a GitHub Actions `windows-latest` runner. |

So your first decision as an agent is: *which ecosystem is the program
written in, and can that ecosystem build a Windows exe from the machine I'm
on?* If not, either (a) port the tool to Go/C/Rust, or (b) delegate the
Windows build to CI (§6 — the most reliable trick).

---

## 1. Quick decision table

| The program is… | Best tool to make the exe | Build command (condensed) | Runs where |
| --- | --- | --- | --- |
| A CLI/utility, small GUI tool | **Go** | `GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o app.exe .` | Any OS → exe |
| Performance-critical, systems-y | **Rust** | `cargo build --release --target x86_64-pc-windows-gnu` | Any OS (gnu target) or Windows (msvc) |
| C/C++ | **MinGW-w64 / Zig cc** | `x86_64-w64-mingw32-gcc -o app.exe main.c -static` or `zig cc -target x86_64-windows-gnu` | Any OS → exe |
| C# / .NET console app | **dotnet publish** | `dotnet publish -r win-x64 --self-contained -p:PublishSingleFile=true` | Linux/macOS OK (console); WinForms/WPF need Windows |
| Python script/app | **PyInstaller** (on Windows!) | `pyinstaller --onefile app.py` | **Windows only** (or CI runner) |
| Node.js GUI app | **electron-builder** | `npx electron-builder --win portable` | Windows (Linux partially, with Wine) |
| Anything, when all else fails | **GitHub Actions windows runner** | see §6 | CI builds it for you |

---

## 2. Go — the agent's default for small exes

Go is the friendliest option for agents because **one command cross-compiles
a zero-dependency exe from any OS**, and Go programs that stick to the
stdlib have no runtime for users to install.

```sh
# from Linux/macOS/Windows:
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -ldflags "-s -w" -o App.exe .
```

- `GOOS=windows GOARCH=amd64` → 64-bit Windows (use `386`/`arm64` for other CPUs).
- `CGO_ENABLED=0` → pure-Go build; mandatory when cross-compiling (cgo would need a Windows C compiler).
- `-ldflags "-s -w"` → strips debug info (~30% smaller).
- `-trimpath` → reproducible, machine-independent paths.

**No Go toolchain installed and package mirrors blocked?** The `go-bin`
package on PyPI ships the full prebuilt toolchain:
`pip install go-bin` gives you a working `go` (that is how this repo's
screen recorder was built in a locked-down sandbox).

Platform-specific code goes in files with build tags — `capture_windows.go`
(`//go:build windows`) and `capture_other.go` (`//go:build !windows`) — so
tests and `go vet` still run on your Linux sandbox while the exe gets the
real Windows implementation. Useful pure-Go Windows syscall pattern:

```go
var (
    modUser32          = syscall.NewLazyDLL("user32.dll")
    procGetAsyncKeyState = modUser32.NewProc("GetAsyncKeyState")
)

func keyDown(vk int) bool {
    r, _, _ := procGetAsyncKeyState.Call(uintptr(vk))
    return r&0x8000 != 0
}
```

For GUI apps look at Fyne (cross-platform) or `lxn/walk` (Windows-native);
both compile to a normal exe. Icons/version-info metadata: embed a `.syso`
resource built with `github.com/josephspurrier/goversioninfo` or
`go-winres` — the exe picks it up automatically at link time.

**Verify your exe without Windows:**
```sh
python3 - <<'EOF'
d = open('App.exe','rb').read()
assert d[:2] == b'MZ'
pe = int.from_bytes(d[0x3c:0x40],'little')
assert d[pe:pe+4] == b'PE\0\0'
print('machine =', hex(int.from_bytes(d[pe+4:pe+6],'little')))  # 0x8664 = x64
EOF
```

---

## 3. Python — PyInstaller on Windows (or CI)

PyInstaller (and Nuitka, cx_Freeze, py2exe) **bundle** your interpreter +
libraries into an exe, so they must run **on Windows**. Never try to fake it
from Linux.

```powershell
# on a Windows machine / VM / CI runner:
pip install pyinstaller
pyinstaller --onefile --noconsole --icon app.ico --name MyApp main.py
# output: dist\MyApp.exe
```

Flags agents should know:

- `--onefile` — single portable exe (slower startup; unpacks to %TEMP%).
- `--noconsole` / `--windowed` — hide the console for GUI apps (keep the console for CLI tools).
- `--add-data "src;dst"` — bundle data files (`;` on Windows, `:` elsewhere).
- `--hidden-import some.module` — required for dynamically imported modules PyInstaller can't see.
- `--clean` — clear PyInstaller's cache when weird stale-bundle errors appear.

Known pain points:

1. **Antivirus false positives** — unsigned PyInstaller onefile exes are
   frequently flagged. Mitigate: build with `--onedir`, sign the exe (§7),
   submit the file to Microsoft as a false positive, or switch the tool to Go.
2. **"Failed to execute script"** — build with `--debug all` and a console to
   see the traceback; usually a missing `--hidden-import` or data file.
3. **Antivirus blocks the build itself** — exclude the `dist`/`build` folders.

---

## 4. .NET / C#

```sh
# console apps cross-compile fine from Linux/macOS:
dotnet publish -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true

# output: bin/Release/net8.0/win-x64/publish/App.exe
```

- `--self-contained` → includes the .NET runtime (no install needed, ~70 MB);
  drop it for a small exe that requires the .NET Desktop Runtime.
- `-r win-arm64` for ARM Windows.
- **WinForms/WPF GUI apps must be built on Windows** (`windows-latest`
  runner) — the Windows Desktop SDK/reference assemblies aren't available on
  Linux.
- Trimming: add `-p:PublishTrimmed=true` to shrink self-contained builds
  (test carefully — reflection-heavy code breaks).

---

## 5. Rust and C/C++

**Rust** (install the target once with `rustup target add x86_64-pc-windows-gnu`,
plus `apt install gcc-mingw-w64-x86-64` and a cargo linker config):

```sh
cargo build --release --target x86_64-pc-windows-gnu
# target/x86_64-pc-windows-gnu/release/app.exe
```
Crates that shell out to MSVC or build Windows GUIs (e.g. `wry`, some
`windows-rs` setups) are happier built on a Windows runner with the default
`x86_64-pc-windows-msvc` target.

**C/C++** with MinGW-w64 (or `zig cc`, which vendors a full mingw
toolchain — `pip install ziglang`, then `python3 -m ziglang cc -target
x86_64-windows-gnu main.c -o app.exe -static`):

```sh
x86_64-w64-mingw32-gcc -O2 -o app.exe main.c -static -lgdi32 -luser32
```
Link Windows libs explicitly (`-lgdi32 -luser32 -lwinmm` …) and prefer
`-static` so the exe doesn't need MinGW runtime DLLs.

---

## 6. The most reliable trick: GitHub Actions builds the exe for you

When the ecosystem can't cross-compile (Python/Electron/MSVC Rust/WinForms),
or you simply want a **provably native** build, push a workflow and let a
real Windows runner do it. Agents can then read logs and download artifacts
via `gh`:

```yaml
# .github/workflows/windows-exe.yml
name: windows-exe
on:
  push:
    paths: ["src/**", ".github/workflows/windows-exe.yml"]
  workflow_dispatch:

permissions:
  contents: write          # allow pushing the exe back / creating releases

jobs:
  build:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: "3.12" }
      - run: pip install pyinstaller
      - run: pyinstaller --onefile --name MyApp main.py
      - uses: actions/upload-artifact@v4
        with: { name: MyApp-exe, path: dist/MyApp.exe }

      # Optional: commit the exe back onto the branch so the agent can
      # `git pull` it even when artifact downloads are blocked:
      - run: |
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add dist/MyApp.exe
          git commit -m "CI: build MyApp.exe [skip ci]" || echo nothing to commit
          git push
```

Agent workflow:

```sh
git push origin your-branch
gh run list --branch your-branch --limit 1
gh run watch <run-id> --exit-status          # or poll gh run view
gh run view <run-id> --log-failed            # when something breaks
gh run download <run-id> -n MyApp-exe -D dist/
```

This repo uses exactly this pattern: `.github/workflows/windows-exe.yml`
builds `Aureus.exe` on `windows-latest` and commits it to
`artifacts/` on every change to `aureus/**` — proof that the
committed exe really is what the source produces.

### Repo conventions for built binaries (used here)

- Generated binaries are **gitignored** by default; a **whitelisted
  "milestone"** artifact is committed under `artifacts/` (see `.gitignore`).
- Each milestone gets a version in its filename
  (`ScreenRecorder-v1.0.0.exe`) and its SHA-256 recorded in
  `artifacts/README.md`.
- CI rebuilds the artifact from source on every relevant change; the
  workflow re-commits the fresh binary so the repo never drifts from source.

---

## 7. Code signing, SmartScreen and antivirus (what users will hit)

An unsigned exe runs fine, but users see **"Windows protected your PC"**
(SmartScreen) → *More info → Run anyway*. For an agent building internal or
open-source tools, this is usually acceptable; for distribution, consider:

1. **Self-signed certificate** (free, tests signing, still untrusted): on Windows,
   ```powershell
   New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=Dev" -CertStoreLocation Cert:\CurrentUser\My
   signtool sign /fd SHA256 /a file.exe
   ```
2. **OV code-signing certificate** (~$100–400/yr, e.g. Certum, Sectigo) — removes most warnings after reputation builds.
3. **EV certificate** — instant SmartScreen reputation, requires hardware token.
4. Open-source alternative: publish reproducible builds + SHA-256 in the
   release notes (this repo's approach) so users can verify what they run.

If Defender/SmartScreen flags a clean PyInstaller exe, report it via
Microsoft's sample submission portal — false positives do get fixed.

---

## 8. Post-build checklist for agents

- [ ] **PE sanity** — starts with `MZ`, `PE\0\0` at the e_lfanew offset, machine `0x8664` (x64) or `0xAA64` (arm64).
- [ ] **No missing DLLs** — `objdump -p app.exe | grep "DLL Name"` (MinGW/Rust-gnu); Go/dotnet self-contained exes list only system DLLs.
- [ ] **Run it** — on a Windows VM/runner at least once; `-h`/`--help` smoke test in CI.
- [ ] **Check size** — Go ~2 MB, PyInstaller ~10–60 MB, .NET self-contained ~70 MB; users notice 500 MB exes.
- [ ] **Version the filename** and record the SHA-256 (`sha256sum app.exe`) next to the artifact.
- [ ] **Don't commit build caches** (`build/`, `dist/`, `__pycache__/`, `target/`) — only the whitelisted milestone binary.
- [ ] **Write the README** — what it does, keys/flags, SmartScreen note, how to rebuild.

## 9. Common errors → fixes

| Error | Likely cause → fix |
| --- | --- |
| `gcc: error creating executable … cannot run 'ld'` when cross-building Go | cgo is on → add `CGO_ENABLED=0`, or port the native part to syscalls |
| PyInstaller `Failed to execute script` | Missing hidden import/data → rebuild with `--debug all --console` to see the traceback |
| `0xc000007b` when the exe starts | 32/64-bit DLL mix — rebuild all native deps for x64 |
| `VCRUNTIME140.dll not found` | MSVC-runtime-dependent build → ship/install VC++ redist, or switch to `-static` (MinGW) / Go |
| SmartScreen blocks everything | Unsigned exe — expected; sign it or document "More info → Run anyway" |
| `go: download … dial tcp: connection refused` in sandbox | No network → `pip install go-bin` (bundled toolchain) or vendor modules with `go mod vendor` on a connected machine |
| Electron-builder fails on Linux for `--win` | Needs Wine for some steps → build on `windows-latest` instead |
| `dotnet publish` GUI exe won't build on Linux | WinForms/WPF need Windows → move that step to CI (§6) |

## 10. Worked example — this repo's screen recorder

`screen-recorder/` shows the whole playbook in miniature:

1. **Pure-Go capture** via `syscall.NewLazyDLL` (user32/gdi32) in
   `capture_windows.go`, with a `!windows` stub so Linux `go vet`/`go test`
   still run.
2. **Zero-dependency GIF streaming** writer (`gif.go`, `palette.go`) — no
   external Go modules, so the cross-compile works with no network at all.
3. **Cross-compiled locally**: `GOOS=windows go build` in a sandbox whose
   Go toolchain came from PyPI (`pip install go-bin`) because go.dev was
   blocked.
4. **Verified without Windows**: PE-header check, stdlib + Pillow decode of
   a generated GIF, byte-exact round-trip tests, per-frame CPU benchmark.
5. **CI reproduces it natively**: `windows-exe.yml` rebuilds the exe on a
   real Windows runner and commits it back to `artifacts/`.

Copy that structure for any small Windows utility: it gives you a fast local
build loop *and* a native, verifiable CI artifact.

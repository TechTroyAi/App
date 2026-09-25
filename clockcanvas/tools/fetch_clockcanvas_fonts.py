#!/usr/bin/env python3
"""Fetch the bundled ClockCanvas fonts (and their OFL licenses) into the project.

The sandbox this app was authored in has no access to Google Fonts' CDN, so
fonts are pulled through the GitHub API blob endpoint from google/fonts, which
is where the upstream, license-clean copies live. Only fonts whose license
explicitly permits redistribution (SIL OFL 1.1) are vendored here.

Usage:  python3 clockcanvas/tools/fetch_clockcanvas_fonts.py [--force]
"""
from __future__ import annotations

import json
import os
import sys
import urllib.request

# This file lives in clockcanvas/tools/, so its parent is the module root.
MODULE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST_FONTS = os.path.join(MODULE, "app", "src", "main", "assets", "fonts")
DEST_LIC = os.path.join(MODULE, "app", "src", "main", "assets", "licenses")

# (google/fonts folder, file name, shipped file name)
FONTS = [
    ("ofl/inter", "Inter[opsz,wght].ttf", "inter_variable.ttf"),
    ("ofl/spacegrotesk", "SpaceGrotesk[wght].ttf", "space_grotesk_variable.ttf"),
    ("ofl/bebasneue", "BebasNeue-Regular.ttf", "bebas_neue_regular.ttf"),
    ("ofl/playfairdisplay", "PlayfairDisplay[wght].ttf", "playfair_display_variable.ttf"),
    ("ofl/sourcecodepro", "SourceCodePro[wght].ttf", "source_code_pro_variable.ttf"),
]


def api(path: str) -> object:
    req = urllib.request.Request(
        "https://api.github.com" + path,
        headers={"Accept": "application/vnd.github+json", "User-Agent": "ClockCanvasFonts/1.0"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def blob_raw(sha: str) -> bytes:
    req = urllib.request.Request(
        f"https://api.github.com/repos/google/fonts/git/blobs/{sha}",
        headers={"Accept": "application/vnd.github.v3.raw", "User-Agent": "ClockCanvasFonts/1.0"},
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read()


def find_sha(folder: str, name: str) -> str:
    entries = api(f"/repos/google/fonts/contents/{folder}?ref=main")
    for e in entries:
        if e["name"] == name:
            return e["sha"]
    print(f"   available: {[e['name'] for e in entries][:12]}")
    raise SystemExit(f"font file not found: {folder}/{name}")


def main() -> None:
    force = "--force" in sys.argv
    os.makedirs(DEST_FONTS, exist_ok=True)
    os.makedirs(DEST_LIC, exist_ok=True)
    for folder, name, out in FONTS:
        dest = os.path.join(DEST_FONTS, out)
        lic_dest = os.path.join(DEST_LIC, f"{out.replace('.ttf', '')}_OFL.txt")
        if os.path.exists(dest) and not force:
            print(f"skip   {out} (already present)")
            continue
        print(f"fetch  {folder}/{name} ...")
        data = blob_raw(find_sha(folder, name))
        with open(dest, "wb") as f:
            f.write(data)
        print(f"        {len(data):,} bytes -> {os.path.relpath(dest, MODULE)}")
        try:
            lic = blob_raw(find_sha(folder, "OFL.txt"))
            with open(lic_dest, "wb") as f:
                f.write(lic)
            print(f"        license -> {os.path.relpath(lic_dest, MODULE)} ({len(lic):,} bytes)")
        except SystemExit:
            print("        WARNING: OFL.txt not found in upstream folder")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Package one remastered tower family into its delivery zip.

Usage:
    python3 tools/remaster_package.py <family> <accent> <turret_resource>

Reads artwork/sprite-remaster/<family>/*.png (game files with exact resource
names) plus artwork/sprite-remaster/raw/<family>/*_source.png, writes the
family README, builds artwork/sprite-remaster/<family>.zip and deletes the
loose directories (only zips are kept in the workspace).
"""

from __future__ import annotations

import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path("artwork/sprite-remaster")

README = """# {F} TOWER - 1.4.3 sprite remaster (code-friendly contract)

Style (locked): flat 2D pixel art, strict bird's-eye top-down, medieval clockwork
forge-fantasy - charcoal iron, dark olive stone, muted brass, rivets/gears,
near-black outline, hard pixel clusters, NO 3D / perspective / bevels / gradients.
Single energy accent: {accent}.

## Files -> exact resource names
Copy the four game files into app/src/main/res/drawable-nodpi/ under these EXACT names
(SpriteCatalog.kt strings; a wrong name crashes at load):

| file in zip | resource name | loaded by |
|---|---|---|
| game/tower_{f}_base.png | tower_{f}_base | SpriteCatalog.load("tower_{f}_base") |
| game/{turret}.png | {turret} | SpriteCatalog.loadStrip("{turret}") |
| game/projectile_{f}.png | projectile_{f} | SpriteCatalog.loadStrip("projectile_{f}") |
| game/impact_{f}.png | impact_{f} | SpriteCatalog.loadStrip("impact_{f}") |
| sources/*.png | - | raw AI sheets, reference/re-edit only, NOT code-referenced |

## Format rules
- Frames: 128x128 square; strips horizontal 384x128 (loadStrip needs width % height == 0,
  frameCount = width / height = 3). Renderer scales, no gameplay code changes.
- Background fully transparent; rotation pivot = exact frame centre in EVERY frame
  (verified <=1px drift by tools/sprite_contract_audit.py).
- turret order: [idle][fire, muzzle flash][recoil]; projectile/impact: 3-stage sequence.
- No baked backdrop, no text/labels/scenery. Do NOT add unreferenced resources
  (*_anim / *_strip names are not in SpriteCatalog).

## Re-editing: slice / transparency / resize
Regenerate from sources with:
  tools/remaster_process.py icon  <source.png> <out128.png>
  tools/remaster_process.py strip <sheet3.png> <out384x128.png>
then verify: tools/sprite_contract_audit.py <dir>  (must report 0 failures)
"""


def main() -> None:
    fam, accent, turret = sys.argv[1], sys.argv[2], sys.argv[3]
    fam_dir = ROOT / fam
    raw_dir = ROOT / "raw" / fam
    game = [
        f"tower_{fam}_base.png",
        f"{turret}.png",
        f"projectile_{fam}.png",
        f"impact_{fam}.png",
    ]
    zpath = ROOT / f"{fam}.zip"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(
            f"{fam}/README.md",
            README.format(F=fam.upper(), f=fam, accent=accent, turret=turret),
        )
        for g in game:
            z.write(fam_dir / g, f"{fam}/game/{g}")
        if raw_dir.exists():
            for p in sorted(raw_dir.glob("*.png")):
                z.write(p, f"{fam}/sources/{p.name}")
    shutil.rmtree(fam_dir)
    if raw_dir.exists():
        shutil.rmtree(raw_dir)
    print(zpath, "built; loose PNGs deleted")


if __name__ == "__main__":
    main()

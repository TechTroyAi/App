#!/usr/bin/env python3
"""Audit a sprite drop against the 1.4.3 sprite contract.

Implements the rules from docs/TOWER_BASE_SPRITE_BRAINSTORM.md §3 so a new art
drop can be checked before it ever reaches Android resources:

  T3.2 transparent means transparent   -> all four corners of every frame alpha 0
  T3.1 one object per frame            -> largest connected ink blob >= 60% of ink
                                          (tower bases / turrets / enemies / utilities)
  T3.3 pivot stability                 -> frame-to-frame bbox-centre drift <= 2 px (turrets)
  T3.1 minimum presence                -> ink bbox >= 24 px per side (towers / enemies /
                                          utilities); >= 8 px for projectile / impact frames
  T3.5 family uniqueness               -> silhouette Jaccard < 0.85 between families of
                                          the same category

Usage:
    python3 tools/sprite_contract_audit.py <dir-with-pngs> [<dir2> ...]

Exits 0 when every check passes, 1 otherwise. Pure review tool: reads only.
"""

from __future__ import annotations

import sys
import itertools
from collections import deque
from pathlib import Path

from PIL import Image
import numpy as np

MIN_BLOB_SHARE = 0.60
DRIFT_LIMIT = 2.0
BIG_BBOX = 24
SMALL_BBOX = 8
JACCARD_LIMIT = 0.85

# prefix -> (kind, checks)
def category(stem: str) -> str:
    if stem.startswith("tower_") and stem.endswith("_turret"):
        return "turret"
    if stem.startswith("tower_") and stem.endswith("_base"):
        return "base"
    if stem.startswith("projectile_"):
        return "projectile"
    if stem.startswith("impact_"):
        return "impact"
    if stem.startswith(("enemy_", "utility_", "trap_", "landmark_")):
        return "structure"
    return "icon"


def frames(img: Image.Image) -> list[np.ndarray]:
    w, h = img.size
    assert w % h == 0, f"not a square-frame strip: {w}x{h}"
    a = np.array(img.convert("RGBA"))[..., 3]
    return [a[:, i * h:(i + 1) * h] for i in range(w // h)]


def blob_share(alpha: np.ndarray) -> float:
    ink = alpha > 8
    total = int(ink.sum())
    if total == 0:
        return 0.0
    seen = np.zeros_like(ink)
    best = 0
    ys, xs = np.nonzero(ink)
    for y, x in zip(ys, xs):
        if seen[y, x]:
            continue
        q = deque([(y, x)])
        seen[y, x] = True
        size = 0
        while q:
            cy, cx = q.popleft()
            size += 1
            for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
                if 0 <= ny < alpha.shape[0] and 0 <= nx < alpha.shape[1] and ink[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    q.append((ny, nx))
        best = max(best, size)
    return best / total


def bbox_center_size(alpha: np.ndarray):
    ys, xs = np.nonzero(alpha > 8)
    if len(xs) == 0:
        return None
    return ((xs.min() + xs.max() + 1) / 2, (ys.min() + ys.max() + 1) / 2,
            int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1))


def silhouette(img: Image.Image) -> np.ndarray:
    return np.array(img.convert("RGBA"))[..., 3] > 8


def audit_file(path: Path) -> list[str]:
    problems: list[str] = []
    cat = category(path.stem)
    img = Image.open(path)
    frs = frames(img)

    for i, fa in enumerate(frs):
        # terrain tiles are seamless opaque ground by design
        if not path.stem.startswith("terrain_"):
            corners = [fa[0, 0], fa[0, -1], fa[-1, 0], fa[-1, -1]]
            if any(c > 8 for c in corners):
                problems.append(f"F{i}: opaque corner (baked backdrop)")
        info = bbox_center_size(fa)
        if info is None:
            problems.append(f"F{i}: empty frame")
            continue
        _, _, bw, bh = info
        need = BIG_BBOX if cat in ("turret", "base") else SMALL_BBOX
        if bw < need or bh < need:
            problems.append(f"F{i}: ink bbox {bw}x{bh} < {need}px")
        if cat in ("turret", "base"):
            share = blob_share(fa)
            if share < MIN_BLOB_SHARE:
                problems.append(f"F{i}: {share:.0%} in largest blob (strip-inside-frame?)")

    if cat == "turret" and len(frs) > 1:
        centers = [bbox_center_size(fa) for fa in frs]
        for i in range(1, len(centers)):
            if centers[i] and centers[i - 1]:
                dx = centers[i][0] - centers[i - 1][0]
                dy = centers[i][1] - centers[i - 1][1]
                if abs(dx) > DRIFT_LIMIT or abs(dy) > DRIFT_LIMIT:
                    problems.append(f"F{i}: pivot drift ({dx:+.1f},{dy:+.1f}) > {DRIFT_LIMIT}px")
    return problems


def main() -> int:
    dirs = [Path(p) for p in sys.argv[1:]] or [Path(".")]
    files: list[Path] = []
    for d in dirs:
        files += sorted(d.glob("*.png"))
    if not files:
        print("no PNGs found in", dirs)
        return 1

    failures = 0
    masks: dict[str, dict[str, np.ndarray]] = {}
    for path in files:
        cat = category(path.stem)
        problems = audit_file(path)
        tag = "ok  " if not problems else "FAIL"
        if problems:
            failures += 1
        print(f"{tag} {path.name:38s} {cat:10s} " + ("; ".join(problems) if problems else ""))
        if cat in ("turret",):
            masks.setdefault(cat, {})[path.stem] = silhouette(Image.open(path))

    for cat, fams in masks.items():
        for (na, ma), (nb, mb) in itertools.combinations(sorted(fams.items()), 2):
            inter = int((ma & mb).sum())
            uni = int((ma | mb).sum())
            j = inter / uni if uni else 0.0
            if j > JACCARD_LIMIT:
                failures += 1
                print(f"FAIL dup-silhouette {cat}: {na} vs {nb} J={j:.2f} > {JACCARD_LIMIT}")

    print()
    print(f"{len(files)} files audited, {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

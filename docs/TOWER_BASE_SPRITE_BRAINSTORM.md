# Tower + Base Sprite Brainstorm (2026-09-01)

**Status: brainstorm only — no code changed.** Goal: find a sprite contract that makes the
tower/base layer *easy to code against*. Everything below is measured from the repo as of
`74158aa` (see `artwork/audit/` for the rendered evidence boards).

## 0. How to read the evidence

- `artwork/audit/audit-part1..3.png` — every tower family at 6× nearest: base + 3 turret frames,
  red cross = the rotation pivot the game uses (frame centre), numbers = per-frame visual-centre
  drift in px.
- `artwork/audit/audit-rotation.png` — each turret spun 8 ways at gameplay scale. Bolt/Cannon
  pivot cleanly; the 1.4 families spin like playing cards.
- `artwork/audit/audit-base.png` — player core (landmark_core) and enemy gate at gameplay scale.
- `artwork/audit/sources-overview.png` — the 1.4 turret sources with the pipeline's third-split
  lines overlaid: sources are fine, the split never happens (see §2).

## 1. The two sprite generations

**Gen-1 (bolt, frost, cannon, ember, beacon + gate/core): genuinely good.**
One object per 64×64 frame, transparent backdrop, pivot ≈ visual centre (drift ≤ 0.5 px on
bolt/frost), recoil/glow animated *inside* the frame, base and top are separate layers.
The rotation test shows them turning correctly. **These are the reference implementation.**

**Gen-2 (thorn, lance, mire, gale, sunforge, lodestone, howl, vitriol, gravebolt, aegis): broken
at the asset level, before any code ever sees them.**

1. **Strip-inside-frame.** Every Gen-2 turret frame contains the *whole 3-pose lineup shrunk to
   miniatures* (see audit-part2/3). Root cause, verified in `tools/process_style_batch.py`:
   `process_strip()` only splits 3 panels when `width/height >= 2.2`; all Gen-2 turret sources are
   1408×768 (ratio **1.83**), so the code takes the "single pose → triplicate" branch and bakes the
   entire lineup into each of the 3 frames.
2. **Opaque backdrops baked in.** Lance/mire frames carry an opaque grey plate, gravebolt a cream
   plate, thorn white speckle noise (audit-rotation.png). `neutralize_background()` only remaps
   *near-black* backdrops; the sources' checkerboard/cream "transparency preview" grounds sail
   through → rotating solid rectangles in game.
3. **Duplicate silhouettes.** Lance vs Mire alpha-mask Jaccard = **0.988**, Mire/Gravebolt 0.938,
   Lance/Gravebolt 0.926 — the same shape re-tinted. Players can't tell three different towers apart.
4. **Pedestal-inside-turret.** The miniatures include their own little stand, then the game draws
   the family base *underneath* → double pedestal, wrong pivot height.
5. **No size hierarchy.** `isolate()` force-crops and scales *everything* to a 58 px box and
   re-centres, so a starter spire and a boss-killer occupy the same footprint (turret ink:
   thorn 0.69 vs aegis 0.13), and per-frame auto-centering makes recoil wobble instead of animate
   (ember +8 px centre jump F0→F1; vitriol drifts (−0.5,+7)/(+2,+6.5)/(−3,+5)).

## 2. Why the code is painful today (symptoms of §1)

`GameView.kt drawTower()` is a 15-branch `when` of hand-tuned magic numbers compensating for art:
base at `y + 0.05*tile, size 0.88`; tops at 0.86/0.82/0.70/0.78 with per-family y-offsets
(−0.10 … 0.0). The seven 1.4 towers share one copy-pasted line (`y − 0.06*tile`,
`size 0.78 + sin(...)`), i.e. they were never tuned. `SpriteCatalog.kt` hand-lists 15 base vals +
15 top vals + 15 projectiles + 15 impacts instead of deriving from `TowerKind`. The player base
expresses health/danger purely through *code-drawn* glow circles because the core art is a static
3-frame icon. Adding tower #16 today = new magic numbers + new catalog vals + hoping the pipeline
cooperates. That is exactly what we're fixing.

## 3. Proposed sprite contract (the brainstorm core)

Keep the existing formats (64×64 icons, 192×64 A/B/C strips) — the *rules* change:

**T3.1 One object per frame.** A turret frame contains exactly one turret. No mini-lineups,
no pedestal inside the top layer (the base layer owns the ground).

**T3.2 Transparent means transparent.** Corners alpha = 0; no checkerboard/cream/grey plates.
Checkerboard is treated as removable background by the pipeline, not shipped.

**T3.3 Pivot contract.** ROTATE tops: mechanical pivot at pixel (32,32), barrel pointing +x,
muzzle tip reaching x ≥ 50 so rotation + projectile spawn align with zero code offsets.
PULSE tops (ember/beacon style): mass centred (32,34). Frame-to-frame centre drift ≤ 1 px
(no auto-recentering; the pipeline must *crop once using frame A's bbox* for all frames).

**T3.4 Size ladder.** Replace the global 58 px box with per-class target extents, e.g.
starter tops fill ~44 px, mid ~50 px, heavy ~56 px; bases sit on a shared baseline
(ground ellipse centred y ≈ 42, max width 56). Restores silhouette hierarchy.

**T3.5 Family uniqueness.** Any two turret silhouettes with Jaccard > 0.85 fail review
(a 20-line CI check, same math as this audit).

**T3.6 Pipeline gates.** After processing, auto-fail a sprite when: any frame has opaque
corner pixels; any frame's ink bbox < 24 px; a frame contains >1 disconnected large blob
(the strip-inside-frame bug); centre drift > 1 px. Broken art can then never reach an APK again.

## 4. The BASE (player core + gate) brainstorm

Today: core = one 64×64 icon in one tile, 3 ambient frames; all health/danger state is code glow.
Ideas, cheapest first:

- **B1 Damage-stage art (quick win).** Keep 1 tile; author 3 core states (intact / cracked /
  burning) as separate sprites; the game swaps sprite per HP band instead of painting glow.
  The existing heartbeat pulse can stay as seasoning on top.
- **B2 Directional gate mouth.** Gate art currently faces "up-ish" while enemies leave rightward;
  author the mouth aligned to the path heading + a 1-frame "swallow" flash when a leak happens.
- **B3 Multi-tile keep (big swing).** Core becomes a 2×2-tile layered building
  (foundation + core + banner topper), with the 4 cells reserved on the grid. Biggest "base"
  fantasy, but touches pathing/placement code — park it until the contract work lands.

## 5. Fix strategy options (to decide together)

- **Option A — Re-author Gen-2 tops (recommended).** Sources are 3-pose lineups with baked
  backdrops and shared silhouettes; re-processing alone can fix the split but not 3/4/5 of §1.
  New sources per template (T3.x) for the 10 tops; keep the 10 bases that are already decent
  (lance/mire bases are actually nice iso art) and re-center them to the T3.4 baseline.
- **Option B — Reprocess only (cheap, incomplete).** Fix the ratio gate (panel-detect by
  background-column scan, not aspect) + checkerboard neutralizing. Wins: real single poses,
  transparent backdrops. Loses: dup silhouettes and pedestal-inside-top remain.
- **Option C — Keep code rotation vs 8-way prerendered tops.** Recommendation: keep code
  rotation (Gen-1 proves it reads fine at nearest-neighbour) and spend the art budget on
  silhouettes instead of directions.

## 6. Later, when we DO code (not now)

One `TowerArtSpec` row per family (base, top, mode ROTATE/PULSE, extent class) derived from
`TowerKind`; `drawTower()` collapses to ~10 lines; `SpriteCatalog` stops hand-listing;
audit checks live in `ci/verify-forgeworks.sh`. That is the "easier to code" payoff.

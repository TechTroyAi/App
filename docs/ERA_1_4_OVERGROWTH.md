# Era 1.4 — Overgrowth Expansion (roadmap)

Living plan for the large content dump. **Do not ship as one mega-APK.** Phased like 1.3 / E1–E3.

## Vision

The hold grows: wider board, denser threats, deeper forge workshop (crafts, enchants, utilities), and more defensive toys — still **forge-fantasy readable**, not a stat spreadsheet.

## Current baseline (pre-1.4)

| Lane | Count (approx) |
|------|----------------|
| Board | **16×9** + pan/zoom (F0) |
| Towers | **8** + **16** evolutions (F4) |
| Traps | 5 |
| Utilities | ~11 (F3 + prior) |
| Imbuements (enchants) | 9 (F3 Ward/Leech/Surge) |
| Crafted supplies | 14 (C1 + C2) |
| Enemy normals / elites / bosses | F1 blood + F2 named + **F5 pests** (15 normals pool) |

## Target adds (player ask)

| Lane | Add | Notes |
|------|-----|--------|
| Normals | +10 | Clear jobs, not HP clones |
| Elites | +5 | One hard rule each |
| Bosses | +4 | Need **tells** |
| Towers | +10 | Heaviest art — late |
| Utilities | +10 | Medium art, strong verbs |
| Imbuements | +10 | Packs of 2–3 |
| Crafted items | +10 | Workshop icons + rules |
| Board | Larger + **pan/zoom** | Foundation pillar |

## Pillars

### F0 — Wide Hold
**Shipped:** grid **16×9**, gate/core row **4**, pinch zoom + drag pan, chrome screen-fixed, starting blocks **420**, max path **64**.

### F1 — New blood
**Shipped:** Sapper, Mycelial, Needlefly, Gloomkin, Carrion Hulk + elite **Thornback**; wave themes mix the new blood.

### F2 — Named threats
**Shipped:** elites **Grave Mender**, **Pyre Wight**; bosses **Iron Monarch**, **Spore Sovereign** with wind-up tells + banner stings; boss rotation on wave%10 (Overgrowth every 30).
+2 elites, +1–2 bosses with wind-up tells; banner sting.

### F3 — Toolkit
**Shipped:** utilities +4 (Ward Beacon, Battle Banner, Essence Still, Trap Lattice), C2 crafts +3, imbuements Ward/Leech/Surge. See `docs/VERIFICATION_F3.md`.

### F4 — New guns
**Shipped:** +3 towers full kit — **Thorn Spire** (mark), **Shard Lance** (pierce), **Mire Spout** (bog); evo×6; TWR 1/2 paging. See `docs/VERIFICATION_F4.md`.

### F5 — Field pests
**Shipped:** +5 normals — Briar Mite (jam), Rust Tick (rust hex), Drift Seed (slow resist), Hollow Shell (buffer), Wisp Drifter (stealth). See `docs/VERIFICATION_F5.md`.

### F6 — Named threats + C3
**Shipped:** elites Vein Lurker / Mirror Moth; bosses Tidal Root / Ashen Choir; C3 crafts×4; Bulwark + Harvest. See `docs/VERIFICATION_F6.md`.

### F7 — Utils + imbues
**Shipped:** +6 utilities (Aegis/Nursery/Warden/Relay/Bounty/Kiln), +4 imbuements (Volley/Siege/Fortune/Binding). Sprite pipeline aligned via `tools/process_style_batch.py`. See `docs/VERIFICATION_F7.md`.

### F8+ — Towers last
~7 tower full kits remain (heaviest art ~42 sheets).

## Crafts roadmap (+10)

| Pack | Items | Status |
|------|--------|--------|
| **C1** | Splinter Brace, Resin Seal, Cooling Flask | **Shipping** |
| C2 | Overcharge Cell, Focus Lens, Snap Spring | **Shipped** (F3) |
| C3 | Survey Spike, Route Oil, Salt Bundle, Scrap Magnet | **Shipped** (F6) |
| C4 | (flex slot / Blank Stamp variant) | Optional |

## Design rules

- One readable verb per new piece
- Elites/bosses need rules/tells, not only stats
- Towers last (sprite cost)
- Board camera before stuffing 15 towers onto 12×7
- Keep forge palette / 64×64 / strip conventions

## Shipped in this session track

- 1.3 A–D + C (combat → death → UI → board ambient)
- E1–E3 evolution moments
- **1.4 C1 crafts** (see `artwork/style-production/batch-21-crafted-supplies-c1/`)
- **1.4 F0 Wide Hold** (see `artwork/style-production/batch-22-wide-hold-f0/`)
- **1.4 F1 New blood** (see `artwork/style-production/batch-23-new-blood-f1/`)
- **1.4 F2 Named threats** (see `docs/VERIFICATION` F2 notes)
- **1.4 F3 Toolkit** (see `docs/VERIFICATION_F3.md`)
- **1.4 F4 New guns** (see `artwork/style-production/batch-26-new-guns-f4/`, `docs/VERIFICATION_F4.md`)
- **1.4 F5 Field pests** (see `artwork/style-production/batch-27-field-pests-f5/`, `docs/VERIFICATION_F5.md`)
- **1.4 F6 threats + C3** (see `artwork/style-production/batch-28-f6-threats-c3/`, `docs/VERIFICATION_F6.md`)
- **1.4 F7 utils + imbues** (see `artwork/style-production/batch-29-utils-imbues-f7/`, `docs/VERIFICATION_F7.md`)


## F3 status (2026-08-28)
**Shipped.** Installable SHA `058f8a7da7e08e565e340467ecc866248850b367b1d19b18258f85b8d8a938db`.  
Utils +4, C2 crafts +3, imbuements +3. Next: **F4 towers**.

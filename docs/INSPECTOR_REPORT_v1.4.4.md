# 🔍 Blockhold Defense — Deep Inspection Report

**Game:** Blockhold Defense: Endless Pathforge · **Version inspected:** v1.4.4 (`versionCode 18`, app id `ai.techtroy.blockhold`)
**Inspected artifact:** `artifacts/Blockhold-Defense-v1.4.4-installable.apk` (SHA-256 `e1e33918...9fdea`)
**Scope:** Android host, all Kotlin source (`GameView.kt` 6,792 lines, `GameModels.kt`, `SpriteCatalog.kt`, `MainActivity.kt`, `AudioEngine.kt`), sprite/art catalogs, design docs, verification records, and GitHub history (PRs #1–#15).
**Date:** 2026-09-03

> **Method note.** This pass was done on the *code, data tables, sprite catalogs, docs, and git history* — the places where an inspector can verify facts, not just eyeball pixels. No gameplay screenshots arrived in the workspace this session, and I have no image-vision on this channel, so I could not pixel-grade your PNG frames or in-run screenshots. Everything below is either **confirmed in source** or clearly marked as an assumption to double-check on-device. Send screenshots and I'll grade them against the HUD geometry described in §3.

---

## 1. Verdict (executive summary)

**Build quality: genuinely strong.** This is a coherent, self-contained, offline Android tower-defense with real systemic depth — route-forging, three inventory shelves, structure economy, corruption/mutations, an elite & 5-boss roster, 25+ perks, evolutions, imbuements, and a complete fantasy-machinery art pass. Static verification passes clean (v2/v3 signing, DEX integrity, resource table, sprite geometry all green), and the repo's own lint reports **0 errors** across the core sim. Anti-crash scaffolding (crash recorder → recovery screen, safe save migration) shows unusually disciplined engineering for a solo project.

**But an inspector's job is to find what's wrong, not what's right.** I found **2 genuine logic bugs**, **several documentation inaccuracies**, **a design tension in the new Overdrive economy**, and a clear list of polish/scale opportunities. Nothing is a crash-blocker; all are fixable.

**Top 3 things to fix first (P0/P1):**
1. **Surveyor preview lies about which boss is coming** (logic mismatch between the telegraph and the real spawn table).
2. **Perfect-wave "Momentum" streak is silently reset if you clear a wave during an active Overdrive** — punishing the exact play the mechanic rewards.
3. **Doc drift** — README enemy rosters/boss rotation don't match the code (15 normal / 10 elite / 5 boss, Overgrowth only every 30).

---

## 2. Top findings with severity & evidence

### 🔴 P1 — Bug: Surveyor Station / Survey Lens preview predicts the wrong boss
`GameView.kt`

The *actual* boss rotation in the wave builder is keyed on `tier % 5` (lines 2404–2407), but the *telegraph* shown to the player is keyed on `tier % 3` (lines 2428–2429) and never even mentions **Tidal Root** or **Ashen Choir**.

| Wave | Real boss (builder, `tier%5`) | Boss the Survey tells you (`tier%3`) |
|---|---|---|
| 20 (t2) | Spore Sovereign | Spore Sovereign ✅ |
| 30 (t3) | Overgrowth | Overgrowth ✅ |
| 40 (t4) | **Ashen Choir** | Iron Monarch ❌ |
| 50 (t5) | **Overgrowth** | Spore Sovereign ❌ |
| 60 (t6) | Iron Monarch | Iron Monarch ✅ (only lucky overlap) |

Since wave 20 and 30 line up, this bug only bites at **wave 40+** — but at that depth a mis-telegraph on the boss is exactly when a wrong plan costs you a run. Players who invest Blocks in a Surveyor are paying to be misled.

**Fix:** make `surveyPreviewText()` consume the same rotation helper as `buildWaveQueue()` (single source of truth), and include all five named bosses.

### 🔴 P1 — Bug: clearing a wave *during* Overdrive resets your perfect-wave Momentum streak
`GameView.kt` `completeWave()` lines ~1982–1990

```kotlin
if (lives >= livesAtWaveStart && !overdriveActive) {
    perfectWaveStreak += 1
    ...give momentum charge...
} else {
    perfectWaveStreak = 0   // <-- also runs when overdriveActive == true but you took ZERO core damage
}
```

The `!overdriveActive` guard was meant to avoid double-dipping (kills during Overdrive already extend the timer). But it's bolted onto the *same* branch as the streak counter, so the `else` also nukes your streak. Because you'd naturally pop Overdrive to *close out* a wave, the optimal play — a flawless wave finished under an active boost — wipes the Momentum streak you'd earned. Punishes good play; logic should test `lives >= livesAtWaveStart` alone for the streak and gate only the *charge reward* on `!overdriveActive`.

### 🟠 P2 — Doc drift: README rosters no longer match the shipped roster
`README.md`

- README says the 30 enemies are **19 normal / 8 elite / 3 boss**. Source actually ships **15 normal / 10 elite / 5 boss** (verified by counting `elite=true`/`boss=true` in `EnemyKind`, and 30 total mapped in `SpriteCatalog`).
- README's boss section says *"The Overgrowth returns every ten waves."* Source now rotates **five** bosses: Overgrowth every 30, otherwise Iron Monarch / Spore Sovereign / Tidal Root / Ashen Choir by `tier % 5`.
- The `One family` count table and prose are otherwise accurate (30 enemies, 50 HUD PNGs etc.) — the roster split numbers and "every ten waves" text are the stale parts.

**Fix:** refresh the roster/boss prose in README (and ideally move roster generation to derive from the enum so it can't rot).

### 🟠 P2 — Robustness: sprite access uses `Map.getValue` and would *crash mid-wave* on a missing key
`SpriteCatalog.kt` (lint flags 9 warnings) — e.g. `projectiles.getValue(kind)`, `impacts.getValue(kind)`, `imbuements.getValue(kind)`.

Right now every enum constant is validated at load, so it's safe **today**. The risk: the *next* time someone adds a Tower/Enemy/Imbuement and forgets the sprite, the app throws `NoSuchElementException` from inside the combat frame loop instead of degrading. The 1.4-era code deliberately avoided `NoWhenBranchMatchedException` by giving new tower kinds fallback sprites — apply the same discipline to the sprite maps (a defensive getter that returns a neutral placeholder + logs once). Cheap insurance for a game that grows by batch content dumps.

---

## 3. HUD & interface inspection (from the draw code + 50-sprite UI catalog)

Because I can't pixel-grade the PNGs here, this is a *geometry and behavior* audit of what the HUD draws and whether it stays readable/consistent. These are the details I'd want you to sanity-check against real screenshots.

**Top command cluster** (`drawTopBar` / `drawOverdriveButton`):
`[Overdrive] [Reset/Reforge] [Primary Action] [Sound] [Feedback] [Pause]`
- Overdrive button shows a **radial charge arc**, a gold "GO" pulse when full, an active red-orange **drain ring with a live `Ns` countdown**, and — my favorite tiny detail — a **2-frame green→gold colour ramp** on the fill arc as it charges. Nice juice.
- ⚠️ Two small polish notes: the active state reuses the generic *launch* glyph rather than a distinct "boost" glyph, and the numeric charge (e.g. `37`) is rendered *below* the arc with no unit/`/100`, so a fresh player won't know it's a fraction. Add a `/100` or a "%" on the fallback text, or scale the number color to fill.

**Resource chips** (`drawTopStats`) — Blocks ▸ Core(heart) ▸ Wave, in that order, with:
- A cascade of layout fallbacks: 3 chips → 2 → 1 as width shrinks, then a fully **compact landscape mode** (`compactControls`) that swaps verbose action labels (`START`, `STACK`, `CONFIRM`) for `W<n>` or bare icons.
- Live-value pulse on gain (`goldPulse`) and an intentional sprite-icon + short-label design (no wordy "Core health: 12/12" line). Good use of DP-scaling and `topBarHeight`-relative font caps so text never overflows the rail. This is well-executed responsive HUD work.
- ⚠️ A real constraint worth noting: the two left readout clusters (`Forge Charges` / `Evolution Cores` in `drawTopStatus`) and the `Blocks/Core/Wave` chips **share the same strip of screen** and are given priority by a "reserve room first" heuristic (`topStatusRight`). On *ultra-compact* width the phase icon shrinks to 14dp and Forge/Evo numbers drop — meaning on small landscape phones the two currencies that fund the late-game (Forge Charges and Cores) can visually collapse to near-unreadable. Recommend an explicit minimum legibility floor (never below ~8sp) even if it costs chip width.

**Banner, phase status, boards:**
- Phase is represented by **icon + short label** (`ROUTE / BUILD / WAVE ×2 / REFORGE / PERKS`) rather than full sentences — a deliberate and good "reduce copy" choice, but it also means there is **no text anywhere that explains what state you're in for a new player** (see §6 discoverability).
- In-combat the build shelf slides fully off-screen (`buildShelfSlide`), but placed-object inspection panels stay interactive — documented in v1.4.3 as a feature. Clean.
- **No on-screen numeric damage on most hits** — damage labels only ~22% of the time (`damageEnemy`) to cut clutter. Combined with `toInt()` labels (not `formatNumber`), at late waves a 12,500 hit reads as `12500` and a multi-million DPS run shows raw integers that can push a label off readability. Use `formatNumber` for large damage pops.
- Big-number formatting is handled sanely (`1.0K` / `3.2M`) on the top chips and end screen but **not** consistently on damage numbers / floating labels / defense panel (`DMG ${damage.toInt()}`). Standardize on `formatNumber` for anything ≥10k so the readout doesn't overflow its frame on late-game towers.

**Bezel / board:** tarnished brass bezel with corner rivets framing the grid, two seamless tiles (dark low-contrast road so bright towers/enemies pop), animated spawn gate & core reactor damage frames. That's the right readability architecture — keep the road **darker than every play piece** so nothing camouflages at a glance. If any screenshot shows a tower/enemy blending into terrain, that's the first contrast knob to turn.

---

## 4. Gameplay mechanics — what the sim actually does right

**Route forging** — drag gate→adjacent→core, backtrack to undo, locks on reaching core; reforge redraw preview with cost rollback; towers/structures impassable; displaced traps return to exact-item Trap Inventory only when fees + shelf capacity are met. Solid state machine (DIG/BUILD/REFORGE guard rails).

**Wave/stacking model** — genuinely clever: each spawn carries a `sourceWave`, the queue and live-enemy list can hold *several* waves at once, and wave-clear is resolved by ownership (`processClearedWaves`) rather than a linear counter. This correctly supports "launch N+1 while N is alive." Not many hobby TDs get stacked-wave bookkeeping this right.

**Anti-stall counterplay** — elites/bosses smashing the trap under them after 1.75s of stall (`ELITE_TRAP_BREAK_DELAY`), with an orange telegraph ring draining on-screen. Sapper sabotage (2 traps/run), Briar Mite jamming, Rootcaller healing, Mirror Moth reflect, Hollow Shell's HP buffer, etc. — every F-batch enemy has a *readable* counter. Enemies each have 3 distinct animation keyframes (neutral/attack/death). This is real game design, not spreadsheet filler.

**Bounded endless** — health `1.095^wave` until wave 80 then a gentler +6%/wave tail (cap 1e9); speed capped 1.62; reward scale capped 20×; corruption capped. So memory stays bounded **and** the curve has a deliberate long-run shape. Honest, well-engineered "endless."

**Status correctness** — slows stack sensibly (Needlefly/Drift Seed resist, Root/Stun gates movement, path warden × oil × stun multiply cleanly); armor is a mitigation multiplier capped 0.88 with pierce/shred; Overdrive damage + fire-rate perks actually feed the constants that fire towers. The sim has **no obviously wrong formulas** in the hot path — that's high praise.

---

## 5. Balance & economy observations (design-level, flag for tuning)

1. **Overdrive charge is heavily "win-more."** Charge comes from *kills* (+4/+15/+35) and *perfect waves* (momentum). If you're already deleting waves you charge fast; if you're leaking, you never see Overdrive when you need it. Consider a **baseline drip** (e.g. +X per elapsed second of wave) so Overdrive is an occasional comeback tool, not only a snowball.
2. **Overcharge is a money sink with per-rank diminishing DPS-per-block.** Overcharge cost escalates ~`1.24^rank` (and later a hard `min(2e9,…)` wall) while each rank adds only +12% dmg / +4% rate on a linear base. Intentional long-run sink, but combined with `1.095^wave` health the player will eventually hit a **damage wall with no escape** — there's no true infinite. If the design goal is genuinely endless, add a periodic scaling lever (perk/evolution/forge tier) beyond Overcharge; otherwise document that wave ~X is the practical "win" line.
3. **Boss-clear income double-counts on milestone waves** (every 10 is also a multiple of 5): on wave 10, 20, … you get both the `%5` regeneration/core and the `%10` boss +1 core + Forge/cores. That's fine (they stack intentionally) — just confirm you want two core repairs on boss-clear milestones.
4. **Auto-next-wave is 60s** (`AUTO_NEXT_WAVE_DELAY`) — generous enough to not punish inventory/reforge shopping. Reasonable; only note that the countdown label (`countdownLabel`) uses a `ceil`-style conversion, so check it never shows `0S` while a wave is actually about to start without a final beat of warning.
5. **Trap-vs-tower identity is preserved well** (traps on route, tower on clean ground), and Elite anti-trap forces you to *support* traps with stuns/knockback towers — nice interaction depth. Watch that root-snare + trap-line cheese doesn't trivialize non-Sapper waves once players learn to stack roots.

---

## 6. Content completeness (verified against code + sprite catalog)

All fully wired (lint-confirmed 1:1 enum↔sprite mapping): **15 towers**, **5 traps**, **17 structures**, **30 enemies** (15/10/5), **30 evolutions**, **18 crafted supplies**, **16 imbuements**, **27 perks**, **6 corruptions**, **3 resources**, **2 landmarks**, **2 terrain tiles**, **50 HUD/UI sprites**. New Overdrive perks (`OVERDRIVE_MASTERY`, `FORGE_ECHO`) are implemented, not just advertised — including the Overdrive-end "Resonance" volley and a kill-extension timer with a +5s hard cap. The **Forgeworks / Living Path / Inventory** systems all persist in the versioned checkpoint (three shelves + legacy Cache→Trap-shelf migration). This is a **big, coherent** game.

---

## 7. Performance, architecture & code-quality notes

- **Monolith:** `GameView.kt` is ~6,800 lines holding input, sim, save/load, and *all* rendering. It works, but it's the single biggest maintainability risk for the content cadence this project runs at. At minimum extract (a) the save/checkpoint reader, (b) the wave director, (c) the HUD draw pass into their own files — the classes already exist as data models, so it's mostly relocation.
- **Hot-path cost:** per combat frame, each enemy scans *all* traps (`triggerTrapIfNeeded` iterates the full `traps` list to find one on its cell) and each tower scans *all* enemies (`findTarget`). Bounded, but a cell→trap occupancy map and a light spatial target query would remove the quadratic-ish creep in long stacked-wave runs. Not urgent; note it before content doubles.
- **Consistency of data:** all sim state lives in `ArrayList`/`HashMap` and mutates during iteration via careful deferred removal (`removeAll`, deferred `destroyAfterTrigger`, `pendingSpawns`). The code is careful here — keep it that way when adding F-batches.
- **Cosmetic formatting:** minor indentation drift in `GameModels.kt` `Enemy` (`var x/y`, mis-indented `slowTimer`, column-0 `var rootTimer`). Zero functional impact; fix in a cleanup pass.
- **Robustness:** crash recorder + recovery screen (with copy-to-send + reset-data) and a null-safe `GameView` lifecycle is excellent defensive engineering — better than most shipped commercial Android games.

---

## 8. Polish / accessibility / QoL suggestions (impactful, low-risk)

| Area | Suggestion |
|---|---|
| Haptics | Add subtle vibration on core hits / Overdrive activation / trap breaks. `Vibrator` is cheap and massively sells impact on mobile. |
| Audio identity | 15 towers share ~5 effect sounds (Gale/Howl/Vitriol reuse `frost`, Lodestone `beacon`, etc.). The SFX are procedural (`tools/generate_sfx.py`) so you can mint distinct per-family sounds cheaply. Same for the new boss roster. |
| Discoverability | For a game this deep there's **no tutorial and no codex**. You have the copy quality to add short one-line banners on first contact ("Towers stand on clean ground", "Elites smash traps that stall them"). A bestiary/encyclopedia tab in the title screen would pay for itself. |
| Big-number UX | Route all numeric readouts through `formatNumber` (damage pops, defense panel) so nothing overflows its frame at high waves. |
| Overdrive clarity | Add `/100` or a fill % and a distinct "boost" glyph so the new mechanic reads instantly. |
| UI text floor | Enforce a minimum sp on the Forge/Core readouts even on ultra-compact layouts. |
| Confirm-before-burn | On long perfect streaks (Momentum ×N) give a lightweight confirm / "use now?" on Overdrive activation — it's a scarce, timed resource. |
| Multi-touch | Confirm Overdrive tap and camera-pan can't fire from the same gesture (it uses a dedicated rect — verify two-finger pan doesn't mis-trigger it). |

---

## 9. Prioritized fix roadmap

**P0 — correctness (ship these first):**
1. Surveyor boss preview → use the real `tier%5` rotation, include Tidal Root & Ashen Choir.
2. Momentum streak: decouple streak tracking from the `!overdriveActive` gate.

**P1 — robustness & docs:**
3. Defensive sprite getter in `SpriteCatalog` (never crash mid-wave on a missing art).
4. Refresh README roster numbers (15/10/5) and boss-rotation prose.

**P2 — feel & balance (tune on-device):**
5. Baseline Overdrive drip + `/100` label + distinct active glyph.
6. Distinct per-family SFX for the 1.4-era towers/bosses.
7. Haptics on core-hit / Overdrive / trap-break.

**P3 — scale & maintainability:**
8. Split `GameView.kt`; add path→trap occupancy map + spatial target culling; standardize `formatNumber`; add a codex/bestiary.

---

## 10. Bottom line

Blockhold Defense is in great shape for its version — it *feels* like a shipped product, not a prototype. The two P1 logic bugs are the kind that slip through because the game is *so large* that the Surveyor telegraph and the streak counter rarely get exercised together in playtesting. Fix those, refresh the README, and the remaining list is polish. Happy to implement any of the P0–P2 fixes in this branch and open a PR for you to review.

*— Inspector sign-off: code, data tables, art catalog, and git-history audit of v1.4.4 (commit `0121c68`, working tree clean). Awaiting real run screenshots to grade on-screen HUD legibility & pixel fidelity.*

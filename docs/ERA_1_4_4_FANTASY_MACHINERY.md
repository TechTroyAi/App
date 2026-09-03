# v1.4.4 — Fantasy Machinery HUD Life

**Version:** 1.4.4  
**Codename:** Fantasy Machinery HUD Life  
**Date:** 2026-09-03  

## Summary

v1.4.4 breathes life into the HUD with AI-generated Fantasy Machinery sprites and introduces the **Forge Overdrive** mechanic — a player-activated combat boost that rewards aggressive play.

---

## New Features

### 🎮 Forge Overdrive (New Mechanic)

A player-activated burst that temporarily boosts ALL towers:

- **Fire Rate:** +45% faster (×0.55 interval)
- **Damage:** +25% bonus damage (+10% per Overdrive Mastery stack)
- **Duration:** 8 seconds (+50% per Overdrive Mastery stack)
- **Meter:** 100 charge points
- **Charge Sources:**
  - Normal enemy kill: +4 charge
  - Elite kill: +15 charge
  - Boss kill: +35 charge
- **Activation:** Tap the Overdrive button in the top HUD command cluster
- **Visual:** Warm gold glow on all active towers during overdrive
- **HUD:** Radial fill ring around arcane gear icon; pulses gold when full; countdown ring while active

### 🎮 Wave Momentum (New Mechanic)

Clearing a wave without core damage charges overdrive:

- **Base bonus:** +8 charge for a perfect wave
- **Streak bonus:** +2 per consecutive perfect wave (up to +12 additional)
- **Streak 3+:** Floating "MOMENTUM ×N" label on the board
- **Streak 3+ activation:** Extends overdrive duration by up to +4 seconds

### 🎮 Overdrive Kill Chain (New Mechanic)

Kills during active overdrive extend the timer:

- Normal kill: +0.15 seconds
- Elite kill: +0.50 seconds
- Boss kill: +1.20 seconds
- Max extension: +5 seconds beyond base duration
- Floating "+X" labels on 30% of kills during overdrive

### 🎮 Overdrive Resonance (New Mechanic)

When overdrive expires, ALL towers fire a bonus volley:

- Every non-disabled tower resets its cooldown to 0
- Gold particle burst from each tower
- "OVERDRIVE RESONANCE • BONUS VOLLEY" banner

### 🎮 New Forge Perks

| Perk | Effect |
|------|--------|
| **Overdrive Mastery** | +50% overdrive duration, +10% damage per stack |
| **Forge Echo** | All traps pulse 25 damage to nearby enemies on overdrive activation |

### 🎨 AI-Generated Fantasy Machinery HUD Sprites (50 Total)

All HUD buttons, rails, panels, cards, modals, menus, and icons regenerated with deeper Fantasy Machinery detail:

**Batch 1 — Buttons & Rails (10):** Top/bottom rails, primary/secondary/accent/warning buttons (normal + pressed)
**Batch 2 — Panels & Cards (10):** Panels (normal + active), cards (normal + active), modal, build slots, tabs, stat frame
**Batch 3 — Icons & Banner (10):** Banner, resource icons (blocks, core, heart, wave), action icons (launch, route, reforge, pause, evolve)
**Batch 4 — Menu & Actions (10):** Title background, menu panel, crest, disabled states, action icons (store, imbue, recycle, back, upgrade)
**Batch 5 — Final Polish (10):** Menu buttons (primary + secondary, normal + pressed), menu icons (play, continue, sound on/off), category icons (towers, traps)

**Total: 50 sprites, 7.1MB (out of 256 total game sprites at 10.3MB)**

### 📐 HUD Button Relocation

- **Overdrive button** added to the top bar command cluster (between reset/reforge and primary action)
- Command cluster now: `[Overdrive] [Reset/Reforge] [Primary Action] [Sound] [Feedback] [Pause]`
- Overdrive button shows real-time charge progress as a radial fill arc

---

## Technical Changes

### Version
- `versionCode`: 17 → **18**
- `versionName`: "1.4.3" → **"1.4.4"**

### New Constants
```kotlin
OVERDRIVE_MAX = 100f
OVERDRIVE_DURATION = 8.0f
OVERDRIVE_FIRE_RATE_BOOST = 0.55f
OVERDRIVE_DAMAGE_BOOST = 1.25f
```

### New State Variables
```kotlin
overdriveCharge: Float    // 0..OVERDRIVE_MAX
overdriveActive: Boolean  // true while boost is running
overdriveTimer: Float     // seconds remaining
overdriveFlash: Float     // visual flash on activation
overdriveRect: RectF      // hit area for the overdrive button
```

### Modified Functions
- `update()` — ticks overdrive timer
- `fireTower()` — applies OVERDRIVE_DAMAGE_BOOST when active
- Tower fire interval — applies OVERDRIVE_FIRE_RATE_BOOST when active
- `beginEnemyDeath()` — charges overdrive meter on kills
- `drawTopBar()` — draws overdrive button with charge/active states
- `drawTower()` — warm gold glow while overdrive is active
- `onTouchEvent()` — handles overdrive button taps
- `finishRun()` — resets overdrive state
- `computeLayout()` — positions overdrive button in command cluster

---

## Design Philosophy

The Forge Overdrive mechanic creates a **risk/reward decision loop**:
- Save it for a boss wave or use it early to clear a swarm?
- The meter charges from kills, so aggressive defense = faster recharge
- Elites and bosses give big chunks, rewarding focused fire
- The 8-second window is long enough to feel impactful but short enough to matter

The Fantasy Machinery HUD sprites reinforce the **forge-fantasy aesthetic** — every button feels like a piece of arcane machinery rather than a flat UI element.

---

## Next Turns (Sprite Pipeline)

Remaining HUD sprites for v1.4.4 Batch 2+:
- [x] `ui_panel.png` / `ui_panel_active.png` — Inspection panel backgrounds
- [x] `ui_card.png` / `ui_card_active.png` — Perk/evolution card backgrounds
- [x] `ui_modal.png` — Full-screen modal overlay
- [x] `ui_build_slot.png` / `ui_build_slot_selected.png` — Build shelf card frames
- [x] `ui_tab.png` / `ui_tab_selected.png` — Bottom shelf tab skins
- [x] `ui_stat_frame.png` — Resource stat chip frame
- [x] `ui_banner.png` — Floating notification banner
- [x] Resource icons (Blocks, Core, Wave, Heart) — enhanced fantasy machinery style
- [x] Action icons (Launch, Route, Reforge, Pause, Evolve) — enhanced
- [x] Menu sprites (title background, panel, crest, buttons) — enhanced
- [x] Menu icons (play, continue, sound on/off) — enhanced
- [x] Category icons (Towers, Traps) — enhanced
- [x] Disabled states (button, build slot) — enhanced
- [x] Action icons (Store, Imbue, Recycle, Back, Upgrade) — enhanced

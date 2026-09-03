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
- **Damage:** +25% bonus damage
- **Duration:** 8 seconds
- **Meter:** 100 charge points
- **Charge Sources:**
  - Normal enemy kill: +4 charge
  - Elite kill: +15 charge
  - Boss kill: +35 charge
- **Activation:** Tap the Overdrive button in the top HUD command cluster
- **Visual:** Warm gold glow on all active towers during overdrive
- **HUD:** Radial fill ring around arcane gear icon; pulses gold when full; countdown ring while active

### 🎨 AI-Generated Fantasy Machinery HUD Sprites (Batch 1)

All HUD buttons and rails regenerated with deeper Fantasy Machinery detail:

| Sprite | Description |
|--------|-------------|
| `hud_top_rail.png` | Brass/copper top bar with gear mechanisms, rune engravings, crystal gauges |
| `hud_bottom_rail.png` | Dark iron/copper build shelf with gear mechanisms, riveted plates, arcane channels |
| `ui_button_primary.png` | Ornate brass frame with decorative gears, green arcane energy glow |
| `ui_button_primary_pressed.png` | Pressed state — brighter glow, inset shadow |
| `ui_button_secondary.png` | Dark bronze/copper frame with subtle gear engravings |
| `ui_button_secondary_pressed.png` | Pressed state — brighter blue metallic, inset shadow |
| `ui_button_accent.png` | Brass frame with purple arcane crystal inlays, mystical rune edges |
| `ui_button_accent_pressed.png` | Pressed state — brighter purple crystals, intense rune glow |
| `ui_button_warning.png` | Dark iron frame with red-orange ember glow, hazard stripes, steam vents |
| `ui_button_warning_pressed.png` | Pressed state — brighter ember, visible steam particles |

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
- [ ] `ui_panel.png` / `ui_panel_active.png` — inspection panel backgrounds
- [ ] `ui_card.png` / `ui_card_active.png` — perk/evolution card backgrounds
- [ ] `ui_modal.png` — full-screen modal overlay
- [ ] `ui_build_slot.png` / `ui_build_slot_selected.png` — build shelf card frames
- [ ] `ui_tab.png` / `ui_tab_selected.png` — bottom shelf tab skins
- [ ] `ui_stat_frame.png` — resource stat chip frame
- [ ] `ui_banner.png` — floating notification banner
- [ ] Resource icons (Blocks, Core, Wave) — enhanced fantasy machinery style
- [ ] Phase status icons (Route, Build, Wave, etc.)

# Batch 17 / 1.3 Phase B — enemy death & hurt (runtime FX)

No new drawable strips. Uses existing walk frames + runtime:

- **Hurt flash**: longer/brighter white rim on hit
- **Death dissolve**: ~0.28s (elite 0.38 / boss 0.55) fade + shrink; frame C held; not targetable while dying
- **Boss ability tell**: windup ring in Kotlin source (smali APK includes death/hurt; tell ships with full rebuild path)

Rewards still grant at death start so gold/SFX feel instant.

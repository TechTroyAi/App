package ai.techtroy.blockhold

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal data class SpriteStrip(val bitmap: Bitmap, val frameCount: Int) {
    val frameWidth: Int = bitmap.width / frameCount

    init {
        check(frameCount >= 1 && bitmap.width % frameCount == 0) { "Invalid sprite strip dimensions" }
    }
}

/** Loads the compact, offline original sprite layer once for the lifetime of the game view. */
internal class SpriteCatalog(private val context: Context) {
    private val resources = context.resources
    private val options = BitmapFactory.Options().apply { inScaled = false }

    val grass = load("terrain_grass")
    val path = load("terrain_path")

    private val enemies = mapOf(
        EnemyKind.MOSSER to loadStrip("enemy_mosser"),
        EnemyKind.RUNNER to loadStrip("enemy_runner"),
        EnemyKind.BRUTE to loadStrip("enemy_brute"),
        EnemyKind.SHELLBACK to loadStrip("enemy_shellback"),
        EnemyKind.SPLITLING to loadStrip("enemy_splitling"),
        EnemyKind.SAPPER to loadStrip("enemy_sapper"),
        EnemyKind.MYCELIAL to loadStrip("enemy_mycelial"),
        EnemyKind.NEEDLEFLY to loadStrip("enemy_needlefly"),
        EnemyKind.GLOOMKIN to loadStrip("enemy_gloomkin"),
        EnemyKind.CARRION_HULK to loadStrip("enemy_carrion"),
        EnemyKind.IRONHIDE to loadStrip("enemy_ironhide"),
        EnemyKind.BLINK_STALKER to loadStrip("enemy_blink"),
        EnemyKind.ROOTCALLER to loadStrip("enemy_rootcaller"),
        EnemyKind.HEX_WEAVER to loadStrip("enemy_hex"),
        EnemyKind.SIEGE_COLOSSUS to loadStrip("enemy_siege"),
        EnemyKind.THORNBACK to loadStrip("enemy_thornback"),
        EnemyKind.OVERGROWTH to loadStrip("enemy_overgrowth"),
        EnemyKind.GRAVE_MENDER to loadStrip("enemy_grave_mender"),
        EnemyKind.PYRE_WIGHT to loadStrip("enemy_pyre_wight"),
        EnemyKind.IRON_MONARCH to loadStrip("enemy_iron_monarch"),
        EnemyKind.SPORE_SOVEREIGN to loadStrip("enemy_spore_sovereign"),
        EnemyKind.BRIAR_MITE to loadStrip("enemy_briar_mite"),
        EnemyKind.RUST_TICK to loadStrip("enemy_rust_tick"),
        EnemyKind.DRIFT_SEED to loadStrip("enemy_drift_seed"),
        EnemyKind.HOLLOW_SHELL to loadStrip("enemy_hollow_shell"),
        EnemyKind.WISP_DRIFTER to loadStrip("enemy_wisp_drifter")
    )

    val towerBase = load("tower_bolt_base")
    val frostBase = load("tower_frost_base")
    val cannonBase = load("tower_cannon_base")
    val emberBase = load("tower_ember_base")
    val beaconBase = load("tower_beacon_base")
    val thornBase = load("tower_thorn_base")
    val lanceBase = load("tower_lance_base")
    val mireBase = load("tower_mire_base")
    val greenTurret = loadStrip("tower_bolt_turret")
    val paleTurret = loadStrip("tower_frost_turret")
    val cannonTurret = loadStrip("tower_cannon_turret")
    val emberFlame = loadStrip("tower_ember_flame")
    val beaconPulse = loadStrip("tower_beacon_pulse")
    val thornTurret = loadStrip("tower_thorn_turret")
    val lanceTurret = loadStrip("tower_lance_turret")
    val mireTurret = loadStrip("tower_mire_turret")

    private val traps = mapOf(
        TrapKind.SPIKE to loadStrip("trap_spike_bed"),
        TrapKind.ROOT to loadStrip("trap_root_snare"),
        TrapKind.EMBER to loadStrip("trap_ember_rune"),
        TrapKind.ARC to loadStrip("trap_arc_plate"),
        TrapKind.CRUSHER to loadStrip("trap_crusher_block")
    )

    val gatePart = loadStrip("landmark_gate")
    val corePart = loadStrip("landmark_core")

    private val corruptions = mapOf(
        CorruptionKind.SPORE_PATH to load("corruption_spore_path"),
        CorruptionKind.CARAPACE_GROWTH to load("corruption_carapace_growth"),
        CorruptionKind.HEX_BLOOM to load("corruption_hex_bloom"),
        CorruptionKind.BLINK_ROOT to load("corruption_blink_root"),
        CorruptionKind.THORN_SOIL to load("corruption_thorn_soil"),
        CorruptionKind.BROOD_NEST to load("corruption_brood_nest")
    )

    private val evolutions = mapOf(
        TowerEvolution.CHAIN_CONDUCTOR to load("evolution_chain_conductor"),
        TowerEvolution.RAIL_SPIRE to load("evolution_rail_spire"),
        TowerEvolution.BLIZZARD_LENS to load("evolution_blizzard_lens"),
        TowerEvolution.SHATTER_CRYSTAL to load("evolution_shatter_crystal"),
        TowerEvolution.SIEGE_MORTAR to load("evolution_siege_mortar"),
        TowerEvolution.GRAVITY_CANNON to load("evolution_gravity_cannon"),
        TowerEvolution.INFERNO_ENGINE to load("evolution_inferno_engine"),
        TowerEvolution.CINDER_REACTOR to load("evolution_cinder_reactor"),
        TowerEvolution.STORM_CHOIR to load("evolution_storm_choir"),
        TowerEvolution.HARMONY_NEXUS to load("evolution_harmony_nexus"),
        TowerEvolution.BRAMBLE_CROWN to load("evolution_bramble_crown"),
        TowerEvolution.VENOM_QUILL to load("evolution_venom_quill"),
        TowerEvolution.PRISM_RAIL to load("evolution_prism_rail"),
        TowerEvolution.SKEWER_ARRAY to load("evolution_skewer_array"),
        TowerEvolution.BOG_KING to load("evolution_bog_king"),
        TowerEvolution.TAR_FONT to load("evolution_tar_font")
    )

    private val craftedItems = mapOf(
        CraftedItem.CORE_PATCH to load("item_core_patch"),
        CraftedItem.RECOVERY_WRAP to load("item_recovery_wrap"),
        CraftedItem.PURIFIER_VIAL to load("item_purifier_vial"),
        CraftedItem.REFORGE_COUPLER to load("item_reforge_coupler"),
        CraftedItem.UTILITY_GEARSET to load("item_utility_gearset"),
        CraftedItem.SURVEY_LENS to load("item_survey_lens"),
        CraftedItem.TRAP_REFIT_KIT to load("item_trap_refit_kit"),
        CraftedItem.BLANK_SIGIL to load("item_blank_sigil"),
        CraftedItem.SPLINTER_BRACE to load("item_splinter_brace"),
        CraftedItem.RESIN_SEAL to load("item_resin_seal"),
        CraftedItem.COOLING_FLASK to load("item_cooling_flask"),
        CraftedItem.OVERCHARGE_CELL to load("item_overcharge_cell"),
        CraftedItem.FOCUS_LENS to load("item_focus_lens"),
        CraftedItem.SNAP_SPRING to load("item_snap_spring")
    )

    private val imbuements = mapOf(
        Imbuement.MIGHT to load("sigil_might"),
        Imbuement.TEMPO to load("sigil_tempo"),
        Imbuement.REACH to load("sigil_reach"),
        Imbuement.CLARITY to load("sigil_clarity"),
        Imbuement.ECHOES to load("sigil_echoes"),
        Imbuement.CONSERVATION to load("sigil_conservation"),
        Imbuement.WARD to load("sigil_ward"),
        Imbuement.LEECH to load("sigil_leech"),
        Imbuement.SURGE to load("sigil_surge")
    )

    val salvageParts = load("icon_salvage_parts")
    val growthEssence = load("icon_growth_essence")
    val forgeCache = load("icon_forge_cache")

    private val utilities = mapOf(
        UtilityKind.BLOCK_GENERATOR to loadStrip("utility_block_generator"),
        UtilityKind.CACHE_DEPOT to loadStrip("utility_cache_depot"),
        UtilityKind.FORGE_WORKSHOP to loadStrip("utility_forge_workshop"),
        UtilityKind.PURIFIER_TOTEM to loadStrip("utility_purifier_totem"),
        UtilityKind.SURVEYOR_STATION to loadStrip("utility_surveyor_station"),
        UtilityKind.REFORGE_ANCHOR to loadStrip("utility_reforge_anchor"),
        UtilityKind.SALVAGE_YARD to loadStrip("utility_salvage_yard"),
        UtilityKind.WARD_BEACON to loadStrip("utility_ward_beacon"),
        UtilityKind.BATTLE_BANNER to loadStrip("utility_battle_banner"),
        UtilityKind.ESSENCE_STILL to loadStrip("utility_essence_still"),
        UtilityKind.TRAP_LATTICE to loadStrip("utility_trap_lattice")
    )

    private val projectiles = mapOf(
        TowerKind.BOLT to loadStrip("projectile_bolt"),
        TowerKind.FROST to loadStrip("projectile_frost"),
        TowerKind.CANNON to loadStrip("projectile_cannon"),
        TowerKind.EMBER to loadStrip("projectile_ember"),
        TowerKind.BEACON to loadStrip("projectile_beacon"),
        TowerKind.THORN to loadStrip("projectile_thorn"),
        TowerKind.LANCE to loadStrip("projectile_lance"),
        TowerKind.MIRE to loadStrip("projectile_mire")
    )

    private val impacts = mapOf(
        TowerKind.BOLT to loadStrip("impact_bolt"),
        TowerKind.FROST to loadStrip("impact_frost"),
        TowerKind.CANNON to loadStrip("impact_cannon"),
        TowerKind.EMBER to loadStrip("impact_ember"),
        TowerKind.BEACON to loadStrip("impact_beacon"),
        TowerKind.THORN to loadStrip("impact_thorn"),
        TowerKind.LANCE to loadStrip("impact_lance"),
        TowerKind.MIRE to loadStrip("impact_mire")
    )

    fun enemy(kind: EnemyKind): SpriteStrip = enemies.getValue(kind)

    fun trap(kind: TrapKind): SpriteStrip = traps.getValue(kind)

    fun utility(kind: UtilityKind): SpriteStrip = utilities.getValue(kind)

    fun projectile(kind: TowerKind): SpriteStrip = projectiles.getValue(kind)

    fun impact(kind: TowerKind): SpriteStrip = impacts.getValue(kind)

    fun corruption(kind: CorruptionKind): Bitmap = corruptions.getValue(kind)

    fun evolution(kind: TowerEvolution): Bitmap = evolutions.getValue(kind)

    fun craftedItem(kind: CraftedItem): Bitmap = craftedItems.getValue(kind)

    fun imbuement(kind: Imbuement): Bitmap = imbuements.getValue(kind)

    private fun loadStrip(name: String): SpriteStrip {
        val bitmap = load(name)
        check(bitmap.width % bitmap.height == 0) { "Sprite strip $name must contain square horizontal frames" }
        return SpriteStrip(bitmap, bitmap.width / bitmap.height)
    }

    private fun load(name: String): Bitmap {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        check(id != 0) { "Missing sprite resource: $name" }
        return BitmapFactory.decodeResource(resources, id, options)
    }
}

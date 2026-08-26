package ai.techtroy.blockhold

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Loads the compact, offline CC0 sprite layer once for the lifetime of the game view. */
internal class SpriteCatalog(private val context: Context) {
    private val resources = context.resources
    private val options = BitmapFactory.Options().apply { inScaled = false }

    val grass = load("terrain_grass")
    val path = load("terrain_path")

    private val enemies = mapOf(
        EnemyKind.MOSSER to load("enemy_mosser"),
        EnemyKind.RUNNER to load("enemy_runner"),
        EnemyKind.BRUTE to load("enemy_brute"),
        EnemyKind.SHELLBACK to load("enemy_shellback"),
        EnemyKind.SPLITLING to load("enemy_splitling"),
        EnemyKind.IRONHIDE to load("enemy_ironhide"),
        EnemyKind.BLINK_STALKER to load("enemy_blink"),
        EnemyKind.ROOTCALLER to load("enemy_rootcaller"),
        EnemyKind.HEX_WEAVER to load("enemy_hex"),
        EnemyKind.SIEGE_COLOSSUS to load("enemy_siege"),
        EnemyKind.OVERGROWTH to load("enemy_overgrowth")
    )

    val towerBase = load("tower_bolt_base")
    val frostBase = load("kenney_td_226")
    val cannonBase = load("kenney_td_227")
    val emberBase = load("kenney_td_228")
    val beaconBase = load("kenney_td_229")
    val greenTurret = load("tower_bolt_turret")
    val paleTurret = load("kenney_td_292")
    val cannonTurret = load("kenney_td_250")
    val emberFlame = load("kenney_td_298")
    val beaconPulse = load("kenney_td_256")

    val spikeTrap = load("trap_spike_bed")
    val rootTrap = load("trap_root_snare")
    val emberTrap = load("trap_ember_rune")
    val arcTrap = load("trap_arc_plate")
    val crusherTrap = load("trap_crusher_block")

    val gatePart = load("landmark_gate")
    val corePart = load("landmark_core")

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
        TowerEvolution.HARMONY_NEXUS to load("evolution_harmony_nexus")
    )

    private val craftedItems = mapOf(
        CraftedItem.CORE_PATCH to load("item_core_patch"),
        CraftedItem.RECOVERY_WRAP to load("item_recovery_wrap"),
        CraftedItem.PURIFIER_VIAL to load("item_purifier_vial"),
        CraftedItem.REFORGE_COUPLER to load("item_reforge_coupler"),
        CraftedItem.UTILITY_GEARSET to load("item_utility_gearset"),
        CraftedItem.SURVEY_LENS to load("item_survey_lens"),
        CraftedItem.TRAP_REFIT_KIT to load("item_trap_refit_kit"),
        CraftedItem.BLANK_SIGIL to load("item_blank_sigil")
    )

    private val imbuements = mapOf(
        Imbuement.MIGHT to load("sigil_might"),
        Imbuement.TEMPO to load("sigil_tempo"),
        Imbuement.REACH to load("sigil_reach"),
        Imbuement.CLARITY to load("sigil_clarity"),
        Imbuement.ECHOES to load("sigil_echoes"),
        Imbuement.CONSERVATION to load("sigil_conservation")
    )

    val salvageParts = load("icon_salvage_parts")
    val growthEssence = load("icon_growth_essence")
    val forgeCache = load("icon_forge_cache")

    private val utilities = mapOf(
        UtilityKind.BLOCK_GENERATOR to load("utility_block_generator"),
        UtilityKind.CACHE_DEPOT to load("utility_cache_depot"),
        UtilityKind.FORGE_WORKSHOP to load("utility_forge_workshop"),
        UtilityKind.PURIFIER_TOTEM to load("utility_purifier_totem"),
        UtilityKind.SURVEYOR_STATION to load("utility_surveyor_station"),
        UtilityKind.REFORGE_ANCHOR to load("utility_reforge_anchor"),
        UtilityKind.SALVAGE_YARD to load("utility_salvage_yard")
    )

    fun enemy(kind: EnemyKind): Bitmap = enemies.getValue(kind)

    fun utility(kind: UtilityKind): Bitmap = utilities.getValue(kind)

    fun corruption(kind: CorruptionKind): Bitmap = corruptions.getValue(kind)

    fun evolution(kind: TowerEvolution): Bitmap = evolutions.getValue(kind)

    fun craftedItem(kind: CraftedItem): Bitmap = craftedItems.getValue(kind)

    fun imbuement(kind: Imbuement): Bitmap = imbuements.getValue(kind)

    private fun load(name: String): Bitmap {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        check(id != 0) { "Missing sprite resource: $name" }
        return BitmapFactory.decodeResource(resources, id, options)
    }
}

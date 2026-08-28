package ai.techtroy.blockhold

import android.graphics.Color
import kotlin.math.min
import kotlin.math.pow

internal enum class GamePhase {
    TITLE,
    CHALLENGE_MENU,
    DIG,
    BUILD,
    REFORGE,
    PERK_DRAFT,
    EVOLUTION_DRAFT,
    WORKSHOP,
    WAVE,
    PAUSED,
    VICTORY,
    GAME_OVER
}

internal enum class GameMode {
    ENDLESS,
    DAILY,
    CUSTOM
}

internal enum class ChallengeModifier(val title: String, val description: String) {
    NONE("OPEN FORGE", "Standard endless rules"),
    TRAPS_ONLY("TRAPS ONLY", "Towers cannot be constructed"),
    TOWERS_ONLY("TOWERS ONLY", "Traps cannot be constructed"),
    NO_RECYCLING("NO RECYCLING", "Every placement is permanent"),
    DOUBLE_CORRUPTION("DEEP CORRUPTION", "Bosses spread twice as many growths"),
    ARMORED_HORDE("IRON HORDE", "Every enemy gains additional armor"),
    RUSH_HOUR("RUSH HOUR", "Enemies move and deploy faster"),
    SHORT_ROUTE("TIGHT FORGE", "The route is limited to sixteen blocks"),
    FRAGILE_CORE("GLASS CORE", "Begin with only five core health")
}

internal enum class BuildPage {
    TOWERS,
    TRAPS,
    UTILITIES,
    CACHE
}

internal enum class BuildTool(val title: String, val cost: Int) {
    DIG("DIG", 0),
    BOLT("BOLT", 70),
    FROST("FROST", 90),
    CANNON("CANNON", 125),
    EMBER("EMBER", 145),
    BEACON("BEACON", 170),
    // 1.4 F4 New guns — keep before traps so tower ordinals stay contiguous
    THORN("THORN", 95),
    LANCE("LANCE", 155),
    MIRE("MIRE", 135),
    GALE("GALE", 140),
    SUNFORGE("SUNFORGE", 160),
    LODESTONE("LODESTONE", 150),
    HOWL("HOWL", 145),
    VITRIOL("VITRIOL", 155),
    SPIKES("SPIKES", 45),
    ROOT("ROOT", 65),
    RUNE("RUNE", 85),
    ARC("ARC", 110),
    CRUSHER("CRUSHER", 145)
}

internal enum class TowerKind(
    val title: String,
    val cost: Int,
    val range: Float,
    val damage: Float,
    val interval: Float,
    val projectileSpeed: Float,
    val accent: Int
) {
    BOLT("Bolt Tower", 70, 2.8f, 24f, 0.62f, 8.5f, Color.rgb(190, 244, 78)),
    FROST("Frost Prism", 90, 2.55f, 13f, 0.88f, 7.2f, Color.rgb(93, 220, 255)),
    CANNON("Core Cannon", 125, 2.45f, 46f, 1.38f, 5.2f, Color.rgb(255, 164, 75)),
    EMBER("Ember Forge", 145, 2.65f, 31f, 0.78f, 6.4f, Color.rgb(255, 91, 60)),
    BEACON("Resonance Beacon", 170, 3.05f, 18f, 0.96f, 10.2f, Color.rgb(195, 120, 255)),
    // 1.4 F4 New guns
    THORN("Thorn Spire", 95, 2.75f, 20f, 0.55f, 8.8f, Color.rgb(120, 210, 90)),
    LANCE("Shard Lance", 155, 3.35f, 52f, 1.45f, 11.5f, Color.rgb(140, 220, 255)),
    MIRE("Mire Spout", 135, 2.40f, 28f, 1.05f, 5.8f, Color.rgb(70, 190, 150)),
    // 1.4 F8a towers
    GALE("Gale Spire", 140, 2.90f, 22f, 0.70f, 9.0f, Color.rgb(100, 200, 230)),
    SUNFORGE("Sunforge", 160, 2.70f, 36f, 0.92f, 7.5f, Color.rgb(255, 150, 40)),
    LODESTONE("Lodestone", 150, 2.60f, 26f, 0.85f, 6.8f, Color.rgb(150, 100, 220)),
    // 1.4 F8b towers
    HOWL("Howl Spire", 145, 3.10f, 18f, 0.80f, 9.5f, Color.rgb(120, 190, 230)),
    VITRIOL("Vitriol Spout", 155, 2.50f, 30f, 1.00f, 6.5f, Color.rgb(140, 200, 50))
}

internal enum class TowerEvolution(
    val title: String,
    val description: String,
    val kind: TowerKind
) {
    CHAIN_CONDUCTOR("Chain Conductor", "Bolts jump through grouped enemies", TowerKind.BOLT),
    RAIL_SPIRE("Rail Spire", "Armor-piercing shots strike a route line", TowerKind.BOLT),
    BLIZZARD_LENS("Blizzard Lens", "Frost creates a slowing impact field", TowerKind.FROST),
    SHATTER_CRYSTAL("Shatter Crystal", "Already-frozen targets take critical damage", TowerKind.FROST),
    SIEGE_MORTAR("Siege Mortar", "Massive range and blast radius", TowerKind.CANNON),
    GRAVITY_CANNON("Gravity Cannon", "Explosions drag enemies backward", TowerKind.CANNON),
    INFERNO_ENGINE("Inferno Engine", "Stronger burns spread through the horde", TowerKind.EMBER),
    CINDER_REACTOR("Cinder Reactor", "Nearby traps receive an Ember power boost", TowerKind.EMBER),
    STORM_CHOIR("Storm Choir", "Resonance reaches a much larger chain", TowerKind.BEACON),
    HARMONY_NEXUS("Harmony Nexus", "Nearby towers fire faster and resist Hex", TowerKind.BEACON),
    // F4 evolutions
    BRAMBLE_CROWN("Bramble Crown", "Marks last longer and stack bonus damage", TowerKind.THORN),
    VENOM_QUILL("Venom Quill", "Marked foes also burn for a short tick", TowerKind.THORN),
    PRISM_RAIL("Prism Rail", "Lance pierces farther along the route", TowerKind.LANCE),
    SKEWER_ARRAY("Skewer Array", "Lance hits a second high-progress target", TowerKind.LANCE),
    BOG_KING("Bog King", "Mire pools are larger and stickier", TowerKind.MIRE),
    TAR_FONT("Tar Font", "Mire also briefly stuns the primary target", TowerKind.MIRE),
    // F8a
    CYCLONE_CROWN("Cyclone Crown", "Gale gusts shove farther along the route", TowerKind.GALE),
    SHEAR_BLADE("Shear Blade", "Gale cuts a second nearby foe", TowerKind.GALE),
    SOLAR_FLARE("Solar Flare", "Sunforge burns spread on impact", TowerKind.SUNFORGE),
    HEARTH_CORE("Hearth Core", "Sunforge shots hit harder up close", TowerKind.SUNFORGE),
    PULL_WELL("Pull Well", "Lodestone yanks foes backward harder", TowerKind.LODESTONE),
    REPULSOR("Repulsor", "Lodestone pulses a brief stun on hit", TowerKind.LODESTONE),
    // F8b
    PACK_CALL("Pack Call", "Howl also slows a second nearby foe", TowerKind.HOWL),
    ECHO_VAULT("Echo Vault", "Howl reveal lasts longer", TowerKind.HOWL),
    ACID_VEIN("Acid Vein", "Vitriol shred stacks higher", TowerKind.VITRIOL),
    RUST_SCOUR("Rust Scour", "Shredded foes take a brief burn tick", TowerKind.VITRIOL);

    companion object {
        fun choices(kind: TowerKind): List<TowerEvolution> = values().filter { it.kind == kind }
    }
}

internal enum class TrapKind(
    val title: String,
    val cost: Int,
    val damage: Float,
    val accent: Int
) {
    SPIKE("Spike Bed", 45, 38f, Color.rgb(225, 235, 216)),
    ROOT("Root Snare", 65, 20f, Color.rgb(91, 196, 99)),
    EMBER("Ember Rune", 85, 34f, Color.rgb(255, 104, 55)),
    ARC("Arc Plate", 110, 48f, Color.rgb(92, 224, 255)),
    CRUSHER("Crusher Block", 145, 94f, Color.rgb(190, 164, 130))
}

internal enum class UtilityKind(
    val title: String,
    val description: String,
    val cost: Int,
    val accent: Int
) {
    BLOCK_GENERATOR("Block Generator", "Produces Blocks after every cleared wave", 200, Color.rgb(255, 203, 81)),
    CACHE_DEPOT("Cache Depot", "Expands trap storage and lowers recovery fees", 180, Color.rgb(93, 220, 255)),
    FORGE_WORKSHOP("Forge Workshop", "Repairs, fabricates supplies, and binds sigils", 220, Color.rgb(255, 157, 84)),
    PURIFIER_TOTEM("Purifier Totem", "Reduces nearby corruption cleansing costs", 210, Color.rgb(112, 231, 143)),
    SURVEYOR_STATION("Surveyor Station", "Reveals approaching waves and elite threats", 130, Color.rgb(151, 204, 255)),
    REFORGE_ANCHOR("Reforge Anchor", "Reduces the cost of nearby route changes", 190, Color.rgb(195, 120, 255)),
    SALVAGE_YARD("Salvage Yard", "Improves recycling returns and recovered Parts", 160, Color.rgb(202, 177, 137)),
    // 1.4 F3 Toolkit utilities
    WARD_BEACON("Ward Beacon", "Nearby towers shrug off Hex faster and gain brief immunity pulses", 200, Color.rgb(112, 220, 200)),
    BATTLE_BANNER("Battle Banner", "Towers near the banner deal extra damage during waves", 175, Color.rgb(255, 140, 70)),
    ESSENCE_STILL("Essence Still", "Converts wave pressure into Growth Essence over time", 230, Color.rgb(180, 120, 255)),
    TRAP_LATTICE("Trap Lattice", "Path traps near the lattice deal more damage", 185, Color.rgb(200, 210, 170)),
    // 1.4 F7 utilities
    AEGIS_PYLON("Aegis Pylon", "Nearby towers take shorter Hex and gain a light shield pulse", 195, Color.rgb(100, 190, 220)),
    GROWTH_NURSERY("Growth Nursery", "Cleared waves yield bonus Growth Essence", 170, Color.rgb(120, 210, 100)),
    PATH_WARDEN("Path Warden", "Enemies on nearby path tiles move slightly slower", 165, Color.rgb(180, 160, 90)),
    SPARK_RELAY("Spark Relay", "Nearby towers fire a touch faster", 190, Color.rgb(255, 210, 70)),
    BOUNTY_BOARD("Bounty Board", "Kills near the board grant a small Blocks bonus", 150, Color.rgb(230, 190, 80)),
    CINDER_KILN("Cinder Kiln", "Nearby Ember traps and Ember towers burn hotter", 180, Color.rgb(255, 120, 50))
}

internal enum class CraftedItem(
    val title: String,
    val description: String,
    val blockCost: Int,
    val partCost: Int,
    val essenceCost: Int,
    val workshopLevel: Int,
    val maxStack: Int
) {
    CORE_PATCH("Core Patch", "Use between waves to repair one Core health", 60, 3, 0, 1, 3),
    RECOVERY_WRAP("Recovery Wrap", "Automatically makes the next trap recovery free", 40, 2, 0, 2, 3),
    PURIFIER_VIAL("Purifier Vial", "Automatically reduces the next cleanse by two Forge", 80, 2, 1, 2, 3),
    REFORGE_COUPLER("Reforge Coupler", "Automatically reduces the next Reforge by two Forge", 120, 4, 0, 2, 3),
    UTILITY_GEARSET("Utility Gearset", "Automatically halves the next utility upgrade cost", 150, 5, 0, 2, 3),
    SURVEY_LENS("Survey Lens", "Use to reveal the next three wave themes", 50, 2, 0, 2, 3),
    TRAP_REFIT_KIT("Trap Refit Kit", "Use to upgrade the lowest-ranked cached trap", 100, 4, 0, 2, 3),
    BLANK_SIGIL("Blank Sigil", "Required to bind one persistent run imbuement", 100, 5, 1, 3, 6),

    // 1.4 Crafts C1 — panic forge kit
    SPLINTER_BRACE("Splinter Brace", "Next Core hit that would deal 2+ damage loses 1 damage", 90, 4, 1, 2, 2),
    RESIN_SEAL("Resin Seal", "Arm a one-leak Core barrier for the next combat wave", 110, 3, 1, 2, 2),
    COOLING_FLASK("Cooling Flask", "Clear Hex on a selected tower and grant brief Hex immunity", 70, 3, 0, 2, 3),

    // 1.4 Crafts C2 — precision forge kit
    OVERCHARGE_CELL("Overcharge Cell", "Selected tower gains a temporary damage surge", 100, 4, 1, 2, 2),
    FOCUS_LENS("Focus Lens", "Selected tower gains temporary range and pierce", 90, 3, 1, 2, 2),
    SNAP_SPRING("Snap Spring", "Selected trap resets triggers and pulses once immediately", 80, 3, 0, 2, 3),

    // 1.4 Crafts C3 — field forge kit
    SURVEY_SPIKE("Survey Spike", "Pin the next wave theme and gain a small Blocks bounty", 70, 2, 0, 2, 3),
    ROUTE_OIL("Route Oil", "Path enemies near the gate move slower for one wave", 85, 3, 1, 2, 2),
    SALT_BUNDLE("Salt Bundle", "Cleanse nearest corruption free and purge nearby Hex", 95, 2, 1, 2, 2),
    SCRAP_MAGNET("Scrap Magnet", "Salvage bonus Parts from the next three kills", 60, 2, 0, 2, 3)
}

internal enum class Imbuement(val title: String, val description: String, val accent: Int) {
    MIGHT("Might", "Improves damage or utility output by 15 percent", Color.rgb(255, 104, 82)),
    TEMPO("Tempo", "Accelerates attacks, statuses, and production cycles", Color.rgb(255, 215, 86)),
    REACH("Reach", "Expands tower, chain, and utility operating radius", Color.rgb(93, 220, 255)),
    CLARITY("Clarity", "Greatly resists Hex disabling", Color.rgb(234, 244, 255)),
    ECHOES("Echoes", "Every fifth activation repeats part of its effect", Color.rgb(195, 120, 255)),
    CONSERVATION("Conservation", "Reduces upgrade, storage, and activation costs", Color.rgb(128, 230, 146)),
    // 1.4 F3 imbuements
    WARD("Ward", "Structure resists Hex and recovers disabled time faster", Color.rgb(140, 230, 210)),
    LEECH("Leech", "Damaging hits restore a sliver of Core when enemies die nearby", Color.rgb(255, 110, 130)),
    SURGE("Surge", "First few activations each wave hit harder", Color.rgb(255, 210, 80)),
    // 1.4 F6 imbuement pack
    BULWARK("Bulwark", "Structure gains bonus armor against elite and boss pressure", Color.rgb(90, 140, 200)),
    HARVEST("Harvest", "Kills near this structure grant a small Blocks trickle", Color.rgb(210, 180, 70)),
    // 1.4 F7 imbuement pack
    VOLLEY("Volley", "Every few shots fire a bonus fragment at a second target", Color.rgb(255, 160, 70)),
    SIEGE("Siege", "Bonus damage against elites and bosses", Color.rgb(220, 100, 50)),
    FORTUNE("Fortune", "Kills have a chance to drop extra Blocks", Color.rgb(255, 210, 90)),
    BINDING("Binding", "Hits apply a brief root snare", Color.rgb(90, 180, 110))
}

internal enum class WorkshopTab {
    CRAFT,
    IMBUE,
    SUPPLIES
}

internal enum class PerkCategory {
    TOWER,
    TRAP,
    ECONOMY,
    CORE,
    ROUTE
}

internal enum class ForgePerk(val title: String, val description: String) {
    FORKED_BOLTS("Forked Bolts", "Bolt hits jump to another enemy"),
    CANNON_SHRAPNEL("Cannon Shrapnel", "Cannon blasts grow and deal more damage"),
    LINGERING_FROST("Lingering Frost", "Frost slows last significantly longer"),
    SPREADING_EMBERS("Spreading Embers", "Burning damage spreads to nearby enemies"),
    RESONANCE_FEEDBACK("Resonance Feedback", "Beacon attacks can immediately echo"),

    DOUBLE_TRIGGER("Double Trigger", "Every trap may activate an additional time"),
    REINFORCED_CRUSHERS("Reinforced Crushers", "Crusher damage is increased by 50 percent"),
    BURNING_ROOTS("Burning Roots", "Root Snares ignite their victims"),
    CONDUCTIVE_SPIKES("Conductive Spikes", "Spike Beds arc damage to a nearby enemy"),
    EXPANDED_ARC("Expanded Arc", "Arc Plates reach additional targets"),

    BETTER_RECYCLING("Better Recycling", "Recycling returns 75 percent of investment"),
    ELITE_BOUNTIES("Elite Bounties", "Elites and bosses award 50 percent more Blocks"),
    COMPOUND_BLOCKS("Compound Blocks", "Wave-clear Block rewards increase by 20 percent"),
    EFFICIENT_OVERCHARGE("Efficient Overcharge", "Overcharge purchases cost 20 percent less"),
    LONG_ROAD_DIVIDEND("Long Road Dividend", "Long routes generate additional wave income"),

    EMERGENCY_SHIELD("Emergency Shield", "Increase maximum core health and repair it"),
    CORE_REGENERATION("Core Regeneration", "Repair core health after every five waves"),
    LAST_BASTION("Last Bastion", "Towers near the core fire faster"),
    PHASE_BARRIER("Phase Barrier", "Prevent one enemy breach"),
    BOSS_HARVEST("Boss Harvest", "Bosses grant extra Forge Charges and Evolution Cores"),

    FORGE_MASTERY("Forge Mastery", "Gain Forge Charges now and after every boss"),
    DEEP_ROUTE("Deep Route", "Increase the route-length limit by eight"),
    CORRUPTION_WARD("Corruption Ward", "Bosses create fewer corrupted blocks"),
    CORNER_AMBUSH("Corner Ambush", "Enemies on route corners take bonus damage"),
    PATHFINDER_TRAPS("Pathfinder Traps", "Traps strengthen as the route becomes longer");

    val category: PerkCategory
        get() = when (ordinal / 5) {
            0 -> PerkCategory.TOWER
            1 -> PerkCategory.TRAP
            2 -> PerkCategory.ECONOMY
            3 -> PerkCategory.CORE
            else -> PerkCategory.ROUTE
        }
}

internal enum class CorruptionKind(val title: String, val pathPreferred: Boolean, val accent: Int) {
    SPORE_PATH("Spore Path", true, Color.rgb(89, 211, 113)),
    CARAPACE_GROWTH("Carapace Growth", true, Color.rgb(119, 145, 161)),
    HEX_BLOOM("Hex Bloom", false, Color.rgb(193, 91, 232)),
    BLINK_ROOT("Blink Root", true, Color.rgb(115, 89, 196)),
    THORN_SOIL("Thorn Soil", false, Color.rgb(68, 151, 79)),
    BROOD_NEST("Brood Nest", false, Color.rgb(222, 119, 157));

    val description: String
        get() = when (this) {
            SPORE_PATH -> "Regenerates enemies crossing this path block"
            CARAPACE_GROWTH -> "Temporarily hardens enemies with heavy armor"
            HEX_BLOOM -> "Periodically disables the nearest tower"
            BLINK_ROOT -> "Warps each enemy forward once"
            THORN_SOIL -> "Makes tower construction on this soil more expensive"
            BROOD_NEST -> "Adds Splitlings to every future wave"
        }
}

internal enum class EnemyKind(
    val title: String,
    val baseHealth: Float,
    val speed: Float,
    val bounty: Int,
    val baseDamage: Int,
    val color: Int,
    val scale: Float,
    val armor: Float = 0f,
    val regeneration: Float = 0f,
    val elite: Boolean = false,
    val boss: Boolean = false
) {
    MOSSER("Mosser", 74f, 0.82f, 13, 1, Color.rgb(91, 178, 93), 0.73f),
    RUNNER("Runner", 60f, 1.31f, 15, 1, Color.rgb(242, 179, 72), 0.64f),
    BRUTE("Brute", 220f, 0.56f, 28, 2, Color.rgb(137, 104, 179), 0.92f),
    SHELLBACK("Shellback", 175f, 0.65f, 25, 2, Color.rgb(103, 145, 155), 0.86f, armor = 0.38f),
    SPLITLING("Splitling", 112f, 0.92f, 20, 1, Color.rgb(218, 119, 157), 0.76f),

    // 1.4 F1 New blood — normals with clear jobs
    SAPPER("Sapper", 95f, 0.88f, 18, 1, Color.rgb(168, 110, 55), 0.78f),
    MYCELIAL("Mycelial", 130f, 0.70f, 22, 1, Color.rgb(70, 190, 150), 0.80f, regeneration = 0.010f),
    NEEDLEFLY("Needlefly", 48f, 1.48f, 16, 1, Color.rgb(140, 200, 70), 0.62f),
    GLOOMKIN("Gloomkin", 88f, 0.78f, 19, 1, Color.rgb(120, 80, 180), 0.74f),
    CARRION_HULK("Carrion Hulk", 155f, 0.68f, 24, 2, Color.rgb(160, 70, 55), 0.88f),

    IRONHIDE("Ironhide Champion", 760f, 0.52f, 90, 3, Color.rgb(103, 120, 132), 1.05f, armor = 0.52f, elite = true),
    BLINK_STALKER("Blink Stalker", 470f, 0.93f, 82, 2, Color.rgb(94, 77, 127), 0.89f, elite = true),
    ROOTCALLER("Rootcaller", 610f, 0.59f, 88, 3, Color.rgb(62, 151, 83), 0.98f, regeneration = 0.012f, elite = true),
    HEX_WEAVER("Hex Weaver", 550f, 0.70f, 92, 2, Color.rgb(176, 82, 205), 0.94f, elite = true),
    SIEGE_COLOSSUS("Siege Colossus", 1180f, 0.38f, 125, 5, Color.rgb(193, 94, 66), 1.22f, armor = 0.25f, elite = true),
    // F1 elite — thorn crest: pulse-armor after each hit
    THORNBACK("Thornback", 820f, 0.48f, 100, 3, Color.rgb(70, 120, 75), 1.10f, armor = 0.28f, elite = true),

    OVERGROWTH("The Overgrowth", 1900f, 0.36f, 260, 7, Color.rgb(200, 64, 73), 1.42f, armor = 0.18f, regeneration = 0.006f, boss = true),
    // F2 named threats — ordinals 17+ (smali append after OVERGROWTH)
    GRAVE_MENDER("Grave Mender", 640f, 0.55f, 95, 2, Color.rgb(90, 200, 140), 1.00f, regeneration = 0.008f, elite = true),
    PYRE_WIGHT("Pyre Wight", 580f, 0.72f, 98, 3, Color.rgb(255, 100, 45), 0.96f, elite = true),
    IRON_MONARCH("Iron Monarch", 2400f, 0.32f, 320, 8, Color.rgb(120, 125, 140), 1.50f, armor = 0.30f, boss = true),
    SPORE_SOVEREIGN("Spore Sovereign", 2100f, 0.34f, 300, 7, Color.rgb(110, 70, 160), 1.48f, regeneration = 0.008f, boss = true),

    // 1.4 F5 Field pests — normals with clear jobs (append after F2 ordinals)
    BRIAR_MITE("Briar Mite", 52f, 1.22f, 14, 1, Color.rgb(90, 170, 70), 0.58f),
    RUST_TICK("Rust Tick", 78f, 0.95f, 17, 1, Color.rgb(200, 110, 55), 0.68f),
    DRIFT_SEED("Drift Seed", 42f, 1.10f, 15, 1, Color.rgb(180, 220, 120), 0.60f),
    HOLLOW_SHELL("Hollow Shell", 140f, 0.62f, 23, 2, Color.rgb(190, 185, 170), 0.84f, armor = 0.22f),
    WISP_DRIFTER("Wisp Drifter", 70f, 0.86f, 18, 1, Color.rgb(130, 150, 230), 0.66f),

    // 1.4 F6 named threats — elites + bosses
    VEIN_LURKER("Vein Lurker", 700f, 0.58f, 105, 3, Color.rgb(180, 40, 90), 1.08f, regeneration = 0.014f, elite = true),
    MIRROR_MOTH("Mirror Moth", 520f, 0.88f, 100, 2, Color.rgb(160, 200, 220), 0.98f, elite = true),
    TIDAL_ROOT("Tidal Root", 2600f, 0.30f, 340, 8, Color.rgb(40, 140, 130), 1.55f, armor = 0.22f, regeneration = 0.005f, boss = true),
    ASHEN_CHOIR("Ashen Choir", 2300f, 0.33f, 330, 7, Color.rgb(200, 160, 120), 1.52f, armor = 0.12f, boss = true)
}

internal data class GridCell(val col: Int, val row: Int)

internal class CorruptedCell(
    val id: Int,
    val cell: GridCell,
    val kind: CorruptionKind
) {
    var cooldown = 1.5f
}

internal data class SpawnSpec(
    val kind: EnemyKind,
    val healthScale: Float,
    val speedScale: Float,
    val rewardScale: Float,
    val bossTier: Int = 0,
    val splitDepth: Int = 0
)

internal class Tower(
    val col: Int,
    val row: Int,
    val kind: TowerKind
) {
    var level = 1
    var overcharge = 0
    var evolution: TowerEvolution? = null
    var imbuement: Imbuement? = null
    var activationCount = 0
    var cooldown = 0f
    var disabledTimer = 0f
    var angle = -1.5708f
    var recoil = 0f
    /** 1 → 0 after evolution confirm (E1 rim + emblem pop). */
    var evolveFlash = 0f
    /** Neighbor path sheen after evolve (E2), seconds remaining. */
    var evolveAura = 0f
    /** Hotter next shot trail while >0 (E2), seconds remaining. */
    var evolveProof = 0f
    /** F3 Overcharge Cell: damage surge remaining. */
    var damageBoostTimer = 0f
    /** F3 Focus Lens: range/pierce boost remaining. */
    var focusBoostTimer = 0f
    /** F3 Surge imbuement: activations this wave still boosted. */
    var surgeCharges = 0

    fun upgradeCost(): Int {
        val base = if (level < 3) kind.cost / 2 + level * 28 else min(2_000_000_000, (kind.cost * 0.82 * 1.24.pow(overcharge.toDouble())).toInt().coerceAtLeast(kind.cost))
        return if (imbuement == Imbuement.CONSERVATION) (base * 0.85f).toInt().coerceAtLeast(1) else base
    }

    fun sellValue(multiplier: Float = 0.60f): Int {
        val invested = kind.cost.toLong() + (level - 1).toLong() * (kind.cost / 2 + 54) + overcharge.toLong() * kind.cost
        return min(2_000_000_000L, (invested * multiplier).toLong()).toInt()
    }

    fun currentDamage(): Float {
        val evolutionMultiplier = when (evolution) {
            TowerEvolution.RAIL_SPIRE -> 1.45f
            TowerEvolution.SHATTER_CRYSTAL -> 1.18f
            TowerEvolution.SIEGE_MORTAR -> 1.22f
            TowerEvolution.INFERNO_ENGINE -> 1.18f
            TowerEvolution.STORM_CHOIR -> 1.12f
            TowerEvolution.BRAMBLE_CROWN -> 1.10f
            TowerEvolution.VENOM_QUILL -> 1.12f
            TowerEvolution.PRISM_RAIL -> 1.20f
            TowerEvolution.SKEWER_ARRAY -> 1.15f
            TowerEvolution.BOG_KING -> 1.18f
            TowerEvolution.TAR_FONT -> 1.12f
            else -> 1f
        }
        val imbuementMultiplier = if (imbuement == Imbuement.MIGHT) 1.15f else 1f
        var damage = kind.damage * (1f + (level - 1) * 0.38f) * (1f + overcharge * 0.12f) * evolutionMultiplier * imbuementMultiplier
        // F3 temporary boosts + Surge imbuement
        if (damageBoostTimer > 0f) damage *= 1.35f
        if (surgeCharges > 0) damage *= 1.28f
        return damage
    }

    fun currentRange(): Float {
        val evolutionBonus = when (evolution) {
            TowerEvolution.SIEGE_MORTAR -> kind.range * 0.38f
            TowerEvolution.STORM_CHOIR -> 0.50f
            TowerEvolution.HARMONY_NEXUS -> 0.35f
            TowerEvolution.PRISM_RAIL -> kind.range * 0.22f
            TowerEvolution.SKEWER_ARRAY -> 0.40f
            TowerEvolution.BOG_KING -> 0.25f
            else -> 0f
        }
        var range = kind.range + (level - 1) * 0.18f + min(0.65f, overcharge * 0.025f) + evolutionBonus + if (imbuement == Imbuement.REACH) 0.38f else 0f
        if (focusBoostTimer > 0f) range += 0.55f
        return range
    }

    fun currentInterval(): Float {
        val evolutionSpeed = when (evolution) {
            TowerEvolution.CHAIN_CONDUCTOR -> 0.88f
            TowerEvolution.BLIZZARD_LENS -> 0.90f
            TowerEvolution.CINDER_REACTOR -> 0.92f
            else -> 1f
        }
        val tempo = if (imbuement == Imbuement.TEMPO) 0.88f else 1f
        return kind.interval * evolutionSpeed * tempo / (1f + (level - 1) * 0.16f + min(1.1f, overcharge * 0.04f))
    }

    fun canEvolve(): Boolean = evolution == null && level >= 3 && overcharge >= 2

    fun rankLabel(): String = when {
        evolution != null -> evolution!!.title.toUpperCase()
        level < 3 -> "LEVEL $level"
        overcharge == 0 -> "LEVEL 3"
        else -> "OVERCHARGE $overcharge"
    }
}

internal class SpikeTrap(
    val id: Int,
    val col: Int,
    val row: Int,
    val kind: TrapKind
) {
    var level = 1
    var overcharge = 0
    var imbuement: Imbuement? = null
    var activationCount = 0
    var pulse = 0f
    /** F3 Surge imbuement: activations this wave still boosted. */
    var surgeCharges = 0
    /** F5 Briar Mite: seconds remaining while trap is jammed (won't trigger). */
    var jamTimer = 0f

    fun upgradeCost(): Int {
        val base = if (level < 3) kind.cost / 2 + level * 22 else min(2_000_000_000, (kind.cost * 0.80 * 1.22.pow(overcharge.toDouble())).toInt().coerceAtLeast(kind.cost))
        return if (imbuement == Imbuement.CONSERVATION) (base * 0.85f).toInt().coerceAtLeast(1) else base
    }

    fun sellValue(multiplier: Float = 0.60f): Int {
        val invested = kind.cost.toLong() + (level - 1).toLong() * (kind.cost / 2 + 40) + overcharge.toLong() * kind.cost
        return min(2_000_000_000L, (invested * multiplier).toLong()).toInt()
    }

    fun currentDamage(): Float {
        var damage = kind.damage * (1f + (level - 1) * 0.42f) * (1f + overcharge * 0.13f) * if (imbuement == Imbuement.MIGHT) 1.15f else 1f
        if (surgeCharges > 0) damage *= 1.28f
        return damage
    }

    fun rankLabel(): String = if (level < 3) "LEVEL $level" else if (overcharge == 0) "LEVEL 3" else "OVERCHARGE $overcharge"
}

internal data class StoredTrap(
    val kind: TrapKind,
    var level: Int = 1,
    var overcharge: Int = 0,
    var imbuement: Imbuement? = null
) {
    fun rankLabel(): String = if (level < 3) "LEVEL $level" else if (overcharge == 0) "LEVEL 3" else "OVERCHARGE $overcharge"
}

internal class Utility(
    val col: Int,
    val row: Int,
    val kind: UtilityKind
) {
    var level = 1
    var imbuement: Imbuement? = null
    var disabledTimer = 0f
    var activationCount = 0
    var productionProgress = 0

    fun upgradeCost(): Int {
        val base = kind.cost / 2 + level * 55
        return if (imbuement == Imbuement.CONSERVATION) (base * 0.85f).toInt().coerceAtLeast(1) else base
    }

    fun outputMultiplier(): Float = (1f + (level - 1) * 0.38f) * if (imbuement == Imbuement.MIGHT) 1.15f else 1f

    fun effectRadius(): Float = 2.2f + (level - 1) * 0.65f + if (imbuement == Imbuement.REACH) 0.75f else 0f

    fun cycleWaves(base: Int): Int {
        val improved = base - (level - 1) - if (imbuement == Imbuement.TEMPO) 1 else 0
        return improved.coerceAtLeast(1)
    }

    fun rankLabel(): String = "LEVEL $level"
}

internal class Enemy(
    val id: Int,
    val kind: EnemyKind,
    val healthScale: Float = 1f,
    val speedScale: Float = 1f,
    rewardScale: Float = 1f,
    val bossTier: Int = 0,
    val splitDepth: Int = 0
) {
    val maxHealth = kind.baseHealth * healthScale
    val moveSpeed = kind.speed * speedScale
    val bounty = (kind.bounty * rewardScale).toInt().coerceAtLeast(1)
    var health = maxHealth
    var progress = 0f
    var x = 0f
    var y = 0f
        var slowTimer = 0f
    /** F4 Thorn Spire: marked for bonus incoming damage. */
    var markTimer = 0f
    /** F8b Vitriol: temporary armor reduction (0–1). */
    var armorShred = 0f
    var armorShredTimer = 0f
var rootTimer = 0f
    var stunTimer = 0f
    var armoredTimer = 0f
    var burnTimer = 0f
    var burnDamagePerSecond = 0f
    var flashTimer = 0f
    /** >0 while playing death dissolve (1.3 Phase B). */
    var deathTimer = 0f
    var animation = (id * 1.618f) % 6.283185f
    var abilityTimer = 2.4f
    var alive = true
    var rewarded = false
    val trapTriggerCounts = HashMap<Int, Int>()
    val triggeredCorruptions = HashSet<Int>()
    /** F1 Sapper: traps triggered this life (disables after 2). */
    var sapperTraps = 0
    /** F1 Gloomkin: soft stealth remaining. */
    var gloomTimer = 0f
    /** F1 Thornback: brief armor pulse after taking a hit. */
    var thornArmorTimer = 0f
    /** F2: ability wind-up remaining (visual tell + delayed fire). */
    var windupTimer = 0f
    /** F2: which ability is charging (0 idle). */
    var windupKind = 0
    /** F2 Pyre Wight: self-burn trail duration. */
    var pyreTrailTimer = 0f
    /** F5 Hollow Shell: remaining shell HP buffer before real health. */
    var shellBuffer = 0f
    /** F5 Wisp Drifter: soft stealth remaining (same pipeline as gloom). */
    var wispTimer = 0f
    /** F6 Mirror Moth: reflect charges remaining this life. */
    var mirrorCharges = 0

    val dying: Boolean get() = deathTimer > 0f
    val targetable: Boolean get() = alive && !dying && health > 0f
    /** Soft stealth: towers still track; prioritization can skip. */
    val stealthed: Boolean get() = gloomTimer > 0.05f || wispTimer > 0.05f
}

internal class Projectile(
    var x: Float,
    var y: Float,
    val target: Enemy,
    val kind: TowerKind,
    val damage: Float,
    val speed: Float,
    val source: Tower,
    /** E2: fired while tower still has evolveProof — hotter trail/impact. */
    val evolveHot: Boolean = false
) {
    var alive = true
    var age = 0f
}

internal class Particle(
    var x: Float,
    var y: Float,
    var velocityX: Float,
    var velocityY: Float,
    var life: Float,
    val maxLife: Float,
    val color: Int,
    val size: Float,
    val square: Boolean
)

/** One-shot A/B/C hit burst at a grid point (Batch 16 / 1.3 Phase A). */
internal class ImpactFx(
    var x: Float,
    var y: Float,
    val kind: TowerKind,
    var age: Float = 0f,
    val duration: Float = 0.28f
) {
    val alive: Boolean get() = age < duration
}

internal class FloatingLabel(
    val message: String,
    var x: Float,
    var y: Float,
    val color: Int,
    var life: Float = 1.1f,
    val maxLife: Float = life,
    /** Visual emphasis — resource / forge pops use >1; APK default 1.2. */
    val pop: Float = 1.2f
)

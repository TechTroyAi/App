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

    val towerBase = load("kenney_td_203")
    val frostBase = load("kenney_td_226")
    val cannonBase = load("kenney_td_227")
    val emberBase = load("kenney_td_228")
    val beaconBase = load("kenney_td_229")
    val greenTurret = load("kenney_td_291")
    val paleTurret = load("kenney_td_292")
    val cannonTurret = load("kenney_td_250")
    val emberFlame = load("kenney_td_298")
    val beaconPulse = load("kenney_td_256")

    val spikeTrap = load("kenney_td_205")
    val rootTrap = load("kenney_td_121")
    val emberTrap = load("kenney_td_296")
    val arcTrap = load("kenney_td_203")
    val crusherTrap = load("kenney_td_126")

    val gatePart = load("kenney_td_226")
    val corePart = load("kenney_td_249")

    fun enemy(kind: EnemyKind): Bitmap = enemies.getValue(kind)

    private fun load(name: String): Bitmap {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        check(id != 0) { "Missing sprite resource: $name" }
        return BitmapFactory.decodeResource(resources, id, options)
    }
}

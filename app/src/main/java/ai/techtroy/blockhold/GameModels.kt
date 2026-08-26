package ai.techtroy.blockhold

import android.graphics.Color
import kotlin.math.min
import kotlin.math.pow

internal enum class GamePhase {
    TITLE,
    DIG,
    BUILD,
    WAVE,
    PAUSED,
    VICTORY,
    GAME_OVER
}

internal enum class BuildPage {
    TOWERS,
    TRAPS
}

internal enum class BuildTool(val title: String, val cost: Int) {
    DIG("DIG", 0),
    BOLT("BOLT", 70),
    FROST("FROST", 90),
    CANNON("CANNON", 125),
    EMBER("EMBER", 145),
    BEACON("BEACON", 170),
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
    BEACON("Resonance Beacon", 170, 3.05f, 18f, 0.96f, 10.2f, Color.rgb(195, 120, 255))
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

    IRONHIDE("Ironhide Champion", 760f, 0.52f, 90, 3, Color.rgb(103, 120, 132), 1.05f, armor = 0.52f, elite = true),
    BLINK_STALKER("Blink Stalker", 470f, 0.93f, 82, 2, Color.rgb(94, 77, 127), 0.89f, elite = true),
    ROOTCALLER("Rootcaller", 610f, 0.59f, 88, 3, Color.rgb(62, 151, 83), 0.98f, regeneration = 0.012f, elite = true),
    HEX_WEAVER("Hex Weaver", 550f, 0.70f, 92, 2, Color.rgb(176, 82, 205), 0.94f, elite = true),
    SIEGE_COLOSSUS("Siege Colossus", 1180f, 0.38f, 125, 5, Color.rgb(193, 94, 66), 1.22f, armor = 0.25f, elite = true),

    OVERGROWTH("The Overgrowth", 1900f, 0.36f, 260, 7, Color.rgb(200, 64, 73), 1.42f, armor = 0.18f, regeneration = 0.006f, boss = true)
}

internal data class GridCell(val col: Int, val row: Int)

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
    var cooldown = 0f
    var disabledTimer = 0f
    var angle = -1.5708f
    var recoil = 0f

    fun upgradeCost(): Int {
        if (level < 3) {
            return kind.cost / 2 + level * 28
        }
        return min(2_000_000_000, (kind.cost * 0.82 * 1.24.pow(overcharge.toDouble())).toInt().coerceAtLeast(kind.cost))
    }

    fun sellValue(): Int {
        val invested = kind.cost.toLong() + (level - 1).toLong() * (kind.cost / 2 + 54) + overcharge.toLong() * kind.cost
        return min(2_000_000_000L, (invested * 0.60).toLong()).toInt()
    }

    fun currentDamage(): Float {
        return kind.damage * (1f + (level - 1) * 0.38f) * (1f + overcharge * 0.12f)
    }

    fun currentRange(): Float {
        return kind.range + (level - 1) * 0.18f + min(0.65f, overcharge * 0.025f)
    }

    fun currentInterval(): Float {
        return kind.interval / (1f + (level - 1) * 0.16f + min(1.1f, overcharge * 0.04f))
    }

    fun rankLabel(): String = if (level < 3) "LEVEL $level" else if (overcharge == 0) "LEVEL 3" else "OVERCHARGE $overcharge"
}

internal class SpikeTrap(
    val id: Int,
    val col: Int,
    val row: Int,
    val kind: TrapKind
) {
    var level = 1
    var overcharge = 0
    var pulse = 0f

    fun upgradeCost(): Int {
        if (level < 3) {
            return kind.cost / 2 + level * 22
        }
        return min(2_000_000_000, (kind.cost * 0.80 * 1.22.pow(overcharge.toDouble())).toInt().coerceAtLeast(kind.cost))
    }

    fun sellValue(): Int {
        val invested = kind.cost.toLong() + (level - 1).toLong() * (kind.cost / 2 + 40) + overcharge.toLong() * kind.cost
        return min(2_000_000_000L, (invested * 0.60).toLong()).toInt()
    }

    fun currentDamage(): Float = kind.damage * (1f + (level - 1) * 0.42f) * (1f + overcharge * 0.13f)

    fun rankLabel(): String = if (level < 3) "LEVEL $level" else if (overcharge == 0) "LEVEL 3" else "OVERCHARGE $overcharge"
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
    var stunTimer = 0f
    var burnTimer = 0f
    var burnDamagePerSecond = 0f
    var flashTimer = 0f
    var animation = 0f
    var abilityTimer = 2.4f
    var alive = true
    var rewarded = false
    val triggeredTraps = HashSet<Int>()
}

internal class Projectile(
    var x: Float,
    var y: Float,
    val target: Enemy,
    val kind: TowerKind,
    val damage: Float,
    val speed: Float
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

internal class FloatingLabel(
    val message: String,
    var x: Float,
    var y: Float,
    val color: Int,
    var life: Float = 1.1f
)
